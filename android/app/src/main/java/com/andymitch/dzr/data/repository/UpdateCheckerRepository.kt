package com.andymitch.dzr.data.repository

import com.andymitch.dzr.BuildConfig
import com.andymitch.dzr.data.network.GitHubApiService
import com.andymitch.dzr.data.network.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateCheckerRepository @Inject constructor(
    private val apiService: GitHubApiService
) {
    suspend fun getLatestReleaseIfNewer(): GitHubRelease? = withContext(Dispatchers.IO) {
        val current = BuildConfig.VERSION_NAME
        if (current.endsWith("-dev")) return@withContext null
        val currentVersion = Version.parse(current) ?: return@withContext null
        try {
            apiService.getReleases()
                .filter { !it.draft && (currentVersion.beta != null || !it.prerelease) }
                .mapNotNull { release -> Version.parse(release.tagName)?.let { release to it } }
                .maxWithOrNull(compareBy { it.second })
                ?.takeIf { it.second > currentVersion }
                ?.first
        } catch (e: Exception) {
            null
        }
    }

    data class Version(val parts: List<Int>, val beta: Int?) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            parts.zip(other.parts).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
            return when {
                beta != null && other.beta != null -> beta.compareTo(other.beta)
                beta != null -> -1
                other.beta != null -> 1
                else -> 0
            }
        }

        companion object {
            fun parse(text: String): Version? {
                val split = text.removePrefix("v").split("-", limit = 2)
                val nums = split[0].split(".").mapNotNull { it.toIntOrNull() }
                if (nums.isEmpty()) return null
                val beta = split.getOrNull(1)?.let { it.substringAfterLast(".").toIntOrNull() ?: 0 }
                return Version(nums + List(maxOf(0, 3 - nums.size)) { 0 }, beta)
            }
        }
    }
}
