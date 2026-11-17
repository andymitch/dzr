package com.andymitch.dzr.presentation.viewmodel

import android.net.Uri
import com.andymitch.dzr.R
import com.andymitch.dzr.data.preferences.ThemePreferencesRepository
import com.andymitch.dzr.data.preferences.UserPreferencesRepository
import com.andymitch.dzr.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.combine
import java.io.File

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andymitch.dzr.data.network.deezer.DeezerAuthApi
import com.andymitch.dzr.data.preferences.AppThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val notificationsPermissionGranted: Boolean = false,
    val deezerAuthUrl: String? = null,
    val deezerAuthCode: String? = null,
    val deezerLoginStatus: DeezerLoginStatus = DeezerLoginStatus.IDLE,
    val deezerLoggedIn: Boolean = false,
) {
    val allPermissionsGranted: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationsPermissionGranted
}

enum class DeezerLoginStatus {
    IDLE, REQUESTING, WAITING_FOR_USER, POLLING, SUCCESS, ERROR
}

sealed interface SetupEvent {
    data class Message(val value: String) : SetupEvent
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val deezerAuthApi: DeezerAuthApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<SetupEvent>()
    val events = _events.asSharedFlow()
    private var pollingJob: Job? = null

    init {
        viewModelScope.launch {
            if (!userPreferencesRepository.initialSetupDoneFlow.first()) {
                themePreferencesRepository.initializeAppThemeMode(AppThemeMode.DARK)
            }
            if (!userPreferencesRepository.deezerAccessTokenFlow.first().isNullOrEmpty()) {
                _uiState.update { it.copy(deezerLoggedIn = true, deezerLoginStatus = DeezerLoginStatus.SUCCESS) }
            }
        }
    }

    fun checkPermissions(context: Context) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        _uiState.update { it.copy(notificationsPermissionGranted = granted) }
    }

    fun setSetupComplete() {
        viewModelScope.launch { userPreferencesRepository.setInitialSetupDone(true) }
    }

    fun startDeezerLogin() {
        pollingJob?.cancel()
        _uiState.update { it.copy(deezerLoginStatus = DeezerLoginStatus.REQUESTING) }
        pollingJob = viewModelScope.launch {
            try {
                val data = deezerAuthApi.getSmartLoginCode(DEEZER_APP_ID).data ?: run {
                    _uiState.update { it.copy(deezerLoginStatus = DeezerLoginStatus.ERROR) }
                    return@launch
                }
                _uiState.update {
                    it.copy(deezerAuthUrl = data.url, deezerAuthCode = data.smartLoginCode, deezerLoginStatus = DeezerLoginStatus.POLLING)
                }
                val intervalMs = (data.pollingInterval * 1000).toLong().coerceAtLeast(2000L)
                while (true) {
                    delay(intervalMs)
                    val poll = runCatching { deezerAuthApi.pollSmartLogin(data.smartLoginCode, DEEZER_APP_ID) }.getOrNull()
                    val accessToken = poll?.data?.accessToken
                    val userId = poll?.data?.userId
                    if (accessToken != null && userId != null) {
                        userPreferencesRepository.saveDeezerAuth(accessToken, userId)
                        _uiState.update { it.copy(deezerLoginStatus = DeezerLoginStatus.SUCCESS, deezerLoggedIn = true) }
                        return@launch
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(deezerLoginStatus = DeezerLoginStatus.ERROR) }
            }
        }
    }

    fun cancelDeezerLogin() {
        pollingJob?.cancel()
        pollingJob = null
        _uiState.update { it.copy(deezerLoginStatus = DeezerLoginStatus.IDLE, deezerAuthUrl = null, deezerAuthCode = null) }
    }

    override fun onCleared() {
        pollingJob?.cancel()
    }

    private companion object {
        const val DEEZER_APP_ID = "447462"
    }
}
