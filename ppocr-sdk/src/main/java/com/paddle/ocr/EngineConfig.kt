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

package com.paddle.ocr

enum class ExecutionProvider {
    CPU,

    /** Not available in the onnxruntime-android-qnn package; falls back to CPU. */
    NNAPI,

    /** Qualcomm QNN HTP (Hexagon NPU). Requires a Snapdragon SoC. */
    QNN,
}

/**
 * QNN HTP specific options.
 *
 * HTP only supports static tensor shapes, so when QNN is active the detection
 * input is letterboxed into [detInputHeight] x [detInputWidth] and recognition
 * crops are padded to [recInputWidth].
 */
data class QnnOptions(
    val detInputHeight: Int = 1024,
    val detInputWidth: Int = 1024,
    val recInputWidth: Int = 640,
    /** burst / sustained_high_performance / high_performance / balanced / power_saver ... */
    val htpPerformanceMode: String = "burst",
    /** Run FP32 models on HTP with FP16 precision (required for non-quantized models). */
    val enableFp16Precision: Boolean = true,
    /** Cache compiled QNN context binaries in filesDir to speed up subsequent cold starts. */
    val enableContextCache: Boolean = true,
    /** Fail session creation if any node would fall back to CPU (diagnostic). */
    val disableCpuFallback: Boolean = false,
    /** If QNN initialization fails, silently re-create sessions on CPU. */
    val fallbackToCpuOnError: Boolean = true,
    /** Profile the first run of each model and log which EP executed each node. */
    val profileFirstRun: Boolean = true,
    /** Set ORT session log level to VERBOSE (logcat tag: onnxruntime). */
    val verboseLogging: Boolean = false,
)

data class EngineConfig(
    val numThreads: Int = 6,
    /** Legacy flag; prefer executionProvider = ExecutionProvider.NNAPI. */
    val useNnapi: Boolean = false,
    val executionProvider: ExecutionProvider =
        if (useNnapi) ExecutionProvider.NNAPI else ExecutionProvider.CPU,
    val qnn: QnnOptions = QnnOptions(),
)
