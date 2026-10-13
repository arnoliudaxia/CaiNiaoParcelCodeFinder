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

package com.paddle.ocr.demo.ui.viewmodel

import android.content.ContentUris
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.content.pm.PackageManager
import androidx.camera.core.ImageProxy
import com.paddle.ocr.demo.VideoFrameReader
import com.paddle.ocr.demo.ResolutionScale
import com.paddle.ocr.demo.camera.LatestFrameQueue
import com.paddle.ocr.demo.OCRModel
import com.paddle.ocr.demo.CameraTarget
import com.paddle.ocr.demo.CameraTargetHit
import com.paddle.ocr.demo.findCameraTargetHit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.opencv.core.Core
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paddle.ocr.demo.OCRApplication
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.model.OCRRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OCRViewModel : ViewModel() {

    private companion object {
        const val DRAW_WAIT_TIMEOUT_MS = 1_000L
    }

    sealed class UIState {
        data object Loading : UIState()
        data object Ready : UIState()
        data object LoadingImage : UIState()
        data class Processing(val bitmap: Bitmap) : UIState()
        data class Result(val bitmap: Bitmap, val result: OCRRunResult, val ocrCompletedAtMs: Long,
            val targetHit: CameraTargetHit? = null) : UIState()
        data class Error(val message: String) : UIState()
    }

    data class TimingInfo(
        val detectionMs: Long,
        val recognitionMs: Long,
        /** App layer: original decoded image available -> about to invoke OCR. */
        val preprocessMs: Long,
        /** OCR returned -> first Compose draw completed; null until actually drawn. */
        val postprocessMs: Long? = null,
    )

    private data class PreparedBitmap(val bitmap: Bitmap, val originalAvailableAtMs: Long)

    private val _uiState = MutableStateFlow<UIState>(
        uiStateForModelState(OCRApplication.instance.modelState.value)
    )
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    private val _resolutionScale = MutableStateFlow(ResolutionScale.QUARTER)
    val resolutionScale: StateFlow<ResolutionScale> = _resolutionScale.asStateFlow()

    fun setResolutionScale(scale: ResolutionScale) {
        _resolutionScale.value = scale
    }

    val modelState = OCRApplication.instance.modelState
    private val _taskBusy = MutableStateFlow(false)
    val taskBusy = _taskBusy.asStateFlow()
    private var selectionJob: Job? = null
        set(value) { field = value; value?.let(::track) }

    private fun track(job: Job) {
        _taskBusy.value = true
        job.invokeOnCompletion {
            viewModelScope.launch { _taskBusy.value = isOcrSessionBusy() }
        }
    }

    fun selectModel(model: OCRModel) {
        if (!canChangeSettings()) return
        autoRecognitionStarted = true
        awaitingGalleryPermission = false
        OCRApplication.instance.selectModel(model)
    }

    private fun canChangeSettings() = !isOcrSessionBusy() &&
        _uiState.value !is UIState.LoadingImage && _uiState.value !is UIState.Processing &&
        modelState.value !is OCRApplication.ModelState.Loading

    fun selectBatch(batch: Int) {
        if (!canChangeSettings()) return
        autoRecognitionStarted = true
        awaitingGalleryPermission = false
        OCRApplication.instance.selectBatch(batch)
    }

    fun selectDetector(detector: com.paddle.ocr.demo.DetectorSettings) {
        if (!canChangeSettings()) return
        autoRecognitionStarted = true
        awaitingGalleryPermission = false
        OCRApplication.instance.selectDetector(detector)
    }

    private val _timing = MutableStateFlow<TimingInfo?>(null)
    val timing: StateFlow<TimingInfo?> = _timing.asStateFlow()

    private fun uiStateForModelState(modelState: OCRApplication.ModelState): UIState {
        return when (modelState) {
            is OCRApplication.ModelState.Loading -> UIState.Loading
            is OCRApplication.ModelState.Ready -> UIState.Ready
            is OCRApplication.ModelState.Error -> UIState.Error(modelState.message)
        }
    }

    init {
        viewModelScope.launch {
            OCRApplication.instance.modelState.collect { modelState ->
                when (modelState) {
                    is OCRApplication.ModelState.Loading -> _uiState.value = UIState.Loading
                    is OCRApplication.ModelState.Ready -> {
                        if (_uiState.value is UIState.Loading) {
                            _timing.value = null
                            _videoProgress.value = null
                            _cameraProgress.value = null
                            frameDrawn = null
                        }
                        if (_uiState.value is UIState.Loading || _uiState.value is UIState.Error) {
                            _uiState.value = UIState.Ready
                        }
                    }
                    is OCRApplication.ModelState.Error -> _uiState.value = UIState.Error(modelState.message)
                }
            }
        }
    }

    data class VideoProgress(
        val completed: Int = 0,
        val total: Int = 0,
        val running: Boolean = true,
        val status: String = "正在打开视频",
        /** Average displayed OCR frames/sec from first-frame preparation, including UI waits. */
        val framesPerSecond: Double = 0.0,
    )

    private val _videoProgress = MutableStateFlow<VideoProgress?>(null)
    val videoProgress: StateFlow<VideoProgress?> = _videoProgress.asStateFlow()
    private var videoJob: Job? = null
        set(value) { field = value; value?.let(::track) }
    private var imageJob: Job? = null
        set(value) { field = value; value?.let(::track) }
    private var frameDrawn: CompletableDeferred<Unit>? = null

    // Process-wide lock also protects engine acquisition/release across ViewModel recreation.
    private suspend fun recognizeExclusive(bitmap: Bitmap): OCRRunResult =
        OCRApplication.instance.recognize(bitmap)

    /** A cancelled job is not complete until its in-flight inference returns. */
    private fun Job?.running() = this != null && !isCompleted

    private fun isOcrSessionBusy() = videoJob.running() || imageJob.running() || cameraJob.running() || selectionJob.running()

    fun onMediaSelected(uri: Uri) {
        if (isOcrSessionBusy() || !OCRApplication.instance.isModelLoaded) return
        selectionJob = viewModelScope.launch {
            try {
                val mime = withContext(Dispatchers.IO) {
                    OCRApplication.instance.contentResolver.getType(uri)
                }
                selectionJob = null // Main-thread handoff: no suspension before task starts.
                if (mime?.startsWith("video/") == true) {
                    onVideoSelected(uri)
                } else if (mime?.startsWith("image/") == true) {
                    onImageSelected(uri)
                } else {
                    _uiState.value = UIState.Error("无法确认所选媒体的类型，请选择图片或视频")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UIState.Error("读取媒体失败：${e.message}")
            }
        }
    }

    private fun onVideoSelected(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _uiState.value = UIState.Error("视频逐帧读取需要 Android 9 或更新版本")
            return
        }
        _timing.value = null
        _cameraProgress.value = null
        _videoProgress.value = VideoProgress()
        _uiState.value = UIState.LoadingImage
        videoJob = viewModelScope.launch {
            var reader: VideoFrameReader? = null
            try {
                // Assign inside the worker so cancellation during opening still releases it.
                withContext(Dispatchers.IO) {
                    reader = VideoFrameReader(OCRApplication.instance, uri)
                }
                val video = checkNotNull(reader)
                check(OCRApplication.instance.isModelLoaded) { "OCR engine not initialized" }
                val processingStart = SystemClock.elapsedRealtime()
                _videoProgress.value = VideoProgress(total = video.frameCount, status = "串行识别中")
                for (index in 0 until video.frameCount) {
                    ensureActive()
                    val scale = _resolutionScale.value
                    val prepared = withContext(Dispatchers.Default) {
                        // Video decoding ends before the preprocessing timer starts.
                        prepareBitmap(video.read(index), scale)
                    }
                    val bitmap = prepared.bitmap
                    val preprocessMs = SystemClock.elapsedRealtime() - prepared.originalAvailableAtMs
                    val result = recognizeExclusive(bitmap)
                    val ocrCompletedAtMs = SystemClock.elapsedRealtime()
                    ensureActive()
                    val drawn = CompletableDeferred<Unit>()
                    frameDrawn = drawn
                    _timing.value = TimingInfo(result.detectionTimeMs, result.recognitionTimeMs, preprocessMs)
                    _uiState.value = UIState.Result(bitmap, result, ocrCompletedAtMs)
                    // Fully finish displaying this frame before preparing the next one.
                    drawn.await()
                    frameDrawn = null
                    val elapsed = (SystemClock.elapsedRealtime() - processingStart).coerceAtLeast(1)
                    _videoProgress.value = VideoProgress(
                        completed = index + 1,
                        total = video.frameCount,
                        status = "串行识别中",
                        framesPerSecond = (index + 1) * 1000.0 / elapsed,
                    )
                }
                _videoProgress.value = _videoProgress.value?.copy(running = false, status = "视频逐帧识别完成")
            } catch (e: CancellationException) {
                _videoProgress.value = _videoProgress.value?.copy(running = false, status = "已停止")
                if (_uiState.value is UIState.LoadingImage) _uiState.value = UIState.Ready
                throw e
            } catch (e: Exception) {
                _videoProgress.value = _videoProgress.value?.copy(running = false, status = "识别失败")
                _uiState.value = UIState.Error("视频识别失败：${e.message}")
            } finally {
                frameDrawn = null
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { reader?.close() }
                }
            }
        }
    }

    fun stopVideo() {
        _videoProgress.value = _videoProgress.value?.copy(status = "正在停止，等待当前推理结束")
        videoJob?.cancel()
    }

    data class CameraProgress(
        val sessionId: Long,
        /** Camera should be bound; false as soon as stop is requested. */
        val active: Boolean = true,
        /** Session coroutine not finished yet (may still be finishing the last inference). */
        val running: Boolean = true,
        val completed: Int = 0,
        val status: String = "正在启动相机",
        /** Average OCR-displayed frames/sec since the first processed frame, including UI waits. */
        val framesPerSecond: Double = 0.0,
    )

    private val _cameraProgress = MutableStateFlow<CameraProgress?>(null)
    val cameraProgress: StateFlow<CameraProgress?> = _cameraProgress.asStateFlow()
    private var cameraJob: Job? = null
        set(value) { field = value; value?.let(::track) }
    private val _cameraTarget = MutableStateFlow(CameraTarget())
    val cameraTarget = _cameraTarget.asStateFlow()
    fun editCameraTarget(value: String) { _cameraTarget.value = _cameraTarget.value.edit(value) }
    fun applyCameraTarget() { _cameraTarget.value = _cameraTarget.value.apply() }
    fun clearCameraTarget() { _cameraTarget.value = CameraTarget() }

    private var cameraSessionCounter = 0L
    @Volatile private var cameraQueue: LatestFrameQueue<ImageProxy>? = null

    /** Camera entry is offered only when idle and the model is ready. */
    fun canStartCamera(): Boolean =
        !isOcrSessionBusy() && OCRApplication.instance.isModelLoaded && (_uiState.value is UIState.Ready || _uiState.value is UIState.Result)

    fun onCameraPermissionDenied(permanentlyDenied: Boolean) {
        if (isOcrSessionBusy() || !OCRApplication.instance.isModelLoaded) return
        _uiState.value = UIState.Error(
            if (permanentlyDenied) "相机权限已被拒绝，请在系统设置中允许相机权限后重试。相册识别不受影响。"
            else "需要相机权限才能实时识别。关闭提示后可再次点击相机按钮，或继续使用相册识别。"
        )
    }

    fun startCamera() {
        if (!canStartCamera()) return
        val app = OCRApplication.instance
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            _uiState.value = UIState.Error("此设备没有可用相机，仍可使用相册识别。")
            return
        }
        if (!app.isModelLoaded) {
            _uiState.value = UIState.Error("OCR 引擎尚未初始化")
            return
        }
        val sessionId = ++cameraSessionCounter
        val queue = LatestFrameQueue<ImageProxy>(sessionId) { it.close() }
        cameraQueue = queue
        _videoProgress.value = null
        _timing.value = null
        _cameraProgress.value = CameraProgress(sessionId)
        _uiState.value = UIState.LoadingImage
        cameraJob = viewModelScope.launch { runCameraSession(queue) }
    }

    private fun updateCamera(sessionId: Long, transform: (CameraProgress) -> CameraProgress) {
        val current = _cameraProgress.value ?: return
        if (current.sessionId == sessionId) _cameraProgress.value = transform(current)
    }

    private suspend fun runCameraSession(queue: LatestFrameQueue<ImageProxy>) {
        val sessionId = queue.sessionId
        var firstFrameStart = 0L
        var completed = 0
        var failed = false
        var targetHit: CameraTargetHit? = null
        try {
            while (true) {
                val frame = queue.receive()
                try {
                    // Keep the ImageProxy open until this frame is drawn: KEEP_ONLY_LATEST then
                    // delivers the newest frame next, and busy-time frames are skipped, not queued.
                    val start = SystemClock.elapsedRealtime()
                    if (completed == 0) firstFrameStart = start
                    val image = frame.value
                    val rotation = image.imageInfo.rotationDegrees
                    val scale = _resolutionScale.value
                    // NonCancellable: a stop during this short step must not drop the result
                    // unrecycled; cancellation is observed right after, inside the try below.
                    val bitmap = withContext(Dispatchers.Default + NonCancellable) {
                        resizeRotateAndRecycle(image.toBitmap(), rotation, scale)
                    }
                    var published = false
                    try {
                        currentCoroutineContext().ensureActive()
                        val preprocessMs = SystemClock.elapsedRealtime() - start
                        val result = recognizeExclusive(bitmap)
                        val ocrCompletedAtMs = SystemClock.elapsedRealtime()
                        currentCoroutineContext().ensureActive()
                        if (cameraQueue !== queue) return // Superseded; never overwrite newer UI.
                        // Main-thread completion snapshot: the latest explicitly applied target
                        // also applies to inference already in flight. No suspension until publish.
                        targetHit = findCameraTargetHit(_cameraTarget.value.active, result.results.map { it.text })
                        val drawn = CompletableDeferred<Unit>()
                        frameDrawn = drawn
                        _timing.value = TimingInfo(result.detectionTimeMs, result.recognitionTimeMs, preprocessMs)
                        _uiState.value = UIState.Result(bitmap, result, ocrCompletedAtMs, targetHit)
                        published = true // Compose owns it now; left to GC, never recycled here.
                        if (targetHit != null) {
                            // Unbind/reject new frames, but don't cancel this job: finish draw/timing.
                            queue.close()
                            updateCamera(sessionId) { it.copy(active = false, status = "已命中 ${targetHit?.target}") }
                        }
                        // Bounded so a hidden/stalled UI cannot freeze the session forever.
                        withTimeoutOrNull(DRAW_WAIT_TIMEOUT_MS) { drawn.await() }
                        if (frameDrawn === drawn) frameDrawn = null
                    } finally {
                        // Safe: recognize() has returned, so native code no longer reads it.
                        if (!published) bitmap.recycle()
                    }
                    completed++
                    val elapsed = (SystemClock.elapsedRealtime() - firstFrameStart).coerceAtLeast(1)
                    updateCamera(sessionId) {
                        it.copy(
                            completed = completed,
                            status = if (it.active) "实时识别中" else it.status,
                            framesPerSecond = completed * 1000.0 / elapsed,
                        )
                    }
                } finally {
                    frame.release()
                }
                if (targetHit != null) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClosedReceiveChannelException) {
            // Normal stop path: the queue was closed before the coroutine observed cancellation.
        } catch (e: Exception) {
            if (cameraQueue === queue) {
                failed = true
                _uiState.value = UIState.Error("相机识别失败：${e.message}")
            }
        } finally {
            queue.close()
            if (cameraQueue === queue) cameraQueue = null
            updateCamera(sessionId) {
                it.copy(active = false, running = false, status = when {
                    failed -> "识别失败"
                    targetHit != null -> "已命中 ${targetHit.target}，已停止"
                    else -> "已停止"
                })
            }
            if (_cameraProgress.value?.sessionId == sessionId && _uiState.value is UIState.LoadingImage) {
                _uiState.value = UIState.Ready // Stopped before any frame was shown.
            }
        }
    }

    /** Analyzer thread. Always takes ownership of [image]; stale sessions close it immediately. */
    fun onCameraFrame(sessionId: Long, image: ImageProxy) {
        val queue = cameraQueue
        if (queue == null || queue.sessionId != sessionId) {
            image.close()
        } else {
            queue.offer(image) // Closes the frame itself when it cannot be queued.
        }
    }

    fun onCameraBound(sessionId: Long) {
        updateCamera(sessionId) { if (it.active && it.completed == 0) it.copy(status = "相机已启动，等待画面") else it }
    }

    fun onCameraError(sessionId: Long, message: String) {
        val current = _cameraProgress.value ?: return
        if (current.sessionId != sessionId || !current.active) return // Late callback from old session.
        stopCamera("相机不可用，正在停止")
        _uiState.value = UIState.Error("$message。关闭提示后可重试或使用相册识别。")
    }

    /** Stop button, background (ON_STOP) and errors. Idempotent; the camera unbinds immediately. */
    fun stopCamera(status: String = "正在停止，等待当前推理结束") {
        val current = _cameraProgress.value ?: return
        if (!current.active) return
        cameraQueue?.close()
        cameraQueue = null
        _cameraProgress.value = current.copy(active = false, status = status)
        cameraJob?.cancel()
    }

    private var autoRecognitionStarted = false
    private var awaitingGalleryPermission = false

    /** Claim once per ViewModel, including across Activity recreation. */
    fun beginAutoRecognition(): Boolean {
        if (autoRecognitionStarted || _uiState.value !is UIState.Ready) return false
        autoRecognitionStarted = true
        awaitingGalleryPermission = true
        return true
    }

    fun onGalleryPermissionResult(granted: Boolean) {
        if (!awaitingGalleryPermission) return
        awaitingGalleryPermission = false
        if (isOcrSessionBusy() || !OCRApplication.instance.isModelLoaded) return
        if (!granted) {
            _uiState.value = UIState.Error("需要允许访问所有照片，才能自动识别相册最新图片。关闭提示后仍可手动选图。")
            return
        }
        if (_uiState.value !is UIState.Ready) return
        _uiState.value = UIState.LoadingImage
        selectionJob = viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    OCRApplication.instance.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.Images.Media._ID),
                        null,
                        null,
                        "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            ContentUris.withAppendedId(collection, cursor.getLong(0))
                        } else null
                    }
                }
                selectionJob = null
                if (uri == null) {
                    _uiState.value = UIState.Error("相册中没有可读取的图片，可关闭提示后手动选图。")
                } else {
                    onImageSelected(uri)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UIState.Error("读取相册失败：${e.message}")
            }
        }
    }

    fun onImageSelected(uri: Uri) {
        if (isOcrSessionBusy() || !OCRApplication.instance.isModelLoaded) return
        _videoProgress.value = null
        _cameraProgress.value = null
        val scale = _resolutionScale.value
        _timing.value = null
        _uiState.value = UIState.LoadingImage
        imageJob = viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    OCRApplication.instance.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null) {
                    val prepared = withContext(Dispatchers.IO) {
                        // Capture scale per image so menu changes do not alter an in-flight task.
                        decodeScaledBitmap(bytes, scale)
                    }
                    if (prepared != null) {
                        processBitmap(prepared)
                    } else {
                        _uiState.value = UIState.Error("Failed to load image")
                    }
                } else {
                    _uiState.value = UIState.Error("Failed to load image")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UIState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun onSampleImageClicked(resId: Int) {
        if (isOcrSessionBusy() || !OCRApplication.instance.isModelLoaded) return
        val scale = _resolutionScale.value
        _timing.value = null
        imageJob = viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                OCRApplication.instance.resources.openRawResource(resId).use { it.readBytes() }
            }
            val prepared = withContext(Dispatchers.IO) {
                decodeScaledBitmap(bytes, scale)
            }
            if (prepared != null) {
                processBitmap(prepared)
            } else {
                _uiState.value = UIState.Error("Failed to load image")
            }
        }
    }

    private suspend fun processBitmap(prepared: PreparedBitmap) {
        val bitmap = prepared.bitmap
        _uiState.value = UIState.Processing(bitmap)

        try {
            val preprocessMs = SystemClock.elapsedRealtime() - prepared.originalAvailableAtMs
            val result = recognizeExclusive(bitmap)
            val ocrCompletedAtMs = SystemClock.elapsedRealtime()
            _timing.value = TimingInfo(
                detectionMs = result.detectionTimeMs,
                recognitionMs = result.recognitionTimeMs,
                preprocessMs = preprocessMs,
            )
            _uiState.value = UIState.Result(bitmap, result, ocrCompletedAtMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = UIState.Error(e.message ?: "OCR failed")
        }
    }

    /** First Compose content draw, not physical display presentation. */
    fun onResultDrawn(state: UIState.Result, drawnAtMs: Long) {
        if (_uiState.value !== state) return // Ignore callbacks from an older image.
        frameDrawn?.complete(Unit)
        val timing = _timing.value ?: return
        if (timing.postprocessMs != null) return // Do not recount recompositions/rotation.
        _timing.value = timing.copy(
            postprocessMs = (drawnAtMs - state.ocrCompletedAtMs).coerceAtLeast(0),
        )
    }

    private fun decodeScaledBitmap(bytes: ByteArray, scale: ResolutionScale): PreparedBitmap? {
        val original = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = 1
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return prepareBitmap(original, scale)
    }

    private fun prepareBitmap(original: Bitmap, scale: ResolutionScale): PreparedBitmap {
        val originalAvailableAtMs = SystemClock.elapsedRealtime()
        return PreparedBitmap(resizeRotateAndRecycle(original, 0, scale), originalAvailableAtMs)
    }

    /** Scale both dimensions, then correct orientation; caller owns the returned Bitmap. */
    private fun resizeRotateAndRecycle(
        original: Bitmap, rotationDegrees: Int, scale: ResolutionScale,
    ): Bitmap {
        val rotateCode = when (((rotationDegrees % 360) + 360) % 360) {
            0 -> null
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> {
                original.recycle()
                throw IllegalArgumentException("Unsupported rotation $rotationDegrees")
            }
        }
        // Transfer ownership directly when no transform is needed.
        if (scale == ResolutionScale.ORIGINAL && rotateCode == null) return original
        val src = Mat()
        val resized = Mat()
        val rotated = Mat()
        try {
            Utils.bitmapToMat(original, src)
            val scaled = if (scale == ResolutionScale.ORIGINAL) src else resized.also {
                Imgproc.resize(
                    src, it, Size(scale.dimension(original.width).toDouble(), scale.dimension(original.height).toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA,
                )
            }
            val output = if (rotateCode == null) scaled else rotated.also {
                Core.rotate(scaled, it, rotateCode)
            }
            val bitmap = Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(output, bitmap)
                return bitmap
            } catch (e: Exception) {
                bitmap.recycle()
                throw e
            }
        } finally {
            src.release()
            resized.release()
            rotated.release()
            original.recycle()
        }
    }

    override fun onCleared() {
        cameraQueue?.close()
        cameraQueue = null
        super.onCleared() // viewModelScope is cancelled too; the session finally block cleans up.
    }

    fun retry() {
        val app = OCRApplication.instance
        if (app.isModelLoaded) {
            _uiState.value = UIState.Ready
        } else {
            app.retryLoadModels()
        }
    }

    fun copyAllResults(results: List<OCRResult>) {
        val text = results.joinToString("\n") { it.text }
        val clipboard = OCRApplication.instance.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR Results", text))
    }

}
