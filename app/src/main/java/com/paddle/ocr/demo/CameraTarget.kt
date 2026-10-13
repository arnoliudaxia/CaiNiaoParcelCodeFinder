package com.paddle.ocr.demo

/** Draft changes never affect the applied target until Apply; empty means ordinary OCR. */
data class CameraTarget(val draft: String = "", val active: String? = null, val error: String? = null) {
    fun edit(value: String) = copy(draft = value, error = null)
    fun apply(): CameraTarget = if (draft.isEmpty() || isValid(draft)) {
        copy(active = draft.ifEmpty { null }, error = null)
    } else copy(error = "请输入恰好 4 位 ASCII 数字（0–9）")

    companion object {
        fun isValid(value: String) = value.length == 4 && value.all { it in '0'..'9' }
    }
}

/** Immutable metadata attached only to the camera result that caused the stop. */
data class CameraTargetHit(val target: String, val indices: Set<Int>)

/** Only surrounding OCR whitespace is ignored; never substring-match or parse integers. */
fun findCameraTargetHit(target: String?, texts: List<String>): CameraTargetHit? {
    if (target == null || !CameraTarget.isValid(target)) return null
    val indices = texts.mapIndexedNotNull { index, text -> index.takeIf { text.trim() == target } }.toSet()
    return if (indices.isEmpty()) null else CameraTargetHit(target, indices)
}
