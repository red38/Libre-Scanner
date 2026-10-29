package com.red38.librescanner.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File

object Sharing {
    private fun sharedDir(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs() }

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun share(context: Context, bitmap: Bitmap) {
        val file = File(sharedDir(context), "escaneo_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        shareUri(context, uriFor(context, file), "image/jpeg")
    }

    /** comparte todas las páginas como imágenes (ACTION_SEND_MULTIPLE) */
    fun shareImages(context: Context, bitmaps: List<Bitmap>, name: String?) {
        if (bitmaps.isEmpty()) return
        if (bitmaps.size == 1) {
            share(context, bitmaps.first())
            return
        }
        val dir = sharedDir(context)
        val stamp = System.currentTimeMillis()
        val base = safeName(name)
        val uris = ArrayList(
            bitmaps.mapIndexed { i, bitmap ->
                val file = File(dir, "${base}_${stamp}_${i + 1}.jpg")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                uriFor(context, file)
            },
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    /** comparte un archivo ya generado (p. ej. el PDF) */
    fun shareFile(context: Context, file: File, mimeType: String) {
        shareUri(context, uriFor(context, file), mimeType)
    }

    private fun shareUri(context: Context, uri: android.net.Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    private fun safeName(name: String?): String =
        (name ?: "escaneo")
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
            .ifEmpty { "escaneo" }
            .take(40)
}
