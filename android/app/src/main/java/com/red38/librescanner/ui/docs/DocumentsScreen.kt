package com.red38.librescanner.ui.docs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.red38.librescanner.data.DocSummary
import com.red38.librescanner.data.DocumentStore
import com.red38.librescanner.data.ScanSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Mis documentos": tarjetas con miniatura, nombre y fecha, como el apartado
 * de documentos de la versión web. Toca una tarjeta → editor del documento.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    onOpen: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var docs by remember { mutableStateOf<List<Pair<DocSummary, Bitmap?>>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<DocSummary?>(null) }

    // se recarga cada vez que la pantalla entra en composición (vuelta del editor, etc.)
    LaunchedEffect(Unit) {
        docs = DocumentStore.list(context).map { doc ->
            doc to doc.thumbPath?.let { ScanSession.decodeFile(it, 320) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis documentos") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Volver") }
                },
            )
        },
    ) { padding ->
        if (docs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("📄", style = MaterialTheme.typography.displayMedium)
                Text(
                    "Sin documentos todavía",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Escanea o importa tu primera página desde el inicio\ny aparecerá aquí con su miniatura.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(docs, key = { it.first.id }) { (doc, thumb) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(doc.id) },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.75f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text("📄", style = MaterialTheme.typography.headlineLarge)
                            }
                        }
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    doc.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                IconButton(onClick = { pendingDelete = doc }) {
                                    Text("🗑")
                                }
                            }
                            Text(
                                "${doc.pageCount} ${if (doc.pageCount == 1) "página" else "páginas"} · ${formatDate(doc.createdAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        pendingDelete?.let { doc ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Eliminar documento") },
                text = { Text("¿Eliminar \"${doc.name}\" y sus ${doc.pageCount} páginas? No se puede deshacer.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            DocumentStore.delete(context, doc.id)
                            pendingDelete = null
                            docs = DocumentStore.list(context).map { it to it.thumbPath?.let { p -> ScanSession.decodeFile(p, 320) } }
                        },
                    ) { Text("Eliminar") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
                },
            )
        }
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale("es")).format(Date(timestamp))
