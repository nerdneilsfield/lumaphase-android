package dev.lumaphase.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.lumaphase.android.CR002DisplayActivity
import dev.lumaphase.android.camera.CameraTestActivity
import dev.lumaphase.android.layout.LayoutChoice
import dev.lumaphase.android.layout.LayoutExport
import dev.lumaphase.android.layout.PatternLayoutBuilder
import dev.lumaphase.android.layout.PreviewedLayout
import dev.lumaphase.android.protocol.EncodedPattern
import dev.lumaphase.android.protocol.PatternEncoder
import dev.lumaphase.android.export.SessionExporter
import dev.lumaphase.android.render.DynamicRenderThread
import dev.lumaphase.android.session.SessionConstants
import java.io.File
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * Settings screen for the static OPTC-1 target: layout geometry/payload, pattern rotation
 * and the render sequence, with an entry to the real optical preview and a system document
 * export of `layout.json`. Compose only configures; the optical pattern itself is drawn
 * by [dev.lumaphase.android.render.StaticPatternSurface], never by Compose.
 *
 * The export writes the layout snapshot returned by the preview, so the file always
 * describes the pixels that were really drawn. It is never rebuilt from window metrics.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    var layoutChoice by remember { mutableStateOf(LayoutChoice.F68_GRID_LARGE) }
    var rotationDeg by remember { mutableIntStateOf(0) }
    var renderSeqText by remember { mutableStateOf("0") }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var previewedLayout by remember { mutableStateOf<PreviewedLayout?>(null) }
    var dynamicSummary by remember { mutableStateOf<String?>(null) }
    var sessionDirectory by remember { mutableStateOf<String?>(null) }
    var sessionExportStatus by remember { mutableStateOf<String?>(null) }

    val parsed = renderSeqText.trim().toULongOrNull()
    val encoded = parsed?.let { PatternEncoder.encode(it, layoutChoice.layoutProfile) }

    val previewLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        previewedLayout = if (result.resultCode == Activity.RESULT_OK) {
            PatternPreviewActivity.previewedLayout(result.data)
        } else {
            null
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        exportStatus = if (result.resultCode != Activity.RESULT_OK || uri == null) {
            "Export cancelled."
        } else {
            writeLayoutSnapshot(context, uri, previewedLayout)
        }
    }

    val dynamicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            dynamicSummary = DynamicPreviewActivity.summary(result.data)
            sessionDirectory = DynamicPreviewActivity.sessionDirectory(result.data)
        } else {
            dynamicSummary = null
            sessionDirectory = null
        }
    }

    val cr002Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        dynamicSummary = CR002DisplayActivity.summary(result.data)
        sessionDirectory = CR002DisplayActivity.sessionDirectory(result.data)
    }

    val sessionTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val directory = sessionDirectory
        sessionExportStatus = when {
            treeUri == null -> "Session export cancelled."
            directory == null -> "No sealed session to export."
            else -> copySessionToTree(context, treeUri, File(directory))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OPTC-1 pattern", style = MaterialTheme.typography.titleLarge)
        Text(
            "protocol OPTC-1 · schema 1.1 · levels 32/224",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Layout geometry and payload", style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutChoice.entries.chunked(2).forEach { rowChoices ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowChoices.forEach { candidate ->
                        FilterChip(
                            selected = candidate == layoutChoice,
                            onClick = {
                                layoutChoice = candidate
                                previewedLayout = null
                            },
                            label = { Text(candidate.label) },
                        )
                    }
                }
            }
        }
        Text(
            "Grid geometry follows the Surface orientation: 9×8 (F68) or 9×6 (C52) when the " +
                "display is portrait or square, 6×12 (F68) or 5×12 (C52) when it is wider " +
                "than tall. Pattern rotation does not change this choice. The single-row " +
                "F68/C52 layouts remain available.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "大Tag（利用留白）：载荷网格、dynamic_rect、modules 和 padding 每个像素保持不变，" +
                "只把四角 Tag 放大到屏幕长边两侧的空白；1080×2400 在 0/180° 使用 64 px cell，" +
                "90/270° 使用 48 px cell。旧 4 个选项仍可按原样使用。",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("pattern_rotation_deg", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PatternLayoutBuilder.ROTATIONS_DEG.forEach { candidate ->
                FilterChip(
                    selected = candidate == rotationDeg,
                    onClick = {
                        rotationDeg = candidate
                        previewedLayout = null
                    },
                    label = { Text("$candidate°") },
                )
            }
        }

        OutlinedTextField(
            value = renderSeqText,
            onValueChange = { renderSeqText = it },
            label = { Text("render_seq (unsigned 64-bit, decimal)") },
            isError = renderSeqText.isNotBlank() && parsed == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (encoded == null) {
            Text(
                "Enter a decimal render_seq in [0, 2^64-1].",
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            EncodedReadout(encoded)
            Button(
                onClick = {
                    previewLauncher.launch(
                        PatternPreviewActivity.intent(
                            context,
                            layoutChoice,
                            rotationDeg,
                            encoded.renderSeq,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open the static optical pattern")
            }
        }

        val snapshot = previewedLayout
        Button(
            onClick = {
                exportStatus = null
                exportLauncher.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "layout.json"),
                )
            },
            enabled = snapshot != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export layout.json")
        }

        Button(
            onClick = {
                dynamicSummary = null
                sessionDirectory = null
                sessionExportStatus = null
                dynamicLauncher.launch(
                    DynamicPreviewActivity.intent(
                        context,
                        layoutChoice,
                        rotationDeg,
                        SessionConstants.EXPERIMENT_PROFILE,
                        startSession = true,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start session (${SessionConstants.EXPERIMENT_PROFILE})")
        }
        Button(
            onClick = {
                dynamicSummary = null
                sessionDirectory = null
                dynamicLauncher.launch(
                    DynamicPreviewActivity.intent(
                        context,
                        layoutChoice,
                        rotationDeg,
                        SessionConstants.EXPERIMENT_PROFILE,
                        startSession = false,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open the pattern without a session")
        }
        Text(
            "A session runs ${SessionConstants.EXPERIMENT_PROFILE}: warm-up, start card, " +
                "dynamic pattern, end card, then the " +
                "${DynamicPreviewActivity.DRAIN_MS / 1000} s drain before sealing. It requests " +
                "${DynamicRenderThread.REQUESTED_REFRESH_HZ} Hz with " +
                "CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS, which is a request, not evidence that the " +
                "panel runs at that rate.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("CR-002 显示诊断", style = MaterialTheme.typography.labelLarge)
        Text(
            "TAG 使用固定横屏大 Tag→RUN，需点画面继续；A 为六段 1 s settle + 3 s 采样；" +
                "B 为 60 s VSync 黑白；A/B 不强制横屏。普通 OPTC 会话保持不变。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CR002DisplayActivity.Mode.entries.forEach { cr002Mode ->
                Button(
                    onClick = {
                        dynamicSummary = null
                        sessionDirectory = null
                        sessionExportStatus = null
                        cr002Launcher.launch(
                            CR002DisplayActivity.intent(
                                context,
                                cr002Mode,
                                layoutChoice,
                                rotationDeg,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (cr002Mode == CR002DisplayActivity.Mode.TAG) "TAG 横屏" else cr002Mode.wire)
                }
            }
        }

        Text("相机识别测试", style = MaterialTheme.typography.labelLarge)
        Text(
            "导入源 layout.json 后连续识别光学时间码，也可冻结画面手动选择四角。",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { context.startActivity(Intent(context, CameraTestActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("打开相机取帧")
        }
        dynamicSummary?.let { summary ->
            Text(summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { sessionTreeLauncher.launch(null) },
            enabled = sessionDirectory != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export session (six files)")
        }
        sessionExportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            if (snapshot == null) {
                "Export stays off until the pattern has been opened at least once. " +
                    "layout.json must describe the pixels that were actually rendered, " +
                    "so it is not estimated from window or display metrics."
            } else {
                "layout.json will contain the layout the preview actually rendered: " +
                    "${snapshot.description}. Changing the layout choice or the rotation " +
                    "clears this until the pattern is opened again."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Writes the snapshot bytes produced by the preview. The geometry is never rebuilt here:
 * [LayoutExport.write] only has the text that the preview serialised from the layout it
 * drew.
 */
private fun writeLayoutSnapshot(
    context: Context,
    uri: Uri,
    snapshot: PreviewedLayout?,
): String = runCatching {
    val stream = context.contentResolver.openOutputStream(uri)
        ?: error("the destination could not be opened")
    stream.use { output ->
        OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
            check(LayoutExport.write(writer, snapshot)) {
                "no rendered layout has been captured yet"
            }
        }
    }
    "Wrote the layout the preview rendered: ${snapshot?.description}."
}.getOrElse { error -> "Export failed: ${error.message}" }

/**
 * Creates `session_<uuid>/` under the chosen tree and copies the six contract files into it.
 */
private fun copySessionToTree(context: Context, treeUri: Uri, sessionDirectory: File): String {
    val resolver = context.contentResolver
    return runCatching {
        val treeDocument = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val sessionDocument = DocumentsContract.createDocument(
            resolver,
            treeDocument,
            DocumentsContract.Document.MIME_TYPE_DIR,
            sessionDirectory.name,
        ) ?: error("the session folder could not be created")

        val failure = SessionExporter.copySession(sessionDirectory) { fileName ->
            val file = DocumentsContract.createDocument(
                resolver,
                sessionDocument,
                "application/json",
                fileName,
            ) ?: return@copySession null
            resolver.openOutputStream(file)
        }
        failure?.let { error(it) }
        "Exported six files into ${sessionDirectory.name}."
    }.getOrElse { error -> "Session export failed: ${error.message}" }
}

/**
 * Grey for one module level in the schematic bar: the 8-bit SDR level is used directly
 * as R=G=B, so L=32 renders as 0x20 and H=224 as 0xE0.
 */
private fun moduleSchematicGrey(level: Int): Color = Color(level / 255f, level / 255f, level / 255f)

@Composable
private fun EncodedReadout(pattern: EncodedPattern) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("render_seq ${pattern.renderSeq}", fontFamily = FontFamily.Monospace)
        Text("payload_n ${pattern.payloadN}", fontFamily = FontFamily.Monospace)
        Text(
            String.format(
                Locale.ROOT,
                "gray 0x%04X   crc 0x%02X",
                pattern.gray,
                pattern.crc8,
            ),
            fontFamily = FontFamily.Monospace,
        )
        Text("modules ${pattern.moduleCodes.size}", fontFamily = FontFamily.Monospace)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            pattern.moduleCodes.forEach { level ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(moduleSchematicGrey(level)),
                )
            }
        }
        Text(
            "Configuration-page schematic only: one grey rectangle per module, " +
                "L=32 dark and H=224 light. This is not the optical output; the real " +
                "target is the full-screen pattern opened by the button below.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen()
    }
}
