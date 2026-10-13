package com.paddle.ocr.demo

import org.junit.Assert.*
import org.junit.Test

class OCRModelTest {
    @Test fun preferencesDefaultAndRoundTrip() {
        assertEquals(OCRModel.SMALL, OCRModel.fromPreference(null))
        assertEquals(OCRModel.SMALL, OCRModel.fromPreference("removed-model"))
        OCRModel.entries.forEach { assertEquals(it, OCRModel.fromPreference(it.name)) }
    }

    @Test fun pairedPathsAreUniqueAndSmallIsBackwardCompatible() {
        assertEquals("models/det/inference.onnx", OCRModel.SMALL.detPath)
        assertEquals("models/rec/inference.onnx", OCRModel.SMALL.recPath)
        assertEquals("models/rec/inference.yml", OCRModel.SMALL.dictionaryPath)
        assertEquals(3, OCRModel.entries.map { it.dictionaryPath }.toSet().size)
        OCRModel.entries.forEach {
            assertEquals(it.recPath.removeSuffix("onnx") + "yml", it.dictionaryPath)
            assertEquals(it.detPath.replace("/det/", "/rec/"), it.recPath)
        }
        assertEquals(listOf(18710, 6906, 18385), OCRModel.entries.map { it.classes })
    }
}
