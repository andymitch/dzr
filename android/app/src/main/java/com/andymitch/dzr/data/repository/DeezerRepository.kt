package com.andymitch.dzr.data.repository

import com.andymitch.dzr.data.preferences.UserPreferencesRepository

import com.andymitch.dzr.data.network.deezer.DeezerApiService
import com.andymitch.dzr.data.network.deezer.DeezerLyricsResponse
import com.andymitch.dzr.data.network.deezer.DeezerMultiFlowResponse
import com.andymitch.dzr.data.network.deezer.DeezerPlaylistDetailResponse
import com.andymitch.dzr.data.network.deezer.DeezerRecommendedPlaylistsResponse
import com.andymitch.dzr.data.network.deezer.DeezerStreamUrlsResponse
import com.andymitch.dzr.data.network.deezer.DeezerTrackInfoResponse
import com.andymitch.dzr.data.network.deezer.DeezerGenericSearchResponse
import com.andymitch.dzr.data.network.deezer.DeezerGenericSearchResponseObject
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import com.andymitch.dzr.data.network.deezer.DeezerPlaylist
import com.andymitch.dzr.data.network.deezer.DeezerPlaylistAttributes
import com.andymitch.dzr.data.network.deezer.DeezerImage
import javax.inject.Singleton

@Singleton
class DeezerRepository @Inject constructor(
    private val deezerApiService: DeezerApiService,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    private suspend fun getAuthHeader(): String? {
        val token = userPreferencesRepository.deezerAccessTokenFlow.firstOrNull()
        return if (!token.isNullOrEmpty()) "Bearer $token" else null
    }

    private suspend fun getRawToken(): String? {
        val token = userPreferencesRepository.deezerAccessTokenFlow.firstOrNull()
        return if (!token.isNullOrEmpty()) token else null
    }

    private suspend fun getUserId(): Long? {
        return userPreferencesRepository.deezerUserIdFlow.firstOrNull()
    }

    suspend fun getMultiFlowConfigs(): com.andymitch.dzr.data.network.deezer.DeezerMultiFlowConfigsResponse? {
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return try {
            deezerApiService.getMultiFlowConfigs(userId, auth)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getMultiFlowTracks(url: String): DeezerMultiFlowResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            val res = deezerApiService.getMultiFlowTracks(url, auth)
            res
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getRecentlySearched(): com.andymitch.dzr.data.network.deezer.DeezerGenericSearchResponseObject? {
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return try {
            deezerApiService.getRecentlySearched(userId, auth)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private var lovedPlaylistId: String? = null

    suspend fun lovedPlaylistId(): String? {
        lovedPlaylistId?.let { return it }
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return runCatching { deezerApiService.getAllGcastLovedTracks(userId, auth).data?.id }.getOrNull()?.also { lovedPlaylistId = it }
    }

    suspend fun getPlaylistTracks(playlistId: String, page: Int = 1, limit: Int = 50): com.andymitch.dzr.data.network.deezer.DeezerPlaylistDetailResponse? {
        if (page > 1) return null
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return try {
            if (lovedPlaylistId == null || lovedPlaylistId == playlistId) {
                val loved = deezerApiService.getAllGcastLovedTracks(userId, auth)
                lovedPlaylistId = loved.data?.id
                if (lovedPlaylistId == playlistId) return loved
            }
            val whole = deezerApiService.getWholePlaylist(playlistId, auth)
            val data = whole.data ?: return whole
            @Suppress("USELESS_CAST")
            val included = (data.included as List<com.andymitch.dzr.data.network.deezer.DeezerTrack>?) ?: emptyList()
            whole.copy(data = data.copy(included = included))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getLovedTracks(nbItems: Int = 2000, page: Int = 0): com.andymitch.dzr.data.network.deezer.DeezerPlaylistDetailResponse? {
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return try {
            deezerApiService.getLovedTracks(userId, auth, nbItems, page)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun addTrackToFavorites(trackId: Long): Boolean {
        val token = getRawToken() ?: return false
        return try {
            val res = deezerApiService.addTrackToFavorites(token, trackId)
            res.isSuccessful || res.body() == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun removeTrackFromFavorites(trackId: Long): Boolean {
        val token = getRawToken() ?: return false
        return try {
            val res = deezerApiService.removeTrackFromFavorites(token, trackId)
            res.isSuccessful || res.body() == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun getDiscoveryMix(): DeezerPlaylistDetailResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.getDiscoveryMix(auth)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun searchArtist(query: String): DeezerGenericSearchResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.searchArtist(query, auth)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun searchTrack(query: String): DeezerGenericSearchResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.searchTrack(query, auth)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun searchPlaylist(query: String): DeezerGenericSearchResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.searchPlaylist(query, auth)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun searchAlbum(query: String): DeezerGenericSearchResponseObject? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.searchAlbum(query, auth)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun searchBestResult(query: String): com.google.gson.JsonObject? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.searchBestResult(query, auth)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getAllLovedTracks(): List<com.andymitch.dzr.data.network.deezer.DeezerTrack> {
        val auth = getAuthHeader() ?: return emptyList()
        val userId = getUserId() ?: return emptyList()
        return try {
            deezerApiService.getAllGcastLovedTracks(userId, auth).data?.included.orEmpty()
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "getAllLovedTracks error: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getLovedTrackDates(): Map<Long, Long> {
        val token = getRawToken() ?: return emptyMap()
        return try {
            deezerApiService.getLovedTrackDates(token).getAsJsonArray("data")?.associate { el ->
                val o = el.asJsonObject
                o.get("id").asLong to o.get("time_add").asLong * 1000L
            }.orEmpty()
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "getLovedTrackDates error: ${e.message}", e)
            emptyMap()
        }
    }

    suspend fun getTrackMix(trackId: Long): List<com.andymitch.dzr.data.network.deezer.DeezerTrack> {
        val auth = getAuthHeader() ?: return emptyList()
        return try {
            deezerApiService.getTrackMix(trackId, auth).data?.included.orEmpty()
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "getTrackMix error: ${e.message}", e)
            emptyList()
        }
    }

    private fun classicTracks(json: com.google.gson.JsonObject): List<com.andymitch.dzr.data.network.deezer.DeezerTrack> =
        json.getAsJsonArray("data")?.map { el ->
            val o = el.asJsonObject
            val artist = o.getAsJsonObject("artist")
            val album = o.getAsJsonObject("album")
            com.andymitch.dzr.data.network.deezer.DeezerTrack(
                id = o.get("id").asString,
                type = "track",
                attributes = com.andymitch.dzr.data.network.deezer.DeezerTrackAttributes(
                    title = o.get("title")?.asString,
                    artistName = artist?.get("name")?.asString,
                    artistId = artist?.get("id")?.asLong,
                    albumName = album?.get("title")?.asString,
                    albumId = album?.get("id")?.asLong,
                    duration = o.get("duration")?.asInt ?: 0,
                    image = DeezerImage(
                        small = album?.get("cover_small")?.asString,
                        medium = album?.get("cover_medium")?.asString,
                        large = album?.get("cover_big")?.asString,
                        full = album?.get("cover_xl")?.asString
                    ),
                    explicitLyrics = o.get("explicit_lyrics")?.asBoolean
                )
            )
        }.orEmpty()

    suspend fun getArtistRadio(artistId: Long): List<com.andymitch.dzr.data.network.deezer.DeezerTrack> {
        val token = getRawToken() ?: return emptyList()
        return runCatching { classicTracks(deezerApiService.getArtistRadio(artistId, token)) }.getOrDefault(emptyList())
    }

    data class Release(val id: String, val title: String, val artist: String, val cover: String?)

    suspend fun getTopTracks(): List<com.andymitch.dzr.data.network.deezer.DeezerTrack> {
        val token = getRawToken() ?: return emptyList()
        return runCatching { classicTracks(deezerApiService.getTopTracks(token)) }.getOrDefault(emptyList())
    }

    suspend fun getRecommendedReleases(): List<Release> {
        val token = getRawToken() ?: return emptyList()
        return runCatching {
            deezerApiService.getRecommendedReleases(token).getAsJsonArray("data").map { el ->
                val o = el.asJsonObject
                Release(
                    id = o.get("id").asString,
                    title = o.get("title")?.asString.orEmpty(),
                    artist = o.getAsJsonObject("artist")?.get("name")?.asString.orEmpty(),
                    cover = o.get("cover_xl")?.asString ?: o.get("cover_big")?.asString
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getAlbumTracks(release: Release): List<com.andymitch.dzr.data.network.deezer.DeezerTrack> {
        val token = getRawToken() ?: return emptyList()
        return runCatching {
            classicTracks(deezerApiService.getAlbumTracks(release.id, token)).map { track ->
                track.copy(attributes = track.attributes?.copy(
                    albumName = release.title,
                    albumId = release.id.toLongOrNull(),
                    image = DeezerImage(medium = release.cover, large = release.cover)
                ))
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getHistory(): List<com.andymitch.dzr.data.network.deezer.DeezerTrack> {
        val token = getRawToken() ?: return emptyList()
        return runCatching { classicTracks(deezerApiService.getHistory(token)) }.getOrDefault(emptyList())
    }

    suspend fun createPlaylist(title: String, trackIds: List<String>): String? {
        val token = getRawToken() ?: return null
        return runCatching {
            val id = deezerApiService.createPlaylist(token, title).get("id").asString
            if (trackIds.isNotEmpty()) deezerApiService.addPlaylistTracks(id, token, trackIds.joinToString(","))
            id
        }.getOrNull()
    }

    suspend fun addPlaylistTracks(playlistId: String, trackIds: List<String>): Boolean {
        val token = getRawToken() ?: return false
        return runCatching { deezerApiService.addPlaylistTracks(playlistId, token, trackIds.joinToString(",")).isSuccessful }.getOrDefault(false)
    }

    suspend fun removePlaylistTracks(playlistId: String, trackIds: List<String>): Boolean {
        val token = getRawToken() ?: return false
        return runCatching { deezerApiService.removePlaylistTracks(playlistId, token, trackIds.joinToString(",")).isSuccessful }.getOrDefault(false)
    }

    suspend fun renamePlaylist(playlistId: String, title: String): Boolean {
        val token = getRawToken() ?: return false
        return runCatching { deezerApiService.renamePlaylist(playlistId, token, title).isSuccessful }.getOrDefault(false)
    }

    suspend fun deletePlaylist(playlistId: String): Boolean {
        val token = getRawToken() ?: return false
        return runCatching { deezerApiService.deletePlaylist(playlistId, token).let { it.isSuccessful && it.body()?.isJsonPrimitive == true } }.getOrDefault(false)
    }

    suspend fun getRecentlyPlayed(): List<Pair<String, DeezerPlaylist>> {
        val auth = getAuthHeader() ?: return emptyList()
        val userId = getUserId() ?: return emptyList()
        return try {
            val lovedId = lovedPlaylistId()
            val included = deezerApiService.getRecentlyPlayed(userId, auth)
                .getAsJsonObject("data")?.getAsJsonArray("included") ?: return emptyList()
            included.mapNotNull { el ->
                val o = el.asJsonObject
                val type = o.get("type")?.asString ?: return@mapNotNull null
                val a = o.getAsJsonObject("attributes") ?: return@mapNotNull null
                fun str(k: String) = a.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                val title = when (type) {
                    "album" -> str("albumName")
                    "artist" -> str("artistName")
                    else -> str("title") ?: str("name")
                } ?: return@mapNotNull null
                val image = a.getAsJsonObject("image")?.let { com.google.gson.Gson().fromJson(it, DeezerImage::class.java) }
                val id = o.get("id").asString
                type to DeezerPlaylist(id = id, attributes = DeezerPlaylistAttributes(title = if (id == lovedId) "Liked songs" else title, image = image))
            }
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "getRecentlyPlayed error: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getRecommendedPlaylists(): DeezerRecommendedPlaylistsResponse? {
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return try {
            val res = deezerApiService.getRecommendedPlaylists(userId, auth)
            android.util.Log.d("DeezerRepository", "getRecommendedPlaylists size: ${res.data?.included?.size}")
            res
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "getRecommendedPlaylists error: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

    suspend fun getUserPlaylists(): DeezerRecommendedPlaylistsResponse? {
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return try {
            val res = deezerApiService.getUserPlaylists(userId, auth)
            android.util.Log.d("DeezerRepository", "getUserPlaylists size: ${res.data?.included?.size}")
            res
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "getUserPlaylists error: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

    suspend fun getGcastLovedTracks(page: Int = 1, limit: Int = 50): DeezerPlaylistDetailResponse? {
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return try {
            val res = deezerApiService.getGcastLovedTracks(userId, auth, page, limit)
            android.util.Log.d("DeezerRepository", "getGcastLovedTracks size: ${res.data?.included?.size}")
            res
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "getGcastLovedTracks error: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }

    suspend fun likeTrack(trackId: Long): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            val res = deezerApiService.likeTrack(trackId, auth)
            res.isSuccessful
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "likeTrack error: ${e.message}", e)
            false
        }
    }

    suspend fun unlikeTrack(trackId: Long): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            val res = deezerApiService.unlikeTrack(trackId, auth)
            res.isSuccessful
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "unlikeTrack error: ${e.message}", e)
            false
        }
    }

    suspend fun banTrack(trackId: Long): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            deezerApiService.banTrack(trackId, auth).isSuccessful
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "banTrack error: ${e.message}", e)
            false
        }
    }

    suspend fun getStreamUrls(trackId: Long): DeezerStreamUrlsResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            val res = deezerApiService.getStreamUrls(trackId, auth)
            res
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getLyrics(trackId: String): com.andymitch.dzr.data.network.deezer.DeezerLyricsResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.getLyrics(trackId, auth)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getUserMe(): Result<com.andymitch.dzr.data.network.deezer.DeezerUserMeResponse> {
        val token = getRawToken() ?: return Result.failure(Exception("No access token found"))
        return try {
            val response = deezerApiService.getUserMe(token)
            if (response.name == null) {
                Result.failure(Exception("Name is null in response. This may mean the token is invalid or expired."))
            } else {
                Result.success(response)
            }
        } catch (e: Exception) {
            android.util.Log.e("DeezerRepository", "getUserMe error: ${e.message}", e)
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun likePlaylist(playlistId: String): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            val res = deezerApiService.likePlaylist(playlistId, auth)
            res.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun unlikePlaylist(playlistId: String): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            val res = deezerApiService.unlikePlaylist(playlistId, auth)
            res.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun getTrackInfo(trackId: String): DeezerTrackInfoResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            val response = deezerApiService.getTrackInfo(trackId, auth)
            if (response.isSuccessful) {
                response.body()
            } else {
                timber.log.Timber.e("Error fetching track info: ${response.code()} ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception fetching track info")
            null
        }
    }

    suspend fun getAlbumInfo(albumId: Long): com.andymitch.dzr.data.network.deezer.DeezerAlbumMetadataResponse? {
        val auth = getAuthHeader()

        if (auth == null) return null
        return try {
            val response = deezerApiService.getAlbumInfo(albumId, auth)

            response
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception fetching album info for albumId=$albumId")
            null
        }
    }

    suspend fun likeAlbum(albumId: Long): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            val response = deezerApiService.likeAlbum(albumId, auth)
            response.isSuccessful
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception liking album")
            false
        }
    }

    suspend fun unlikeAlbum(albumId: Long): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            val response = deezerApiService.unlikeAlbum(albumId, auth)
            response.isSuccessful
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception unliking album")
            false
        }
    }

    suspend fun getGcastLovedAlbums(start: Int = 0, limit: Int = 50): com.andymitch.dzr.data.network.deezer.DeezerLovedAlbumsResponse? {
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return try {
            deezerApiService.getGcastLovedAlbums(userId, auth, start, limit)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception fetching loved albums")
            null
        }
    }

    // --- Artist Methods ---

    suspend fun getArtistInfo(artistId: Long): com.andymitch.dzr.data.network.deezer.DeezerArtist? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.getArtistInfo(artistId, auth)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception fetching artist info for artistId=$artistId")
            null
        }
    }

    suspend fun getArtistAlbums(artistId: Long): com.andymitch.dzr.data.network.deezer.DeezerLovedAlbumsResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.getArtistAlbums(artistId, auth)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception fetching artist albums for artistId=$artistId")
            null
        }
    }

    suspend fun getSimilarArtists(artistId: Long): com.andymitch.dzr.data.network.deezer.DeezerArtistsListResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.getSimilarArtists(artistId, auth)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception fetching similar artists for artistId=$artistId")
            null
        }
    }

    suspend fun getArtistTopTracks(artistId: Long): com.andymitch.dzr.data.network.deezer.DeezerPlaylistDetailResponse? {
        val auth = getAuthHeader() ?: return null
        return try {
            deezerApiService.getArtistTopTracks(artistId, auth)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception fetching artist top tracks for artistId=$artistId")
            null
        }
    }

    suspend fun likeArtist(artistId: Long): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            val response = deezerApiService.likeArtist(artistId, auth)
            response.isSuccessful
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception liking artist")
            false
        }
    }

    suspend fun unlikeArtist(artistId: Long): Boolean {
        val auth = getAuthHeader() ?: return false
        return try {
            val response = deezerApiService.unlikeArtist(artistId, auth)
            response.isSuccessful
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception unliking artist")
            false
        }
    }

    suspend fun getGcastLovedArtists(): com.andymitch.dzr.data.network.deezer.DeezerArtistsListResponse? {
        val auth = getAuthHeader() ?: return null
        val userId = getUserId() ?: return null
        return try {
            deezerApiService.getGcastLovedArtists(userId, auth)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Exception fetching loved artists")
            null
        }
    }
}
