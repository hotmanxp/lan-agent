// MainActivity.kt — 单 Activity 入口,immersive 全屏
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
        // Draw under system bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Hide status bar + navigation bar on first show. The system bars stay
        // hidden until the user swipes from an edge (then auto-hide again).
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            LanAgentTheme {
                AppNavHost()
            }
        }
    }
}