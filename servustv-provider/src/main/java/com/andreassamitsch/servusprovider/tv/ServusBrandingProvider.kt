package com.andreassamitsch.servusprovider.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.andreassamitsch.servusprovider.R
import java.io.FileNotFoundException

/**
 * Read-only bridge for branding assets referenced from Android TvProvider rows.
 *
 * TvProvider consumers run in another process/package. An android.resource:// URI therefore is not
 * a reliable contract for our private resources. This provider exposes only the public 90-second
 * logo as image/png and keeps the asset local/offline.
 */
class ServusBrandingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        if (uri.isNews90Logo()) MIME_PNG else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || !uri.isNews90Logo()) {
            throw FileNotFoundException(uri.toString())
        }
        val providerContext = context ?: throw FileNotFoundException("Provider context unavailable")
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    providerContext.resources.openRawResource(R.drawable.servus_news_90_logo).use { input ->
                        input.copyTo(output)
                    }
                }
            }.onFailure {
                runCatching { writeSide.close() }
            }
        }, "ServusBrandingProvider").apply {
            isDaemon = true
            start()
        }
        return readSide
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun Uri.isNews90Logo(): Boolean = pathSegments.singleOrNull() == NEWS_90_LOGO_PATH

    private companion object {
        const val NEWS_90_LOGO_PATH = "servus_news_90_logo.png"
        const val MIME_PNG = "image/png"
    }
}
