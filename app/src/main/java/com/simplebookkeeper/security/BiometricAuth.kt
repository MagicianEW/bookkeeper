package com.simplebookkeeper.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.simplebookkeeper.R
import com.simplebookkeeper.util.AppLogger

class BiometricAuth(private val context: Context) {

    companion object {
        private const val TAG = "BiometricAuth"
    }

    // 持有当前 BiometricPrompt 引用，以便 cancel
    private var currentPrompt: BiometricPrompt? = null
    private var currentPromptInfo: BiometricPrompt.PromptInfo? = null

    // 标记当前是否有活跃的认证请求
    @Volatile
    private var isAuthenticating = false

    // 待执行的认证请求（Activity 还没 RESUMED 时暂存）
    private var pendingAuth: PendingAuth? = null
    private var pendingObserver: LifecycleEventObserver? = null

    private data class PendingAuth(
        val activity: FragmentActivity,
        val onSuccess: () -> Unit,
        val onFailed: () -> Unit,
        val onError: (String) -> Unit
    )

    /**
     * 取消当前活跃的生物识别请求，清理系统队列
     * 必须在每次新 authenticate() 前调用，防止 BiometricScheduler 队列堆积
     */
    fun cancel() {
        currentPrompt?.let {
            AppLogger.i(TAG, "cancel() 取消旧的 BiometricPrompt")
            try {
                it.cancelAuthentication()
            } catch (e: Exception) {
                AppLogger.i(TAG, "cancel() 异常（可忽略）: ${e.message}")
            }
        }
        currentPrompt = null
        currentPromptInfo = null
        isAuthenticating = false

        // 清理 pending
        pendingAuth?.let { pending ->
            pending.activity.lifecycle.removeObserver(pendingObserver ?: return@let)
        }
        pendingAuth = null
        pendingObserver = null
    }

    // 检查设备是否支持生物识别
    fun canAuthenticate(): Boolean {
        val biometricManager = BiometricManager.from(context)
        val result = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        val canAuth = result == BiometricManager.BIOMETRIC_SUCCESS
        AppLogger.i(TAG, "canAuthenticate() result=$result (${resultCodeToString(result)}), canAuth=$canAuth")
        return canAuth
    }

    private fun resultCodeToString(code: Int): String = when (code) {
        BiometricManager.BIOMETRIC_SUCCESS -> "SUCCESS"
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "STATUS_UNKNOWN"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "ERROR_NO_HARDWARE"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "ERROR_HW_UNAVAILABLE"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "ERROR_NONE_ENROLLED"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "ERROR_SECURITY_UPDATE_REQUIRED"
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "ERROR_UNSUPPORTED"
        else -> "UNKNOWN_$code"
    }

    /**
     * 显示生物识别认证弹窗
     * 如果 Activity 还没 RESUMED，会自动等待 onResume 后再调用
     *
     * 重要：每次调用前会先 cancel() 旧的 BiometricPrompt，防止系统队列堆积
     */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailed: () -> Unit,
        onError: (String) -> Unit
    ) {
        // 先取消旧的认证请求，防止 BiometricScheduler 队列堆积
        cancel()

        val currentState = activity.lifecycle.currentState
        AppLogger.i(TAG, "authenticate() called, lifecycleState=$currentState")

        if (currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            doAuthenticate(activity, onSuccess, onFailed, onError)
        } else {
            // Activity 还没 RESUMED，注册观察者等待 onResume
            AppLogger.i(TAG, "Activity 尚未 RESUMED，等待 ON_RESUME...")
            pendingAuth = PendingAuth(activity, onSuccess, onFailed, onError)
            val observer = object : LifecycleEventObserver {
                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                    if (event == Lifecycle.Event.ON_RESUME) {
                        activity.lifecycle.removeObserver(this)
                        pendingObserver = null
                        val pending = pendingAuth
                        if (pending != null) {
                            pendingAuth = null
                            AppLogger.i(TAG, "Activity 已 RESUMED，执行待定的认证请求")
                            doAuthenticate(pending.activity, pending.onSuccess, pending.onFailed, pending.onError)
                        }
                    }
                }
            }
            pendingObserver = observer
            activity.lifecycle.addObserver(observer)
        }
    }

    private fun doAuthenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailed: () -> Unit,
        onError: (String) -> Unit
    ) {
        isAuthenticating = true
        val executor = ContextCompat.getMainExecutor(context)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.biometric_prompt_title))
            .setSubtitle(context.getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(context.getString(R.string.biometric_use_password))
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAuthenticating = false
                    currentPrompt = null
                    AppLogger.i(TAG, "onAuthenticationSucceeded")
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    AppLogger.i(TAG, "onAuthenticationFailed")
                    onFailed()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    isAuthenticating = false
                    currentPrompt = null
                    AppLogger.i(TAG, "onAuthenticationError: code=$errorCode, msg=$errString")
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        onError("USE_PASSWORD")
                    } else {
                        onError(errString.toString())
                    }
                }
            }
        )

        // 持有引用以便后续 cancel
        currentPrompt = biometricPrompt
        currentPromptInfo = promptInfo

        AppLogger.i(TAG, "calling biometricPrompt.authenticate()")
        biometricPrompt.authenticate(promptInfo)
    }
}
