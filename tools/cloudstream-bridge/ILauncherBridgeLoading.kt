package com.lagradost.cloudstream3

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small modal loading surface for the I Launcher -> CloudStream bridge.
 *
 * The bridge can legitimately spend several seconds waiting for extensions, matching a provider,
 * loading a provider detail page and resolving playable links. Keep those phases visible so a TV
 * user never has to guess whether OK was accepted. No media URLs or provider credentials are shown.
 */
internal class ILauncherBridgeLoading private constructor(
    private val activity: FragmentActivity,
    private val dialog: AlertDialog,
    private val messageView: TextView,
    private val cancelled: AtomicBoolean,
) {
    companion object {
        fun show(activity: FragmentActivity, initialMessage: String): ILauncherBridgeLoading {
            val cancelled = AtomicBoolean(false)
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val progress = ProgressBar(activity).apply {
                isIndeterminate = true
            }
            val message = TextView(activity).apply {
                text = initialMessage
                textSize = 16f
                setPadding(dp(18), 0, 0, 0)
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(18), dp(24), dp(10))
                addView(
                    progress,
                    LinearLayout.LayoutParams(dp(42), dp(42)),
                )
                addView(
                    message,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }

            val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                .setTitle("CloudStream")
                .setView(content)
                .setNegativeButton("Abbrechen") { _, _ -> cancelled.set(true) }
                .create()
            dialog.setOnCancelListener { cancelled.set(true) }
            dialog.show()

            return ILauncherBridgeLoading(activity, dialog, message, cancelled)
        }
    }

    fun update(message: String) {
        activity.runOnUiThread {
            if (!cancelled.get() && dialog.isShowing) {
                messageView.text = message
            }
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    fun isCancelled(): Boolean = cancelled.get()
}
