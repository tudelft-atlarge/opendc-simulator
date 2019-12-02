/*
 * MIT License
 *
 * Copyright (c) 2017 atlarge-research
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

package com.atlarge.opendc.model.odc.topology.machine

import com.atlarge.opendc.model.odc.platform.workload.Task
import com.atlarge.opendc.model.topology.Topology
import com.atlarge.opendc.model.topology.destinations
import com.atlarge.opendc.simulator.Context
import com.atlarge.opendc.simulator.Process
import mu.KotlinLogging
import java.util.*

/**
 * A Physical Machine (PM) inside a rack of a datacenter. It has a speed, and can be given a workload on which it will
 * work until finished or interrupted.
 *
 * @property ethernetSpeed The speed of the ethernet port of the machine.
 * @author Fabian Mastenbroek (f.s.mastenbroek@student.tudelft.nl)
 */
open class Machine(val ethernetSpeed: Double = 0.0) : Process<Machine.State, Topology> {
    /**
     * The logger instance to use for the simulator.
     */
    private val logger = KotlinLogging.logger {}

    /**
     * The status of a machine.
     */
    enum class Status {
        HALT, IDLE, RUNNING
    }

    /**
     * The shape of the state of a [Machine] entity.
     *
     * @property status The status of the machine.
     * @property tasks The task assigned to the machine.
     * @property memory The memory usage of the machine (defaults to 50mb for the kernel)
     * @property load The load on the machine (defaults to 0.0)
     * @property temperature The temperature of the machine (defaults to 23 degrees Celcius)
     * @property available The available cores of the machine.
     */
    data class State(val status: Status,
                     val tasks: Set<Task> = emptySet(),
                     val memory: Int = 50,
                     val load: Double = 0.0,
                     val temperature: Double = 23.0,
                     val available: Int,
                     var startTime: Date = Date(),
                     var endTime: Date = Date())

    /**
     * This message is sent when a task is accepted by the machine.
     *
     * @property task The task that has been accepted by the machine.
     */
    data class Accept(val task: Task)

    /**
     * This message is sent when a task is declined by the machine.
     *
     * @property task The task that has been declined by the machine.
     */
    data class Decline(val task: Task)

    /**
     * Internal message to indicate the task is done.
     *
     * @property task The task that is done running.
     */
    data class Done(val task: Task)

    /**
     * The initial state of a [Machine] entity.
     */
    override val initialState = State(Status.HALT, available = 0)

    /**
     * Run the simulation kernel for this entity.
     */
    override suspend fun Context<State, Topology>.run() = model.run {
        val cpus = outgoingEdges.destinations<Cpu>("cpu")
        val cores = cpus.map { it.cores }.sum()
        // Speed per core is an weighted average clock rate.
        val speed = cpus.fold(0) { acc, cpu -> acc + cpu.clockRate * cpu.cores } / cores

        state = State(Status.IDLE, available = cores)

        // Halt the machine if it has not processing units (see bug #4)
        if (cpus.isEmpty()) {
            logger.warn { "[$time] Machine $id halted due to no cores" }
            state = state.copy(status = Status.HALT)
            return
        }

        while (true) {
            // Check if we have received a new order in the meantime.
            val msg = receive()
            when (msg) {
                is Task -> {
                    // Check if the machine has enough cores available
                    if (state.available >= msg.cores) {
                        logger.debug { "[$time] Task ${msg.id} received on machine $id" }
                        state = state.copy(
                            status = Status.RUNNING,
                            tasks = state.tasks.plus(msg),
                            available = state.available - msg.cores,
                            load = state.load + (msg.cores.toDouble() / cores),
                            memory = state.memory + 50,
                            temperature = state.temperature + 5.0
                        )
                        sender?.send(Accept(msg))

                        // Inform the task that it is running
                        msg.consume(time, 0)

                        // Awake the machine when the task is done
                        self.send(Done(msg), delay = msg.flops / msg.cores / speed)
                    } else {
                        logger.debug { "[$time] Task ${msg.id} not accepted on machine $id" }
                        sender?.send(Decline(msg))
                    }
                }
                is Done -> {
                    val task = msg.task
                    logger.debug { "[$time] Task ${task.id} finished on machine $id" }
                    task.consume(time, task.flops)
                    state = state.copy(
                        tasks = state.tasks.minus(task),
                        available = state.available + task.cores,
                        load = state.load - (task.cores.toDouble() / cores),
                        memory =  state.memory - 50,
                        temperature = state.temperature - 5.0
                    )
                }
            }

            // Determine whether the machine is idle.
            if (state.tasks.isEmpty()) {
                state = state.copy(
                    status = Status.IDLE,
                    memory =  50,
                    load = 0.0,
                    temperature = 23.0
                )
            }
        }
    }
}

