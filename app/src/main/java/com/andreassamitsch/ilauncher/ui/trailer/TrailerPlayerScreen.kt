package com.andreassamitsch.ilauncher.ui.trailer

import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.andreassamitsch.ilauncher.data.youtube.YouTubeEmbedPlayer

@Composable
internal fun TrailerPlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val html = remember(videoId) { YouTubeEmbedPlayer.html(videoId) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(onBack = onBack)

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (html == null) {
            Text(
                text = "Trailer kann nicht intern geöffnet werden.",
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
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
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
