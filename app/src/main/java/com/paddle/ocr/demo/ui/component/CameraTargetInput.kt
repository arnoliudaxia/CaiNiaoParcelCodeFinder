package com.paddle.ocr.demo.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paddle.ocr.demo.CameraTarget

@Composable
fun CameraTargetInput(state: CameraTarget, enabled: Boolean, onEdit: (String) -> Unit,
    onApply: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.draft, onValueChange = onEdit, enabled = enabled,
                label = { Text("相机目标（可留空）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.error != null, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onApply, enabled = enabled) { Text("应用") }
            TextButton(onClick = onClear, enabled = enabled) { Text("清除") }
        }
        Text(state.error ?: "当前目标：${state.active ?: "无（普通识别）"}",
            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}
