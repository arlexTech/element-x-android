/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.services.appnavstate.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.services.appnavstate.api.AppForegroundStateService
import io.element.android.services.appnavstate.api.AppNavigationState
import io.element.android.services.appnavstate.api.AppNavigationStateService
import io.element.android.services.appnavstate.api.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import timber.log.Timber

private val loggerTag = LoggerTag("Navigation")

/**
 * TODO This will maybe not support properly navigation using permalink.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultAppNavigationStateService(
    private val appForegroundStateService: AppForegroundStateService,
    @AppCoroutineScope
    coroutineScope: CoroutineScope,
) : AppNavigationStateService {
    private val navigationStates = MutableStateFlow<Map<String, NavigationState>>(emptyMap())
    private val focusedOwner = MutableStateFlow<String?>(null)
    private val state = MutableStateFlow(
        AppNavigationState(
            navigationState = NavigationState.Root,
            allNavigationStates = emptyList(),
            focusedOwner = null,
            isInForeground = true,
        )
    )
    override val appNavigationState: StateFlow<AppNavigationState> = state

    init {
        coroutineScope.launch {
            appForegroundStateService.startObservingForeground()
            appForegroundStateService.isInForeground.collect { isInForeground ->
                state.getAndUpdate { it.copy(isInForeground = isInForeground) }
            }
        }
        coroutineScope.launch {
            kotlinx.coroutines.flow.combine(navigationStates, focusedOwner) { states, focused ->
                states to focused
            }.collect { (states, focused) ->
                val allStates = states.values.toList()
                val lastState = states[focused] ?: allStates.lastOrNull() ?: NavigationState.Root
                state.getAndUpdate {
                    it.copy(
                        navigationState = lastState,
                        allNavigationStates = allStates,
                        focusedOwner = focused,
                    )
                }
            }
        }
    }

    override fun onNavigateToSession(owner: String, sessionId: SessionId) {
        Timber.tag(loggerTag.value).d("Navigating to session $sessionId (owner=$owner).")
        focusedOwner.value = owner
        navigationStates.getAndUpdate { current ->
            current + (owner to NavigationState.Session(owner, sessionId))
        }
    }

    override fun onNavigateToRoom(owner: String, roomId: RoomId, isBubble: Boolean) {
        Timber.tag(loggerTag.value).d("Navigating to room $roomId (isBubble=$isBubble, owner=$owner).")
        focusedOwner.value = owner
        navigationStates.getAndUpdate { current ->
            val currentState = current[owner] ?: current.values.lastOrNull() ?: NavigationState.Root
            val newValue: NavigationState.Room = when (currentState) {
                NavigationState.Root -> return@getAndUpdate current // Log error if needed, but for now just skip
                is NavigationState.Session -> NavigationState.Room(owner, roomId, currentState, isBubble)
                is NavigationState.Room -> NavigationState.Room(owner, roomId, currentState.parentSession, isBubble)
                is NavigationState.Thread -> NavigationState.Room(owner, roomId, currentState.parentRoom.parentSession, isBubble)
            }
            current + (owner to newValue)
        }
    }

    override fun onNavigateToThread(owner: String, threadId: ThreadId, isBubble: Boolean) {
        Timber.tag(loggerTag.value).d("Navigating to thread $threadId (isBubble=$isBubble, owner=$owner).")
        focusedOwner.value = owner
        navigationStates.getAndUpdate { current ->
            val currentState = current[owner] ?: current.values.lastOrNull() ?: NavigationState.Root
            val newValue: NavigationState.Thread = when (currentState) {
                NavigationState.Root -> return@getAndUpdate current
                is NavigationState.Session -> return@getAndUpdate current
                is NavigationState.Room -> NavigationState.Thread(owner, threadId, currentState, isBubble)
                is NavigationState.Thread -> NavigationState.Thread(owner, threadId, currentState.parentRoom, isBubble)
            }
            current + (owner to newValue)
        }
    }

    override fun onLeavingThread(owner: String) {
        Timber.tag(loggerTag.value).d("Leaving thread (owner=$owner).")
        navigationStates.getAndUpdate { current ->
            val currentState = current[owner] ?: return@getAndUpdate current
            val newValue: NavigationState.Room = when (currentState) {
                NavigationState.Root -> return@getAndUpdate current
                is NavigationState.Session -> return@getAndUpdate current
                is NavigationState.Room -> return@getAndUpdate current
                is NavigationState.Thread -> currentState.parentRoom
            }
            current + (owner to newValue)
        }
    }

    override fun onLeavingRoom(owner: String) {
        Timber.tag(loggerTag.value).d("Leaving room (owner=$owner).")
        navigationStates.getAndUpdate { current ->
            val currentState = current[owner] ?: return@getAndUpdate current
            val newValue: NavigationState.Session = when (currentState) {
                NavigationState.Root -> return@getAndUpdate current
                is NavigationState.Session -> return@getAndUpdate current
                is NavigationState.Room -> currentState.parentSession
                is NavigationState.Thread -> currentState.parentRoom.parentSession
            }
            current + (owner to newValue)
        }
    }

    override fun onLeavingSession(owner: String) {
        Timber.tag(loggerTag.value).d("Leaving session (owner=$owner).")
        navigationStates.getAndUpdate { current ->
            current - owner
        }
        if (focusedOwner.value == owner) {
            focusedOwner.value = null
        }
    }
}
