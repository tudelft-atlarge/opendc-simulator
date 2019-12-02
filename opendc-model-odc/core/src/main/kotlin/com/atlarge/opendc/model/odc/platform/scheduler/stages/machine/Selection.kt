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

package com.atlarge.opendc.model.odc.platform.scheduler.stages.machine

import com.atlarge.opendc.model.odc.OdcModel
import com.atlarge.opendc.model.odc.platform.scheduler.StageScheduler
import com.atlarge.opendc.model.odc.platform.workload.Task
import com.atlarge.opendc.model.odc.topology.machine.Cpu
import com.atlarge.opendc.model.odc.topology.machine.Machine
import com.atlarge.opendc.model.topology.destinations
import com.atlarge.opendc.simulator.context
import java.util.NavigableMap
import java.util.Random
import java.util.TreeMap
import javax.crypto.Mac
import kotlin.math.abs

/**
 * This interface represents the **R5** stage of the Reference Architecture for Schedulers and matches the the selected
 * task with a (set of) resource(s), using policies such as First-Fit, Worst-Fit, and Best-Fit.
 */
interface MachineSelectionPolicy {
    /**
     * Select a machine on which the task should be scheduled.
     *
     * @param machines The list of machines in the system.
     * @param task The task that is to be scheduled.
     * @return The selected machine or `null` if no machine could be found.
     */
    suspend fun select(machines: List<Machine>, task: Task): Machine?
}

/**
 * A [MachineSelectionPolicy] that selects the first machine that is available.
 */
class FirstFitMachineSelectionPolicy : MachineSelectionPolicy {
    override suspend fun select(machines: List<Machine>, task: Task): Machine? = machines.firstOrNull()
}

/**
 * A [MachineSelectionPolicy] that selects the machine using a Best-Fit allocation algorithm: select the machine with
 * the smallest amount of available cores such that the given task can be scheduled on it.
 */
class BestFitMachineSelectionPolicy : MachineSelectionPolicy {
    override suspend fun select(machines: List<Machine>, task: Task): Machine? =
        context<StageScheduler.State, OdcModel>().run {
            machines
                .sortedBy { abs(task.cores - (state.machineCores[it] ?: 0)) }
                .firstOrNull()
        }
}

/**
 * A [MachineSelectionPolicy] that selects the machine using a Worst-Fit allocation algorithm: select the machine with
 * the largest amount of available cores such that the given task can be scheduled on it.
 */
class WorstFitMachineSelectionPolicy : MachineSelectionPolicy {
    override suspend fun select(machines: List<Machine>, task: Task): Machine? =
        context<StageScheduler.State, OdcModel>().run {
            machines
                .sortedByDescending { abs(task.cores - (state.machineCores[it] ?: 0)) }
                .firstOrNull()
        }
}

/**
 * A [MachineSelectionPolicy] that selects the machine randomly.
 *
 * @property random The [Random] instance used to pick the machine.
 */
class RandomMachineSelectionPolicy(private val random: Random = Random()) : MachineSelectionPolicy {
    override suspend fun select(machines: List<Machine>, task: Task): Machine? =
        if (machines.isNotEmpty())
            machines[random.nextInt(machines.size)]
        else
            null
}

/**
 * Heterogeneous Earliest Finish Time (HEFT) scheduling.
 *
 * https://en.wikipedia.org/wiki/Heterogeneous_Earliest_Finish_Time
 */
class HeftMachineSelectionPolicy : MachineSelectionPolicy {
    override suspend fun select(machines: List<Machine>, task: Task): Machine? =
        context<StageScheduler.State, OdcModel>().run {
            model.run {
                // NOTE: higher is better.
                fun communication(task: Task, machine: Machine): Double {
                    return machine.ethernetSpeed / task.inputSize
                }
                fun availableCompute(machine: Machine): Double {
                    val cpus = machine.outgoingEdges.destinations<Cpu>("cpu")
                    val cores = cpus.map { it.cores }.sum()
                    val speed = cpus.fold(0) { acc, cpu -> acc + cpu.clockRate * cpu.cores } / cores
                    return (1.0 - machine.state.load) * speed
                }

                machines.maxBy { machine -> communication(task, machine) + availableCompute(machine) }
            }
        }
}

/**
 * Critical-Path-on-a-Processor (CPOP) scheduling as described by H. Topcuoglu et al. in
 * "Task Scheduling Algorithms for Heterogeneous Processors".
 */
class CpopMachineSelectionPolicy : MachineSelectionPolicy {
    override suspend fun select(machines: List<Machine>, task: Task): Machine? =
        context<StageScheduler.State, OdcModel>().run {
            model.run {
                // NOTE: higher is better.
                fun communication(task: Task, machine: Machine): Double {
                    return machine.ethernetSpeed.toDouble() / task.inputSize
                }
                fun availableCompute(machine: Machine): Double {
                    val cpus = machine.outgoingEdges.destinations<Cpu>("cpu")
                    val cores = cpus.map { it.cores }.sum()
                    val speed = cpus.fold(0) { acc, cpu -> acc + cpu.clockRate * cpu.cores } / cores
                    return (1.0 - machine.state.load) * speed
                }

                machines.maxBy { machine -> communication(task, machine) + availableCompute(machine) }
            }
        }
}

/**
 * Round robin (RR) scheduling.
 *
 * https://en.wikipedia.org/wiki/Round-robin_scheduling
 */
class RrMachineSelectionPolicy(private var current: Int = 0) : MachineSelectionPolicy {
    override suspend fun select(machines: List<Machine>, task: Task): Machine? =
        context<StageScheduler.State, OdcModel>().run {
            model.run {
                if (machines.isEmpty()) {
                    return null
                }
                val ids: NavigableMap<Int, Machine> = TreeMap(machines.associateBy { it.id })
                current = ids.higherKey(current) ?: ids.firstKey()
                return ids[current]
            }
        }
}

/**
 * Fast Critical Path (FCP) Scheduling machine selection
 *
 * FCP considers two machines candidates, one which has sent the last message (last message received), and
 * the other which has became idle earliest. The procedure then selects the one with the earliest start time.
 */
class FCPMachineSelectionPolicy: MachineSelectionPolicy {
    override suspend fun select(machines: List<Machine>, task: Task): Machine? =
        context<StageScheduler.State, OdcModel>().run {
            model.run {
                if (machines.isEmpty()) {
                    return null
                }

                // Sort by the time they are done with tasks
                // Times are managed by the scheduler, so no
                // inconsistencies
                val earliest = machines.sortedBy{it.state.endTime}[0]
                val last = machines.sortedBy {it.state.startTime}[0]

                if (earliest.state.endTime < last.state.startTime) {
                    return earliest
                }

                return last
            }
        }
}
