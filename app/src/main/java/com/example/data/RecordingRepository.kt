package com.example.data

import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val recordingDao: RecordingDao) {
    val allRecordings: Flow<List<Recording>> = recordingDao.getAllRecordings()

    suspend fun getRecordingById(id: Int): Recording? {
        return recordingDao.getRecordingById(id)
    }

    suspend fun insert(recording: Recording): Long {
        return recordingDao.insert(recording)
    }

    suspend fun update(recording: Recording) {
        recordingDao.update(recording)
    }

    suspend fun delete(recording: Recording) {
        recordingDao.delete(recording)
    }
}
