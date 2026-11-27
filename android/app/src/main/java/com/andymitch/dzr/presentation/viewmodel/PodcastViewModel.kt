package com.andymitch.dzr.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andymitch.dzr.data.podcast.Episode
import com.andymitch.dzr.data.podcast.Podcast
import com.andymitch.dzr.data.podcast.PodcastRepository
import com.andymitch.dzr.data.podcast.toSong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastViewModel @Inject constructor(
    private val repository: PodcastRepository,
) : ViewModel() {
    val shows: StateFlow<List<Podcast>> = repository.index.map { it.shows }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.index.value.shows)

    val progress: StateFlow<Map<String, Long>> = repository.progress

    val newEpisodes: StateFlow<List<Pair<Podcast, Episode>>> = combine(repository.index, repository.progress) { index, progress ->
        index.shows.flatMap { show -> show.episodes.take(NEW_PER_SHOW).map { show to it } }
            .filter { progress[it.second.id] != PodcastRepository.PLAYED }
            .sortedByDescending { it.second.publishedMs }
            .take(NEW_EPISODES)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val speed: StateFlow<Float> = repository.speed
    val cutSilence: StateFlow<Boolean> = repository.cutSilence

    init {
        viewModelScope.launch { repository.refreshIfStale() }
    }

    suspend fun load(feedUrl: String): Podcast? = repository.show(feedUrl) ?: repository.fetch(feedUrl)

    suspend fun refresh(feedUrl: String): Podcast? = repository.fetch(feedUrl)?.also { fresh ->
        if (repository.show(feedUrl) != null) repository.subscribe(fresh)
    }

    suspend fun addByLink(url: String): Podcast? = repository.fetch(url.trim())?.also(repository::subscribe)

    fun subscribe(podcast: Podcast) = viewModelScope.launch {
        repository.subscribe(if (podcast.episodes.isEmpty()) repository.fetch(podcast.feedUrl) ?: podcast else podcast)
    }

    fun unsubscribe(feedUrl: String) = repository.unsubscribe(feedUrl)

    fun play(playerViewModel: PlayerViewModel, podcast: Podcast, episode: Episode) {
        val song = episode.toSong(podcast)
        playerViewModel.playSongs(listOf(song), song, queueName = podcast.title)
    }

    fun cycleSpeed() {
        val next = SPEEDS.firstOrNull { it > speed.value + 0.01f } ?: SPEEDS.first()
        repository.setSpeed(next)
    }

    fun toggleCutSilence() = repository.setCutSilence(!cutSilence.value)

    companion object {
        val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f)
        private const val NEW_PER_SHOW = 3
        private const val NEW_EPISODES = 20
    }
}
