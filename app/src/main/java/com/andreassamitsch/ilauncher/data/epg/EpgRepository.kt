package com.andreassamitsch.ilauncher.data.epg

import android.content.Context
import android.util.Log
import com.andreassamitsch.ilauncher.data.database.EpgChannelMappingEntity
import com.andreassamitsch.ilauncher.data.database.EpgProgramEntity
import com.andreassamitsch.ilauncher.data.database.ILauncherDatabase
import com.andreassamitsch.ilauncher.data.tmdb.MediaLookup
import com.andreassamitsch.ilauncher.data.tmdb.TmdbRepository
import com.andreassamitsch.ilauncher.model.EpgChannelMapping
import com.andreassamitsch.ilauncher.model.EpgSourceChannel
import com.andreassamitsch.ilauncher.model.LiveTvChannel
import com.andreassamitsch.ilauncher.model.LiveTvProgram
import com.andreassamitsch.ilauncher.model.MediaType
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.zip.ZipException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.xml.sax.SAXException

private const val TAG = "EPG"

class EpgRepository(
    context: Context,
    private val tmdbRepository: TmdbRepository,
) {
    private val appContext = context.applicationContext
    private val database = ILauncherDatabase.get(appContext)
    private val dao = database.epgDao()
    private val store = EpgPreferences(appContext)
    private val sourceClient = EpgSourceClient()

    private val _state = MutableStateFlow(EpgState(sourceUrl = store.sourceUrl()))
    val state: StateFlow<EpgState> = _state.asStateFlow()

    private var currentChannels: List<LiveTvChannel> = emptyList()

    suspend fun refresh(channels: List<LiveTvChannel>, force: Boolean = false) = withContext(Dispatchers.IO) {
        currentChannels = channels
        val sourceUrl = store.sourceUrl()
        if (sourceUrl.isNullOrBlank()) {
            _state.value = EpgState(sourceUrl = null, enrichedChannels = channels)
            return@withContext
        }

        val now = System.currentTimeMillis()
        val cachedMappings = loadMappings()
        val cachedProgrammes = loadCachedProgrammes(now)
        val cachedSourceChannels = store.sourceChannels()
        if (cachedSourceChannels.isNotEmpty() || cachedProgrammes.isNotEmpty()) {
            publishState(
                source = EpgSourceSnapshot(
                    sourceUrl = sourceUrl,
                    epgUrl = store.epgUrl() ?: sourceUrl,
                    channels = cachedSourceChannels,
                    updatedAtUtcMillis = store.sourceUpdatedAtUtcMillis(),
                ),
                mappings = cachedMappings,
                programmes = cachedProgrammes,
                now = now,
                isRefreshing = true,
                error = null,
            )
        } else {
            _state.update { it.copy(sourceUrl = sourceUrl, enrichedChannels = channels, isRefreshing = true, errorMessage = null) }
        }

        val cacheFresh = !force && store.xmlTvUpdatedAtUtcMillis()?.let { now - it < XMLTV_REFRESH_MILLIS } == true
        if (cacheFresh && cachedProgrammes.isNotEmpty()) {
            publishState(
                source = EpgSourceSnapshot(
                    sourceUrl = sourceUrl,
                    epgUrl = store.epgUrl() ?: sourceUrl,
                    channels = cachedSourceChannels,
                    updatedAtUtcMillis = store.sourceUpdatedAtUtcMillis(),
                ),
                mappings = cachedMappings,
                programmes = cachedProgrammes,
                now = now,
                isRefreshing = false,
                error = null,
            )
            enrichCurrentProgramsProgressively()
            return@withContext
        }

        runCatching {
            val source = sourceClient.loadSource(sourceUrl)
            store.saveSourceSnapshot(source)
            val initialMappings = EpgChannelMatcher.resolve(
                receiverChannels = channels,
                sourceChannels = source.channels,
                manualMappings = cachedMappings.filter { it.matchMethod == EpgChannelMatcher.METHOD_MANUAL },
            )
            val sourceIds = mappedSourceIds(initialMappings, source.channels)
            val programmes = sourceClient.loadXmlTv(
                epgUrl = source.epgUrl,
                allowedXmltvChannelIds = sourceIds,
                windowStartUtcMillis = now - GUIDE_PAST_MILLIS,
                windowEndUtcMillis = now + GUIDE_FUTURE_MILLIS,
            )
            val resolvedMappings = resolveAlternateIds(initialMappings, source.channels, programmes, now)
            dao.upsertMappings(resolvedMappings.map { it.toEntity(now) })
            dao.deleteOldProgrammes(now - PROGRAMME_CACHE_PAST_MILLIS)
            dao.upsertProgrammes(programmes.map { it.toEntity(now) })
            store.setXmlTvUpdatedAtUtcMillis(now)
            publishState(
                source = source,
                mappings = resolvedMappings,
                programmes = programmes,
                now = now,
                isRefreshing = false,
                error = null,
            )
        }.onFailure { throwable ->
            Log.w(TAG, "EPG refresh failed (${throwable.javaClass.simpleName})")
            val source = EpgSourceSnapshot(
                sourceUrl = sourceUrl,
                epgUrl = store.epgUrl() ?: sourceUrl,
                channels = cachedSourceChannels,
                updatedAtUtcMillis = store.sourceUpdatedAtUtcMillis(),
            )
            publishState(
                source = source,
                mappings = cachedMappings,
                programmes = cachedProgrammes,
                now = now,
                isRefreshing = false,
                error = friendlyError(throwable),
            )
        }
        enrichCurrentProgramsProgressively()
    }

    suspend fun updateSource(rawUrl: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = EpgSourceUrl.normalize(rawUrl) ?: return@withContext false
        store.setSourceUrl(normalized)
        store.clearSnapshot()
        _state.value = EpgState(sourceUrl = normalized, enrichedChannels = currentChannels)
        true
    }

    suspend fun setManualMapping(serviceReference: String, xmltvChannelId: String) = withContext(Dispatchers.IO) {
        val trimmed = xmltvChannelId.trim()
        if (trimmed.isBlank()) return@withContext
        val mapping = EpgChannelMapping(
            serviceReference = serviceReference,
            xmltvChannelId = trimmed,
            matchMethod = EpgChannelMatcher.METHOD_MANUAL,
            confidence = 1f,
        )
        dao.upsertMappings(listOf(mapping.toEntity(System.currentTimeMillis())))
        val source = EpgSourceSnapshot(
            sourceUrl = store.sourceUrl().orEmpty(),
            epgUrl = store.epgUrl() ?: store.sourceUrl().orEmpty(),
            channels = store.sourceChannels(),
            updatedAtUtcMillis = store.sourceUpdatedAtUtcMillis(),
        )
        publishState(
            source = source,
            mappings = loadMappings(),
            programmes = loadCachedProgrammes(System.currentTimeMillis()),
            now = System.currentTimeMillis(),
            isRefreshing = false,
            error = null,
        )
    }

    suspend fun enrichProgram(serviceReference: String, startUtcMillis: Long) = withContext(Dispatchers.IO) {
        val program = _state.value.guideByServiceReference[serviceReference]
            ?.firstOrNull { it.startUtcMillis == startUtcMillis }
            ?: _state.value.enrichedChannels
                .firstOrNull { it.serviceReference == serviceReference }
                ?.let { channel ->
                    listOfNotNull(channel.now, channel.next).firstOrNull { it.startUtcMillis == startUtcMillis }
                }
            ?: return@withContext
        val enriched = resolveTmdb(serviceReference, program) ?: return@withContext
        applyProgramEnrichment(serviceReference, startUtcMillis, enriched)
    }

    private suspend fun loadMappings(): List<EpgChannelMapping> = dao.mappings().map { it.toModel() }

    private suspend fun loadCachedProgrammes(now: Long): List<XmlTvProgram> {
        return dao.programmesInWindow(
            windowStartUtcMillis = now - GUIDE_PAST_MILLIS,
            windowEndUtcMillis = now + GUIDE_FUTURE_MILLIS,
        ).map { it.toModel() }
    }

    private fun mappedSourceIds(
        mappings: List<EpgChannelMapping>,
        sourceChannels: List<EpgSourceChannel>,
    ): Set<String> = buildSet {
        mappings.forEach { mapping ->
            val source = sourceChannels.firstOrNull { mapping.xmltvChannelId in it.allXmltvIds }
            if (source != null) addAll(source.allXmltvIds) else add(mapping.xmltvChannelId)
        }
    }

    private suspend fun resolveAlternateIds(
        mappings: List<EpgChannelMapping>,
        sourceChannels: List<EpgSourceChannel>,
        programmes: List<XmlTvProgram>,
        now: Long,
    ): List<EpgChannelMapping> {
        val counts = programmes.groupingBy(XmlTvProgram::xmltvChannelId).eachCount()
        val resolved = mappings.map { mapping ->
            if ((counts[mapping.xmltvChannelId] ?: 0) > 0) return@map mapping
            val source = sourceChannels.firstOrNull { mapping.xmltvChannelId in it.allXmltvIds }
                ?: return@map mapping
            val alternate = source.allXmltvIds.maxByOrNull { counts[it] ?: 0 } ?: return@map mapping
            if ((counts[alternate] ?: 0) <= 0 || alternate == mapping.xmltvChannelId) return@map mapping
            mapping.copy(
                xmltvChannelId = alternate,
                matchMethod = if (mapping.matchMethod == EpgChannelMatcher.METHOD_MANUAL) {
                    EpgChannelMatcher.METHOD_MANUAL
                } else {
                    EpgChannelMatcher.METHOD_ALT_XMLTV_ID
                },
            )
        }
        val changed = resolved.filter { resolvedMapping ->
            mappings.firstOrNull { it.serviceReference == resolvedMapping.serviceReference }
                ?.xmltvChannelId != resolvedMapping.xmltvChannelId
        }
        if (changed.isNotEmpty()) dao.upsertMappings(changed.map { it.toEntity(now) })
        return resolved
    }

    private fun publishState(
        source: EpgSourceSnapshot,
        mappings: List<EpgChannelMapping>,
        programmes: List<XmlTvProgram>,
        now: Long,
        isRefreshing: Boolean,
        error: String?,
    ) {
        val merge = EpgMerger.merge(currentChannels, mappings, programmes, now)
        val mappedRefs = mappings.map(EpgChannelMapping::serviceReference).toSet()
        val suggestions = currentChannels
            .filter { it.serviceReference !in mappedRefs }
            .associate { channel ->
                channel.serviceReference to EpgChannelMatcher.suggestions(
                    channelName = channel.name,
                    sourceChannels = source.channels,
                )
            }

        _state.value = EpgState(
            sourceUrl = source.sourceUrl,
            sourceLabel = EpgSourceUrl.label(source.sourceUrl),
            epgLabel = EpgSourceUrl.label(source.epgUrl),
            sourceChannels = source.channels,
            mappings = mappings,
            mappingSuggestions = suggestions,
            enrichedChannels = merge.channels,
            guideByServiceReference = merge.guideByServiceReference,
            isRefreshing = isRefreshing,
            lastUpdatedUtcMillis = store.xmlTvUpdatedAtUtcMillis() ?: source.updatedAtUtcMillis,
            errorMessage = error,
        )
    }

    private suspend fun enrichCurrentProgramsProgressively() {
        if (!tmdbRepository.isConfigured) return
        val candidates = _state.value.enrichedChannels
            .mapNotNull { channel -> channel.now?.let { channel.serviceReference to it } }
            .take(MAX_CURRENT_TMDB_ITEMS)
        for ((serviceReference, program) in candidates) {
            val enriched = resolveTmdb(serviceReference, program) ?: continue
            applyProgramEnrichment(serviceReference, program.startUtcMillis, enriched)
        }
    }

    private suspend fun resolveTmdb(
        serviceReference: String,
        program: LiveTvProgram,
    ): LiveTvProgram? {
        if (program.tmdbId != null) return program
        val metadata = tmdbRepository.resolve(
            sourceKey = "epg:$serviceReference:${program.startUtcMillis}",
            lookup = MediaLookup(
                rawTitle = program.title,
                typeHint = epgMediaTypeHint(program),
                releaseYear = program.releaseYear,
                seasonNumber = program.seasonNumber,
                episodeNumber = program.episodeNumber,
            ),
        ) ?: return null
        val episode = metadata.episode
        val tmdbOverview = episode?.overview ?: metadata.overview
        val tmdbYear = episode?.airYear ?: metadata.releaseYear
        return program.copy(
            subtitle = episode?.title ?: program.subtitle,
            longDescription = tmdbOverview ?: program.longDescription,
            shortDescription = tmdbOverview ?: program.shortDescription,
            releaseYear = tmdbYear ?: program.releaseYear,
            tmdbId = metadata.tmdbId,
            tmdbEpisodeId = episode?.tmdbEpisodeId,
            tmdbType = metadata.mediaType,
            tmdbTitle = metadata.title,
            tmdbOverview = tmdbOverview,
            tmdbReleaseYear = tmdbYear,
            tmdbLogoUri = metadata.logoUri,
            posterUri = metadata.posterUri,
            backdropUri = metadata.backdropUri,
            episodeStillUri = episode?.stillUri,
            voteAverage = episode?.voteAverage ?: metadata.voteAverage,
        )
    }

    private fun applyProgramEnrichment(
        serviceReference: String,
        startUtcMillis: Long,
        enriched: LiveTvProgram,
    ) {
        _state.update { current ->
            val guide = current.guideByServiceReference.toMutableMap()
            guide[serviceReference] = guide[serviceReference]
                .orEmpty()
                .map { program ->
                    if (program.startUtcMillis == startUtcMillis) enriched else program
                }
            val channels = current.enrichedChannels.map { channel ->
                if (channel.serviceReference != serviceReference) return@map channel
                channel.copy(
                    now = if (channel.now?.startUtcMillis == startUtcMillis) enriched else channel.now,
                    next = if (channel.next?.startUtcMillis == startUtcMillis) enriched else channel.next,
                )
            }
            current.copy(
                enrichedChannels = channels,
                guideByServiceReference = guide,
            )
        }
    }

    private fun resolveLinkedUrl(sourceUrl: String, rawLinkedUrl: String): String? = runCatching {
        val resolved = URI(sourceUrl).resolve(rawLinkedUrl.trim()).toString()
        EpgSourceUrl.normalize(resolved)
    }.getOrNull()

    private fun friendlyError(throwable: Throwable): String = when (throwable) {
        is EpgHttpException -> "EPG-Quelle antwortet mit HTTP ${throwable.statusCode}."
        is UnknownHostException -> "EPG-Hostname konnte nicht aufgelöst werden."
        is ConnectException -> "EPG-Quelle ist nicht erreichbar."
        is SocketTimeoutException -> "Zeitüberschreitung beim Laden der EPG-Daten."
        is SSLException -> "HTTPS-Verbindung zur EPG-Quelle konnte nicht aufgebaut werden."
        is ZipException -> "EPG-GZIP-Datei konnte nicht entpackt werden."
        is SAXException -> "XMLTV-Datei ist nicht gültig oder nicht unterstützt."
        else -> "EPG-Aktualisierung fehlgeschlagen (${throwable.javaClass.simpleName})."
    }

    private fun EpgChannelMappingEntity.toModel() = EpgChannelMapping(
        serviceReference = serviceReference,
        xmltvChannelId = xmltvChannelId,
        matchMethod = matchMethod,
        confidence = confidence,
    )

    private fun EpgChannelMapping.toEntity(now: Long) = EpgChannelMappingEntity(
        serviceReference = serviceReference,
        xmltvChannelId = xmltvChannelId,
        matchMethod = matchMethod,
        confidence = confidence,
        updatedAtUtcMillis = now,
    )

    private fun EpgProgramEntity.toModel() = XmlTvProgram(
        xmltvChannelId = xmltvChannelId,
        startUtcMillis = startUtcMillis,
        endUtcMillis = endUtcMillis,
        title = title,
        subtitle = subtitle,
        description = description,
        categories = categories?.split(CATEGORY_SEPARATOR)?.filter(String::isNotBlank).orEmpty(),
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        releaseYear = releaseYear,
        imageUri = imageUri,
    )

    private fun XmlTvProgram.toEntity(now: Long) = EpgProgramEntity(
        programKey = "$xmltvChannelId:$startUtcMillis",
        xmltvChannelId = xmltvChannelId,
        startUtcMillis = startUtcMillis,
        endUtcMillis = endUtcMillis,
        title = title,
        subtitle = subtitle,
        description = description,
        categories = categories.takeIf { it.isNotEmpty() }?.joinToString(CATEGORY_SEPARATOR),
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        releaseYear = releaseYear,
        imageUri = imageUri,
        updatedAtUtcMillis = now,
    )

    companion object {
        private const val XMLTV_REFRESH_MILLIS = 6L * 60L * 60L * 1_000L
        private const val GUIDE_PAST_MILLIS = 6L * 60L * 60L * 1_000L
        private const val GUIDE_FUTURE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        private const val PROGRAMME_CACHE_PAST_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MAX_CURRENT_TMDB_ITEMS = 12
        private const val CATEGORY_SEPARATOR = "\u001F"
    }
}
