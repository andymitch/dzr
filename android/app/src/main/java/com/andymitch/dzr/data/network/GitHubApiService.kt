package com.andymitch.dzr.data.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("body") val body: String? = null,
    @SerializedName("published_at") val publishedAt: String? = null
)

interface GitHubApiService {
    @GET("repos/andymitch/dzr/releases/latest")
    suspend fun getLatestRelease(): GitHubRelease

    @GET("repos/andymitch/dzr/releases")
    suspend fun getReleases(): List<GitHubRelease>
}
