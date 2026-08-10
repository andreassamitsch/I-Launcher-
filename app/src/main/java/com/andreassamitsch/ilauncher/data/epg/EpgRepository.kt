package com.andreassamitsch.ilauncher.data.epg

import android.content.Context
import com.andreassamitsch.ilauncher.data.database.EpgChannelMappingEntity
import com.andreassamitsch.ilauncher.data.database.EpgProgramEntity
import com.andreassamitsch.ilauncher.data.database.ILauncherDatabase
import com.andreassamitsch.ilauncher.data.tmdb.MediaLookup
import com.andreassamitsch.ilauncher.data.tmdb.TmdbRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xml.sax.SAXException

internal fun epgMediaTypeHint(program: LiveTvProgram): MediaType {
    if (program.seasonNumber != null && program.episodeNumber != null) return MediaType.Episode
    val categories = program.categories.orEmpty()
        .joinToString(" ")
        .let(EpgChannelMatcher::normalizeName)
    return when {
        listOf("spielfilm", "film", "movie").any(categories::contains) -> MediaType.Movie
        listOf("serie", "series", "soap", "sitcom").any(categories::contains) -> MediaType.Series
        // A missing/weak XMLTV category must not suppress lookup completely. The existing TMDB
        // multi-search + confidence threshold remains the authority for whether a match is safe.
        else -> MediaType.Unknown
    }
}

class EpgRepository(
    context: Context,
    private val tmdbRepository: TmdbRepository,
) {
    private val appContext = context.applicationContext
    private val store = EpgStore(appContext)
    private val dao = ILauncherDatabase.get(appContext).epgDao()
    private val network = EpgNetworkClient()
    private val refreshMutex = Mutex()
    private var currentChannels: List<LiveTvChannel> = emptyList()

    private val _state = MutableStateFlow(
        EpgState(
            sourceUrl = store.sourceUrl(),
            sourceLabel = EpgSourceUrl.label(store.sourceUrl()),
        ),
    )
    val state: StateFlow<EpgState> = _state.asStateFlow()

    suspend fun updateSource(rawUrl: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = EpgSourceUrl.normalize(rawUrl)
        if (normalized == null) {
            _state.update { it.copy(errorMessage = "Ungültige EPG-M3U-Adresse. HTTP oder HTTPS ohne eingebettete Zugangsdaten verwenden.") }
            return@withContext false
        }

        store.saveSourceUrl(normalized)
        dao.deleteAllMappings()
        dao.deleteAllPrograms()
        _state.value = EpgState(
            sourceUrl = normalized,
            sourceLabel = EpgSourceUrl.label(normalized),
        )
        true
    }

    suspend fun setManualMapping(serviceReference: String, xmltvChannelId: String?) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (xmltvChannelId.isNullOrBlank()) {
                dao.deleteMapping(serviceReference)
            } else {
                dao.upsertMapping(
                    EpgChannelMappingEntity(
                        serviceReference = serviceReference,
                        xmltvChannelId = xmltvChannelId,
                        matchMethod = EpgChannelMatcher.METHOD_MANUAL,
                        confidence = 1f,
                        updatedAtUtcMillis = now,
                    ),
                )
            }
            val source = store.loadSourceSnapshot()
            if (source != null) {
                val mappings = refreshMappings(currentChannels, source.channels, now)
                val programmes = loadCachedProgrammes(mappings, now)
                publishState(source, mappings, programmes, now, isRefreshing = false, error = null)
            }
        }

    suspend fun refresh(channels: List<LiveTvChannel>, force: Boolean = false) =
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                currentChannels = channels
                val now = System.currentTimeMillis()
                val sourceUrl = store.sourceUrl()
                _state.update {
                    it.copy(
                        sourceUrl = sourceUrl,
                        sourceLabel = EpgSourceUrl.label(sourceUrl),
                        isRefreshing = channels.isNotEmpty(),
                        errorMessage = null,
                    )
                }

                if (channels.isEmpty()) {
                    _state.update { it.copy(isRefreshing = false) }
                    return@withLock
                }

                var cachedPublished = false
                try {
                    var source = store.loadSourceSnapshot()
                    if (source != null) {
                        val cachedMappings = refreshMappings(channels, source.channels, now)
                        val cachedProgrammes = loadCachedProgrammes(cachedMappings, now)
                        if (cachedProgrammes.isNotEmpty()) {
                            publishState(
                                source = source,
                                mappings = cachedMappings,
                                programmes = cachedProgrammes,
                                now = now,
                                isRefreshing = true,
                                error = null,
                            )
                            cachedPublished = true
                        }
                    }

                    var sourceWasRefreshed = false
                    val sourceStale = force ||
                        source == null ||
                        now - (source?.updatedAtUtcMillis ?: 0L) >= SOURCE_REFRESH_INTERVAL_MILLIS
                    if (sourceStale) {
                        val parsed = M3uEpgParser.parse(network.loadM3u(sourceUrl))
                        val rawEpgUrl = parsed.epgUrl?.takeIf(String::isNotBlank)
                            ?: error("M3U enthält keine x-tvg-url EPG-Quelle")
                        val resolvedEpgUrl = resolveLinkedUrl(sourceUrl, rawEpgUrl)
                            ?: error("M3U enthält eine ungültige EPG-URL")
                        check(parsed.channels.isNotEmpty()) { "M3U enthält keine EPG-Sender-IDs" }
                        source = EpgSourceSnapshot(
                            sourceUrl = sourceUrl,
                            epgUrl = resolvedEpgUrl,
                            channels = parsed.channels,
                            updatedAtUtcMillis = now,
                        )
                        store.saveSourceSnapshot(source)
                        sourceWasRefreshed = true
                    }

                    val activeSource = requireNotNull(source)
                    var mappings = refreshMappings(channels, activeSource.channels, now)
                    var programmes = loadCachedProgrammes(mappings, now)
                    val interestedIds = mappedSourceIds(mappings, activeSource.channels)
                    val coveredIds = store.xmlTvChannelIds()
                    val xmlTvUpdatedAt = store.xmlTvUpdatedAtUtcMillis()
                    val coverageMissing = interestedIds.any { it !in coveredIds }
                    val xmlTvStale = force ||
                        sourceWasRefreshed ||
                        coverageMissing ||
                        xmlTvUpdatedAt == null ||
                        now - xmlTvUpdatedAt >= XMLTV_REFRESH_INTERVAL_MILLIS

                    if (xmlTvStale && interestedIds.isNotEmpty()) {
                        val parsedProgrammes = network.readXmlTv(activeSource.epgUrl) { input ->
                            XmlTvParser.parse(
                                input = input,
                                interestedChannelIds = interestedIds,
                                windowStartUtcMillis = now - GUIDE_PAST_MILLIS,
                                windowEndUtcMillis = now + GUIDE_FUTURE_MILLIS,
                            )
                        }
                        mappings = resolveAlternateIds(
                            mappings = mappings,
                            sourceChannels = activeSource.channels,
                            programmes = parsedProgrammes,
                            now = now,
                        )
                        dao.deleteAllPrograms()
                        if (parsedProgrammes.isNotEmpty()) {
                            dao.upsertPrograms(parsedProgrammes.map { it.toEntity(now) })
                        }
                        store.saveXmlTvUpdatedAtUtcMillis(now)
                        store.saveXmlTvChannelIds(interestedIds)
                        programmes = parsedProgrammes
                    }

                    publishState(
                        source = activeSource,
                        mappings = mappings,
                        programmes = programmes,
                        now = now,
                        isRefreshing = false,
                        error = null,
                    )
                    enrichCurrentProgramsProgressively()
                } catch (throwable: Throwable) {
                    _state.update { current ->
                        current.copy(
                            isRefreshing = false,
                            errorMessage = friendlyError(throwable),
                        )
                    }
                    if (cachedPublished) enrichCurrentProgramsProgressively()
                }
            }
        }

    suspend fun enrichProgram(serviceReference: String, startUtcMillis: Long) =
        withContext(Dispatchers.IO) {
            val program = _state.value.guideByServiceReference[serviceReference]
                ?.firstOrNull { it.startUtcMillis == startUtcMillis }
                ?: return@withContext
            val enriched = resolveTmdb(serviceReference, program) ?: return@withContext
            applyProgramEnrichment(serviceReference, program.startUtcMillis, enriched)
        }

    private suspend fun refreshMappings(
        channels: List<LiveTvChannel>,
        sourceChannels: List<EpgSourceChannel>,
        now: Long,
    ): List<EpgChannelMapping> {
        val currentRefs = channels.map(LiveTvChannel::serviceReference).toSet()
        val existing = dao.mappings().map { it.toModel() }
        val manual = existing.filter { mapping ->
            mapping.matchMethod == EpgChannelMatcher.METHOD_MANUAL &&
                mapping.serviceReference in currentRefs &&
                sourceChannels.any { mapping.xmltvChannelId in it.allXmltvIds }
        }
        dao.deleteAutomaticMappings()
        val automatic = EpgChannelMatcher.autoMappings(channels, sourceChannels, manual)
        if (automatic.isNotEmpty()) {
            dao.upsertMappings(automatic.map { it.toEntity(now) })
        }
        return dao.mappings()
            .map { it.toModel() }
            .filter { it.serviceReference in currentRefs }
    }

    private suspend fun loadCachedProgrammes(
        mappings: List<EpgChannelMapping>,
        now: Long,
    ): List<XmlTvProgram> {
        val ids = mappings.map(EpgChannelMapping::xmltvChannelId).distinct()
        if (ids.isEmpty()) return emptyList()
        return dao.programs(
            channelIds = ids,
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
        return program.copy(
            subtitle = program.subtitle ?: episode?.title,
            longDescription = program.longDescription ?: episode?.overview ?: metadata.overview,
            shortDescription = program.shortDescription ?: episode?.overview ?: metadata.overview,
            tmdbId = metadata.tmdbId,
            tmdbEpisodeId = episode?.tmdbEpisodeId,
            tmdbType = metadata.mediaType,
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

    private fun XmlTvProgram.toEntity(now: Long) = EpgProgramEntity(
        programKey = "$xmltvChannelId|$startUtcMillis",
        xmltvChannelId = xmltvChannelId,
        startUtcMillis = startUtcMillis,
        stopUtcMillis = stopUtcMillis,
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

    private fun EpgProgramEntity.toModel() = XmlTvProgram(
        xmltvChannelId = xmltvChannelId,
        startUtcMillis = startUtcMillis,
        stopUtcMillis = stopUtcMillis,
        title = title,
        subtitle = subtitle,
        description = description,
        categories = categories?.split(CATEGORY_SEPARATOR).orEmpty().filter(String::isNotBlank),
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        releaseYear = releaseYear,
        imageUri = imageUri,
    )

    companion object {
        private const val CATEGORY_SEPARATOR = "\u001F"
        private const val SOURCE_REFRESH_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
        private const val XMLTV_REFRESH_INTERVAL_MILLIS = 6L * 60L * 60L * 1_000L
        private const val GUIDE_PAST_MILLIS = 6L * 60L * 60L * 1_000L
        private const val GUIDE_FUTURE_MILLIS = 72L * 60L * 60L * 1_000L
        private const val MAX_CURRENT_TMDB_ITEMS = 12
    }
}
