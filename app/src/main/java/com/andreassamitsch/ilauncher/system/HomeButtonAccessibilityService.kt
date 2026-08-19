package com.andreassamitsch.ilauncher.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.andreassamitsch.ilauncher.MainActivity

internal enum class HomeRedirectTrigger {
    KeyEvent,
    DefaultHomeWindow,
}

internal class HomeRedirectDebouncer(
    private val debounceMillis: Long,
) {
    private var lastKeyRedirectAt: Long? = null
    private var lastWindowRedirectAt: Long? = null

    fun shouldRedirect(trigger: HomeRedirectTrigger, nowMillis: Long): Boolean {
        val lastRedirectAt = when (trigger) {
            HomeRedirectTrigger.KeyEvent -> lastKeyRedirectAt
            HomeRedirectTrigger.DefaultHomeWindow -> lastWindowRedirectAt
        }
        if (lastRedirectAt != null && nowMillis - lastRedirectAt < debounceMillis) {
            return false
        }

        when (trigger) {
            HomeRedirectTrigger.KeyEvent -> lastKeyRedirectAt = nowMillis
            HomeRedirectTrigger.DefaultHomeWindow -> lastWindowRedirectAt = nowMillis
        }
        return true
    }
}

class HomeButtonAccessibilityService : AccessibilityService() {
    private val redirectDebouncer = HomeRedirectDebouncer(REDIRECT_DEBOUNCE_MS)

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

        // Google-TV/OEM builds may still complete their system HOME transition even when the
        // accessibility key callback already attempted to consume HOME. This post-transition
        // window fallback must therefore never be suppressed by the key-event debounce.
        redirectToLauncher(HomeRedirectTrigger.DefaultHomeWindow)
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) {
            return super.onKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            redirectToLauncher(HomeRedirectTrigger.KeyEvent)
        }

        return true
    }

    private fun redirectToLauncher(trigger: HomeRedirectTrigger) {
        val now = SystemClock.elapsedRealtime()
        if (!redirectDebouncer.shouldRedirect(trigger, now)) {
            return
        }

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
