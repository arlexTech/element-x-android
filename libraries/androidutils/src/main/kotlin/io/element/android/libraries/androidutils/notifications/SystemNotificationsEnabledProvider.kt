/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.androidutils.notifications

import androidx.core.app.NotificationManagerCompat
import android.os.Build
import dev.zacsweers.metro.AppScope
import android.app.NotificationManager
import android.content.Context
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn

interface SystemNotificationsEnabledProvider {
    fun notificationsEnabled(): Boolean
    fun areBubblesAllowed(context: Context): Boolean
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultSystemNotificationsEnabledProvider(
    private val notificationManager: NotificationManagerCompat,
) : SystemNotificationsEnabledProvider {
    override fun notificationsEnabled(): Boolean {
        return notificationManager.areNotificationsEnabled()
    }

    override fun areBubblesAllowed(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                notificationManager.areBubblesAllowed() && notificationManager.bubblePreference != NotificationManager.BUBBLE_PREFERENCE_NONE
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                notificationManager.areBubblesAllowed()
            }
            else -> true
        }
    }
}
