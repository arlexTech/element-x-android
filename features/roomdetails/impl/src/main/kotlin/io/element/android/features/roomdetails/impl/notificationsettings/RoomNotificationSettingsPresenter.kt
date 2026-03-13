/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl.notificationsettings

import io.element.android.features.roomdetails.impl.R
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.libraries.androidutils.notifications.SystemNotificationsEnabledProvider
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.core.coroutine.suspendWithMinimumDuration
import io.element.android.libraries.matrix.api.notificationsettings.NotificationSettingsService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.push.api.notifications.NotificationCleaner
import androidx.compose.runtime.collectAsState
import io.element.android.libraries.designsystem.utils.snackbar.LocalSnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.designsystem.utils.snackbar.collectSnackbarMessageAsState
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.room.RoomNotificationSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class RoomNotificationSettingsPresenter(
    private val room: JoinedRoom,
    private val notificationSettingsService: NotificationSettingsService,
    private val appPreferencesStore: AppPreferencesStore,
    private val sessionPreferencesStore: SessionPreferencesStore,
    private val notificationCleaner: NotificationCleaner,
    private val snackbarDispatcher: io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher,
    private val systemNotificationsEnabledProvider: SystemNotificationsEnabledProvider,
    @ApplicationContext private val context: Context,
    @Assisted private val showUserDefinedSettingStyle: Boolean,
) : Presenter<RoomNotificationSettingsState> {
    @AssistedFactory
    interface Factory {
        fun create(showUserDefinedSettingStyle: Boolean): RoomNotificationSettingsPresenter
    }

    @Composable
    override fun present(): RoomNotificationSettingsState {
        var shouldDisplayMentionsOnlyDisclaimer by remember { mutableStateOf(false) }
        val defaultRoomNotificationMode: MutableState<RoomNotificationMode?> = rememberSaveable {
            mutableStateOf(null)
        }
        val localCoroutineScope = rememberCoroutineScope()
        val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()
        val setNotificationSettingAction: MutableState<AsyncAction<Unit>> = remember { mutableStateOf(AsyncAction.Uninitialized) }
        val restoreDefaultAction: MutableState<AsyncAction<Unit>> = remember { mutableStateOf(AsyncAction.Uninitialized) }

        val roomNotificationSettings: MutableState<AsyncData<RoomNotificationSettings>> = remember {
            mutableStateOf(AsyncData.Uninitialized)
        }

        // We store state of which mode the user has set via the notification service before the new push settings have been updated.
        // We show this state immediately to the user and debounce updates to notification settings to hide some invalid states returned
        // by the rust sdk during these two events that cause the radio buttons ot toggle quickly back and forth.
        // This is a client side work-around until bulk push rule updates are supported.
        // ref: https://github.com/matrix-org/matrix-spec-proposals/pull/3934
        val pendingRoomNotificationMode: MutableState<RoomNotificationMode?> = remember {
            mutableStateOf(null)
        }

        // We store state of whether the user has set the notifications settings to default or custom via the notification service.
        // We show this state immediately to the user and debounce updates to notification settings to hide some invalid states returned
        // by the rust sdk during these two events that cause the switch ot toggle quickly back and forth.
        // This is a client side work-around until bulk push rule updates are supported.
        // ref: https://github.com/matrix-org/matrix-spec-proposals/pull/3934
        val pendingSetDefault: MutableState<Boolean?> = remember {
            mutableStateOf(null)
        }

        val displayName by produceState(room.info().name) {
            room.roomInfoFlow.collect { value = it.name }
        }

        val isRoomEncrypted by produceState(room.info().isEncrypted) {
            room.roomInfoFlow.collect { value = it.isEncrypted }
        }

        val isBubblesEnabled by appPreferencesStore.isBubblesEnabledFlow()
            .collectAsState(initial = false)

        val isBubblesEnabledForAllConversations by appPreferencesStore.isBubblesEnabledForAllConversationsFlow()
            .collectAsState(initial = false)

        val isBubbleEnabledForRoom by sessionPreferencesStore.isBubbleEnabledForRoom(room.roomId.value)
            .collectAsState(initial = false)

        val lifecycleOwner = LocalLifecycleOwner.current
        val isBubblesAllowedInSettings by produceState(initialValue = false, lifecycleOwner) {
            value = systemNotificationsEnabledProvider.areBubblesAllowed(context)
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    value = systemNotificationsEnabledProvider.areBubblesAllowed(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            awaitDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        LaunchedEffect(Unit) {
            getDefaultRoomNotificationMode(defaultRoomNotificationMode)
            fetchNotificationSettings(pendingRoomNotificationMode, roomNotificationSettings)
            observeNotificationSettings(pendingRoomNotificationMode, roomNotificationSettings)
        }

        LaunchedEffect(isRoomEncrypted) {
            shouldDisplayMentionsOnlyDisclaimer = isRoomEncrypted == true &&
                !notificationSettingsService.canHomeServerPushEncryptedEventsToDevice().getOrDefault(true)
        }

        fun handleEvent(event: RoomNotificationSettingsEvent) {
            when (event) {
                is RoomNotificationSettingsEvent.ChangeRoomNotificationMode -> {
                    localCoroutineScope.setRoomNotificationMode(event.mode, pendingRoomNotificationMode, pendingSetDefault, setNotificationSettingAction)
                }
                is RoomNotificationSettingsEvent.SetNotificationMode -> {
                    if (event.isDefault) {
                        localCoroutineScope.restoreDefaultRoomNotificationMode(restoreDefaultAction, pendingSetDefault)
                    } else {
                        defaultRoomNotificationMode.value?.let {
                            localCoroutineScope.setRoomNotificationMode(it, pendingRoomNotificationMode, pendingSetDefault, setNotificationSettingAction)
                        }
                    }
                }
                is RoomNotificationSettingsEvent.DeleteCustomNotification -> {
                    localCoroutineScope.restoreDefaultRoomNotificationMode(restoreDefaultAction, pendingSetDefault)
                }
                RoomNotificationSettingsEvent.ClearSetNotificationError -> {
                    setNotificationSettingAction.value = AsyncAction.Uninitialized
                }
                RoomNotificationSettingsEvent.ClearRestoreDefaultError -> {
                    restoreDefaultAction.value = AsyncAction.Uninitialized
                }
                is RoomNotificationSettingsEvent.SetBubbleEnabled -> {
                    localCoroutineScope.launch {
                        sessionPreferencesStore.setBubbleEnabledForRoom(room.roomId.value, event.enabled)
                        if (event.enabled && systemNotificationsEnabledProvider.areBubblesAllowed(context)) {
                            notificationCleaner.triggerBubble(room.sessionId, room.roomId)
                        }
                    }
                    if (event.enabled && !systemNotificationsEnabledProvider.areBubblesAllowed(context)) {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                        } else {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                putExtra(Settings.EXTRA_CHANNEL_ID, context.packageName) // Android 10 way
                            }
                        }
                        snackbarDispatcher.post(SnackbarMessage(io.element.android.libraries.ui.strings.R.string.screen_room_notification_settings_bubbles_disabled_globally))
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        context.startActivity(intent)
                    }
                }
                is RoomNotificationSettingsEvent.ShowSnackbar -> {
                    snackbarDispatcher.post(SnackbarMessage(event.messageResId))
                }
            }
        }

        return RoomNotificationSettingsState(
            showUserDefinedSettingStyle = showUserDefinedSettingStyle,
            roomName = displayName.orEmpty(),
            roomNotificationSettings = roomNotificationSettings.value,
            pendingRoomNotificationMode = pendingRoomNotificationMode.value,
            pendingSetDefault = pendingSetDefault.value,
            defaultRoomNotificationMode = defaultRoomNotificationMode.value,
            setNotificationSettingAction = setNotificationSettingAction.value,
            restoreDefaultAction = restoreDefaultAction.value,
            displayMentionsOnlyDisclaimer = shouldDisplayMentionsOnlyDisclaimer,
            isBubblesEnabled = isBubblesEnabled,
            isBubblesEnabledForAllConversations = isBubblesEnabledForAllConversations,
            isBubbleEnabledForRoom = isBubbleEnabledForRoom,
            isBubblesAllowedInSettings = isBubblesAllowedInSettings,
            snackbarMessage = snackbarMessage,
            eventSink = ::handleEvent,
        )
    }

    @OptIn(FlowPreview::class)
    private fun CoroutineScope.observeNotificationSettings(
        pendingModeState: MutableState<RoomNotificationMode?>,
        roomNotificationSettings: MutableState<AsyncData<RoomNotificationSettings>>
    ) {
        notificationSettingsService.notificationSettingsChangeFlow
            .debounce(0.5.seconds)
            .onEach {
                fetchNotificationSettings(pendingModeState, roomNotificationSettings)
            }
            .launchIn(this)
    }

    private fun CoroutineScope.fetchNotificationSettings(
        pendingModeState: MutableState<RoomNotificationMode?>,
        roomNotificationSettings: MutableState<AsyncData<RoomNotificationSettings>>
    ) = launch {
        suspend {
            val isEncrypted = room.info().isEncrypted ?: room.getUpdatedIsEncrypted().getOrThrow()
            pendingModeState.value = null
            notificationSettingsService.getRoomNotificationSettings(room.roomId, isEncrypted, room.isOneToOne).getOrThrow()
        }.runCatchingUpdatingState(roomNotificationSettings)
    }

    private fun CoroutineScope.getDefaultRoomNotificationMode(
        defaultRoomNotificationMode: MutableState<RoomNotificationMode?>
    ) = launch {
        val isEncrypted = room.info().isEncrypted ?: room.getUpdatedIsEncrypted().getOrThrow()
        defaultRoomNotificationMode.value = notificationSettingsService.getDefaultRoomNotificationMode(
            isEncrypted,
            room.isOneToOne
        ).getOrThrow()
    }

    private fun CoroutineScope.setRoomNotificationMode(
        mode: RoomNotificationMode,
        pendingModeState: MutableState<RoomNotificationMode?>,
        pendingDefaultState: MutableState<Boolean?>,
        action: MutableState<AsyncAction<Unit>>
    ) = launch {
        suspendWithMinimumDuration {
            pendingModeState.value = mode
            pendingDefaultState.value = false
            val result = notificationSettingsService.setRoomNotificationMode(room.roomId, mode)
            if (result.isFailure) {
                pendingModeState.value = null
                pendingDefaultState.value = null
            }
            result.getOrThrow()
        }.runCatchingUpdatingState(action)
    }

    private fun CoroutineScope.restoreDefaultRoomNotificationMode(
        action: MutableState<AsyncAction<Unit>>,
        pendingDefaultState: MutableState<Boolean?>
    ) = launch {
        suspendWithMinimumDuration {
            pendingDefaultState.value = true
            val result = notificationSettingsService.restoreDefaultRoomNotificationMode(room.roomId)
            if (result.isFailure) {
                pendingDefaultState.value = null
            }
            result.getOrThrow()
        }.runCatchingUpdatingState(action)
    }
}
