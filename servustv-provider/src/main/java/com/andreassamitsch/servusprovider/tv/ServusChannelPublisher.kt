package com.andreassamitsch.servusprovider.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.andreassamitsch.servusprovider.R
import com.andreassamitsch.servusprovider.data.ServusNewsEpisode
import com.andreassamitsch.servusprovider.ui.MainActivity
import com.andreassamitsch.servusprovider.ui.PlaybackActivity

class ServusChannelPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val helper = PreviewChannelHelper(appContext)

    fun isPublished(): Boolean = helper.getAllChannels()
        .count { it.internalProviderId == INTERNAL_CHANNEL_ID } == 1

    fun publish(episodes: List<ServusNewsEpisode>) {
        val channelId = findOrCreateChannel()
        appContext.contentResolver.delete(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            null,
            null,
        )

        episodes.forEachIndexed { index, episode ->
            helper.publishPreviewProgram(buildProgram(channelId, episode, episodes.size - index))
        }
    }

    private fun findOrCreateChannel(): Long {
        val existingChannels = helper.getAllChannels()
            .filter { it.internalProviderId == INTERNAL_CHANNEL_ID }
            .sortedBy { it.id }
        existingChannels.firstOrNull()?.let { primary ->
            existingChannels.drop(1).forEach { duplicate ->
                appContext.contentResolver.delete(
                    TvContractCompat.buildChannelUri(duplicate.id),
                    null,
                    null,
                )
            }
            return primary.id
        }

        val appIntentUri = Uri.parse(
            Intent(appContext, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("iservus://channel/news"))
                .toUri(Intent.URI_INTENT_SCHEME),
        )
        val channel = PreviewChannel.Builder()
            .setDisplayName(CHANNEL_NAME)
            .setDescription("Die letzten vollständigen Servus-Nachrichten um 19:20")
            .setInternalProviderId(INTERNAL_CHANNEL_ID)
            .setAppLinkIntentUri(appIntentUri)
            .setLogo(createChannelLogo())
            .build()
        return helper.publishChannel(channel)
    }

    private fun createChannelLogo(): Bitmap {
        val drawable = checkNotNull(appContext.getDrawable(R.drawable.ic_launcher)) {
            "Kanal-Logo konnte nicht aus den App-Ressourcen geladen werden"
        }
        return Bitmap.createBitmap(
            CHANNEL_LOGO_SIZE_PX,
            CHANNEL_LOGO_SIZE_PX,
            Bitmap.Config.ARGB_8888,
        ).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    private fun buildProgram(
        channelId: Long,
        episode: ServusNewsEpisode,
        weight: Int,
    ): PreviewProgram {
        val playbackIntentUri = Uri.parse(
            Intent(appContext, PlaybackActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("iservus://play/${Uri.encode(episode.id)}"))
                .toUri(Intent.URI_INTENT_SCHEME),
        )
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
            .setTitle(episode.title)
            .setDescription(episode.description ?: "Servus Nachrichten 19:20")
            .setDurationMillis(episode.durationMillis.toInt())
            .setInternalProviderId(episode.id)
            .setIntentUri(playbackIntentUri)
            .setWeight(weight)
            .setBrowsable(true)
            .setSearchable(true)

        episode.artworkUri?.let { uri ->
            val artwork = Uri.parse(uri)
            builder.setPosterArtUri(artwork)
            builder.setThumbnailUri(artwork)
        }
        return builder.build()
    }

    private companion object {
        const val INTERNAL_CHANNEL_ID = "servus-news-19-20"
        const val CHANNEL_NAME = "Servus Nachrichten"
        const val CHANNEL_LOGO_SIZE_PX = 256
    }
}
