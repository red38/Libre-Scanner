package com.red38.librescanner.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Guarda archivos en la carpeta pública Descargas (PDF) o Imágenes (JPEG) con
 * MediaStore en Android 10+ y con el almacenamiento compartido en Android 8/9.
 */
object MediaStoreSaver {
    /** guarda el PDF en Descargas; devuelve la URI pública o null */
    fun savePdf(context: Context, file: File, displayName: String): Uri? {
        val name = displayName.ensureExtension(".pdf")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insert(
                context,
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                relativePath = Environment.DIRECTORY_DOWNLOADS,
                mimeType = "application/pdf",
                name = name,
                write = { out -> file.inputStream().use { it.copyTo(out) } },
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            copyToPublic(context, dir, name, "application/pdf") { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        }
    }

    /** guarda la imagen en Imágenes (Pictures/Libre Scanner); devuelve la URI o null */
    fun saveImage(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val name = displayName.ensureExtension(".jpg")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insert(
                context,
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                relativePath = "${Environment.DIRECTORY_PICTURES}/Libre Scanner",
                mimeType = "image/jpeg",
                name = name,
                write = { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) },
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Libre Scanner",
            )
            copyToPublic(context, dir, name, "image/jpeg") { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        }
    }

    private fun insert(
        context: Context,
        collection: Uri,
        relativePath: String,
        mimeType: String,
        name: String,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use(write)
                ?: throw IllegalStateException("openOutputStream devolvió null")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun copyToPublic(
        context: Context,
        dir: File,
        name: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? = runCatching {
        dir.mkdirs()
        val out = File(dir, name)
        out.outputStream().use(write)
        MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf(mimeType), null)
        Uri.fromFile(out)
    }.getOrNull()

    private fun String.ensureExtension(extension: String): String =
        if (endsWith(extension, ignoreCase = true)) this else this + extension
}
