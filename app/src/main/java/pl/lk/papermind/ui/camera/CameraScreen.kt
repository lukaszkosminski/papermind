package pl.lk.papermind.ui.camera

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.lk.papermind.R
import pl.lk.papermind.ui.MainViewModel
import pl.lk.papermind.ui.ScanStatus
import pl.lk.papermind.ui.UiState
import pl.lk.papermind.ui.overlay.AnswerOverlay
import pl.lk.papermind.ui.panel.AnswerPanel
import pl.lk.papermind.ui.panel.engineName
import java.util.concurrent.Executors

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Ekran kamery z bramką uprawnienia. [paused] — gdy nad kamerą jest inny ekran (ustawienia):
 * analiza OCR zostaje odpięta, ale podgląd i nakładka pozostają w pamięci.
 */
@Composable
fun CameraScreen(viewModel: MainViewModel, paused: Boolean, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = context.hasCameraPermission() }

    if (granted) {
        ScannerScreen(viewModel, paused, onOpenSettings)
    } else {
        PermissionScreen(onResult = { granted = it })
    }
}

@Composable
private fun PermissionScreen(onResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    var requested by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        requested = true
        onResult(ok)
        if (!ok) {
            val activity = context.findActivity()
            permanentlyDenied = activity != null &&
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                stringResource(if (permanentlyDenied) R.string.permission_denied else R.string.permission_rationale),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Button(onClick = {
                if (permanentlyDenied && requested) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                } else {
                    launcher.launch(Manifest.permission.CAMERA)
                }
            }) {
                Text(stringResource(if (permanentlyDenied) R.string.permission_open_settings else R.string.permission_grant))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerScreen(viewModel: MainViewModel, paused: Boolean, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val density = LocalDensity.current

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            isTapToFocusEnabled = true
            isPinchToZoomEnabled = true
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var frozenFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var torchOn by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    DisposableEffect(controller, lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    // Analiza OCR działa tylko gdy nie ma pauzy ani innego ekranu.
    val analyzing = !state.frozen && !paused
    DisposableEffect(controller, analyzing) {
        if (analyzing) viewModel.bindCamera(controller, executor)
        onDispose { if (analyzing) viewModel.unbindCamera(controller) }
    }

    // Ekran nie gaśnie podczas skanowania.
    DisposableEffect(analyzing) {
        val window = context.findActivity()?.window
        if (analyzing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val peekHeight = 132.dp
    val peekPx = with(density) { peekHeight.toPx() }

    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(),
        sheetPeekHeight = peekHeight,
        sheetContent = {
            AnswerPanel(
                items = state.items,
                selectedId = state.selectedId,
                showExplanations = state.settings.showExplanations,
                onSelect = viewModel::select,
            )
        },
    ) { _ ->
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        this.controller = controller
                        previewView = this
                    }
                },
            )
            frozenFrame?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            AnswerOverlay(
                items = state.items,
                lineBoxes = state.lineBoxes,
                showLineOutlines = state.showLineOutlines,
                selectedId = state.selectedId,
                bottomInsetPx = peekPx,
                modifier = Modifier.fillMaxSize(),
            )
            TopControls(
                state = state,
                torchOn = torchOn,
                onTorch = {
                    if (controller.cameraInfo?.hasFlashUnit() == true) {
                        torchOn = !torchOn
                        controller.enableTorch(torchOn)
                    }
                },
                onFreeze = {
                    frozenFrame = if (state.frozen) null else previewView?.bitmap?.asImageBitmap()
                    viewModel.toggleFreeze()
                },
                onClear = viewModel::clear,
                onOutlines = viewModel::toggleLineOutlines,
                onSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun TopControls(
    state: UiState,
    torchOn: Boolean,
    onTorch: () -> Unit,
    onFreeze: () -> Unit,
    onClear: () -> Unit,
    onOutlines: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatusChip(state)
        Row(
            Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(28.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(onTorch, if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff, R.string.cd_torch)
            ControlButton(
                onOutlines,
                if (state.showLineOutlines) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                R.string.cd_outlines,
            )
            ControlButton(onFreeze, if (state.frozen) Icons.Default.PlayArrow else Icons.Default.Pause, if (state.frozen) R.string.cd_resume else R.string.cd_pause)
            ControlButton(onClear, Icons.Default.DeleteSweep, R.string.cd_clear)
            ControlButton(onSettings, Icons.Default.Settings, R.string.cd_settings)
        }
    }
}

@Composable
private fun ControlButton(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, description: Int) {
    IconButton(onClick = onClick, colors = IconButtonDefaults.iconButtonColors()) {
        Icon(icon, contentDescription = stringResource(description))
    }
}

@Composable
private fun StatusChip(state: UiState) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.status == ScanStatus.ANALYZING) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
            }
            Text(
                "${engineName(state.activeEngine)} · " + stringResource(
                    when (state.status) {
                        ScanStatus.SCANNING -> R.string.status_scanning
                        ScanStatus.ANALYZING -> R.string.status_analyzing
                        ScanStatus.DONE -> R.string.status_done
                        ScanStatus.FROZEN -> R.string.status_frozen
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
