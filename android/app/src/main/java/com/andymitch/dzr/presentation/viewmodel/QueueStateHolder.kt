package com.andymitch.dzr.presentation.viewmodel

import com.andymitch.dzr.data.model.Song

import com.andymitch.dzr.utils.QueueUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages queue shuffle state.
 * Extracted from PlayerViewModel to improve modularity.
 * 
 * This class handles the original queue order for shuffle/unshuffle operations.
 */
@Singleton
class QueueStateHolder @Inject constructor() {
    
    // Original queue order before shuffle (for restoring when unshuffling)
    private var _originalQueueOrder: List<Song> = emptyList()
    val originalQueueOrder: List<Song> get() = _originalQueueOrder
    
    // Original queue name before shuffle
    private var _originalQueueName: String = "None"
    
    /**
     * Store the original queue state before shuffling.
     */
    fun saveOriginalQueueState(queue: List<Song>, queueName: String) {
        _originalQueueOrder = queue.toList()
        _originalQueueName = queueName
    }
    
    /**
     * Set original queue order (for updates during playback).
     */
    fun setOriginalQueueOrder(queue: List<Song>) {
        _originalQueueOrder = queue.toList()
    }
    
    /**
     * Check if original queue is empty.
     */
    fun hasOriginalQueue(): Boolean = _originalQueueOrder.isNotEmpty()
    
    
    



    /**
     * Suspendable variant for large queues.
     * Runs the heavy shuffle computation on Default dispatcher to avoid UI stalls.
     */
    suspend fun prepareShuffledQueueSuspending(
        songs: List<Song>, 
        queueName: String, 
        startAtZero: Boolean = false
    ): Pair<List<Song>, Song>? {
        if (songs.isEmpty()) return null

        val startSong = songs.random()
        saveOriginalQueueState(songs, queueName)

        val startIndex = songs.indexOfFirst { it.id == startSong.id }.coerceAtLeast(0)
        val shuffledQueue = withContext(Dispatchers.Default) {
            QueueUtils.buildAnchoredShuffleQueueSuspending(songs, startIndex, startAtZero)
        }
        return Pair(shuffledQueue, startSong)
    }
}
