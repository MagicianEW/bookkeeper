package com.simplebookkeeper.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
import com.simplebookkeeper.ui.screens.FirstLaunchSyncDialog
import com.simplebookkeeper.ui.screens.LockScreen
import com.simplebookkeeper.ui.screens.SplashScreen
import com.simplebookkeeper.ui.theme.SimpleBookkeeperTheme
import com.simplebookkeeper.viewmodel.MainViewModel
import kotlinx.coroutines.delay

class MainActivity : AppCompatActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SimpleBookkeeperTheme {
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
                        val app = application as BookkeeperApp
                        val windowSizeClass = calculateWindowSizeClass(this)
                        val isTablet = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

                        val isPasswordEnabled by app.passwordManager.isPasswordEnabled.collectAsState(initial = false)
                        val isFirstLaunch by app.passwordManager.isFirstLaunch.collectAsState(initial = true)
                        val isSyncPromptShown by app.settingsRepository.isCloudSyncPromptShown.collectAsState(initial = true)

                        var isUnlocked by remember { mutableStateOf(false) }
                        var showFirstLaunchDialog by remember { mutableStateOf(false) }

                        // 首次启动弹出云端同步对话框
                        LaunchedEffect(isFirstLaunch, isSyncPromptShown) {
                            if (isFirstLaunch && !isSyncPromptShown) {
                                showFirstLaunchDialog = true
                                app.passwordManager.markNotFirstLaunch()
                            }
                        }

                        when {
                            // 需要密码验证
                            isPasswordEnabled && !isUnlocked -> {
                                LockScreen(onUnlocked = { isUnlocked = true })
                            }
                            // 首次启动云端对话框（密码验证后或无密码）
                            showFirstLaunchDialog -> {
                                FirstLaunchSyncDialog(onDismiss = { showFirstLaunchDialog = false })
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
}
