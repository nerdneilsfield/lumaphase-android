package dev.lumaphase.android.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.lumaphase.android.layout.ImportedLayout
import dev.lumaphase.android.layout.LayoutImportException
import dev.lumaphase.android.layout.LayoutJsonImport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only camera capture screen for the recognition work.
 *
 * This slice deliberately has no ArUco button, no manual four-corner button and no decoded
 * payload result. It opens the back camera, shows a live preview, imports a real
 * `layout.json`, and freezes one upright grayscale frame for the next slice. Showing a
 * "recognized" value here would be a fabricated success.
 */
@Composable
fun CameraTestScreen(
    resumed: Boolean,
    controller: CameraController,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var permissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var status by remember { mutableStateOf("Camera is not open") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var previewSize by remember { mutableStateOf<Size?>(null) }
    var surfaceAvailable by remember { mutableStateOf(false) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frozenFrame by remember { mutableStateOf<GrayFrame?>(null) }
    var frozenDescription by remember { mutableStateOf<String?>(null) }

    var layouts by remember { mutableStateOf<List<ImportedLayout>>(emptyList()) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val importingFlag = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            errorMessage = null
            status = "Camera permission granted"
        } else {
            errorMessage = "Camera permission was denied; the capture test cannot start"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            importStatus = "Layout import cancelled"
            return@rememberLauncherForActivityResult
        }
        if (!importingFlag.compareAndSet(false, true)) return@rememberLauncherForActivityResult
        importing = true
        importStatus = "Reading ${uri.lastPathSegment ?: "layout.json"}…"
        Thread {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } ?: throw LayoutImportException("The selected file could not be read")
                LayoutJsonImport.parse(text)
            }
            mainHandler.post {
                importingFlag.set(false)
                importing = false
                result.onSuccess { imported ->
                    layouts = imported
                    // With more than one immutable layout, do not silently choose one from
                    // the current screen orientation or file order: the operator must select.
                    selectedIndex = if (imported.size == 1) 0 else -1
                    importStatus = if (imported.size == 1) {
                        "Imported 1 layout object"
                    } else {
                        "Imported ${imported.size} layout objects; select one below"
                    }
                }.onFailure { error ->
                    layouts = emptyList()
                    selectedIndex = -1
                    importStatus = null
                    errorMessage = error.message ?: "Layout import failed"
                }
            }
        }.start()
    }

    DisposableEffect(controller) {
        controller.listener = object : CameraController.Listener {
            override fun onStatus(message: String) {
                status = message
            }

            override fun onError(message: String) {
                errorMessage = message
            }

            override fun onPreviewSize(size: Size) {
                previewSize = size
                textureView?.let { controller.configurePreview(it) }
            }

            override fun onFrame(frame: GrayFrame) {
                // Keep the upright grayscale buffer, not only the display bitmap: the next
                // slice needs the exact pixels and dimensions for manual-corner sampling.
                frozenFrame = frame
                frozenBitmap = frame.toDisplayBitmap()
                frozenDescription = frame.description
                errorMessage = null
                status = "Frozen frame; live preview continues underneath"
            }
        }
        onDispose { controller.listener = null }
    }

    LaunchedEffect(resumed) {
        // A one-time camera grant can be revoked while the app is in the background. Re-check
        // when the Activity becomes visible instead of assuming the old grant is still valid.
        if (resumed) {
            permissionGranted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(resumed, permissionGranted, surfaceAvailable, textureView) {
        val view = textureView
        if (resumed && permissionGranted && surfaceAvailable && view != null) {
            controller.start(view)
        } else {
            controller.stop()
        }
    }

    val selected = layouts.getOrNull(selectedIndex)
    val previewRatio = previewSize?.let { it.width.toFloat() / it.height.toFloat() } ?: (4f / 3f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Camera recognition capture", style = MaterialTheme.typography.titleLarge)
        Text(
            "This slice only previews the back camera and freezes one upright Y frame. " +
                "ArUco detection, manual corners and payload decoding are not enabled yet.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (!permissionGranted) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera permission")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(previewRatio),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { viewContext ->
                    TextureView(viewContext).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surface: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                textureView = this@apply
                                surfaceAvailable = true
                                controller.configurePreview(this@apply)
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surface: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                controller.configurePreview(this@apply)
                            }

                            override fun onSurfaceTextureDestroyed(
                                surface: SurfaceTexture,
                            ): Boolean {
                                surfaceAvailable = false
                                controller.stop()
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        }
                        addOnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
                            controller.configurePreview(changedView as TextureView)
                        }
                    }.also { textureView = it }
                },
                update = { view -> controller.configurePreview(view) },
                modifier = Modifier.fillMaxSize(),
            )
            frozenBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Frozen upright grayscale camera frame",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = permissionGranted && resumed && !importing,
                onClick = { controller.requestFrame() },
            ) {
                Text("Capture one frame")
            }
            Button(
                enabled = frozenBitmap != null,
                onClick = {
                    frozenFrame = null
                    frozenBitmap = null
                    frozenDescription = null
                    status = "Live preview"
                },
            ) {
                Text("Resume preview")
            }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        frozenDescription?.let {
            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Text("Imported source layout", style = MaterialTheme.typography.labelLarge)
        Button(
            enabled = !importing,
            onClick = { importLauncher.launch(arrayOf("*/*")) },
        ) {
            Text(if (importing) "Reading…" else "Import layout.json")
        }
        importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (layouts.size > 1) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                layouts.forEachIndexed { index, layout ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        label = { Text(layout.layoutId) },
                    )
                }
            }
        }
        if (layouts.size > 1 && selected == null) {
            Text(
                "Select one source layout before the next recognition slice uses it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        selected?.let { layout ->
            Text(
                layoutMetadata(layout),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun layoutMetadata(layout: ImportedLayout): String = buildString {
    appendLine("layout_id ${layout.layoutId}")
    appendLine("schema ${layout.schemaVersion} · profile ${layout.profile.name}")
    appendLine(
        "geometry ${layout.geometryLabel} · " +
            "drawable ${layout.drawableWidthPx}x${layout.drawableHeightPx} · " +
            "rotation ${layout.patternRotationDeg}°",
    )
    appendLine(
        "dynamic ${layout.dynamicRectPx.edges.joinToString(",")} · " +
            "modules ${layout.modules.size} · padding ${layout.padding.size}",
    )
    appendLine(
        "levels ${layout.levelLow}/${layout.levelHigh} · " +
            "markers ${layout.markers.joinToString(",") { it.id.toString() }}",
    )
}

private fun GrayFrame.toDisplayBitmap(): Bitmap {
    val argb = IntArray(width * height) { index ->
        val value = pixels[index].toInt() and 0xFF
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    }
    return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
}
