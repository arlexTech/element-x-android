/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.roomlist.RoomListService
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import io.element.android.libraries.sessionstorage.api.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Watch for changes in the room list and clear notifications for rooms that are no longer unread.
 * This handles the case where a room is marked as read on another device.
 */
@SingleIn(AppScope::class)
class SyncNotificationWatcher @Inject constructor(
    private val matrixClientProvider: MatrixClientProvider,
    private val notificationCleaner: NotificationCleaner,
    private val sessionStore: SessionStore,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) {

    private val sessionJobs = ConcurrentHashMap<SessionId, Job>()

    fun start() {
        Timber.d("Starting SyncNotificationWatcher")
        sessionStore.sessionsFlow()
            .onEach { sessions ->
                val newSessionIds = sessions.map { SessionId(it.userId) }.toSet()
                
                // Stop jobs for removed sessions
                val removedSessions = sessionJobs.keys - newSessionIds
                removedSessions.forEach { sessionId ->
                    sessionJobs.remove(sessionId)?.cancel()
                }

                // Start jobs for added sessions
                val addedSessions = newSessionIds - sessionJobs.keys
                addedSessions.forEach { sessionId ->
                    observeSession(sessionId)
                }
            }
            .launchIn(appCoroutineScope)
    }

    private fun observeSession(sessionId: SessionId) {
        if (sessionJobs.containsKey(sessionId)) return

        val job = appCoroutineScope.launch {
            val matrixClient = matrixClientProvider.getOrRestore(sessionId).getOrNull()
            if (matrixClient == null) {
                Timber.e("Failed to restore MatrixClient for session $sessionId")
                return@launch
            }

            Timber.d("Starting room summary observation for session $sessionId")
            matrixClient.roomListService.allRooms.summaries
                .onEach { summaries ->
                    summaries.forEach { summary ->
                        val info = summary.info
                        // If the room is not unread and has no notifications/mentions, clear its notifications.
                        // We match the "isHighlighted" logic from RoomListRoomSummary but without the MUTE check,
                        // as we want to clear notifications even if the room is muted (it shouldn't have been there anyway).
                        val hasNotifications = info.numUnreadNotifications > 0 ||
                            info.numUnreadMentions > 0 ||
                            info.isMarkedUnread
                        
                        if (!hasNotifications) {
                            notificationCleaner.clearMessagesForRoom(sessionId, summary.roomId)
                        }
                    }
                }
                .launchIn(this)
        }
        
        sessionJobs[sessionId] = job
    }
}
