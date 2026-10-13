package com.paddle.ocr.demo

/** Scales each dimension, not pixel area. */
enum class ResolutionScale(val divisor: Int, val label: String) {
    ORIGINAL(1, "原始"),
    HALF(2, "一半"),
    QUARTER(4, "四分之一");

    fun dimension(original: Int): Int = (original / divisor).coerceAtLeast(1)
}
