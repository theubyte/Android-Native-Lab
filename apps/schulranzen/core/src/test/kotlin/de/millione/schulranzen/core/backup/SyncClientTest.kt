package de.millione.schulranzen.core.backup

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Protocol tests against a fake that answers like `server/unraid-sync/app.py`
 * (same status codes, JSON fields and bare 401). [realServer] runs the same
 * flow against the real Flask server when SCHULRANZEN_SYNC_URL is set.
 */
class SyncClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var url: String
    private val users = HashMap<String, String>()
    private val stored = LinkedHashMap<String, ByteArray>()
    private var lastContentType = ""

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> runCatching { handle(ex) }.onFailure { ex.reply(500, "{}") }; ex.close() }
        server.start()
        url = "127.0.0.1:${server.address.port}/"
    }

    @After fun stop() = server.stop(0)

    private fun HttpExchange.reply(code: Int, body: String, type: String = "application/json") {
        val b = body.toByteArray()
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
        if (b.isNotEmpty()) responseBody.use { it.write(b) }
    }

    private fun HttpExchange.authorized(): Boolean {
        val ok = requestHeaders.getFirst("Authorization") == "Bearer tok-1"
        if (!ok) reply(401, "<!doctype html><title>401 Unauthorized</title>", "text/html")
        return ok
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.rawPath
        when {
            path == "/health" -> ex.reply(200, """{"ok":true,"service":"DigitalerSchulranzen Sync","time":1}""")
            path == "/api/register" || path == "/api/login" -> {
                val j = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
                val email = j["email"]!!.jsonPrimitive.content; val pin = j["pin"]!!.jsonPrimitive.content
                if (path == "/api/register") {
                    if (email in users) ex.reply(409, """{"error":"E-Mail ist bereits registriert."}""")
                    else { users[email] = pin; ex.reply(200, """{"ok":true,"token":"tok-1","email":"$email"}""") }
                } else {
                    if (users[email] != pin) ex.reply(401, """{"error":"Login fehlgeschlagen."}""")
                    else ex.reply(200, """{"ok":true,"token":"tok-1","email":"$email"}""")
                }
            }
            path == "/api/sync/upload" -> {
                if (!ex.authorized()) return
                lastContentType = ex.requestHeaders.getFirst("Content-Type")
                val boundary = lastContentType.substringAfter("boundary=")
                val body = ex.requestBody.readBytes()
                val text = String(body, Charsets.ISO_8859_1)
                val start = text.indexOf("\r\n\r\n") + 4
                val end = text.lastIndexOf("\r\n--$boundary--")
                require(text.startsWith("--$boundary\r\n") && "name=\"backup\"" in text)
                val name = "20261029-120000-${"%06x".format(stored.size)}.zip"
                stored[name] = body.copyOfRange(start, end)
                ex.reply(200, """{"ok":true,"version":"$name","size":${end - start}}""")
            }
            path == "/api/sync/versions" -> {
                if (!ex.authorized()) return
                val list = stored.entries.reversed().mapIndexed { i, (n, b) -> """{"version":"$n","size":${b.size},"mtime":${2000 - i}}""" }
                ex.reply(200, """{"ok":true,"versions":[${list.joinToString(",")}]}""")
            }
            path == "/api/sync/latest" || path.startsWith("/api/sync/version/") -> {
                if (!ex.authorized()) return
                val b = if (path == "/api/sync/latest") stored.values.lastOrNull() else stored[path.removePrefix("/api/sync/version/")]
                if (b == null) { ex.reply(404, """{"error":"Noch kein Backup vorhanden."}"""); return }
                ex.responseHeaders.add("Content-Type", "application/zip")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            else -> ex.reply(404, "")
        }
    }

    @Test fun normalizesUrls() {
        assertEquals("http://192.168.1.20:8788", SyncClient.normalizeUrl(" 192.168.1.20:8788/ "))
        assertEquals("https://nas.local", SyncClient.normalizeUrl("https://nas.local//"))
        assertEquals("", SyncClient.normalizeUrl("  "))
        assertFailsWith<SyncException> { SyncClient(" ") }
    }

    @Test fun fullFlow() = flow(SyncClient(url), "Max@Example.org")

    private fun flow(c: SyncClient, email: String) {
        assertTrue(c.health())
        val s = c.register(email, "123456")
        assertEquals(email.lowercase(), s.email)
        assertEquals(SyncError.EXISTS, assertFailsWith<SyncException> { c.register(email, "123456") }.error)
        assertEquals(SyncError.WRONG_LOGIN, assertFailsWith<SyncException> { c.login(email, "654321") }.error)
        assertEquals("E-Mail oder PIN falsch. (HTTP 401)", assertFailsWith<SyncException> { c.login(email, "654321") }.message)
        c.token = null
        c.login(email, "123456")

        val zip = tmp.newFile("b.zip").apply { writeBytes(ByteArray(200_000) { (it * 31).toByte() }) }
        var sent = 0L
        val up = c.upload(zip) { s2, _ -> sent = s2 }
        assertEquals(zip.length(), sent)
        assertEquals(zip.length(), up.size)
        val zip2 = tmp.newFile("c.zip").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val up2 = c.upload(zip2)

        val versions = c.versions()
        assertEquals(up2.version, versions.first().name)
        assertTrue(versions.any { it.name == up.version && it.size == zip.length() })

        val got = c.download(up.version, tmp.root.resolve("dl/one.zip"))
        assertContentEquals(zip.readBytes(), got.readBytes())
        val latest = c.download(null, tmp.root.resolve("dl/latest.zip"))
        assertContentEquals(zip2.readBytes(), latest.readBytes())
        assertEquals(SyncError.NOT_FOUND, assertFailsWith<SyncException> { c.download("20000101-000000-000000.zip", tmp.root.resolve("dl/x.zip")) }.error)
        assertTrue(!tmp.root.resolve("dl/x.zip.part").exists())
        assertFailsWith<SyncException> { c.download("../etc.zip", tmp.root.resolve("dl/y.zip")) }
    }

    @Test fun multipartHeader() {
        val c = SyncClient(url)
        c.register("a@b.de", "000000")
        c.upload(tmp.newFile("x.zip").apply { writeBytes(byteArrayOf(1)) })
        assertTrue(lastContentType.startsWith("multipart/form-data; boundary="))
        assertContentEquals(byteArrayOf(1), stored.values.single())
    }

    @Test fun expiredTokenAndMissingLogin() {
        val c = SyncClient(url, token = "falsch")
        val e = assertFailsWith<SyncException> { c.versions() }
        assertEquals(SyncError.SESSION_EXPIRED, e.error)
        assertEquals(401, e.httpCode)
        assertEquals("Anmeldung abgelehnt (HTTP 401: 401 Unauthorized). Bitte neu anmelden.", e.message)
        assertEquals(SyncError.SESSION_EXPIRED, assertFailsWith<SyncException> { SyncClient(url).versions() }.error)
    }

    @Test fun reloginOnceAfterRejectedToken() {
        SyncClient(url).register("r@b.de", "111111")
        val c = SyncClient(url, token = "alt-und-falsch")
        var logins = 0
        val v = c.withRelogin("r@b.de", "111111", onLogin = { logins++ }) { it.versions() }
        assertTrue(v.isEmpty())
        assertEquals(1, logins)
        assertEquals("tok-1", c.token)
        // Without stored credentials the 401 goes through.
        val d = SyncClient(url, token = "falsch")
        assertEquals(SyncError.SESSION_EXPIRED, assertFailsWith<SyncException> { d.withRelogin(null, null) { it.versions() } }.error)
        // Wrong PIN: the login error is reported, not a loop.
        assertEquals(SyncError.WRONG_LOGIN, assertFailsWith<SyncException> { d.withRelogin("r@b.de", "999999") { it.versions() } }.error)
    }

    @Test fun uploadWithRejectedTokenNamesTheCode() {
        val c = SyncClient(url, token = "falsch")
        val big = tmp.newFile("big.zip").apply { writeBytes(ByteArray(3_000_000)) }
        val e = assertFailsWith<SyncException> { c.upload(big) }
        assertEquals(SyncError.SESSION_EXPIRED, e.error)
        assertTrue("HTTP 401" in e.message!!, e.message)
    }

    @Test fun diagnoseShowsEachStep() {
        SyncClient(url).register("d@b.de", "222222")
        val ok = SyncClient(url).diagnose("d@b.de", "222222")
        assertEquals(listOf(true, true, true), ok.map { it.ok })
        assertTrue(ok[2].detail.contains("Noch keine Backups"))
        val wrong = SyncClient(url).diagnose("d@b.de", "000000")
        assertEquals(listOf(true, false), wrong.map { it.ok })
        assertTrue(wrong[1].detail.contains("HTTP 401"))
        val port = ServerSocket(0).use { it.localPort }
        val down = SyncClient("127.0.0.1:$port", connectTimeoutMs = 2000).diagnose("d@b.de", "222222")
        assertEquals(1, down.size)
        assertTrue(down[0].detail.startsWith("Server nicht erreichbar"))
    }

    @Test fun networkErrorsNameTheCause() {
        assertTrue(SyncClient.networkError(java.net.SocketException("Connection reset")).message!!.startsWith("Verbindung vom Server zurückgesetzt. [Connection reset]"))
        assertTrue(SyncClient.networkError(java.net.SocketException("Broken pipe")).message!!.startsWith("Verbindung abgebrochen"))
        assertTrue(SyncClient.networkError(java.net.SocketTimeoutException("Read timed out")).message!!.contains("Zeitüberschreitung"))
    }

    @Test fun inputIsCheckedBeforeTheNetwork() {
        val c = SyncClient("127.0.0.1:1")
        assertEquals(SyncError.BAD_INPUT, assertFailsWith<SyncException> { c.login("keine-mail", "123456") }.error)
        assertEquals(SyncError.BAD_INPUT, assertFailsWith<SyncException> { c.login("a@b.de", "12345") }.error)
        assertEquals(SyncError.BAD_INPUT, assertFailsWith<SyncException> { c.login("a@b.de", "12345a") }.error)
    }

    @Test fun unreachableServer() {
        val port = ServerSocket(0).use { it.localPort } // free port, nobody listens
        val e = assertFailsWith<SyncException> { SyncClient("127.0.0.1:$port", connectTimeoutMs = 2000).health() }
        assertEquals(SyncError.UNREACHABLE, e.error)
        assertTrue(e.message!!.startsWith("Server nicht erreichbar"))
    }

    @Test fun wrongServer() {
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        other.createContext("/") { ex -> ex.reply(200, "<html>Unraid</html>", "text/html"); ex.close() }
        other.start()
        try {
            assertEquals(SyncError.PROTOCOL, assertFailsWith<SyncException> { SyncClient("127.0.0.1:${other.address.port}").health() }.error)
        } finally { other.stop(0) }
    }

    @Test fun realServer() {
        val real = System.getenv("SCHULRANZEN_SYNC_URL")
        Assume.assumeTrue("SCHULRANZEN_SYNC_URL nicht gesetzt", !real.isNullOrBlank())
        flow(SyncClient(real!!), "test-${System.nanoTime()}@example.org")
    }
}
