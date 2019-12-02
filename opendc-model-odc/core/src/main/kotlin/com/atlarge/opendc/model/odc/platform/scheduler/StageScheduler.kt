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

package com.atlarge.opendc.model.odc.platform.scheduler

import com.atlarge.opendc.model.odc.OdcModel
import com.atlarge.opendc.model.odc.platform.scheduler.stages.StageMeasurementAccumulator
import com.atlarge.opendc.model.odc.platform.scheduler.stages.machine.MachineDynamicFilteringPolicy
import com.atlarge.opendc.model.odc.platform.scheduler.stages.machine.MachineSelectionPolicy
import com.atlarge.opendc.model.odc.platform.scheduler.stages.task.TaskEligibilityFilteringPolicy
import com.atlarge.opendc.model.odc.platform.scheduler.stages.task.TaskSortingPolicy
import com.atlarge.opendc.model.odc.platform.workload.Task
import com.atlarge.opendc.model.odc.topology.machine.Machine
import com.atlarge.opendc.simulator.Context
import com.atlarge.opendc.simulator.util.EventBus
import java.util.*

/**
 * A [Scheduler] that distributes work through a multi-stage process.
 *
 * @property name The name of the scheduler.
 * @property taskEligibilityFilteringPolicy The policy used to filter tasks based on eligibility (T1).
 * @property taskSortingPolicy The policy used to sort tasks (T2).
 * @property machineDynamicFilteringPolicy The policy used to filter machines based on dynamic information (R4).
 * @property machineSelectionPolicy The policy used to select the machine on which to schedule the task (R5).
 */
class StageScheduler(
    override val name: String,
    private val taskEligibilityFilteringPolicy: TaskEligibilityFilteringPolicy,
    private val taskSortingPolicy: TaskSortingPolicy,
    private val machineDynamicFilteringPolicy: MachineDynamicFilteringPolicy,
    private val machineSelectionPolicy: MachineSelectionPolicy
) : Scheduler<StageScheduler.State> {
    /**
     * The event bus of the scheduler.
     */
    override val bus = EventBus()

    /**
     * The initial state of the scheduler.
     */
    override val initialState = State()

    /**
     * The state of the stage scheduler.
     *
     * @property machines The available machines to schedule the tasks over.
     * @property tasks The tasks that are managed by the scheduler.
     * @property pending The tasks that are awaiting response of the machine.
     * @property queued The tasks that should be scheduled.
     */
    data class State(
        internal val machines: MutableSet<Machine> = LinkedHashSet(),
        internal val tasks: MutableSet<Task> = LinkedHashSet(),
        internal val pending: MutableSet<Task> = LinkedHashSet(),
        internal val queued: MutableSet<Task> = LinkedHashSet(),
        internal val machineCores: MutableMap<Machine, Int> = HashMap(),
        internal val taskMachines: MutableMap<Task, Machine> = HashMap()
    )


    /**
     * Run the simulation kernel for this entity.
     */
    override suspend fun Context<State, OdcModel>.run() {
        // Start the event bus
        start(bus)

        while (true) {
            val msg = receive()
            when (msg) {
                is Scheduler.Schedule -> schedule(msg.tasks)
                is Scheduler.Resources -> resources(msg.registered, msg.unregistered)
                is Machine.Accept -> state.pending.remove(msg.task)
                is Machine.Decline -> {
                    state.pending.remove(msg.task)
                    state.queued.add(msg.task)
                }
            }
        }
    }

    /**
     * Update the resources available to the scheduler.
     *
     * @property registered The new machines that have been registered to the datacenter.
     * @property unregistered The machines that have been unregistered.
     */
    private fun Context<State, OdcModel>.resources(registered: Set<Machine>, unregistered: Set<Machine>) {
        // R1 Gather available machines in the system
        state.machines.addAll(registered)
        state.machines.removeAll(unregistered)

        // R2 Filter machines based on authorization
        // TODO

        // R4 Keep track of dynamic information of machines in the system
        state.machineCores.putAll(registered.map { Pair(it, it.state.available) })
    }

    /**
     * (Re)schedule the tasks submitted to the scheduler over the specified set of machines.
     *
     * @param tasks The tasks that have been submitted to the scheduler.
     */
    private suspend fun Context<State, OdcModel>.schedule(tasks: Set<Task>) {
        val acc = StageMeasurementAccumulator(time, tasks.size)
        acc.start()

        // J2 Create list of eligible jobs
        // TODO For now, assume all jobs are eligible

        // J3 Sort jobs on criterion
        // TODO For now, jobs are processed in FIFO order

        // J5 Job setup
        // TODO Jobs require no setup in the current model

        // C1 Update caches of scheduler
        // Add all new tasks to the queue
        state.queued.addAll(tasks)
        state.tasks.addAll(tasks)

        // Remove finished tasks
        val iterator = state.tasks.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.finished) {
                val correspondingMachine = state.taskMachines[task]
                if (correspondingMachine != null) {
                    correspondingMachine.state.endTime = Date()
                    state.machineCores.merge(correspondingMachine, task.cores, Int::plus)
                    state.taskMachines.remove(task)
                }
                iterator.remove()
            }
        }

        if (state.queued.isEmpty()) {
            return
        }

        // T1 Filter tasks on eligibility
        // For now, eligibility of a tasks means its dependencies have finished
        val filteredTasks = acc.runStage(1, input = state.queued.size) {
            taskEligibilityFilteringPolicy.filter(state.queued)
        }

        // T2 Sort task on criterion
        val sortedTasks = acc.runStage(2, input = filteredTasks.size) {
            taskSortingPolicy.sort(filteredTasks)
        }

        // M6 Pass tasks on to Local Resource Manager (LRM)
        // TODO Move this process into a separate entity functioning as a LRM, independent of the global scheduler
        sortedTasks.forEach {
            // R4 Filter machines based on dynamic information
            val filteredMachines = acc.runStage(3, input = state.machines.size) {
                machineDynamicFilteringPolicy.filter(state.machines, it)
            }

            // R5 Select and allocate resource(s)
            val machine = acc.runStage(4, input = filteredMachines.size) {
                machineSelectionPolicy.select(filteredMachines, it)
            }

            // T4 Submit task to machine
            if (machine != null) {
                machine.send(it)
                state.queued.remove(it)
                state.pending.add(it)
                state.taskMachines[it] = machine
                machine.state.startTime = Date()
                state.machineCores.merge(machine, it.cores, Int::minus)
            }
        }

        acc.end()
        acc.measurements.forEach { bus.publish(it) }
    }
}
