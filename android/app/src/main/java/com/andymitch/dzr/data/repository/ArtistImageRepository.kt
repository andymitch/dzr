package com.andymitch.dzr.data.repository

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtistImageRepository @Inject constructor() {
    suspend fun getArtistImageUrl(artistName: String, artistId: Long): String? = null
    fun clearCache() {}
}
