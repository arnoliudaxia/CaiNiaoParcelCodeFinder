package com.paddle.ocr.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.preprocess.DetPreprocessor
import com.paddle.ocr.util.OpenCVUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

@RunWith(AndroidJUnit4::class)
class DetectorResizeSmokeTest {
    @Test fun explicitAndLegacyPreprocessing() {
        assertTrue(OpenCVUtils.init(InstrumentationRegistry.getInstrumentation().targetContext))
        val src = Mat(270, 480, CvType.CV_8UC3, Scalar(255.0, 128.0, 0.0))
        try {
            val mobile = DetPreprocessor.preprocess(src, 64, "min", 4000, "BGR", 960)
            assertArrayEquals(longArrayOf(1, 3, 640, 1024), mobile.shape)
            assertEquals(270, mobile.originalH)
            assertEquals(480, mobile.originalW)
            assertEquals(((1.0 - .485) / .229).toFloat(), mobile.tensorData[0], .0001f)
            val legacy = DetPreprocessor.preprocess(src, 64, "min", 4000, "BGR")
            val unspecified = DetPreprocessor.preprocess(src, 64, "min", 4000, "BGR", null)
            assertArrayEquals(longArrayOf(1, 3, 256, 480), legacy.shape)
            assertArrayEquals(legacy.tensorData, unspecified.tensorData, 0f)
            assertFalse(src.empty())
        } finally { src.release() }
    }
}
