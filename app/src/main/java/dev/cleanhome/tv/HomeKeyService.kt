package dev.cleanhome.tv

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV always sends the Home button to its own launcher. This service takes the Home key
 * and opens Clean Home instead. If the Google TV home screen still comes up (after boot, or
 * after pressing Back out of an app), it switches to Clean Home.
 *
 * The Google TV launcher stays installed and enabled, so the mic / assistant button keeps working.
 * Voice results are shown by the Google TV launcher, so we leave it alone after a voice
 * request until the user opens another app or presses Home.
 */
class HomeKeyService : AccessibilityService() {

    private val stockLaunchers = mutableSetOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
    )

    private var voiceActive = false
    private var homeLongPressHandled = false
    private var lastRedirect = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != packageName && it != "android" }
            ?.let { stockLaunchers.add(it) }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_HOME -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        homeLongPressHandled = false
                    } else if (!homeLongPressHandled && (event.isLongPress || event.repeatCount >= 3)) {
                        // Long-press Home: the stock dashboard is gone, so open TV settings instead.
                        homeLongPressHandled = true
                        openTvSettings(this)
                    }
                } else if (event.action == KeyEvent.ACTION_UP && !homeLongPressHandled) {
                    voiceActive = false
                    goHome()
                }
                return true
            }
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST ->
                voiceActive = true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        when {
            pkg == packageName -> Unit
            isVoicePackage(pkg) -> voiceActive = true
            pkg in stockLaunchers -> {
                if (!voiceActive && isActivity(pkg, cls)) goHome()
            }
            // A real app came to the front, so any voice session is over.
            isActivity(pkg, cls) -> voiceActive = false
        }
    }

    override fun onInterrupt() = Unit

    private fun goHome() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRedirect < 700) return
        lastRedirect = now
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    private fun isVoicePackage(pkg: String) =
        pkg.contains("katniss") || pkg == "com.google.android.googlequicksearchbox" ||
            pkg.contains("assistant") || pkg.contains("gemini") || pkg.contains("bard")

    // Ignore panels, dialogs and overlays; only full screens (activities) count.
    private fun isActivity(pkg: String, cls: String) = try {
        packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(ctx, HomeKeyService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
