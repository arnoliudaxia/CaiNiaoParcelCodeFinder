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

package com.paddle.ocr.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.system.Os
import android.util.Log
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.ExecutionProvider
import com.paddle.ocr.model.OCRError
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer

class ORTSessionManager(
    private val context: Context,
    private val config: EngineConfig,
) {
    companion object {
        private const val TAG = "PPOCR-ORT"
        private const val QNN_CACHE_DIR = "qnn_ctx_cache"
        private const val PROFILE_DIR = "ort_profile"
    }

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var detInputName: String = "x"
    private var recInputName: String = "x"
    private val pendingProfile = mutableSetOf<String>()

    var coldLoadTimeMs: Long = 0
        private set

    /** Execution provider actually in use after [loadModels]. */
    var activeProvider: ExecutionProvider = ExecutionProvider.CPU
        private set

    /** Human-readable note, e.g. why QNN fell back to CPU. */
    var providerNote: String? = null
        private set

    /** Per-model node placement summary collected from the first profiled run. */
    val providerReport: MutableMap<String, String> = linkedMapOf()

    /** Non-null when the detection session was created with a static input shape (H, W). */
    var detStaticSize: Pair<Int, Int>? = null
        private set

    /** Non-null when the recognition session was created with a static input width. */
    var recStaticWidth: Int? = null
        private set

    /** Non-null when the recognition session was created with a static batch size. */
    var recStaticBatch: Int? = null
        private set

    /** Static class dimension is present in the shipped CTC graphs. Dynamic graphs validate at run. */
    fun validateRecognitionClasses(dictionarySize: Int) {
        val info = recSession?.outputInfo?.values?.firstOrNull()?.info as? ai.onnxruntime.TensorInfo
        val shape = info?.shape ?: return
        require(shape.size == 3) { "Expected CTC output [N,T,C], got ${shape.contentToString()}" }
        if (shape[2] > 0) {
            require(shape[2] == dictionarySize.toLong() + 1) {
                "Recognition model/dictionary mismatch: ${shape[2]} classes vs ${dictionarySize + 1} (including blank)"
            }
        }
    }

    fun loadModels(detAssetPath: String, recAssetPath: String, recBatchSize: Int = 1) {
        val loadStart = System.currentTimeMillis()
        val ortEnv = OrtEnvironment.getEnvironment()
        env = ortEnv
        val detBytes = readModelAsset(detAssetPath)
        val recBytes = readModelAsset(recAssetPath)

        when (config.executionProvider) {
            ExecutionProvider.QNN -> {
                try {
                    createQnnSessions(ortEnv, detBytes, recBytes, recBatchSize.coerceAtLeast(1))
                    activeProvider = ExecutionProvider.QNN
                } catch (t: Throwable) {
                    closeSessions()
                    if (!config.qnn.fallbackToCpuOnError) throw t
                    Log.w(TAG, "QNN initialization failed, falling back to CPU", t)
                    providerNote = "QNN unavailable: ${t.message ?: t.javaClass.simpleName}"
                    createPlainSessions(ortEnv, detBytes, recBytes, useNnapi = false)
                    activeProvider = ExecutionProvider.CPU
                }
            }
            ExecutionProvider.NNAPI -> {
                try {
                    createPlainSessions(ortEnv, detBytes, recBytes, useNnapi = true)
                    activeProvider = ExecutionProvider.NNAPI
                } catch (t: Throwable) {
                    closeSessions()
                    Log.w(TAG, "NNAPI unavailable in this ORT build, falling back to CPU", t)
                    providerNote = "NNAPI unavailable: ${t.message ?: t.javaClass.simpleName}"
                    createPlainSessions(ortEnv, detBytes, recBytes, useNnapi = false)
                    activeProvider = ExecutionProvider.CPU
                }
            }
            ExecutionProvider.CPU -> {
                createPlainSessions(ortEnv, detBytes, recBytes, useNnapi = false)
                activeProvider = ExecutionProvider.CPU
            }
        }

        detInputName = try {
            detSession!!.inputNames.iterator().next()
        } catch (t: Throwable) {
            throw OCRError.ModelLoadFailed("detection", t)
        }
        recInputName = try {
            recSession!!.inputNames.iterator().next()
        } catch (t: Throwable) {
            throw OCRError.ModelLoadFailed("recognition", t)
        }
        coldLoadTimeMs = System.currentTimeMillis() - loadStart
        Log.i(
            TAG,
            "Models loaded in ${coldLoadTimeMs}ms, provider=$activeProvider" +
                (providerNote?.let { " ($it)" } ?: "") +
                ", detStatic=$detStaticSize, recStaticWidth=$recStaticWidth, recStaticBatch=$recStaticBatch",
        )
    }

    // ---------------------------------------------------------------------
    // CPU / NNAPI
    // ---------------------------------------------------------------------

    private fun createPlainSessions(
        ortEnv: OrtEnvironment,
        detBytes: ByteArray,
        recBytes: ByteArray,
        useNnapi: Boolean,
    ) {
        detStaticSize = null
        recStaticWidth = null
        recStaticBatch = null
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(config.numThreads)
            if (useNnapi) addNnapi()
        }
        try {
            detSession = createSessionOrThrow("detection") { ortEnv.createSession(detBytes, opts) }
            recSession = createSessionOrThrow("recognition") { ortEnv.createSession(recBytes, opts) }
        } finally {
            opts.close()
        }
    }

    // ---------------------------------------------------------------------
    // QNN HTP
    // ---------------------------------------------------------------------

    private fun createQnnSessions(
        ortEnv: OrtEnvironment,
        detBytes: ByteArray,
        recBytes: ByteArray,
        recBatchSize: Int,
    ) {
        val q = config.qnn
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        // The Hexagon skel libraries are loaded by the DSP through FastRPC, which
        // searches ADSP_LIBRARY_PATH. Point it at the extracted app native libs.
        try {
            Os.setenv(
                "ADSP_LIBRARY_PATH",
                "$nativeLibDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp",
                true,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set ADSP_LIBRARY_PATH", t)
        }
        val htpLib = File(nativeLibDir, "libQnnHtp.so")
        val backendPath = if (htpLib.exists()) htpLib.absolutePath else "libQnnHtp.so"

        val qnnOptions = mapOf(
            "backend_path" to backendPath,
            "htp_performance_mode" to q.htpPerformanceMode,
            "enable_htp_fp16_precision" to if (q.enableFp16Precision) "1" else "0",
            "htp_graph_finalization_optimization_mode" to "3",
        )

        // Model input dims (see onnx graph):
        //   det: x[DynamicDimension.0, 3, DynamicDimension.1, DynamicDimension.2]
        //   rec: x[DynamicDimension.0, 3, 48, DynamicDimension.1]
        val detDims = mapOf(
            "DynamicDimension.0" to 1L,
            "DynamicDimension.1" to q.detInputHeight.toLong(),
            "DynamicDimension.2" to q.detInputWidth.toLong(),
        )
        val recDims = mapOf(
            "DynamicDimension.0" to recBatchSize.toLong(),
            "DynamicDimension.1" to q.recInputWidth.toLong(),
        )

        val cacheTag = cacheTag()
        detSession = createQnnSession(
            ortEnv, "detection", detBytes, detDims, qnnOptions,
            "det_${q.detInputHeight}x${q.detInputWidth}_$cacheTag",
        )
        recSession = createQnnSession(
            ortEnv, "recognition", recBytes, recDims, qnnOptions,
            "rec_b${recBatchSize}_w${q.recInputWidth}_$cacheTag",
        )
        detStaticSize = q.detInputHeight to q.detInputWidth
        recStaticWidth = q.recInputWidth
        recStaticBatch = recBatchSize
    }

    private fun createQnnSession(
        ortEnv: OrtEnvironment,
        modelName: String,
        modelBytes: ByteArray,
        dims: Map<String, Long>,
        qnnOptions: Map<String, String>,
        cacheKey: String,
    ): OrtSession {
        val q = config.qnn
        fun baseOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(config.numThreads)
            dims.forEach { (name, value) -> setSymbolicDimensionValue(name, value) }
            if (q.disableCpuFallback) addConfigEntry("session.disable_cpu_ep_fallback", "1")
            if (q.verboseLogging) setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
            if (q.profileFirstRun) {
                val dir = File(context.cacheDir, PROFILE_DIR).apply { mkdirs() }
                enableProfiling(File(dir, modelName).absolutePath)
            }
            addQnn(qnnOptions)
        }

        val cacheFile = File(File(context.filesDir, QNN_CACHE_DIR), "${cacheKey}_ctx.onnx")
        val session: OrtSession = if (q.enableContextCache) {
            loadCachedContext(ortEnv, modelName, cacheFile, ::baseOptions)
                ?: generateContext(ortEnv, modelName, modelBytes, cacheFile, ::baseOptions)
                ?: createWithOptions(modelName, baseOptions()) { ortEnv.createSession(modelBytes, it) }
        } else {
            createWithOptions(modelName, baseOptions()) { ortEnv.createSession(modelBytes, it) }
        }
        if (q.profileFirstRun) pendingProfile.add(modelName)
        return session
    }

    private fun loadCachedContext(
        ortEnv: OrtEnvironment,
        modelName: String,
        cacheFile: File,
        options: () -> OrtSession.SessionOptions,
    ): OrtSession? {
        if (!cacheFile.exists()) return null
        return try {
            createWithOptions(modelName, options()) { ortEnv.createSession(cacheFile.absolutePath, it) }
                .also { Log.i(TAG, "[$modelName] loaded QNN context cache ${cacheFile.name}") }
        } catch (t: Throwable) {
            Log.w(TAG, "[$modelName] invalid QNN context cache, regenerating", t)
            cacheFile.delete()
            null
        }
    }

    private fun generateContext(
        ortEnv: OrtEnvironment,
        modelName: String,
        modelBytes: ByteArray,
        cacheFile: File,
        options: () -> OrtSession.SessionOptions,
    ): OrtSession? {
        val dir = cacheFile.parentFile!!
        // Drop stale caches for the same model (different shape / app build).
        dir.listFiles()?.filter { it.name.startsWith(cacheFile.name.substringBefore('_')) }
            ?.forEach { it.delete() }
        dir.mkdirs()
        val opts = options().apply {
            addConfigEntry("ep.context_enable", "1")
            addConfigEntry("ep.context_embed_mode", "1")
            addConfigEntry("ep.context_file_path", cacheFile.absolutePath)
        }
        return try {
            val start = System.currentTimeMillis()
            createWithOptions(modelName, opts) { ortEnv.createSession(modelBytes, it) }.also {
                Log.i(
                    TAG,
                    "[$modelName] compiled QNN graph in ${System.currentTimeMillis() - start}ms, " +
                        "cache=${cacheFile.name} exists=${cacheFile.exists()}",
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[$modelName] QNN context generation failed, compiling without cache", t)
            cacheFile.delete()
            null
        }
    }

    private fun cacheTag(): String {
        val lastUpdate = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
            0L
        }
        val fp16 = if (config.qnn.enableFp16Precision) "fp16" else "fp32"
        return "${fp16}_$lastUpdate"
    }

    private inline fun createWithOptions(
        modelName: String,
        opts: OrtSession.SessionOptions,
        create: (OrtSession.SessionOptions) -> OrtSession,
    ): OrtSession {
        return try {
            createSessionOrThrow(modelName) { create(opts) }
        } finally {
            opts.close()
        }
    }

    private inline fun createSessionOrThrow(modelName: String, create: () -> OrtSession): OrtSession {
        return try {
            create()
        } catch (t: Throwable) {
            throw OCRError.ModelLoadFailed(modelName, t)
        }
    }

    // ---------------------------------------------------------------------
    // Inference
    // ---------------------------------------------------------------------

    fun runDetection(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = detSession
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("detection", Exception("Environment not initialized"))
        return runSession(ortEnv, session, detInputName, input, shape, "detection")
    }

    fun runRecognition(input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val session = recSession
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Session not initialized"))
        val ortEnv = env
            ?: throw OCRError.ModelLoadFailed("recognition", Exception("Environment not initialized"))
        return runSession(ortEnv, session, recInputName, input, shape, "recognition")
    }

    fun release() {
        closeSessions()
        env = null
    }

    private fun closeSessions() {
        try {
            detSession?.close()
        } finally {
            detSession = null
            try {
                recSession?.close()
            } finally {
                recSession = null
            }
        }
    }

    private fun readModelAsset(assetPath: String): ByteArray {
        return try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (t: Throwable) {
            throw OCRError.ModelNotFound(assetPath, t)
        }
    }

    private fun runSession(
        ortEnv: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        modelName: String,
    ): Pair<FloatArray, LongArray> {
        val tensor = try {
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(input), shape)
        } catch (t: Throwable) {
            throw OCRError.InferenceFailed(modelName, t)
        }
        val result = try {
            try {
                session.run(mapOf(inputName to tensor))
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            tensor.close()
        }

        if (pendingProfile.remove(modelName)) collectProfile(session, modelName)

        return try {
            try {
                val outputName = session.outputNames.iterator().next()
                val ortValue = result.get(outputName)
                    .orElseThrow { Exception("No output tensor found") }
                val outputTensor = ortValue as? OnnxTensor
                    ?: throw Exception("Output is not an ONNX tensor")
                Pair(copyFloatBuffer(outputTensor.floatBuffer), outputTensor.info.shape)
            } catch (t: Throwable) {
                throw OCRError.InferenceFailed(modelName, t)
            }
        } finally {
            result.close()
        }
    }

    /** Stops profiling and summarizes which execution provider ran each kernel. */
    private fun collectProfile(session: OrtSession, modelName: String) {
        try {
            val path = session.endProfiling()
            val file = File(path)
            val events = JSONArray(file.readText())
            val byProvider = linkedMapOf<String, Int>()
            val cpuOps = sortedMapOf<String, Int>()
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                if (e.optString("cat") != "Node") continue
                if (!e.optString("name").endsWith("_kernel_time")) continue
                val args = e.optJSONObject("args") ?: continue
                val provider = args.optString("provider", "unknown")
                byProvider[provider] = (byProvider[provider] ?: 0) + 1
                if (provider == "CPUExecutionProvider") {
                    val op = args.optString("op_name", "?")
                    cpuOps[op] = (cpuOps[op] ?: 0) + 1
                }
            }
            val summary = buildString {
                append(byProvider.entries.joinToString { "${it.key}=${it.value}" })
                if (cpuOps.isNotEmpty()) {
                    append("; CPU ops: ")
                    append(cpuOps.entries.joinToString { "${it.key}x${it.value}" })
                }
            }
            providerReport[modelName] = summary
            Log.i(TAG, "[$modelName] node placement: $summary")
            file.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "[$modelName] failed to collect profile", t)
        }
    }

    private fun copyFloatBuffer(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val output = FloatArray(duplicate.remaining())
        duplicate.get(output)
        return output
    }
}
