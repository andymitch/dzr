package com.andymitch.dzr.presentation.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

@Composable
fun ForegroundBackHandler(navController: NavHostController, enabled: Boolean) {
    var foregroundEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(navController) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        var wasStopped = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> wasStopped = true
                Lifecycle.Event.ON_START -> if (wasStopped) {
                    wasStopped = false
                    foregroundEntryId = navController.currentBackStackEntry?.id
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    key(foregroundEntryId) {
        BackHandler(
            enabled = enabled && currentEntry?.id == foregroundEntryId && currentEntry?.destination?.route != Screen.Home.route
        ) {
            navController.navigateUpToHome()
        }
    }
}
