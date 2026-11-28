package com.andreassamitsch.servusprovider.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.andreassamitsch.servusprovider.BuildConfig
import com.andreassamitsch.servusprovider.update.ServusUpdateInfo
import com.andreassamitsch.servusprovider.update.ServusUpdateState

/** Native equivalent of the Joyn TV popup; a single window stays open as progress changes. */
internal class ServusUpdatePopup(
    private val activity: Activity,
    info: ServusUpdateInfo,
    onLater: () -> Unit,
    onPrimary: () -> Unit,
) {
    val versionCode = info.versionCode
    private val dialog = Dialog(activity)
    private val status = text(16f)
    private val message = text(15f, Color.rgb(255, 197, 197))
    private val primary = button("Update laden", onPrimary)
    private val later = button("Später", onLater)
    private var lastStage: Int? = null

    init {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.rgb(32, 37, 46))
            }
            addView(text(23f).apply { text = "ServusTV – Update verfügbar" })
            addView(text(16f).apply {
                text = "Installiert: ${BuildConfig.VERSION_NAME}\nNeu: ${info.versionName}"
                setPadding(0, dp(12), 0, dp(12))
            })
            addView(status)
            addView(message)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(16), 0, 0)
                addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(later, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(12)
                })
            })
        }
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onLater() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(dp(540), activity.resources.displayMetrics.widthPixels - dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun render(state: ServusUpdateState, installMessage: String?, installing: Boolean) {
        val stage = when (state) {
            is ServusUpdateState.Downloading -> 1
            is ServusUpdateState.ReadyToInstall -> if (installing) 3 else 2
            else -> 0
        }
        status.text = when (state) {
            is ServusUpdateState.Downloading -> state.progressPercent?.let { "Download: $it %" }
                ?: "Update wird heruntergeladen …"
            is ServusUpdateState.ReadyToInstall -> if (installing) "APK wird geprüft …"
                else "Download abgeschlossen. Das Update kann installiert werden."
            else -> ""
        }
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        message.text = installMessage.orEmpty()
        message.visibility = if (installMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        primary.text = if (stage == 2 || stage == 3) "Installieren" else "Update laden"
        primary.visibility = if (stage == 1) View.GONE else View.VISIBLE
        primary.isEnabled = stage != 3
        if (lastStage != stage) {
            if (stage == 1 || stage == 3) later.requestFocus() else primary.requestFocus()
            lastStage = stage
        }
    }

    fun close() { dialog.dismiss() }

    private fun text(size: Float, color: Int = Color.WHITE) = TextView(activity).apply {
        textSize = size
        setTextColor(color)
    }

    private fun button(label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        isFocusable = true
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        fun backgroundForFocus(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (focused) Color.rgb(80, 85, 95) else Color.rgb(48, 53, 62))
            if (focused) setStroke(dp(2), Color.WHITE)
        }
        background = backgroundForFocus(false)
        setOnFocusChangeListener { _, focused -> background = backgroundForFocus(focused) }
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
