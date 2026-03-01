package com.livetranscript.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class TranscriptEntry(
    val speakerId: Int,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun LiveScreen(
    isRecording: Boolean,
    transcripts: List<TranscriptEntry>,
    modelsReady: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to bottom on new entries
    LaunchedEffect(transcripts.size) {
        if (transcripts.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(transcripts.size - 1)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "LiveTranscript",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Status indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = when {
                            !modelsReady -> Color.Gray
                            isRecording -> Color.Red
                            else -> Color.Green
                        },
                        shape = RoundedCornerShape(6.dp)
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    !modelsReady -> "Loading models..."
                    isRecording -> "Recording..."
                    else -> "Ready"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Transcript list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (transcripts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (modelsReady) "Press Start to begin transcription"
                            else "Preparing models...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(transcripts) { entry ->
                TranscriptBubble(entry = entry)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = if (isRecording) onStopRecording else onStartRecording,
                enabled = modelsReady,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isRecording) "Stop" else "Start")
            }

            OutlinedButton(
                onClick = onClear,
                enabled = transcripts.isNotEmpty() && !isRecording,
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear")
            }
        }
    }
}

@Composable
fun TranscriptBubble(entry: TranscriptEntry) {
    val speakerColors = listOf(
        Color(0xFF1976D2), // Blue
        Color(0xFF388E3C), // Green
        Color(0xFFF57C00), // Orange
        Color(0xFF7B1FA2), // Purple
        Color(0xFFD32F2F), // Red
        Color(0xFF0097A7), // Cyan
    )
    val color = speakerColors[entry.speakerId % speakerColors.size]

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (entry.speakerId >= 0) "Speaker ${entry.speakerId + 1}" else "Unknown",
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        Surface(
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 12.dp,
                bottomEnd = 12.dp,
                bottomStart = 12.dp
            ),
            color = color.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = entry.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 15.sp
            )
        }
    }
}
