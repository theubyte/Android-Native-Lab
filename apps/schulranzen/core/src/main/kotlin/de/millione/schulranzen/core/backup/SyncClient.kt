package de.millione.schulranzen.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a server call failed; the UI shows [SyncException.message] as is. */
enum class SyncError { UNREACHABLE, BAD_INPUT, WRONG_LOGIN, EXISTS, SESSION_EXPIRED, NOT_FOUND, TOO_LARGE, SERVER, PROTOCOL }

/** [httpCode] is set when the server answered (null = network or input problem). */
class SyncException(val error: SyncError, message: String, cause: Throwable? = null, val httpCode: Int? = null) : IOException(message, cause)

/** One step of [SyncClient.diagnose]: what was tried, whether it worked, the answer, the time taken. */
data class DiagnoseStep(val name: String, val ok: Boolean, val detail: String, val millis: Long)

data class SyncSession(val token: String, val email: String)
data class RemoteVersion(val name: String, val size: Long, /** Epoch seconds. */ val mtime: Long)
data class UploadResult(val version: String, val size: Long)

/**
 * Client for the Unraid sync server (`server/unraid-sync/app.py`), API
 * unchanged from V15:
 *
 * - `GET /health` → `{ok, service, time}`
 * - `POST /api/register`, `POST /api/login` with JSON `{email, pin}` → `{ok, token, email}`;
 *   the PIN has exactly 6 digits, the e-mail is stored lower case.
 * - `POST /api/sync/upload`, multipart field `backup` → `{ok, version, size}`
 * - `GET /api/sync/versions` → `{ok, versions: [{version, size, mtime}]}`, newest first
 * - `GET /api/sync/latest`, `GET /api/sync/version/<name>` → the ZIP
 *
 * Auth is `Authorization: Bearer <token>`; the server answers a bad or
 * expired token with a bare 401 (HTML, not JSON). Blocking: call it on an
 * IO thread.
 */
class SyncClient(
    serverUrl: String,
    var token: String? = null,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 120_000,
    /**
     * Waiting for the answer after a large upload: the server writes the
     * file to disk first (gunicorn timeout there: 600 s).
     */
    private val uploadTimeoutMs: Int = 600_000,
) {
    val baseUrl: String = normalizeUrl(serverUrl)

    init {
        if (baseUrl.isEmpty()) throw SyncException(SyncError.BAD_INPUT, "Bitte die Server-Adresse eintragen, z. B. 192.168.1.20:8788.")
        try { URI(baseUrl).toURL() } catch (e: Exception) { throw SyncException(SyncError.BAD_INPUT, "Die Server-Adresse ist ungültig.", e) }
    }

    /** True when the address answers like a Schulranzen sync server. */
    fun health(): Boolean {
        val (code, body) = request("GET", "/health", auth = false)
        if (code != 200) throw SyncException(SyncError.PROTOCOL, "Unter dieser Adresse läuft kein Schulranzen-Server (HTTP $code).")
        val o = parse(body) ?: throw SyncException(SyncError.PROTOCOL, "Unter dieser Adresse läuft kein Schulranzen-Server.")
        val ok = o["ok"]?.jsonPrimitive?.booleanOrNull == true
        val service = o["service"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!ok || !service.contains("Schulranzen", ignoreCase = true)) throw SyncException(SyncError.PROTOCOL, "Unter dieser Adresse läuft kein Schulranzen-Server.")
        return true
    }

    fun register(email: String, pin: String): SyncSession = account("/api/register", email, pin, register = true)
    fun login(email: String, pin: String): SyncSession = account("/api/login", email, pin, register = false)

    private fun account(path: String, email: String, pin: String, register: Boolean): SyncSession {
        val mail = email.trim().lowercase()
        checkCredentials(mail, pin)
        val body = buildJsonObject { put("email", mail); put("pin", pin) }.toString()
        val (code, text) = request("POST", path, auth = false, json = body)
        val o = parse(text)
        when {
            code == 200 -> {
                val tok = o?.get("token")?.jsonPrimitive?.contentOrNull
                    ?: throw SyncException(SyncError.PROTOCOL, "Der Server hat keine Anmeldung zurückgegeben.")
                token = tok
                return SyncSession(tok, o["email"]?.jsonPrimitive?.contentOrNull ?: mail)
            }
            code == 409 -> throw SyncException(SyncError.EXISTS, "Diese E-Mail ist schon registriert. Bitte anmelden. (HTTP 409)", httpCode = code)
            code == 400 -> throw SyncException(SyncError.BAD_INPUT, "E-Mail oder PIN ungültig. Die PIN hat genau 6 Ziffern. (HTTP 400${detailOf(o, text)?.let { ": $it" } ?: ""})", httpCode = code)
            code == 401 && !register -> throw SyncException(SyncError.WRONG_LOGIN, "E-Mail oder PIN falsch. (HTTP 401)", httpCode = code)
            else -> throw serverError(code, o, text)
        }
    }

    /** Uploads a backup ZIP; [progress] gets bytes sent and total. */
    fun upload(zip: File, fileName: String = "schulranzen-backup.zip", progress: (Long, Long) -> Unit = { _, _ -> }): UploadResult {
        val boundary = "----Schulranzen" + System.nanoTime().toString(16)
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"backup\"; filename=\"$safeName\"\r\n" +
            "Content-Type: application/zip\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val total = zip.length()
        // A server that rejects the token answers 401 and closes the socket
        // while we are still sending; the client then only sees "broken pipe".
        // A cheap authenticated call first turns that into a clear 401 (and
        // lets withRelogin repair it before megabytes are sent).
        if (total > PREFLIGHT_BYTES) versions()
        val h = open("POST", "/api/sync/upload", auth = true)
        h.readTimeout = uploadTimeoutMs
        var sent = 0L
        try {
            h.doOutput = true
            h.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Fixed length streams the file instead of buffering it in memory.
            h.setFixedLengthStreamingMode(head.size + total + tail.size)
            val (code, text) = exchange(h) {
                h.outputStream.buffered(64 * 1024).use { out ->
                    out.write(head)
                    zip.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            progress(sent, total)
                        }
                    }
                    out.write(tail)
                }
            }
            val o = parse(text)
            if (code != 200) throw serverError(code, o, text)
            val version = o?.get("version")?.jsonPrimitive?.contentOrNull
                ?: throw SyncException(SyncError.PROTOCOL, "Der Server hat das Hochladen nicht bestätigt (HTTP $code).", httpCode = code)
            return UploadResult(version, o["size"]?.jsonPrimitive?.longOrNull ?: total)
        } catch (e: SyncException) {
            // A broken connection: say how far it got.
            if (e.httpCode == null && e.error == SyncError.UNREACHABLE) {
                val where = if (sent >= total) "nach dem Senden aller ${mb(total)} (beim Warten auf die Antwort)" else "bei ${mb(sent)} von ${mb(total)}"
                throw SyncException(e.error, "Hochladen abgebrochen $where. ${e.message}", e.cause)
            }
            throw e
        } finally {
            h.disconnect()
        }
    }

    /** The stored backups, newest first. */
    fun versions(): List<RemoteVersion> {
        val (code, text) = request("GET", "/api/sync/versions", auth = true)
        val o = parse(text)
        if (code != 200) throw serverError(code, o, text)
        val arr = runCatching { o!!["versions"]!!.jsonArray }.getOrNull()
            ?: throw SyncException(SyncError.PROTOCOL, "Antwort des Servers ist unverständlich.")
        return arr.mapNotNull { e ->
            val v = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = v["version"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            RemoteVersion(name, v["size"]?.jsonPrimitive?.longOrNull ?: 0, v["mtime"]?.jsonPrimitive?.longOrNull ?: 0)
        }.sortedByDescending { it.mtime }
    }

    /**
     * Downloads one version ([version] null = newest) to [target], written
     * to a temp file first so a broken download never looks complete.
     */
    fun download(version: String?, target: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val path = if (version == null) "/api/sync/latest" else {
            if (version.contains('/') || version.contains('\\') || !version.endsWith(".zip")) throw SyncException(SyncError.BAD_INPUT, "Ungültiger Versionsname.")
            "/api/sync/version/" + URLEncoder.encode(version, "UTF-8").replace("+", "%20")
        }
        val h = open("GET", path, auth = true)
        try {
            val code = try { h.responseCode } catch (e: IOException) { throw networkError(e) }
            if (code != 200) {
                val text = readBody(h.errorStream)
                val o = parse(text)
                if (code == 404) throw SyncException(SyncError.NOT_FOUND, (if (version == null) "Auf dem Server liegt noch kein Backup." else "Diese Version gibt es auf dem Server nicht mehr.") + " (HTTP 404)", httpCode = code)
                throw serverError(code, o, text)
            }
            val total = h.contentLengthLong
            val tmp = File(target.parentFile, target.name + ".part")
            target.parentFile?.mkdirs()
            try {
                h.inputStream.use { input ->
                    tmp.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            progress(got, total)
                        }
                        if (total >= 0 && got != total) throw SyncException(SyncError.UNREACHABLE, "Download abgebrochen bei ${mb(got)} von ${mb(total)}. Bitte erneut versuchen.")
                    }
                }
            } catch (e: SyncException) {
                tmp.delete(); throw e
            } catch (e: IOException) {
                tmp.delete()
                val n = networkError(e)
                throw SyncException(n.error, "Download abgebrochen. ${n.message}", e)
            }
            target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            return target
        } finally {
            h.disconnect()
        }
    }

    // --- plumbing -------------------------------------------------------

    private fun open(method: String, path: String, auth: Boolean): HttpURLConnection {
        val h = try {
            URL(baseUrl + path).openConnection() as HttpURLConnection
        } catch (e: MalformedURLException) {
            throw SyncException(SyncError.BAD_INPUT, "Die Server-Adresse ist ungültig.", e)
        }
        h.connectTimeout = connectTimeoutMs
        h.readTimeout = readTimeoutMs
        h.requestMethod = method
        h.useCaches = false
        h.instanceFollowRedirects = false
        h.setRequestProperty("Accept", "application/json")
        if (auth) {
            val t = token
            if (t.isNullOrEmpty()) throw SyncException(SyncError.SESSION_EXPIRED, "Bitte zuerst am Server anmelden.")
            h.setRequestProperty("Authorization", "Bearer $t")
        }
        return h
    }

    private fun request(method: String, path: String, auth: Boolean, json: String? = null): Pair<Int, String> {
        val h = open(method, path, auth)
        try {
            if (json != null) {
                h.doOutput = true
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            return exchange(h) { if (json != null) h.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) } }
        } finally {
            h.disconnect()
        }
    }

    private inline fun exchange(h: HttpURLConnection, send: () -> Unit): Pair<Int, String> {
        try {
            send()
            val code = h.responseCode
            val body = readBody(if (code in 200..299) h.inputStream else h.errorStream)
            return code to body
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            // The server may answer and close before it read the whole body
            // (too large, or a rejected token): prefer its answer to "broken pipe".
            val code = runCatching { h.responseCode }.getOrNull()
            if (code != null && code >= 300) {
                val body = runCatching { readBody(h.errorStream) }.getOrDefault("")
                throw serverError(code, parse(body), body)
            }
            throw networkError(e)
        }
    }

    private fun readBody(input: InputStream?): String =
        input?.use { s -> s.readNBytesCompat(1 shl 20).toString(Charsets.UTF_8) } ?: ""

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

    /**
     * Runs [block]; when the server rejects the token (401) and [email] and
     * [pin] are known, logs in again once and repeats the call. [onLogin]
     * gets the new session so it can be stored. A second 401 is thrown.
     */
    fun <T> withRelogin(email: String?, pin: String?, onLogin: (SyncSession) -> Unit = {}, block: (SyncClient) -> T): T {
        val canRelogin = !email.isNullOrBlank() && !pin.isNullOrBlank()
        if (token.isNullOrEmpty() && canRelogin) onLogin(login(email!!, pin!!))
        return try {
            block(this)
        } catch (e: SyncException) {
            if (e.error != SyncError.SESSION_EXPIRED || !canRelogin) throw e
            onLogin(login(email!!, pin!!))
            block(this)
        }
    }

    /** Every server error names the HTTP code and, if any, the server's own words. */
    private fun serverError(code: Int, o: JsonObject?, body: String = ""): SyncException {
        val detail = detailOf(o, body)
        val tail = " (HTTP $code" + (detail?.let { ": $it" } ?: "") + ")"
        return when (code) {
            401 -> SyncException(SyncError.SESSION_EXPIRED, "Anmeldung abgelehnt$tail. Bitte neu anmelden.", httpCode = code)
            404 -> SyncException(SyncError.NOT_FOUND, "Nicht gefunden$tail. Stimmt die Server-Adresse?", httpCode = code)
            413 -> SyncException(SyncError.TOO_LARGE, "Das Backup ist größer als das Limit des Servers (MAX_UPLOAD_MB)$tail.", httpCode = code)
            in 300..399 -> SyncException(SyncError.PROTOCOL, "Der Server leitet um$tail. Bitte die genaue Adresse eintragen (http:// oder https://).", httpCode = code)
            502, 503, 504 -> SyncException(SyncError.SERVER, "Server überlastet oder Zeitüberschreitung$tail. Später erneut versuchen.", httpCode = code)
            else -> SyncException(SyncError.SERVER, "Serverfehler$tail.", httpCode = code)
        }
    }

    /**
     * Checks the whole chain step by step for troubleshooting: is the server
     * there, does the login work, can the backups be listed. Never throws;
     * stops after the first failed step, since the later ones depend on it.
     * Sets [token] on a successful login.
     */
    fun diagnose(email: String, pin: String): List<DiagnoseStep> {
        val steps = ArrayList<DiagnoseStep>()
        fun step(name: String, block: () -> String): Boolean {
            val t0 = System.nanoTime()
            val r = runCatching(block)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val detail = r.fold({ it }, { e ->
                when (e) {
                    is SyncException -> e.message.orEmpty()
                    is IOException -> networkError(e).message.orEmpty()
                    else -> e.message ?: e.javaClass.simpleName
                }
            })
            steps += DiagnoseStep(name, r.isSuccess, detail, ms)
            return r.isSuccess
        }
        if (!step("Server erreichbar ($baseUrl)") { health(); "Antwortet als Schulranzen-Server." }) return steps
        if (!step("Anmelden") { "Angemeldet als ${login(email, pin).email}." }) return steps
        step("Backups auflisten") {
            val v = versions()
            if (v.isEmpty()) "Anmeldung gilt. Noch keine Backups auf dem Server." else "Anmeldung gilt. ${v.size} Backup(s), neuestes ${mb(v.first().size)}."
        }
        return steps
    }

    companion object {
        private const val PREFLIGHT_BYTES = 1L shl 20

        /** The server's "error" field, or the title of an HTML error page, shortened. */
        internal fun detailOf(o: JsonObject?, body: String): String? {
            val err = o?.get("error")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            if (!err.isNullOrBlank()) return err.take(160)
            val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()
            return title?.takeIf { it.isNotBlank() }?.take(160)
        }

        internal fun mb(bytes: Long): String = String.format(java.util.Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024))

        /** "192.168.1.20:8788/" → "http://192.168.1.20:8788" (as V15 did). */
        fun normalizeUrl(s: String?): String {
            var v = s?.trim().orEmpty()
            if (v.isEmpty()) return ""
            if (!v.startsWith("http://", ignoreCase = true) && !v.startsWith("https://", ignoreCase = true)) v = "http://$v"
            return v.trimEnd('/')
        }

        fun checkCredentials(email: String, pin: String) {
            if (!email.contains('@') || email.length < 3) throw SyncException(SyncError.BAD_INPUT, "Bitte eine gültige E-Mail-Adresse eintragen.")
            if (pin.length != 6 || !pin.all { it in '0'..'9' }) throw SyncException(SyncError.BAD_INPUT, "Die PIN hat genau 6 Ziffern.")
        }

        /** Network failures with their cause in plain German, plus the original text for troubleshooting. */
        fun networkError(e: IOException): SyncException {
            if (e is SyncException) return e
            val raw = e.message ?: e.javaClass.simpleName
            val msg = when {
                e is UnknownHostException -> "Server nicht gefunden. Adresse prüfen."
                e is ConnectException || e is NoRouteToHostException -> "Server nicht erreichbar. Läuft er, und ist das Tablet im selben WLAN?"
                e is SocketTimeoutException -> "Der Server antwortet nicht (Zeitüberschreitung)."
                e is SSLException -> "Sichere Verbindung fehlgeschlagen. Mit http:// statt https:// versuchen?"
                raw.contains("reset", ignoreCase = true) -> "Verbindung vom Server zurückgesetzt."
                raw.contains("broken pipe", ignoreCase = true) -> "Verbindung abgebrochen, der Server hat nicht weiter gelesen."
                e is java.io.EOFException || raw.contains("end of stream", ignoreCase = true) -> "Der Server hat die Verbindung vorzeitig beendet."
                raw.contains("unreachable", ignoreCase = true) -> "Kein Netzwerk. WLAN prüfen."
                else -> "Verbindung zum Server unterbrochen."
            }
            return SyncException(SyncError.UNREACHABLE, "$msg [$raw]", e)
        }
    }
}
