from pathlib import Path


def patch(path, old, new):
    p = Path(path)
    source = p.read_text()
    count = source.count(old)
    if count != 1:
        raise AssertionError(f'{path}: expected exactly one occurrence, found {count}: {old[:100]!r}')
    p.write_text(source.replace(old, new, 1))

app = 'app/src/main/java/com/andreassamitsch/ilauncher/'
joyn = 'joyntv/src/main/java/com/andreassamitsch/joyntv/'

patch(app + 'model/MediaItem.kt',
      '    val resolverConfidence: Float? = null,\n) {',
      '    val resolverConfidence: Float? = null,\n    /** Android TV CONTINUE, NEXT or NEW; null for non-Watch-Next media. */\n    val watchNextType: Int? = null,\n) {')
patch(app + 'data/tv/WatchNextMediaMapper.kt',
      '            lastEngagementTimeUtcMillis = item.lastEngagementTimeUtcMillis,\n',
      '            lastEngagementTimeUtcMillis = item.lastEngagementTimeUtcMillis,\n            watchNextType = item.watchNextType,\n')

card = app + 'ui/components/WatchNextCard.kt'
patch(card, 'import android.view.KeyEvent as AndroidKeyEvent\n',
      'import android.view.KeyEvent as AndroidKeyEvent\nimport android.media.tv.TvContract\n')
patch(card, 'import androidx.compose.foundation.layout.width\n',
      'import androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.shape.RoundedCornerShape\nimport androidx.compose.ui.draw.clip\n')
patch(card,
      '    val artwork = artworkOverrideUri ?: item.preferredArtworkUri\n',
      '    val artwork = artworkOverrideUri ?: item.preferredArtworkUri\n    val statusBadge = watchNextBadgeLabel(item.watchNextType)\n')
patch(card,
      '''                    item.progressFraction?.let { progress ->
                        Box(''',
      '''                    // NEXT and NEW start at zero, and must never inherit a stale resume bar.
                    item.progressFraction?.takeIf { item.watchNextType != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT &&
                        item.watchNextType != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW }?.let { progress ->
                        Box(''')
patch(card,
      '''                    item.logoUri?.takeIf { it.isNotBlank() }?.let { logoUri ->''',
      '''                    statusBadge?.let { status ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1,
                            )
                        }
                    }

                    item.logoUri?.takeIf { it.isNotBlank() }?.let { logoUri ->''')
with Path(card).open('a') as out:
    out.write('''
/** Provider type, not a guess based on an air date or a zero playback position. */
internal fun watchNextBadgeLabel(type: Int?): String? = when (type) {
    TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW -> "Neue Folge"
    TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT -> "Nächste Folge"
    else -> null
}
''')
patch(app + 'ui/home/HomeScreen.kt',
      'import com.andreassamitsch.ilauncher.ui.components.WatchNextCard\n',
      'import com.andreassamitsch.ilauncher.ui.components.WatchNextCard\nimport com.andreassamitsch.ilauncher.ui.components.watchNextBadgeLabel\n')
patch(app + 'ui/home/HomeScreen.kt',
      '''    val metadata = buildList {
        when (item.type) {''',
      '''    val metadata = buildList {
        watchNextBadgeLabel(item.watchNextType)?.let(::add)
        when (item.type) {''')

next_file = joyn + 'JoynNextEpisode.kt'
patch(next_file,
      '    val announcedVideoId: String? = null,\n)',
      '''    val announcedVideoId: String? = null,
    /** A prior catalogue check saw no available follow-up; not inferred from TMDB air_date. */
    val observedWithoutNext: Boolean = false,
    val announcedAsNew: Boolean = false,
)''')
patch(next_file,
      '''    fun markAnnounced(seriesKey: String, videoId: String?) {
        val current = items().map {
            if (it.seriesKey == seriesKey) it.copy(announcedVideoId = videoId) else it
        }
        persist(current)
    }''',
      '''    fun markAnnounced(seriesKey: String, videoId: String?, isNew: Boolean = false) {
        val current = items().map {
            if (it.seriesKey == seriesKey) it.copy(
                announcedVideoId = videoId,
                announcedAsNew = isNew,
                observedWithoutNext = false,
            ) else it
        }
        persist(current)
    }

    @Synchronized
    fun markNoNextAvailable(seriesKey: String) {
        val current = items().map {
            if (it.seriesKey == seriesKey) it.copy(
                announcedVideoId = null,
                announcedAsNew = false,
                observedWithoutNext = true,
            ) else it
        }
        persist(current)
    }''')
patch(next_file,
      '                        .putNullable("announcedVideoId", item.announcedVideoId),',
      '''                        .putNullable("announcedVideoId", item.announcedVideoId)
                        .put("observedWithoutNext", item.observedWithoutNext)
                        .put("announcedAsNew", item.announcedAsNew),''')
patch(next_file,
      '                            announcedVideoId = json.nullableString("announcedVideoId"),',
      '''                            announcedVideoId = json.nullableString("announcedVideoId"),
                            observedWithoutNext = json.optBoolean("observedWithoutNext", false),
                            announcedAsNew = json.optBoolean("announcedAsNew", false),''')
patch(next_file,
      '''                    if (state.announcedVideoId != null) {
                        publisher.removeNextEpisode(state.seriesKey)
                        store.markAnnounced(state.seriesKey, null)
                    }''',
      '''                    if (state.announcedVideoId != null) {
                        publisher.removeNextEpisode(state.seriesKey)
                    }
                    store.markNoNextAvailable(state.seriesKey)''')
patch(next_file,
      '''                        publisher.publishNextEpisode(state.seriesKey, enriched)
                        store.markAnnounced(state.seriesKey, nextVideoId)''',
      '''                        val newlyAvailable = JoynNextEpisodePolicy.isNewlyAvailable(state)
                        publisher.publishNextEpisode(state.seriesKey, enriched, isNew = newlyAvailable)
                        store.markAnnounced(state.seriesKey, nextVideoId, isNew = newlyAvailable)''')
patch(next_file,
      '''internal object JoynNextEpisodePolicy {
    fun nextInSeason(''',
      '''internal object JoynNextEpisodePolicy {
    /** Only a previously observed absence permits a NEW label on a subsequent catalogue find. */
    fun isNewlyAvailable(state: JoynCompletedSeries): Boolean =
        state.observedWithoutNext && state.announcedVideoId == null

    fun nextInSeason(''')

pub = joyn + 'JoynContinueWatching.kt'
patch(pub,
      '    fun publishNextEpisode(seriesKey: String, media: JoynMediaItem) {',
      '    fun publishNextEpisode(seriesKey: String, media: JoynMediaItem, isNew: Boolean = false) {')
patch(pub,
      '            if (rowId(storageKey) != null) publishNextEpisode(seriesKey, media)',
      '            if (rowId(storageKey) != null) publishNextEpisode(seriesKey, media, isNew = isNew)')
patch(pub,
      '            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)',
      '''            .setWatchNextType(
                if (isNew) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW
                else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
            )''')

print('Guarded migration applied: shared Watch Next badge, true NEW availability, preserved resume states.')
