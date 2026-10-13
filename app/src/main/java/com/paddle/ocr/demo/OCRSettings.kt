package com.paddle.ocr.demo

import com.paddle.ocr.PaddleOCRConfig

/** Immutable, validated detector overrides. UI and persisted values use the same validation. */
data class DetectorSettings(
    val detThresh: Float,
    val detBoxThresh: Float,
    val detUnclipRatio: Float,
    val detMaxCandidates: Int,
) {
    init {
        require(detThresh.isFinite() && detThresh in 0f..1f)
        require(detBoxThresh.isFinite() && detBoxThresh in 0f..1f)
        require(detUnclipRatio.isFinite() && detUnclipRatio > 0f && detUnclipRatio <= 10f)
        require(detMaxCandidates in 1..10000)
    }

    companion object {
        fun defaults(model: OCRModel) = when (model) {
            OCRModel.SMALL -> DetectorSettings(.2f, .45f, 1.4f, 3000)
            OCRModel.TINY -> DetectorSettings(.2f, .4f, 1.4f, 3000)
            OCRModel.MOBILE -> DetectorSettings(.3f, .6f, 1.5f, 1000)
        }

        fun parse(thresh: String, box: String, unclip: String, candidates: String): DetectorSettings? {
            return runCatching {
                DetectorSettings(thresh.trim().toFloat(), box.trim().toFloat(),
                    unclip.trim().toFloat(), candidates.trim().toInt())
            }.getOrNull()
        }
    }
}

/** Recognition batches contain text ROIs from one frame, never multiple frames. */
data class OCRSettings(
    val model: OCRModel = OCRModel.SMALL,
    val recBatchSize: Int = DEFAULT_BATCH_SIZE,
    val detector: DetectorSettings = DetectorSettings.defaults(model),
) {
    init { require(recBatchSize in BATCH_SIZES) }

    fun withModel(model: OCRModel) = if (model == this.model) this else
        copy(model = model, detector = DetectorSettings.defaults(model))
    fun resetDetector() = copy(detector = DetectorSettings.defaults(model))

    /** Explicit overrides; omitted pipeline options retain SDK defaults. */
    fun config() = PaddleOCRConfig(
        recScoreThresh = 0.0f, recBatchSize = recBatchSize,
        detThresh = detector.detThresh, detBoxThresh = detector.detBoxThresh,
        detUnclipRatio = detector.detUnclipRatio, detMaxCandidates = detector.detMaxCandidates,
        detResizeLong = if (model == OCRModel.MOBILE) 960 else null,
    )

    fun preferenceValues(): Map<String, Any> = mapOf(
        "selected" to model.name, "recBatchSize" to recBatchSize,
        "detThresh" to detector.detThresh, "detBoxThresh" to detector.detBoxThresh,
        "detUnclipRatio" to detector.detUnclipRatio, "detMaxCandidates" to detector.detMaxCandidates,
    )

    companion object {
        const val DEFAULT_BATCH_SIZE = 4
        val BATCH_SIZES = listOf(1, 2, 4, 8)
        fun fromPreferences(model: String?, batch: Int) = OCRSettings(
            OCRModel.fromPreference(model), batch.takeIf { it in BATCH_SIZES } ?: DEFAULT_BATCH_SIZE,
        )

        /** Wrong types, missing fields, non-finite or out-of-range overrides fall back as a group. */
        fun fromPreferences(values: Map<String, *>) : OCRSettings {
            val savedModel = values["selected"] as? String
            val base = fromPreferences(savedModel, values["recBatchSize"] as? Int ?: DEFAULT_BATCH_SIZE)
            if (OCRModel.entries.none { it.name == savedModel }) return base
            val detector = runCatching {
                DetectorSettings(values["detThresh"] as Float, values["detBoxThresh"] as Float,
                    values["detUnclipRatio"] as Float, values["detMaxCandidates"] as Int)
            }.getOrNull() ?: base.detector
            return base.copy(detector = detector)
        }
    }
}
