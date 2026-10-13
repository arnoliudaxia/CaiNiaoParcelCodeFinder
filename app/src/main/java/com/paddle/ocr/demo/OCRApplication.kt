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

package com.paddle.ocr.demo

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.ExecutionProvider
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OCRApplication : Application() {
    sealed class ModelState {
        data class Loading(val requested: OCRSettings, val previous: OCRSettings? = null) : ModelState()
        data class Ready(val settings: OCRSettings, val warning: String? = null) : ModelState()
        data class Error(val message: String) : ModelState()
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Acquisition, native inference, replacement and release share ONE process-wide lock.
    private val engineMutex = Mutex()
    private var engine: PaddleOCR? = null
    private var loadedSettings: OCRSettings? = null
    private val _modelState = MutableStateFlow<ModelState>(ModelState.Loading(OCRSettings()))
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()
    val isModelLoaded get() = _modelState.value is ModelState.Ready
    private val preferences get() = getSharedPreferences("ocr-model", MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        instance = this
        loadModels(savedSettings())
    }

    // Called on main thread; publishes Loading synchronously to reject late media callbacks.
    private fun savedSettings() = OCRSettings.fromPreferences(preferences.all)

    fun selectModel(model: OCRModel) = selectSettings(
        (loadedSettings ?: savedSettings()).withModel(model),
    )

    fun selectBatch(batch: Int) = selectSettings(
        (loadedSettings ?: savedSettings()).copy(recBatchSize = batch),
    )

    fun selectDetector(detector: DetectorSettings) = selectSettings(
        (loadedSettings ?: savedSettings()).copy(detector = detector),
    )

    private fun selectSettings(settings: OCRSettings) {
        if (_modelState.value is ModelState.Loading) return
        if ((_modelState.value as? ModelState.Ready)?.settings == settings) return
        loadModels(settings)
    }

    fun retryLoadModels() {
        if (_modelState.value is ModelState.Loading) return
        loadModels(savedSettings())
    }

    suspend fun recognize(bitmap: Bitmap) = engineMutex.withLock {
        check(_modelState.value is ModelState.Ready) { "OCR models are loading" }
        checkNotNull(engine) { "OCR engine not initialized" }.recognize(bitmap)
    }

    private fun loadModels(settings: OCRSettings) {
        val model = settings.model
        _modelState.value = ModelState.Loading(settings, loadedSettings)
        applicationScope.launch {
            engineMutex.withLock {
                try {
                    check(OpenCVUtils.init(this@OCRApplication)) { "OpenCV initialization failed" }
                    // Apply one immutable settings snapshot; keep BGR and CPU6 unchanged.
                    val replacement = PaddleOCR.create(
                        context = this@OCRApplication,
                        config = settings.config(),
                        engineConfig = EngineConfig(numThreads = 6, executionProvider = ExecutionProvider.CPU),
                        detModelAssetPath = model.detPath,
                        recModelAssetPath = model.recPath,
                        recConfigAssetPath = model.dictionaryPath,
                    )
                    val previous = engine
                    engine = replacement
                    loadedSettings = settings
                    // Keep old engine until replacement is fully loaded/validated.
                    withContext(NonCancellable) {
                        runCatching { previous?.release() }.onFailure { Log.e(TAG, "Release failed", it) }
                    }
                    val editor = preferences.edit()
                    settings.preferenceValues().forEach { (key, value) ->
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Int -> editor.putInt(key, value)
                            is Float -> editor.putFloat(key, value)
                        }
                    }
                    editor.apply()
                    Log.i(TAG, "Loaded ${model.label}: ${replacement.executionProvider}, ${replacement.coldLoadTimeMs}ms")
                    _modelState.value = ModelState.Ready(settings)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val message = "${model.label} / batch ${settings.recBatchSize} 加载失败：${t.message}"
                    Log.e(TAG, message, t)
                    _modelState.value = loadedSettings?.let { ModelState.Ready(it, message) }
                        ?: ModelState.Error(message)
                }
            }
        }
    }

    companion object {
        private const val TAG = "OCRApplication"
        lateinit var instance: OCRApplication
            private set
    }
}
