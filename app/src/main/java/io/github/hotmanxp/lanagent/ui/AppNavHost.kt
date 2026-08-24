// ui/AppNavHost.kt — NavHost("home" → HomeScreen, "webview/{url}" → WebViewScreen)
package io.github.hotmanxp.lanagent.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.hotmanxp.lanagent.model.Card

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(onCardClick = { card ->
                navController.navigate("webview/${Uri.encode(card.url)}")
            })
        }
        // webview/{url} 路由在 Task 8 接入
    }
}
