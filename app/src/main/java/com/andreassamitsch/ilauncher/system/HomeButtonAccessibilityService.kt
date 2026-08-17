package com.andreassamitsch.ilauncher.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.andreassamitsch.ilauncher.MainActivity

class HomeButtonAccessibilityService : AccessibilityService() {
    private var lastRedirectAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == packageName) {
            return
        }

        val defaultHomePackage = HomeLauncherManager.defaultHomePackageName(this) ?: return
        if (defaultHomePackage == packageName || eventPackage != defaultHomePackage) {
            return
        }

        redirectToLauncher()
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) {
            return super.onKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            redirectToLauncher()
        }

        return true
    }

    private fun redirectToLauncher() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectAt < REDIRECT_DEBOUNCE_MS) {
            return
        }
        lastRedirectAt = now

        val launcherIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_RETURN_TO_LAUNCHER_HOME
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { startActivity(launcherIntent) }
    }

    private companion object {
        const val REDIRECT_DEBOUNCE_MS = 750L
    }
}
