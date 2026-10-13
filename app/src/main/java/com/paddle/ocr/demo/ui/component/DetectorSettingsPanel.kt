package com.paddle.ocr.demo.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paddle.ocr.demo.*

@Composable
fun DetectorSettingsPanel(
    state: OCRApplication.ModelState,
    enabled: Boolean,
    onApply: (DetectorSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actual = when (state) {
        is OCRApplication.ModelState.Ready -> state.settings
        is OCRApplication.ModelState.Loading -> state.previous
        is OCRApplication.ModelState.Error -> null
    }
    val requested = (state as? OCRApplication.ModelState.Loading)?.requested
    var expanded by remember { mutableStateOf(false) }
    var draft by remember(actual) { mutableStateOf(DetectorDraft((actual ?: OCRSettings()).detector)) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    val editable = enabled && state is OCRApplication.ModelState.Ready
    LaunchedEffect(editable) { if (!editable) dismissKeyboard() }
    Column(modifier) {
        TextButton(onClick = { expanded = !expanded; dismissKeyboard() }) {
            Text("检测参数 ${if (expanded) "▴" else "▾"} · " +
                (actual?.detector?.let { "${it.detThresh} / ${it.detBoxThresh} / ${it.detUnclipRatio} / ${it.detMaxCandidates}" }
                    ?: "尚未加载"))
        }
        if (requested != null) {
            Text("请求加载：${requested.detector.let { "${it.detThresh} / ${it.detBoxThresh} / ${it.detUnclipRatio} / ${it.detMaxCandidates}" }}；上方为已加载值",
                style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("顺序：像素阈值 / 框阈值 / 扩张比例 / 候选数。编辑后点击应用，仅重载一次。",
                    style = MaterialTheme.typography.bodySmall)
                val errors = draft.errors
                val labels = listOf("像素阈值 detThresh [0,1]", "框阈值 detBoxThresh [0,1]",
                    "扩张比例 detUnclipRatio (0,10]", "候选数 detMaxCandidates [1,10000]")
                val values = listOf(draft.thresh, draft.box, draft.unclip, draft.candidates)
                values.forEachIndexed { index, value ->
                    OutlinedTextField(value = value, onValueChange = { text ->
                        draft = when (index) {
                            0 -> draft.copy(thresh = text)
                            1 -> draft.copy(box = text)
                            2 -> draft.copy(unclip = text)
                            else -> draft.copy(candidates = text)
                        }
                    }, label = { Text(labels[index]) }, enabled = editable, singleLine = true,
                        isError = errors[index] != null,
                        supportingText = errors[index]?.let { error -> { Text(error) } },
                        keyboardOptions = KeyboardOptions(keyboardType = if (index == 3) KeyboardType.Number else KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = {
                    actual?.let { draft = DetectorDraft(it.resetDetector().detector) }
                    dismissKeyboard()
                }, enabled = editable) { Text("恢复当前模型默认值（再点击应用）") }
                Button(onClick = {
                    draft.settings()?.let { dismissKeyboard(); expanded = false; onApply(it) }
                }, enabled = editable && draft.settings() != null && draft.settings() != actual?.detector) {
                    Text("应用检测参数")
                }
            }
        }
    }
}
