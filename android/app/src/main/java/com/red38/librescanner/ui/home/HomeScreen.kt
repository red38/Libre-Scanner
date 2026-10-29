package com.red38.librescanner.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.red38.librescanner.data.ImageLoader
import com.red38.librescanner.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla inicial: elige cámara o galería, o continúa el documento en curso.
 * Nada entra directo a la cámara.
 */
@Composable
fun HomeScreen(
    docsCount: Int,
    onNewScan: () -> Unit,
    onImage: (Bitmap) -> Unit,
    onDocs: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            importing = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            importing = true
            val bitmap = withContext(Dispatchers.IO) { ImageLoader.load(context, uri) }
            importing = false
            if (bitmap != null) {
                onImage(bitmap)
            } else {
                importError = "No se pudo abrir la imagen."
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            ),
    ) {
        // sin weight dentro del scroll: el footer se cortaba y quedaba bajo la barra de navegación
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            // "logotipo": página escaneada estilizada
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(48.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Libre Scanner",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Escáner de documentos · 100% local",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = onNewScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(AppIcons.Camera, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(10.dp))
                Text("Nuevo escaneo", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    if (!importing) {
                        pickLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(AppIcons.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(10.dp))
                Text("Importar de galería", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onDocs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(AppIcons.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    if (docsCount > 0) "Mis documentos ($docsCount)"
                    else "Mis documentos",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "🔒 Todo el procesamiento ocurre en tu dispositivo · sin conexión",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }

        if (importing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Importando…", color = Color.White)
            }
        }

        importError?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp),
            )
        }
    }
}
