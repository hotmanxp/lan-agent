// MainActivity.kt — 单 Activity 入口
package io.github.hotmanxp.lanagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.hotmanxp.lanagent.ui.AppNavHost
import io.github.hotmanxp.lanagent.ui.LanAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LanAgentTheme {
                AppNavHost()
            }
        }
    }
}
