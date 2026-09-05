package com.pefoley.websitedownload.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.pefoley.websitedownload.ui.screens.DashboardScreen
import com.pefoley.websitedownload.ui.screens.ViewerScreen

@Composable
fun NavigationGraph() {
    val backStack = rememberNavBackStack(Dashboard)

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1) },
        entryProvider = { key ->
            when (key) {
                is Dashboard -> NavEntry(key) {
                    DashboardScreen(
                        onNavigateToViewer = { id -> backStack.add(Viewer(id)) },
                    )
                }
                is Viewer -> NavEntry(key) {
                    ViewerScreen(
                        mirrorId = key.mirrorId,
                        onNavigateBack = {
                            if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1)
                        },
                    )
                }
                else -> error("Unknown key: $key")
            }
        }
    )
}
