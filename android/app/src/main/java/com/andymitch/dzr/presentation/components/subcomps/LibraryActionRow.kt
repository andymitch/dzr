package com.andymitch.dzr.presentation.components.subcomps

import com.andymitch.dzr.data.model.MusicFolder
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andymitch.dzr.data.model.Lyrics
import com.andymitch.dzr.presentation.components.ToggleSegmentButton
import com.andymitch.dzr.presentation.components.player.BottomToggleRow
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.andymitch.dzr.ui.theme.LocalDzrDarkTheme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dataset
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andymitch.dzr.R
import com.andymitch.dzr.ui.theme.RoundedSans
import java.io.File
import androidx.compose.ui.res.stringResource

val defaultShape = RoundedCornerShape(26.dp) // Fallback shape
val genHeight = 42.dp

@Composable
fun LibraryActionRow(
    onMainActionClick: () -> Unit,
    iconRotation: Float,
    onSortClick: () -> Unit,
    onLocateClick: () -> Unit = {},
    showSortButton: Boolean,
    showLocateButton: Boolean = false,
    showImportButton: Boolean = true,
    isPlaylistTab: Boolean,
    onImportM3uClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Breadcrumb parameters
    isShuffleEnabled: Boolean = false,
    // Storage Filter
    showStorageFilterButton: Boolean = false,
    showMainActionButtons: Boolean = true,
    currentStorageFilter: com.andymitch.dzr.data.model.StorageFilter = com.andymitch.dzr.data.model.StorageFilter.ALL,
    onStorageFilterClick: () -> Unit = {},
    // Optional Play Action
    onPlayClick: (() -> Unit)? = null,
    // Custom Main Action
    customMainActionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    customMainActionText: String? = null
) {

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        horizontalArrangement = Arrangement.Absolute.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
                val outerCorner = 26.dp
                val innerCorner = 8.dp

                val newButtonEndCorner = outerCorner

                val hasPlayButton = onPlayClick != null && !isPlaylistTab
                val shuffleStartCorner by animateDpAsState(
                    targetValue = if (hasPlayButton) innerCorner else 26.dp,
                    label = "ShuffleStartCornerAnim"
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showMainActionButtons) {
                        // Determine button colors based on shuffle state (not for playlist tab)
                        val buttonContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                        val buttonContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        
                        AnimatedVisibility(
                            visible = hasPlayButton,
                            enter = slideInHorizontally { -it } + fadeIn() + expandHorizontally(),
                            exit = slideOutHorizontally { -it } + fadeOut() + shrinkHorizontally()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilledTonalButton(
                                    onClick = onPlayClick ?: {},
                                    shape = RoundedCornerShape(
                                        topStart = 26.dp, bottomStart = 26.dp,
                                        topEnd = innerCorner, bottomEnd = innerCorner
                                    ),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = buttonContainerColor,
                                        contentColor = buttonContentColor
                                    ),
                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 6.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                    modifier = Modifier.height(genHeight)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = stringResource(R.string.cd_play),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                        }

                        FilledTonalButton(
                            onClick = onMainActionClick,
                            shape = RoundedCornerShape(
                                topStart = shuffleStartCorner, bottomStart = shuffleStartCorner,
                                topEnd =  newButtonEndCorner, bottomEnd = newButtonEndCorner
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonContainerColor,
                                contentColor = buttonContentColor
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 6.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier.height(genHeight)
                        ) {
                            val icon = customMainActionIcon ?: if (isPlaylistTab) Icons.AutoMirrored.Rounded.PlaylistAdd else Icons.Rounded.Shuffle
                            val text = customMainActionText ?: if (isPlaylistTab) {
                                stringResource(R.string.library_action_new)
                            } else {
                                stringResource(R.string.shortcut_shuffle_short)
                            }
                            val contentDesc = customMainActionText ?: if (isPlaylistTab) {
                                stringResource(R.string.cd_create_new_playlist)
                            } else {
                                stringResource(R.string.cd_shuffle_play)
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = contentDesc,
                                    modifier = Modifier.size(20.dp).rotate(iconRotation)
                                )
                                Text(
                                    modifier = Modifier.animateContentSize(),
                                    text = text,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Import button removed per user request
                    }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (showSortButton) {
            val outerCorner = 26.dp
            
            // Logic for Sort Button (Rightmost)
            val sortStartCorner by animateDpAsState(
                targetValue = if (showLocateButton || showStorageFilterButton) 8.dp else outerCorner,
                label = "SortStartCorner"
            )

            // Logic for Filter Button (Middle or Left if Locate hidden)
            // Filter is visible if showStorageFilterButton is true
            val filterEndCorner = 8.dp // Connected to Sort
            val filterStartCorner by animateDpAsState(
                targetValue = if (showLocateButton) 8.dp else outerCorner,
                label = "FilterStartCorner"
            )
            
            // Logic for Locate Button (Leftmost)
            val locateEndCorner = 8.dp // Connected to Filer or Sort

            // Gaps
            // If Filter is shown, gap is between Filter and Sort? OR if we use connected buttons, gap is 4dp between groups or 0dp between connected?
            // Existing code used 4dp gap and 8dp corner. 
            // "SortButtonsGap" was 4dp if showLocateButton else 0dp.
            // If we want "connected" look (segmented), gap should be small (1dp or 2dp) or 0.
            // But existing code uses `4.dp`.
            
            val gapBetweenLocateAndNext by animateDpAsState(
                targetValue = if (showLocateButton) 4.dp else 0.dp,
                label = "GapLocate"
            )
            val gapBetweenFilterAndSort by animateDpAsState(
                targetValue = if (showStorageFilterButton) 4.dp else 0.dp,
                label = "GapFilter"
            )


            Row(verticalAlignment = Alignment.CenterVertically) {
                // Locate Button
                AnimatedVisibility(
                    visible = showLocateButton,
                    enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut()
                ) {
                    FilledTonalIconButton(
                        onClick = onLocateClick,
                        shape = RoundedCornerShape(
                            topStart = outerCorner,
                            bottomStart = outerCorner,
                            topEnd = locateEndCorner,
                            bottomEnd = locateEndCorner
                        ),
                        modifier = Modifier.size(genHeight)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MyLocation,
                            contentDescription = stringResource(R.string.cd_locate_current_song)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(gapBetweenLocateAndNext))

                // Storage Filter Button
                AnimatedVisibility(
                    visible = showStorageFilterButton,
                    enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut()
                ) {
                     val finalIcon = when(currentStorageFilter) {
                         com.andymitch.dzr.data.model.StorageFilter.ALL -> Icons.Rounded.Dataset
                         com.andymitch.dzr.data.model.StorageFilter.ONLINE -> Icons.Rounded.Cloud
                         com.andymitch.dzr.data.model.StorageFilter.OFFLINE -> Icons.Rounded.PhoneAndroid
                     }
                     val tooltipText = when(currentStorageFilter) {
                         com.andymitch.dzr.data.model.StorageFilter.ALL -> stringResource(R.string.library_storage_filter_all_songs)
                         com.andymitch.dzr.data.model.StorageFilter.ONLINE -> stringResource(R.string.library_storage_filter_online)
                         com.andymitch.dzr.data.model.StorageFilter.OFFLINE -> stringResource(R.string.library_storage_filter_offline)
                     }
                     val tooltipState = rememberTooltipState()

                    @OptIn(ExperimentalMaterial3Api::class)
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip {
                                Text(tooltipText)
                            }
                        },
                        state = tooltipState
                    ) {
                        FilledTonalIconButton(
                            onClick = onStorageFilterClick,
                            shape = RoundedCornerShape(
                                topStart = filterStartCorner,
                                bottomStart = filterStartCorner,
                                topEnd = filterEndCorner,
                                bottomEnd = filterEndCorner
                            ),
                            modifier = Modifier.size(genHeight)
                        ) {
                             Icon(
                                imageVector = finalIcon,
                                contentDescription = tooltipText
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(gapBetweenFilterAndSort))

                // Sort Button
                FilledTonalIconButton(
                    onClick = onSortClick,
                    shape = RoundedCornerShape(
                        topStart = sortStartCorner,
                        bottomStart = sortStartCorner,
                        topEnd = outerCorner,
                        bottomEnd = outerCorner
                    ),
                    modifier = Modifier.size(genHeight)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = stringResource(R.string.cd_sort_options),
                    )
                }
            }
        }
    }
}

