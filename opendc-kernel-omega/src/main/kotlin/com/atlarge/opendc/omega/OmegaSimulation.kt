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

package com.atlarge.opendc.omega

import com.atlarge.opendc.simulator.*
import com.atlarge.opendc.simulator.instrumentation.Instrument
import com.atlarge.opendc.simulator.instrumentation.InstrumentScope
import com.atlarge.opendc.simulator.instrumentation.Port
import com.atlarge.opendc.simulator.kernel.Simulation
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import mu.KotlinLogging
import java.lang.ref.WeakReference
import java.util.*
import kotlin.coroutines.experimental.*

/**
 * The Omega simulation kernel is the reference simulation kernel implementation for the OpenDC Simulator core.
 *
 * This simulator implementation is a single-threaded implementation, running simulation kernels synchronously and
 * provides a single priority queue for all events (messages, ticks, etc) that occur in the entities.
 *
 * @property model The model that is simulated.
 * @author Fabian Mastenbroek (f.s.mastenbroek@student.tudelft.nl)
 */
internal class OmegaSimulation<M>(bootstrap: Bootstrap<M>) : Simulation<M>, Bootstrap.Context<M> {
    /**
     * The logger instance to use for the simulator.
     */
    private val logger = KotlinLogging.logger {}

    /**
     * The registry of the processes used in the simulation.
     */
    private val registry: MutableMap<Entity<*, *>, OmegaContext<*>> = HashMap()

    /**
     * The message queue.
     */
    private val queue: Queue<Envelope> = PriorityQueue(Comparator
        .comparingLong(Envelope::time)
        .thenComparingLong(Envelope::id))

    /**
     * The kernel process instance that handles internal operations during the simulation.
     */
    private val process = object : Process<Unit, M> {
        override val initialState = Unit

        override suspend fun Context<Unit, M>.run() {
            while(true) {
                val msg = receive()
                when (msg) {
                    is Launch<*> ->
                        @Suppress("UNCHECKED_CAST")
                        launch((msg as Launch<M>).process)
                }
            }
        }
    }

    /**
     * The context associated with an [Entity].
     */
    @Suppress("UNCHECKED_CAST")
    private val <E : Entity<S, M>, S, M> E.context: OmegaContext<S>?
        get() = registry[this] as? OmegaContext<S>

    /**
     * The simulation time.
     */
    override var time: Instant = 0

    /**
     * The model of simulation.
     */
    // XXX: the bootstrap requires the properties of this class to be initialised, so changing the order may cause NPEs
    override var model: M = bootstrap.apply(this)

    /**
     * The observable state of an [Entity] in simulation, which is provided by the simulation context.
     */
    override val <E : Entity<S, *>, S> E.state: S
        get() = context?.state ?: initialState

    /**
     * Initialise the simulation instance.
     */
    init {
        // Launch the Omega kernel process
        launch(process)
    }

    // Bootstrap Context implementation
    override fun register(entity: Entity<*, M>): Boolean {
        if (!registry.containsKey(entity) && entity !is Process) {
            return false
        }

        schedule(Launch(entity as Process<*, M>), process)
        return true
    }

    override fun deregister(entity: Entity<*, M>): Boolean {
        val context = entity.context ?: return false
        context.resume(Unit)
        return true
    }

    override fun schedule(message: Any, destination: Entity<*, *>, sender: Entity<*, *>?, delay: Duration) =
        schedule(prepare(message, destination, sender, delay))

    // Simulation implementation
    override fun openPort(): Port<M> = object : Port<M> {
        val channels: MutableSet<WeakReference<Channel<*>>> = mutableSetOf()

        override fun <T> install(capacity: Int, instrument: Instrument<T, M>): ReceiveChannel<T> {
            val channel = Channel<T>(capacity)
            val process = object : Process<Unit, M> {
                override val initialState = Unit
                override suspend fun Context<Unit, M>.run() {
                    val builder = object : InstrumentScope<T, M>, SendChannel<T> by channel, Context<Unit, M> by this {}
                    try {
                        instrument(builder)
                        channel.close()
                    } catch (cause: Throwable) {
                        channel.close(cause)
                    }
                }
            }
            channels.add(WeakReference(channel))
            register(process)
            return channel
        }

        override fun close(cause: Throwable?): Boolean = channels
            .map { it.get()?.close(cause) ?: false }
            .any()
    }

    override fun step() {
        while (true) {
            val envelope = queue.peek() ?: return
            val delivery = envelope.time

            if (delivery > time) {
                // Tick has yet to occur
                // Jump in time to next event
                time = delivery
                break
            } else if (delivery < time) {
                // Tick has already occurred
                logger.warn { "Message processed out of order" }
            }

            queue.poll()

            // If the sender has canceled the message, we move on to the next message
            if (envelope.canceled) {
                continue
            }

            val context = envelope.destination.context ?: continue
            val continuation = context.continuation ?: continue

            // Clear the continuation to prevent resuming an already resumed continuation
            context.continuation = null

            if (envelope.message is Interrupt) {
                continuation.resumeWithException(envelope.message)
            } else {
                continuation.resume(envelope)
            }

            context.last = time
        }
    }

    override fun run() {
        while (queue.isNotEmpty()) {
            step()
        }
    }

    override fun run(until: Instant) {
        require(until > 0) { "The given instant must be a non-zero positive number" }

        if (time >= until) {
            return
        }

        while (time < until && queue.isNotEmpty()) {
            step()
        }

        // Fix clock if step() jumped too far in time to give the impression to the user that simulation stopped at
        // exactly the tick it gave. This has not effect on the actual simulation results as the next call to run() will
        // just jump forward again.
        if (time > until) {
            time = until
        }
    }

    /**
     * The identifier for the next message to be scheduled.
     */
    private var nextId: Long = 0

    /**
     * A wrapper around a message that has been scheduled for processing.
     *
     * @property id The identifier of the message to keep the priority queue stable
     * @property message The message to wrap.
     * @property time The point in time to deliver the message.
     * @property sender The sender of the message.
     * @property destination The destination of the message.
     */
    private data class Envelope(val id: Long,
                                val message: Any,
                                val time: Instant,
                                val sender: Entity<*, *>?,
                                val destination: Entity<*, *>) {
        /**
         * A flag to indicate the message has been canceled.
         */
        internal var canceled: Boolean = false
    }

    /**
     * Schedule the given envelope to be processed by the kernel.
     *
     * @param envelope The envelope containing the message to schedule.
     */
    private fun schedule(envelope: Envelope) {
        queue.add(envelope)
    }

    /**
     * Prepare a message for scheduling by wrapping it into an envelope.
     *
     * @param message The message to send.
     * @param destination The destination entity that should receive the message.
     * @param sender The optional sender of the message.
     * @param delay The time to delay the message.
     */
    private fun prepare(message: Any, destination: Entity<*, *>, sender: Entity<*, *>? = null,
                        delay: Duration): Envelope {
        require(delay >= 0) { "The amount of time to delay the message must be a positive number" }
        return Envelope(nextId++, message, time + delay, sender, destination)
    }

    /**
     * Launch the given [Process].
     *
     * @param process The process to launch.
     */
    private fun launch(process: Process<*, M>) {
        val context = OmegaContext(process).also { registry[process] = it }

        // Bootstrap the process coroutine
        val block: suspend () -> Unit = { context.start() }
        block.startCoroutine(context)
    }

    /**
     * This internal class provides the default implementation for the [Context] interface for this simulator.
     */
    private inner class OmegaContext<S>(val process: Process<S, M>) : Context<S, M>, Continuation<Unit>,
        AbstractCoroutineContextElement(Context) {
        /**
         * The model in which the process exists.
         */
        override val model: M
            get() = this@OmegaSimulation.model

        /**
         * The current point in simulation time.
         */
        override val time: Instant
            get() = this@OmegaSimulation.time

        /**
         * The [Entity] associated with this context.
         */
        override val self: Entity<S, M>
            get() = process

        /**
         * The duration between the current point in simulation time and the last point in simulation time where the
         * [Context] has executed some work.
         */
        override val delta: Duration
            get() = maxOf(time - last, 0)

        /**
         * The state of the entity.
         */
        override var state: S = process.initialState

        /**
         * The observable state of an [Entity] within the simulation is provided by the context of the simulation.
         */
        override val <T : Entity<S, *>, S> T.state: S
            get() = context?.state ?: initialState

        /**
         * The sender of the last received message or `null` in case the process has not received any messages yet.
         */
        override var sender: Entity<*, *>? = null

        /**
         * The [CoroutineContext] for a [Context].
         */
        override val context: CoroutineContext = this

        /**
         * The continuation to resume the execution of the process.
         */
        var continuation: Continuation<Envelope>? = null

        /**
         * The last point in time the process has done some work.
         */
        var last: Instant = -1

        override suspend fun receive(): Any = receiveEnvelope().message

        override suspend fun receive(timeout: Duration): Any? {
            val send = prepare(Timeout, process, process, timeout).also { schedule(it) }

            try {
                val received = receiveEnvelope()

                if (received.message != Timeout) {
                    send.canceled = true
                    return received.message
                }

                return null
            } finally {
                send.canceled = true
            }
        }

        override suspend fun Entity<*, *>.send(msg: Any, sender: Entity<*, *>, delay: Duration) =
            schedule(prepare(msg, this, sender, delay))

        override suspend fun Entity<*, *>.interrupt(interrupt: Interrupt) = send(interrupt)

        override suspend fun hold(duration: Duration) {
            require(duration >= 0) { "The amount of time to hold must be a positive number" }
            val envelope = prepare(Resume, process, process, duration).also { schedule(it) }

            try {
                while (true) {
                    if (receive() == Resume)
                        return
                }
            } finally {
                envelope.canceled = true
            }
        }

        override suspend fun hold(duration: Duration, queue: Queue<Any>) {
            require(duration >= 0) { "The amount of time to hold must be a positive number" }
            val envelope = prepare(Resume, process, process, duration).also { schedule(it) }

            try {
                while (true) {
                    val msg = receive()
                    if (msg == Resume)
                        return
                    queue.add(msg)
                }
            } finally {
                envelope.canceled = true
            }
        }

        /**
         * Start the process associated with this context.
         */
        internal suspend fun start() = process.run { run() }

        /**
         * Retrieve and remove and single message from the mailbox of the [Entity] and suspend the [Context] until the
         * message has been received.
         *
         * @return The envelope containing the message.
         */
        suspend fun receiveEnvelope() = suspendCoroutine<Envelope> { continuation = it }
            .also { sender = it.sender }

        // Completion continuation implementation
        /**
         * Resume the execution of this continuation with the given value.
         *
         * @param value The value to resume with.
         */
        override fun resume(value: Unit) {
            // Deregister process from registry in order to have the GC collect this context
            registry.remove(process)
        }

        /**
         * Resume the execution of this continuation with an exception.
         *
         * @param exception The exception to resume with.
         */
        override fun resumeWithException(exception: Throwable) = throw exception
    }
}
