package com.andreassamitsch.ilauncher.data.youtube

internal object YouTubeEmbedPlayer {
    const val baseUrl = "https://ilauncher.local/"

    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    fun html(videoId: String): String? {
        if (!videoIdPattern.matches(videoId)) return null

        return """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <meta name="referrer" content="strict-origin-when-cross-origin">
                <style>
                  html, body, iframe {
                    margin: 0;
                    padding: 0;
                    width: 100%;
                    height: 100%;
                    border: 0;
                    background: #000;
                    overflow: hidden;
                  }
                </style>
              </head>
              <body>
                <iframe
                  id="player"
                  src="https://www.youtube.com/embed/$videoId?autoplay=1&controls=1&fs=0&playsinline=1&rel=0"
                  title="YouTube trailer"
                  allow="autoplay; encrypted-media; picture-in-picture"
                  allowfullscreen>
                </iframe>
              </body>
            </html>
        """.trimIndent()
    }
}
