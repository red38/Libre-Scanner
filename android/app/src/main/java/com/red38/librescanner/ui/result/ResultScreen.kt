package com.red38.librescanner.ui.result

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.red38.librescanner.ui.icons.AppIcons
import com.red38.librescanner.util.MediaStoreSaver
import com.red38.librescanner.util.PdfExport
import com.red38.librescanner.util.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    pages: List<Bitmap>,
    docName: String?,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onNewScan: () -> Unit,
    onDocs: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // bitmap actual (la sesión siempre deja al menos uno al llegar aquí)
    val bitmap = pages[index.coerceIn(0, pages.size - 1)]
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    var busy by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf("") }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun sharePdf() {
        if (busy) return
        scope.launch {
            busy = true
            busyMessage = "Generando PDF…"
            val file = withContext(Dispatchers.IO) { PdfExport.create(context, pages, docName) }
            busy = false
            if (file != null) {
                Sharing.shareFile(context, file, "application/pdf")
            } else {
                toast("No se pudo generar el PDF")
            }
        }
    }

    fun savePdf() {
        if (busy) return
        scope.launch {
            busy = true
            busyMessage = "Guardando PDF…"
            val uri = withContext(Dispatchers.IO) {
                PdfExport.create(context, pages, docName)?.let { file ->
                    MediaStoreSaver.savePdf(context, file, file.name)
                }
            }
            busy = false
            toast(
                if (uri != null) "PDF guardado en Descargas"
                else "No se pudo guardar el PDF",
            )
        }
    }

    fun saveImage() {
        if (busy) return
        scope.launch {
            busy = true
            busyMessage = "Guardando imagen…"
            val uri = withContext(Dispatchers.IO) {
                val name = "${docName ?: "documento"}_${index + 1}"
                MediaStoreSaver.saveImage(context, bitmap, name)
            }
            busy = false
            toast(
                if (uri != null) "Imagen guardada en Pictures/Libre Scanner"
                else "No se pudo guardar la imagen",
            )
        }
    }

    val needsLegacyPermission =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) {
            action?.invoke()
        } else {
            toast("Permiso denegado: no se pudo guardar")
        }
    }
    fun withSavePermission(action: () -> Unit) {
        if (needsLegacyPermission) {
            pendingAction = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                docName != null && pages.size > 1 -> "$docName · ${index + 1}/${pages.size}"
                                docName != null -> docName
                                pages.size > 1 -> "Documento · ${index + 1}/${pages.size}"
                                else -> "Resultado"
                            },
                        )
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }

                if (pages.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { onIndexChange(index - 1) },
                            enabled = index > 0 && !busy,
                        ) { Text("◀") }
                        Text(
                            "${index + 1} / ${pages.size}",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                        OutlinedButton(
                            onClick = { onIndexChange(index + 1) },
                            enabled = index < pages.size - 1 && !busy,
                        ) { Text("▶") }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onNewScan,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text("Nuevo escaneo")
                    }
                    Button(
                        onClick = { Sharing.shareImages(context, pages, docName) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Compartir", maxLines = 1)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { withSavePermission { savePdf() } },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Icon(AppIcons.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Guardar PDF", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { sharePdf() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Icon(AppIcons.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Compartir PDF", maxLines = 1)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { withSavePermission { saveImage() } },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text("Guardar imagen")
                    }
                    OutlinedButton(
                        onClick = onDocs,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text("Mis documentos")
                    }
                }
            }
        }

        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        busyMessage,
                        color = Color.White,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
