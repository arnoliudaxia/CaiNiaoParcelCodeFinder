// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.demo.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingBar(
    detectionMs: Long,
    recognitionMs: Long,
    preprocessMs: Long,
    postprocessMs: Long?,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = {},
            label = {
                Text("前处理: ${preprocessMs}ms", style = MaterialTheme.typography.labelSmall)
            },
        )
        AssistChip(
            onClick = {},
            label = {
                Text("Det: ${detectionMs}ms", style = MaterialTheme.typography.labelSmall)
            },
        )
        AssistChip(
            onClick = {},
            label = {
                Text("Rec: ${recognitionMs}ms", style = MaterialTheme.typography.labelSmall)
            },
        )
        AssistChip(
            onClick = {},
            label = {
                Text("后处理: ${postprocessMs?.let { "${it}ms" } ?: "待绘制"}", style = MaterialTheme.typography.labelSmall)
            },
        )
    }
}
