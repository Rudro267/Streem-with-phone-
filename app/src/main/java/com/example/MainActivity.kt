package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.example.ui.TallyLinkApp
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.TallyLinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(StudioObsidian.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(StudioObsidian.toArgb())
        )
        setContent {
            TallyLinkTheme {
                TallyLinkApp()
            }
        }
    }
}
