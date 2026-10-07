package com.gearforge.app

import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gearforge.core.GearParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    lateinit var settings: SettingsStore
        private set
    lateinit var adManager: AdManager
        private set
    lateinit var billingManager: BillingManager
        private set

    /**
     * Per-app language (API 33+): mirrors the in-app EN/SV choice into the system locale for
     * this app, so the system per-app language screen and the app agree. Below API 33 the
     * in-app toggle alone drives every string (see [I18n]).
     */
    private fun applyAppLocale(lang: I18n.Lang) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                getSystemService(LocaleManager::class.java)?.applicationLocales =
                    LocaleList.forLanguageTags(if (lang == I18n.Lang.SV) "sv" else "en")
            }.onFailure { android.util.Log.w("MainActivity", "Per-app locale not applied", it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash reporting + telemetry must be installed before any other initialization
        // so an early crash is still captured (point 1).
        CrashReporting.init(applicationContext)
        CrashReporting.logEvent("app_launch")
        lifecycleScope.launch(Dispatchers.IO) {
            ExportManager.recoverIncompleteDownloads(applicationContext).onFailure {
                android.util.Log.w("ExportManager", "Incomplete export recovery deferred", it)
            }
        }

        enableEdgeToEdge()
        settings = SettingsStore(this)
        adManager = AdManager(this)
        billingManager = BillingManager(this, settings)

        // UMP consent must complete before ads are initialized/loaded.
        ConsentManager(this).ensureConsent { adManager.init() }

        // Pre-warm the wizard's first screen of 3D thumbnails in the current theme so the type grid
        // is populated when it opens; other types and accents render on demand.
        GearPreviewRenderer.warmCache(
            if (settings.darkTheme) DarkPrimaryArgb else LightPrimaryArgb,
            PRIMARY_TYPES
        )

        setContent {
            // Activity-scoped ViewModel: editor type/params/stage survive rotation + process death.
            val editorViewModel: EditorViewModel = viewModel()

            var darkTheme by remember { mutableStateOf(settings.darkTheme) }
            var lang by remember { mutableStateOf(settings.lang) }
            var showAbout by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            // New-user walkthrough position (see Tips). Held here, not in each screen, because the
            // walkthrough spans the stages: the wizard shows the first tip and the editor the rest,
            // and both have to agree on what is next. Mirrored into SettingsStore so it survives a
            // restart — which is the whole point of "first run" rather than "every run".
            var tipStep by remember { mutableIntStateOf(settings.tipsStep) }
            fun advanceTips() {
                tipStep = Tips.next(tipStep)
                settings.tipsStep = tipStep
            }

            val stage = editorViewModel.stage
            // The editor shows no thumbnails: stop prefetching them so they leave its CPU alone.
            LaunchedEffect(stage) {
                if (stage == Stage.EDITOR) GearPreviewRenderer.stopWarmUp()
            }

            // "Show tips again" restarts from the first tip that belongs to the screen the user is
            // actually looking at. Resetting to the wizard's tip while the editor is open would show
            // nothing at all there — a button that appears to do nothing is worse than no button.
            fun restartTips() {
                tipStep = if (stage == Stage.EDITOR) Tips.EDITOR_ORBIT else Tips.FIRST
                settings.tipsStep = tipStep
            }

            AppTheme(darkTheme = darkTheme) {
                // Stage-level back. The wizard registers its own handler (it owns its steps and
                // normally answers first, because it is composed later); the WIZARD branch below is
                // the safety net for "back always gets the user out", never the step navigation.
                BackHandler(enabled = stage != Stage.LANDING) {
                    when (stage) {
                        Stage.WIZARD -> editorViewModel.updateStage(Stage.LANDING)
                        Stage.EDITOR -> {
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
                        onCancel = { editorViewModel.updateStage(Stage.LANDING) },
                        tipStep = tipStep,
                        onTipAdvance = { advanceTips() }
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
                                onLangChange = { lang = it; settings.lang = it; applyAppLocale(it) },
                                viewModel = editorViewModel,
                                onBack = {
                                    editorViewModel.clearEditor()
                                    editorViewModel.updateStage(Stage.LANDING)
                                },
                                tipStep = tipStep,
                                onTipAdvance = { advanceTips() },
                                onRestartTips = { restartTips() }
                            )
                        }
                    }
                }

                if (showAbout) AboutDialog(lang = lang, onDismiss = { showAbout = false })
                if (showSettings) {
                    SettingsDialog(
                        activity = this@MainActivity,
                        darkTheme = darkTheme,
                        onThemeChange = { darkTheme = it; settings.darkTheme = it },
                        lang = lang,
                        onLangChange = { lang = it; settings.lang = it; applyAppLocale(it) },
                        settings = settings,
                        billingManager = billingManager,
                        onDismiss = { showSettings = false },
                        onShowTipsAgain = { restartTips() }
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
