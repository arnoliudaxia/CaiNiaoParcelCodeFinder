package com.paddle.ocr.demo

/** Text remains a draft until all four fields validate and Apply is pressed. */
data class DetectorDraft(val thresh: String, val box: String, val unclip: String, val candidates: String) {
    constructor(settings: DetectorSettings) : this(settings.detThresh.toString(),
        settings.detBoxThresh.toString(), settings.detUnclipRatio.toString(), settings.detMaxCandidates.toString())

    val errors: List<String?> get() = listOf(
        decimalError(thresh, 0f, 1f, false, "请输入 0～1 的有限小数"),
        decimalError(box, 0f, 1f, false, "请输入 0～1 的有限小数"),
        decimalError(unclip, 0f, 10f, true, "请输入大于 0 且不超过 10 的有限小数"),
        if (candidates.trim().toIntOrNull()?.let { it in 1..10000 } == true) null else "请输入 1～10000 的整数",
    )
    fun settings(): DetectorSettings? = DetectorSettings.parse(thresh, box, unclip, candidates)

    private fun decimalError(text: String, min: Float, max: Float, exclusive: Boolean, error: String): String? {
        val value = text.trim().toFloatOrNull()
        return if (value != null && value.isFinite() && value <= max &&
            (if (exclusive) value > min else value >= min)) null else error
    }
}
