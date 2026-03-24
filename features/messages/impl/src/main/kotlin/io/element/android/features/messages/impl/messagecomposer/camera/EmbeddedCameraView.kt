/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer.camera

import android.annotation.SuppressLint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.element.android.features.messages.impl.messagecomposer.EmbeddedCameraMode
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor

@SuppressLint("MissingPermission")
@Composable
fun EmbeddedCameraView(
    initialMode: EmbeddedCameraMode,
    onPhotoCaptured: (Uri) -> Unit,
    onVideoCaptured: (Uri) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = ContextCompat.getMainExecutor(context)
    
    var currentMode by remember { mutableStateOf(initialMode) }
    var isCapturing by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableLongStateOf(0L) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }

    BackHandler {
        if (isRecording) {
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
        }
        onClose()
    }

    val cameraController = remember { 
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
        }
    }

    LaunchedEffect(lensFacing) {
        cameraController.cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
    }

    LaunchedEffect(flashMode) {
        cameraController.imageCaptureFlashMode = flashMode
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0L
            while (isRecording) {
                delay(1000)
                recordingDuration += 1
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = cameraController
                    cameraController.bindToLifecycle(lifecycleOwner)
                    cameraController.isTapToFocusEnabled = true
                    cameraController.isPinchToZoomEnabled = true
                }
            }
        )

        // Top Controls
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // Close Button
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Camera",
                    tint = Color.White
                )
            }

            // Flash Button
            if (!isRecording) {
                IconButton(
                    onClick = {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                            else -> ImageCapture.FLASH_MODE_AUTO
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    val flashIcon = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        ImageCapture.FLASH_MODE_OFF -> Icons.Default.FlashOff
                        else -> Icons.Default.FlashAuto
                    }
                    Icon(
                        imageVector = flashIcon,
                        contentDescription = "Toggle Flash",
                        tint = Color.White
                    )
                }
            }
        }

        // Recording Indicator
        if (isRecording) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatDuration(recordingDuration),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Bottom Controls Container
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Switcher
            if (!isRecording) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeTab(
                        text = "PHOTO",
                        isSelected = currentMode == EmbeddedCameraMode.Photo,
                        onClick = { currentMode = EmbeddedCameraMode.Photo }
                    )
                    ModeTab(
                        text = "VIDEO",
                        isSelected = currentMode == EmbeddedCameraMode.Video,
                        onClick = { currentMode = EmbeddedCameraMode.Video }
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(64.dp))
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Flip Camera Button
                if (!isRecording) {
                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 32.dp)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Flip Camera",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Shutter Button
                val outerRingScale by animateFloatAsState(if (isRecording) 1.2f else 1.0f)
                val innerSize by animateDpAsState(if (isRecording) 32.dp else 64.dp)
                val innerCornerRadius by animateDpAsState(if (isRecording) 4.dp else 40.dp)
                val innerColor by animateColorAsState(
                    if (currentMode == EmbeddedCameraMode.Video) Color.Red else Color.White
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(outerRingScale)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (currentMode == EmbeddedCameraMode.Photo) {
                                if (!isCapturing) {
                                    isCapturing = true
                                    takePhoto(context, cameraController, mainExecutor) { uri ->
                                        onPhotoCaptured(uri)
                                        isCapturing = false
                                    }
                                }
                            } else {
                                if (!isRecording) {
                                    isRecording = true
                                    activeRecording = startRecording(context, cameraController, mainExecutor) { uri ->
                                        onVideoCaptured(uri)
                                    }
                                } else {
                                    activeRecording?.stop()
                                    activeRecording = null
                                    isRecording = false
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Outer Ring
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.3f))
                    )
                    
                    // Inner Circle/Square
                    Box(
                        modifier = Modifier
                            .size(innerSize)
                            .clip(RoundedCornerShape(innerCornerRadius))
                            .background(innerColor)
                    )

                    if (isCapturing) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeTab(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) Color.White.copy(alpha = 0.9f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.Black else Color.White,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp
        )
    }
}

private fun takePhoto(
    context: android.content.Context,
    cameraController: LifecycleCameraController,
    executor: Executor,
    onCaptured: (Uri) -> Unit
) {
    val photoFile = File(context.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    cameraController.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = output.savedUri ?: Uri.fromFile(photoFile)
                onCaptured(uri)
            }

            override fun onError(exc: ImageCaptureException) {
                Timber.e(exc, "Photo capture failed")
            }
        }
    )
}

@SuppressLint("MissingPermission")
private fun startRecording(
    context: android.content.Context,
    cameraController: LifecycleCameraController,
    executor: Executor,
    onCaptured: (Uri) -> Unit
): Recording {
    val videoFile = File(context.cacheDir, "camera_video_${System.currentTimeMillis()}.mp4")
    val outputOptions = FileOutputOptions.Builder(videoFile).build()
    
    return cameraController.startRecording(
        outputOptions,
        AudioConfig.create(true),
        executor
    ) { event ->
        if (event is VideoRecordEvent.Finalize) {
            if (!event.hasError()) {
                val uri = event.outputResults.outputUri ?: Uri.fromFile(videoFile)
                onCaptured(uri)
            } else {
                Timber.e("Video recording failed: ${event.error}")
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
