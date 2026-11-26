package com.andymitch.dzr.presentation.screens

import androidx.compose.foundation.basicMarquee
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.data.preferences.CollagePattern
import com.andymitch.dzr.presentation.viewmodel.SettingsViewModel
import com.andymitch.dzr.presentation.viewmodel.StatsViewModel

import com.andymitch.dzr.presentation.navigation.navigateSafely
import com.andymitch.dzr.presentation.navigation.navigateSafelyReplacing

import android.content.Intent
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andymitch.dzr.R
import com.andymitch.dzr.presentation.components.AlbumArtCollage
import com.andymitch.dzr.presentation.components.HomeGradientTopBar
import com.andymitch.dzr.presentation.components.MiniPlayerHeight
import com.andymitch.dzr.presentation.components.RecentlyPlayedSection
import com.andymitch.dzr.presentation.components.RecentlyPlayedSectionMinSongsToShow
import com.andymitch.dzr.presentation.components.SmartImage
import com.andymitch.dzr.presentation.components.StatsOverviewCard
import com.andymitch.dzr.presentation.components.resolveMainScreenBottomGradientHeight
import com.andymitch.dzr.presentation.model.collectRecentlyPlayedSongIds
import com.andymitch.dzr.presentation.model.mapRecentlyPlayedSongs
import com.andymitch.dzr.presentation.components.subcomps.PlayingEqIcon
import com.andymitch.dzr.presentation.navigation.Screen
import com.andymitch.dzr.presentation.viewmodel.PlayerViewModel
import com.andymitch.dzr.ui.theme.ExpTitleTypography
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import com.andymitch.dzr.data.network.deezer.DeezerTrack
import com.andymitch.dzr.data.network.deezer.DeezerPlaylist
import com.andymitch.dzr.presentation.screens.mapDeezerTrackToSong

private const val HomeLoadingPlaceholderMinDurationMillis = 1200L

// Modern HomeScreen with collapsible top bar and staggered grid layout
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    paddingValuesParent: PaddingValues,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    libraryViewModel: com.andymitch.dzr.presentation.viewmodel.LibraryViewModel = hiltViewModel(androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity),
    onOpenSidebar: () -> Unit
) {
    val context = LocalContext.current
    // DETECT BENCHMARK MODE
    val isBenchmarkMode = remember {
        (context as? android.app.Activity)?.intent?.getBooleanExtra("is_benchmark", false) ?: false
    }
    val statsViewModel: StatsViewModel = hiltViewModel()
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val flowConfigsState by libraryViewModel.deezerFlowConfigs.collectAsState()
    val playlists by libraryViewModel.deezerRecommendedPlaylists.collectAsState()
    val myTop by libraryViewModel.myTop.collectAsState()
    val newReleases by libraryViewModel.newReleases.collectAsState()
    val newReleasesMix by libraryViewModel.newReleasesMix.collectAsState()
    val yourDiscovery by libraryViewModel.yourDiscovery.collectAsState()
    val recentlyPlayed by libraryViewModel.recentlyPlayed.collectAsState()
    val songMixSeeds by libraryViewModel.songMixSeeds.collectAsState()
    val navBarStyle by playerViewModel.navBarStyle.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { libraryViewModel.refreshRecentlyPlayed() }
    
    val flowConfigs = flowConfigsState?.data?.included ?: emptyList()
    val recommendedPlaylists = playlists?.data?.included ?: emptyList()
    val coroutineScope = rememberCoroutineScope()
    val recentSongIds = remember(playbackHistory) {
        collectRecentlyPlayedSongIds(
            playbackHistory = playbackHistory,
            maxItems = 64
        )
    }
    val recentlyPlayedSourceSongsInitialValue = remember(recentSongIds) {
        if (recentSongIds.isEmpty()) persistentListOf<Song>() else null
    }
    val recentlyPlayedSourceSongs by remember(recentSongIds, playerViewModel) {
        playerViewModel.observeSongs(recentSongIds)
            .map<List<Song>, List<Song>?> { it }
    }.collectAsStateWithLifecycle(initialValue = recentlyPlayedSourceSongsInitialValue)
    val latestRecentlyPlayedSongs = remember(playbackHistory, recentlyPlayedSourceSongs) {
        val sourceSongs = recentlyPlayedSourceSongs ?: return@remember emptyList()
        mapRecentlyPlayedSongs(
            playbackHistory = playbackHistory,
            songs = sourceSongs,
            maxItems = 64
        )
    }
    // Keep the visible Home snapshot stable and only refresh it once the screen is off-screen.
    var recentlyPlayedSongs by rememberSaveable { mutableStateOf(latestRecentlyPlayedSongs) }
    val latestRecentlyPlayedSongsState = rememberUpdatedState(latestRecentlyPlayedSongs)

    LaunchedEffect(latestRecentlyPlayedSongs, lifecycleOwner) {
        val isHomeVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (recentlyPlayedSongs.isEmpty() || !isHomeVisible) {
            recentlyPlayedSongs = latestRecentlyPlayedSongs
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                recentlyPlayedSongs = latestRecentlyPlayedSongsState.value
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val recentlyPlayedQueue = remember(recentlyPlayedSongs) {
        recentlyPlayedSongs.map { it.song }.toImmutableList()
    }

    ReportDrawnWhen {
        songMixSeeds.isNotEmpty() || isBenchmarkMode
    }

    // 2) Observe only the currentSong (or null) to know whether to show padding
    val currentSong by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong }
    }.collectAsStateWithLifecycle(initialValue = null)

    // 3) Observe shuffle state for sync
    val isShuffleEnabled by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState
            .map { it.isShuffleEnabled }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // Bottom padding if there is a song playing
    val bottomPadding = if (currentSong != null) MiniPlayerHeight else 0.dp
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()

    var showStreamingProviderSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    LocalContext.current

    val homeStatsOverview by statsViewModel.homeOverview.collectAsStateWithLifecycle()

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val density = LocalDensity.current

    // Persist the scroll position across navigation away/back. The Stats card and other
    // conditional sections can shift indices while data re-emits when returning, which
    // would otherwise leave the list scrolled to the wrong place or jump to the top.
    var savedScrollIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    var needsScrollRestore by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, listState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                savedScrollIndex = listState.firstVisibleItemIndex
                savedScrollOffset = listState.firstVisibleItemScrollOffset
                needsScrollRestore = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        needsScrollRestore,
        songMixSeeds.isNotEmpty(),
        yourDiscovery != null,
        recentlyPlayedSongs.size,
        homeStatsOverview
    ) {
        if (!needsScrollRestore) return@LaunchedEffect
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems == 0) return@LaunchedEffect
        val targetIndex = savedScrollIndex.coerceIn(0, (totalItems - 1).coerceAtLeast(0))
        listState.scrollToItem(targetIndex, savedScrollOffset)
        needsScrollRestore = false
    }

    // Drawer state for sidebar
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(
                    top = 0.dp,
                    bottom = paddingValuesParent.calculateBottomPadding()
                            + 38.dp + bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item(key = "home_top_bar", contentType = "home_top_bar") {
                    HomeGradientTopBar(
                        onNavigationIconClick = {
                            navController.navigateSafely(Screen.Settings.route)
                        },
                        onStreamingClick = {
                              showStreamingProviderSheet = true
                        },
                        onMenuClick = {
                            // onOpenSidebar() // Disabled
                        },
                        isScrolled = false,
                    onSearchClick = if (navBarStyle == com.andymitch.dzr.data.preferences.NavBarStyle.NONE) {
                        { navController.navigateSafely(Screen.Search.route) }
                    } else null,
                    onLibraryClick = if (navBarStyle == com.andymitch.dzr.data.preferences.NavBarStyle.NONE) {
                        { navController.navigateSafely(Screen.Library.route) }
                    } else null
                    )
                }
                if (flowConfigs.isNotEmpty()) {
                    item(
                        key = "deezer_flow_header",
                        contentType = "deezer_flow_header"
                    ) {
                        Text(
                            text = "Flow",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    item(
                        key = "deezer_flow_list",
                        contentType = "deezer_flow_list"
                    ) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            itemsIndexed(flowConfigs, key = { index, _ -> "flow_$index" }) { _, config ->
                                DeezerHomeFlowConfigItem(config = config, onClick = {
                                    coroutineScope.launch {
                                        val flowTracksResponse = libraryViewModel.getMultiFlowTracks(config.links?.self ?: return@launch)
                                        val flowTracksList = flowTracksResponse?.data?.included?.filter { it.type == "track" } ?: emptyList()
                                        if (flowTracksList.isNotEmpty()) {
                                            val song = mapDeezerTrackToSong(flowTracksList.first())
                                            val songsToPlay = flowTracksList.map { mapDeezerTrackToSong(it) }
                                            playerViewModel.playSongs(songsToPlay, song, config.attributes?.title ?: "Flow", null, config.links?.self)
                                        }
                                    }
                                })
                            }
                        }
                    }
                }

                // Your Mix section always visible to avoid pop-in
                item(
                    key = "your_mix_header",
                    contentType = "your_mix_header"
                ) {
                    YourMixHeader(
                        song = "Today, we made for you",
                        isShuffleEnabled = isShuffleEnabled,
                        onPlayShuffled = {
                            val discoverySongs = yourDiscovery?.included?.filter { it.type == "track" }?.map { mapDeezerTrackToSong(it) }.orEmpty()
                            val choices = buildList<Triple<String, String?, suspend () -> List<Song>>> {
                                songMixSeeds.forEach { seed -> add(Triple("${seed.title} Mix", null) { libraryViewModel.trackMix(seed) }) }
                                if (discoverySongs.isNotEmpty()) add(Triple("Your Discovery", null) { discoverySongs })
                                listOfNotNull(myTop, newReleasesMix).forEach { mix -> add(Triple(mix.title, null) { mix.songs }) }
                                flowConfigs.forEach { config ->
                                    val url = config.links?.self ?: return@forEach
                                    add(Triple(config.attributes?.title ?: "Flow", url) {
                                        libraryViewModel.getMultiFlowTracks(url)?.data?.included.orEmpty()
                                            .filter { it.type == "track" }.map { mapDeezerTrackToSong(it) }
                                    })
                                }
                                recommendedPlaylists.forEach { playlist ->
                                    add(Triple(playlist.attributes?.title ?: playlist.attributes?.name ?: "Playlist", null) { libraryViewModel.playlistSongs(playlist.id) })
                                }
                                recentlyPlayed.filter { it.first == "playlist" }.forEach { (_, playlist) ->
                                    add(Triple(playlist.attributes?.title ?: playlist.attributes?.name ?: "Playlist", null) { libraryViewModel.playlistSongs(playlist.id) })
                                }
                            }
                            choices.randomOrNull()?.let { (name, flowUrl, load) ->
                                coroutineScope.launch {
                                    val songs = load()
                                    songs.firstOrNull()?.let { playerViewModel.playSongs(songs, it, queueName = name, flowUrl = flowUrl) }
                                }
                            }
                        }
                    )
                }
                if (songMixSeeds.isNotEmpty()) {
                    item(key = "song_mixes_header", contentType = "deezer_playlists_header") {
                        HomeSectionTitle("Mixes inspired by...")
                    }
                    item(key = "song_mixes", contentType = "song_mixes") {
                        androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(
                            rows = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.height(64.dp * 3 + 24.dp)
                        ) {
                            items(count = songMixSeeds.size, key = { "songmix_${songMixSeeds[it].id}" }) { index ->
                                val song = songMixSeeds[index]
                                SongMixRow(song = song, onClick = {
                                    coroutineScope.launch {
                                        val mix = libraryViewModel.trackMix(song)
                                        mix.firstOrNull()?.let {
                                            playerViewModel.playSongs(mix, it, queueName = "${song.title} Mix")
                                            playerViewModel.expandPlayerSheet()
                                        }
                                    }
                                })
                            }
                        }
                    }
                }

                val discoveryCovers = yourDiscovery?.included.orEmpty().mapNotNull { it.attributes?.image?.large ?: it.attributes?.image?.medium }.distinct().take(3)
                if (newReleases.isNotEmpty()) {
                    item(key = "new_releases_header", contentType = "deezer_playlists_header") {
                        HomeSectionTitle("New releases for you")
                    }
                    item(key = "new_releases", contentType = "deezer_playlists_list") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            newReleasesMix?.let { mix ->
                                item {
                                    HomeCard(subtitle = mix.featuring, onClick = { navController.navigateSafely(Screen.SmartTrackList.createRoute("new_releases")) }) {
                                        MixArt(label = "New releases", covers = mix.covers)
                                    }
                                }
                            }
                            items(newReleases, key = { "release_${it.id}" }) { release ->
                                HomeCard(title = release.title, subtitle = release.artist, onClick = { navController.navigateSafely(Screen.AlbumDetail.createRoute(release.id)) }) {
                                    AsyncImage(model = release.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }

                if (recentlyPlayed.isNotEmpty()) {
                    item(key = "recently_played_header", contentType = "deezer_playlists_header") {
                        Text(
                            text = "Recently Played",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    item(key = "recently_played_list", contentType = "deezer_playlists_list") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            itemsIndexed(recentlyPlayed, key = { index, _ -> "recent_$index" }) { _, (type, item) ->
                                DeezerHomePlaylistItem(playlist = item, onClick = {
                                    when (type) {
                                        "album" -> navController.navigateSafely(Screen.AlbumDetail.createRoute(item.id))
                                        "artist" -> navController.navigateSafely(Screen.ArtistDetail.createRoute(item.id))
                                        else -> navController.navigate(Screen.PlaylistDetail.createRoute("deezer_${item.id}"))
                                    }
                                })
                            }
                        }
                    }
                }

                if (recommendedPlaylists.isNotEmpty() || yourDiscovery != null || myTop != null) {
                    item(
                        key = "deezer_playlists_header",
                        contentType = "deezer_playlists_header"
                    ) {
                        Text(
                            text = "Recommended Playlists",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    item(
                        key = "deezer_playlists_list",
                        contentType = "deezer_playlists_list"
                    ) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            yourDiscovery?.let {
                                item {
                                    HomeCard(title = "Discovery", subtitle = null, size = 140.dp, onClick = { navController.navigateSafely(Screen.SmartTrackList.createRoute("discovery")) }) {
                                        MixArt(label = "Discovery", covers = discoveryCovers)
                                    }
                                }
                            }
                            myTop?.let { mix ->
                                item {
                                    HomeCard(title = mix.title, subtitle = null, size = 140.dp, onClick = { navController.navigateSafely(Screen.SmartTrackList.createRoute("my_top")) }) {
                                        MixArt(label = mix.title, covers = mix.covers)
                                    }
                                }
                            }
                            itemsIndexed(recommendedPlaylists, key = { index, _ -> "recommended_$index" }) { _, playlist ->
                                DeezerHomePlaylistItem(playlist = playlist, onClick = {
                                    navController.navigate(Screen.PlaylistDetail.createRoute("deezer_${playlist.id}"))
                                })
                            }
                        }
                    }
                }

                if (recentlyPlayedSongs.size >= RecentlyPlayedSectionMinSongsToShow) {
                    item(
                        key = "recently_played_section",
                        contentType = "recently_played_section"
                    ) {
                        RecentlyPlayedSection(
                            songs = recentlyPlayedSongs,
                            onSongClick = { song ->
                                if (recentlyPlayedQueue.isNotEmpty()) {
                                    playerViewModel.playSongs(
                                        songsToPlay = recentlyPlayedQueue,
                                        startSong = song,
                                        queueName = "Recently Played"
                                    )
                                }
                            },
                            onOpenAllClick = {
                                navController.navigateSafely(Screen.RecentlyPlayed.route)
                            },
                            themeStateHolder = playerViewModel.themeStateHolder,
                            currentSongId = currentSong?.id,
                            contentPadding = PaddingValues(start = 8.dp, end = 24.dp)
                        )
                    }
                }

                if (homeStatsOverview != null) {
                    item(
                        key = "listening_stats_preview",
                        contentType = "listening_stats_preview"
                    ) {
                        StatsOverviewCard(
                            summary = homeStatsOverview,
                            onClick = { navController.navigateSafely(Screen.Stats.route) }
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 32.dp)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to MaterialTheme.colorScheme.background,
                            0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                            1.0f to Color.Transparent
                        )
                    )
                )
        )
    }
}



@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YourMixHeader(
    song: String,
    isShuffleEnabled: Boolean = false,
    onPlayShuffled: () -> Unit
) {
    val buttonCorners = 68.dp
    val colors = MaterialTheme.colorScheme

    val titleStyle = rememberYourMixTitleStyle()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 16.dp, start = 12.dp)
        ) {
            // Your Mix Title
            Text(
                text = stringResource(R.string.home_your_mix_title),
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
            )

            // Artist/Song subtitle
            Text(
                text = song,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        // Play Button - color changes based on shuffle state
        androidx.compose.material3.LargeFloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp),
            onClick = onPlayShuffled,
            containerColor = if (isShuffleEnabled) colors.primary else colors.tertiaryContainer,
            contentColor = if (isShuffleEnabled) colors.onPrimary else colors.onTertiaryContainer,
            shape = AbsoluteSmoothCornerShape(
                cornerRadiusTL = buttonCorners,
                smoothnessAsPercentTR = 60,
                cornerRadiusBR = buttonCorners,
                smoothnessAsPercentTL = 60,
                cornerRadiusBL = buttonCorners,
                smoothnessAsPercentBR = 60,
                cornerRadiusTR = buttonCorners,
                smoothnessAsPercentBL = 60,
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_dice_24),
                contentDescription = "Play a random mix",
                modifier = Modifier.size(72.dp)
            )
        }
    }
}





@OptIn(ExperimentalTextApi::class)
@Composable
private fun rememberYourMixTitleStyle(): TextStyle {
    return remember {
        TextStyle(
            fontFamily = FontFamily(
                Font(
                    resId = R.font.gflex_variable,
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(636),
                        FontVariation.width(152f),
                        FontVariation.Setting("ROND", 50f),
                        FontVariation.Setting("XTRA", 520f),
                        FontVariation.Setting("YOPQ", 90f),
                        FontVariation.Setting("YTLC", 505f)
                    )
                )
            ),
            fontWeight = FontWeight(760),
            fontSize = 64.sp,
            lineHeight = 62.sp
        )
    }
}


@Composable
fun DeezerHomeFlowConfigItem(config: com.andymitch.dzr.data.network.deezer.DeezerMultiFlowConfig, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick)
    ) {
        val imageUrl = config.attributes?.images?.square?.medium ?: config.attributes?.images?.square?.small
        AsyncImage(
            model = imageUrl,
            contentDescription = "Flow Artwork",
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = config.attributes?.title ?: "Flow",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterHorizontally).basicMarquee()
        )
    }
}

@Composable
fun DeezerHomePlaylistItem(playlist: DeezerPlaylist, artOverlay: String? = null, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.size(140.dp).clip(AbsoluteSmoothCornerShape(16.dp, 60))) {
            AsyncImage(
                model = playlist.attributes?.image?.medium ?: playlist.attributes?.image?.small,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (artOverlay != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "MIX",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black.copy(alpha = 0.55f)
                    )
                    Text(
                        text = artOverlay,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.Black.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.attributes?.title ?: playlist.attributes?.name ?: "Unknown",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.basicMarquee()
        )
    }
}

@Composable
private fun HomeSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SongMixRow(song: Song, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .width(300.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.highResAlbumArtUriString ?: song.albumArtUriString,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp)
        )
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeCard(title: String? = null, subtitle: String?, size: Dp = 160.dp, onClick: () -> Unit, art: @Composable () -> Unit) {
    Column(modifier = Modifier.width(size).clickable(onClick = onClick)) {
        Box(modifier = Modifier.size(size).clip(RoundedCornerShape(16.dp))) { art() }
        Spacer(modifier = Modifier.height(8.dp))
        title?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee()) }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }
}

@Composable
private fun MixArt(label: String, covers: List<String>) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer)) {
        Text(
            text = label.uppercase(),
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2,
            lineHeight = 19.sp,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)
        )
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            covers.take(2).forEach { cover ->
                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}
