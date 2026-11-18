package app.inkwell

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Thin wrapper over BiometricPrompt: biometrics or the device PIN/pattern/password. */
object AppLock {
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** True when the device has a biometric or screen lock the user can authenticate with. */
    fun available(context: Context) = BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onDone: () -> Unit = {}) {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onDone(); onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onDone() }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTH).build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb).authenticate(info)
    }

    /** Set before handing off to another app (file picker, share sheet, permission dialog) so returning doesn't re-lock. */
    @Volatile var skipNextLock = false
}
