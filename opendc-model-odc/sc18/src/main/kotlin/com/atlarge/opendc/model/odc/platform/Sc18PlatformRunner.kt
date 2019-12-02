/*
 * MIT License
 *
 * Copyright (c) 2018 atlarge-research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.atlarge.opendc.model.odc.platform

import com.atlarge.opendc.model.odc.integration.csv.Sc18CsvWriter
import com.atlarge.opendc.model.odc.platform.workload.format.sgwf.SgwfParser
import com.atlarge.opendc.model.odc.topology.format.sc18.Sc18SetupParser
import com.atlarge.opendc.omega.OmegaKernel
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import mu.KotlinLogging
import java.nio.file.Paths
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicInteger
import javax.persistence.Persistence
import kotlin.coroutines.experimental.coroutineContext

/**
 * The main entry point of the program. This program polls experiments from a database and runs the
 * simulation and reports the results back to the database.
 *
 * @param args The command line arguments of the program.
 */
fun main(args: Array<String>) = SC18PlatformRunner().main(args)

/**
 * The command line executable program powered by the Clikt library for parsing the command line arguments.
 */
class SC18PlatformRunner : CliktCommand() {
    /**
     * This command line option specifies the path to the trace file in SGWF format to use for the simulation. See
     * [SgwfParser] for more information.
     */
    private val trace by argument(help = "The trace to run the simulation over").file(exists = true)

    /**
     * This command line option specifies the path to the datacenter setup file in JSON format. See [Sc18SetupParser]
     * for more information.
     */
    private val setup by option("-s", "--setup", help = "The datacenter setup file").file(exists = true).required()

    /**
     * This command line option specifies the list of schedulers to test in the experiments.
     */
    private val schedulers by option("--schedulers", help = "The list of schedulers to use").multiple(listOf(
        "SRTF-BESTFIT", "SRTF-FIRSTFIT", "SRTF-WORSTFIT", "SRTF-ROUNDROBIN",
        "FIFO-BESTFIT", "FIFO-FIRSTFIT", "FIFO-WORSTFIT", "FIFO-ROUNDROBIN",
        "RANDOM-BESTFIT", "RANDOM-FIRSTFIT", "RANDOM-WORSTFIT", "RANDOM-ROUNDROBIN",
        "PISA-BESTFIT", "PISA-FIRSTFIT", "PISA-WORSTFIT", "PISA-ROUNDROBIN",
        "HEFT", "CPOP", "FCP"
    ))

    /**
     * This command line option specifies the amount of times to repeat the experiment (default: 1).
     */
    private val repeat by option("-r", "--repeat", help = "The amount of times to repeat the experiment").int().default(1)

    /**
     * This command line option specifies the amount of times to run a warm-up experiments before running the actual
     * experiments and collecting the data (default: 0).
     */
    private val warmUp by option("-w", "--warmup", help = "The amount of times to warm up the simulator").int().default(0)

    /**
     * This command line option specifies the amount of parallelism to use for the experiments. Defaults to the amount
     * of processors available on the machine.
     */
    private val parallelism by option("-p", "--parallelism", help = "The parallelism level to use").int()
        .default(Runtime.getRuntime().availableProcessors())

    /**
     * The platform logger.
     */
    private val logger = KotlinLogging.logger {}

    /**
     * Run the command line application.
     */
    override fun run() = runBlocking {
        logger.info { "SC18 A Reference Architecture for Datacenter Scheduling" }
        logger.info { "See data/opendc.log for more detailed logging information."}
        logger.info { "Parsing input files [trace=$trace, setup=$setup]" }

        val trace = SgwfParser().parse(trace.inputStream())
        val path = Sc18SetupParser().parse(setup.inputStream())

        val factory = Persistence.createEntityManagerFactory("opendc-simulator")
        val manager = factory.createEntityManager()
        val pool = ForkJoinPool(parallelism)
        val dispatcher = pool.asCoroutineDispatcher()
        val kernel = OmegaKernel

        val writer = Sc18CsvWriter(Paths.get("data/"), newFixedThreadPoolContext(2, "writer"))

        val timeout = 500_000L

        val total = schedulers.size * (repeat + warmUp)
        val done = AtomicInteger(0)

        try {
            logger.info { "Setting up $total experiments" }

            manager.transaction.begin()
            manager.persist(trace)
            manager.persist(path)
            manager.transaction.commit()

            logger.info { "Launching experiments with parallelism level of $parallelism" }

            Sc18ExperimentManager(factory, writer, schedulers, trace.id!!, path.id!!, repeat, warmUp).use { experimentManager ->
                launch(Unconfined) {
                    // Launch experiments
                    for (experiment in experimentManager.experiments) {
                        launch(dispatcher, parent = coroutineContext[Job]) {
                            experiment.use { it.run(kernel, timeout) }
                            val snapshot = done.incrementAndGet()
                            logger.info { "$snapshot/$total experiments finished" }

                        }
                    }
                }.join()
            }

            logger.info { "Exporting secondary data" }

            // The queries export the experiments and tasks that have been run
            val queries = mapOf(
                "experiments" to """
                    SELECT e.id, e.simulation_id, e.path_id, e.trace_id, e.scheduler_name, e.name, e.state, e.last_simulated_tick
                    FROM experiments AS e
                    WHERE e.name <> 'WARM-UP'
                """.trimIndent(),
                "tasks" to """
                    SELECT t.id, t.start_tick, t.total_flop_count, t.core_count, t.job_id
                    FROM tasks AS t
                    JOIN jobs AS j ON j.id = t.job_id
                    JOIN traces AS tr ON tr.id = j.trace_id
                    JOIN experiments AS e ON e.trace_id = tr.id
                    WHERE e.name <> 'WARM-UP'
                """.trimIndent()
            )
            val query = queries.map {
                "CALL CSVWRITE('data/${it.key}.csv', '${it.value.replace("\n", " ").replace("'", "''")}')"
            }.joinToString(";")

            // Export the results as CSV
            manager.transaction.begin()
            manager
                .createNativeQuery(query)
                .executeUpdate()
            manager.transaction.commit()
        } finally {
            manager.close()
            factory.close()
            dispatcher.close()
            writer.close()
        }
    }
}
