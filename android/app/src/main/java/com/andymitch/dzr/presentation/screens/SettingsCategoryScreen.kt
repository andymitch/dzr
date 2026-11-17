package com.andymitch.dzr.presentation.screens

import com.andymitch.dzr.data.preferences.CollagePattern
import com.andymitch.dzr.data.model.Song
import com.andymitch.dzr.presentation.model.SettingsCategory
import com.andymitch.dzr.presentation.viewmodel.LyricsRefreshProgress
import com.andymitch.dzr.presentation.viewmodel.SettingsViewModel

import com.andymitch.dzr.presentation.navigation.navigateSafely

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.andymitch.dzr.R

import com.andymitch.dzr.data.preferences.AppThemeMode
import com.andymitch.dzr.data.preferences.CarouselStyle
import com.andymitch.dzr.data.preferences.LaunchTab
import com.andymitch.dzr.data.preferences.LibraryNavigationMode
import com.andymitch.dzr.data.preferences.NavBarStyle
import com.andymitch.dzr.data.preferences.ThemePreference
import com.andymitch.dzr.data.model.LyricsSourcePreference
import com.andymitch.dzr.presentation.components.CollapsibleCommonTopBar
import com.andymitch.dzr.presentation.components.ExpressiveTopBarContent
import com.andymitch.dzr.presentation.components.MiniPlayerHeight
import com.andymitch.dzr.presentation.navigation.Screen
import com.andymitch.dzr.presentation.viewmodel.PlayerViewModel
import com.andymitch.dzr.ui.theme.RoundedSans

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsCategoryScreen(
    categoryId: String,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    statsViewModel: com.andymitch.dzr.presentation.viewmodel.StatsViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val category = SettingsCategory.fromId(categoryId) ?: return
    
    // State Collection (Duplicated from SettingsScreen for now to ensure functionality)
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val playbackSpeed by settingsViewModel.playbackSpeed.collectAsStateWithLifecycle()


    // Local State








    // TopBar Animations (identical to SettingsScreen)
    // TopBar Animations (identical to SettingsScreen)
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    
    val categoryTitle = stringResource(category.titleRes)
    val isLongTitle = categoryTitle.length > 13
    
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = if (isLongTitle) 200.dp else 180.dp //for 2 lines use 220 and make text use \n

    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
    
    val titleMaxLines = if (isLongTitle) 2 else 1

    val topBarHeight = remember(maxTopBarHeightPx) { Animatable(maxTopBarHeightPx) }
    var collapseFraction by remember { mutableStateOf(0f) }

    LaunchedEffect(topBarHeight.value, maxTopBarHeightPx) {
        collapseFraction =
                1f -
                        ((topBarHeight.value - minTopBarHeightPx) /
                                        (maxTopBarHeightPx - minTopBarHeightPx))
                                .coerceIn(0f, 1f)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0

                if (!isScrollingDown &&
                                (lazyListState.firstVisibleItemIndex > 0 ||
                                        lazyListState.firstVisibleItemScrollOffset > 0)
                ) {
                    return Offset.Zero
                }

                val previousHeight = topBarHeight.value
                val newHeight =
                        (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight

                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch { topBarHeight.snapTo(newHeight) }
                }

                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand =
                    lazyListState.firstVisibleItemIndex == 0 &&
                            lazyListState.firstVisibleItemScrollOffset == 0

            val targetValue =
                    if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx

            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }

    Box(
        modifier =
            Modifier.nestedScroll(nestedScrollConnection).fillMaxSize()
    ) {
        val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }
        
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = currentTopBarHeightDp + 8.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            )
        ) {
            item {
               // Use a simple Column for now, or ExpressiveSettingsGroup if preferred strictly for items
               Column(
                    modifier = Modifier.background(Color.Transparent)
               ) {
                    when (category) {
                        SettingsCategory.APPEARANCE -> {
                            val useSmoothCorners by settingsViewModel.useSmoothCorners.collectAsStateWithLifecycle()
                            val appColor by settingsViewModel.appColor.collectAsStateWithLifecycle()
                            val pureBlack by settingsViewModel.pureBlack.collectAsStateWithLifecycle()

                            SettingsSubsection(title = stringResource(R.string.setcat_global_theme)) {
                                ThemeSelectorItem(
                                    label = stringResource(R.string.setcat_app_theme_label),
                                    description = stringResource(R.string.setcat_app_theme_desc),
                                    options = mapOf(
                                        AppThemeMode.LIGHT to stringResource(R.string.setcat_theme_light),
                                        AppThemeMode.DARK to stringResource(R.string.setcat_theme_dark),
                                        AppThemeMode.FOLLOW_SYSTEM to stringResource(R.string.setcat_theme_follow_system)
                                    ),
                                    selectedKey = uiState.appThemeMode,
                                    onSelectionChanged = { settingsViewModel.setAppThemeMode(it) },
                                    leadingIcon = { Icon(Icons.Outlined.LightMode, null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                ThemeSelectorItem(
                                    label = "App color",
                                    description = "Wallpaper colors, or one accent over black and white.",
                                    options = linkedMapOf(com.andymitch.dzr.data.preferences.AppColor.DYNAMIC to "Wallpaper") +
                                        com.andymitch.dzr.data.preferences.AppColor.presets.keys.associateWith { it.replaceFirstChar(Char::uppercase) },
                                    selectedKey = appColor,
                                    onSelectionChanged = settingsViewModel::setAppColor,
                                    leadingIcon = { Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                SwitchSettingItem(
                                    title = "Pure black",
                                    subtitle = "True black backgrounds in dark mode, for OLED screens.",
                                    checked = pureBlack,
                                    onCheckedChange = settingsViewModel::setPureBlack,
                                    leadingIcon = { Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_smooth_corners_title),
                                    subtitle = stringResource(R.string.setcat_smooth_corners_subtitle),
                                    checked = useSmoothCorners,
                                    onCheckedChange = settingsViewModel::setUseSmoothCorners,
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_rounded_corner_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                            }

                            SettingsSubsection(title = stringResource(R.string.setcat_now_playing)) {
                                ThemeSelectorItem(
                                    label = stringResource(R.string.setcat_player_theme_label),
                                    description = stringResource(R.string.setcat_player_theme_desc),
                                    options = mapOf(
                                        ThemePreference.ALBUM_ART to stringResource(R.string.setcat_player_theme_album_art),
                                        ThemePreference.DYNAMIC to stringResource(R.string.setcat_player_theme_dynamic)
                                    ),
                                    selectedKey = uiState.playerThemePreference,
                                    onSelectionChanged = { settingsViewModel.setPlayerThemePreference(it) },
                                    leadingIcon = { Icon(Icons.Outlined.PlayCircle, null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_show_player_file_info_title),
                                    subtitle = stringResource(R.string.setcat_show_player_file_info_subtitle),
                                    checked = uiState.showPlayerFileInfo,
                                    onCheckedChange = { settingsViewModel.setShowPlayerFileInfo(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_attach_file_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                SettingsItem(
                                    title = stringResource(R.string.setcat_album_art_palette_title),
                                    subtitle = stringResource(R.string.setcat_album_art_palette_subtitle, uiState.albumArtPaletteStyle.label),
                                    leadingIcon = { Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.secondary) },
                                    trailingIcon = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = { navController.navigateSafely(Screen.PaletteStyle.route) }
                                )
                                ThemeSelectorItem(
                                    label = stringResource(R.string.setcat_carousel_style_label),
                                    description = stringResource(R.string.setcat_carousel_style_desc),
                                    options = mapOf(
                                        CarouselStyle.NO_PEEK to stringResource(R.string.setcat_carousel_no_peek),
                                        CarouselStyle.ONE_PEEK to stringResource(R.string.setcat_carousel_one_peek)
                                    ),
                                    selectedKey = uiState.carouselStyle,
                                    onSelectionChanged = { settingsViewModel.setCarouselStyle(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_view_carousel_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                            }

                            SettingsSubsection(title = stringResource(R.string.setcat_navigation_bar)) {
                                ThemeSelectorItem(
                                    label = stringResource(R.string.setcat_navbar_style_label),
                                    description = stringResource(R.string.setcat_navbar_style_desc),
                                    options = mapOf(
                                        NavBarStyle.DEFAULT to stringResource(R.string.setcat_navbar_style_default),
                                        NavBarStyle.FULL_WIDTH to stringResource(R.string.setcat_navbar_style_full_width),
                                        NavBarStyle.NONE to "No Nav Bar"
                                    ),
                                    selectedKey = uiState.navBarStyle,
                                    onSelectionChanged = { settingsViewModel.setNavBarStyle(it) },
                                    leadingIcon = { Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_compact_mode_title),
                                    subtitle = stringResource(R.string.setcat_compact_mode_subtitle),
                                    checked = uiState.navBarCompactMode,
                                    onCheckedChange = { settingsViewModel.setNavBarCompactMode(it) },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(R.drawable.rounded_view_week_24),
                                            null,
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                )
                                SettingsItem(
                                    title = stringResource(R.string.setcat_navbar_corner_title),
                                    subtitle = stringResource(R.string.setcat_navbar_corner_subtitle),
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_rounded_corner_24), null, tint = MaterialTheme.colorScheme.secondary) },
                                    trailingIcon = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = { navController.navigateSafely("nav_bar_corner_radius") }
                                )
                            }

                            SettingsSubsection(title = stringResource(R.string.setcat_lyrics_screen)) {
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_immersive_lyrics_title),
                                    subtitle = stringResource(R.string.setcat_immersive_lyrics_subtitle),
                                    checked = uiState.immersiveLyricsEnabled,
                                    onCheckedChange = { settingsViewModel.setImmersiveLyricsEnabled(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_lyrics_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )

                                if (uiState.immersiveLyricsEnabled) {
                                    ThemeSelectorItem(
                                        label = stringResource(R.string.setcat_auto_hide_delay_label),
                                        description = stringResource(R.string.setcat_auto_hide_delay_desc),
                                        options = mapOf(
                                            "3000" to stringResource(R.string.setcat_lyrics_delay_3s),
                                            "4000" to stringResource(R.string.setcat_lyrics_delay_4s),
                                            "5000" to stringResource(R.string.setcat_lyrics_delay_5s),
                                            "6000" to stringResource(R.string.setcat_lyrics_delay_6s)
                                        ),
                                        selectedKey = uiState.immersiveLyricsTimeout.toString(),
                                        onSelectionChanged = { settingsViewModel.setImmersiveLyricsTimeout(it.toLong()) },
                                        leadingIcon = { Icon(Icons.Rounded.Timer, null, tint = MaterialTheme.colorScheme.secondary) }
                                    )
                                }
                            }

                            SettingsSubsection(
                                title = stringResource(R.string.setcat_app_navigation_section),
                                addBottomSpace = false
                            ) {
                                ThemeSelectorItem(
                                    label = stringResource(R.string.setcat_default_tab_label),
                                    description = stringResource(R.string.setcat_default_tab_desc),
                                    options = mapOf(
                                        LaunchTab.HOME to stringResource(R.string.tab_home),
                                        LaunchTab.SEARCH to stringResource(R.string.search),
                                        LaunchTab.LIBRARY to stringResource(R.string.tab_library),
                                    ),
                                    selectedKey = uiState.launchTab,
                                    onSelectionChanged = { settingsViewModel.setLaunchTab(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.tab_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                ThemeSelectorItem(
                                    label = stringResource(R.string.setcat_library_navigation_label),
                                    description = stringResource(R.string.setcat_library_navigation_desc),
                                    options = mapOf(
                                        LibraryNavigationMode.TAB_ROW to stringResource(R.string.setcat_library_nav_tab_row),
                                        LibraryNavigationMode.COMPACT_PILL to stringResource(R.string.setcat_library_nav_compact_pill)
                                    ),
                                    selectedKey = uiState.libraryNavigationMode,
                                    onSelectionChanged = { settingsViewModel.setLibraryNavigationMode(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_library_music_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                            }
                        }
                        SettingsCategory.PLAYBACK -> {
                            SettingsSubsection(title = stringResource(R.string.setcat_background_playback)) {
                                ThemeSelectorItem(
                                    label = stringResource(R.string.setcat_keep_playing_label),
                                    description = stringResource(R.string.setcat_keep_playing_desc),
                                    options = mapOf("true" to stringResource(R.string.label_on), "false" to stringResource(R.string.label_off)),
                                    selectedKey = if (uiState.keepPlayingInBackground) "true" else "false",
                                    onSelectionChanged = { settingsViewModel.setKeepPlayingInBackground(it.toBoolean()) },
                                    leadingIcon = { Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                            }

                            SettingsSubsection(title = "Deezer Streaming") {
                                ThemeSelectorItem(
                                    label = "Audio Quality",
                                    description = "Select the default streaming quality for Deezer flows and songs.",
                                    options = com.andymitch.dzr.data.preferences.DeezerAudioQuality.entries.associate { it.name to it.label },
                                    selectedKey = uiState.deezerAudioQuality.name,
                                    onSelectionChanged = { settingsViewModel.setDeezerAudioQuality(com.andymitch.dzr.data.preferences.DeezerAudioQuality.valueOf(it)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.outline_high_quality_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                            }

                            DownloadsSettings()

                            SettingsSubsection(title = stringResource(R.string.setcat_replaygain_section)) {
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_replaygain_enable_title),
                                    subtitle = stringResource(R.string.setcat_replaygain_enable_subtitle),
                                    checked = uiState.replayGainEnabled,
                                    onCheckedChange = { settingsViewModel.setReplayGainEnabled(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_volume_down_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                AnimatedVisibility(
                                    visible = uiState.replayGainEnabled,
                                    enter = expandVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeIn(animationSpec = spring(stiffness = 400f)),
                                    exit = shrinkVertically(animationSpec = spring(stiffness = 500f)) + fadeOut(animationSpec = spring(stiffness = 500f))
                                ) {
                                    ThemeSelectorItem(
                                        label = stringResource(R.string.setcat_gain_mode_label),
                                        description = stringResource(R.string.setcat_gain_mode_desc),
                                        options = mapOf("track" to stringResource(R.string.setcat_gain_mode_track), "album" to stringResource(R.string.setcat_gain_mode_album)),
                                        selectedKey = if (uiState.replayGainUseAlbumGain) "album" else "track",
                                        onSelectionChanged = { settingsViewModel.setReplayGainUseAlbumGain(it == "album") },
                                        leadingIcon = { Icon(painterResource(R.drawable.rounded_volume_down_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                    )
                                }
                            }

                            SettingsSubsection(title = stringResource(R.string.setcat_headphones)) {
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_headphones_resume_title),
                                    subtitle = stringResource(R.string.setcat_headphones_resume_subtitle),
                                    checked = uiState.resumeOnHeadsetReconnect,
                                    onCheckedChange = { settingsViewModel.setResumeOnHeadsetReconnect(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_headphones_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                            }

                            SettingsSubsection(title = stringResource(R.string.setcat_queue_transitions)) {
                                ThemeSelectorItem(
                                    label = stringResource(R.string.setcat_crossfade_label),
                                    description = stringResource(R.string.setcat_crossfade_desc),
                                    options = mapOf("true" to stringResource(R.string.label_enabled), "false" to stringResource(R.string.label_disabled)),
                                    selectedKey = if (uiState.isCrossfadeEnabled) "true" else "false",
                                    onSelectionChanged = { settingsViewModel.setCrossfadeEnabled(it.toBoolean()) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_align_justify_space_even_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                if (uiState.isCrossfadeEnabled) {
                                    SliderSettingsItem(
                                        label = stringResource(R.string.setcat_crossfade_duration),
                                        value = uiState.crossfadeDuration.toFloat(),
                                        valueRange = 1000f..12000f,
                                        steps= 10,
                                        onValueChange = { settingsViewModel.setCrossfadeDuration(it.toInt()) },
                                        valueText = { value -> "${(value / 1000).toInt()}s" }
                                    )
                                }
                                SliderSettingsItem(
                                    label = stringResource(R.string.setcat_playback_speed),
                                    value = playbackSpeed,
                                    valueRange = 0.5f..2.0f,
                                    steps = 5,
                                    onValueChange = { settingsViewModel.setPlaybackSpeed(it) },
                                    valueText = { value -> String.format(Locale.US, "%.2f×", value) }
                                )
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_hifi_mode_title),
                                    subtitle = if (uiState.hiFiModeDeviceSupported)
                                        stringResource(R.string.setcat_hifi_mode_subtitle_supported)
                                    else
                                        stringResource(R.string.setcat_hifi_mode_subtitle_unsupported),
                                    checked = uiState.hiFiModeEnabled,
                                    onCheckedChange = { settingsViewModel.setHiFiModeEnabled(it) },
                                    enabled = uiState.hiFiModeDeviceSupported,
                                    leadingIcon = { Icon(painterResource(R.drawable.outline_high_quality_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_persistent_shuffle_title),
                                    subtitle = stringResource(R.string.setcat_persistent_shuffle_subtitle),
                                    checked = uiState.persistentShuffleEnabled,
                                    onCheckedChange = { settingsViewModel.setPersistentShuffleEnabled(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_shuffle_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_show_queue_history_title),
                                    subtitle = stringResource(R.string.setcat_show_queue_history_subtitle),
                                    checked = uiState.showQueueHistory,
                                    onCheckedChange = { settingsViewModel.setShowQueueHistory(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_queue_music_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                            }

                        }
                        SettingsCategory.BEHAVIOR -> {
                            SettingsSubsection(
                                title = stringResource(R.string.setcat_player_gestures)
                            ) {
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_tap_bg_closes_title),
                                    subtitle = stringResource(R.string.setcat_tap_bg_closes_subtitle),
                                    checked = uiState.tapBackgroundClosesPlayer,
                                    onCheckedChange = { settingsViewModel.setTapBackgroundClosesPlayer(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_touch_app_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                            }
                            SettingsSubsection(
                                title = stringResource(R.string.setcat_haptics),
                                addBottomSpace = false
                            ) {
                                SwitchSettingItem(
                                    title = stringResource(R.string.setcat_haptic_feedback_title),
                                    subtitle = stringResource(R.string.setcat_haptic_feedback_subtitle),
                                    checked = uiState.hapticsEnabled,
                                    onCheckedChange = { settingsViewModel.setHapticsEnabled(it) },
                                    leadingIcon = { Icon(painterResource(R.drawable.rounded_touch_app_24), null, tint = MaterialTheme.colorScheme.secondary) }
                                )
                            }
                        }
                        SettingsCategory.DEVELOPER -> {
                            SettingsSubsection(title = stringResource(R.string.setcat_experiments)) {
                                SettingsItem(
                                    title = stringResource(R.string.setcat_experimental_title),
                                    subtitle = stringResource(R.string.setcat_experimental_subtitle),
                                    leadingIcon = { Icon(Icons.Rounded.Science, null, tint = MaterialTheme.colorScheme.secondary) },
                                    trailingIcon = { Icon(Icons.Rounded.ChevronRight, stringResource(R.string.cd_open), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = { navController.navigateSafely(Screen.Experimental.route) }
                                )
                                SettingsItem(
                                    title = stringResource(R.string.setcat_test_setup_title),
                                    subtitle = stringResource(R.string.setcat_test_setup_subtitle),
                                    leadingIcon = { Icon(Icons.Rounded.Science, null, tint = MaterialTheme.colorScheme.tertiary) },
                                    onClick = {
                                        settingsViewModel.resetSetupFlow()
                                    }
                                )
                            }



                            SettingsSubsection(
                                title = stringResource(R.string.setcat_diagnostics),
                                addBottomSpace = false
                            ) {
                                SettingsItem(
                                    title = stringResource(R.string.setcat_trigger_crash_title),
                                    subtitle = stringResource(R.string.setcat_trigger_crash_subtitle),
                                    leadingIcon = { Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { settingsViewModel.triggerTestCrash() }
                                )
                            }
                        }
                        SettingsCategory.ABOUT -> {
                            SettingsSubsection(
                                title = stringResource(R.string.setcat_application),
                                addBottomSpace = false
                            ) {
                                SettingsItem(
                                    title = stringResource(R.string.setcat_about_title),
                                    subtitle = stringResource(R.string.setcat_about_subtitle),
                                    leadingIcon = { Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.secondary) },
                                    trailingIcon = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = { navController.navigateSafely("about") }
                                )
                            }
                        }
                        SettingsCategory.EQUALIZER -> {
                             // Equalizer has its own screen, so this block is unreachable via standard navigation
                             // but required for exhaustiveness.
                        }
                        SettingsCategory.DEVICE_CAPABILITIES -> {
                             // Device Capabilities has its own screen
                        }

                    }
               }
            }

            item {
                // Spacer handled by contentPadding
                Spacer(Modifier.height(1.dp))
            }
        }

        CollapsibleCommonTopBar(
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeightDp,
            onBackClick = onBackClick,
            title = categoryTitle,
            maxLines = titleMaxLines
        )

        // Block interaction during transition
        var isTransitioning by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(com.andymitch.dzr.presentation.navigation.TRANSITION_DURATION.toLong())
            isTransitioning = false
        }
        
        if (isTransitioning) {
            Box(modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                   awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                        }
                    }
                }
            )
        }
    }


    // Dialogs


    
     // Dialogs logic (copied)

    



}


@Composable
private fun SettingsSubsectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsSubsection(
    title: String,
    addBottomSpace: Boolean = true,
    content: @Composable () -> Unit
) {
    SettingsSubsectionHeader(title)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Transparent),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
    if (addBottomSpace) {
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun DownloadsSettings() {
    val viewModel: com.andymitch.dzr.presentation.viewmodel.DownloadsViewModel =
        hiltViewModel(androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity)
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val used = remember(index) { android.text.format.Formatter.formatShortFileSize(context, viewModel.bytesUsed()) }
    var confirm by remember { mutableStateOf(false) }
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val cacheAhead by viewModel.cacheAhead.collectAsStateWithLifecycle()
    var cacheUsed by remember { mutableStateOf(android.text.format.Formatter.formatShortFileSize(context, viewModel.cacheBytes())) }
    SettingsSubsection(title = "Downloads") {
        ThemeSelectorItem(
            label = "Download Quality",
            description = "Quality for songs you download to play offline.",
            options = com.andymitch.dzr.data.preferences.DeezerAudioQuality.entries.associate { it.name to it.label },
            selectedKey = quality.name,
            onSelectionChanged = { viewModel.setQuality(com.andymitch.dzr.data.preferences.DeezerAudioQuality.valueOf(it)) },
            leadingIcon = { Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.secondary) }
        )
        SwitchSettingItem(
            title = "Download only on Wi-Fi",
            subtitle = "Downloads wait for Wi-Fi and keep going with the app closed.",
            checked = wifiOnly,
            onCheckedChange = viewModel::setWifiOnly,
            leadingIcon = { Icon(Icons.Rounded.Wifi, null, tint = MaterialTheme.colorScheme.secondary) }
        )
        SliderSettingsItem(
            label = "Songs to cache ahead",
            value = cacheAhead.toFloat(),
            valueRange = com.andymitch.dzr.data.download.SongCache.MIN_AHEAD.toFloat()..com.andymitch.dzr.data.download.SongCache.MAX_AHEAD.toFloat(),
            steps = com.andymitch.dzr.data.download.SongCache.MAX_AHEAD - com.andymitch.dzr.data.download.SongCache.MIN_AHEAD - 1,
            onValueChange = { viewModel.setCacheAhead(it.toInt()) },
            valueText = { "${it.toInt()}" }
        )
        SettingsItem(
            title = "Clear cache",
            subtitle = "Songs cached for smooth playback, using $cacheUsed",
            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.secondary) },
            onClick = {
                viewModel.clearCache()
                cacheUsed = android.text.format.Formatter.formatShortFileSize(context, 0)
            }
        )
        SettingsItem(
            title = "Remove all downloads",
            subtitle = "Using $used",
            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.secondary) },
            onClick = { if (index.collections.isNotEmpty()) confirm = true }
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove all downloads?") },
            text = { Text("Downloaded songs will need a connection to play again.") },
            confirmButton = { TextButton(onClick = { confirm = false; viewModel.removeAll() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
