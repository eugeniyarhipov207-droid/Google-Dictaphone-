package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Recording
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.DictaphoneViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Support explicit toggle or sync with system dark/light theme
            var forceDarkTheme by remember { mutableStateOf<Boolean?>(null) }
            val systemDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            val useDarkTheme = forceDarkTheme ?: systemDarkTheme

            MyApplicationTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DictaphoneApp(
                        isDarkTheme = useDarkTheme,
                        onThemeToggle = { forceDarkTheme = !useDarkTheme }
                    )
                }
            }
        }
    }
}

enum class ShareMode {
    TEXT, AUDIO, BOTH
}

// Global share utility supporting sharing data directly to remote cloud apps (Yandex, Drive, Dropbox, Yandex.Disk etc.)
fun shareRecording(context: Context, recording: Recording, mode: ShareMode) {
    try {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Log.e("ShareRecording", "Audio file not found")
            return
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "com.example.fileprovider",
            file
        )

        val intent = when (mode) {
            ShareMode.TEXT -> {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, recording.title)
                    putExtra(Intent.EXTRA_TEXT, recording.transcription ?: "[Нет расшифрованного текста]")
                }
            }
            ShareMode.AUDIO -> {
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, recording.title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            ShareMode.BOTH -> {
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, recording.title)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "${recording.title}\n\nТранскрипция:\n${recording.transcription ?: "[Нет текста]"}"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        val chooser = Intent.createChooser(intent, "Экспорт заметок...")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e("ShareRecording", "Failed to share note", e)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictaphoneApp(
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    viewModel: DictaphoneViewModel = viewModel()
) {
    val context = LocalContext.current
    val recordings by viewModel.recordings.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDurationMs by viewModel.recordingDurationMs.collectAsState()
    val amplitudeHistory by viewModel.amplitudeHistory.collectAsState()

    val playingRecordingId by viewModel.playingRecordingId.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val playbackPositionMs by viewModel.playbackPositionMs.collectAsState()
    val playbackDurationMs by viewModel.playbackDurationMs.collectAsState()

    val errorMessage by viewModel.errorMessage.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedRecordingIdForDetail by remember { mutableStateOf<Int?>(null) }

    // Dialog editing states
    var recordingToRename by remember { mutableStateOf<Recording?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }

    var recordingToDelete by remember { mutableStateOf<Recording?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var selectedRecordingForExport by remember { mutableStateOf<Recording?>(null) }
    var showExportSheet by remember { mutableStateOf(false) }

    // Audio recording runtime permissions check
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                viewModel.startRecording()
            } else {
                viewModel.setManualError("Пожалуйста, предоставьте доступ к микрофону для записи голоса.")
            }
        }
    )

    // Helper to format timestamps dynamically
    val timeFormat = remember { SimpleDateFormat("mm:ss", Locale.getDefault()) }
    fun formatMs(ms: Long): String {
        return timeFormat.format(Date(ms))
    }

    // Filter past recordings based on structural matches
    val filteredRecordings = remember(recordings, searchQuery) {
        if (searchQuery.isBlank()) {
            recordings
        } else {
            recordings.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                        (it.transcription ?: "").contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Диктофон AI",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(
                        onClick = onThemeToggle,
                        modifier = Modifier.testTag("theme_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Сменить тему",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        bottomBar = {
            // Elegant Recording Control Center formatted to Sleek Theme guidelines
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                    )
                    .padding(bottom = 24.dp, top = 16.dp, start = 24.dp, end = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    // Active Recording panel containing voice wave amplitude pulses
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE91E63))
                            )
                            Text(
                                text = "Запись звука...",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        Text(
                            text = formatMs(recordingDurationMs),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        // Animated live soundbar amplitude waveform visualizer - Matches Sleek Style in HTML
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val maxBars = 35
                            val normalizedHistory = amplitudeHistory.takeLast(maxBars)
                            val paddedSize = maxBars - normalizedHistory.size
                            val finalWaveform = List(paddedSize) { 0.05f } + normalizedHistory

                            finalWaveform.forEach { amp ->
                                val barHeight = (4.dp + (46.dp * amp))
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(barHeight)
                                        .weight(1f)
                                        .padding(horizontal = 1.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Large Sleek Stop Button
                        Button(
                            onClick = { viewModel.stopRecording() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = CircleShape,
                            modifier = Modifier
                                .size(64.dp)
                                .testTag("stop_record_button"),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Остановить запись",
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                } else {
                    // Ready to Record Minimalist action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val permission = Manifest.permission.RECORD_AUDIO
                                if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                                    viewModel.startRecording()
                                } else {
                                    permissionLauncher.launch(permission)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = CircleShape,
                            modifier = Modifier
                                .height(60.dp)
                                .fillMaxWidth()
                                .testTag("start_record_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Начать запись",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "Начать новую запись",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Elegant M3 Search and Filter Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Поиск записей и текстов") },
                placeholder = { Text("Название надиктовки или ключевое слово...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("search_text_input")
            )

            // Dynamic Error Warning Bar
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                errorMessage?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.clearError() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Закрыть",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            // List of Recordings
            if (filteredRecordings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = if (searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.Default.SettingsVoice,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Записи с таким текстом не найдены" else "У вас пока нет записей.\nНажмите красную кнопку внизу!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("recordings_list"),
                    contentPadding = PaddingValues(16.dp, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredRecordings, key = { it.id }) { recording ->
                        val isExpanded = selectedRecordingIdForDetail == recording.id
                        
                        RecordingItemCard(
                            recording = recording,
                            isExpanded = isExpanded,
                            isPlayingThis = playingRecordingId == recording.id,
                            isPlaying = isPlaying,
                            playbackProgress = playbackProgress,
                            playbackPositionMs = playbackPositionMs,
                            playbackDurationMs = playbackDurationMs,
                            onCardClick = {
                                selectedRecordingIdForDetail = if (isExpanded) null else recording.id
                            },
                            onPlayPauseToggle = { viewModel.togglePlayback(recording) },
                            onSeek = { progress -> viewModel.seekPlayback(progress) },
                            onRenameClick = {
                                renameInput = recording.title
                                recordingToRename = recording
                                showRenameDialog = true
                            },
                            onExportClick = {
                                selectedRecordingForExport = recording
                                showExportSheet = true
                            },
                            onDeleteClick = {
                                recordingToDelete = recording
                                showDeleteDialog = true
                            },
                            onTranscribeRetry = { viewModel.transcribeRecording(recording.id) },
                            onSaveTranscriptionText = { updatedText ->
                                viewModel.updateTranscription(recording.id, updatedText)
                            }
                        )
                    }
                }
            }
        }
    }

    // Rename Dialog
    if (showRenameDialog && recordingToRename != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Переименовать запись") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Название") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("rename_text_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        recordingToRename?.let { viewModel.renameRecording(it.id, renameInput) }
                        showRenameDialog = false
                    },
                    modifier = Modifier.testTag("rename_confirm_button")
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog && recordingToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить запись?") },
            text = { Text("Вы действительно хотите безвозвратно удалить аудиозапись «${recordingToDelete?.title}» и сохраненный текст?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        recordingToDelete?.let { viewModel.deleteRecording(it) }
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("delete_confirm_button")
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Export Options Selector Sheet / Dialog (Material 3 styling)
    if (showExportSheet && selectedRecordingForExport != null) {
        val recording = selectedRecordingForExport!!
        AlertDialog(
            onDismissRequest = { showExportSheet = false },
            title = { Text("Экспорт заметки в облако") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Выберите вид экспорта. Вы сможете отправить файл в Google Drive, Яндекс.Диск, Dropbox, Telegram или почту:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Button(
                        onClick = {
                            shareRecording(context, recording, ShareMode.TEXT)
                            showExportSheet = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_text_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Article, contentDescription = null)
                            Text("Поделиться текстом распознавания")
                        }
                    }

                    Button(
                        onClick = {
                            shareRecording(context, recording, ShareMode.AUDIO)
                            showExportSheet = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_audio_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AudioFile, contentDescription = null)
                            Text("Отправить аудиозапись (.m4a)")
                        }
                    }

                    Button(
                        onClick = {
                            shareRecording(context, recording, ShareMode.BOTH)
                            showExportSheet = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_both_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Text("Экспортировать Аудио + Текст")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportSheet = false }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

@Composable
fun RecordingItemCard(
    recording: Recording,
    isExpanded: Boolean,
    isPlayingThis: Boolean,
    isPlaying: Boolean,
    playbackProgress: Float,
    playbackPositionMs: Int,
    playbackDurationMs: Int,
    onCardClick: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onRenameClick: () -> Unit,
    onExportClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onTranscribeRetry: () -> Unit,
    onSaveTranscriptionText: (String) -> Unit
) {
    var transcriptionEditorText by remember(recording.transcription) {
        mutableStateOf(recording.transcription ?: "")
    }
    
    val formattedDuration = remember(recording.durationMs) {
        val totalSecs = recording.durationMs / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        String.format("%02d:%02d", mins, secs)
    }

    val formattedDate = remember(recording.timestamp) {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        sdf.format(Date(recording.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() }
            .testTag("recording_item_card_${recording.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isExpanded) 4.dp else 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Main Summary Panel (Collapsed state)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular Play Button
                IconButton(
                    onClick = onPlayPauseToggle,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .testTag("play_pause_button_${recording.id}")
                ) {
                    Icon(
                        imageVector = if (isPlayingThis && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlayingThis && isPlaying) "Пауза" else "Воспроизвести",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = recording.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = formattedDuration,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            // Expanding Detail Panel
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

                    // Media Player Progress Slider
                    if (isPlayingThis) {
                        val timeFormat = remember { SimpleDateFormat("mm:ss", Locale.getDefault()) }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Slider(
                                value = playbackProgress,
                                onValueChange = onSeek,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("player_seek_slider_${recording.id}")
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = timeFormat.format(Date(playbackPositionMs.toLong())),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = timeFormat.format(Date(playbackDurationMs.toLong())),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Transcription UI Status / Body text
                    Text(
                        text = "Текстовая расшифровка (ИИ):",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    when {
                        recording.isTranscribing -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp).testTag("transcribing_spinner"))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Распознавание речи искусственным интеллектом...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                        recording.transcriptionError != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Ошибка транскрипции: ${recording.transcriptionError}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TextButton(
                                    onClick = onTranscribeRetry,
                                    modifier = Modifier.testTag("retry_transcribe_button_${recording.id}")
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text("Повторить расшифровку ИИ")
                                    }
                                }
                            }
                        }
                        else -> {
                            // Render Editable Outlined Text Box of Transcript
                            OutlinedTextField(
                                value = transcriptionEditorText,
                                onValueChange = { transcriptionEditorText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp, max = 220.dp)
                                    .testTag("transcription_editor_${recording.id}"),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                shape = RoundedCornerShape(12.dp),
                                placeholder = { Text("Тут появится расшифрованный текст...") }
                            )

                            // Save Text Modification Trigger
                            if (transcriptionEditorText != (recording.transcription ?: "")) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = { onSaveTranscriptionText(transcriptionEditorText) },
                                        modifier = Modifier.testTag("save_text_button_${recording.id}")
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Text("Сохранить изменения текста")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Secondary Card Actions Row (Share to Cloud, Rename, Delete)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rename Trigger
                        IconButton(
                            onClick = onRenameClick,
                            modifier = Modifier.testTag("rename_button_${recording.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Переименовать",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Share/Export to Cloud Storage
                        IconButton(
                            onClick = onExportClick,
                            modifier = Modifier.testTag("export_button_${recording.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "Экспортировать в облако",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Delete
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.testTag("delete_button_${recording.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Удалить",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Привет $name!", modifier = modifier)
}
