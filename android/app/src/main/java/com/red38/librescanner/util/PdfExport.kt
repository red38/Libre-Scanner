package com.red38.librescanner.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

/**
 * Exportación a PDF A4 (595×842 pt) con margen de 24 pt, equivalente a
 * `src/pdf.ts` de la versión web: cada página se embebe como JPEG
 * (`/DCTDecode`) sin recomprimirla por el motor PDF, así el archivo pesa
 * cientos de KB por página en vez de varios MB.
 *
 * Se escribe el PDF a mano porque `android.graphics.pdf.PdfDocument` (Skia)
 * siempre comprime las imágenes con Flate.
 */
object PdfExport {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 24f
    private const val JPEG_QUALITY = 85

    /** crea el PDF en cacheDir/exports y devuelve el archivo (null si falla) */
    fun create(context: Context, pages: List<Bitmap>, name: String?): File? = runCatching {
        require(pages.isNotEmpty())

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${safeName(name)}_${System.currentTimeMillis()}.pdf")
        file.writeBytes(build(pages, name))
        file
    }.getOrNull()

    private fun build(pages: List<Bitmap>, name: String?): ByteArray {
        val count = pages.size
        // objetos: 1 catálogo, 2 páginas, 3..3+N-1 páginas, luego contenido, luego imágenes, luego info
        val pageObj = { i: Int -> 3 + i }
        val contentObj = { i: Int -> 3 + count + i }
        val imageObj = { i: Int -> 3 + 2 * count + i }
        val infoObj = 3 + 3 * count
        val totalObjects = infoObj

        val out = ByteArrayOutputStream(64 * 1024)
        val offsets = IntArray(totalObjects + 1)

        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        fun begin(id: Int) {
            offsets[id] = out.size()
            write("$id 0 obj\n")
        }
        fun end() = write("endobj\n")

        write("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")

        begin(1)
        write("<< /Type /Catalog /Pages 2 0 R >>\n")
        end()

        begin(2)
        write("<< /Type /Pages /Kids [")
        for (i in 0 until count) write("${pageObj(i)} 0 R ")
        write("] /Count $count >>\n")
        end()

        val images = pages.map { toOpaqueJpeg(it) }

        for (i in 0 until count) {
            val (bytes, width, height) = images[i]
            val scale = min(
                (PAGE_W - MARGIN * 2) / width.toFloat(),
                (PAGE_H - MARGIN * 2) / height.toFloat(),
            )
            val drawW = width * scale
            val drawH = height * scale
            val x = (PAGE_W - drawW) / 2f
            val y = (PAGE_H - drawH) / 2f

            begin(pageObj(i))
            write(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_W $PAGE_H] " +
                    "/Resources << /XObject << /Im0 ${imageObj(i)} 0 R >> >> " +
                    "/Contents ${contentObj(i)} 0 R >>\n",
            )
            end()

            val content = "q\n${fmt(drawW)} 0 0 ${fmt(drawH)} ${fmt(x)} ${fmt(y)} cm\n/Im0 Do\nQ\n"
            val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
            begin(contentObj(i))
            write("<< /Length ${contentBytes.size} >>\nstream\n")
            out.write(contentBytes)
            write("endstream\n")
            end()

            begin(imageObj(i))
            write(
                "<< /Type /XObject /Subtype /Image /Width $width /Height $height " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                    "/Length ${bytes.size} >>\nstream\n",
            )
            out.write(bytes)
            write("\nendstream\n")
            end()
        }

        begin(infoObj)
        write("<< /Title ${pdfString(name ?: "Documento")} /Producer ${pdfString("Libre Scanner")} >>\n")
        end()

        val xref = out.size()
        write("xref\n0 ${totalObjects + 1}\n")
        write("0000000000 65535 f \n")
        for (id in 1..totalObjects) {
            write(String.format("%010d 00000 n \n", offsets[id]))
        }
        write("trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R /Info $infoObj 0 R >>\n")
        write("startxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** JPEG opaco (sin alfa) + dimensiones, listo para /DCTDecode */
    private fun toOpaqueJpeg(source: Bitmap): Triple<ByteArray, Int, Int> {
        val opaque = if (source.hasAlpha()) {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { copy ->
                Canvas(copy).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(source, 0f, 0f, null)
                }
            }
        } else {
            source
        }
        val bytes = ByteArrayOutputStream().also {
            opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }.toByteArray()
        if (opaque !== source) opaque.recycle()
        return Triple(bytes, source.width, source.height)
    }

    private fun fmt(value: Float): String =
        String.format(java.util.Locale.US, "%.2f", value)

    /** cadena PDF como hex UTF-16BE (soporta acentos y evita escapes) */
    private fun pdfString(value: String): String {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + value.toByteArray(Charsets.UTF_16BE)
        return "<" + bytes.joinToString("") { String.format(java.util.Locale.US, "%02X", it) } + ">"
    }

    private fun safeName(name: String?): String {
        val cleaned = (name ?: "documento")
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
            .ifEmpty { "documento" }
        return cleaned.take(60)
    }
}
