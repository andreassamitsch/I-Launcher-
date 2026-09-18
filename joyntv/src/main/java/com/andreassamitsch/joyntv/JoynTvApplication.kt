package com.andreassamitsch.joyntv

import android.app.Application

class JoynTvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JoynNextEpisodeScheduler.install(this)
    }
}
