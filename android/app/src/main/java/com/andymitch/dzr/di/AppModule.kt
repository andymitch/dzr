package com.andymitch.dzr.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkManager
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.andymitch.dzr.BuildConfig
import com.andymitch.dzr.DzrApplication
import com.andymitch.dzr.data.database.AlbumArtThemeDao
import com.andymitch.dzr.data.database.EngagementDao
import com.andymitch.dzr.data.database.FavoritesDao
import com.andymitch.dzr.data.database.LyricsDao
import com.andymitch.dzr.data.database.LocalPlaylistDao
import com.andymitch.dzr.data.database.MIGRATION_1_2
import com.andymitch.dzr.data.database.MIGRATION_3_4
import com.andymitch.dzr.data.database.MusicDao
import com.andymitch.dzr.data.database.DzrDatabase
import com.andymitch.dzr.data.database.SearchHistoryDao
import com.andymitch.dzr.data.database.TransitionDao
import com.andymitch.dzr.data.preferences.UserPreferencesRepository
import com.andymitch.dzr.data.preferences.PlaylistPreferencesRepository
import com.andymitch.dzr.data.preferences.dataStore
import com.andymitch.dzr.data.media.SongMetadataEditor
import com.andymitch.dzr.data.network.deezer.DeezerAuthApi
import com.andymitch.dzr.data.network.deezer.DeezerApiService
import com.andymitch.dzr.data.repository.DeezerRepository
import com.andymitch.dzr.data.network.lyrics.LrcLibApiService
import com.andymitch.dzr.data.repository.ArtistImageRepository
import com.andymitch.dzr.data.repository.LyricsRepository
import com.andymitch.dzr.data.repository.LyricsRepositoryImpl
import com.andymitch.dzr.data.repository.MediaStoreSongRepository
import com.andymitch.dzr.data.repository.MusicRepository
import com.andymitch.dzr.data.repository.MusicRepositoryImpl
import com.andymitch.dzr.data.repository.SongRepository
import com.andymitch.dzr.data.repository.TransitionRepository
import com.andymitch.dzr.data.repository.TransitionRepositoryImpl
import com.andymitch.dzr.data.repository.FolderTreeBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideApplication(@ApplicationContext app: Context): DzrApplication {
        return app as DzrApplication
    }

    @Singleton
    @Provides
    fun provideGson(): com.google.gson.Gson {
        return com.google.gson.Gson()
    }

    @OptIn(UnstableApi::class)
    @Singleton
    @Provides
    fun provideSessionToken(@ApplicationContext context: Context): androidx.media3.session.SessionToken {
        return androidx.media3.session.SessionToken(
            context,
            android.content.ComponentName(context, com.andymitch.dzr.data.service.MusicService::class.java)
        )
    }

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.dataStore

    @Singleton
    @Provides
    fun provideJson(): Json { // Provide Json
        return Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    @Singleton
    @Provides
    @AppScope
    fun provideAppCoroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Singleton
    @Provides
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Singleton
    @Provides
    fun provideDzrDatabase(@ApplicationContext context: Context): DzrDatabase {
        listOf("", "-wal", "-shm").forEach { suffix ->
            val legacy = context.getDatabasePath("pixelplayer_database$suffix")
            if (legacy.exists()) legacy.renameTo(context.getDatabasePath("dzr_database$suffix"))
        }
        val builder = Room.databaseBuilder(
            context.applicationContext,
            DzrDatabase::class.java,
            "dzr_database"
        )
            .addCallback(DzrDatabase.createRuntimeArtifactsCallback())
            .addMigrations(MIGRATION_1_2, MIGRATION_3_4)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

        // P2-4: Only allow destructive recreation in debug builds.
        // In release, an unexpected schema version (e.g. a database restored by
        // Platform Auto Backup from an older install) must crash the app
        // rather than silently wiping user data (playlists, favorites, statistics).
        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }

        return builder.build()
    }

    @Singleton
    @Provides
    fun provideAlbumArtThemeDao(database: DzrDatabase): AlbumArtThemeDao {
        return database.albumArtThemeDao()
    }

    @Singleton
    @Provides
    fun provideSearchHistoryDao(database: DzrDatabase): SearchHistoryDao { // NEW METHOD
        return database.searchHistoryDao()
    }

    @Singleton
    @Provides
    fun provideMusicDao(database: DzrDatabase): MusicDao { // Provide MusicDao
        return database.musicDao()
    }

    @Singleton
    @Provides
    fun provideTransitionDao(database: DzrDatabase): TransitionDao {
        return database.transitionDao()
    }

    @Singleton
    @Provides
    fun provideEngagementDao(database: DzrDatabase): EngagementDao {
        return database.engagementDao()
    }

    @Singleton
    @Provides
    fun provideFavoritesDao(database: DzrDatabase): FavoritesDao {
        return database.favoritesDao()
    }

    @Singleton
    @Provides
    fun provideLyricsDao(database: DzrDatabase): LyricsDao {
        return database.lyricsDao()
    }

    @Singleton
    @Provides
    fun provideLocalPlaylistDao(database: DzrDatabase): LocalPlaylistDao {
        return database.localPlaylistDao()
    }
    
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): ImageLoader {
        return ImageLoader.Builder(context)
            // Reuse the app's shared client so image loads share its connection pool
            // and dispatcher instead of spinning up a second, independent OkHttp stack.
            .okHttpClient(okHttpClient)
            .dispatcher(Dispatchers.Default) // Use CPU-bound dispatcher for decoding
            .allowHardware(true) // Re-enable hardware bitmaps for better performance
            .memoryCache {
                MemoryCache.Builder(context)
                    // Hard 40 MB cap instead of 20%-of-heap. Rationale:
                    //  - On large-heap devices (Pixel 8 etc.) the percentage
                    //    expanded to ~80–100 MB, far beyond what an album-art
                    //    workload needs.
                    //  - allowHardware(true) keeps most decoded pixels in GPU
                    //    memory, so the MemoryCache mostly tracks Bitmap
                    //    references — 40 MB still buffers ~100+ album arts.
                    //  - Tighter cap = less GC pressure and less thermal
                    //    headroom spent on memory pressure during long sessions.
                    .maxSizeBytes(40 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB disk cache
                    .build()
            }
            .respectCacheHeaders(false) // Ignore server cache headers, always cache
            .build()
    }


    @Provides
    @Singleton
    fun provideSongRepository(
        @ApplicationContext context: Context,
        mediaStoreObserver: com.andymitch.dzr.data.observer.MediaStoreObserver,
        favoritesDao: FavoritesDao,
        userPreferencesRepository: UserPreferencesRepository,
        musicDao: MusicDao
    ): SongRepository {
        return MediaStoreSongRepository(
            context = context,
            mediaStoreObserver = mediaStoreObserver,
            favoritesDao = favoritesDao,
            userPreferencesRepository = userPreferencesRepository,
            musicDao = musicDao
        )
    }

    @Provides
    @Singleton
    fun provideFolderTreeBuilder(): FolderTreeBuilder {
        return FolderTreeBuilder()
    }


    @Singleton
    @Provides
    fun provideSongMetadataEditor(
        @ApplicationContext context: Context
    ): SongMetadataEditor {
        return SongMetadataEditor(context)
    }

    /**
     * Provides a singleton OkHttpClient instance with logging and a User-Agent interceptor.
     * Retry logic with backoff is handled in coroutine-based callers.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // HEADERS (not BODY) so we never print response bodies that may contain
            // cookies, tokens, or third-party API payloads. Headers are still useful
            // for debugging request paths and status codes.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            // Redact every header that can carry a credential or session token.
            redactHeader("Authorization")
            redactHeader("Proxy-Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            redactHeader("X-Emby-Token")
            redactHeader("X-Emby-Authorization")
            redactHeader("X-MediaBrowser-Token")
        }
        
        // Connection pool with optimized connections for better performance
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Add User-Agent header (required by some APIs)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", "dzr/1.0 (Music Player)")
                    .build()
                chain.proceed(requestWithUserAgent)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provides an OkHttpClient instance with timeouts for lyrics searches.
     * Includes DNS resolver, modern TLS, connection pool, and connection retry.
     */
    @Provides
    @Singleton
    @FastOkHttpClient
    fun provideFastOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // Only log request/response headers in debug builds. In release this
            // would leak LRCLIB search URLs (which carry the query terms) to logcat.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        
        // Connection pool to reuse connections for better performance
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        // Use the platform resolver and fall back to manual JVM resolution if needed.
        val dns = okhttp3.Dns { hostname ->
            try {
                // First try system DNS
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                // Fallback to manual resolution if system DNS fails
                java.net.InetAddress.getAllByName(hostname).toList()
            }
        }

        return OkHttpClient.Builder()
            .dns(dns)
            .connectionPool(connectionPool)
            // Use HTTP/1.1 to avoid HTTP/2 stream issues with some servers
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            // Use modern TLS connection spec
            .connectionSpecs(listOf(
                okhttp3.ConnectionSpec.MODERN_TLS,
                okhttp3.ConnectionSpec.COMPATIBLE_TLS
            ))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            // Enable built-in retry on connection failure
            .retryOnConnectionFailure(true)
            // Add headers
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithHeaders = originalRequest.newBuilder()
                    .header("User-Agent", "dzr/1.0 (Music Player)")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(requestWithHeaders)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provides a singleton Retrofit instance for the LRCLIB API.
     */
    @Provides
    @Singleton
    fun provideRetrofit(@FastOkHttpClient okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Provides a singleton instance of the LRCLIB API service.
     */
    @Provides
    @Singleton
    fun provideLrcLibApiService(retrofit: Retrofit): LrcLibApiService {
        return retrofit.create(LrcLibApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideGitHubApiService(okHttpClient: OkHttpClient): com.andymitch.dzr.data.network.GitHubApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(com.andymitch.dzr.data.network.GitHubApiService::class.java)
    }

    /**
     * Provides a Retrofit instance for the Deezer API.
     */
    @Provides
    @Singleton
    @DeezerRetrofit
    fun provideDeezerRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.deezer.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Provides the Deezer API service.
     */
    @Provides
    @Singleton
    fun provideDeezerApiService(@DeezerRetrofit retrofit: Retrofit): DeezerApiService {
        return retrofit.create(DeezerApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideDeezerAuthApi(@DeezerRetrofit retrofit: Retrofit): DeezerAuthApi {
        return retrofit.create(DeezerAuthApi::class.java)
    }

    /**
     * Provides the artist image repository.
     */
    @Provides
    @Singleton
    fun provideArtistImageRepository(): ArtistImageRepository {
        return ArtistImageRepository()
    }
}
