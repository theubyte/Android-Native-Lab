package de.millione.schulranzen.app.notebook

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands a PDF to another app through the system's share sheet. */
object PdfShare {
    /** False when no app can take it. */
    fun open(context: Context, file: File): Boolean {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(send, "PDF teilen").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
