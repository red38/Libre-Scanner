package com.red38.librescanner.data

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.red38.librescanner.vision.DocumentFilter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** resumen de un documento guardado, para la lista de "Mis documentos" */
data class DocSummary(
    val id: String,
    val name: String,
    val pageCount: Int,
    val createdAt: Long,
    val thumbPath: String?,
)

/** documento completo cargado: páginas listas para el editor */
data class LoadedDoc(
    val id: String,
    val name: String,
    val pages: List<PageItem>,
)

/**
 * Persistencia local de documentos, equivalente al almacén de documentos de la web:
 * documents/<id>/page_i.jpg + meta.json {name, createdAt, pages:[{f, quad, filter}]}.
 * Todo vive en filesDir del contexto (sin servicios en la nube, como siempre).
 */
object DocumentStore {
    private fun root(context: Context): File = File(context.filesDir, "documents").apply { mkdirs() }

    private fun dir(context: Context, id: String): File = File(root(context), id)

    /** crea un documento nuevo con las páginas actuales y devuelve su resumen */
    fun create(context: Context, pages: List<PageItem>, name: String? = null): DocSummary? {
        val id = UUID.randomUUID().toString()
        val finalName = name ?: "Documento ${list(context).size + 1}"
        val createdAt = writePages(context, id, pages, finalName) ?: return null
        return DocSummary(id, finalName, pages.size, createdAt, thumbPath(context, id))
    }

    /** lista de documentos con miniatura (primera página), ordenados por fecha desc */
    fun list(context: Context): List<DocSummary> =
        root(context).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { folder ->
                runCatching {
                    val meta = JSONObject(File(folder, "meta.json").readText())
                    DocSummary(
                        id = folder.name,
                        name = meta.optString("name", "Documento"),
                        pageCount = meta.optJSONArray("pages")?.length() ?: 0,
                        createdAt = meta.optLong("createdAt", folder.lastModified()),
                        thumbPath = thumbPath(context, folder.name),
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun load(context: Context, id: String): LoadedDoc? {
        val metaFile = File(dir(context, id), "meta.json")
        if (!metaFile.isFile) return null
        return runCatching {
            val meta = JSONObject(metaFile.readText())
            val arr = meta.optJSONArray("pages") ?: JSONArray()
            val pages = mutableListOf<PageItem>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val file = File(dir(context, id), item.getString("f"))
                if (!file.isFile) continue
                val quad = item.optJSONArray("quad")?.takeIf { it.length() == 8 }?.let { qa ->
                    List(4) { k ->
                        Offset(qa.getDouble(k * 2).toFloat(), qa.getDouble(k * 2 + 1).toFloat())
                    }
                }
                val filter = runCatching {
                    DocumentFilter.valueOf(item.optString("filter", DocumentFilter.MAGIC.name))
                }.getOrDefault(DocumentFilter.MAGIC)
                pages.add(PageItem(file.readBytes(), quad).apply { this.filter = filter })
            }
            LoadedDoc(id, meta.optString("name", "Documento"), pages)
        }.getOrNull()
    }

    /** guarda la estructura completa del documento (páginas, quads y filtros) */
    fun persist(context: Context, id: String, pages: List<PageItem>, name: String? = null): Boolean =
        writePages(context, id, pages, name) != null

    /** elimina el documento entero */
    fun delete(context: Context, id: String): Boolean = dir(context, id).deleteRecursively()

    /** guarda la miniatura del documento con la primera página ya procesada */
    fun saveThumb(context: Context, id: String, bitmap: Bitmap): Boolean =
        runCatching {
            File(dir(context, id), "thumb.jpg").writeBytes(ScanSession.encode(bitmap))
            true
        }.getOrDefault(false)

    /** ruta de la miniatura: la procesada si existe, si no la primera página */
    fun thumbPath(context: Context, id: String): String? {
        val thumb = File(dir(context, id), "thumb.jpg")
        if (thumb.isFile) return thumb.absolutePath
        return dir(context, id).listFiles()
            ?.filter { it.name.startsWith("page_") && it.name.endsWith(".jpg") }
            ?.minByOrNull { it.name }
            ?.absolutePath
    }

    private fun writePages(context: Context, id: String, pages: List<PageItem>, name: String?): Long? {
        val folder = dir(context, id)
        folder.mkdirs()
        // conserva nombre y fecha de la versión anterior cuando no se pasa un nombre nuevo
        val previous = runCatching { JSONObject(File(folder, "meta.json").readText()) }.getOrNull()
        val finalName = name ?: previous?.optString("name")?.takeIf { it.isNotEmpty() } ?: "Documento"
        val createdAt = previous?.optLong("createdAt")?.takeIf { it > 0 } ?: System.currentTimeMillis()

        val names = mutableSetOf("meta.json")
        val arr = JSONArray()
        pages.forEachIndexed { idx, page ->
            val fileName = "page_$idx.jpg"
            File(folder, fileName).writeBytes(page.data)
            names.add(fileName)
            val item = JSONObject().apply {
                put("f", fileName)
                page.quad?.let { quad ->
                    val qa = JSONArray()
                    quad.forEach {
                        qa.put(it.x.toDouble())
                        qa.put(it.y.toDouble())
                    }
                    put("quad", qa)
                }
                put("filter", page.filter.name)
            }
            arr.put(item)
        }
        val obj = JSONObject().apply {
            put("name", finalName)
            put("createdAt", createdAt)
            put("pages", arr)
        }
        return runCatching {
            val tmp = File(folder, "meta.json.tmp")
            tmp.writeText(obj.toString())
            File(folder, "meta.json").delete()
            if (!tmp.renameTo(File(folder, "meta.json"))) {
                tmp.copyTo(File(folder, "meta.json"), overwrite = true)
                tmp.delete()
            }
            // borra páginas sobrantes de versiones anteriores
            folder.listFiles()?.forEach { if (it.name !in names) it.delete() }
            createdAt
        }.getOrNull()
    }
}
