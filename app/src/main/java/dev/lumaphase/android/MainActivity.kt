package dev.lumaphase.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.lumaphase.android.protocol.EncodedPattern
import dev.lumaphase.android.protocol.LayoutProfile
import dev.lumaphase.android.protocol.PatternEncoder
import java.util.Locale

/**
 * T1 host for the shared OPTC-1 encoder. Compose only presents the configuration and
 * the current encoded state; it does not drive the optical pattern. Frame scheduling
 * is added with the render thread in T3.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EncoderScreen()
                }
            }
        }
    }
}

@Composable
private fun EncoderScreen() {
    var profile by remember { mutableStateOf(LayoutProfile.F68) }
    var renderSeqText by remember { mutableStateOf("0") }
    val parsed = renderSeqText.trim().toULongOrNull()
    val encoded = parsed?.let { PatternEncoder.encode(it, profile) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OPTC-1 encoder", style = MaterialTheme.typography.titleLarge)
        Text(
            "protocol OPTC-1 · schema 1.0 · levels 32/224",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutProfile.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == profile,
                    onClick = { profile = candidate },
                    label = { Text("${candidate.name} · ${candidate.moduleCount}") },
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
        }
    }
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
                "L=32 dark and H=224 light. This is not the optical output; the " +
                "drawable-pixel surface is not implemented yet.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EncoderScreenPreview() {
    MaterialTheme {
        EncoderScreen()
    }
}
