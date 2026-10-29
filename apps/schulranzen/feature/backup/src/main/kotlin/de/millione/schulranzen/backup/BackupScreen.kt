package de.millione.schulranzen.backup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.backup.ArchiveKind
import de.millione.schulranzen.core.backup.ImportedLibrary
import de.millione.schulranzen.core.backup.NotebookKind
import de.millione.schulranzen.core.backup.RemoteVersion
import de.millione.schulranzen.core.backup.V15Backup
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.TactileKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Icons of this screen, in the house line style. */
object BackupIcons {
    /** Arrow down into a tray: save a copy. */
    val Save = RanzenIcons.line("save") {
        moveTo(12f, 4f); lineTo(12f, 14.5f); moveTo(7.5f, 10f); lineTo(12f, 14.5f); lineTo(16.5f, 10f)
        moveTo(4.5f, 15f); lineTo(4.5f, 19.5f); lineTo(19.5f, 19.5f); lineTo(19.5f, 15f)
    }
    /** Arrow up out of a tray: restore / upload. */
    val Restore = RanzenIcons.line("restore") {
        moveTo(12f, 14.5f); lineTo(12f, 4f); moveTo(7.5f, 8.5f); lineTo(12f, 4f); lineTo(16.5f, 8.5f)
        moveTo(4.5f, 15f); lineTo(4.5f, 19.5f); lineTo(19.5f, 19.5f); lineTo(19.5f, 15f)
    }
    /** Two stacked drive units: the home server. */
    val Server = RanzenIcons.line("server") {
        moveTo(4.5f, 5f); lineTo(19.5f, 5f); lineTo(19.5f, 11f); lineTo(4.5f, 11f); close()
        moveTo(4.5f, 13f); lineTo(19.5f, 13f); lineTo(19.5f, 19f); lineTo(4.5f, 19f); close()
        moveTo(7.5f, 8f); lineTo(8f, 8f); moveTo(7.5f, 16f); lineTo(8f, 16f)
    }
    /** Old satchel with an arrow: take over from V15. */
    val Import = RanzenIcons.line("import") {
        moveTo(5f, 9f); lineTo(19f, 9f); lineTo(19f, 19.5f); lineTo(5f, 19.5f); close()
        moveTo(9f, 9f); lineTo(9f, 6.5f); curveTo(9f, 4.5f, 15f, 4.5f, 15f, 6.5f); lineTo(15f, 9f)
        moveTo(12f, 11.5f); lineTo(12f, 17f); moveTo(9.5f, 14.5f); lineTo(12f, 17f); lineTo(14.5f, 14.5f)
    }
}

/** Remembers one manager per screen; pass your own to set the hooks. */
@Composable
fun rememberBackupManager(): BackupManager {
    val ctx = LocalContext.current.applicationContext
    return remember { BackupManager(ctx) }
}

private sealed interface Sheet {
    data class ConfirmRestore(val info: ArchiveInfo, val source: String) : Sheet
    data class Preview(val library: ImportedLibrary, val file: File) : Sheet
}

/**
 * Backup, restore, Unraid sync and the import from the old app.
 * [onImportV15] stores the read library (implement with V15Import.run and
 * an ImportSink); the library is closed afterwards. [manager] carries the
 * database hooks (beforeBackup / beforeRestore).
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onImportV15: suspend (ImportedLibrary) -> Unit,
    manager: BackupManager = rememberBackupManager(),
) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val account = remember { SyncAccount(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val progress by manager.progress.collectAsState()
    val restart by manager.restartRequired.collectAsState()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val busy = progress is BackupProgress.Working

    fun fail(e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        manager.report(BackupProgress.Failed(BackupManager.messageOf(e)))
    }

    /** A picked/downloaded archive: own backup → confirm restore, V15 → read and preview. */
    suspend fun handle(info: ArchiveInfo, source: String) {
        when (info.kind) {
            ArchiveKind.OWN -> sheet = Sheet.ConfirmRestore(info, source)
            ArchiveKind.V15 -> {
                manager.report(BackupProgress.Working("Alte Daten lesen", 0f))
                val lib = withContext(Dispatchers.IO) {
                    V15Backup.read(info.file) { label, f -> manager.report(BackupProgress.Working(label, f)) }
                }
                manager.report(BackupProgress.Idle)
                sheet = Sheet.Preview(lib, info.file)
            }
            ArchiveKind.UNKNOWN -> manager.discard(info)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch { runCatching { manager.exportTo(uri) }.onFailure(::fail) }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runCatching { handle(manager.open(uri), "Datei") }.onFailure(::fail) }
    }
    val zipTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

    DisposableEffect(Unit) {
        onDispose {
            (sheet as? Sheet.Preview)?.let { it.library.close(); it.file.delete() }
            manager.resetProgress()
        }
    }
    BackHandler(enabled = sheet != null && !busy) { sheet = closeSheet(sheet) }

    Box(Modifier.fillMaxSize().background(c.desk)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TactileKey(onBack, contentDescription = "Zurück") { RanzenIcon(RanzenIcons.Back) }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sichern & Übernehmen", style = Ranzen.type.title, color = c.ink)
                    Text("Vollbackup, Unraid-Server und Daten aus der alten App", style = Ranzen.type.label, color = c.inkMuted)
                }
            }
            Spacer(Modifier.height(18.dp))
            StatusStrip(progress) { manager.resetProgress() }
            Spacer(Modifier.height(18.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 980.dp
                val device: @Composable () -> Unit = {
                    DeviceSection(
                        busy = busy,
                        onSave = { saveLauncher.launch(manager.suggestedName()) },
                        onOpen = { openLauncher.launch(zipTypes) },
                    )
                }
                val v15: @Composable () -> Unit = { V15Section(busy) { openLauncher.launch(zipTypes) } }
                val server: @Composable () -> Unit = {
                    ServerSection(account, manager, busy, scope, onFail = ::fail) { file ->
                        runCatching { handle(manager.inspect(file), "Unraid-Server") }.onFailure(::fail)
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            device(); v15()
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { server() }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        device(); server(); v15()
                    }
                }
            }
        }

        val current = sheet
        AnimatedVisibility(current != null || restart, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(c.desk.copy(alpha = 0.72f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                when {
                    restart -> RestartSheet(progress) { context.findActivity()?.let { BackupManager.restartApp(it) } }
                    current is Sheet.ConfirmRestore -> ConfirmRestoreSheet(current, busy,
                        onCancel = { manager.discard(current.info); sheet = null },
                        onRestore = {
                            scope.launch {
                                runCatching { manager.restore(current.info.file) }.onFailure { fail(it); sheet = null }
                            }
                        },
                    )
                    current is Sheet.Preview -> PreviewSheet(current.library, busy,
                        onCancel = { sheet = closeSheet(current) },
                        onImport = {
                            scope.launch {
                                manager.report(BackupProgress.Working("Daten übernehmen", null))
                                runCatching { onImportV15(current.library) }
                                    .onSuccess {
                                        val lib = current.library
                                        manager.report(BackupProgress.Done("Übernommen: ${lib.notebooks.count { !it.trashed }} Hefte, ${lib.books.count { !it.trashed }} Bücher."))
                                        sheet = closeSheet(current)
                                    }
                                    .onFailure { fail(it); sheet = closeSheet(current) }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun closeSheet(s: Sheet?): Sheet? {
    if (s is Sheet.Preview) { s.library.close(); s.file.delete() }
    if (s is Sheet.ConfirmRestore) s.info.file.delete()
    return null
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) { if (ctx is Activity) return ctx; ctx = ctx.baseContext }
    return null
}

// --- building blocks ---------------------------------------------------

@Composable
private fun Section(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    val c = Ranzen.colors
    PaperPanel(Modifier.fillMaxWidth()) {
        Row {
            // Accent edge like a register tab.
            Box(Modifier.width(5.dp).heightIn(min = 120.dp).background(accent))
            Column(Modifier.padding(26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RanzenIcon(icon, tint = c.ink, size = 26.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = Ranzen.type.heading, color = c.ink)
                }
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = Ranzen.type.body, color = c.inkMuted)
                Spacer(Modifier.height(18.dp))
                content()
            }
        }
    }
}

@Composable
private fun StatusStrip(p: BackupProgress, onDismiss: () -> Unit) {
    val c = Ranzen.colors
    val (lamp, text) = when (p) {
        BackupProgress.Idle -> c.inkFaint to "Bereit."
        is BackupProgress.Working -> SubjectPalette.of(2) to (p.label + (p.fraction?.let { " · ${(it * 100).toInt()} %" } ?: " …"))
        is BackupProgress.Done -> SubjectPalette.of(3) to p.message
        is BackupProgress.Failed -> c.signal to p.message
    }
    PaperPanel(Modifier.fillMaxWidth(), corner = 16.dp, depth = 0.5f) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(lamp, size = 10.dp, glow = p !is BackupProgress.Idle)
                Spacer(Modifier.width(12.dp))
                Text(text, style = Ranzen.type.label, color = if (p is BackupProgress.Failed) c.signal else c.ink, modifier = Modifier.weight(1f))
                if (p is BackupProgress.Done || p is BackupProgress.Failed) QuietButton("OK", onDismiss)
            }
            if (p is BackupProgress.Working) {
                Spacer(Modifier.height(10.dp))
                ProgressBar(p.fraction)
            }
        }
    }
}

/** A slim retro gauge; without a fraction a short segment runs back and forth. */
@Composable
private fun ProgressBar(fraction: Float?) {
    val c = Ranzen.colors
    val t = rememberInfiniteTransition(label = "bar")
    val run by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "run")
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)
            .drawBehind {
                if (fraction != null) drawRect(c.signal, size = size.copy(width = size.width * fraction.coerceIn(0f, 1f)))
                else {
                    val w = size.width * 0.25f
                    drawRect(c.signal, topLeft = Offset((size.width - w) * run, 0f), size = size.copy(width = w))
                }
            },
    )
}

@Composable
private fun LineField(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, modifier: Modifier = Modifier,
) {
    val c = Ranzen.colors
    Column(modifier) {
        Text(label.uppercase(), style = Ranzen.type.caption, color = c.inkMuted)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value, onChange, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = Ranzen.type.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.signal),
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                .drawBehind { drawLine(c.line, Offset(0f, size.height + 4.dp.toPx()), Offset(size.width, size.height + 4.dp.toPx()), 1.5.dp.toPx()) },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = Ranzen.type.body, color = c.inkFaint)
                    inner()
                }
            },
        )
    }
}

// --- sections ----------------------------------------------------------

@Composable
private fun DeviceSection(busy: Boolean, onSave: () -> Unit, onOpen: () -> Unit) {
    Section(BackupIcons.Save, "Auf diesem Gerät", "Alle Hefte, Bücher, Lerndaten und Einstellungen in eine Datei sichern, z. B. auf eine SD-Karte oder in den Download-Ordner.", SubjectPalette.of(0)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalButton("Backup speichern …", { if (!busy) onSave() }, icon = BackupIcons.Save)
            Spacer(Modifier.width(12.dp))
            QuietButton("Backup wiederherstellen …", { if (!busy) onOpen() }, color = Ranzen.colors.ink)
        }
    }
}

@Composable
private fun V15Section(busy: Boolean, onPick: () -> Unit) {
    val c = Ranzen.colors
    Section(BackupIcons.Import, "Aus alter App übernehmen (V15)", "Hefte, Arbeitsblätter, Bücher mit Notizen, Fächer, Prüfungen und Karteikarten aus dem Vollbackup der alten App einlesen. Vor dem Übernehmen siehst du, was gefunden wurde.", SubjectPalette.of(5)) {
        SignalButton("V15-Backup wählen …", { if (!busy) onPick() }, icon = BackupIcons.Import)
        Spacer(Modifier.height(12.dp))
        Text("Tipp: Hat die alte App auf den Unraid-Server hochgeladen, dort einfach die Version wählen. Sie wird erkannt.", style = Ranzen.type.caption, color = c.inkMuted)
    }
}

@Composable
private fun ServerSection(
    account: SyncAccount,
    manager: BackupManager,
    busy: Boolean,
    scope: CoroutineScope,
    onFail: (Throwable) -> Unit,
    onDownloaded: suspend (File) -> Unit,
) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val st by account.state.collectAsState()
    var server by remember { mutableStateOf(st.server.removePrefix("http://")) }
    var email by remember { mutableStateOf(st.email) }
    var pin by remember { mutableStateOf("") }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    var versions by remember { mutableStateOf<List<RemoteVersion>?>(null) }

    fun op(label: String, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            manager.report(BackupProgress.Working(label, null))
            runCatching { block() }.onFailure(onFail)
        }
    }

    fun refresh() = op("Versionen laden") {
        versions = account.versions()
        manager.report(BackupProgress.Idle)
    }

    LaunchedEffect(st.loggedIn) { if (st.loggedIn && versions == null) runCatching { versions = account.versions() } }

    Section(BackupIcons.Server, "Unraid-Server", "Sicherungen auf dem eigenen Server im Heimnetz. Der Server bewahrt die letzten Versionen auf.", SubjectPalette.of(4)) {
        Row(verticalAlignment = Alignment.Bottom) {
            LineField("Server-Adresse", server, { server = it.trim(); reachable = null }, "192.168.1.20:8788", KeyboardType.Uri, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            Lamp(when (reachable) { true -> SubjectPalette.of(3); false -> c.signal; null -> c.inkFaint }, size = 10.dp, glow = reachable != null)
            Spacer(Modifier.width(6.dp))
            QuietButton("Prüfen", {
                op("Server prüfen") {
                    account.setServer(server)
                    reachable = false
                    account.checkServer()
                    reachable = true
                    manager.report(BackupProgress.Done("Server erreichbar."))
                }
            }, color = c.ink)
        }
        Spacer(Modifier.height(20.dp))
        if (!st.loggedIn) {
            Row {
                LineField("E-Mail", email, { email = it.trim() }, "name@beispiel.de", KeyboardType.Email, modifier = Modifier.weight(1.4f))
                Spacer(Modifier.width(18.dp))
                LineField("PIN (6 Ziffern)", pin, { v -> pin = v.filter { it.isDigit() }.take(6) }, "••••••", KeyboardType.NumberPassword, secret = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalButton("Anmelden", {
                    op("Anmelden") {
                        account.setServer(server)
                        account.login(email, pin)
                        pin = ""
                        manager.report(BackupProgress.Done("Angemeldet."))
                    }
                })
                Spacer(Modifier.width(10.dp))
                QuietButton("Neu registrieren", {
                    op("Registrieren") {
                        account.setServer(server)
                        account.register(email, pin)
                        pin = ""
                        manager.report(BackupProgress.Done("Konto angelegt und angemeldet."))
                    }
                }, color = c.ink)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Lamp(SubjectPalette.of(3), size = 8.dp)
                Spacer(Modifier.width(10.dp))
                Text("Angemeldet als ${st.email}", style = Ranzen.type.label, color = c.ink, modifier = Modifier.weight(1f))
                QuietButton("Abmelden", { account.logout(); versions = null })
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalButton("Jetzt hochladen", {
                    op("Sichern") {
                        val zip = manager.createBackup()
                        try {
                            val r = account.upload(zip, manager.suggestedName()) { s, t ->
                                manager.report(BackupProgress.Working("Hochladen", if (t > 0) s.toFloat() / t else null))
                            }
                            manager.report(BackupProgress.Done("Hochgeladen: ${r.version} (${BackupManager.formatSize(r.size)})."))
                        } finally {
                            withContext(Dispatchers.IO) { zip.delete() }
                        }
                        versions = runCatching { account.versions() }.getOrNull() ?: versions
                    }
                }, icon = BackupIcons.Restore)
                Spacer(Modifier.width(10.dp))
                QuietButton("Liste aktualisieren", { refresh() }, color = c.ink)
            }
            Spacer(Modifier.height(18.dp))
            Text("VERSIONEN AUF DEM SERVER", style = Ranzen.type.caption, color = c.inkMuted)
            Spacer(Modifier.height(8.dp))
            val list = versions
            when {
                list == null -> Text("Noch nicht geladen.", style = Ranzen.type.body, color = c.inkFaint)
                list.isEmpty() -> Text("Noch keine Sicherung auf dem Server.", style = Ranzen.type.body, color = c.inkFaint)
                else -> Column {
                    list.forEach { v ->
                        VersionRow(v) {
                            op("Herunterladen") {
                                val target = File(File(context.cacheDir, "backup").apply { mkdirs() }, "dl-" + System.currentTimeMillis() + ".zip")
                                account.download(v.name, target) { s, t ->
                                    manager.report(BackupProgress.Working("Herunterladen", if (t > 0) s.toFloat() / t else null))
                                }
                                manager.report(BackupProgress.Idle)
                                onDownloaded(target)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(v: RemoteVersion, onRestore: () -> Unit) {
    val c = Ranzen.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .drawBehind { drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (v.mtime > 0) BackupManager.formatDate(v.mtime * 1000) else v.name, style = Ranzen.type.mono, color = c.ink, modifier = Modifier.width(190.dp))
        Text(BackupManager.formatSize(v.size), style = Ranzen.type.mono, color = c.inkMuted, modifier = Modifier.weight(1f))
        QuietButton("Wiederherstellen …", onRestore, color = c.ink)
    }
}

// --- sheets ------------------------------------------------------------

@Composable
private fun SheetPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    PaperPanel(Modifier.padding(24.dp).widthIn(max = 600.dp), corner = 26.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(30.dp)) {
            Text(title, style = Ranzen.type.title, color = Ranzen.colors.ink)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun SheetButtons(busy: Boolean, cancel: String = "Abbrechen", confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Spacer(Modifier.height(26.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        if (busy) {
            Text("Bitte warten …", style = Ranzen.type.label, color = Ranzen.colors.inkMuted)
        } else {
            QuietButton(cancel, onCancel)
            Spacer(Modifier.width(10.dp))
            SignalButton(confirm, onConfirm)
        }
    }
}

@Composable
private fun ConfirmRestoreSheet(s: Sheet.ConfirmRestore, busy: Boolean, onCancel: () -> Unit, onRestore: () -> Unit) {
    val c = Ranzen.colors
    val m = s.info.manifest
    SheetPanel("Backup wiederherstellen?") {
        if (m != null) {
            Fact("Stand", BackupManager.formatDate(m.created))
            Fact("Quelle", s.source + if (m.device.isNotBlank()) " · ${m.device}" else "")
            Fact("App-Version", m.appVersion.ifBlank { "?" })
            Fact("Inhalt", "${m.entries.size} Dateien · ${BackupManager.formatSize(m.totalSize)}")
        }
        Spacer(Modifier.height(14.dp))
        Text("Alle Daten auf diesem Gerät werden durch das Backup ersetzt. Vorher wird jede Datei geprüft; ist etwas beschädigt, bleibt alles wie es ist. Danach startet die App neu.", style = Ranzen.type.body, color = c.ink)
        SheetButtons(busy, confirm = "Wiederherstellen", onCancel = onCancel, onConfirm = onRestore)
    }
}

@Composable
private fun PreviewSheet(lib: ImportedLibrary, busy: Boolean, onCancel: () -> Unit, onImport: () -> Unit) {
    val c = Ranzen.colors
    val notebooks = lib.notebooks.filter { !it.trashed }
    SheetPanel("Aus alter App übernehmen") {
        lib.created?.let { Fact("Backup vom", BackupManager.formatDate(it)) }
        Fact("Fächer", lib.subjects.size.toString())
        Fact("Hefte", "${notebooks.count { it.kind == NotebookKind.NOTEBOOK }}")
        Fact("Arbeitsblätter", "${notebooks.count { it.kind == NotebookKind.WORKSHEET }}")
        if (notebooks.any { it.kind == NotebookKind.TIMETABLE }) Fact("Stundenpläne", "${notebooks.count { it.kind == NotebookKind.TIMETABLE }}")
        Fact("Seiten", notebooks.sumOf { it.pages.size }.toString())
        Fact("Bücher", lib.books.count { !it.trashed }.toString() + lib.books.filter { !it.trashed }.sumOf { it.pdfSize }.takeIf { it > 0 }?.let { " · ${BackupManager.formatSize(it)}" }.orEmpty())
        Fact("Prüfungen / Karten", "${lib.study.exams.size} / ${lib.study.cards.size}")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(if (lib.verified) SubjectPalette.of(3) else SubjectPalette.of(2), size = 8.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (lib.verified) "Alle Prüfsummen stimmen." else "Nicht vollständig geprüft.", style = Ranzen.type.label, color = c.ink)
        }
        if (lib.warnings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("HINWEISE (${lib.warnings.size})", style = Ranzen.type.caption, color = c.inkMuted)
            Spacer(Modifier.height(6.dp))
            lib.warnings.take(8).forEach { Text("· $it", style = Ranzen.type.caption, color = c.inkMuted) }
            if (lib.warnings.size > 8) Text("· … und ${lib.warnings.size - 8} weitere", style = Ranzen.type.caption, color = c.inkMuted)
        }
        Spacer(Modifier.height(14.dp))
        Text("Die Daten kommen zusätzlich zu dem, was schon in der App ist. Gelöschtes aus dem Papierkorb der alten App wird nicht übernommen.", style = Ranzen.type.body, color = c.ink)
        SheetButtons(busy, cancel = "Verwerfen", confirm = "Übernehmen", onCancel = onCancel, onConfirm = onImport)
    }
}

@Composable
private fun RestartSheet(p: BackupProgress, onRestart: () -> Unit) {
    SheetPanel("Wiederherstellung fertig") {
        Text((p as? BackupProgress.Done)?.message ?: "Die Daten wurden ersetzt.", style = Ranzen.type.body, color = Ranzen.colors.ink)
        Spacer(Modifier.height(8.dp))
        Text("Damit alles neu geladen wird, startet die App jetzt neu.", style = Ranzen.type.body, color = Ranzen.colors.inkMuted)
        Spacer(Modifier.height(26.dp))
        Row { Spacer(Modifier.weight(1f)); SignalButton("Jetzt neu starten", onRestart) }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val c = Ranzen.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Ranzen.type.label, color = c.inkMuted, modifier = Modifier.width(190.dp))
        Text(value, style = Ranzen.type.mono, color = c.ink)
    }
}
