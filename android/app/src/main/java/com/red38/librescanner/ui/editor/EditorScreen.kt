package com.red38.librescanner.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.red38.librescanner.data.PageItem
import com.red38.librescanner.data.ScanSession
import com.red38.librescanner.ui.fitRect
import com.red38.librescanner.ui.icons.AppIcons
import com.red38.librescanner.vision.DocumentDetector
import com.red38.librescanner.vision.DocumentFilter
import com.red38.librescanner.vision.ImageFilters
import com.red38.librescanner.vision.PerspectiveWarper
import com.red38.librescanner.vision.Pt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val VIEW_MAX_SIDE = 2000
private const val PREVIEW_MAX_SIDE = 720
private const val SAVE_MAX_SIDE = 2600

private val OutlineBlue = Color(0xFF1F6FEB)
private val HandleFill = Color(0xFF1F6FEB).copy(alpha = 0.92f)
private val ShadeColor = Color.Black.copy(alpha = 0.45f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    pages: List<PageItem>,
    page: PageItem,
    index: Int,
    pageCount: Int,
    docName: String? = null,
    onIndexChange: (Int) -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onCancel: () -> Unit,
    onDone: (List<Bitmap>) -> Unit,
) {
    // bitmap de edición decodificado bajo demanda desde el JPEG de la página
    val viewBitmap by produceState<Bitmap?>(initialValue = null, page.data) {
        value = withContext(Dispatchers.Default) { ScanSession.decode(page.data, VIEW_MAX_SIDE) }
    }
    val aspect = viewBitmap?.let { it.width.toFloat() / it.height.toFloat() } ?: 0.75f

    // fuente en baja resolución para la vista previa en vivo
    val previewSource by produceState<Bitmap?>(initialValue = null, page.data) {
        value = withContext(Dispatchers.Default) { ScanSession.decode(page.data, PREVIEW_MAX_SIDE) }
    }

    var preview by remember(page.data) { mutableStateOf<Bitmap?>(null) }
    var previewBusy by remember(page.data) { mutableStateOf(false) }

    // vista previa (recorte + filtro) recalculada con debounce al arrastrar o cambiar filtro
    LaunchedEffect(page.data, page.quad, page.filter, previewSource) {
        val source = previewSource ?: return@LaunchedEffect
        previewBusy = true
        delay(160) // debounce de los recalculos frenéticos durante el arrastre
        val pixelQuad = page.quad?.let {
            ScanSession.toPixelQuad(it, source.width, source.height)
        }
        val (tw, th) = PerspectiveWarper.targetSize(
            pixelQuad, source.width, source.height, PREVIEW_MAX_SIDE,
        )
        val warped = PerspectiveWarper.warp(source, pixelQuad, tw, th)
        preview = ImageFilters.apply(warped, page.filter)
        previewBusy = false
    }

    var busyDetect by remember { mutableStateOf(false) }
    var busyRotate by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val busy = busyDetect || busyRotate || saving
    var draggingCorner by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    suspend fun rotatePage(steps: Int) {
        busyRotate = true
        val rotated = withContext(Dispatchers.Default) {
            val source = ScanSession.decode(page.data, SAVE_MAX_SIDE) ?: return@withContext null
            ScanSession.encode(ScanSession.rotate(source, steps))
        }
        busyRotate = false
        if (rotated != null) {
            page.data = rotated
            page.quad = ScanSession.transformQuad(page.quad ?: defaultQuad(), steps)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val base = docName ?: "Editor"
                    Text(if (pageCount > 1) "$base · ${index + 1}/$pageCount" else base)
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = onAddPage, enabled = !busy) {
                        Icon(Icons.Default.Add, contentDescription = "Añadir página")
                    }
                    IconButton(onClick = { confirmDelete = true }, enabled = !busy) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar página")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(page.data) {
                            detectDragGestures(
                                onDragStart = { position ->
                                    val rect = fitRect(size, aspect)
                                    val points = page.quad ?: defaultQuad()
                                    var best = -1
                                    var bestDistance = Float.MAX_VALUE
                                    points.forEachIndexed { i, point ->
                                        val cx = rect.left + point.x * rect.width
                                        val cy = rect.top + point.y * rect.height
                                        val d = hypot(position.x - cx, position.y - cy)
                                        if (d < bestDistance) {
                                            bestDistance = d
                                            best = i
                                        }
                                    }
                                    // radio de agarre generoso en píxeles de pantalla (web: ~30 CSS px)
                                    draggingCorner = if (bestDistance <= with(density) { 44.dp.toPx() }) best else -1
                                },
                                onDragEnd = { draggingCorner = -1 },
                                onDragCancel = { draggingCorner = -1 },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val i = draggingCorner
                                    val rect = fitRect(size, aspect)
                                    if (i in 0..3 && rect.width > 0f && rect.height > 0f) {
                                        val points = (page.quad ?: defaultQuad()).toMutableList()
                                        val p = points[i]
                                        points[i] = Offset(
                                            (p.x + dragAmount.x / rect.width).coerceIn(0f, 1f),
                                            (p.y + dragAmount.y / rect.height).coerceIn(0f, 1f),
                                        )
                                        page.quad = points
                                    }
                                },
                            )
                        },
                ) {
                    val rect = fitRect(size, aspect)
                    viewBitmap?.let { bmp ->
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                            dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                        )
                    }

                    val pts = (page.quad ?: defaultQuad()).map {
                        Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height)
                    }
                    if (pts.size == 4) {
                        // sombreado fuera del recorte (evenodd, como en la web)
                        val shade = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(0f, 0f, size.width, size.height))
                            moveTo(pts[0].x, pts[0].y)
                            lineTo(pts[1].x, pts[1].y)
                            lineTo(pts[2].x, pts[2].y)
                            lineTo(pts[3].x, pts[3].y)
                            close()
                        }
                        drawPath(shade, ShadeColor)

                        // borde del cuadrilátero
                        drawPath(
                            quadPath(pts),
                            OutlineBlue,
                            style = Stroke(width = 2.5.dp.toPx()),
                        )

                        // soportes en L por esquina (guía visual del agarre)
                        val lLength = 26.dp.toPx()
                        val lOffset = 5.dp.toPx()
                        pts.forEachIndexed { i, pt ->
                            listOf(pts[(i + 3) % 4], pts[(i + 1) % 4]).forEach { neighbor ->
                                val dx = neighbor.x - pt.x
                                val dy = neighbor.y - pt.y
                                val len = hypot(dx, dy)
                                if (len > 1f) {
                                    val ux = dx / len
                                    val uy = dy / len
                                    drawLine(
                                        OutlineBlue,
                                        Offset(pt.x + ux * lOffset, pt.y + uy * lOffset),
                                        Offset(pt.x + ux * (lOffset + lLength), pt.y + uy * (lOffset + lLength)),
                                        strokeWidth = 2.5.dp.toPx(),
                                    )
                                }
                            }
                        }

                        // mangos: círculo grande, anillo blanco y contorno oscuro
                        pts.forEach { pt ->
                            val r = 12.dp.toPx()
                            drawCircle(HandleFill, radius = r, center = pt)
                            drawCircle(
                                Color.White,
                                radius = r,
                                center = pt,
                                style = Stroke(width = 3.dp.toPx()),
                            )
                            drawCircle(
                                Color.Black.copy(alpha = 0.55f),
                                radius = r,
                                center = pt,
                                style = Stroke(1.5f),
                            )
                        }
                    }
                }

                if (busy) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Text(
                                if (saving) "Guardando documento…" else "Procesando…",
                                color = Color.White,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }

            // filtros
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DocumentFilter.entries.forEach { option ->
                    FilterChip(
                        selected = page.filter == option,
                        onClick = { page.filter = option },
                        label = { Text(option.uiLabel()) },
                        enabled = !saving,
                    )
                }
            }

            // rotación + redetección
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { scope.launch { rotatePage(-1) } },
                    enabled = !busy,
                ) {
                    Icon(AppIcons.RotateLeft, contentDescription = "Rotar a la izquierda")
                }
                OutlinedButton(
                    onClick = { scope.launch { rotatePage(1) } },
                    enabled = !busy,
                ) {
                    Icon(AppIcons.RotateRight, contentDescription = "Rotar a la derecha")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busyDetect = true
                            val detected = withContext(Dispatchers.Default) {
                                viewBitmap?.let { DocumentDetector.detect(it) }
                            }
                            busyDetect = false
                            val bmp = viewBitmap
                            if (detected != null && bmp != null) {
                                page.quad = ScanSession.normalizeQuad(detected.pts, bmp)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    Icon(
                        AppIcons.AutoFix,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Redetectar")
                }
            }

            // paginación del documento (como ed-prev / ed-pos / ed-next en la web)
            if (pageCount > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { onIndexChange(index - 1) },
                        enabled = index > 0 && !busy,
                    ) { Text("◀") }
                    Text(
                        "${index + 1} / $pageCount",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = { onIndexChange(index + 1) },
                        enabled = index < pageCount - 1 && !busy,
                    ) { Text("▶") }
                }
            }

            // vista previa en vivo del resultado final (recorte + filtro)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                preview?.let { p ->
                    Image(
                        bitmap = p.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (previewBusy && preview == null) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White)
                }
            }

            // guardar la página actual y el documento entero (auto-detección en las sin recorte)
            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        val outputs = withContext(Dispatchers.Default) {
                            pages.mapNotNull { sourcePage ->
                                val source = ScanSession.decode(sourcePage.data, SAVE_MAX_SIDE)
                                    ?: return@mapNotNull null
                                if (sourcePage.quad == null && sourcePage !== page) {
                                    val coarse = ScanSession.decode(sourcePage.data, VIEW_MAX_SIDE)
                                    detectedQuad(coarse)?.let {
                                        sourcePage.quad = ScanSession.normalizeQuad(it.pts, source)
                                    }
                                }
                                val pixelQuad = sourcePage.quad
                                    ?.takeIf { it.size == 4 }
                                    ?.let { ScanSession.toPixelQuad(it, source.width, source.height) }
                                val (tw, th) = PerspectiveWarper.targetSize(
                                    pixelQuad, source.width, source.height,
                                )
                                val warped = PerspectiveWarper.warp(source, pixelQuad, tw, th)
                                ImageFilters.apply(warped, sourcePage.filter)
                            }
                        }
                        saving = false
                        onDone(outputs)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                enabled = !busy,
            ) {
                Text(
                    if (pageCount > 1) "✅ Aplicar y guardar ($pageCount páginas)"
                    else "✅ Aplicar y guardar",
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar página") },
            text = {
                Text(
                    if (pageCount > 1) "¿Eliminar la página ${index + 1} de $pageCount? No se puede deshacer."
                    else "¿Eliminar esta página? El documento quedará vacío y se borrará. No se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeletePage()
                    },
                ) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            },
        )
    }
}

private suspend fun detectedQuad(bitmap: Bitmap?) =
    if (bitmap == null) null else DocumentDetector.detect(bitmap)

private fun quadPath(pts: List<Offset>): Path = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    lineTo(pts[1].x, pts[1].y)
    lineTo(pts[2].x, pts[2].y)
    lineTo(pts[3].x, pts[3].y)
    close()
}

private fun defaultQuad(): List<Offset> = listOf(
    Offset(0.06f, 0.06f),
    Offset(0.94f, 0.06f),
    Offset(0.94f, 0.94f),
    Offset(0.06f, 0.94f),
)

private fun DocumentFilter.uiLabel(): String = when (this) {
    DocumentFilter.ORIGINAL -> "Original"
    DocumentFilter.GRAY -> "Gris"
    DocumentFilter.BW -> "B/N"
    DocumentFilter.BW2 -> "B/N suave"
    DocumentFilter.MAGIC -> "Color"
}
