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
    private val helper by lazy { PreviewChannelHelper(appContext) }

    /** Normal Android phones/tablets usually do not expose Android TV's TvProvider authority. */
    fun isSupported(): Boolean =
        appContext.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

    fun isPublished(): Boolean {
        if (!isSupported()) return false
        return helper.getAllChannels().any { it.internalProviderId == INTERNAL_CHANNEL_ID }
    }

    fun publish(episodes: List<ServusNewsEpisode>) {
        if (!isSupported()) return

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
        val existing = helper.getAllChannels()
            .firstOrNull { it.internalProviderId == INTERNAL_CHANNEL_ID }
        val channel = buildChannel()
        if (existing != null) {
            // Keep the original internal ID so I Launcher's existing channel preference remains stable.
            // A metadata-only update is best effort: even if one TV implementation rejects it, the
            // existing channel ID is still valid and its program list must continue to refresh.
            runCatching { helper.updatePreviewChannel(existing.id, channel) }
            return existing.id
        }
        return helper.publishChannel(channel).also { channelId ->
            check(channelId >= 0L) { "Android-TV-Kanal konnte nicht veröffentlicht werden" }
        }
    }

    private fun buildChannel(): PreviewChannel {
        val appIntentUri = Uri.parse(
            Intent(appContext, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("iservus://channel/news"))
                .toUri(Intent.URI_INTENT_SCHEME),
        )
        return PreviewChannel.Builder()
            .setDisplayName(CHANNEL_NAME)
            .setDescription("Servus Nachrichten, Nachrichten in 90 Sekunden und Der Wegscheider")
            .setInternalProviderId(INTERNAL_CHANNEL_ID)
            .setAppLinkIntentUri(appIntentUri)
            .setLogo(createChannelLogo())
            .build()
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
            .setDescription(episode.description ?: episode.showName ?: "ServusTV")
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
        // Intentionally unchanged from the first prototype to preserve the launcher's channel identity.
        const val INTERNAL_CHANNEL_ID = "servus-news-19-20"
        const val CHANNEL_NAME = "ServusTV Aktuelles"
        const val CHANNEL_LOGO_SIZE_PX = 256
    }
}
