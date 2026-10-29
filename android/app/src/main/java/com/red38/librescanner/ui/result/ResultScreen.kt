package com.red38.librescanner.ui.result

import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.red38.librescanner.util.Sharing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    pages: List<Bitmap>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onNewScan: () -> Unit,
    onDocs: () -> Unit,
) {
    val context = LocalContext.current
    //bitmap actual (la sesión siempre deja al menos uno al llegar aquí)
    val bitmap = pages[index.coerceIn(0, pages.size - 1)]
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pages.size > 1) "Documento · ${index + 1}/${pages.size}" else "Resultado") },
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
                        enabled = index > 0,
                    ) { Text("◀") }
                    Text(
                        "${index + 1} / ${pages.size}",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = { onIndexChange(index + 1) },
                        enabled = index < pages.size - 1,
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
                ) {
                    Text("Nuevo escaneo")
                }
                Button(
                    onClick = { Sharing.share(context, bitmap) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Compartir")
                }
            }

            OutlinedButton(
                onClick = onDocs,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("📚 Mis documentos")
            }
        }
    }
}
