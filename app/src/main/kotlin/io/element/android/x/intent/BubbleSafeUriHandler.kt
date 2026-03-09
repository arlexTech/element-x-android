/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x.intent

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.platform.UriHandler
import io.element.android.libraries.androidutils.system.openUrlInExternalApp
import io.element.android.x.MinimizeBubbleActivity

/**
 * A [UriHandler] for use inside bubble windows.
 * Opens the URL in an external app and then minimizes the bubble
 * so the browser comes to the foreground.
 */
class BubbleSafeUriHandler(private val activity: Activity) : UriHandler {
    override fun openUri(uri: String) {
        try {
            // Launch minimize trick and pass the URI to open after minimization
            val minimizeIntent = Intent(activity, MinimizeBubbleActivity::class.java)
            minimizeIntent.putExtra("EXTRA_URI", uri)
            minimizeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            activity.startActivity(minimizeIntent)
        } catch (e: Exception) {
            // Fallback if no browser
        }
    }
}
