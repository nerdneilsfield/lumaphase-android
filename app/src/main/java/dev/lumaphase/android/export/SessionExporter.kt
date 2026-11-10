package dev.lumaphase.android.export

import dev.lumaphase.android.layout.LayoutJson
import dev.lumaphase.android.session.SealedSession
import dev.lumaphase.android.session.SessionFiles
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Writes the six files of one session.
 *
 * The two JSONL logs are written while the session runs, so this writes the four documents
 * that can only be produced after sealing (manifest, layouts, display profile, summary) into
 * the same directory, and copies all six out to the user's chosen destination.
 *
 * A write failure is reported, never hidden: an export that failed must not be presented as
 * a complete package.
 */
object SessionExporter {

    /**
     * Writes the four sealed documents next to the two JSONL files.
     * Returns null on success, or the failure to report.
     */
    fun writeSealedDocuments(sessionDirectory: File, sealed: SealedSession): String? {
        if (!sessionDirectory.exists() && !sessionDirectory.mkdirs()) {
            return "could not create ${sessionDirectory.absolutePath}"
        }
        return try {
            writeText(sessionDirectory, SessionFiles.MANIFEST) { writer ->
                SessionJson.writeManifest(writer, sealed.manifest)
            }
            writeText(sessionDirectory, SessionFiles.LAYOUT) { writer ->
                LayoutJson.write(writer, sealed.layouts)
            }
            writeText(sessionDirectory, SessionFiles.DISPLAY_PROFILE) { writer ->
                SessionJson.writeDisplayProfile(writer, sealed.displayProfile)
            }
            writeText(sessionDirectory, SessionFiles.SESSION_SUMMARY) { writer ->
                SessionJson.writeSessionSummary(writer, sealed.summary)
            }
            null
        } catch (error: IOException) {
            "writing the session documents failed: ${error.message}"
        }
    }

    /**
     * Copies the six files into a destination opened by [openTarget], which returns one
     * output stream per file name. Returns null on success.
     */
    fun copySession(
        sessionDirectory: File,
        openTarget: (fileName: String) -> OutputStream?,
    ): String? {
        for (fileName in SessionFiles.ALL) {
            val source = File(sessionDirectory, fileName)
            if (!source.exists()) return "the session file $fileName is missing"
            val target = openTarget(fileName)
                ?: return "the destination for $fileName could not be opened"
            try {
                target.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
            } catch (error: IOException) {
                return "copying $fileName failed: ${error.message}"
            }
        }
        return null
    }

    private fun writeText(
        directory: File,
        fileName: String,
        write: (java.io.Writer) -> Unit,
    ) {
        File(directory, fileName).outputStream().use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                write(writer)
                writer.flush()
            }
        }
    }
}
