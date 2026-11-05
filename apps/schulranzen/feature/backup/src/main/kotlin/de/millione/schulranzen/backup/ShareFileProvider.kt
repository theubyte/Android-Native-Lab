package de.millione.schulranzen.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Its own class: two plain FileProviders in one app would collide in the merged manifest. */
class ShareFileProvider : FileProvider() {
    companion object {
        /** Opens the system's share sheet for a file from [BackupManager.createShareFile]. */
        fun share(context: Context, file: File) {
            val uri = getUriForFile(context, context.packageName + ".backup.share", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Abgleich an anderes Gerät").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
