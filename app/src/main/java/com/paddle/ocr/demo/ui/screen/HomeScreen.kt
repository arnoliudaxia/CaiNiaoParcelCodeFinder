// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.demo.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.core.app.ActivityCompat
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paddle.ocr.demo.ui.component.*
import com.paddle.ocr.demo.ui.viewmodel.OCRViewModel

@Composable
fun HomeScreen(viewModel: OCRViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val timing by viewModel.timing.collectAsState()
    val videoProgress by viewModel.videoProgress.collectAsState()
    val cameraProgress by viewModel.cameraProgress.collectAsState()
    val resolutionScale by viewModel.resolutionScale.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val taskBusy by viewModel.taskBusy.collectAsState()
    val cameraTarget by viewModel.cameraTarget.collectAsState()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onMediaSelected(it) } }
    val selectMedia = {
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    val hostView = LocalView.current
    val context = LocalContext.current
    val imagePermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        viewModel.onGalleryPermissionResult(grants[imagePermission] == true)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startCamera()
        } else {
            // No rationale after a denial means "don't ask again" (or policy-blocked).
            val activity = context.findActivity()
            val permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            viewModel.onCameraPermissionDenied(permanentlyDenied)
        }
    }
    val startCamera = {
        if (viewModel.canStartCamera()) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.startCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    val cameraRunning = cameraProgress?.running == true
    val settingsEnabled = !taskBusy && modelState !is com.paddle.ocr.demo.OCRApplication.ModelState.Loading &&
        state !is OCRViewModel.UIState.LoadingImage && state !is OCRViewModel.UIState.Processing

    // Bound only while the session is active; stopping/disposal unbinds CameraX immediately.
    cameraProgress?.takeIf { it.active }?.let { session ->
        CameraAnalysisEffect(sessionId = session.sessionId, viewModel = viewModel)
    }

    LaunchedEffect(state) {
        if (state is OCRViewModel.UIState.Ready && viewModel.beginAutoRecognition()) {
            if (ContextCompat.checkSelfPermission(context, imagePermission) == PackageManager.PERMISSION_GRANTED) {
                viewModel.onGalleryPermissionResult(true)
            } else {
                val permissions = if (Build.VERSION.SDK_INT >= 34) {
                    arrayOf(imagePermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                } else {
                    arrayOf(imagePermission)
                }
                permissionLauncher.launch(permissions)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
    val controlsMaxHeight = maxHeight * 0.5f
    Column(modifier = Modifier.fillMaxSize()) {
    if (cameraRunning) {
        CameraTargetInput(cameraTarget, cameraProgress?.active == true,
            viewModel::editCameraTarget, viewModel::applyCameraTarget, viewModel::clearCameraTarget)
        Button(
            onClick = { viewModel.stopCamera() },
            enabled = cameraProgress?.active == true,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(if (cameraProgress?.active == true) "停止相机识别" else "正在停止相机…")
        }
    } else {
    // Bound the entire settings/progress area, not just tuning fields: keep media reachable.
    Column(Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState())) {
        CameraTargetInput(cameraTarget, settingsEnabled,
            viewModel::editCameraTarget, viewModel::applyCameraTarget, viewModel::clearCameraTarget)
        ModelSelector(
            state = modelState,
            enabled = settingsEnabled,
            onSelected = viewModel::selectModel,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        // Stack settings vertically so all controls fit narrow viewports and large fonts.
        RecognitionBatchSelector(
            state = modelState,
            enabled = settingsEnabled,
            onSelected = viewModel::selectBatch,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        ResolutionSelector(
            selected = resolutionScale,
            enabled = modelState is com.paddle.ocr.demo.OCRApplication.ModelState.Ready,
            onSelected = viewModel::setResolutionScale,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        DetectorSettingsPanel(
            state = modelState,
            enabled = settingsEnabled,
            onApply = viewModel::selectDetector,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        videoProgress?.let { progress ->
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("${progress.status} · ${progress.completed}/${progress.total} 帧")
                Text("平均处理帧率：${"%.2f".format(progress.framesPerSecond)} 帧/秒")
                if (progress.running) {
                    Button(onClick = { viewModel.stopVideo() }) { Text("停止视频识别") }
                }
            }
        }
        cameraProgress?.let { progress ->
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("相机：${progress.status} · 已识别 ${progress.completed} 帧")
                Text("平均处理帧率：${"%.2f".format(progress.framesPerSecond)} 帧/秒")
                Text(
                    "串行处理最新帧：每帧识别并显示后才取下一帧，识别期间到达的帧会被跳过，不积压。" +
                        "画面只显示已识别的帧，不是流畅预览。原始指相机输出帧尺寸；FPS 为本次累计平均。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (progress.active) {
                    Button(onClick = { viewModel.stopCamera() }) { Text("停止相机识别") }
                }
            }
        }
    }
    }
        when (val s = state) {
            is OCRViewModel.UIState.Loading -> {
                LoadingOverlay("Loading OCR models...")
            }
            is OCRViewModel.UIState.LoadingImage -> {
                LoadingOverlay(
                    when {
                        cameraRunning -> "正在启动相机并识别第一帧..."
                        videoProgress?.running == true -> "正在解码并识别视频帧..."
                        else -> "正在读取相册图片..."
                    }
                )
            }
            is OCRViewModel.UIState.Ready -> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(modifier = Modifier.height(32.dp))
                    ImagePicker(
                        onGalleryClick = selectMedia,
                        onSampleClick = { viewModel.onSampleImageClicked(it) },
                        sampleImages = emptyList(),
                        onCameraClick = startCamera,
                    )
                }
            }
            is OCRViewModel.UIState.Processing -> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(modifier = Modifier.height(16.dp))
                    ImagePreview(bitmap = s.bitmap, results = emptyList())
                    Spacer(modifier = Modifier.height(16.dp))
                    LoadingOverlay("Processing...")
                }
            }
            is OCRViewModel.UIState.Result -> {
                // Plain flag avoids state mutations during the draw pass.
                val drawReported = remember(s) { booleanArrayOf(false) }
                Column(modifier = Modifier
                    .drawWithContent {
                        drawContent()
                        if (!drawReported[0]) {
                            drawReported[0] = true
                            val drawnAtMs = SystemClock.elapsedRealtime()
                            hostView.post { viewModel.onResultDrawn(s, drawnAtMs) }
                        }
                    }
                    .verticalScroll(rememberScrollState())) {
                    if (!cameraRunning) {
                        Text("当前处理图像：${s.bitmap.width} × ${s.bitmap.height}")
                    }
                    s.targetHit?.let { hit ->
                        Text("命中 ${hit.target} · ${hit.indices.size} 处 · 相机已停止",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium)
                    }
                    ImagePreview(
                        bitmap = s.bitmap,
                        results = s.result.results,
                        targetHit = s.targetHit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (timing != null) {
                        TimingBar(
                            detectionMs = timing!!.detectionMs,
                            recognitionMs = timing!!.recognitionMs,
                            preprocessMs = timing!!.preprocessMs,
                            postprocessMs = timing!!.postprocessMs,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    ResultList(
                        results = s.result.results,
                        onCopyAll = { viewModel.copyAllResults(s.result.results) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (videoProgress?.running != true && !cameraRunning) {
                        ImagePicker(
                            onGalleryClick = selectMedia,
                            onSampleClick = { viewModel.onSampleImageClicked(it) },
                            sampleImages = emptyList(),
                            onCameraClick = startCamera,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            is OCRViewModel.UIState.Error -> {
                LoadingOverlay("Error occurred")
                ErrorDialog(
                    message = s.message,
                    onRetry = { viewModel.retry() },
                    onDismiss = { viewModel.retry() },
                )
            }
        }
    }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
