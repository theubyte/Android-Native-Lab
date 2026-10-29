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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.millione.schulranzen.core.backup.ArchiveKind
import de.millione.schulranzen.core.backup.AutoInterval
import de.millione.schulranzen.core.backup.BackupCrypto
import de.millione.schulranzen.core.backup.BackupHooks
import de.millione.schulranzen.core.backup.ImportedLibrary
import de.millione.schulranzen.core.backup.MergeParticipant
import de.millione.schulranzen.core.backup.MergeReport
import de.millione.schulranzen.core.backup.NotebookKind
import de.millione.schulranzen.core.backup.RemoteVersion
import de.millione.schulranzen.core.backup.V15Backup
import de.millione.schulranzen.core.backup.WrongPasswordException
import de.millione.schulranzen.design.Lamp
import de.millione.schulranzen.design.PaperPanel
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.design.RanzenIcon
import de.millione.schulranzen.design.RanzenIcons
import de.millione.schulranzen.design.SignalButton
import de.millione.schulranzen.design.SubjectPalette
import de.millione.schulranzen.design.TactileKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    /** Clock face with a circular arrow: automatic backup. */
    val Auto = RanzenIcons.line("auto") {
        moveTo(19.5f, 12f); curveTo(19.5f, 16.2f, 16.2f, 19.5f, 12f, 19.5f); curveTo(7.8f, 19.5f, 4.5f, 16.2f, 4.5f, 12f)
        curveTo(4.5f, 7.8f, 7.8f, 4.5f, 12f, 4.5f); curveTo(14.6f, 4.5f, 16.9f, 5.8f, 18.2f, 7.8f)
        moveTo(18.5f, 4.5f); lineTo(18.4f, 8f); lineTo(15f, 7.8f)
        moveTo(12f, 8f); lineTo(12f, 12f); lineTo(14.8f, 13.8f)
    }
    /** Padlock: encryption. */
    val Lock = RanzenIcons.Lock
}

/** Remembers one manager per screen with the given hooks. */
@Composable
fun rememberBackupManager(hooks: BackupHooks = BackupHooks.None): BackupManager {
    val ctx = LocalContext.current.applicationContext
    return remember { BackupManager(ctx, hooks) }.also { it.hooks = hooks }
}

private sealed interface Sheet {
    data class ConfirmRestore(val info: ArchiveInfo, val source: String) : Sheet
    data class Preview(val library: ImportedLibrary, val file: File) : Sheet
    data class Unlock(val info: ArchiveInfo, val source: String, val error: String?) : Sheet
    /** Asks for a password; [twice] = new password (typed twice), [offerRemember] shows the switch. */
    class Password(
        val title: String,
        val text: String,
        val twice: Boolean,
        val offerRemember: Boolean,
        val onOk: (CharArray, Boolean) -> Unit,
    ) : Sheet
    data class MergeDone(val reports: List<MergeReport>) : Sheet
}

/**
 * Backup, restore, automatic backup, encryption, Unraid sync and the
 * import from the old app.
 *
 * - [onImportV15] stores the read library (implement with V15Import.run
 *   and an ImportSink); the library is closed afterwards.
 * - [hooks] close and reopen the databases around a restore
 *   ([BackupHooks.beforeRestore] / [BackupHooks.afterRestore]); after a
 *   restore the app restarts by itself.
 * - [mergeParticipants] enable "Zusammenführen" (merge instead of
 *   replace); with none the option is shown greyed out.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onImportV15: suspend (ImportedLibrary) -> Unit,
    hooks: BackupHooks = BackupHooks.None,
    mergeParticipants: List<MergeParticipant> = emptyList(),
    manager: BackupManager = rememberBackupManager(hooks),
) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val account = remember { SyncAccount(context.applicationContext) }
    val settings = remember { BackupSettings.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val progress by manager.progress.collectAsState()
    val restart by manager.restartRequired.collectAsState()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = progress is BackupProgress.Working

    fun fail(e: Throwable) {
        if (e is CancellationException) throw e
        manager.report(BackupProgress.Failed(BackupManager.messageOf(e)))
    }

    /** Starts one user operation; only one at a time, cancellable from the status strip. */
    fun launchOp(block: suspend CoroutineScope.() -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                if (manager.progress.value is BackupProgress.Working) manager.report(BackupProgress.Done("Abgebrochen."))
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** A picked/downloaded file: own backup → confirm, encrypted → password, V15 → read and preview. */
    suspend fun handle(info: ArchiveInfo, source: String) {
        when (info.kind) {
            ArchiveKind.OWN -> sheet = Sheet.ConfirmRestore(info, source)
            ArchiveKind.ENCRYPTED -> {
                val opened = manager.unlockWithSaved(info)
                if (opened != null) handle(opened, source) else sheet = Sheet.Unlock(info, source, null)
            }
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

    /**
     * Runs [action] with the password for a new backup: null when
     * encryption is off, the remembered one, or one asked for now.
     */
    fun withBackupPassword(action: (CharArray?) -> Unit) {
        val st = settings.state.value
        if (!st.encrypt) return action(null)
        val saved = settings.password()
        if (saved != null) return action(saved)
        sheet = Sheet.Password(
            "Passwort für dieses Backup",
            "Die Verschlüsselung ist an, das Passwort ist aber nicht gemerkt. Ohne dieses Passwort lässt sich das Backup nie wieder öffnen.",
            twice = true, offerRemember = false,
        ) { pw, _ -> sheet = null; action(pw) }
    }

    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) launchOp { manager.exportTo(uri, null) }
    }
    val saveEncrypted = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = pendingPassword
        pendingPassword = null
        if (uri != null && pw != null) launchOp { manager.exportTo(uri, pw) }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) launchOp { handle(manager.open(uri), "Datei") }
    }
    val pickTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

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
                    Text("Vollbackup, automatisch, verschlüsselt, Unraid-Server und Daten aus der alten App", style = Ranzen.type.label, color = c.inkMuted)
                }
            }
            Spacer(Modifier.height(18.dp))
            StatusStrip(progress, onCancel = { job?.cancel() }) { manager.resetProgress() }
            Spacer(Modifier.height(18.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 980.dp
                val device: @Composable () -> Unit = {
                    DeviceSection(
                        busy = busy,
                        onSave = {
                            withBackupPassword { pw ->
                                if (pw == null) saveZip.launch(manager.suggestedName(false))
                                else { pendingPassword = pw; saveEncrypted.launch(manager.suggestedName(true)) }
                            }
                        },
                        onOpen = { openLauncher.launch(pickTypes) },
                    )
                }
                val crypto: @Composable () -> Unit = {
                    EncryptionSection(settings, busy) { sheet = it }
                }
                val auto: @Composable () -> Unit = { AutoSection(settings, account, busy) }
                val v15: @Composable () -> Unit = { V15Section(busy) { openLauncher.launch(pickTypes) } }
                val server: @Composable () -> Unit = {
                    ServerSection(
                        account, manager, busy,
                        launchOp = ::launchOp,
                        withPassword = ::withBackupPassword,
                    ) { file -> handle(manager.inspect(file), "Unraid-Server") }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            device(); crypto(); v15()
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            auto(); server()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        device(); auto(); crypto(); server(); v15()
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
                    current is Sheet.ConfirmRestore -> ConfirmRestoreSheet(current, busy, canMerge = mergeParticipants.isNotEmpty(),
                        onCancel = { manager.discard(current.info); sheet = null },
                        onRestore = { merge ->
                            launchOp {
                                try {
                                    if (merge) {
                                        val reports = manager.merge(current.info.file, mergeParticipants)
                                        sheet = Sheet.MergeDone(reports)
                                    } else {
                                        manager.restore(current.info.file)
                                    }
                                } catch (e: Throwable) {
                                    sheet = null
                                    throw e
                                }
                            }
                        },
                    )
                    current is Sheet.Unlock -> UnlockSheet(current, busy,
                        onCancel = { sheet = closeSheet(current) },
                        onUnlock = { pw ->
                            launchOp {
                                try {
                                    val opened = manager.unlock(current.info, pw)
                                    sheet = null
                                    handle(opened, current.source)
                                } catch (_: WrongPasswordException) {
                                    manager.report(BackupProgress.Idle)
                                    sheet = current.copy(error = "Falsches Passwort.")
                                } catch (e: Throwable) {
                                    if (e !is CancellationException) sheet = closeSheet(current)
                                    throw e
                                }
                            }
                        },
                    )
                    current is Sheet.Password -> PasswordSheet(current, onCancel = { sheet = null })
                    current is Sheet.MergeDone -> MergeDoneSheet(current.reports,
                        onClose = { sheet = null; manager.resetProgress() },
                        onRestart = { context.findActivity()?.let { BackupManager.restartApp(it) } },
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
    if (s is Sheet.Unlock) s.info.file.delete()
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
private fun StatusStrip(p: BackupProgress, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val c = Ranzen.colors
    val (lamp, text) = when (p) {
        BackupProgress.Idle -> c.inkFaint to "Bereit."
        is BackupProgress.Working -> SubjectPalette.of(2) to workingText(p)
        is BackupProgress.Done -> SubjectPalette.of(3) to p.message
        is BackupProgress.Failed -> c.signal to p.message
    }
    PaperPanel(Modifier.fillMaxWidth(), corner = 16.dp, depth = 0.5f) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
                Lamp(lamp, size = 10.dp, glow = p !is BackupProgress.Idle)
                Spacer(Modifier.width(12.dp))
                Text(text, style = Ranzen.type.label, color = if (p is BackupProgress.Failed) c.signal else c.ink, modifier = Modifier.weight(1f))
                if (p is BackupProgress.Working && p.cancellable) QuietButton("Abbrechen", onCancel, color = c.signal)
                if (p is BackupProgress.Done || p is BackupProgress.Failed) QuietButton("OK", onDismiss)
            }
            if (p is BackupProgress.Working) {
                Spacer(Modifier.height(10.dp))
                ProgressBar(p.fraction)
            }
        }
    }
}

/** "Sichern · 42 % · 12,3 MB von 80,0 MB". */
private fun workingText(p: BackupProgress.Working): String {
    val sb = StringBuilder(p.label)
    if (p.fraction != null && p.total > 0) sb.append(" · ").append((p.fraction * 100).toInt()).append(" %")
    when {
        p.done >= 0 && p.total > 0 -> sb.append(" · ").append(BackupManager.formatSize(p.done)).append(" von ").append(BackupManager.formatSize(p.total))
        p.done >= 0 -> sb.append(" · ").append(BackupManager.formatSize(p.done))
        p.fraction == null -> sb.append(" …")
        else -> sb.append(" · ").append((p.fraction * 100).toInt()).append(" %")
    }
    return sb.toString()
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

/** A labelled switch built from a tactile key: lit check mark when on. */
@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, hint: String? = null, onChange: (Boolean) -> Unit) {
    val c = Ranzen.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Checkbox, onClickLabel = label) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TactileKey({ if (enabled) onChange(!checked) }, selected = checked, size = 44.dp, corner = 12.dp, contentDescription = label) {
            if (checked) RanzenIcon(RanzenIcons.Check, tint = c.signal, size = 22.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Ranzen.type.label, color = c.ink)
            if (hint != null) Text(hint, style = Ranzen.type.caption, color = c.inkMuted)
        }
    }
}

/** A row of tactile keys, one of them pressed in. */
@Composable
private fun Choice(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    val c = Ranzen.colors
    Row(Modifier.alpha(if (enabled) 1f else 0.45f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEachIndexed { i, label ->
            TactileKey({ if (enabled) onSelect(i) }, Modifier.widthIn(min = 120.dp), selected = i == selected, contentDescription = label) {
                Text(label, style = Ranzen.type.label, color = if (i == selected) c.ink else c.inkMuted, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
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
private fun EncryptionSection(settings: BackupSettings, busy: Boolean, show: (Sheet?) -> Unit) {
    val c = Ranzen.colors
    val st by settings.state.collectAsState()
    Section(BackupIcons.Lock, "Verschlüsselung", "Backups mit einem eigenen Passwort schützen (AES-256). Gilt für Datei, Server und automatische Backups.", SubjectPalette.of(1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(if (st.encrypt) SubjectPalette.of(3) else c.inkFaint, size = 10.dp, glow = st.encrypt)
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    !st.encrypt -> "Aus – Backups werden unverschlüsselt gespeichert."
                    st.passwordSaved -> "An – das Passwort ist auf diesem Gerät gemerkt."
                    else -> "An – das Passwort wird bei jedem Backup abgefragt."
                },
                style = Ranzen.type.label, color = c.ink,
            )
        }
        Spacer(Modifier.height(16.dp))
        fun setup(title: String) = show(
            Sheet.Password(
                title,
                "Mindestens ${BackupCrypto.MIN_PASSWORD} Zeichen. Gut merken oder aufschreiben: Ohne das Passwort lässt sich ein verschlüsseltes Backup nie wieder öffnen, auch nicht vom Server.",
                twice = true, offerRemember = true,
            ) { pw, remember -> settings.setEncryption(pw, remember); show(null) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!st.encrypt) {
                SignalButton("Verschlüsselung einrichten …", { if (!busy) setup("Verschlüsselung einrichten") }, icon = BackupIcons.Lock)
            } else {
                QuietButton("Passwort ändern …", { if (!busy) setup("Neues Passwort") }, color = c.ink)
                if (st.passwordSaved) QuietButton("Passwort vergessen", { settings.forgetPassword() }, color = c.ink)
                QuietButton("Ausschalten", { settings.setEncryption(null, false) }, color = c.signal)
            }
        }
        if (st.encrypt) {
            Spacer(Modifier.height(8.dp))
            Text("Ältere Backups behalten ihr altes Passwort. Beim Wiederherstellen wird das gemerkte Passwort zuerst versucht.", style = Ranzen.type.caption, color = c.inkMuted)
        }
    }
}

@Composable
private fun AutoSection(settings: BackupSettings, account: SyncAccount, busy: Boolean) {
    val c = Ranzen.colors
    val context = LocalContext.current
    val st by settings.state.collectAsState()
    val acc by account.state.collectAsState()
    val work by remember { AutoBackup.status(context.applicationContext) }.collectAsState(AutoBackup.Status(false, null, false))
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { AutoBackup.setFolder(context, uri) }
            .onFailure { settings.recordRun(null, 0, "", "Ordner kann nicht dauerhaft verwendet werden: ${BackupManager.messageOf(it)}") }
    }
    fun changed() = AutoBackup.apply(context.applicationContext)

    Section(BackupIcons.Auto, "Automatisch sichern", "Regelmäßig im Hintergrund sichern, in einen Ordner und/oder auf den Unraid-Server.", SubjectPalette.of(2)) {
        val intervals = AutoInterval.entries
        Choice(intervals.map { it.label }, intervals.indexOf(st.interval)) { settings.setInterval(intervals[it]); changed() }
        Spacer(Modifier.height(16.dp))
        Text("ZIEL", style = Ranzen.type.caption, color = c.inkMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CheckRow(
                    if (st.localTree != null) "Ordner: ${st.localName ?: "gewählt"}" else "In einen Ordner",
                    checked = st.localTree != null,
                    hint = if (st.localTree == null) "Ordner wählen, z. B. auf der SD-Karte" else "Behält die letzten ${st.keep} automatischen Backups",
                ) { on -> if (on) folderPicker.launch(null) else AutoBackup.clearFolder(context) }
            }
            if (st.localTree != null) QuietButton("Ändern …", { folderPicker.launch(null) }, color = c.ink)
        }
        CheckRow(
            "Auf den Unraid-Server", st.toServer, enabled = acc.loggedIn || st.toServer,
            hint = if (!acc.loggedIn) "Dazu unten am Server anmelden" else "Der Server behält selbst die letzten Versionen",
        ) { settings.setToServer(it); changed() }
        Spacer(Modifier.height(8.dp))
        Text("BEDINGUNGEN", style = Ranzen.type.caption, color = c.inkMuted)
        CheckRow("Nur im WLAN", st.wifiOnly, enabled = st.toServer, hint = "Gilt für das Hochladen") { settings.setWifiOnly(it); changed() }
        CheckRow("Nur beim Laden", st.chargingOnly) { settings.setChargingOnly(it); changed() }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Aufbewahren", style = Ranzen.type.label, color = c.ink, modifier = Modifier.weight(1f))
            TactileKey({ settings.setKeep(st.keep - 1) }, contentDescription = "Weniger") { Text("−", style = Ranzen.type.heading, color = c.ink) }
            Text("${st.keep}", style = Ranzen.type.mono, color = c.ink, modifier = Modifier.width(56.dp).padding(horizontal = 18.dp))
            TactileKey({ settings.setKeep(st.keep + 1) }, contentDescription = "Mehr") { RanzenIcon(RanzenIcons.Plus) }
            Spacer(Modifier.width(12.dp))
            Text("Versionen", style = Ranzen.type.label, color = c.inkMuted)
        }
        Spacer(Modifier.height(16.dp))

        // Status: last result, next run, problems.
        val lines = buildList {
            add(
                if (st.lastOk > 0) "Letztes Backup: ${BackupManager.formatDate(st.lastOk)} · ${BackupManager.formatSize(st.lastSize)} · ${st.lastTargets}"
                else "Noch kein automatisches Backup.",
            )
            when {
                work.running -> add("Läuft gerade …")
                work.waiting -> add("Wartet auf WLAN, Ladekabel oder freien Speicher.")
                st.interval != AutoInterval.OFF && work.next != null -> add("Nächstes: ab ${BackupManager.formatDate(work.next!!)}")
            }
        }
        lines.forEach { Text(it, style = Ranzen.type.label, color = c.ink) }
        val problem = when {
            st.interval != AutoInterval.OFF && !st.hasTarget -> "Bitte ein Ziel wählen – sonst wird nichts gesichert."
            st.interval != AutoInterval.OFF && st.autoNeedsPassword -> "Verschlüsselung ist an, aber das Passwort ist nicht gemerkt. Automatische Backups brauchen das gemerkte Passwort."
            else -> null
        }
        if (problem != null) {
            Spacer(Modifier.height(8.dp))
            Text(problem, style = Ranzen.type.label, color = c.signal)
        }
        st.lastError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Lamp(c.signal, size = 8.dp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.width(10.dp))
                Text("Fehler am ${BackupManager.formatDate(st.lastErrorAt)}:\n$err", style = Ranzen.type.label, color = c.signal, modifier = Modifier.weight(1f))
                QuietButton("Ausblenden", { settings.clearError() })
            }
        }
        Spacer(Modifier.height(14.dp))
        SignalButton("Jetzt automatisch sichern", {
            if (!busy && st.hasTarget && !work.running) AutoBackup.runNow(context.applicationContext)
        }, icon = BackupIcons.Save, modifier = Modifier.alpha(if (st.hasTarget && !work.running) 1f else 0.45f))
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

/** Progress callback for uploads and downloads that also stops when the job is cancelled. */
private suspend fun transferProgress(manager: BackupManager, label: String): (Long, Long) -> Unit {
    val job = currentCoroutineContext()[Job]
    return { s, t ->
        job?.ensureActive()
        manager.report(BackupProgress.Working(label, if (t > 0) (s.toFloat() / t).coerceIn(0f, 1f) else null, s, t, cancellable = true))
    }
}

@Composable
private fun ServerSection(
    account: SyncAccount,
    manager: BackupManager,
    busy: Boolean,
    launchOp: (suspend CoroutineScope.() -> Unit) -> Unit,
    withPassword: ((CharArray?) -> Unit) -> Unit,
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
        launchOp {
            manager.report(BackupProgress.Working(label, null))
            block()
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
                    if (!busy) withPassword { pw ->
                        op("Sichern") {
                            val zip = manager.createBackup(pw)
                            try {
                                val r = account.upload(zip, manager.suggestedName(false), transferProgress(manager, "Hochladen"))
                                manager.report(BackupProgress.Done("Hochgeladen: ${r.version} (${BackupManager.formatSize(r.size)}${if (pw != null) ", verschlüsselt" else ""})."))
                            } finally {
                                withContext(Dispatchers.IO) { zip.delete() }
                            }
                            versions = runCatching { account.versions() }.getOrNull() ?: versions
                        }
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
                                val dir = File(context.cacheDir, "backup").apply { mkdirs() }
                                dir.listFiles()?.filter { it.name.startsWith("dl-") }?.forEach { it.delete() }
                                val target = File(dir, "dl-" + System.currentTimeMillis())
                                try {
                                    account.download(v.name, target, transferProgress(manager, "Herunterladen"))
                                } catch (e: Throwable) {
                                    withContext(Dispatchers.IO) { target.delete(); File(target.path + ".part").delete() }
                                    throw e
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
private fun ConfirmRestoreSheet(s: Sheet.ConfirmRestore, busy: Boolean, canMerge: Boolean, onCancel: () -> Unit, onRestore: (merge: Boolean) -> Unit) {
    val c = Ranzen.colors
    val m = s.info.manifest
    var merge by remember { mutableStateOf(false) }
    SheetPanel("Backup wiederherstellen?") {
        if (m != null) {
            Fact("Stand", BackupManager.formatDate(m.created))
            Fact("Quelle", s.source + if (m.device.isNotBlank()) " · ${m.device}" else "")
            Fact("App-Version", m.appVersion.ifBlank { "?" })
            Fact("Inhalt", "${m.entries.size} Dateien · ${BackupManager.formatSize(m.totalSize)}")
        }
        Spacer(Modifier.height(14.dp))
        CheckRow("Ersetzen", !merge, hint = "Alle Daten auf diesem Gerät werden durch das Backup ersetzt. Danach startet die App neu.") { merge = false }
        CheckRow(
            "Zusammenführen", merge, enabled = canMerge,
            hint = if (canMerge) "Nur Neues und Neueres aus dem Backup übernehmen; auf diesem Gerät geht nichts verloren." else "In dieser Version noch nicht verfügbar.",
        ) { if (canMerge) merge = true }
        Spacer(Modifier.height(10.dp))
        Text("Vorher wird jede Datei geprüft; ist etwas beschädigt, bleibt alles, wie es ist.", style = Ranzen.type.body, color = c.inkMuted)
        SheetButtons(busy, confirm = if (merge) "Zusammenführen" else "Wiederherstellen", onCancel = onCancel, onConfirm = { onRestore(merge) })
    }
}

@Composable
private fun UnlockSheet(s: Sheet.Unlock, busy: Boolean, onCancel: () -> Unit, onUnlock: (CharArray) -> Unit) {
    val c = Ranzen.colors
    var pw by remember { mutableStateOf("") }
    SheetPanel("Verschlüsseltes Backup") {
        Text("Dieses Backup ist mit einem Passwort geschützt (Quelle: ${s.source}).", style = Ranzen.type.body, color = c.ink)
        Spacer(Modifier.height(18.dp))
        LineField("Passwort", pw, { pw = it }, "Passwort des Backups", KeyboardType.Password, secret = true)
        s.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = Ranzen.type.label, color = c.signal)
        }
        SheetButtons(busy, confirm = "Öffnen", onCancel = onCancel, onConfirm = { if (pw.isNotEmpty()) onUnlock(pw.toCharArray()) })
    }
}

@Composable
private fun PasswordSheet(s: Sheet.Password, onCancel: () -> Unit) {
    val c = Ranzen.colors
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    SheetPanel(s.title) {
        Text(s.text, style = Ranzen.type.body, color = c.ink)
        Spacer(Modifier.height(18.dp))
        LineField("Passwort", pw, { pw = it; error = null }, "mindestens ${BackupCrypto.MIN_PASSWORD} Zeichen", KeyboardType.Password, secret = true)
        if (s.twice) {
            Spacer(Modifier.height(18.dp))
            LineField("Wiederholen", again, { again = it; error = null }, "noch einmal", KeyboardType.Password, secret = true)
        }
        if (s.offerRemember) {
            Spacer(Modifier.height(12.dp))
            CheckRow("Auf diesem Gerät merken", remember, hint = "Sicher im Android-Schlüsselspeicher. Nötig für automatische Backups.") { remember = it }
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = Ranzen.type.label, color = c.signal)
        }
        SheetButtons(false, confirm = "OK", onCancel = onCancel, onConfirm = {
            error = when {
                pw.length < BackupCrypto.MIN_PASSWORD -> "Das Passwort braucht mindestens ${BackupCrypto.MIN_PASSWORD} Zeichen."
                s.twice && pw != again -> "Die beiden Passwörter sind verschieden."
                else -> null
            }
            if (error == null) s.onOk(pw.toCharArray(), s.offerRemember && remember)
        })
    }
}

@Composable
private fun MergeDoneSheet(reports: List<MergeReport>, onClose: () -> Unit, onRestart: () -> Unit) {
    val c = Ranzen.colors
    SheetPanel("Zusammengeführt") {
        reports.forEach { r ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Lamp(if (r.ok) SubjectPalette.of(3) else c.signal, size = 8.dp)
                Spacer(Modifier.width(10.dp))
                Text(r.summary(), style = Ranzen.type.label, color = if (r.ok) c.ink else c.signal)
            }
            r.warnings.take(3).forEach { Text("· $it", style = Ranzen.type.caption, color = c.inkMuted) }
            if (r.warnings.size > 3) Text("· … und ${r.warnings.size - 3} weitere", style = Ranzen.type.caption, color = c.inkMuted)
        }
        Spacer(Modifier.height(12.dp))
        Text("Damit überall der neue Stand zu sehen ist, die App am besten neu starten.", style = Ranzen.type.body, color = c.inkMuted)
        SheetButtons(false, cancel = "Fertig", confirm = "Neu starten", onCancel = onClose, onConfirm = onRestart)
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

/** After a restore: short notice, then the app restarts by itself. */
@Composable
private fun RestartSheet(p: BackupProgress, onRestart: () -> Unit) {
    var left by remember { mutableIntStateOf(3) }
    LaunchedEffect(Unit) {
        while (left > 0) { delay(1000); left-- }
        onRestart()
    }
    SheetPanel("Wiederherstellung fertig") {
        Text((p as? BackupProgress.Done)?.message ?: "Die Daten wurden ersetzt.", style = Ranzen.type.body, color = Ranzen.colors.ink)
        Spacer(Modifier.height(8.dp))
        Text("Die App startet neu, damit alles frisch geladen wird … ($left)", style = Ranzen.type.body, color = Ranzen.colors.inkMuted)
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
