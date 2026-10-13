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

import android.graphics.Bitmap
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.postprocess.DBPostProcessor
import com.paddle.ocr.preprocess.DetPreprocessResult
import com.paddle.ocr.preprocess.DetPreprocessor
import com.paddle.ocr.util.BitmapUtils
import org.opencv.core.Mat

class DetectionEngine(
    private val ortManager: ORTSessionManager,
    private val config: PaddleOCRConfig,
) {
    data class DetectionResult(
        val boxes: List<OCRBox>,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val timeMs: Long,
        val inputShape: List<Int>,
    )

    fun detect(bitmap: Bitmap): DetectionResult {
        val src = BitmapUtils.bitmapToBGRMat(bitmap)
        return try {
            detect(src)
        } finally {
            src.release()
        }
    }

    fun detect(src: Mat): DetectionResult {
        val static = ortManager.detStaticSize
        if (static != null) {
            return detect {
                DetPreprocessor.preprocessFixed(src, static.first, static.second, config.detImgMode)
            }
        }
        return detect {
            DetPreprocessor.preprocess(
                src,
                config.detLimitSideLen,
                config.detLimitType,
                config.detMaxSideLimit,
                config.detImgMode,
                config.detResizeLong,
            )
        }
    }

    private fun detect(preprocessor: () -> DetPreprocessResult): DetectionResult {
        val preStart = System.currentTimeMillis()
        val preResult = preprocessor()
        val preprocessMs = System.currentTimeMillis() - preStart

        val infStart = System.currentTimeMillis()
        val (rawOutput, rawShape) = ortManager.runDetection(preResult.tensorData, preResult.shape)
        val inferenceMs = System.currentTimeMillis() - infStart

        val postStart = System.currentTimeMillis()
        val (outputData, outputShape) = cropToValidRegion(rawOutput, rawShape, preResult.validH, preResult.validW)
        val boxes = DBPostProcessor.process(
            pred = outputData,
            predShape = outputShape,
            thresh = config.detThresh,
            boxThresh = config.detBoxThresh,
            unclipRatio = config.detUnclipRatio,
            maxCandidates = config.detMaxCandidates,
            useDilation = config.detUseDilation,
            scoreMode = config.detScoreMode,
            boxType = config.detBoxType,
            originalH = preResult.originalH,
            originalW = preResult.originalW,
        )
        val postprocessMs = System.currentTimeMillis() - postStart

        return DetectionResult(
            boxes = boxes,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            timeMs = preprocessMs + inferenceMs + postprocessMs,
            inputShape = listOf(1, 3, preResult.shape[2].toInt(), preResult.shape[3].toInt()),
        )
    }

    /** Removes letterbox padding from a [1, 1, H, W] probability map. */
    private fun cropToValidRegion(
        pred: FloatArray,
        shape: LongArray,
        validH: Int,
        validW: Int,
    ): Pair<FloatArray, LongArray> {
        val pH = shape[2].toInt()
        val pW = shape[3].toInt()
        if (validH >= pH && validW >= pW) return pred to shape
        val h = validH.coerceAtMost(pH)
        val w = validW.coerceAtMost(pW)
        val out = FloatArray(h * w)
        for (y in 0 until h) {
            System.arraycopy(pred, y * pW, out, y * w, w)
        }
        return out to longArrayOf(shape[0], shape[1], h.toLong(), w.toLong())
    }
}
