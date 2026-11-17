package com.andymitch.dzr.di

import com.andymitch.dzr.data.repository.LyricsRepositoryImpl
import com.andymitch.dzr.data.repository.TransitionRepositoryImpl

import com.andymitch.dzr.data.repository.LyricsRepository
import com.andymitch.dzr.data.repository.MusicRepository
import com.andymitch.dzr.data.repository.MusicRepositoryImpl
import com.andymitch.dzr.data.repository.TransitionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindMusicRepository(
        musicRepositoryImpl: MusicRepositoryImpl
    ): MusicRepository

    @Binds
    abstract fun bindLyricsRepository(
        lyricsRepositoryImpl: LyricsRepositoryImpl
    ): LyricsRepository

    @Binds
    abstract fun bindTransitionRepository(
        transitionRepositoryImpl: TransitionRepositoryImpl
    ): TransitionRepository
}
