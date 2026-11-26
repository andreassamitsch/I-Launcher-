package com.andreassamitsch.ilauncher.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read the existing on-demand snapshot from an optimized non-debuggable development APK.
 * No collection, directory traversal, mutation, network or access by ordinary applications.
 * Manifest DUMP permission plus explicit shell/root UID enforcement.
 */
class TvProviderSnapshotProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val uid = Binder.getCallingUid()
        if (uid != 2000 && uid != 0) throw SecurityException("ADB shell only")
        if (mode != "r" || uri.path != "/snapshot") {
            throw FileNotFoundException("Read-only snapshot")
        }
        val app = context ?: throw FileNotFoundException("No context")
        return ParcelFileDescriptor.open(
            File(app.filesDir, "tvprovider-diagnostic.json"),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
}
