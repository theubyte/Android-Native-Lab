package de.millione.schulranzen.app.notebook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen

/**
 * Samsung opens Air Command when the pen button is pressed while hovering,
 * on top of any app; there is no API for an app to switch that off. Only
 * the user can, in the S Pen settings ("Air Command mit Stifttaste öffnen").
 * Then the button reaches the app and opens the tool ring instead.
 */
object AirCommand {
    val isSamsung: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Opens the S Pen / Air Command settings, or the main settings if this One UI names them differently. */
    fun openSettings(context: Context) {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AirCommandSettingsActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$SPenSettingsActivity")),
            Intent("com.samsung.android.settings.SPEN_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return
        }
    }
}

@Composable
fun AirCommandHint(modifier: Modifier = Modifier) {
    val c = Ranzen.colors
    val context = LocalContext.current
    Column(modifier) {
        Text("Air Command stört den Werkzeugring?", style = Ranzen.type.label, color = c.ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "Samsung lässt Apps das nicht abschalten. Einmal in den Einstellungen: Erweiterte Funktionen → S Pen → " +
                "Air Command → „Air Command mit Stifttaste öffnen“ aus. Danach öffnet die Stifttaste hier den Ring.",
            style = Ranzen.type.caption, color = c.inkMuted,
        )
        Spacer(Modifier.height(6.dp))
        QuietButton("S-Pen-Einstellungen öffnen", { AirCommand.openSettings(context) }, color = c.signal)
    }
}
