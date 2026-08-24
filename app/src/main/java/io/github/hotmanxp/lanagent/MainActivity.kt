// MainActivity.kt — 单 Activity 入口,启用 immersive 全屏
package io.github.hotmanxp.lanagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.hotmanxp.lanagent.ui.AppNavHost
import io.github.hotmanxp.lanagent.ui.LanAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: app draws under system bars; WebView fills the screen.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersive()
        setContent {
            LanAgentTheme {
                AppNavHost()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide after the user swipes to reveal system bars (which Android
        // does as a temporary show); without this, the gesture bar stays sticky.
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}