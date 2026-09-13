package com.andreassamitsch.joyntv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = JoynRepository(applicationContext)
        val updateManager = JoynUpdateManager(applicationContext)
        setContent {
            JoynTvTheme {
                JoynHomeScreen(
                    repository = repository,
                    updateManager = updateManager,
                    onPlayLive = { channel ->
                        // Prepare exactly the market encoded in the combined live-channel id before
                        // PlayerActivity exists. If that market has a fresh Mysterium CONNECT lease,
                        // pin that exact proxy. If preparation had to fall back to app-scoped
                        // WireGuard, do NOT call pinCurrent(): persisted proxy preferences can still
                        // point at the UI-selected AT/DE/CH market and would immediately overwrite the
                        // correctly prepared tunnel route.
                        lifecycleScope.launch {
                            val pinned = withContext(Dispatchers.IO) {
                                val prepared = repository.prepareLiveChannel(channel.id)
                                if (prepared.isFailure) {
                                    false
                                } else {
                                    val country = liveCountryFromChannelId(channel.id)
                                    val preparedProxy = country
                                        ?.takeUnless { repository.mysteriumLeaseNeedsRefresh(it) }
                                        ?.let { repository.mysteriumLastSuccessful(it) }
                                        ?.copy(enabled = true, automatic = true)

                                    preparedProxy?.let {
                                        JoynPlaybackRouteGuard.pin(applicationContext, it)
                                    } ?: false
                                }
                            }
                            runCatching {
                                startActivity(PlayerActivity.intent(this@MainActivity, channel.id, channel.title))
                            }.onFailure {
                                if (pinned) JoynPlaybackRouteGuard.release(applicationContext)
                            }
                        }
                    },
                    onOpenMedia = { item -> openJoynMedia(this, item) },
                    onSearch = { startActivity(Intent(this, SearchActivity::class.java)) },
                    onAccount = { startActivity(Intent(this, LoginActivity::class.java)) },
                )
            }
        }
    }
}

private fun liveCountryFromChannelId(channelId: String): JoynCountry? {
    if (!channelId.startsWith("multi:")) return null
    val payload = channelId.removePrefix("multi:")
    val countryName = payload.substringBefore(':')
    return runCatching { JoynCountry.valueOf(countryName) }.getOrNull()
}
