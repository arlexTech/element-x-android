/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import io.element.android.libraries.matrix.ui.media.ImageLoaderHolder
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import im.vector.app.features.analytics.plan.MobileScreen
import io.element.android.annotations.ContributesNode
import io.element.android.features.leaveroom.api.LeaveRoomRenderer
import io.element.android.libraries.androidutils.system.startSharePlainTextIntent
import io.element.android.libraries.architecture.appyx.launchMolecule
import io.element.android.libraries.architecture.appyx.anyParent
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.BaseRoom
import io.element.android.libraries.push.api.notifications.NotificationBitmapLoader
import io.element.android.libraries.push.api.notifications.NotificationIdProvider
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import io.element.android.libraries.androidutils.R as AndroidUtilsR

@ContributesNode(RoomScope::class)
@AssistedInject
class RoomDetailsNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val presenter: RoomDetailsPresenter,
    private val client: MatrixClient,
    private val room: BaseRoom,
    private val analyticsService: AnalyticsService,
    private val leaveRoomRenderer: LeaveRoomRenderer,
    private val notificationBitmapLoader: NotificationBitmapLoader,
    private val imageLoaderHolder: ImageLoaderHolder,
    private val dispatchers: CoroutineDispatchers,
) : Node(buildContext, plugins = plugins) {
    interface Callback : Plugin {
        fun navigateToRoomMemberList()
        fun navigateToInviteMembers()
        fun navigateToRoomDetailsEdit()
        fun navigateToRoomNotificationSettings()
        fun navigateToAvatarPreview(name: String, url: String)
        fun navigateToPollHistory()
        fun navigateToMediaGallery()
        fun navigateToAdminSettings()
        fun navigateToPinnedMessagesList()
        fun navigateToKnockRequestsList()
        fun navigateToSecurityAndPrivacy()
        fun navigateToRoomMemberDetails(userId: UserId)
        fun navigateToRoomCall()
        fun navigateToReportRoom()
        fun navigateToSelectNewOwnersWhenLeaving()
    }

    private val callback: Callback = callback()

    init {
        lifecycle.subscribe(
            onResume = {
                analyticsService.screen(MobileScreen(screenName = MobileScreen.ScreenName.RoomDetails))
            }
        )
    }

    private fun CoroutineScope.onShareRoom(context: Context) = launch {
        room.getPermalink()
            .onSuccess { permalink ->
                context.startSharePlainTextIntent(
                    activityResultLauncher = null,
                    chooserTitle = context.getString(R.string.screen_room_details_share_room_title),
                    text = permalink,
                    noActivityFoundMessage = context.getString(AndroidUtilsR.string.error_no_compatible_app_found)
                )
            }
            .onFailure {
                Timber.e(it)
            }
    }

    private val stateFlow = launchMolecule { presenter.present() }

    fun onNewOwnersSelected() {
        stateFlow.value.eventSink(RoomDetailsEvent.LeaveRoom(needsConfirmation = false))
    }

    @Composable
    override fun View(modifier: Modifier) {
        val context = LocalContext.current
        val activity = requireNotNull(androidx.activity.compose.LocalActivity.current)
        val state by stateFlow.collectAsState()
        val isBubble = androidx.compose.runtime.remember { anyParent { it.plugins.filterIsInstance<io.element.android.libraries.architecture.appyx.BubblePlugin>().isNotEmpty() } }

        fun onShareRoom() {
            lifecycleScope.onShareRoom(context)
        }

        fun onActionClick(action: RoomDetailsAction) {
            when (action) {
                RoomDetailsAction.Edit -> {
                    callback.navigateToRoomDetailsEdit()
                }
                RoomDetailsAction.AddTopic -> {
                    callback.navigateToRoomDetailsEdit()
                }
            }
        }

        RoomDetailsView(
            state = state,
            modifier = modifier,
            goBack = ::navigateUp,
            onActionClick = ::onActionClick,
            onShareRoom = ::onShareRoom,
            openRoomMemberList = callback::navigateToRoomMemberList,
            openRoomNotificationSettings = callback::navigateToRoomNotificationSettings,
            invitePeople = callback::navigateToInviteMembers,
            openAvatarPreview = callback::navigateToAvatarPreview,
            openPollHistory = callback::navigateToPollHistory,
            openMediaGallery = callback::navigateToMediaGallery,
            openAdminSettings = callback::navigateToAdminSettings,
            onJoinCallClick = callback::navigateToRoomCall,
            onPinnedMessagesClick = callback::navigateToPinnedMessagesList,
            onKnockRequestsClick = callback::navigateToKnockRequestsList,
            onSecurityAndPrivacyClick = callback::navigateToSecurityAndPrivacy,
            onProfileClick = callback::navigateToRoomMemberDetails,
            onReportRoomClick = callback::navigateToReportRoom,
            leaveRoomView = {
                leaveRoomRenderer.Render(
                    state = state.leaveRoomState,
                    onSelectNewOwners = { callback.navigateToSelectNewOwnersWhenLeaving() },
                    modifier = Modifier
                )
            },
            isBubble = isBubble,
            onOpenAppClick = {
                val encodedSessionId = java.net.URLEncoder.encode(room.sessionId.value, "UTF-8")
                val encodedRoomId = java.net.URLEncoder.encode(room.roomId.value, "UTF-8")
                val uriString = "elementx://open/$encodedSessionId/$encodedRoomId"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uriString)).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                activity.startActivity(intent)
            },
            onOpenBubbleClick = {
                val shortcutId = "${room.sessionId.value}-${room.roomId.value}"
                
                lifecycleScope.launch {
                    val avatarUrl = state.roomAvatarUrl 
                        ?: (state.roomType as? RoomDetailsType.Dm)?.otherMember?.avatarUrl
                        ?: state.heroes.firstOrNull()?.avatarUrl
                    val avatarData = AvatarData(
                        id = room.roomId.value,
                        name = state.roomName,
                        url = avatarUrl,
                        size = AvatarSize.TimelineRoom
                    )
                    val avatarBitmap = withContext(dispatchers.io) {
                        notificationBitmapLoader.getRoomBitmap(avatarData, imageLoaderHolder.get(client))
                    }
                    val avatarIcon = avatarBitmap?.let { androidx.core.graphics.drawable.IconCompat.createWithBitmap(it) }

                    val intent = android.content.Intent().apply {
                        setClassName(context.packageName, "io.element.android.x.BubbleActivity")
                        action = "io.element.android.x.ACTION_OPEN_BUBBLE"
                        putExtra("EXTRA_SESSION_ID", room.sessionId.value)
                        putExtra("EXTRA_ROOM_ID", room.roomId.value)
                    }
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        context,
                        room.roomId.value.hashCode(),
                        intent,
                        android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    
                    // Try to find an existing shortcut created by the Push Notification Library
                    val existingShortcut = androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(context)
                        .firstOrNull { it.id == shortcutId }
                    
                    val person = androidx.core.app.Person.Builder()
                        .setName(state.roomName)
                        .apply {
                            if (avatarIcon != null) {
                                setIcon(avatarIcon)
                            }
                        }
                        .build()
                    
                    val shortcut = existingShortcut ?: androidx.core.content.pm.ShortcutInfoCompat.Builder(context, shortcutId)
                        .setShortLabel(state.roomName)
                        .setLongLabel(state.roomName)
                        .apply {
                            if (avatarIcon != null) {
                                setIcon(avatarIcon)
                            } else {
                                setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(context, io.element.android.compound.R.drawable.ic_compound_pop_out))
                            }
                        }
                        .setIntent(intent)
                        .setLongLived(true)
                        .setPerson(person)
                        .build()
                        
                    if (existingShortcut == null) {
                        androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                    }
                    
                    val bubbleMetadata = androidx.core.app.NotificationCompat.BubbleMetadata.Builder(
                        pendingIntent,
                        shortcut.icon ?: androidx.core.graphics.drawable.IconCompat.createWithResource(context, io.element.android.compound.R.drawable.ic_compound_pop_out)
                    )
                        .setAutoExpandBubble(true)
                        .setSuppressNotification(true)
                        .setDesiredHeight(600)
                        .build()
                        
                    val messagingStyle = androidx.core.app.NotificationCompat.MessagingStyle(person)
                        .addMessage(context.getString(io.element.android.libraries.ui.strings.R.string.common_message), System.currentTimeMillis(), person)
                        
                    val notification = androidx.core.app.NotificationCompat.Builder(context, "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID_V2")
                        .setSmallIcon(io.element.android.compound.R.drawable.ic_compound_pop_out)
                        .setContentTitle(state.roomName)
                        .setContentText(context.getString(io.element.android.libraries.ui.strings.R.string.common_message))
                        .setShortcutId(shortcutId)
                        .setStyle(messagingStyle)
                        .setGroup(room.sessionId.value)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                        .setBubbleMetadata(bubbleMetadata)
                        .build()
                        
                    // Use the exact ID pattern used by DefaultNotificationDrawerManager for Room Messages
                    val notificationId = NotificationIdProvider.getRoomMessagesNotificationId(room.sessionId)
                    androidx.core.app.NotificationManagerCompat.from(context).notify(room.roomId.value, notificationId, notification)
                    
                    val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                        addCategory(android.content.Intent.CATEGORY_HOME)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(homeIntent)
                }
            }
        )
    }
}
