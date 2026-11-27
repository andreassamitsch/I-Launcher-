package com.lagradost.cloudstream3

import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects

/** Direct playback bypasses ResultViewModel2.load(), but must write its native resume header. */
internal object ILauncherDirectResumeHeader {
    private const val TAG = "ILauncherResumeHeader"

    internal fun build(
        parentId: Int, responseId: Int, provider: String, url: String,
        type: TvType, title: String, poster: String?, now: Long,
    ): DownloadObjects.DownloadHeaderCached? {
        // Use the exact same provider parent key as the player's resume record.
        if (parentId != responseId || provider.isBlank() || url.isBlank() || title.isBlank()) return null
        return DownloadObjects.DownloadHeaderCached(
            apiName = provider,
            url = url,
            type = type,
            name = title,
            poster = poster,
            id = parentId,
            cacheTime = now,
        )
    }

    fun persist(parentId: Int, response: LoadResponse) {
        val header = build(
            parentId, response.getId(), response.apiName, response.url,
            response.type, response.name, response.posterUrl, System.currentTimeMillis(),
        )
        if (header == null) {
            Log.w(TAG, "resume header rejected parent=$parentId")
            return
        }
        // Never set the resume pointer or position here. Only actual playback does that.
        setKey(DOWNLOAD_HEADER_CACHE, parentId.toString(), header)
        ILauncherSeriesIdentity.remember(parentId, response)
        Log.i(TAG, "resume header saved parent=$parentId provider=${header.apiName}")
    }
}
