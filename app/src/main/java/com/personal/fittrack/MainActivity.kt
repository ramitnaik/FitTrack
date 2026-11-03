package com.personal.fittrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.fittrack.ui.AppRoot
import com.personal.fittrack.ui.FitTheme
import com.personal.fittrack.ui.LocalContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FitApp).container
        setContent {
            val theme by container.prefs.theme.collectAsStateWithLifecycle()
            val dark = when (theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
            FitTheme(dark) { CompositionLocalProvider(LocalContainer provides container) { AppRoot() } }
        }
    }
}
