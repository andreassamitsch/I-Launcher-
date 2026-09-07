package com.andreassamitsch.servusprovider.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.andreassamitsch.servusprovider.R
import com.andreassamitsch.servusprovider.api.ServusNetwork
import com.andreassamitsch.servusprovider.data.ServusBranding
import com.andreassamitsch.servusprovider.data.ServusCategory
import com.andreassamitsch.servusprovider.data.ServusCollectionRole
import com.andreassamitsch.servusprovider.data.ServusCurrentChannelSelectionStore
import com.andreassamitsch.servusprovider.data.ServusHubStore
import com.andreassamitsch.servusprovider.data.ServusLiveChannel
import com.andreassamitsch.servusprovider.data.ServusNewsEpisode
import com.andreassamitsch.servusprovider.data.ServusNewsStore
import com.andreassamitsch.servusprovider.data.ServusShow
import com.andreassamitsch.servusprovider.data.ServusShowChannelSelectionStore
import com.andreassamitsch.servusprovider.data.ServusShowCollection
import com.andreassamitsch.servusprovider.data.ServusSourceKey
import com.andreassamitsch.servusprovider.ui.MainActivity
import com.andreassamitsch.servusprovider.ui.PlaybackActivity
import com.andreassamitsch.servusprovider.ui.ShowActivity
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ServusChannelPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val helper by lazy { PreviewChannelHelper(appContext) }
    private val remoteLogoCache = mutableMapOf<String, Bitmap?>()
    private val hubStore = ServusHubStore(appContext)
    private val newsStore = ServusNewsStore(appContext)
    private val currentSelectionStore = ServusCurrentChannelSelectionStore(appContext)
    private val showChannelSelectionStore = ServusShowChannelSelectionStore(appContext)

    fun isSupported(): Boolean =
        appContext.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

    fun isPublished(): Boolean {
        if (!isSupported()) return false
        return helper.getAllChannels().any { it.internalProviderId == CURRENT_CHANNEL_ID }
    }

    fun publish(episodes: List<ServusNewsEpisode>) {
        if (!isSupported()) return
        val categories = hubStore.loadCategories()
        val effectiveEpisodes = currentSelectionStore.effectiveEpisodes(
            categories = categories,
            legacyEpisodes = episodes,
        )
        val showArtworkById = categories
            .flatMap { it.shows }
            .distinctBy { it.id }
            .associate { it.id to it.artworkUri }
        val channelId = findOrCreateChannel(
            internalId = CURRENT_CHANNEL_ID,
            displayName = CURRENT_CHANNEL_NAME,
            description = "Deine ausgewählten aktuellen Sendungen von ServusTV",
            appIntent = Intent(appContext, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("iservus://channel/news")),
            logo = createAppLogo(),
        )
        replacePrograms(channelId, effectiveEpisodes.mapIndexed { index, episode ->
            val parentShowArtwork = episode.showArtworkUri
                ?.takeIf { it.isNotBlank() }
                ?: episode.showId
                    ?.let(showArtworkById::get)
                    ?.takeIf { it.isNotBlank() }
            buildEpisodeProgram(
                channelId = channelId,
                episode = episode,
                weight = effectiveEpisodes.size - index,
                logoUri = episode.logoUri,
                posterArtworkUri = parentShowArtwork ?: episode.artworkUri,
                includeLogo = true,
            )
        })
    }

    /** Synchronizes whole-show and sub-collection channels selected by the user. */
    fun publishShows(categories: List<ServusCategory>) {
        if (!isSupported()) return
        val selectedKeys = showChannelSelectionStore.effectiveSelectedSourceKeys(categories)
        val desiredInternalIds = selectedKeys.mapNotNullTo(hashSetOf()) { key ->
            if (ServusSourceKey.isCollection(key)) {
                val showId = ServusSourceKey.parentShowId(key) ?: return@mapNotNullTo null
                val collectionId = ServusSourceKey.collectionId(key) ?: return@mapNotNullTo null
                collectionInternalId(showId, collectionId)
            } else {
                showInternalId(key)
            }
        }
        val existingChannels = helper.getAllChannels().filter { channel ->
            channel.internalProviderId?.startsWith(SHOW_CHANNEL_PREFIX) == true ||
                channel.internalProviderId?.startsWith(COLLECTION_CHANNEL_PREFIX) == true
        }
        existingChannels.filter { it.internalProviderId !in desiredInternalIds }.forEach { channel ->
            appContext.contentResolver.delete(TvContractCompat.buildChannelUri(channel.id), null, null)
        }
        val existingByInternalId = existingChannels.mapNotNull { channel ->
            channel.internalProviderId?.let { it to channel.id }
        }.toMap()

        categories.flatMap { it.shows }.distinctBy { it.id }.forEach { show ->
            if (ServusSourceKey.show(show.id) in selectedKeys && show.episodes.isNotEmpty()) {
                publishShow(show, existingByInternalId[showInternalId(show.id)])
            }
            show.collections.filter { it.role == ServusCollectionRole.CONTENT }.forEach { collection ->
                val key = ServusSourceKey.collection(show.id, collection.id)
                if (key in selectedKeys && collection.episodes.isNotEmpty()) {
                    publishCollection(
                        show = show,
                        collection = collection,
                        existingChannelId = existingByInternalId[collectionInternalId(show.id, collection.id)],
                    )
                }
            }
        }

        if (currentSelectionStore.isConfigured()) publish(newsStore.loadEpisodes())
        showChannelSelectionStore.markTvProviderSynced()
    }

    fun removeShowChannel(showId: String) {
        removeChannelByInternalId(showInternalId(showId))
    }

    fun removeCollectionChannel(showId: String, collectionId: String) {
        removeChannelByInternalId(collectionInternalId(showId, collectionId))
    }

    private fun removeChannelByInternalId(internalId: String) {
        if (!isSupported()) return
        helper.getAllChannels().firstOrNull { it.internalProviderId == internalId }?.let { channel ->
            appContext.contentResolver.delete(TvContractCompat.buildChannelUri(channel.id), null, null)
        }
    }

    fun publishLive(channels: List<ServusLiveChannel>) {
        if (!isSupported() || channels.isEmpty()) return
        val channelId = findOrCreateChannel(
            internalId = LIVE_CHANNEL_ID,
            displayName = LIVE_CHANNEL_NAME,
            description = "ServusTV und die digitalen Live-Kanäle",
            appIntent = Intent(appContext, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("iservus://channel/live")),
            logo = createAppLogo(),
        )
        replacePrograms(channelId, channels.mapIndexed { index, live ->
            buildLiveProgram(channelId, live, channels.size - index)
        })
    }

    private fun publishShow(show: ServusShow, existingChannelId: Long?) {
        val channelId = findOrCreateChannel(
            internalId = showInternalId(show.id),
            displayName = show.title,
            description = show.description ?: "${show.title} bei ServusTV",
            appIntent = showIntent(show.id),
            logo = createShowLogo(show),
            existingChannelId = existingChannelId,
        )
        replacePrograms(channelId, show.episodes.mapIndexed { index, episode ->
            buildEpisodeProgram(channelId, episode, show.episodes.size - index, show.logoUri)
        })
    }

    private fun publishCollection(
        show: ServusShow,
        collection: ServusShowCollection,
        existingChannelId: Long?,
    ) {
        val displayName = if (collection.title.equals("Aktuelle Sendungen", ignoreCase = true)) {
            show.title
        } else {
            collection.title
        }
        val logoUri = collection.episodes.firstNotNullOfOrNull { it.logoUri } ?: show.logoUri
        val channelId = findOrCreateChannel(
            internalId = collectionInternalId(show.id, collection.id),
            displayName = displayName,
            description = "${collection.title} · ${show.title}",
            appIntent = showIntent(show.id),
            logo = createSourceLogo(show, logoUri),
            existingChannelId = existingChannelId,
        )
        replacePrograms(channelId, collection.episodes.mapIndexed { index, episode ->
            buildEpisodeProgram(channelId, episode, collection.episodes.size - index, logoUri)
        })
    }

    private fun showIntent(showId: String): Intent = Intent(appContext, ShowActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse("iservus://show/${Uri.encode(showId)}"))

    private fun findOrCreateChannel(
        internalId: String,
        displayName: String,
        description: String,
        appIntent: Intent,
        logo: Bitmap,
        existingChannelId: Long? = null,
    ): Long {
        val channel = PreviewChannel.Builder()
            .setDisplayName(displayName)
            .setDescription(description)
            .setInternalProviderId(internalId)
            .setAppLinkIntentUri(Uri.parse(appIntent.toUri(Intent.URI_INTENT_SCHEME)))
            .setLogo(logo)
            .build()
        val existingId = existingChannelId
            ?: helper.getAllChannels().firstOrNull { it.internalProviderId == internalId }?.id
        if (existingId != null) {
            runCatching { helper.updatePreviewChannel(existingId, channel) }
            return existingId
        }
        return helper.publishChannel(channel).also { id ->
            check(id >= 0L) { "Android-TV-Kanal '$displayName' konnte nicht veröffentlicht werden" }
        }
    }

    private fun replacePrograms(channelId: Long, programs: List<PreviewProgram>) {
        appContext.contentResolver.delete(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            null,
            null,
        )
        programs.forEach(helper::publishPreviewProgram)
    }

    private fun buildEpisodeProgram(
        channelId: Long,
        episode: ServusNewsEpisode,
        weight: Int,
        logoUri: String?,
        posterArtworkUri: String? = null,
        includeLogo: Boolean = true,
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
            .setDurationMillis(episode.durationMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setInternalProviderId(episode.id)
            .setIntentUri(playbackIntentUri)
            .setWeight(weight)
            .setBrowsable(true)
            .setSearchable(true)
        episode.publishedAtMillis?.let { builder.setReleaseDate(RELEASE_DATE_FORMAT.format(Date(it))) }
        episode.seasonNumber?.let(builder::setSeasonNumber)
        episode.episodeNumber?.let(builder::setEpisodeNumber)

        val episodeArtwork = episode.artworkUri
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
        val posterArtwork = posterArtworkUri
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: episodeArtwork
        posterArtwork?.let(builder::setPosterArtUri)
        episodeArtwork?.let(builder::setThumbnailUri)

        if (includeLogo) {
            ServusBranding.logoUriForEpisode(episode, logoUri ?: episode.logoUri)
                ?.takeIf { it.isNotBlank() }
                ?.let { builder.setLogoUri(Uri.parse(it)) }
        }
        return builder.build()
    }

    private fun buildLiveProgram(channelId: Long, live: ServusLiveChannel, weight: Int): PreviewProgram {
        val playbackIntentUri = Uri.parse(
            Intent(appContext, PlaybackActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("iservus://play/${Uri.encode(live.id)}"))
                .toUri(Intent.URI_INTENT_SCHEME),
        )
        val current = live.currentProgram()
        val description = buildString {
            current?.let {
                append(it.title)
                it.subtitle?.takeIf(String::isNotBlank)?.let { subtitle -> append(" · $subtitle") }
            }
            if (isEmpty()) append(live.description ?: "ServusTV Live")
        }
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
            .setTitle(live.title)
            .setDescription(description)
            .setInternalProviderId("live:${live.id}")
            .setIntentUri(playbackIntentUri)
            .setWeight(weight)
            .setBrowsable(true)
            .setSearchable(true)
        live.artworkUri?.let { uri ->
            val artwork = Uri.parse(uri)
            builder.setPosterArtUri(artwork)
            builder.setThumbnailUri(artwork)
        }
        live.logoUri?.takeIf { it.isNotBlank() }?.let { builder.setLogoUri(Uri.parse(it)) }
        return builder.build()
    }

    private fun createShowLogo(show: ServusShow): Bitmap = createSourceLogo(show, show.logoUri)

    private fun createSourceLogo(show: ServusShow, logoUri: String?): Bitmap {
        val source = when {
            show.id == ServusBranding.NEWS_90_SECONDS_SHOW_ID &&
                ServusBranding.isNinetySecondLogoUri(logoUri) -> drawableToBitmap(R.drawable.servus_news_90_logo)
            ServusBranding.isNinetySecondLogoUri(logoUri) -> drawableToBitmap(R.drawable.servus_news_90_logo)
            else -> logoUri?.let(::downloadBitmap)
        } ?: show.squareArtworkUri?.let(::downloadBitmap)
            ?: show.artworkUri?.let(::downloadBitmap)
            ?: return createAppLogo()
        return fitOnDarkCanvas(source)
    }

    private fun drawableToBitmap(resourceId: Int): Bitmap? {
        val drawable = appContext.getDrawable(resourceId) ?: return null
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        if (remoteLogoCache.containsKey(url)) return remoteLogoCache[url]
        val bitmap = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ServusNetwork.WEB_USER_AGENT)
                .header("Referer", "https://www.servustv.com/")
                .build()
            ServusNetwork.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                BitmapFactory.decodeStream(response.body.byteStream())
            }
        }.getOrNull()
        remoteLogoCache[url] = bitmap
        return bitmap
    }

    private fun fitOnDarkCanvas(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(CHANNEL_LOGO_SIZE_PX, CHANNEL_LOGO_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(12, 12, 12))
        val available = CHANNEL_LOGO_SIZE_PX - CHANNEL_LOGO_PADDING_PX * 2
        val scale = minOf(
            available.toFloat() / source.width.coerceAtLeast(1),
            available.toFloat() / source.height.coerceAtLeast(1),
        )
        val width = source.width * scale
        val height = source.height * scale
        val left = (CHANNEL_LOGO_SIZE_PX - width) / 2f
        val top = (CHANNEL_LOGO_SIZE_PX - height) / 2f
        canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG))
        return output
    }

    private fun createAppLogo(): Bitmap {
        val drawable = checkNotNull(appContext.getDrawable(R.drawable.ic_launcher)) {
            "Kanal-Logo konnte nicht aus den App-Ressourcen geladen werden"
        }
        return Bitmap.createBitmap(CHANNEL_LOGO_SIZE_PX, CHANNEL_LOGO_SIZE_PX, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    companion object {
        const val CURRENT_CHANNEL_ID = "servus-news-19-20"
        const val CURRENT_CHANNEL_NAME = "ServusTV Aktuelles"
        const val LIVE_CHANNEL_ID = "servus-live"
        const val LIVE_CHANNEL_NAME = "ServusTV Live"
        const val SHOW_CHANNEL_PREFIX = "servus-show:"
        const val COLLECTION_CHANNEL_PREFIX = "servus-collection:"
        const val CHANNEL_LOGO_SIZE_PX = 256
        const val CHANNEL_LOGO_PADDING_PX = 16

        fun showInternalId(showId: String): String = "$SHOW_CHANNEL_PREFIX$showId"
        fun collectionInternalId(showId: String, collectionId: String): String =
            "$COLLECTION_CHANNEL_PREFIX$showId:$collectionId"

        private val RELEASE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
}
