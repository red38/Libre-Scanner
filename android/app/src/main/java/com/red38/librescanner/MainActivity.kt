package com.red38.librescanner

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.red38.librescanner.data.PageItem
import com.red38.librescanner.data.ScanSession
import com.red38.librescanner.ui.capture.CaptureScreen
import com.red38.librescanner.ui.home.HomeScreen
import com.red38.librescanner.ui.editor.EditorScreen
import com.red38.librescanner.ui.result.ResultScreen
import com.red38.librescanner.ui.theme.LibreScannerTheme
import com.red38.librescanner.vision.DocumentDetector
import com.red38.librescanner.vision.OpenCv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        OpenCv.ensureLoaded()
        setContent {
            LibreScannerTheme {
                LibreScannerApp()
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Capture : Screen
    data object Edit : Screen
    data object Result : Screen
}

@Composable
private fun LibreScannerApp() {
    // sesión: las páginas originales del documento en curso
    val pages = remember { mutableStateListOf<PageItem>() }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var editingIndex by remember { mutableIntStateOf(0) }
    var outputs by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var showingIndex by remember { mutableIntStateOf(0) }
    var detecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // flujo común para cámara y galería: detecta bordes, añade página y abre el editor
    val handleImage: (Bitmap) -> Unit = { bitmap ->
        detecting = true
        scope.launch {
            val detected = withContext(Dispatchers.Default) { DocumentDetector.detect(bitmap) }
            // la página se guarda como JPEG comprimido: varias páginas no tumban la memoria
            pages.add(
                PageItem(
                    ScanSession.encode(bitmap),
                    detected?.pts?.let { ScanSession.normalizeQuad(it, bitmap) },
                ),
            )
            detecting = false
            editingIndex = pages.size - 1
            screen = Screen.Edit
        }
    }

    BackHandler(enabled = screen !is Screen.Home) {
        screen = Screen.Home
    }

    when (screen) {
        Screen.Home -> HomeScreen(
            pageCount = pages.size,
            onNewScan = { screen = Screen.Capture },
            onImage = handleImage,
            onContinue = {
                if (pages.isNotEmpty()) {
                    editingIndex = editingIndex.coerceIn(0, pages.size - 1)
                    screen = Screen.Edit
                }
            },
            onDiscard = { pages.clear() },
        )

        Screen.Capture -> CaptureScreen(onImage = handleImage)

        Screen.Edit -> {
            val idx = editingIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            if (pages.isNotEmpty()) {
                EditorScreen(
                    pages = pages,
                    page = pages[idx],
                    index = idx,
                    pageCount = pages.size,
                    onIndexChange = { editingIndex = it },
                    onDeletePage = {
                        pages.removeAt(idx)
                        if (pages.isEmpty()) {
                            screen = Screen.Capture
                        } else {
                            editingIndex = idx.coerceIn(0, pages.size - 1)
                        }
                    },
                    onCancel = { screen = Screen.Home },
                    onDone = {
                        outputs = it
                        showingIndex = 0
                        screen = Screen.Result
                    },
                )
            }
        }

        Screen.Result -> if (outputs.isNotEmpty()) {
            ResultScreen(
                pages = outputs,
                index = showingIndex.coerceIn(0, outputs.size - 1),
                onIndexChange = { showingIndex = it },
                onNewScan = { screen = Screen.Home },
                onNewDoc = {
                    pages.clear()
                    screen = Screen.Capture
                },
            )
        }
    }

    if (detecting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text("Detectando bordes…", color = Color.White)
            }
        }
    }
}
