package dev.lumaphase.android.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.lumaphase.android.layout.BuiltInLayouts
import dev.lumaphase.android.layout.BuiltInSelection
import dev.lumaphase.android.layout.ImportedLayout
import dev.lumaphase.android.layout.LayoutImportException
import dev.lumaphase.android.layout.LayoutJsonImport
import dev.lumaphase.android.layout.MacReferenceLayouts
import dev.lumaphase.android.layout.MacReferenceSelection
import dev.lumaphase.android.recognition.BandReading
import dev.lumaphase.android.recognition.RecognitionReading
import dev.lumaphase.android.recognition.RecognitionStatus
import dev.lumaphase.android.recognition.RecognitionVisual
import dev.lumaphase.android.recognition.RecognitionVisualState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

/**
 * Continuous camera recognition screen.
 *
 * The preview and the frozen/overlay coordinates both use CameraPreviewGeometry.fit on the
 * upright frame; there is no raw-buffer aspect or second pattern rotation. Auto mode analyzes
 * the newest Y frame only when a source layout is selected; manual mode freezes one upright
 * frame and maps dynamic-rectangle taps with the same fit.
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
    var status by remember { mutableStateOf("相机未打开") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cameraRetryAvailable by remember { mutableStateOf(false) }
    var previewInfo by remember { mutableStateOf<CameraPreviewInfo?>(null) }
    var surfaceAvailable by remember { mutableStateOf(false) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frozenFrame by remember { mutableStateOf<GrayFrame?>(null) }
    var frozenDescription by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<RecognitionReading?>(null) }
    var manualMode by remember { mutableStateOf(false) }
    var manualPoints by remember { mutableStateOf<List<DoublePoint>>(emptyList()) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    var layouts by remember { mutableStateOf<List<ImportedLayout>>(emptyList()) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var sourceText by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val importingFlag = remember { AtomicBoolean(false) }

    var androidSelection by remember { mutableStateOf<BuiltInSelection?>(null) }
    var macLayouts by remember { mutableStateOf<List<ImportedLayout>>(emptyList()) }
    var macSelection by remember { mutableStateOf<MacReferenceSelection?>(null) }
    var macStatus by remember { mutableStateOf<String?>(null) }
    val androidLayout = remember(androidSelection) {
        androidSelection?.let { BuiltInLayouts.build(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            errorMessage = null
            status = "相机权限已授予"
        } else {
            errorMessage = "相机权限被拒绝，无法进行取帧测试"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            importStatus = "已取消导入"
            return@rememberLauncherForActivityResult
        }
        if (!importingFlag.compareAndSet(false, true)) return@rememberLauncherForActivityResult
        importing = true
        importStatus = "正在读取 ${uri.lastPathSegment ?: "layout.json"}…"
        Thread {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } ?: throw LayoutImportException("The selected file could not be read")
                text to LayoutJsonImport.parse(text)
            }
            mainHandler.post {
                importingFlag.set(false)
                importing = false
                result.onSuccess { (text, imported) ->
                    layouts = imported
                    sourceText = text
                    selectedIndex = if (imported.size == 1) 0 else -1
                    androidSelection = null
                    macSelection = null
                    reading = null
                    frozenBitmap = null
                    frozenFrame = null
                    frozenDescription = null
                    manualMode = false
                    manualPoints = emptyList()
                    importStatus = if (imported.size == 1) {
                        "已导入 1 个布局对象"
                    } else {
                        "已导入 ${imported.size} 个布局对象，请在下方选择一个"
                    }
                }.onFailure { error ->
                    importStatus = null
                    errorMessage = error.message ?: "布局导入失败"
                }
            }
        }.start()
    }

    // Restore only through the real parsers; a broken preference is discarded, never guessed.
    // Imported layouts stay available for switching back even when a built-in source was last used.
    LaunchedEffect(Unit) {
        val savedImport = CameraLayoutStore.load(context)
        val savedSource = CameraLayoutStore.loadSource(context)
        val savedBuiltIn = CameraLayoutStore.loadBuiltIn(context)
        Thread {
            val macResult = runCatching {
                val text = context.assets.open(MacReferenceLayouts.ASSET_PATH)
                    .use { it.bufferedReader(Charsets.UTF_8).readText() }
                MacReferenceLayouts.load(text)
            }
            val importResult = savedImport?.let { saved ->
                runCatching { CameraLayoutRestore.restore(saved.text, saved.layoutId) }
            }
            mainHandler.post {
                macResult.onSuccess {
                    macLayouts = it
                    macStatus = null
                }.onFailure { error ->
                    macStatus = "Mac 参考预设不可用：${error.message ?: "读取失败"}"
                }
                val restoredImport = importResult?.getOrNull()
                if (importResult != null) {
                    importResult.onSuccess { restored ->
                        layouts = restored.layouts
                        sourceText = savedImport!!.text
                        if (savedSource == null) {
                            selectedIndex = restored.selectedIndex
                            importStatus = if (restored.selectedIndex >= 0) {
                                "已恢复上次选择的布局"
                            } else {
                                "上次布局未选择，请选择"
                            }
                        }
                    }.onFailure {
                        CameraLayoutStore.clearImported(context)
                        importStatus = "上次保存的 layout.json 无法恢复，请重新导入"
                    }
                }

                when (savedSource) {
                    CameraLayoutStore.Source.BUILTIN_ANDROID -> {
                        androidSelection = savedBuiltIn?.let {
                            BuiltInLayouts.selection(it.presetId, it.rotationDeg)
                        }
                        if (androidSelection == null) {
                            errorMessage = "上次保存的 Android 内置预设无法恢复，请重新选择"
                        }
                    }
                    CameraLayoutStore.Source.BUILTIN_MACOS -> {
                        macSelection = savedBuiltIn?.let {
                            MacReferenceLayouts.select(macResult.getOrDefault(emptyList()), it.presetId, it.rotationDeg)
                        }
                        if (macSelection == null) {
                            errorMessage = "上次保存的 Mac 参考预设无法恢复，请重新选择"
                        }
                    }
                    CameraLayoutStore.Source.IMPORTED -> {
                        val restored = restoredImport
                        if (restored == null) {
                            errorMessage = "上次保存的 layout.json 无法恢复，请重新导入"
                        } else {
                            selectedIndex = restored.selectedIndex
                            importStatus = if (restored.selectedIndex >= 0) {
                                "已恢复上次选择的布局"
                            } else {
                                "上次布局未选择，请选择"
                            }
                        }
                    }
                    null -> {
                        // First launch (or no saved selection): start with the named portrait
                        // F68 grid preset. This is an explicit preset, never an auto-detection.
                        if (restoredImport == null) {
                            androidSelection = BuiltInSelection(BuiltInLayouts.DEFAULT, 0)
                        } else {
                            selectedIndex = restoredImport.selectedIndex
                            importStatus = if (restoredImport.selectedIndex >= 0) {
                                "已恢复上次选择的布局"
                            } else {
                                "上次布局未选择，请选择"
                            }
                        }
                    }
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
                reading = null
                cameraRetryAvailable = true
                errorMessage = message
            }

            override fun onPreviewInfo(preview: CameraPreviewInfo) {
                previewInfo = preview
                cameraRetryAvailable = false
                errorMessage = null
                textureView?.let { controller.configurePreview(it) }
            }

            override fun onFrame(frame: GrayFrame) {
                frozenFrame = frame
                frozenBitmap = frame.toDisplayBitmap()
                frozenDescription = frame.description
                reading = null
                manualMode = false
                manualPoints = emptyList()
                errorMessage = null
                status = "已冻结一帧；可点手动四角或恢复预览"
            }

            override fun onRecognition(readingValue: RecognitionReading, generation: Int) {
                reading = readingValue
                val profile = layouts.getOrNull(selectedIndex)?.profile
                val visual = profile?.let { RecognitionVisualState.of(readingValue, it) }
                status = when (visual) {
                    RecognitionVisual.PASS -> "已定位 · CRC PASS"
                    RecognitionVisual.FAILED -> "已定位 · CRC FAIL"
                    RecognitionVisual.LOCATED_WARNING -> "已定位 · 未通过"
                    RecognitionVisual.NOT_LOCATED, null -> "未定位"
                } + if (readingValue.manual) "（手动四角）" else ""
                cameraRetryAvailable = false
                errorMessage = null
            }

            override fun onAnalysisError(message: String, generation: Int) {
                reading = null
                cameraRetryAvailable = true
                errorMessage = message
                status = "分析不可用"
            }

            override fun onAnalysisBusy(message: String, generation: Int) {
                // User-reachable manual double tap only: keep the current reading and do not
                // offer the camera-reopen action for a temporary busy rejection.
                status = message
            }
        }
        onDispose { controller.listener = null }
    }

    LaunchedEffect(resumed) {
        if (!resumed) {
            reading = null
            manualMode = false
            manualPoints = emptyList()
        } else {
            permissionGranted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    val selected = androidLayout ?: macSelection?.layout ?: layouts.getOrNull(selectedIndex)
    val builtInRotation = androidSelection?.rotationDeg ?: macSelection?.rotationDeg ?: 0
    LaunchedEffect(
        resumed,
        permissionGranted,
        surfaceAvailable,
        textureView,
        selected?.layoutId,
        selected?.patternRotationDeg,
        manualMode,
        frozenBitmap,
    ) {
        val view = textureView
        if (resumed && permissionGranted && surfaceAvailable && view != null) {
            controller.start(view)
            if (selected != null && !manualMode && frozenBitmap == null) {
                controller.startLive(selected)
            } else {
                controller.stopLive()
            }
        } else {
            controller.stopLive()
            controller.stop()
        }
    }

    LaunchedEffect(selected?.layoutId, selected?.patternRotationDeg) {
        reading = null
        manualMode = false
        manualPoints = emptyList()
        frozenBitmap = null
        frozenFrame = null
        frozenDescription = null
    }

    LaunchedEffect(selectedIndex, layouts, sourceText) {
        val chosen = layouts.getOrNull(selectedIndex)
        val text = sourceText
        if (chosen != null && !text.isNullOrBlank() && androidSelection == null && macSelection == null) {
            CameraLayoutStore.save(context, text, chosen.layoutId)
        }
    }

    LaunchedEffect(androidSelection) {
        androidSelection?.let {
            CameraLayoutStore.saveBuiltIn(
                context,
                CameraLayoutStore.Source.BUILTIN_ANDROID,
                it.preset.id,
                it.rotationDeg,
            )
        }
    }

    LaunchedEffect(macSelection) {
        macSelection?.let {
            CameraLayoutStore.saveBuiltIn(
                context,
                CameraLayoutStore.Source.BUILTIN_MACOS,
                it.geometry.wire,
                it.rotationDeg,
            )
        }
    }

    val previewRatio = previewInfo?.uprightSize?.let {
        it.width.toFloat() / it.height.toFloat()
    } ?: (4f / 3f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("相机取帧", style = MaterialTheme.typography.titleLarge)
        Text(status, style = MaterialTheme.typography.bodySmall)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(previewRatio)
                .onSizeChanged { viewSize = it }
                .pointerInput(manualMode, frozenFrame, viewSize) {
                    detectTapGestures { tap ->
                        if (!manualMode) return@detectTapGestures
                        val frame = frozenFrame ?: return@detectTapGestures
                        val fit = CameraPreviewGeometry.fit(
                            viewSize.width.toFloat(),
                            viewSize.height.toFloat(),
                            frame.width.toFloat(),
                            frame.height.toFloat(),
                        )
                        val mapped = CameraPreviewGeometry.viewToFrame(
                            fit,
                            tap.x,
                            tap.y,
                            frame.width,
                            frame.height,
                        )
                        if (mapped == null) {
                            status = "点在图像外，请点动态区外边界"
                            return@detectTapGestures
                        }
                        if (manualPoints.size >= 4) manualPoints = emptyList()
                        val next = manualPoints + mapped
                        manualPoints = next
                        status = "已点 ${next.size}/4：动态区外边界 TL/TR/BR/BL"
                        if (next.size == 4) {
                            val layout = selected
                            if (layout == null) {
                                errorMessage = "请先选择内置预设或导入源布局"
                            } else {
                                manualMode = false
                                status = "手动四角分析中…"
                                controller.analyzeManual(frame, layout, next)
                            }
                        }
                    }
                },
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
                    contentDescription = "冻结的 upright 灰度帧",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize(),
                )
            }
            OverlayCanvas(
                reading = reading,
                manualPoints = manualPoints,
                frozenFrame = frozenFrame,
                profile = selected?.profile,
                modifier = Modifier.matchParentSize(),
            )
            if (!permissionGranted) {
                Text("相机权限未授予", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!permissionGranted) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("授予相机权限")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = permissionGranted && resumed && !importing,
                onClick = {
                    controller.stopLive()
                    controller.requestFrame()
                },
            ) {
                Text("拍摄一帧")
            }
            Button(
                enabled = frozenFrame != null,
                onClick = {
                    manualMode = true
                    manualPoints = emptyList()
                    reading = null
                    status = "请点动态区外边界 TL/TR/BR/BL（不含 ArUco）"
                },
            ) {
                Text("手动四角")
            }
            Button(
                enabled = frozenBitmap != null || manualMode,
                onClick = {
                    frozenFrame = null
                    frozenBitmap = null
                    frozenDescription = null
                    manualMode = false
                    manualPoints = emptyList()
                    reading = null
                    status = "实时自动识别"
                },
            ) {
                Text("恢复预览")
            }
        }
        if (manualMode) {
            Text(
                "请按 左上→右上→右下→左下 点击动态编码区外边界；已点 ${manualPoints.size}/4",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        frozenDescription?.let {
            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (cameraRetryAvailable) {
            Button(onClick = {
                val view = textureView ?: return@Button
                errorMessage = null
                cameraRetryAvailable = false
                controller.retryCamera(view, selected)
            }) {
                Text("重新打开相机")
            }
        }

        RecognitionResultBar(reading, selected)

        Text("内置参考预设", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BuiltInLayouts.PRESETS.forEach { preset ->
                FilterChip(
                    selected = androidSelection?.preset?.id == preset.id,
                    onClick = {
                        androidSelection = BuiltInSelection(preset, builtInRotation)
                        macSelection = null
                        selectedIndex = -1
                        reading = null
                        manualMode = false
                        manualPoints = emptyList()
                        frozenBitmap = null
                        frozenFrame = null
                        frozenDescription = null
                        status = "已选内置预设，等待识别…"
                    },
                    label = { Text(preset.label) },
                )
            }
        }

        Text(
            "Mac 参考预设（参考源 $MacReferenceLayouts.SOURCE_REFERENCE，非本机配置）",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MacReferenceLayouts.GEOMETRIES.forEach { geometry ->
                FilterChip(
                    enabled = macLayouts.isNotEmpty(),
                    selected = macSelection?.geometry == geometry,
                    onClick = {
                        val selection = MacReferenceLayouts.select(
                            macLayouts,
                            geometry.wire,
                            builtInRotation,
                        )
                        if (selection == null) {
                            errorMessage = "Mac 参考预设缺少 ${geometry.wire} ${builtInRotation}°"
                        } else {
                            androidSelection = null
                            macSelection = selection
                            selectedIndex = -1
                            reading = null
                            manualMode = false
                            manualPoints = emptyList()
                            frozenBitmap = null
                            frozenFrame = null
                            frozenDescription = null
                            status = "已选 Mac 参考预设，等待识别…"
                        }
                    },
                    label = { Text(MacReferenceLayouts.label(geometry)) },
                )
            }
        }
        macStatus?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (androidSelection != null || macSelection != null) {
            Text("内置预设 pattern rotation（切换清空旧结果）", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BuiltInLayouts.ROTATIONS_DEG.forEach { rotation ->
                    FilterChip(
                        selected = builtInRotation == rotation,
                        onClick = {
                            val android = androidSelection
                            val mac = macSelection
                            if (android != null) {
                                androidSelection = android.copy(rotationDeg = rotation)
                            } else if (mac != null) {
                                val selection = MacReferenceLayouts.select(
                                    macLayouts,
                                    mac.geometry.wire,
                                    rotation,
                                )
                                if (selection == null) {
                                    errorMessage = "Mac 参考预设缺少 ${mac.geometry.wire} ${rotation}°"
                                    return@FilterChip
                                }
                                macSelection = selection
                            }
                            reading = null
                            manualMode = false
                            manualPoints = emptyList()
                            frozenBitmap = null
                            frozenFrame = null
                            frozenDescription = null
                        },
                        label = { Text("$rotation°") },
                    )
                }
            }
        }

        val activeBuiltInLabel = androidSelection?.preset?.label
            ?: macSelection?.let { MacReferenceLayouts.label(it.geometry) }
        val activeBuiltInReference = androidSelection?.preset?.sourceReference
            ?: macSelection?.let { MacReferenceLayouts.SOURCE_REFERENCE }
        if (activeBuiltInLabel != null && activeBuiltInReference != null) {
            Text(
                "当前内置参考预设：$activeBuiltInLabel · rotation $builtInRotation°（非自动检测）",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "源参考尺寸 $activeBuiltInReference；若被测设备几何不同，仍需导入该设备生成的 layout.json。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("导入源布局", style = MaterialTheme.typography.labelLarge)
        Button(
            enabled = !importing,
            onClick = { importLauncher.launch(arrayOf("*/*")) },
        ) {
            Text(if (importing) "读取中…" else "导入 layout.json")
        }
        importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (layouts.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                layouts.forEachIndexed { index, layout ->
                    FilterChip(
                        selected = index == selectedIndex && androidSelection == null && macSelection == null,
                        onClick = {
                            selectedIndex = index
                            androidSelection = null
                            macSelection = null
                            reading = null
                            manualMode = false
                            manualPoints = emptyList()
                            frozenBitmap = null
                            frozenFrame = null
                            frozenDescription = null
                        },
                        label = { Text(layout.layoutId) },
                    )
                }
            }
        }
        if (layouts.size > 1 && selectedIndex < 0 && androidSelection == null && macSelection == null) {
            Text("请选择要使用的源布局。", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun OverlayCanvas(
    reading: RecognitionReading?,
    manualPoints: List<DoublePoint>,
    frozenFrame: GrayFrame?,
    profile: dev.lumaphase.android.protocol.LayoutProfile?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val frameWidth = reading?.frameWidthPx ?: frozenFrame?.width
        val frameHeight = reading?.frameHeightPx ?: frozenFrame?.height
        if (frameWidth != null && frameHeight != null && frameWidth > 0 && frameHeight > 0) {
            val fit = CameraPreviewGeometry.fit(
                size.width,
                size.height,
                frameWidth.toFloat(),
                frameHeight.toFloat(),
            )
            fun screen(point: DoublePoint) = Offset(
                fit.offsetX + point.x.toFloat() * fit.scale,
                fit.offsetY + point.y.toFloat() * fit.scale,
            )
            val corners = reading?.markerFrameCorners.orEmpty()
            val visual = reading?.let { value ->
                profile?.let { RecognitionVisualState.of(value, it) }
            }
            val color = when (visual) {
                RecognitionVisual.PASS -> Color(0xFF00C853)
                RecognitionVisual.FAILED -> Color(0xFFFF1744)
                RecognitionVisual.LOCATED_WARNING -> Color(0xFFFFC400)
                RecognitionVisual.NOT_LOCATED, null -> Color(0xFFFF5252)
            }
            corners.forEach { group ->
                if (group.size >= 4) {
                    for (index in 0 until 4) {
                        drawLine(
                            color = color,
                            start = screen(group[index]),
                            end = screen(group[(index + 1) % 4]),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                    group.forEach { drawCircle(color, radius = 3.dp.toPx(), center = screen(it)) }
                }
            }
            if (manualPoints.isNotEmpty()) {
                for (index in manualPoints.indices) {
                    val point = screen(manualPoints[index])
                    drawCircle(Color.Yellow, radius = 5.dp.toPx(), center = point)
                    if (index > 0) {
                        drawLine(
                            color = Color.Yellow,
                            start = screen(manualPoints[index - 1]),
                            end = point,
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecognitionResultBar(reading: RecognitionReading?, selected: ImportedLayout?) {
    if (selected == null) {
        Text("未配置源布局：请选择内置参考预设或导入 layout.json", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (reading == null) {
        Text("等待识别…", style = MaterialTheme.typography.bodySmall)
        return
    }
    val visual = RecognitionVisualState.of(reading, selected.profile)
    val located = if (reading.located) {
        "定位 ${if (reading.manual) "手动四角" else "4/4"} · RMS ${reading.geometryErrorPx?.let { format1(it) } ?: "—"} px"
    } else {
        "未定位 · 缺 IDs ${reading.missingMarkerIds.joinToString(",")}"
    }
    Text(
        "$located · $visual · ${reading.failureReason ?: ""}",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
    if (!reading.located) return
    if (reading.status == RecognitionStatus.NO_DECODE && reading.bands.isEmpty()) {
        Text(
            "不可判：${reading.failureReason ?: "未知原因"}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    reading.bands.forEach { band -> Text(bandLine(band), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    if (reading.bands.size > 1) {
        Text(
            "单排薄带结果逐带显示；不跨 band 合成 payload。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun bandLine(band: BandReading): String {
    val decode = band.decode
    if (decode == null) {
        return "band ${band.bandIndex}: 不可判 ${band.reason ?: ""} " +
            "missing=${band.missingModules.size} refMissing=${band.referenceMissing}"
    }
    val payload = when (decode.payloadCandidates.size) {
        0 -> "payload 无"
        1 -> String.format(Locale.ROOT, "payload 0x%04X", decode.payloadCandidates[0])
        else -> "payload 多候选 ${decode.payloadCandidates.size}"
    }
    return "band ${band.bandIndex}: $payload · CRC ${decode.crcStatus.wire} · " +
        "Runner ${decode.runnerStatus.wire} · hard=${decode.hardBitCount}" +
        if (decode.truncated) " · TRUNCATED" else ""
}

private fun format1(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

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
