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
                        // Prepare the selected country before PlayerActivity exists and pin that
                        // residential route for the player's lifetime. Home can keep running in the
                        // background without being able to switch entitlement/manifest traffic to a
                        // different AT/DE/CH exit while the stream starts or plays.
                        lifecycleScope.launch {
                            val pinned = withContext(Dispatchers.IO) {
                                repository.prepareLiveChannel(channel.id).isSuccess &&
                                    JoynPlaybackRouteGuard.pinCurrent(applicationContext)
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
