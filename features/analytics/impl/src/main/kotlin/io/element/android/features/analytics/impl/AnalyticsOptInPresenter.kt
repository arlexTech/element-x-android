/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.analytics.impl

import androidx.compose.runtime.Composable
import dev.zacsweers.metro.Inject
import androidx.compose.runtime.rememberCoroutineScope
import io.element.android.appconfig.AnalyticsConfig
import io.element.android.features.analytics.api.AnalyticsOptInEvents
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.analytics.api.AnalyticsOptInNodeCallback

class AnalyticsOptInPresenter @AssistedInject constructor(
    private val buildMeta: BuildMeta,
    private val analyticsService: AnalyticsService,
    @Assisted private val callback: AnalyticsOptInNodeCallback,
) : Presenter<AnalyticsOptInState> {
    @AssistedFactory
    interface Factory {
        fun create(callback: AnalyticsOptInNodeCallback): AnalyticsOptInPresenter
    }

    @Composable
    override fun present(): AnalyticsOptInState {
        val localCoroutineScope = rememberCoroutineScope()

        fun handleEvent(event: AnalyticsOptInEvents) {
            when (event) {
                is AnalyticsOptInEvents.EnableAnalytics -> localCoroutineScope.setIsEnabled(event.isEnabled)
            }
            localCoroutineScope.launch {
                analyticsService.setDidAskUserConsent()
                callback.onAnalyticsOptInFinished()
            }
        }

        return AnalyticsOptInState(
            applicationName = buildMeta.applicationName,
            hasPolicyLink = AnalyticsConfig.POLICY_LINK.isNotEmpty(),
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.setIsEnabled(enabled: Boolean) = launch {
        analyticsService.setUserConsent(enabled)
    }
}
