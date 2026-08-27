package com.gearforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gearforge.core.GearParams

class MainActivity : ComponentActivity() {

    lateinit var settings: SettingsStore
        private set
    lateinit var adManager: AdManager
        private set
    lateinit var billingManager: BillingManager
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash reporting + telemetry must be installed before any other initialization
        // so an early crash is still captured (point 1).
        CrashReporting.init(applicationContext)
        CrashReporting.logEvent("app_launch")

        enableEdgeToEdge()
        settings = SettingsStore(this)
        adManager = AdManager(this)
        billingManager = BillingManager(this, settings)

        // UMP consent must complete before ads are initialized/loaded.
        ConsentManager(this).ensureConsent { adManager.init() }

        setContent {
            // Activity-scoped ViewModel: editor type/params/stage survive rotation + process death.
            val editorViewModel: EditorViewModel = viewModel()

            var darkTheme by remember { mutableStateOf(settings.darkTheme) }
            var lang by remember { mutableStateOf(settings.lang) }
            var showAbout by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }

            val stage = editorViewModel.stage

            AppTheme(darkTheme = darkTheme) {
                BackHandler(enabled = stage != Stage.LANDING) {
                    when {
                        stage == Stage.WIZARD -> editorViewModel.updateStage(Stage.LANDING)
                        stage == Stage.EDITOR -> {
                            editorViewModel.clearEditor()
                            editorViewModel.updateStage(Stage.LANDING)
                        }
                        else -> {}
                    }
                }
                when (stage) {
                    Stage.LANDING -> LandingScreen(
                        darkTheme = darkTheme,
                        lang = lang,
                        onStart = { editorViewModel.updateStage(Stage.WIZARD) },
                        onSettings = { showSettings = true },
                        onAbout = { showAbout = true },
                        onLoadSaved = { p ->
                            editorViewModel.startEditor(p)
                            editorViewModel.updateStage(Stage.EDITOR)
                        }
                    )
                    Stage.WIZARD -> GearWizard(
                        lang = lang,
                        onDone = { p ->
                            editorViewModel.startEditor(p)
                            editorViewModel.updateStage(Stage.EDITOR)
                        },
                        onCancel = { editorViewModel.updateStage(Stage.LANDING) }
                    )
                    Stage.EDITOR -> {
                        if (editorViewModel.params == null) {
                            // Defensive: inconsistent restored state cannot happen in practice.
                            editorViewModel.updateStage(Stage.LANDING)
                        } else {
                            GearWorkspaceScreen(
                                activity = this@MainActivity,
                                settings = settings,
                                adManager = adManager,
                                billingManager = billingManager,
                                darkTheme = darkTheme,
                                onThemeChange = { darkTheme = it; settings.darkTheme = it },
                                lang = lang,
                                onLangChange = { lang = it; settings.lang = it },
                                viewModel = editorViewModel,
                                onBack = {
                                    editorViewModel.clearEditor()
                                    editorViewModel.updateStage(Stage.LANDING)
                                }
                            )
                        }
                    }
                }

                if (showAbout) AboutDialog(lang = lang, onDismiss = { showAbout = false })
                if (showSettings) {
                    SettingsDialog(
                        darkTheme = darkTheme,
                        onThemeChange = { darkTheme = it; settings.darkTheme = it },
                        lang = lang,
                        onLangChange = { lang = it; settings.lang = it },
                        settings = settings,
                        billingManager = billingManager,
                        onDismiss = { showSettings = false }
                    )
                }
            }
        }
    }

    override fun onPause() {
        GearGLViewBridge.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        GearGLViewBridge.onResume()
    }

    override fun onDestroy() {
        if (::billingManager.isInitialized) billingManager.close()
        super.onDestroy()
    }
}
