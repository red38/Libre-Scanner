package com.red38.librescanner

import android.content.Context
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.red38.librescanner.data.DocumentStore
import com.red38.librescanner.data.PageItem
import com.red38.librescanner.data.ScanSession
import com.red38.librescanner.ui.capture.CaptureScreen
import com.red38.librescanner.ui.docs.DocumentsScreen
import com.red38.librescanner.ui.editor.EditorScreen
import com.red38.librescanner.ui.home.HomeScreen
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
    data object Docs : Screen
    data object Edit : Screen
    data object Result : Screen
}

/** documento abierto en el editor (persistido en DocumentStore) */
private data class OpenDoc(val id: String, val name: String)

@Composable
private fun LibreScannerApp() {
    val context = LocalContext.current
    // documento abierto: las páginas del editor (persistidas via DocumentStore)
    val pages = remember { mutableStateListOf<PageItem>() }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var returnTo by remember { mutableStateOf<Screen>(Screen.Home) }
    var openDoc by remember { mutableStateOf<OpenDoc?>(null) }
    var editingIndex by remember { mutableIntStateOf(0) }
    var outputs by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var showingIndex by remember { mutableIntStateOf(0) }
    var progressMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // flujo común para cámara y galería: detecta bordes, añade página y abre el editor.
    // Si no hay documento abierto, se crea uno nuevo persistido; si lo hay, se le añade.
    val handleImage: (Bitmap) -> Unit = { bitmap ->
        progressMessage = "Detectando bordes…"
        scope.launch {
            val wasNewDoc = openDoc == null
            val detected = withContext(Dispatchers.Default) { DocumentDetector.detect(bitmap) }
            pages.add(
                PageItem(
                    ScanSession.encode(bitmap),
                    detected?.pts?.let { ScanSession.normalizeQuad(it, bitmap) },
                ),
            )
            withContext(Dispatchers.IO) {
                val doc = openDoc
                if (doc == null) {
                    DocumentStore.create(context, pages)?.let { openDoc = OpenDoc(it.id, it.name) }
                } else {
                    DocumentStore.persist(context, doc.id, pages)
                }
            }
            progressMessage = null
            editingIndex = pages.size - 1
            if (wasNewDoc) returnTo = Screen.Home
            screen = Screen.Edit
        }
    }

    fun persistCurrent() {
        val doc = openDoc ?: return
        if (pages.isEmpty()) {
            DocumentStore.delete(context, doc.id)
            openDoc = null
        } else {
            scope.launch(Dispatchers.IO) { DocumentStore.persist(context, doc.id, pages) }
        }
    }

    BackHandler(enabled = screen !is Screen.Home) {
        if (screen is Screen.Edit) persistCurrent()
        screen = when (screen) {
            is Screen.Docs -> Screen.Home
            else -> returnTo
        }
    }

    when (screen) {
        Screen.Home -> {
            val docsCount by produceState(0) {
                value = withContext(Dispatchers.IO) { DocumentStore.list(context).size }
            }
            HomeScreen(
                docsCount = docsCount,
                onNewScan = { screen = Screen.Capture },
                onImage = handleImage,
                onDocs = { screen = Screen.Docs },
            )
        }

        Screen.Capture -> CaptureScreen(onImage = handleImage)

        Screen.Docs -> DocumentsScreen(
            onOpen = { id ->
                progressMessage = "Abriendo documento…"
                scope.launch {
                    val loaded = withContext(Dispatchers.IO) { DocumentStore.load(context, id) }
                    progressMessage = null
                    if (loaded != null && loaded.pages.isNotEmpty()) {
                        pages.clear()
                        pages.addAll(loaded.pages)
                        openDoc = OpenDoc(loaded.id, loaded.name)
                        editingIndex = 0
                        returnTo = Screen.Docs
                        screen = Screen.Edit
                    } else {
                        // documento vacío o corrupto: fuera de la lista
                        DocumentStore.delete(context, id)
                    }
                }
            },
            onBack = { screen = Screen.Home },
        )

        Screen.Edit -> {
            val idx = editingIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            if (pages.isNotEmpty()) {
                EditorScreen(
                    pages = pages,
                    page = pages[idx],
                    index = idx,
                    pageCount = pages.size,
                    docName = openDoc?.name,
                    onIndexChange = { editingIndex = it },
                    onAddPage = { screen = Screen.Capture },
                    onDeletePage = {
                        pages.removeAt(idx)
                        persistCurrent()
                        if (pages.isEmpty()) {
                            screen = returnTo
                        } else {
                            editingIndex = idx.coerceIn(0, pages.size - 1)
                        }
                    },
                    onCancel = {
                        persistCurrent()
                        screen = returnTo
                    },
                    onDone = {
                        persistCurrent()
                        openDoc?.let { doc ->
                            scope.launch(Dispatchers.IO) {
                                DocumentStore.saveThumb(context, doc.id, it.first())
                            }
                        }
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
                onNewScan = { screen = Screen.Capture },
                onDocs = { screen = Screen.Docs },
            )
        }
    }

    progressMessage?.let { message ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(message, color = Color.White)
            }
        }
    }
}
