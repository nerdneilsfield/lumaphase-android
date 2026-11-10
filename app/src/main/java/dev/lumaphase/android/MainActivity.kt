package dev.lumaphase.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import dev.lumaphase.android.layout.LayoutExport
import dev.lumaphase.android.layout.PatternLayoutBuilder
import dev.lumaphase.android.layout.PreviewedLayout
import dev.lumaphase.android.protocol.EncodedPattern
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * Settings screen for the static OPTC-1 target: layout profile, pattern rotation and the
 * render sequence, with an entry to the real optical preview and a system document
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
    var profile by remember { mutableStateOf(LayoutProfile.F68) }
    var rotationDeg by remember { mutableIntStateOf(0) }
    var renderSeqText by remember { mutableStateOf("0") }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var previewedLayout by remember { mutableStateOf<PreviewedLayout?>(null) }

    val parsed = renderSeqText.trim().toULongOrNull()
    val encoded = parsed?.let { PatternEncoder.encode(it, profile) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OPTC-1 pattern", style = MaterialTheme.typography.titleLarge)
        Text(
            "protocol OPTC-1 · schema 1.0 · levels 32/224",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Layout profile", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutProfile.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == profile,
                    onClick = {
                        profile = candidate
                        previewedLayout = null
                    },
                    label = { Text("${candidate.name} · ${candidate.moduleCount}") },
                )
            }
        }

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
                        PatternPreviewActivity.intent(context, profile, rotationDeg, encoded.renderSeq),
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
        Text(
            if (snapshot == null) {
                "Export stays off until the pattern has been opened at least once. " +
                    "layout.json must describe the pixels that were actually rendered, " +
                    "so it is not estimated from window or display metrics."
            } else {
                "layout.json will contain the layout the preview actually rendered: " +
                    "${snapshot.description}. Changing the profile or the rotation " +
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
