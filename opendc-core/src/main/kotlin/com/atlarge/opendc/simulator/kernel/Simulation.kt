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

package com.atlarge.opendc.simulator.kernel

import com.atlarge.opendc.simulator.Entity
import com.atlarge.opendc.simulator.Instant
import com.atlarge.opendc.simulator.instrumentation.Instrument
import com.atlarge.opendc.simulator.instrumentation.Port

/**
 * A message based discrete event simulation over some model `M`. This interface provides direct control over the
 * simulation, allowing the user to step over cycles of the simulation and inspecting the state of the simulation via
 * [Entity.state].
 *
 * @param M The shape of the model over which the simulation runs.
 * @author Fabian Mastenbroek (f.s.mastenbroek@student.tudelft.nl)
 */
interface Simulation<M> {
    /**
     * The model in which the simulation runs.
     */
    val model: M

    /**
     * The simulation time.
     */
    var time: Instant

    /**
     * The observable state of an [Entity] in simulation, which is provided by the simulation context.
     */
    val <E : Entity<S, *>, S> E.state: S

    /**
     * Open a new [Port] to manage [Instrument]s.
     *
     * @return A new [Port] instance to install [Instrument]s to.
     */
    fun openPort(): Port<M>

    /**
     * Step through one cycle in the simulation. This method will process all events in a single tick, update the
     * internal clock and then return the control to the user.
     *
     * This method will throw if a process running during the cycle throws an exception.
     */
    fun step()

    /**
     * Run a simulation over the specified model.
     * This method will step through multiple cycles in the simulation until no more message exist in the queue.
     *
     * This method will throw if a process running during a cycle throws an exception.
     */
    fun run()

    /**
     * Run a simulation over the specified model, stepping through cycles until the specified clock tick has
     * occurred. The control is then handed back to the user.
     *
     * This method will throw if a process running during a cycle throws an exception.
     *
     * @param until The point in simulation time at which the simulation should be paused and the control is handed
     * 				back to the user.
     */
    fun run(until: Instant)
}
