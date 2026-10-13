package com.paddle.ocr.demo.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.paddle.ocr.demo.OCRApplication
import com.paddle.ocr.demo.OCRSettings

@Composable
fun RecognitionBatchSelector(
    state: OCRApplication.ModelState,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Column(modifier) {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text(when (state) {
                    is OCRApplication.ModelState.Ready -> "识别 batch：${state.settings.recBatchSize} ▾"
                    is OCRApplication.ModelState.Loading -> "识别 batch：${state.requested.recBatchSize}（加载中）"
                    is OCRApplication.ModelState.Error -> "识别 batch ▾"
                })
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                OCRSettings.BATCH_SIZES.forEach { batch ->
                    DropdownMenuItem(text = { Text(batch.toString()) }, onClick = {
                        expanded = false
                        onSelected(batch)
                    })
                }
            }
        }
        Text("同一帧内的文字框数量，不是视频帧数", style = MaterialTheme.typography.bodySmall)
    }
}
