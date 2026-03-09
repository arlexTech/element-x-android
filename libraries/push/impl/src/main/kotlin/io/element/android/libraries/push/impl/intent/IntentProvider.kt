/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.intent

import android.content.Intent
import android.os.Bundle
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId

interface IntentProvider {
    /**
     * Provide an intent to start the application on a room or thread.
     */
    fun getViewRoomIntent(
        sessionId: SessionId,
        roomId: RoomId?,
        threadId: ThreadId?,
        eventId: EventId?,
        extras: Bundle? = null,
    ): Intent

    /**
     * Provide an intent specifically for a bubble notification.
     * Bubbles cannot embed an activity with launchMode="singleTask", so this should target
     * a dedicated BubbleActivity that acts as a trampoline.
     * The default implementation falls back to [getViewRoomIntent].
     */
    fun getBubbleRoomIntent(
        sessionId: SessionId,
        roomId: RoomId,
        eventId: EventId?,
        extras: Bundle? = null,
    ): Intent = getViewRoomIntent(sessionId, roomId, null, eventId, extras)
}
