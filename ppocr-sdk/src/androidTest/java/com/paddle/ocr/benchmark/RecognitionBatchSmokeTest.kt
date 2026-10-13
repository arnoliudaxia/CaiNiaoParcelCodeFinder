package com.paddle.ocr.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.ExecutionProvider
import com.paddle.ocr.engine.ORTSessionManager
import com.paddle.ocr.engine.RecognitionEngine
import com.paddle.ocr.model.ModelConfig
import com.paddle.ocr.util.OpenCVUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** Shape/ownership smoke only: synthetic crops are not an OCR accuracy benchmark. */
@RunWith(AndroidJUnit4::class)
class RecognitionBatchSmokeTest {
    @Test fun cpuDynamicBatchesWithUnevenWidthsAndTails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(OpenCVUtils.init(context))
        val crops = listOf(80, 124, 192, 260, 340, 96, 156, 220, 300).mapIndexed { i, width ->
            Mat(48, width, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0)).also {
                Imgproc.putText(it, "ROI $i", Point(3.0, 32.0), Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.7, Scalar(0.0, 0.0, 0.0), 1)
            }
        }
        try {
            for (directory in listOf("models", "models/v6_tiny", "models/v5_mobile")) {
                for (batch in listOf(1, 2, 4, 8)) {
                    val manager = ORTSessionManager(context, EngineConfig(numThreads = 6, executionProvider = ExecutionProvider.CPU))
                    try {
                        manager.loadModels("$directory/det/inference.onnx", "$directory/rec/inference.onnx", batch)
                        assertEquals(ExecutionProvider.CPU, manager.activeProvider)
                        assertNull(manager.recStaticBatch)
                        val dictionary = ModelConfig.parse(context, "$directory/rec/inference.yml").characterList
                        manager.validateRecognitionClasses(dictionary.size)
                        val recognition = RecognitionEngine(manager, dictionary)
                        var count = 0
                        for (chunk in crops.chunked(batch)) {
                            val result = recognition.recognize(chunk)
                            assertEquals(chunk.size, result.texts.size)
                            assertEquals(chunk.size, result.inputShape.first())
                            assertTrue(result.texts.all { it.second.isFinite() })
                            count += result.texts.size
                        }
                        assertEquals(crops.size, count)
                        println("BATCH_SMOKE PASS $directory batch=$batch crops=$count tail=${crops.size % batch}")
                    } finally { manager.release() }
                }
            }
        } finally { crops.forEach { it.release() } }
    }
}
