package io.github.artemimennoi.awgbar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** Full-screen camera that reads a QR code, or a whole AmneziaVPN series of them, and hands back the payload. */
@Composable
fun QrScanner(onResult: (ByteArray) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) onDismiss()
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    val series = remember { QrSeries() }
    var progress by remember { mutableStateOf<String?>(null) }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                var view by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
                AndroidView(
                    factory = { ctx ->
                        DecoratedBarcodeView(ctx).apply {
                            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                            setStatusText("")
                            decodeContinuous { result ->
                                val text = result.text ?: return@decodeContinuous
                                val done = series.add(text)
                                if (done != null) {
                                    pause()
                                    onResult(done)
                                } else {
                                    progress = "${series.received} / ${series.total}"
                                }
                            }
                            resume()
                            view = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                DisposableEffect(Unit) { onDispose { view?.pause() } }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    progress?.let { stringResource(R.string.qr_series, it) } ?: stringResource(R.string.qr_hint),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = Color.White) }
            }
        }
    }
}
