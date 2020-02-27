package com.example

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext


typealias StateMachineSideEffect<S, E> = suspend StateMachine<S, E>.(E) -> Unit


interface ErrorHandler {
    fun publishError(throwable: Throwable)
}

class StateMachine<S : Any, E : Any> private constructor(private val machineMetadata: MachineMetadata<S, E>) : CoroutineScope, ErrorHandler {
    private val TAG = "StateMachine"
    private val job = Job()
    val states: Flow<S>
        get() = mStatesChannel.openSubscription().consumeAsFlow(mState)

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    // incoming events channel
    private val mEventsChannel = Channel<E>(0)

    // states observability channel (mostly UI display)
    private var mState: S = machineMetadata.initialState
    private val mStatesChannel = BroadcastChannel<S>(DEFAULT_BUFFER_SIZE)

    private val inProcessingLoop = AtomicBoolean(false)

    // transition function
    init {
        // events processing routine
        launch {
            mEventsChannel.consumeEach { event ->
                try {
                    check(inProcessingLoop.compareAndSet(false, true)) {
                        "Do not call publish() from side-effects synchronously! It may result in deadlocks!"
                    }

                    val state = mState
                    val transition = state.getTransition(event) ?: run {
                        Log.e(TAG, "Undefined transition $mState($event)")
                        return@consumeEach
                    }

                    if (transition.fromState == transition.toState) {
                        Log.d(TAG, "NoTransition: $mState($event)")
                        transition.sideEffect?.invoke(this@StateMachine, event)
                        return@consumeEach
                    } else {
                        Log.d(TAG, "Transition: $mState($event) = ${transition.toState}")
                    }


                    // before leaving old state
                    transition.fromStateMetadata.onExit?.invoke(this@StateMachine, event)

                    // execute side effect (suspending)
                    transition.sideEffect?.invoke(this@StateMachine, event)

                    // update to the new mState
                    mState = transition.toState

                    // after entering new state (suspending)
                    transition.toStateMetadata.onEnter?.invoke(this@StateMachine, event)

                    // signal state change after all side-effects are executed
                    mStatesChannel.send(transition.toState)

                } catch (exc: Throwable) {
                    Log.e(TAG, "Side effect failed. Executing onError.", exc)
                    launch { publishError(exc) }
                } finally {
                    inProcessingLoop.set(false)
                }
            }
        }
    }

    private data class Transition<S : Any, E : Any>(
        val fromState: S,
        val toState: S,
        val fromStateMetadata: MachineMetadata.StateMetadata<S, E>,
        val toStateMetadata: MachineMetadata.StateMetadata<S, E>,
        val sideEffect: StateMachineSideEffect<S, E>?
    )

    private fun S.getTransition(event: E): Transition<S, E>? {
        val fromMetadata = getMetadata()
        for ((eventMatcher, createTransitionTo) in fromMetadata.transitions) {
            if (eventMatcher.matches(event)) {
                val transitionMetadata = createTransitionTo(this, event)
                return Transition(this, transitionMetadata.newState, fromMetadata, transitionMetadata.newState.getMetadata(), transitionMetadata.sideEffect)
            }
        }
        return null
    }

    private fun S.getMetadata() = machineMetadata.states
        .filter { it.key.matches(this) }
        .map { it.value }
        .firstOrNull()
        ?: error("Missing machineMetadata for state ${this.javaClass.simpleName}!")


    override fun publishError(throwable: Throwable) {
        machineMetadata.handleError(this, throwable)
    }

    suspend fun publish(event: E) {
        mEventsChannel.send(event)
    }


    class MachineMetadata<S : Any, E : Any> internal constructor(
        val initialState: S,
        val states: Map<Matcher<S, S>, StateMetadata<S, E>>,
        var errorHandler: StateMachine<S, E>.(exception: Throwable) -> Unit) {

        fun handleError(stateMachine: StateMachine<S, E>, throwable: Throwable) {
            stateMachine.apply {
                errorHandler(throwable)
            }
        }

        class StateMetadata<S : Any, E : Any> internal constructor() {
            var onEnter: StateMachineSideEffect<S, E>? = null
            var onExit: StateMachineSideEffect<S, E>? = null
            var transitions = linkedMapOf<Matcher<E, E>, (S, E) -> TransitionMetadata<S, E>>()

            class TransitionMetadata<S : Any, E : Any> internal constructor(
                val newState: S,
                val sideEffect: StateMachineSideEffect<S, E>? = null
            )
        }


    }

    companion object {
        fun <S : Any, E : Any> create(init: StateMachineBuilder<S, E>.() -> Unit
        ): StateMachine<S, E> {
            return StateMachine(StateMachineBuilder<S, E>().apply(init).build())
        }
    }

    class StateMachineBuilder<STATE : Any, EVENT : Any> internal constructor() {
        private lateinit var initialState: STATE
        private val stateMetadata: HashMap<Matcher<STATE, STATE>, MachineMetadata.StateMetadata<STATE, EVENT>> = LinkedHashMap()
        private var errorHandler: StateMachine<STATE, EVENT>.(exception: Throwable) -> Unit = { }


        fun initialState(state: STATE) {
            initialState = state
        }

        fun <S : STATE> state(
            stateMatcher: Matcher<STATE, S>,
            init: StateMetadataBuilder<S>.() -> Unit
        ) {
            stateMetadata[stateMatcher] = StateMetadataBuilder<S>().apply(init).build()
        }

        inline fun <reified S : STATE> state(noinline init: StateMetadataBuilder<S>.() -> Unit) {
            state(Matcher.any(), init)
        }

        inline fun <reified S : STATE> state(state: S, noinline init: StateMetadataBuilder<S>.() -> Unit) {
            state(Matcher.eq<STATE, S>(state), init)
        }

        fun build(): MachineMetadata<STATE, EVENT> {
            return MachineMetadata(initialState, stateMetadata, errorHandler)
        }

        fun onError(function: StateMachine<STATE, EVENT>.(exception: Throwable) -> Unit) {
            errorHandler = function
        }

        inner class StateMetadataBuilder<S : STATE> internal constructor() {
            private val stateMetadata = MachineMetadata.StateMetadata<STATE, EVENT>()

            @Suppress("UNCHECKED_CAST")
            fun <E1 : EVENT> on(matcher: Matcher<EVENT, E1>, function: S.(E1) -> MachineMetadata.StateMetadata.TransitionMetadata<STATE, EVENT>) {
                stateMetadata.transitions[matcher] = { s, e -> function(s as S, e as E1) }
            }

            inline fun <reified E1 : EVENT> on(noinline function: S.(E1) -> MachineMetadata.StateMetadata.TransitionMetadata<STATE, EVENT>) {
                on(Matcher.any(), function)
            }

            fun onEnter(function: StateMachineSideEffect<STATE, EVENT>) {
                stateMetadata.onEnter = function
            }

            fun onExit(function: StateMachineSideEffect<STATE, EVENT>) {
                stateMetadata.onExit = function

            }

            fun build(): MachineMetadata.StateMetadata<STATE, EVENT> {
                return stateMetadata
            }

            fun <S1 : STATE> transitionTo(newState: S1): MachineMetadata.StateMetadata.TransitionMetadata<STATE, EVENT> {
                return MachineMetadata.StateMetadata.TransitionMetadata(newState)
            }

            fun <S1 : STATE> transitionTo(newState: S1, function: StateMachineSideEffect<STATE, EVENT>): MachineMetadata.StateMetadata.TransitionMetadata<STATE, EVENT> {
                return MachineMetadata.StateMetadata.TransitionMetadata(newState, function)
            }

            fun S.noTransition(): MachineMetadata.StateMetadata.TransitionMetadata<STATE, EVENT> {
                return MachineMetadata.StateMetadata.TransitionMetadata(this)
            }

            fun S.noTransition(function: StateMachineSideEffect<STATE, EVENT>): MachineMetadata.StateMetadata.TransitionMetadata<STATE, EVENT> {
                return MachineMetadata.StateMetadata.TransitionMetadata(this, function)
            }
        }
    }


    class Matcher<T : Any, out R : T> private constructor(private val clazz: Class<R>) {

        private val predicates = mutableListOf<(T) -> Boolean>({ clazz.isInstance(it) })

        fun where(predicate: R.() -> Boolean): Matcher<T, R> = apply {
            predicates.add {
                @Suppress("UNCHECKED_CAST")
                (it as R).predicate()
            }
        }

        fun matches(value: T) = predicates.all { it(value) }

        companion object {
            fun <T : Any, R : T> any(clazz: Class<R>): Matcher<T, R> = Matcher(clazz)

            inline fun <T : Any, reified R : T> any(): Matcher<T, R> = any(R::class.java)

            inline fun <T : Any, reified R : T> eq(value: R): Matcher<T, R> = any<T, R>().where { this == value }
        }
    }
}
