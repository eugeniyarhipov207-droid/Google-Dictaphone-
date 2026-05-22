package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorderManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var startTime: Long = 0L
    var isRecording: Boolean = false
        private set

    fun startRecording(outputFile: File): Boolean {
        if (isRecording) return false
        
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            startTime = System.currentTimeMillis()
            isRecording = true
            return true
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Failed to start recording", e)
            mediaRecorder?.release()
            mediaRecorder = null
            return false
        }
    }

    fun stopRecording(): Long {
        if (!isRecording) return 0L
        
        var duration = 0L
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            duration = System.currentTimeMillis() - startTime
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Failed to stop recording", e)
        } finally {
            mediaRecorder = null
            isRecording = false
        }
        return duration
    }

    fun getAmplitude(): Float {
        if (!isRecording) return 0f
        return try {
            val maxAmp = mediaRecorder?.maxAmplitude ?: 0
            // Normalize amplitude scale 0f - 1f (max input is usually 32767)
            (maxAmp.toFloat() / 32767f).coerceIn(0f, 1f)
        } catch (e: Exception) {
            0f
        }
    }
}

class AudioPlayerManager {
    private var mediaPlayer: MediaPlayer? = null
    var isPlaying: Boolean = false
        private set
    private var currentPlayingFile: File? = null

    fun startPlaying(file: File, onCompletion: () -> Unit): Boolean {
        if (mediaPlayer != null && currentPlayingFile == file) {
            // Re-start or resume if paused
            mediaPlayer?.start()
            isPlaying = true
            return true
        }
        
        stopPlaying()
        
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            mp.setOnCompletionListener {
                this@AudioPlayerManager.isPlaying = false
                onCompletion()
                releasePlayer()
            }
            mediaPlayer = mp
            currentPlayingFile = file
            isPlaying = true
            return true
        } catch (e: Exception) {
            Log.e("AudioPlayerManager", "Failed to start playback", e)
            releasePlayer()
            return false
        }
    }

    fun pausePlaying() {
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
        }
    }

    fun resumePlaying() {
        if (!isPlaying && mediaPlayer != null) {
            mediaPlayer?.start()
            isPlaying = true
        }
    }

    fun stopPlaying() {
        mediaPlayer?.stop()
        releasePlayer()
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
    }

    val currentPosition: Int
        get() = mediaPlayer?.currentPosition ?: 0

    val duration: Int
        get() = mediaPlayer?.duration ?: 0

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentPlayingFile = null
        isPlaying = false
    }
}
