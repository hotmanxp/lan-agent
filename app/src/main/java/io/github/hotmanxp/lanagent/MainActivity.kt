// MainActivity.kt — 单 Activity 入口,system bars 由 WebViewScreen FAB 手动控制
package io.github.hotmanxp.lanagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import io.github.hotmanxp.lanagent.ui.AppNavHost
import io.github.hotmanxp.lanagent.ui.LanAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: app draws under system bars; bars are shown/hidden
        // by a FAB in WebViewScreen, not automatically.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            LanAgentTheme {
                AppNavHost()
            }
        }
    }
}