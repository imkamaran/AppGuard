package io.github.imkamaran.appguard.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.imkamaran.appguard.AppGuardApp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val policies = (application as AppGuardApp).policies
        setContent {
            val config by policies.state.collectAsStateWithLifecycle()
            val dark = isAppInDarkTheme(config.ui)
            // System-bar icons follow the app's theme choice, not only the system setting.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppGuardTheme(config.ui) {
                Surface(color = MaterialTheme.colorScheme.background) { AppGuardRoot() }
            }
        }
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Apps : Screen
    data object Audit : Screen
    data object Settings : Screen
    data class Policy(val packageName: String) : Screen
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Screen.Home, "Home", Icons.Filled.Home),
    Tab(Screen.Apps, "Apps", Icons.AutoMirrored.Filled.List),
    Tab(Screen.Audit, "Audit log", Icons.Filled.Notifications),
    Tab(Screen.Settings, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppGuardRoot() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = stack.last()
    fun open(s: Screen) {
        if (s is Screen.Policy) stack.add(s) else { stack.clear(); stack.add(s) }
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    if (current is Screen.Policy) {
        PolicyEditorScreen(current.packageName, onBack = { stack.removeAt(stack.lastIndex) })
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in tabs) {
                    NavigationBarItem(
                        selected = current == t.screen,
                        onClick = { open(t.screen) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (current) {
            Screen.Home -> HomeScreen(m, onOpenApp = { open(Screen.Policy(it)) }, onOpenTab = { open(it) })
            Screen.Apps -> AppListScreen(m, onOpenApp = { open(Screen.Policy(it)) })
            Screen.Audit -> AuditScreen(m)
            Screen.Settings -> SettingsScreen(m)
            is Screen.Policy -> Unit
        }
    }
}
