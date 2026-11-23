package com.the412banner.batterylog.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.the412banner.batterylog.BatteryLogService
import com.the412banner.batterylog.ui.apps.AppDetailScreen
import com.the412banner.batterylog.ui.apps.AppsScreen
import com.the412banner.batterylog.ui.health.HealthScreen
import com.the412banner.batterylog.ui.history.HistoryScreen
import com.the412banner.batterylog.ui.now.NowActions
import com.the412banner.batterylog.ui.now.NowScreen
import com.the412banner.batterylog.ui.run.RunScreen
import com.the412banner.batterylog.ui.settings.SettingsScreen
import com.the412banner.batterylog.ui.settings.UpdatesScreen
import kotlinx.coroutines.flow.StateFlow

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Now("now", "الآن", Icons.Rounded.BatteryChargingFull),
    History("history", "السجل", Icons.Rounded.Insights),
    Apps("apps", "التطبيقات", Icons.Rounded.Apps),
    Health("health", "الصحة", Icons.Rounded.Favorite),
    Settings("settings", "الإعدادات", Icons.Rounded.Settings),
}

@Composable
fun AppRoot(pendingRoute: StateFlow<String?>, consumeRoute: () -> Unit) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val onTab = Tab.entries.any { it.route == current }

    val route by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        consumeRoute()
        if (r == "updates") nav.navigate("updates") { launchSingleTop = true }
        if (r == "apps") nav.navigate(Tab.Apps.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val grantFiles: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (onTab) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = {
                                if (current != t.route) {
                                    nav.navigate(t.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = Tab.Now.route,
            modifier = Modifier.padding(bottom = pad.calculateBottomPadding()),
            enterTransition = {
                if (isTabSwitch()) fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.98f)
                else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300)) + fadeIn(tween(300))
            },
            exitTransition = {
                if (isTabSwitch()) fadeOut(tween(160))
                else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300), targetOffset = { it / 4 }) + fadeOut(tween(300))
            },
            popEnterTransition = {
                if (isTabSwitch()) fadeIn(tween(220))
                else slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300), initialOffset = { it / 4 }) + fadeIn(tween(300))
            },
            popExitTransition = {
                if (isTabSwitch()) fadeOut(tween(160))
                else slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300)) + fadeOut(tween(200))
            },
        ) {
            composable(Tab.Now.route) {
                NowScreen(
                    NowActions(
                        toggleAuto = { on -> if (on) BatteryLogService.startAuto(context) else BatteryLogService.stop(context) },
                        record = { BatteryLogService.startManual(context) },
                        stop = { BatteryLogService.stop(context) },
                        openHistory = {
                            nav.navigate(Tab.History.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        grantFiles = grantFiles,
                    ),
                )
            }
            composable(Tab.History.route) { HistoryScreen(openRun = { path -> nav.navigate("run?path=" + Uri.encode(path)) }) }
            composable(Tab.Apps.route) { AppsScreen(openApp = { pkg -> nav.navigate("app?pkg=" + Uri.encode(pkg)) }) }
            composable(
                "app?pkg={pkg}",
                arguments = listOf(navArgument("pkg") { type = NavType.StringType; defaultValue = "" }),
            ) { e ->
                AppDetailScreen(pkg = e.arguments?.getString("pkg").orEmpty(), back = { nav.popBackStack() })
            }
            composable(Tab.Health.route) { HealthScreen(grantFiles = grantFiles) }
            composable(Tab.Settings.route) { SettingsScreen(openUpdates = { nav.navigate("updates") { launchSingleTop = true } }) }
            composable(
                "run?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
            ) { e ->
                RunScreen(path = e.arguments?.getString("path").orEmpty(), back = { nav.popBackStack() })
            }
            composable("updates") { UpdatesScreen(back = { nav.popBackStack() }) }
        }
    }
}

private fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isTabSwitch(): Boolean {
    val tabs = Tab.entries.map { it.route }
    return initialState.destination.route in tabs && targetState.destination.route in tabs
}
