/**
 * ZONA-OSIER — Main Activity.
 * Entry point dengan NavHost untuk semua screen.
 */
package com.zonaosier.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zonaosier.governor.GovernorState
import com.zonaosier.model.RouterStatus
import com.zonaosier.ui.screens.*
import com.zonaosier.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val skinEngine = SkinEngine(this)

        setContent {
            val colors by skinEngine.currentSkin.collectAsState()
            val zonaColors = ZonaPalette.ALL_SKINS[colors] ?: ZonaPalette.SUNYATA
            val animConfig = skinEngine.getAnimationConfig()
            val navController = rememberNavController()

            // Dummy states — di produksi di-inject via ViewModel/DI
            val governorState = remember { mutableStateOf(GovernorState()) }
            val routerStatus = remember { mutableStateOf(RouterStatus()) }

            ZonaOsierTheme(
                colors = zonaColors,
                animationConfig = animConfig
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "home"
                ) {
                    composable("home") {
                        HomeScreen(
                            governorState = governorState.value,
                            routerStatus = routerStatus.value,
                            activeTier = "Auto",
                            onQuickAction = { action ->
                                when (action) {
                                    QuickAction.CHAT -> navController.navigate("chat")
                                    QuickAction.VOICE -> navController.navigate("voice")
                                    QuickAction.SETTINGS -> navController.navigate("settings")
                                    QuickAction.MEMORY -> navController.navigate("memory")
                                    QuickAction.SHELL -> { /* TODO */ }
                                    QuickAction.SCREEN -> { /* TODO: Screenshot */ }
                                }
                            },
                            onCharacterDrawerOpen = { /* TODO: ModalDrawer */ }
                        )
                    }

                    composable("chat") {
                        ChatScreen(
                            characterName = "ZONA-OSIER",
                            personaRegister = "Default",
                            messages = emptyList(),
                            isThinking = false,
                            thinkingSteps = emptyList(),
                            confidenceCategory = com.zonaosier.ui.components.ConfidenceCategory.HIGH,
                            currentTool = null,
                            onSendMessage = { },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("voice") {
                        VoiceScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            governorState = governorState.value,
                            providers = emptyList(),
                            selectedSkin = colors,
                            onSkinChange = { skinEngine.setSkin(it) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("security") {
                        SecurityScreen(
                            auditEntries = emptyList(),
                            isFrozen = false,
                            onFreeze = { },
                            onUnfreeze = { },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("memory") {
                        MemoryTimelineScreen(
                            entries = emptyList(),
                            isLoading = false,
                            onLoadMore = { },
                            onSync = { },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("character_editor") {
                        CharacterEditorScreen(
                            onBack = { navController.popBackStack() },
                            onSave = { _, _, _, _, _, _ -> navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
