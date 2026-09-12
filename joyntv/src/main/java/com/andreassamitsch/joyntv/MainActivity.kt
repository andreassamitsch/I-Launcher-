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
                        // Prepare the selected country before PlayerActivity exists and pin exactly
                        // that residential route for the player's lifetime. Do not simply re-read the
                        // process-wide current proxy after preparation: Home may still finish another
                        // AT/DE/CH background request in between and otherwise pin the wrong market.
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

                                    if (preparedProxy != null) {
                                        JoynPlaybackRouteGuard.pin(applicationContext, preparedProxy)
                                    } else {
                                        // WireGuard/manual/direct routes do not have a reusable
                                        // Mysterium CONNECT profile; retain the existing behaviour.
                                        JoynPlaybackRouteGuard.pinCurrent(applicationContext)
                                    }
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
