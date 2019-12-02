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

package com.atlarge.opendc.model.odc.platform.scheduler.stages.task

import com.atlarge.opendc.model.odc.OdcModel
import com.atlarge.opendc.model.odc.platform.scheduler.StageScheduler
import com.atlarge.opendc.model.odc.platform.workload.Task
import com.atlarge.opendc.model.odc.topology.machine.Cpu
import com.atlarge.opendc.model.topology.destinations
import com.atlarge.opendc.simulator.context
import java.util.Random

/**
 * This interface represents the **T2** stage of the Reference Architecture for Schedulers and provides the scheduler
 * with a sorted list of tasks to schedule.
 */
interface TaskSortingPolicy {
    /**
     * Sort the given list of tasks on a given criterion.
     *
     * @param tasks The list of tasks that should be sorted.
     * @return The sorted list of tasks.
     */
    suspend fun sort(tasks: List<Task>): List<Task>
}

/**
 * The [FifoSortingPolicy] sorts tasks based on the order of arrival in the queue.
 */
class FifoSortingPolicy: TaskSortingPolicy {
    override suspend fun sort(tasks: List<Task>): List<Task> = tasks
}

/**
 * The [SrtfSortingPolicy] sorts tasks based on the remaining duration (in runtime) of the task.
 */
class SrtfSortingPolicy : TaskSortingPolicy {
    override suspend fun sort(tasks: List<Task>): List<Task> = tasks.sortedBy { it.remaining }
}

/**
 * The [RandomSortingPolicy] sorts tasks randomly.
 *
 * @property random The [Random] instance to use when sorting the list of tasks.
 */
class RandomSortingPolicy(private val random: Random = Random()) : TaskSortingPolicy {
    override suspend fun sort(tasks: List<Task>): List<Task> = tasks.shuffled(random)
}

/**
 * Heterogeneous Earliest Finish Time (HEFT) scheduling.
 *
 * https://en.wikipedia.org/wiki/Heterogeneous_Earliest_Finish_Time
 */
class HeftSortingPolicy : TaskSortingPolicy {
    override suspend fun sort(tasks: List<Task>): List<Task> =
        context<StageScheduler.State, OdcModel>().run {
            model.run {
                val machines = state.machines;
                fun averageComputationCost(task: Task): Double {
                    return machines.sumByDouble { machine ->
                        val cpus = machine.outgoingEdges.destinations<Cpu>("cpu")
                        val cores = cpus.map { it.cores }.sum()
                        val speed = cpus.fold(0) { acc, cpu -> acc + cpu.clockRate * cpu.cores } / cores
                        (task.remaining / speed).toDouble()
                    } / machines.size
                }
                fun averageCommunicationCost(dependency: Task): Double {
                    // Here we assume that all the output of the dependency
                    // (parent) task is needed as input for the task.
                    return machines.sumByDouble { dependency.outputSize / it.ethernetSpeed } / machines.size
                }
                // Upward rank of a `task`, as defined in the HEFT policy.
                fun upwardRank(task: Task): Double {
                    val avgCompCost = averageComputationCost(task)
                    val highestDependentCost = (task.dependents.map { dependentTask ->
                        averageCommunicationCost(dependentTask) + upwardRank(dependentTask)
                    }.max() ?: 0.0)
                    return avgCompCost + highestDependentCost
                }

                tasks.sortedByDescending { task -> upwardRank(task) }
            }
        }
}

/**
 * Critical-Path-on-a-Processor (CPOP) scheduling as described by H. Topcuoglu et al. in
 * "Task Scheduling Algorithms for Heterogeneous Processors".
 */
class CpopSortingPolicy : TaskSortingPolicy {
    override suspend fun sort(tasks: List<Task>): List<Task> =
        context<StageScheduler.State, OdcModel>().run {
            model.run {
                val machines = state.machines;
                fun averageComputationCost(task: Task): Double {
                    return machines.sumByDouble { machine ->
                        val cpus = machine.outgoingEdges.destinations<Cpu>("cpu")
                        val cores = cpus.map { it.cores }.sum()
                        val speed = cpus.fold(0) { acc, cpu -> acc + cpu.clockRate * cpu.cores } / cores
                        (task.remaining / speed).toDouble()
                    } / machines.size
                }
                fun averageCommunicationCost(taskNi: Task, taskNj: Task): Double {
                    // Here we assume that all the output of the dependency
                    // (parent) task is needed as input for the task.
                    return machines.sumByDouble { machine ->
                        (taskNi.outputSize / machine.ethernetSpeed).toDouble()
                    } / machines.size
                }
                // Upward rank of a `task`, as defined in the CPOP policy.
                fun upwardRank(task: Task): Double {
                    val avgCompCost = averageComputationCost(task)
                    val highestDependentCost = (task.dependents.map { successorTask ->
                        averageCommunicationCost(successorTask, task) + upwardRank(successorTask)
                    }.max() ?: 0.0)
                    return avgCompCost + highestDependentCost
                }
                // Downward rank of a 'task', as defined by the CPOP policy.
                fun downwardRank(task: Task): Double {
                    val rankD = (task.dependencies.map { predecessorTask ->
                        downwardRank(predecessorTask) + averageComputationCost(predecessorTask) +
                        averageCommunicationCost(predecessorTask, task)
                    }.max() ?: 0.0)
                    return rankD
                }

                tasks.sortedByDescending { task -> upwardRank(task) + downwardRank(task) }
            }
        }
}

/**
 * Priority Impact Scheduling Algorithm (PISA) scheduling.
 *
 * Hu Wu et al. A Priority Constrained Scheduling Strategy of Multiple Workflows
 * for Cloud Computing, 2012.
 */
class PisaSortingPolicy(val MaxWaitCount: Int = 100, private val waitCounts: MutableMap<Task, Int> = mutableMapOf()) : TaskSortingPolicy {
    // NOTE: The paper mentions `MaxWaitValue` (line 19 of figure 3) but never
    // mentions a suitable value for this variable, not in the algorithm or in
    // the experiments section.
    //
    // Because of this a, somewhat arbitrary, value of 100 is chosen. This means
    // that a low(er) priority task will have to let at least 100 other high(er)
    // priority tasks run before it can be run.

    override suspend fun sort(tasks: List<Task>): List<Task> =
        tasks.map { task ->
            val count = waitCounts.merge(task, 1, Int::plus)!!

            // If the task is waiting to long it will get priority over the
            // other tasks.
            if (count > MaxWaitCount) {
                Pair(task, Int.MAX_VALUE)
            } else {
                Pair(task, task.priority)
            }
        }
        .sortedByDescending { (_, priority) -> priority }
        .map { (task, _) -> task }
}


/**
 * Fast Critical Path (FCP) Algorithm scheduling
 *
 * The paper keeps track of 2 lists which are sorted and unsorted. For the sorted list, the paper uses
 * a priority queue, however a small sorted list will be sufficient for this simulation.
 */
class FCPSortingPolicy : TaskSortingPolicy {
    override suspend fun sort(tasks: List<Task>): List<Task> {
        // subList returns a view of the list limited by indices
        // Operations done on this sublist is reflected on parent list
        // So, instead of keeping two separate lists, we just keep
        // a sub section of tasks sorted
        val len = if (tasks.size > 500) 500 else tasks.size

        tasks.subList(0, len).sortedBy {it.priority}
        return tasks
    }
}
