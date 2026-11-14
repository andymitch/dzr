package com.lostf1sh.pixelplayeross.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.network.deezer.DeezerUserMeResponse
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.DeezerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    val isLoading: Boolean = true,
    val user: DeezerUserMeResponse? = null,
    val error: String? = null
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val deezerRepository: DeezerRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState

    init {
        fetchUser()
    }

    private fun fetchUser() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = deezerRepository.getUserMe()
            result.fold(
                onSuccess = { response ->
                    _uiState.update { it.copy(isLoading = false, user = response) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = "Failed to load account info: ${e.message}") }
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            userPreferencesRepository.clearDeezerAuth()
        }
    }
}
