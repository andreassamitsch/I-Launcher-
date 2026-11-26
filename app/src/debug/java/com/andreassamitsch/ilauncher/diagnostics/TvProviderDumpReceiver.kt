package com.andreassamitsch.ilauncher.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.tv.TvContract
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug-only, DUMP-permission-protected, explicit ADB snapshot. No UI, URLs or network. */
class TvProviderDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val report = snapshot(app)
                val target = AtomicFile(File(app.filesDir, FILE))
                val output = target.startWrite()
                try {
                    output.write(report.toString(2).toByteArray(Charsets.UTF_8))
                    target.finishWrite(output)
                } catch (error: Exception) {
                    target.failWrite(output)
                    throw error
                }
                pending.resultCode = 1
                pending.resultData = FILE
                Log.i(TAG, "Local TV provider snapshot ready")
            } catch (error: Exception) {
                pending.resultCode = -1
                pending.resultData = error.javaClass.simpleName
                Log.w(TAG, "TV provider snapshot failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun snapshot(context: Context): JSONObject {
        val resolver = context.contentResolver
        val errors = JSONArray()
        fun query(label: String, uri: android.net.Uri, columns: Array<String>, order: String? = null): List<JSONObject> {
            return try {
                val cursor = resolver.query(uri, columns, null, null, order)
                    ?: error("Null cursor")
                cursor.use { rows ->
                    buildList {
                        while (rows.moveToNext()) add(rows.toSafeJson(columns))
                    }
                }
            } catch (error: Exception) {
                errors.put(JSONObject().put("scope", label).put("error", error.javaClass.simpleName))
                emptyList()
            }
        }

        val channelColumns = arrayOf("_id", "package_name", "display_name", "type", "browsable")
        val programColumns = arrayOf(
            "_id", "package_name", "title", "release_date", "type",
            "season_display_number", "episode_display_number", "episode_title", "duration_millis",
        )
        val previewColumns = programColumns + arrayOf("weight", "browsable")
        val watchColumns = programColumns + arrayOf(
            "watch_next_type", "last_engagement_time_utc_millis", "last_playback_position_millis",
        )
        val channels = query("channels", TvContract.Channels.CONTENT_URI, channelColumns)
            .filter { it.optString("type") == TvContract.Channels.TYPE_PREVIEW }
        val channelReports = JSONArray()
        channels.forEach { channel ->
            val id = channel.optLong("_id", -1L)
            if (id < 0) return@forEach
            val programs = query("preview:$id", TvContract.buildPreviewProgramsUriForChannel(id), previewColumns, "weight DESC")
            channel.put("programs", JSONArray(programs))
            channel.put("program_count", programs.size)
            channel.put("possible_duplicate_groups", duplicateCandidates(programs))
            channelReports.put(channel)
        }
        val watch = query("watch_next", TvContract.WatchNextPrograms.CONTENT_URI, watchColumns, "last_engagement_time_utc_millis DESC")
        return JSONObject()
            .put("schema_version", 1)
            .put("captured_at_utc_millis", System.currentTimeMillis())
            .put("preview_channels", channelReports)
            .put("watch_next", JSONArray(watch))
            .put("watch_next_possible_duplicate_groups", duplicateCandidates(watch))
            .put("errors", errors)
            .put("complete", errors.length() == 0)
    }

    private fun Cursor.toSafeJson(columns: Array<String>): JSONObject = JSONObject().apply {
        columns.forEach { column ->
            val index = getColumnIndex(column)
            val value = if (index < 0 || isNull(index)) JSONObject.NULL else when (getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_STRING -> getString(index)
                else -> JSONObject.NULL
            }
            put(column, value)
        }
    }

    // Same-title/same-episode rows are candidates only, not a deletion or deduplication policy.
    private fun duplicateCandidates(rows: List<JSONObject>): JSONArray {
        val groups = rows.filter { !it.isNull("title") && it.optString("title").isNotBlank() }.groupBy {
            listOf(
                it.optString("title").trim().lowercase(java.util.Locale.ROOT),
                it.optString("season_display_number"),
                it.optString("episode_display_number"),
            )
        }
        return JSONArray(groups.values.filter { it.size > 1 }.map { group ->
            JSONObject().put("title", group.first().optString("title"))
                .put("rows", JSONArray(group.map { it.optLong("_id") }))
        })
    }

    private companion object {
        const val ACTION = "com.andreassamitsch.ilauncher.action.DUMP_TV_PROVIDER"
        const val FILE = "tvprovider-diagnostic.json"
        const val TAG = "TV_PROVIDER_DUMP"
    }
}
