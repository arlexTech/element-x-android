/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.notifications

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.notifications.SystemNotificationsEnabledProvider
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.designsystem.utils.snackbar.collectSnackbarMessageAsState
import io.element.android.libraries.architecture.runUpdatingStateNoSuccess
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.fullscreenintent.api.FullScreenIntentPermissionsState
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.notificationsettings.NotificationSettingsService
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.push.api.PushService
import io.element.android.libraries.pushproviders.api.Distributor
import io.element.android.libraries.pushproviders.api.PushProvider
import io.element.android.libraries.pushstore.api.UserPushStore
import io.element.android.libraries.pushstore.api.UserPushStoreFactory
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Inject
class NotificationSettingsPresenter(
    private val notificationSettingsService: NotificationSettingsService,
    private val userPushStoreFactory: UserPushStoreFactory,
    private val matrixClient: MatrixClient,
    private val pushService: PushService,
    private val systemNotificationsEnabledProvider: SystemNotificationsEnabledProvider,
    private val appPreferencesStore: AppPreferencesStore,
    private val fullScreenIntentPermissionsPresenter: Presenter<FullScreenIntentPermissionsState>,
    private val snackbarDispatcher: io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher,
    @io.element.android.libraries.di.annotations.ApplicationContext
    private val context: Context,
    @SessionCoroutineScope
    private val sessionCoroutineScope: CoroutineScope,
) : Presenter<NotificationSettingsState> {
    @Composable
    override fun present(): NotificationSettingsState {
        val userPushStore = remember { userPushStoreFactory.getOrCreate(matrixClient.sessionId) }
        val systemNotificationsEnabled: MutableState<Boolean> = remember {
            mutableStateOf(systemNotificationsEnabledProvider.notificationsEnabled())
        }
        val changeNotificationSettingAction: MutableState<AsyncAction<Unit>> = remember { mutableStateOf(AsyncAction.Uninitialized) }

        val localCoroutineScope = rememberCoroutineScope()
        val appNotificationsEnabled by remember {
            userPushStore.getNotificationEnabledForDevice()
        }.collectAsState(initial = false)

        val isBubblesEnabled by appPreferencesStore.isBubblesEnabledFlow()
            .collectAsState(initial = false)

        val isBubblesEnabledForAllConversations by appPreferencesStore.isBubblesEnabledForAllConversationsFlow()
            .collectAsState(initial = false)

        val lifecycleOwner = LocalLifecycleOwner.current
        val isBubblesAllowedInSettings by produceState<Boolean>(initialValue = false, lifecycleOwner) {
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

        val snackbarMessage by snackbarDispatcher.collectSnackbarMessageAsState()

        val matrixSettings: MutableState<NotificationSettingsState.MatrixSettings> = remember {
            mutableStateOf(NotificationSettingsState.MatrixSettings.Uninitialized)
        }

        // Used to force a recomposition
        var refreshFullScreenIntentSettings by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            fetchSettings(matrixSettings)
            observeNotificationSettings(matrixSettings, changeNotificationSettingAction)
        }

        // List of PushProvider -> Distributor
        val distributors = remember {
            pushService.getAvailablePushProviders()
                .flatMap { pushProvider ->
                    pushProvider.getDistributors().map { distributor ->
                        pushProvider to distributor
                    }
                }
        }
        // List of Distributors
        val availableDistributors = remember {
            distributors.map { it.second }.toImmutableList()
        }

        val currentDistributorState = remember { mutableStateOf<AsyncData<Distributor>>(AsyncData.Uninitialized) }
        var refreshPushProvider by remember { mutableIntStateOf(0) }

        LaunchedEffect(refreshPushProvider) {
            val p = pushService.getCurrentPushProvider(matrixClient.sessionId)
            val distributor = p?.getCurrentDistributor(matrixClient.sessionId)
            currentDistributorState.value = if (distributor != null) {
                AsyncData.Success<Distributor>(distributor)
            } else {
                AsyncData.Failure<Distributor>(Exception("Failed to get current push provider"))
            }
        }

        var showChangePushProviderDialog by remember { mutableStateOf(false) }

        fun CoroutineScope.changePushProvider(
            data: Pair<PushProvider, Distributor>?
        ) = launch {
            showChangePushProviderDialog = false
            data ?: return@launch
            val (pushProvider, distributor) = data
            // No op if the distributor is the same.
            if (distributor == currentDistributorState.value.dataOrNull()) return@launch
            currentDistributorState.value = AsyncData.Loading<Distributor>(currentDistributorState.value.dataOrNull())
            pushService.registerWith(
                matrixClient = matrixClient,
                pushProvider = pushProvider,
                distributor = distributor
            )
                .fold(
                    {
                        refreshPushProvider++
                    },
                    {
                        currentDistributorState.value = AsyncData.Failure<Distributor>(it)
                    }
                )
        }

        fun handleEvent(event: NotificationSettingsEvents) {
            when (event) {
                is NotificationSettingsEvents.SetAtRoomNotificationsEnabled -> {
                    localCoroutineScope.setAtRoomNotificationsEnabled(event.enabled, changeNotificationSettingAction)
                }
                is NotificationSettingsEvents.SetCallNotificationsEnabled -> {
                    localCoroutineScope.setCallNotificationsEnabled(event.enabled, changeNotificationSettingAction)
                }
                is NotificationSettingsEvents.SetInviteForMeNotificationsEnabled -> {
                    localCoroutineScope.setInviteForMeNotificationsEnabled(event.enabled, changeNotificationSettingAction)
                }
                is NotificationSettingsEvents.SetNotificationsEnabled -> sessionCoroutineScope.setNotificationsEnabled(userPushStore, event.enabled)
                NotificationSettingsEvents.ClearConfigurationMismatchError -> {
                    matrixSettings.value = NotificationSettingsState.MatrixSettings.Invalid(fixFailed = false)
                }
                NotificationSettingsEvents.FixConfigurationMismatch -> localCoroutineScope.fixConfigurationMismatch(matrixSettings)
                NotificationSettingsEvents.RefreshSystemNotificationsEnabled -> {
                    systemNotificationsEnabled.value = systemNotificationsEnabledProvider.notificationsEnabled()
                    refreshFullScreenIntentSettings++
                }
                NotificationSettingsEvents.ClearNotificationChangeError -> changeNotificationSettingAction.value = AsyncAction.Uninitialized
                NotificationSettingsEvents.ChangePushProvider -> showChangePushProviderDialog = true
                NotificationSettingsEvents.CancelChangePushProvider -> showChangePushProviderDialog = false
                is NotificationSettingsEvents.SetPushProvider -> localCoroutineScope.changePushProvider(distributors.getOrNull(event.index))
                is NotificationSettingsEvents.SetBubblesEnabled -> {
                    localCoroutineScope.launch {
                        appPreferencesStore.setBubblesEnabled(event.enabled)
                        if (!event.enabled) {
                            appPreferencesStore.setBubblesEnabledForAllConversations(false)
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
                                putExtra(Settings.EXTRA_CHANNEL_ID, context.packageName)
                            }
                        }
                        snackbarDispatcher.post(SnackbarMessage(io.element.android.libraries.ui.strings.R.string.screen_room_notification_settings_bubbles_disabled_globally))
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        context.startActivity(intent)
                    }
                }
                is NotificationSettingsEvents.SetBubblesEnabledForAllConversations -> {
                    localCoroutineScope.launch { appPreferencesStore.setBubblesEnabledForAllConversations(event.enabled) }
                }
            }
        }

        return NotificationSettingsState(
            matrixSettings = matrixSettings.value,
            appSettings = NotificationSettingsState.AppSettings(
                systemNotificationsEnabled = systemNotificationsEnabled.value,
                appNotificationsEnabled = appNotificationsEnabled,
                isBubblesEnabled = isBubblesEnabled,
                isBubblesEnabledForAllConversations = isBubblesEnabledForAllConversations,
                isBubblesAllowedInSettings = isBubblesAllowedInSettings,
            ),
            changeNotificationSettingAction = changeNotificationSettingAction.value,
            currentPushDistributor = currentDistributorState.value,
            availablePushDistributors = availableDistributors,
            showChangePushProviderDialog = showChangePushProviderDialog,
            fullScreenIntentPermissionsState = key(refreshFullScreenIntentSettings) { fullScreenIntentPermissionsPresenter.present() },
            snackbarMessage = snackbarMessage,
            eventSink = ::handleEvent,
        )
    }

    @OptIn(FlowPreview::class)
    private fun CoroutineScope.observeNotificationSettings(
        target: MutableState<NotificationSettingsState.MatrixSettings>,
        changeNotificationSettingAction: MutableState<AsyncAction<Unit>>,
    ) {
        notificationSettingsService.notificationSettingsChangeFlow
            .debounce(0.5.seconds)
            .onEach {
                fetchSettings(target)
                changeNotificationSettingAction.value = AsyncAction.Uninitialized
            }
            .launchIn(this)
    }

    private fun CoroutineScope.fetchSettings(target: MutableState<NotificationSettingsState.MatrixSettings>) = launch {
        val groupDefaultMode = notificationSettingsService.getDefaultRoomNotificationMode(isEncrypted = false, isOneToOne = false).getOrThrow()
        val encryptedGroupDefaultMode = notificationSettingsService.getDefaultRoomNotificationMode(isEncrypted = true, isOneToOne = false).getOrThrow()

        val oneToOneDefaultMode = notificationSettingsService.getDefaultRoomNotificationMode(isEncrypted = false, isOneToOne = true).getOrThrow()
        val encryptedOneToOneDefaultMode = notificationSettingsService.getDefaultRoomNotificationMode(isEncrypted = true, isOneToOne = true).getOrThrow()

        if (groupDefaultMode != encryptedGroupDefaultMode || oneToOneDefaultMode != encryptedOneToOneDefaultMode) {
            target.value = NotificationSettingsState.MatrixSettings.Invalid(fixFailed = false)
            return@launch
        }

        val callNotificationsEnabled = notificationSettingsService.isCallEnabled().getOrThrow()
        val atRoomNotificationsEnabled = notificationSettingsService.isRoomMentionEnabled().getOrThrow()
        val inviteForMeNotificationsEnabled = notificationSettingsService.isInviteForMeEnabled().getOrThrow()

        target.value = NotificationSettingsState.MatrixSettings.Valid(
            atRoomNotificationsEnabled = atRoomNotificationsEnabled,
            callNotificationsEnabled = callNotificationsEnabled,
            inviteForMeNotificationsEnabled = inviteForMeNotificationsEnabled,
            defaultGroupNotificationMode = encryptedGroupDefaultMode,
            defaultOneToOneNotificationMode = encryptedOneToOneDefaultMode,
        )
    }

    private fun CoroutineScope.fixConfigurationMismatch(target: MutableState<NotificationSettingsState.MatrixSettings>) = launch {
        runCatchingExceptions {
            val groupDefaultMode = notificationSettingsService.getDefaultRoomNotificationMode(isEncrypted = false, isOneToOne = false).getOrThrow()
            val encryptedGroupDefaultMode = notificationSettingsService.getDefaultRoomNotificationMode(isEncrypted = true, isOneToOne = false).getOrThrow()

            if (groupDefaultMode != encryptedGroupDefaultMode) {
                notificationSettingsService.setDefaultRoomNotificationMode(
                    isEncrypted = encryptedGroupDefaultMode != RoomNotificationMode.ALL_MESSAGES,
                    mode = RoomNotificationMode.ALL_MESSAGES,
                    isOneToOne = false,
                )
            }

            val oneToOneDefaultMode = notificationSettingsService.getDefaultRoomNotificationMode(isEncrypted = false, isOneToOne = true).getOrThrow()
            val encryptedOneToOneDefaultMode = notificationSettingsService.getDefaultRoomNotificationMode(isEncrypted = true, isOneToOne = true).getOrThrow()

            if (oneToOneDefaultMode != encryptedOneToOneDefaultMode) {
                notificationSettingsService.setDefaultRoomNotificationMode(
                    isEncrypted = encryptedOneToOneDefaultMode != RoomNotificationMode.ALL_MESSAGES,
                    mode = RoomNotificationMode.ALL_MESSAGES,
                    isOneToOne = true,
                )
            }
        }.fold(
            onSuccess = {},
            onFailure = {
                target.value = NotificationSettingsState.MatrixSettings.Invalid(fixFailed = true)
            }
        )
    }

    private fun CoroutineScope.setAtRoomNotificationsEnabled(enabled: Boolean, action: MutableState<AsyncAction<Unit>>) = launch {
        action.runUpdatingStateNoSuccess {
            notificationSettingsService.setRoomMentionEnabled(enabled)
        }
    }

    private fun CoroutineScope.setCallNotificationsEnabled(enabled: Boolean, action: MutableState<AsyncAction<Unit>>) = launch {
        action.runUpdatingStateNoSuccess {
            notificationSettingsService.setCallEnabled(enabled)
        }
    }

    private fun CoroutineScope.setInviteForMeNotificationsEnabled(enabled: Boolean, action: MutableState<AsyncAction<Unit>>) = launch {
        action.runUpdatingStateNoSuccess {
            notificationSettingsService.setInviteForMeEnabled(enabled)
        }
    }

    private fun CoroutineScope.setNotificationsEnabled(userPushStore: UserPushStore, enabled: Boolean) = launch {
        userPushStore.setNotificationEnabledForDevice(enabled)
        if (enabled) {
            pushService.ensurePusherIsRegistered(matrixClient)
        } else {
            pushService.getCurrentPushProvider(matrixClient.sessionId)?.unregister(matrixClient)
        }
    }
}
