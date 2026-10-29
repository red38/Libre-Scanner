package com.red38.librescanner.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.red38.librescanner.vision.DocumentFilter
import com.red38.librescanner.vision.Pt
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Página escaneada: se guarda como JPEG comprimido (no Bitmap) para no
 * reventar la memoria con varias páginas; se decodifica bajo demanda.
 */
class PageItem(data: ByteArray, initialQuad: List<Offset>?) {
    /** JPEG de la imagen ORIGINAL (vigente tras rotar) */
    var data: ByteArray by mutableStateOf(data)

    /** cuadrilátero normalizado 0..1 en orden TL,TR,BR,BL (null = imagen completa) */
    var quad: List<Offset>? by mutableStateOf(initialQuad)

    var filter: DocumentFilter by mutableStateOf(DocumentFilter.MAGIC)
}

object ScanSession {
    fun encode(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }.toByteArray()

    fun decode(data: ByteArray, maxSide: Int = 2000): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    /** decodifica un JPEG del disco a un tamaño máximo dado (para miniaturas) */
    fun decodeFile(path: String, maxSide: Int = 280): Bitmap? =
        runCatching { decode(File(path).readBytes(), maxSide) }.getOrNull()

    /** rota el bitmap en pasos de 90° horario (steps puede ser negativo) */
    fun rotate(bitmap: Bitmap, steps: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(((steps % 4 + 4) % 4) * 90f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** quad normalizado → coordenadas de píxel, en orden TL,TR,BR,BL */
    fun toPixelQuad(quad: List<Offset>, width: Int, height: Int): List<Pt> =
        quad.map { Pt(it.x.toDouble() * width, it.y.toDouble() * height) }

    /** Pt en píxeles → quad normalizado 0..1 */
    fun normalizeQuad(points: List<Pt>, bitmap: Bitmap): List<Offset> = points.map {
        Offset(
            (it.x / bitmap.width).toFloat().coerceIn(0f, 1f),
            (it.y / bitmap.height).toFloat().coerceIn(0f, 1f),
        )
    }

    /**
     * Rota el quad normalizado junto con la imagen: steps pasos de 90° horario.
     * (x,y) → 90°: (1-y, x) · 180°: (1-x, 1-y) · 270°: (y, 1-x)
     */
    fun transformQuad(quad: List<Offset>, steps: Int): List<Offset> =
        quad.map { p ->
            when ((steps % 4 + 4) % 4) {
                1 -> Offset(1f - p.y, p.x)
                2 -> Offset(1f - p.x, 1f - p.y)
                3 -> Offset(p.y, 1f - p.x)
                else -> p
            }
        }
}
