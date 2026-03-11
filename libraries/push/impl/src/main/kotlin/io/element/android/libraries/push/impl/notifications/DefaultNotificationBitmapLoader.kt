/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.drawable.IconCompat
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.CircleCropTransformation
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.media.AVATAR_THUMBNAIL_SIZE_IN_PIXEL
import io.element.android.libraries.matrix.ui.media.InitialsAvatarBitmapGenerator
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.libraries.push.api.notifications.NotificationBitmapLoader
import io.element.android.services.toolbox.api.sdk.BuildVersionSdkIntProvider
import timber.log.Timber

@ContributesBinding(AppScope::class)
class DefaultNotificationBitmapLoader(
    @ApplicationContext private val context: Context,
    private val sdkIntProvider: BuildVersionSdkIntProvider,
    private val initialsAvatarBitmapGenerator: InitialsAvatarBitmapGenerator,
) : NotificationBitmapLoader {
    override suspend fun getRoomBitmap(
        avatarData: AvatarData,
        imageLoader: ImageLoader,
        targetSize: Long,
    ): Bitmap? {
        return try {
            loadBitmap(
                avatarData = avatarData,
                imageLoader = imageLoader,
                targetSize = targetSize,
            )?.toAdaptiveIconBitmap()
        } catch (e: Throwable) {
            Timber.e(e, "Unable to load room bitmap")
            null
        }
    }

    override suspend fun getUserIcon(
        avatarData: AvatarData,
        imageLoader: ImageLoader,
    ): IconCompat? {
        if (sdkIntProvider.get() < Build.VERSION_CODES.P) {
            return null
        }
        return try {
            loadBitmap(
                avatarData = avatarData,
                imageLoader = imageLoader,
                targetSize = AVATAR_THUMBNAIL_SIZE_IN_PIXEL,
            )
                ?.let { IconCompat.createWithBitmap(it) }
        } catch (e: Throwable) {
            Timber.e(e, "Unable to load user bitmap")
            null
        }
    }

    private fun isDarkTheme(): Boolean {
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private suspend fun loadBitmap(
        avatarData: AvatarData,
        imageLoader: ImageLoader,
        targetSize: Long,
    ): Bitmap? {
        val path = avatarData.url
        val data = if (path != null) {
            MediaRequestData(
                source = MediaSource(path),
                kind = MediaRequestData.Kind.Thumbnail(targetSize),
            )
        } else {
            initialsAvatarBitmapGenerator.generateBitmap(
                size = targetSize.toInt(),
                avatarData = avatarData,
                useDarkTheme = isDarkTheme(),
            )
        }
        val imageRequest = ImageRequest.Builder(context)
            .data(data)
            .transformations(CircleCropTransformation())
            .build()
        return imageLoader.execute(imageRequest).image?.toBitmap()
    }

    /**
     * Pad a bitmap for use with [IconCompat.createWithAdaptiveBitmap].
     *
     * Adaptive icons use a 108dp canvas but only the inner 72dp circle is visible
     * (ratio ≈ 0.667). Without padding the system zooms/crops the image.
     * This places the original image centered on a larger transparent canvas
     * so it fits neatly inside the visible safe zone.
     */
    private fun Bitmap.toAdaptiveIconBitmap(): Bitmap {
        // Adaptive icon safe-zone ratio: visible area is 72/108 of total canvas
        val scale = 72f / 108f
        val newSize = (maxOf(width, height) / scale).toInt()
        val result = Bitmap.createBitmap(newSize, newSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        // Transparent background — the system circle mask will clip it
        canvas.drawColor(Color.TRANSPARENT)
        val left = (newSize - width) / 2f
        val top = (newSize - height) / 2f
        canvas.drawBitmap(this, left, top, null)
        return result
    }
}

