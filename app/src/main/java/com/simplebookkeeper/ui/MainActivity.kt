package com.simplebookkeeper.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplebookkeeper.BookkeeperApp
import com.simplebookkeeper.ui.screens.FirstLaunchSetPasswordDialog
import com.simplebookkeeper.ui.screens.FirstLaunchSyncDialog
import com.simplebookkeeper.ui.screens.LockScreen
import com.simplebookkeeper.ui.screens.SplashScreen
import com.simplebookkeeper.ui.theme.LanguageMode
import com.simplebookkeeper.ui.theme.SimpleBookkeeperTheme
import com.simplebookkeeper.ui.theme.ThemeMode
import com.simplebookkeeper.util.LocaleHelper
import com.simplebookkeeper.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        @JvmStatic
        var lastAppliedLanguage: LanguageMode = LanguageMode.FOLLOW_SYSTEM
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as BookkeeperApp

        // 应用语言设置（默认 FOLLOW_SYSTEM，后续由 Flow 更新）
        applyLanguage(lastAppliedLanguage)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 清理上次残留的生物识别请求，防止 BiometricScheduler 队列堆积
        app.biometricAuth.cancel()

        setContent {
            val app = application as BookkeeperApp
            val themeMode by app.settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            // languageMode 不再用于驱动 recreate，仅用于 UI 状态显示
            val languageMode by app.settingsRepository.languageMode.collectAsState(initial = LanguageMode.FOLLOW_SYSTEM)

            // 同步静态变量并在语言加载后应用（语言切换由 SettingsScreen 显式重启应用来生效）
            LaunchedEffect(languageMode) {
                lastAppliedLanguage = languageMode
                applyLanguage(languageMode)
            }
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            SimpleBookkeeperTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSplash by remember { mutableStateOf(true) }

                    // 开屏页显示 1.5 秒后自动消失
                    LaunchedEffect(Unit) {
                        delay(1500)
                        showSplash = false
                    }

                    if (showSplash) {
                        SplashScreen()
                    } else {
                        val windowSizeClass = calculateWindowSizeClass(this)
                        val isTablet = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

                        val isPasswordEnabled by app.passwordManager.isPasswordEnabled.collectAsState(initial = false)
                        val isFirstLaunch by app.settingsRepository.isFirstLaunch.collectAsState(initial = true)
                        val isPasswordSetupDone by app.settingsRepository.isPasswordSetupDone.collectAsState(initial = false)
                        val isSyncPromptShown by app.settingsRepository.isCloudSyncPromptShown.collectAsState(initial = true)

                        var isUnlocked by remember { mutableStateOf(false) }
                        var showSetPasswordDialog by remember { mutableStateOf(false) }
                        var showSyncDialog by remember { mutableStateOf(false) }
                        val scope = rememberCoroutineScope()

                        // 首次启动流程：1. 密码设置  2. 云端恢复
                        LaunchedEffect(isFirstLaunch, isPasswordSetupDone, isSyncPromptShown) {
                            if (isFirstLaunch && !isPasswordSetupDone) {
                                showSetPasswordDialog = true
                            } else if (isFirstLaunch && isPasswordSetupDone && !isSyncPromptShown) {
                                showSyncDialog = true
                            }
                        }

                        when {
                            // 需要密码验证
                            isPasswordEnabled && !isUnlocked -> {
                                LockScreen(onUnlocked = { isUnlocked = true })
                            }
                            // 首次启动：密码设置对话框
                            showSetPasswordDialog && !isPasswordSetupDone -> {
                                FirstLaunchSetPasswordDialog(
                                    onPasswordSet = { password ->
                                        scope.launch {
                                            app.passwordManager.setPassword(password)
                                            app.settingsRepository.markPasswordSetupDone()
                                        }
                                        showSetPasswordDialog = false
                                        // 密码设置后，弹出云端恢复对话框
                                        if (!isSyncPromptShown) {
                                            showSyncDialog = true
                                        }
                                    },
                                    onSkip = {
                                        scope.launch {
                                            app.settingsRepository.markPasswordSetupDone()
                                        }
                                        showSetPasswordDialog = false
                                        // 跳过密码后，也弹出云端恢复对话框
                                        if (!isSyncPromptShown) {
                                            showSyncDialog = true
                                        }
                                    }
                                )
                            }
                            // 首次启动：云端恢复对话框
                            showSyncDialog && !isSyncPromptShown -> {
                                FirstLaunchSyncDialog(onDismiss = {
                                    showSyncDialog = false
                                    scope.launch {
                                        app.settingsRepository.markNotFirstLaunch()
                                    }
                                })
                            }
                            // 正常主界面
                            else -> {
                                val viewModel: MainViewModel = viewModel()
                                AppNavigation(
                                    viewModel = viewModel,
                                    isTablet = isTablet,
                                    activity = this
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyLanguage(mode: LanguageMode) {
        val locale = LocaleHelper.getLocaleFromMode(mode)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
