package local.voice2text.persiandictation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the diagnostics zip to the app the user shares it with (read-only, by temporary grant).
 * A minimal stand-in for AndroidX's FileProvider, which this project doesn't use.
 */
class DiagnosticsProvider : ContentProvider() {
    companion object {
        fun directory(context: Context) = File(context.cacheDir, "diagnostics")
        fun uriFor(context: Context, file: File): Uri =
            Uri.parse("content://${context.packageName}.diagnostics/${Uri.encode(file.name)}")
    }

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
        val file = File(directory(context!!), name)
        if (file.parentFile != directory(context!!) || !file.isFile) throw FileNotFoundException(uri.toString())
        return file
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/zip"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
