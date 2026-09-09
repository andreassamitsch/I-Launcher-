package com.andreassamitsch.joyntv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri

internal class JoynPreviewChannelPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences("joyn_preview_channels", Context.MODE_PRIVATE)

    fun publishLive(channels: List<JoynLiveChannel>) {
        if (channels.isEmpty()) return
        val channelId = ensureChannel()
        val programsUri = TvContract.buildPreviewProgramsUriForChannel(channelId)
        runCatching { resolver.delete(programsUri, null, null) }

        channels.forEachIndexed { index, channel ->
            val current = channel.currentProgram
            val artwork = current?.imageUrl ?: channel.logoUrl
            val values = ContentValues().apply {
                put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
                put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CHANNEL)
                put(TvContract.PreviewPrograms.COLUMN_TITLE, current?.title ?: channel.title)
                put(
                    TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                    current?.subtitle ?: "Live auf ${channel.title}",
                )
                artwork?.let {
                    put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, it)
                    put(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, it)
                }
                channel.logoUrl?.let { put(TvContract.PreviewPrograms.COLUMN_LOGO_URI, it) }
                put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, playerIntent(channel).toUri(Intent.URI_INTENT_SCHEME))
                put(TvContract.PreviewPrograms.COLUMN_WEIGHT, channels.size - index)
                put(TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1)
                put(TvContract.PreviewPrograms.COLUMN_SEARCHABLE, 1)
                val start = current?.startEpochSeconds
                val end = current?.endEpochSeconds
                if (start != null && end != null && end > start) {
                    put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, (end - start) * 1000L)
                }
            }
            runCatching { resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values) }
        }
    }

    private fun ensureChannel(): Long {
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, CHANNEL_NAME)
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, CHANNEL_INTERNAL_ID)
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, mainIntent().toUri(Intent.URI_INTENT_SCHEME))
        }
        val storedId = prefs.getLong(PREF_CHANNEL_ID, -1L)
        if (storedId > 0L) {
            val updated = runCatching {
                resolver.update(TvContract.buildChannelUri(storedId), values, null, null)
            }.getOrDefault(0)
            if (updated > 0) return storedId
        }

        val uri = resolver.insert(TvContract.Channels.CONTENT_URI, values)
            ?: error("Unable to create Joyn preview channel")
        val id = ContentUris.parseId(uri)
        prefs.edit().putLong(PREF_CHANNEL_ID, id).apply()
        return id
    }

    private fun mainIntent(): Intent = Intent(appContext, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)

    private fun playerIntent(channel: JoynLiveChannel): Intent = Intent(appContext, PlayerActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("ilauncherjoyn://live/${Uri.encode(channel.id)}")
        putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        putExtra(PlayerActivity.EXTRA_CHANNEL_TITLE, channel.title)
    }

    companion object {
        private const val CHANNEL_NAME = "Joyn · Live TV"
        private const val CHANNEL_INTERNAL_ID = "joyn_live_tv"
        private const val PREF_CHANNEL_ID = "live_channel_id"
    }
}
