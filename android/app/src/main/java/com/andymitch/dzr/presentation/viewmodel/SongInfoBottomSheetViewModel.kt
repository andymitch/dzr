package com.andymitch.dzr.presentation.viewmodel

import com.andymitch.dzr.data.model.Artist
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.utils.AudioMeta

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andymitch.dzr.R

import com.andymitch.dzr.utils.AudioMetaUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@HiltViewModel
class SongInfoBottomSheetViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val deezerRepository: com.andymitch.dzr.data.repository.DeezerRepository,
) : ViewModel() {

    suspend fun trackMix(song: Song): List<Song> =
        song.id.toLongOrNull()?.let { id ->
            deezerRepository.getTrackMix(id).map { com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong(it) }
        }.orEmpty()

    suspend fun banSong(song: Song): Boolean =
        song.id.toLongOrNull()?.let { deezerRepository.banTrack(it) } ?: false

    data class SongLocationInfo(
        val label: String,
        val value: String,
        val isCloud: Boolean,
    )

    enum class ToneTarget {
        Ringtone,
        Notification,
        Alarm,
    }

    sealed interface ToneActionResult {
        data class Success(val message: String) : ToneActionResult
        data class NeedsSystemWritePermission(val message: String) : ToneActionResult
        data class Error(val message: String) : ToneActionResult
    }

    private val _audioMeta = MutableStateFlow<AudioMeta?>(null)
    private val _resolvedArtists = MutableStateFlow<List<Artist>>(emptyList())
    val resolvedArtists: StateFlow<List<Artist>> = _resolvedArtists.asStateFlow()

    val audioMeta: StateFlow<AudioMeta?> = _audioMeta.asStateFlow()

    fun loadArtistsForSong(song: Song) {
        _resolvedArtists.value = emptyList()
    }

    fun loadAudioMeta(song: Song) {
        _audioMeta.value = null
    }













    private val ToneTarget.ringtoneManagerType: Int
        get() = when (this) {
            ToneTarget.Ringtone -> RingtoneManager.TYPE_RINGTONE
            ToneTarget.Notification -> RingtoneManager.TYPE_NOTIFICATION
            ToneTarget.Alarm -> RingtoneManager.TYPE_ALARM
        }

    private val ToneTarget.successLabelResId: Int
        get() = when (this) {
            ToneTarget.Ringtone -> R.string.song_info_tone_ringtone_label
            ToneTarget.Notification -> R.string.song_info_tone_notification_label
            ToneTarget.Alarm -> R.string.song_info_tone_alarm_label
        }
}
