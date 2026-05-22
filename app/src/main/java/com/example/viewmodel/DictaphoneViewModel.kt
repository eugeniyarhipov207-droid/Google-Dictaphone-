package com.example.viewmodel

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.Content
import com.example.api.GenerateContentRequest
import com.example.api.InlineData
import com.example.api.Part
import com.example.api.RetrofitClient
import com.example.audio.AudioPlayerManager
import com.example.audio.AudioRecorderManager
import com.example.data.AppDatabase
import com.example.data.Recording
import com.example.data.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DictaphoneViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RecordingRepository
    val recordings: StateFlow<List<Recording>>

    private val recorderManager = AudioRecorderManager(application)
    private val playerManager = AudioPlayerManager()

    // Recorder State
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs = _recordingDurationMs.asStateFlow()

    private val _amplitudeHistory = MutableStateFlow<List<Float>>(emptyList())
    val amplitudeHistory = _amplitudeHistory.asStateFlow()

    private var recordTimerJob: Job? = null
    private var activeOutputFile: File? = null

    // Player State
    private val _playingRecordingId = MutableStateFlow<Int?>(null)
    val playingRecordingId = _playingRecordingId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress = _playbackProgress.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0)
    val playbackPositionMs = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0)
    val playbackDurationMs = _playbackDurationMs.asStateFlow()

    private var playbackProgressJob: Job? = null

    // Active error warning message
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = RecordingRepository(database.recordingDao())
        recordings = repository.allRecordings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    // Clear error
    fun clearError() {
        _errorMessage.value = null
    }

    // Set custom manual error
    fun setManualError(msg: String) {
        _errorMessage.value = msg
    }

    // Start Voice Recording
    fun startRecording() {
        if (recorderManager.isRecording) return
        
        // Stop any playback first
        stopPlayback()

        val timestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "REC_${dateFormat.format(Date(timestamp))}.m4a"
        val file = File(getApplication<Application>().filesDir, fileName)
        activeOutputFile = file

        _amplitudeHistory.value = emptyList()
        _recordingDurationMs.value = 0L

        val success = recorderManager.startRecording(file)
        if (success) {
            _isRecording.value = true
            startRecordTimer()
        } else {
            _errorMessage.value = "Не удалось запустить запись. Пожалуйста, разрешите доступ к микрофону."
        }
    }

    // Stop Voice Recording
    fun stopRecording() {
        if (!recorderManager.isRecording) return

        recordTimerJob?.cancel()
        val durationMs = recorderManager.stopRecording()
        _isRecording.value = false

        val file = activeOutputFile
        if (file != null && file.exists() && durationMs > 500) { 
            // Valid recording
            val timestamp = System.currentTimeMillis()
            val defaultTitle = SimpleDateFormat("Запись dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
            val recording = Recording(
                title = defaultTitle,
                filePath = file.absolutePath,
                durationMs = durationMs,
                timestamp = timestamp
            )

            viewModelScope.launch {
                val newId = repository.insert(recording).toInt()
                // Automatically transcribe this recording
                transcribeRecording(newId)
            }
        } else {
            _errorMessage.value = "Запись отменена или оказалась слишком короткой."
            file?.delete()
        }
        activeOutputFile = null
    }

    private fun startRecordTimer() {
        recordTimerJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (recorderManager.isRecording) {
                delay(100)
                _recordingDurationMs.value = System.currentTimeMillis() - startTime
                
                // Get amplitude and add to dynamic visual history
                val amp = recorderManager.getAmplitude()
                _amplitudeHistory.value = (_amplitudeHistory.value + amp).takeLast(60) // keep last 60 bar cycles
            }
        }
    }

    // Trigger Transcription using Gemini AI
    fun transcribeRecording(id: Int) {
        viewModelScope.launch {
            val record = repository.getRecordingById(id) ?: return@launch
            if (record.isTranscribing) return@launch

            // Set loading state
            repository.update(record.copy(isTranscribing = true, transcriptionError = null))

            val file = File(record.filePath)
            if (!file.exists()) {
                repository.update(record.copy(
                    isTranscribing = false,
                    transcriptionError = "Файл записи не найден на диске."
                ))
                return@launch
            }

            // Do background conversion and REST call
            try {
                val transcriptionResult = withContext(Dispatchers.IO) {
                    val bytes = file.readBytes()
                    if (bytes.isEmpty()) {
                        throw Exception("Файл аудио пуст или поврежден.")
                    }
                    val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    
                    val apiKey = BuildConfig.GEMINI_API_KEY
                    if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "GEMINI_API_KEY") {
                        throw Exception("Ключ API Gemini отсутствует. Пожалуйста, добавьте его через вкладку Secrets в AI Studio.")
                    }

                    val systemPrompt = "Ты — продвинутый ассистент распознавания русской речи. Твоя единственная цель — транскрибировать предложенный файл аудио в точный текст на языке оригинала (в основном русский). Выведи только распознанный текст. Не используй комментарии, вступления или заключения."
                    
                    val inlineData = InlineData(mimeType = "audio/mp4", data = base64Audio)
                    val request = GenerateContentRequest(
                        contents = listOf(Content(parts = listOf(
                            Part(inlineData = inlineData)
                        ))),
                        systemInstruction = Content(parts = listOf(Part(text = systemPrompt)))
                    )

                    val response = RetrofitClient.service.generateContent(apiKey, request)
                    val outText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (outText.isNullOrBlank()) {
                        throw Exception("Gemini не распознал речь. Возможно, на записи тишина.")
                    }
                    outText.trim()
                }

                repository.update(record.copy(
                    transcription = transcriptionResult,
                    isTranscribing = false,
                    transcriptionError = null
                ))
            } catch (e: Exception) {
                Log.e("DictaphoneViewModel", "Transcription failed info", e)
                val displayError = when {
                    e.message?.contains("GEMINI_API_KEY") == true -> "Добавьте GEMINI_API_KEY в панели Secrets!"
                    e.message?.contains("Unable to resolve host") == true -> "Проверьте интернет-соединение."
                    else -> e.localizedMessage ?: "Не удалось составить транскрипцию."
                }
                repository.update(record.copy(
                    isTranscribing = false,
                    transcriptionError = displayError
                ))
            }
        }
    }

    // Manual Edit of Transcription
    fun updateTranscription(id: Int, text: String) {
        viewModelScope.launch {
            val record = repository.getRecordingById(id) ?: return@launch
            repository.update(record.copy(transcription = text))
        }
    }

    // Rename recorded note
    fun renameRecording(id: Int, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            val record = repository.getRecordingById(id) ?: return@launch
            repository.update(record.copy(title = newTitle.trim()))
        }
    }

    // Delete recording
    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            // Stop playing first
            if (_playingRecordingId.value == recording.id) {
                stopPlayback()
            }
            // Delete file from disk
            try {
                val file = File(recording.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e("DictaphoneViewModel", "Failed to delete file", e)
            }
            // Delete DB record
            repository.delete(recording)
        }
    }

    // Playback control
    fun togglePlayback(recording: Recording) {
        if (_playingRecordingId.value == recording.id) {
            if (_isPlaying.value) {
                pausePlayback()
            } else {
                resumePlayback()
            }
        } else {
            startPlayback(recording)
        }
    }

    private fun startPlayback(recording: Recording) {
        stopPlayback()

        val file = File(recording.filePath)
        if (!file.exists()) {
            _errorMessage.value = "Файл аудио отсутствует на диске."
            return
        }

        _playingRecordingId.value = recording.id
        _isPlaying.value = true

        val success = playerManager.startPlaying(file) {
            // On completion callback
            stopPlayback()
        }

        if (success) {
            _playbackDurationMs.value = playerManager.duration
            startPlaybackProgressPolling()
        } else {
            stopPlayback()
            _errorMessage.value = "Ошибка при чтении аудиофайла."
        }
    }

    fun pausePlayback() {
        playerManager.pausePlaying()
        _isPlaying.value = false
        playbackProgressJob?.cancel()
    }

    fun resumePlayback() {
        playerManager.resumePlaying()
        _isPlaying.value = true
        startPlaybackProgressPolling()
    }

    fun stopPlayback() {
        playerManager.stopPlaying()
        playbackProgressJob?.cancel()
        _isPlaying.value = false
        _playingRecordingId.value = null
        _playbackProgress.value = 0f
        _playbackPositionMs.value = 0
        _playbackDurationMs.value = 0
    }

    fun seekPlayback(progress: Float) {
        if (_playingRecordingId.value != null) {
            val posMs = (progress * _playbackDurationMs.value).toInt()
            playerManager.seekTo(posMs)
            _playbackPositionMs.value = posMs
            _playbackProgress.value = progress
        }
    }

    private fun startPlaybackProgressPolling() {
        playbackProgressJob?.cancel()
        playbackProgressJob = viewModelScope.launch {
            while (_isPlaying.value) {
                val pos = playerManager.currentPosition
                val dur = playerManager.duration
                if (dur > 0) {
                    _playbackPositionMs.value = pos
                    _playbackProgress.value = pos.toFloat() / dur.toFloat()
                }
                delay(100)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        recorderManager.stopRecording()
        playerManager.stopPlaying()
        recordTimerJob?.cancel()
        playbackProgressJob?.cancel()
    }
}
