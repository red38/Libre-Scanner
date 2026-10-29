package com.red38.librescanner.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.red38.librescanner.ui.fitRect
import com.red38.librescanner.vision.DocumentDetector
import com.red38.librescanner.vision.DocumentFilter
import com.red38.librescanner.vision.ImageFilters
import com.red38.librescanner.vision.PerspectiveWarper
import com.red38.librescanner.vision.Pt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    bitmap: Bitmap,
    initialQuad: List<Pt>?,
    onCancel: () -> Unit,
    onDone: (Bitmap) -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    var quad by remember(bitmap) {
        mutableStateOf(initialQuad?.let { normalize(it, bitmap) } ?: defaultQuad())
    }
    var filter by remember { mutableStateOf(DocumentFilter.MAGIC) }
    var busy by remember { mutableStateOf(false) }
    var activeCorner by remember { mutableIntStateOf(-1) }
    val currentQuad by rememberUpdatedState(quad)
    val currentBitmap by rememberUpdatedState(bitmap)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustar documento") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("Volver") }
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
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { position ->
                                    val rect = fitRect(size, aspect)
                                    val points = currentQuad
                                    var best = -1
                                    var bestDistance = Float.MAX_VALUE
                                    points.forEachIndexed { index, point ->
                                        val cx = rect.left + point.x * rect.width
                                        val cy = rect.top + point.y * rect.height
                                        val distance = hypot(position.x - cx, position.y - cy)
                                        if (distance < bestDistance) {
                                            bestDistance = distance
                                            best = index
                                        }
                                    }
                                    activeCorner = if (bestDistance <= 40.dp.toPx()) best else -1
                                },
                                onDragEnd = { activeCorner = -1 },
                                onDragCancel = { activeCorner = -1 },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val index = activeCorner
                                    val rect = fitRect(size, aspect)
                                    if (index in 0..3 && rect.width > 0f && rect.height > 0f) {
                                        val points = currentQuad.toMutableList()
                                        val point = points[index]
                                        points[index] = Offset(
                                            (point.x + dragAmount.x / rect.width).coerceIn(0f, 1f),
                                            (point.y + dragAmount.y / rect.height).coerceIn(0f, 1f),
                                        )
                                        quad = points
                                    }
                                },
                            )
                        },
                ) {
                    val rect = fitRect(size, aspect)
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                    )

                    val points = quad.map {
                        Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height)
                    }
                    if (points.size == 4) {
                        val path = Path().apply {
                            moveTo(points[0].x, points[0].y)
                            lineTo(points[1].x, points[1].y)
                            lineTo(points[2].x, points[2].y)
                            lineTo(points[3].x, points[3].y)
                            close()
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF5AD9CB),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                        points.forEach { point ->
                            drawCircle(color = Color.White, radius = 11.dp.toPx(), center = point)
                            drawCircle(color = Color(0xFF0F766E), radius = 7.dp.toPx(), center = point)
                        }
                    }
                }

                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.Center)
                            .padding(24.dp),
                        color = Color.White,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DocumentFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label()) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val detected = withContext(Dispatchers.Default) {
                                DocumentDetector.detect(currentBitmap)
                            }
                            busy = false
                            if (detected != null) quad = normalize(detected.pts, currentBitmap)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    Text("Redetectar")
                }

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val result = withContext(Dispatchers.Default) {
                                val source = currentBitmap
                                val pixelQuad = currentQuad.map {
                                    Pt(it.x.toDouble() * source.width, it.y.toDouble() * source.height)
                                }
                                val (width, height) =
                                    PerspectiveWarper.targetSize(pixelQuad, source.width, source.height)
                                val warped = PerspectiveWarper.warp(source, pixelQuad, width, height)
                                ImageFilters.apply(warped, filter)
                            }
                            busy = false
                            onDone(result)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    Text(if (busy) "Procesando..." else "Aplicar")
                }
            }
        }
    }
}

private fun normalize(points: List<Pt>, bitmap: Bitmap): List<Offset> = points.map {
    Offset(
        (it.x / bitmap.width).toFloat().coerceIn(0f, 1f),
        (it.y / bitmap.height).toFloat().coerceIn(0f, 1f),
    )
}

private fun defaultQuad(): List<Offset> = listOf(
    Offset(0.06f, 0.06f),
    Offset(0.94f, 0.06f),
    Offset(0.94f, 0.94f),
    Offset(0.06f, 0.94f),
)

private fun DocumentFilter.label(): String = when (this) {
    DocumentFilter.ORIGINAL -> "Original"
    DocumentFilter.GRAY -> "Gris"
    DocumentFilter.BW -> "B/N"
    DocumentFilter.BW2 -> "B/N suave"
    DocumentFilter.MAGIC -> "Color"
}
