package com.paddle.ocr.demo.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paddle.ocr.demo.camera.CameraAnalysisBinder
import com.paddle.ocr.demo.ui.viewmodel.OCRViewModel

/**
 * Keeps one CameraX ImageAnalysis bound while this effect is in composition for [sessionId].
 * Leaving composition (stop, session change, Activity destroy) unbinds it; ON_STOP (background,
 * screen off, rotation) stops the OCR session so no camera resources are held in background.
 * No raw preview is shown: the UI only displays frames that finished OCR.
 */
@Composable
fun CameraAnalysisEffect(sessionId: Long, viewModel: OCRViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentViewModel by rememberUpdatedState(viewModel)

    DisposableEffect(lifecycleOwner, sessionId) {
        val binder = CameraAnalysisBinder(context)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                currentViewModel.stopCamera("应用进入后台，已停止相机识别")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        binder.start(
            lifecycleOwner = lifecycleOwner,
            onFrame = { image -> currentViewModel.onCameraFrame(sessionId, image) },
            onBound = { currentViewModel.onCameraBound(sessionId) },
            onError = { message -> currentViewModel.onCameraError(sessionId, message) },
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            binder.stop()
        }
    }
}
