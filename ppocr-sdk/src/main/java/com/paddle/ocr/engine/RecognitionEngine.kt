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

import com.paddle.ocr.postprocess.CTCDecoder
import com.paddle.ocr.preprocess.RecPreprocessor
import org.opencv.core.Mat

class RecognitionEngine(
    private val ortManager: ORTSessionManager,
    private val characterList: List<String>,
) {
    data class RecognitionResult(
        val texts: List<Pair<String, Float>>,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val timeMs: Long,
        val inputShape: List<Int>,
    )

    fun recognize(crops: List<Mat>): RecognitionResult {
        // Preprocess
        val preStart = System.currentTimeMillis()
        val preResult = RecPreprocessor.preprocessBatch(
            crops,
            fixedWidth = ortManager.recStaticWidth,
            fixedBatch = ortManager.recStaticBatch,
        )
        val preprocessMs = System.currentTimeMillis() - preStart

        // Inference
        val infStart = System.currentTimeMillis()
        val (outputData, outputShape) = ortManager.runRecognition(preResult.tensorData, preResult.shape)
        require(outputShape.size == 3 && outputShape[2] == characterList.size.toLong() + 1) {
            "Recognition output does not match dictionary + CTC blank: ${outputShape.contentToString()} vs ${characterList.size + 1}"
        }
        val inferenceMs = System.currentTimeMillis() - infStart

        // Postprocess (CTC decode)
        val postStart = System.currentTimeMillis()
        // Drop outputs of zero-padded batch entries (static-shape backends).
        val decoded = CTCDecoder.decode(outputData, outputShape, characterList).take(crops.size)
        val postprocessMs = System.currentTimeMillis() - postStart

        val inputShape = preResult.shape.map { it.toInt() }
        val timeMs = preprocessMs + inferenceMs + postprocessMs
        return RecognitionResult(
            texts = decoded,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            timeMs = timeMs,
            inputShape = inputShape,
        )
    }
}
