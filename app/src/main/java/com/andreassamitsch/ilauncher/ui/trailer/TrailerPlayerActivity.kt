package com.andreassamitsch.ilauncher.ui.trailer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.andreassamitsch.ilauncher.data.youtube.YouTubeEmbedPlayer

class TrailerPlayerActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        val html = YouTubeEmbedPlayer.html(videoId)
        if (html == null) {
            finish()
            return
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        val playerWebView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(
                    view: View,
                    callback: CustomViewCallback,
                ) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    root.removeView(this@apply)
                    root.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    view.requestFocus()
                }

                override fun onHideCustomView() {
                    hideCustomView()
                }
            }
            isFocusable = true
            isFocusableInTouchMode = true
            loadDataWithBaseURL(
                YouTubeEmbedPlayer.baseUrl,
                html,
                "text/html",
                "UTF-8",
                null,
            )
            requestFocus()
        }
        webView = playerWebView
        root.addView(
            playerWebView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        customView = null
        webView?.run {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView?.let { playerWebView ->
            if (playerWebView.parent == null) {
                root.addView(
                    playerWebView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            playerWebView.requestFocus()
        }
    }

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"

        fun start(context: Context, videoId: String) {
            context.startActivity(
                Intent(context, TrailerPlayerActivity::class.java)
                    .putExtra(EXTRA_VIDEO_ID, videoId),
            )
        }
    }
}
