package de.millione.schulranzen.app.about

import android.content.Context
import android.os.StrictMode
import androidx.startup.Initializer
import de.millione.schulranzen.app.BuildConfig

/**
 * Runs before anything else in the process (declared in the manifest), so a
 * crash while the app itself starts is caught too, without any code in
 * MainActivity or the Application class.
 */
class StartupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        CrashCatcher.install(context)
        // Only logs, so existing findings do not break debug builds. R8 removes this from release.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
