package com.andymitch.dzr.presentation.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

private fun NavController.isReadyForNavigation(): Boolean {
    return runCatching {
        // We allow navigation if the current entry is at least STARTED.
        // This is safer than strictly RESUMED as transitions can sometimes delay RESUMED state.
        currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
    }.getOrDefault(false)
}

fun NavController.navigateSafely(route: String): Boolean {
    if (!isReadyForNavigation()) return false
    navigate(route) {
        launchSingleTop = true
    }
    return true
}

fun NavController.navigateSafely(
    route: String,
    builder: NavOptionsBuilder.() -> Unit
): Boolean {
    if (!isReadyForNavigation()) return false
    navigate(route) {
        launchSingleTop = true
        builder()
    }
    return true
}

const val REOPEN_PLAYER_KEY = "reopen_player"

fun NavController.navigateFromPlayer(route: String) {
    val from = currentBackStackEntry
    if (navigateSafely(route) && currentBackStackEntry !== from) {
        from?.savedStateHandle?.set(REOPEN_PLAYER_KEY, true)
    }
}

fun NavController.navigateSafelyReplacing(
    route: String,
    patternToPop: String,
    builder: NavOptionsBuilder.() -> Unit = {}
): Boolean {
    if (!isReadyForNavigation()) return false
    navigate(route) {
        launchSingleTop = false
        popUpTo(patternToPop) {
            inclusive = true
        }
        builder()
    }
    return true
}

private val settingsChildRoutes = setOf(
    Screen.Accounts.route,
    Screen.SettingsCategory.route,
    Screen.PaletteStyle.route,
    Screen.Experimental.route,
    Screen.NavBarCrRad.route,
    Screen.About.route,
    Screen.ArtistSettings.route,
    Screen.DelimiterConfig.route,
    Screen.WordDelimiterConfig.route,
    Screen.Equalizer.route,
    Screen.DeviceCapabilities.route
)

fun NavController.navigateBack() {
    if (previousBackStackEntry != null) popBackStack() else navigateUpToHome()
}

fun NavController.navigateUpToHome() {
    if (currentDestination?.route == Screen.Home.route) return
    if (currentDestination?.route in settingsChildRoutes) {
        navigate(Screen.Settings.route) {
            popUpTo(Screen.Home.route)
            launchSingleTop = true
        }
    } else if (!popBackStack(Screen.Home.route, inclusive = false)) {
        navigate(Screen.Home.route) {
            popUpTo(graph.id) { inclusive = true }
        }
    }
}
