package com.paddle.ocr.demo

import org.junit.Assert.*
import org.junit.Test

class OCRSettingsTest {
    @Test fun preferencesDefaultAndValidate() {
        assertEquals(4, OCRSettings().recBatchSize)
        assertEquals(4, OCRSettings.fromPreferences(emptyMap<String, Any>()).recBatchSize)
        assertEquals(1, OCRSettings.fromPreferences(mapOf("selected" to "SMALL", "recBatchSize" to 1)).recBatchSize)
        assertEquals(OCRSettings(), OCRSettings.fromPreferences(null, 0))
        assertEquals(OCRSettings(), OCRSettings.fromPreferences("unknown", 3))
        for (model in OCRModel.entries) for (batch in OCRSettings.BATCH_SIZES) {
            val settings = OCRSettings.fromPreferences(model.name, batch)
            assertEquals(model, settings.model)
            assertEquals(batch, settings.config().recBatchSize)
            assertEquals(0.0f, settings.config().recScoreThresh, 0.0f)
        }
    }

    @Test fun batchIsPartOfEngineIdentityAndSurvivesModelChange() {
        val original = OCRSettings()
        for (batch in OCRSettings.BATCH_SIZES.filter { it != original.recBatchSize }) {
            val changed = original.copy(recBatchSize = batch)
            assertNotEquals(original, changed)
            assertEquals(batch, changed.withModel(OCRModel.TINY).recBatchSize)
            assertEquals(OCRModel.SMALL, changed.model)
        }
    }

    @Test fun officialDetectorDefaultsSurvivePreferencesModelAndBatchChanges() {
        for (model in OCRModel.entries) for (batch in OCRSettings.BATCH_SIZES) {
            val restored = OCRSettings.fromPreferences(model.name, batch)
            val switched = OCRSettings(OCRModel.SMALL, batch).withModel(model)
            assertEquals(restored.config(), switched.config())
            assertEquals(restored.config(), OCRSettings(model).copy(recBatchSize = batch).config())
            val c = restored.config()
            assertEquals(if (model == OCRModel.MOBILE) 0.3f else 0.2f, c.detThresh, 0f)
            assertEquals(when (model) { OCRModel.SMALL -> 0.45f; OCRModel.TINY -> 0.4f; OCRModel.MOBILE -> 0.6f }, c.detBoxThresh, 0f)
            assertEquals(if (model == OCRModel.MOBILE) 1.5f else 1.4f, c.detUnclipRatio, 0f)
            assertEquals(if (model == OCRModel.MOBILE) 1000 else 3000, c.detMaxCandidates)
            assertEquals(if (model == OCRModel.MOBILE) 960 else null, c.detResizeLong)
            assertEquals(batch, c.recBatchSize)
            assertEquals("BGR", c.detImgMode)
            assertEquals("min", c.detLimitType)
            assertEquals(64, c.detLimitSideLen)
            assertEquals(4000, c.detMaxSideLimit)
            assertFalse(c.detUseDilation)
            assertEquals("fast", c.detScoreMode)
            assertEquals("quad", c.detBoxType)
        }
        assertNull(com.paddle.ocr.PaddleOCRConfig().detResizeLong)
    }

    @Test fun overridesSurviveBatchAndPersistenceButResetOnModelChange() {
        val custom = DetectorSettings(.1f, .7f, 2f, 4321)
        val original = OCRSettings(detector = custom)
        for (batch in OCRSettings.BATCH_SIZES) {
            val changed = original.copy(recBatchSize = batch)
            assertEquals(custom, changed.detector)
            assertEquals(changed, OCRSettings.fromPreferences(changed.preferenceValues()))
            for (model in listOf(OCRModel.TINY, OCRModel.MOBILE)) {
                val switched = changed.withModel(model)
                assertEquals(batch, switched.recBatchSize)
                assertEquals(DetectorSettings.defaults(model), switched.detector)
            }
            assertEquals(DetectorSettings.defaults(original.model), changed.resetDetector().detector)
            assertEquals(batch, changed.resetDetector().recBatchSize)
        }
    }

    @Test fun corruptPreferencesFallBackWithoutThrowing() {
        val valid = OCRSettings(OCRModel.MOBILE, 4, DetectorSettings(.1f, .8f, 2f, 42))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 1.1f, "oops", null)) {
            val restored = OCRSettings.fromPreferences(valid.preferenceValues() + ("detThresh" to bad))
            assertEquals(valid.resetDetector(), restored)
        }
        assertEquals(OCRSettings(), OCRSettings.fromPreferences(mapOf("selected" to 7, "recBatchSize" to "8")))
        assertEquals(OCRSettings(OCRModel.MOBILE), OCRSettings.fromPreferences(mapOf("selected" to "MOBILE")))
    }

    @Test fun draftValidationRejectsInvalidAndNonFiniteValues() {
        val good = DetectorDraft("0", "1", "0.001", "10000")
        assertNotNull(good.settings())
        assertTrue(good.errors.all { it == null })
        for (bad in listOf("", "-0.1", "1.1", "NaN", "Infinity", "1e100", "abc", ".")) {
            assertNull(good.copy(thresh = bad).settings())
            assertNotNull(good.copy(thresh = bad).errors[0])
            assertNull(good.copy(box = bad).settings())
        }
        for (bad in listOf("0", "-1", "10.1", "NaN", "Infinity")) {
            assertNull(good.copy(unclip = bad).settings())
            assertNotNull(good.copy(unclip = bad).errors[2])
        }
        for (bad in listOf("0", "10001", "1.5", "2147483648", "NaN", "")) {
            assertNull(good.copy(candidates = bad).settings())
            assertNotNull(good.copy(candidates = bad).errors[3])
        }
        assertNotNull(good.copy(unclip = "10", candidates = "1").settings())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedBatchRejected() { OCRSettings(recBatchSize = 3) }
}
