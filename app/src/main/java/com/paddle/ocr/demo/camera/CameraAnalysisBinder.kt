package com.paddle.ocr.demo.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Binds one back-camera [ImageAnalysis] (KEEP_ONLY_LATEST, RGBA output) to a lifecycle.
 *
 * Main-thread only for [start]/[stop]. [onFrame] runs on a private analyzer thread and receives
 * ownership of the [ImageProxy]; it must eventually close it. After [stop] every late frame is
 * closed here. The binder is single-use.
 */
class CameraAnalysisBinder(context: Context) {
    private val appContext = context.applicationContext
    // One serial thread that exits when idle. It is never shut down, so a frame CameraX posts
    // concurrently with stop() cannot be rejected (and leaked); it is closed by the stopped check.
    private val analyzerExecutor = ThreadPoolExecutor(
        0, 1, 2, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { r -> Thread(r, "camera-ocr-analyzer") }
    @Volatile private var stopped = false
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    fun start(
        lifecycleOwner: LifecycleOwner,
        onFrame: (ImageProxy) -> Unit,
        onBound: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val future = try {
            ProcessCameraProvider.getInstance(appContext)
        } catch (e: Exception) {
            onError("相机初始化失败：${e.message}")
            return
        }
        future.addListener({
            if (stopped) return@addListener // Disposed before CameraX became available.
            try {
                val cameraProvider = future.get()
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    onError("未找到可用的后置摄像头")
                    return@addListener
                }
                val useCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    TARGET_ANALYSIS_SIZE,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                )
                            )
                            .build()
                    )
                    .build()
                useCase.setAnalyzer(analyzerExecutor) { image ->
                    if (stopped) {
                        image.close()
                    } else {
                        try {
                            onFrame(image)
                        } catch (t: Throwable) {
                            image.close()
                        }
                    }
                }
                provider = cameraProvider
                analysis = useCase
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
                onBound()
            } catch (e: Exception) {
                releaseUseCase()
                onError("相机启动失败：${e.cause?.message ?: e.message}")
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun stop() {
        if (stopped) return
        stopped = true
        releaseUseCase()
        // Already-queued analyzer tasks still run and close their frames because stopped == true.
    }

    private fun releaseUseCase() {
        analysis?.let { useCase ->
            useCase.clearAnalyzer()
            runCatching { provider?.unbind(useCase) }
        }
        analysis = null
        provider = null
    }

    companion object {
        /** Before the 1/4 OpenCV resize: 1920x1080 becomes 480x270 (270x480 portrait). */
        val TARGET_ANALYSIS_SIZE = Size(1920, 1080)
    }
}
