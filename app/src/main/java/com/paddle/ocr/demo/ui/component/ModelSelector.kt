package com.paddle.ocr.demo.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.paddle.ocr.demo.OCRApplication
import com.paddle.ocr.demo.OCRModel

@Composable
fun ModelSelector(state: OCRApplication.ModelState, enabled: Boolean, onSelected: (OCRModel) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Column(modifier) {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text(when (state) {
                    is OCRApplication.ModelState.Ready -> "模型：${state.settings.model.label} ▾"
                    is OCRApplication.ModelState.Loading -> "正在加载：${state.requested.model.label}"
                    is OCRApplication.ModelState.Error -> "选择模型 ▾"
                })
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                OCRModel.entries.forEach { model ->
                    DropdownMenuItem(text = { Text(model.label) }, onClick = {
                        expanded = false
                        onSelected(model)
                    })
                }
            }
        }
        if (state is OCRApplication.ModelState.Loading && state.previous != null) {
            Text("先前模型：${state.previous.model.label} / batch ${state.previous.recBatchSize}（暂停使用）", style = MaterialTheme.typography.bodySmall)
        }
        if (state is OCRApplication.ModelState.Ready && state.warning != null) {
            Text("${state.warning}；继续使用 ${state.settings.model.label} / batch ${state.settings.recBatchSize}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state is OCRApplication.ModelState.Ready) {
            val config = state.settings.config()
            val resize = config.detResizeLong?.let { "长边 $it / 对齐128" } ?: "SDK缩放(min64 / max4000)"
            Text("Det 阈值 ${config.detThresh} / 框 ${config.detBoxThresh} / 扩张 ${config.detUnclipRatio} / 候选 ${config.detMaxCandidates}；$resize", style = MaterialTheme.typography.bodySmall)
        }
        Text("模型检测默认参数 / BGR / CPU 6线程；切换前请停止视频或相机。", style = MaterialTheme.typography.bodySmall)
    }
}
