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

package com.paddle.ocr.preprocess

import android.graphics.Bitmap
import com.paddle.ocr.util.BitmapUtils
import com.paddle.ocr.util.ImageUtils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

data class DetPreprocessResult(
    val tensorData: FloatArray,
    val shape: LongArray,
    val originalH: Int,
    val originalW: Int,
    /** Valid (non-padded) region of the tensor; equals the tensor H/W unless letterboxed. */
    val validH: Int = shape[2].toInt(),
    val validW: Int = shape[3].toInt(),
)

object DetPreprocessor {
    private val mean = doubleArrayOf(0.485, 0.456, 0.406)
    private val std = doubleArrayOf(0.229, 0.224, 0.225)
    private const val scale = 1.0 / 255.0

    fun preprocess(
        bitmap: Bitmap,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
        imgMode: String,
    ): DetPreprocessResult {
        val src = BitmapUtils.bitmapToBGRMat(bitmap)
        return try {
            preprocess(src, limitSideLen, limitType, maxSideLimit, imgMode)
        } finally {
            src.release()
        }
    }

    fun preprocess(
        src: Mat,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
        imgMode: String,
    ): DetPreprocessResult = preprocess(src, limitSideLen, limitType, maxSideLimit, imgMode, null)

    fun preprocess(
        src: Mat,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
        imgMode: String,
        resizeLong: Int?,
    ): DetPreprocessResult {
        val originalH = src.rows()
        val originalW = src.cols()
        val input = if (imgMode.uppercase() == "RGB") {
            Mat().also { Imgproc.cvtColor(src, it, Imgproc.COLOR_BGR2RGB) }
        } else {
            src
        }

        val resized = if (resizeLong == null) {
            ImageUtils.resizeToMultipleOf32(input, limitSideLen, limitType, maxSideLimit)
        } else {
            val (h, w) = DetResizePolicy.explicitLongSize(originalH, originalW, resizeLong)
            Mat().also { Imgproc.resize(input, it, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR) }
        }
        if (input !== src) input.release()

        val h = resized.rows()
        val w = resized.cols()
        val floatMat = Mat(h, w, CvType.CV_32FC3)
        resized.convertTo(floatMat, CvType.CV_32F)

        val channels = mutableListOf<Mat>()
        Core.split(floatMat, channels)
        for (c in 0..2) {
            Core.multiply(channels[c], Scalar(scale), channels[c])
            Core.subtract(channels[c], Scalar(mean[c]), channels[c])
            Core.divide(channels[c], Scalar(std[c]), channels[c])
        }
        resized.release()
        floatMat.release()

        val channelSize = h * w
        val tensorData = FloatArray(3 * channelSize)
        for (c in 0..2) {
            val chan = channels[c]
            val buf = FloatArray(channelSize)
            chan.get(0, 0, buf)
            System.arraycopy(buf, 0, tensorData, c * channelSize, channelSize)
            chan.release()
        }

        return DetPreprocessResult(
            tensorData = tensorData,
            shape = longArrayOf(1, 3, h.toLong(), w.toLong()),
            originalH = originalH,
            originalW = originalW,
        )
    }

    /**
     * Letterbox preprocessing for static-shape backends (e.g. QNN HTP).
     * The image is resized (never upscaled) to fit into [targetH] x [targetW],
     * placed at the top-left corner and the rest is zero padded.
     */
    fun preprocessFixed(
        src: Mat,
        targetH: Int,
        targetW: Int,
        imgMode: String,
    ): DetPreprocessResult {
        val originalH = src.rows()
        val originalW = src.cols()
        val input = if (imgMode.uppercase() == "RGB") {
            Mat().also { Imgproc.cvtColor(src, it, Imgproc.COLOR_BGR2RGB) }
        } else {
            src
        }

        val ratio = minOf(targetH.toDouble() / originalH, targetW.toDouble() / originalW, 1.0)
        val h = (originalH * ratio).roundToInt().coerceIn(1, targetH)
        val w = (originalW * ratio).roundToInt().coerceIn(1, targetW)
        val resized = if (h == originalH && w == originalW) {
            input
        } else {
            Mat().also { Imgproc.resize(input, it, Size(w.toDouble(), h.toDouble())) }
        }

        val floatMat = Mat(h, w, CvType.CV_32FC3)
        resized.convertTo(floatMat, CvType.CV_32F)
        if (resized !== input) resized.release()
        if (input !== src) input.release()

        val channels = mutableListOf<Mat>()
        Core.split(floatMat, channels)
        floatMat.release()

        val planeSize = targetH * targetW
        val tensorData = FloatArray(3 * planeSize)
        val buf = FloatArray(h * w)
        for (c in 0..2) {
            val chan = channels[c]
            Core.multiply(chan, Scalar(scale), chan)
            Core.subtract(chan, Scalar(mean[c]), chan)
            Core.divide(chan, Scalar(std[c]), chan)
            chan.get(0, 0, buf)
            chan.release()
            val planeOffset = c * planeSize
            for (y in 0 until h) {
                System.arraycopy(buf, y * w, tensorData, planeOffset + y * targetW, w)
            }
        }

        return DetPreprocessResult(
            tensorData = tensorData,
            shape = longArrayOf(1, 3, targetH.toLong(), targetW.toLong()),
            originalH = originalH,
            originalW = originalW,
            validH = h,
            validW = w,
        )
    }
}
