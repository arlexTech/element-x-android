/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.x

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.bumble.appyx.core.integration.NodeHost
import com.bumble.appyx.core.integrationpoint.NodeActivity
import com.bumble.appyx.core.plugin.NodeReadyObserver
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.lockscreen.api.LockScreenEntryPoint
import io.element.android.features.lockscreen.api.LockScreenLockState
import io.element.android.features.lockscreen.api.LockScreenService
import io.element.android.features.lockscreen.api.handleSecureFlag
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.services.analytics.compose.LocalAnalyticsService
import io.element.android.x.di.AppBindings
import io.element.android.x.intent.SafeUriHandler
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import timber.log.Timber

private val loggerTag = LoggerTag("BubbleActivity")

/**
 * A full activity used exclusively for the Android Bubble floating window.
 *
 * Unlike [MainActivity], this activity does NOT have launchMode="singleTask", which is required
 * so that Android can embed it inside the bubble's floating overlay. It bootstraps the full
 * navigation stack (via [MainNode]) so the correct conversation is shown inside the bubble window.
 */
class BubbleActivity : NodeActivity() {
    private lateinit var mainNode: MainNode
    private lateinit var appBindings: AppBindings

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.e("BubbleDebug", "BubbleActivity.onCreate intent.data=${intent?.data}")
        // NodeActivity extends AppCompatActivity which requires an AppCompat theme.
        // Theme.ElementX uses Theme.Material3 (not AppCompat), so we must set an AppCompat
        // compatible theme before super.onCreate(). Compose overrides the visual appearance anyway.
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar)
        super.onCreate(savedInstanceState)
        appBindings = bindings()
        enableEdgeToEdge()
        setContent {
            MainContent(appBindings)
        }
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.e("BubbleDebug", "BubbleActivity.onStart")
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.e("BubbleDebug", "BubbleActivity.onResume")
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.e("BubbleDebug", "BubbleActivity.onPause")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.e("BubbleDebug", "BubbleActivity.onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.e("BubbleDebug", "BubbleActivity.onDestroy isFinishing=$isFinishing")
    }

    override fun finish() {
        android.util.Log.e("BubbleDebug", "BubbleActivity.finish() called", Throwable())
        super.finish()
    }

    override fun finishAndRemoveTask() {
        android.util.Log.e("BubbleDebug", "BubbleActivity.finishAndRemoveTask() called", Throwable())
        super.finishAndRemoveTask()
    }

    override fun finishAffinity() {
        android.util.Log.e("BubbleDebug", "BubbleActivity.finishAffinity() called", Throwable())
        super.finishAffinity()
    }

    override fun finishActivity(requestCode: Int) {
        android.util.Log.e("BubbleDebug", "BubbleActivity.finishActivity(requestCode=$requestCode) called", Throwable())
        super.finishActivity(requestCode)
    }

    @Composable
    private fun MainContent(appBindings: AppBindings) {
        val colors by remember {
            appBindings.enterpriseService().semanticColorsFlow(sessionId = null)
        }.collectAsState(SemanticColorsLightDark.default)
        ElementThemeApp(
            appPreferencesStore = appBindings.preferencesStore(),
            compoundLight = colors.light,
            compoundDark = colors.dark,
            buildMeta = appBindings.buildMeta()
        ) {
            CompositionLocalProvider(
                LocalSnackbarDispatcher provides appBindings.snackbarDispatcher(),
                LocalUriHandler provides SafeUriHandler(this),
                LocalAnalyticsService provides appBindings.analyticsService(),
                io.element.android.libraries.architecture.appyx.LocalIsBubble provides true,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ElementTheme.colors.bgCanvasDefault),
                ) {
                    NodeHost(integrationPoint = appyxV1IntegrationPoint) {
                        MainNode(
                            it,
                            plugins = listOf(
                                io.element.android.libraries.architecture.appyx.BubblePlugin(),
                                object : NodeReadyObserver<MainNode> {
                                    override fun init(node: MainNode) {
                                        android.util.Log.e("BubbleDebug", "BubbleActivity MainNode ready")
                                        mainNode = node
                                        android.util.Log.e("BubbleDebug", "BubbleActivity: mainNode.handleIntent(intent)")
                                        mainNode.handleIntent(intent)
                                    }
                                }
                            ),
                            context = applicationContext
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.e("BubbleDebug", "BubbleActivity.onNewIntent")
        if (::mainNode.isInitialized) {
            mainNode.handleIntent(intent)
        } else {
            setIntent(intent)
        }
    }
}
