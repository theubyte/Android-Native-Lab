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

class SyncException(val error: SyncError, message: String, cause: Throwable? = null) : IOException(message, cause)

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
            code == 409 -> throw SyncException(SyncError.EXISTS, "Diese E-Mail ist schon registriert. Bitte anmelden.")
            code == 400 -> throw SyncException(SyncError.BAD_INPUT, "E-Mail oder PIN ungültig. Die PIN hat genau 6 Ziffern.")
            code == 401 && !register -> throw SyncException(SyncError.WRONG_LOGIN, "E-Mail oder PIN falsch.")
            else -> throw serverError(code, o)
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
        val h = open("POST", "/api/sync/upload", auth = true)
        try {
            h.doOutput = true
            h.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Fixed length streams the file instead of buffering it in memory.
            h.setFixedLengthStreamingMode(head.size + total + tail.size)
            val (code, text) = exchange(h) {
                h.outputStream.buffered(64 * 1024).use { out ->
                    out.write(head)
                    var sent = 0L
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
            if (code != 200) throw serverError(code, o)
            val version = o?.get("version")?.jsonPrimitive?.contentOrNull
                ?: throw SyncException(SyncError.PROTOCOL, "Der Server hat das Hochladen nicht bestätigt.")
            return UploadResult(version, o["size"]?.jsonPrimitive?.longOrNull ?: total)
        } finally {
            h.disconnect()
        }
    }

    /** The stored backups, newest first. */
    fun versions(): List<RemoteVersion> {
        val (code, text) = request("GET", "/api/sync/versions", auth = true)
        val o = parse(text)
        if (code != 200) throw serverError(code, o)
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
                val o = parse(readBody(h.errorStream))
                if (code == 404) throw SyncException(SyncError.NOT_FOUND, if (version == null) "Auf dem Server liegt noch kein Backup." else "Diese Version gibt es auf dem Server nicht mehr.")
                throw serverError(code, o)
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
                        if (total >= 0 && got != total) throw SyncException(SyncError.UNREACHABLE, "Download abgebrochen. Bitte erneut versuchen.")
                    }
                }
            } catch (e: SyncException) {
                tmp.delete(); throw e
            } catch (e: IOException) {
                tmp.delete(); throw networkError(e)
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
            // Flask closes the connection on a too large upload before reading it.
            val code = runCatching { h.responseCode }.getOrNull()
            if (code == 413) throw SyncException(SyncError.TOO_LARGE, "Das Backup ist größer als das Limit des Servers (MAX_UPLOAD_MB).")
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

    private fun serverError(code: Int, o: JsonObject?): SyncException = when (code) {
        401 -> SyncException(SyncError.SESSION_EXPIRED, "Die Anmeldung ist abgelaufen. Bitte neu anmelden.")
        404 -> SyncException(SyncError.NOT_FOUND, "Nicht gefunden. Stimmt die Server-Adresse?")
        413 -> SyncException(SyncError.TOO_LARGE, "Das Backup ist größer als das Limit des Servers (MAX_UPLOAD_MB).")
        in 300..399 -> SyncException(SyncError.PROTOCOL, "Der Server leitet um. Bitte die genaue Adresse eintragen (http:// oder https://).")
        else -> {
            val detail = o?.get("error")?.jsonPrimitive?.contentOrNull
            SyncException(SyncError.SERVER, "Serverfehler $code" + (detail?.let { ": $it" } ?: "."))
        }
    }

    companion object {
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

        fun networkError(e: IOException): SyncException = when (e) {
            is SyncException -> e
            is UnknownHostException -> SyncException(SyncError.UNREACHABLE, "Server nicht gefunden. Adresse prüfen.", e)
            is ConnectException, is NoRouteToHostException -> SyncException(SyncError.UNREACHABLE, "Server nicht erreichbar. Läuft er, und ist das Tablet im selben WLAN?", e)
            is SocketTimeoutException -> SyncException(SyncError.UNREACHABLE, "Der Server antwortet nicht (Zeitüberschreitung).", e)
            is SSLException -> SyncException(SyncError.UNREACHABLE, "Sichere Verbindung fehlgeschlagen. Mit http:// statt https:// versuchen?", e)
            else -> SyncException(SyncError.UNREACHABLE, "Verbindung zum Server unterbrochen: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
