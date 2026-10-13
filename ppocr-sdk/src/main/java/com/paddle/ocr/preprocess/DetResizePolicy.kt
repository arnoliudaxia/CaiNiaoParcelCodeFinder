package com.paddle.ocr.preprocess

/** DetResizeForTest.resize_image_type2, distinct from limit_type="resize_long".
 * Returns (height, width). Scale to the requested long side, truncate, then ceil
 * each dimension to stride 128, as in ppocr/data/imaug/operators.py.
 */
object DetResizePolicy {
    fun explicitLongSize(height: Int, width: Int, resizeLong: Int): Pair<Int, Int> {
        require(height > 0 && width > 0 && resizeLong > 0)
        val ratio = resizeLong.toDouble() / maxOf(height, width)
        fun aligned(value: Int): Int {
            val scaled = (value * ratio).toInt()
            // Guard pathological aspect ratios against an invalid zero-size OpenCV image.
            return (((scaled.toLong() + 127) / 128) * 128).coerceAtLeast(128).toInt()
        }
        return aligned(height) to aligned(width)
    }
}
