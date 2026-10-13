package com.paddle.ocr.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.*
import com.paddle.ocr.model.ModelConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Separate SDK test application: does not change the demo's preferences, photos or camera. */
class ModelSwitchSmokeTest {
    @Test fun loadRecognizeReplaceAndRejectWrongDictionary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(OpenCVUtils.init(context))
        val bitmap = Bitmap.createBitmap(640, 160, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("OCR TEST 12345", 20f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 56f
            })
        }
        suspend fun create(directory: String, dictionary: String = "$directory/rec/inference.yml") = PaddleOCR.create(
            context, PaddleOCRConfig(recScoreThresh = 0f, recBatchSize = 1),
            EngineConfig(numThreads = 6, executionProvider = ExecutionProvider.CPU),
            "$directory/det/inference.onnx", "$directory/rec/inference.onnx", dictionary,
        )
        var engine: PaddleOCR? = null
        try {
            for ((directory, classes) in listOf("models" to 18710, "models/v6_tiny" to 6906, "models/v5_mobile" to 18385, "models" to 18710)) {
                assertEquals(classes, ModelConfig.parse(context, "$directory/rec/inference.yml").characterList.size + 1)
                val replacement = create(directory) // Old engine remains alive during load.
                engine?.release()
                engine = replacement
                val result = replacement.recognize(bitmap)
                assertTrue("No recognized text for $directory", result.results.any { it.text.isNotBlank() })
                assertTrue("Recognizer must run", result.recInputShapes.isNotEmpty())
                Log.i("ModelSwitchSmoke", "$directory: ${result.results.map { it.text }}")
            }
            var rejected = false
            try {
                create("models/v6_tiny", "models/rec/inference.yml").release()
            } catch (expected: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Mismatched dictionary must fail at load", rejected)
            assertTrue("Old engine survives failed replacement", engine!!.recognize(bitmap).results.isNotEmpty())
        } finally {
            engine?.release()
            bitmap.recycle()
        }
    }
}
