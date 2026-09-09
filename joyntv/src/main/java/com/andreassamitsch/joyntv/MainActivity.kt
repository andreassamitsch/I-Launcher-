package com.andreassamitsch.joyntv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

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
                        startActivity(PlayerActivity.intent(this, channel.id, channel.title))
                    },
                    onOpenMedia = { item -> openJoynMedia(this, item) },
                    onSearch = { startActivity(Intent(this, SearchActivity::class.java)) },
                    onAccount = { startActivity(Intent(this, LoginActivity::class.java)) },
                )
            }
        }
    }
}
