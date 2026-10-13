package com.paddle.ocr.demo.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.paddle.ocr.demo.ResolutionScale

@Composable
fun ResolutionSelector(
    selected: ResolutionScale,
    onSelected: (ResolutionScale) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text("分辨率：${selected.label} ▾")
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                ResolutionScale.entries.forEach { scale ->
                    DropdownMenuItem(
                        text = { Text(scale.label) },
                        onClick = { expanded = false; onSelected(scale) },
                    )
                }
            }
        }
        Text(
            "按宽高缩放；视频/相机下一帧生效，图片下次识别生效。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
