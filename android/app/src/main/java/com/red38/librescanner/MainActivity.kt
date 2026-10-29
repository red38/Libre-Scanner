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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.red38.librescanner.ui.capture.CaptureScreen
import com.red38.librescanner.ui.editor.EditorScreen
import com.red38.librescanner.ui.result.ResultScreen
import com.red38.librescanner.ui.theme.LibreScannerTheme
import com.red38.librescanner.vision.DocumentDetector
import com.red38.librescanner.vision.OpenCv
import com.red38.librescanner.vision.Pt
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
    data object Capture : Screen
    data class Edit(val bitmap: Bitmap, val quad: List<Pt>?) : Screen
    data class Result(val bitmap: Bitmap) : Screen
}

@Composable
private fun LibreScannerApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Capture) }
    var detecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = screen !is Screen.Capture) {
        screen = Screen.Capture
    }

    when (val current = screen) {
        Screen.Capture -> CaptureScreen(
            onImage = { bitmap ->
                detecting = true
                scope.launch {
                    val quad = withContext(Dispatchers.Default) { DocumentDetector.detect(bitmap) }
                    detecting = false
                    screen = Screen.Edit(bitmap, quad?.pts)
                }
            },
        )

        is Screen.Edit -> EditorScreen(
            bitmap = current.bitmap,
            initialQuad = current.quad,
            onCancel = { screen = Screen.Capture },
            onDone = { screen = Screen.Result(it) },
        )

        is Screen.Result -> ResultScreen(
            bitmap = current.bitmap,
            onNewScan = { screen = Screen.Capture },
        )
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
