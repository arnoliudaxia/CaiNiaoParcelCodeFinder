package com.paddle.ocr.demo

/** Paired graphs and their OWN recognition dictionary. Small preserves the SDK default paths. */
enum class OCRModel(val label: String, private val directory: String, val classes: Int) {
    SMALL("PP-OCRv6_small", "models", 18710),
    TINY("PP-OCRv6_tiny", "models/v6_tiny", 6906),
    MOBILE("PP-OCRv5_mobile", "models/v5_mobile", 18385);

    val detPath get() = "$directory/det/inference.onnx"
    val recPath get() = "$directory/rec/inference.onnx"
    val dictionaryPath get() = "$directory/rec/inference.yml"

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: SMALL
    }
}
