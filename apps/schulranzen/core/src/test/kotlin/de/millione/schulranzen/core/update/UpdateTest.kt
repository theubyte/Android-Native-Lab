package de.millione.schulranzen.core.update

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

private fun manifestJson(url: String = "https://github.com/a/b.apk", sha: String = SHA) = """
    {"versionCode": 50100, "versionName": "0.5.1", "changes": "Neu: Updates", "future": 1,
     "apks": {"arm64-v8a": {"url": "$url", "sha256": "$sha", "size": 1000},
              "armeabi-v7a": {"url": "https://github.com/a/c.apk", "sha256": "$sha"}}}
""".trimIndent()

class UpdateManifestTest {
    @Test fun `reads a complete file and ignores unknown fields`() {
        val m = UpdateManifestParser.parse(manifestJson())
        assertEquals(50100, m.versionCode)
        assertEquals("0.5.1", m.versionName)
        assertEquals("Neu: Updates", m.changes)
        assertEquals(1000, m.apks.getValue("arm64-v8a").size)
        assertEquals(null, m.apks.getValue("armeabi-v7a").size)
    }

    @Test fun `http urls are refused`() {
        val e = assertFailsWith<UpdateException> { UpdateManifestParser.parse(manifestJson(url = "http://github.com/a.apk")) }
        assertEquals(UpdateError.INSECURE_URL, e.error)
    }

    @Test fun `http works only for the debug test server`() {
        assertEquals(50100, UpdateManifestParser.parse(manifestJson(url = "http://10.0.2.2/a.apk"), httpsOnly = false).versionCode)
    }

    @Test fun `broken files are refused`() {
        for (text in listOf(
            "kein json", "[]", "{}",
            """{"versionCode": 0, "versionName": "1", "apks": {"a": {"url": "https://x/a", "sha256": "$SHA"}}}""",
            """{"versionCode": "x", "versionName": "1", "apks": {"a": {"url": "https://x/a", "sha256": "$SHA"}}}""",
            """{"versionCode": 2, "apks": {"a": {"url": "https://x/a", "sha256": "$SHA"}}}""",
            """{"versionCode": 2, "versionName": "1", "apks": {}}""",
            """{"versionCode": 2, "versionName": "1", "apks": {"a": "x"}}""",
            manifestJson(sha = "abc"),
        )) {
            val e = assertFailsWith<UpdateException>(text) { UpdateManifestParser.parse(text) }
            assertEquals(UpdateError.BAD_MANIFEST, e.error, text)
        }
    }
}

class UpdatePolicyTest {
    private val m = UpdateManifestParser.parse(manifestJson())

    @Test fun `only a higher code is an update`() {
        assertTrue(UpdatePolicy.isNewer(50000, m))
        assertFalse(UpdatePolicy.isNewer(50100, m))
        assertFalse(UpdatePolicy.isNewer(60000, m))
    }

    @Test fun `the device's order of preference decides the abi`() {
        assertEquals("arm64-v8a", UpdatePolicy.pickApk(m, listOf("arm64-v8a", "armeabi-v7a")).abi)
        assertEquals("armeabi-v7a", UpdatePolicy.pickApk(m, listOf("armeabi-v7a")).abi)
        assertEquals("armeabi-v7a", UpdatePolicy.pickApk(m, listOf("x86", "armeabi-v7a")).abi)
    }

    @Test fun `no matching abi is a clear error`() {
        val e = assertFailsWith<UpdateException> { UpdatePolicy.pickApk(m, listOf("x86_64")) }
        assertEquals(UpdateError.NO_APK_FOR_DEVICE, e.error)
    }

    @Test fun `auto check at most once a day`() {
        val day = UpdatePolicy.AUTO_CHECK_INTERVAL_MS
        assertTrue(UpdatePolicy.autoCheckDue(0, 1000))
        assertFalse(UpdatePolicy.autoCheckDue(1000, 1000 + day - 1))
        assertTrue(UpdatePolicy.autoCheckDue(1000, 1000 + day))
        assertTrue(UpdatePolicy.autoCheckDue(5000, 1000), "clock set back")
    }

    @Test fun `signers must match exactly`() {
        assertTrue(UpdatePolicy.sameSigner(setOf("a"), setOf("a")))
        assertFalse(UpdatePolicy.sameSigner(setOf("a"), setOf("b")))
        assertFalse(UpdatePolicy.sameSigner(setOf("a"), setOf("a", "b")))
        assertFalse(UpdatePolicy.sameSigner(emptySet(), emptySet()))
    }

    @Test fun `file names cannot leave the folder`() {
        val apk = UpdateApk("../../evil", "https://x/a", SHA)
        assertEquals("schulranzen-50100-evil.apk", UpdatePolicy.fileName(m, apk))
    }
}

class UpdateClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private var honorRange = true
    private var dropAfter: Int? = null
    private var ranges = mutableListOf<String?>()
    private var manifestBody = manifestJson()
    private var manifestStatus = 200

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/update.json") { ex ->
            val b = manifestBody.toByteArray()
            ex.sendResponseHeaders(manifestStatus, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.createContext("/a.apk") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            ranges += range
            val from = if (honorRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
            val rest = content.copyOfRange(from, content.size)
            val cut = dropAfter
            ex.sendResponseHeaders(if (from > 0) 206 else 200, rest.size.toLong())
            runCatching {
                ex.responseBody.use { if (cut != null) { it.write(rest, 0, cut); it.flush(); ex.close() } else it.write(rest) }
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() = server.stop(0)

    private fun apk(size: Long? = content.size.toLong(), sha: String = SHA) = UpdateApk("arm64-v8a", "$base/a.apk", sha, size)
    private val client get() = UpdateClient(httpsOnly = false)

    @Test fun `manifest is fetched`() {
        assertEquals("0.5.1", client.fetchManifest("$base/update.json").versionName)
    }

    @Test fun `server errors and silence are told apart`() {
        manifestStatus = 503
        assertEquals(UpdateError.SERVER, assertFailsWith<UpdateException> { client.fetchManifest("$base/update.json") }.error)
        val closed = "http://127.0.0.1:1/update.json"
        assertEquals(UpdateError.OFFLINE, assertFailsWith<UpdateException> { client.fetchManifest(closed) }.error)
    }

    @Test fun `the real client refuses http before connecting`() {
        val e = assertFailsWith<UpdateException> { UpdateClient().fetchManifest("$base/update.json") }
        assertEquals(UpdateError.INSECURE_URL, e.error)
    }

    @Test fun `download reports progress and verifies`() {
        val part = File(tmp.root, "a.part")
        var last = 0L
        client.download(apk(), part) { done, _ -> last = done }
        assertEquals(content.size.toLong(), last)
        assertContentEquals(content, part.readBytes())
        UpdateClient.verifyChecksum(part, apk(sha = UpdateClient.sha256(part)))
        assertTrue(part.exists())
    }

    @Test fun `a wrong checksum deletes the file`() {
        val part = File(tmp.root, "a.part")
        client.download(apk(), part)
        val e = assertFailsWith<UpdateException> { UpdateClient.verifyChecksum(part, apk()) }
        assertEquals(UpdateError.BAD_CHECKSUM, e.error)
        assertFalse(part.exists())
    }

    @Test fun `continues after a partial file`() {
        val part = File(tmp.root, "a.part").apply { writeBytes(content.copyOf(100_000)) }
        client.download(apk(), part)
        assertEquals("bytes=100000-", ranges.single())
        assertContentEquals(content, part.readBytes())
    }

    @Test fun `starts over when the server ignores the range`() {
        honorRange = false
        val part = File(tmp.root, "a.part").apply { writeBytes(content.copyOf(100_000)) }
        client.download(apk(), part)
        assertContentEquals(content, part.readBytes())
    }

    @Test fun `a broken connection keeps the part and can continue`() {
        val part = File(tmp.root, "a.part")
        dropAfter = 70_000
        val e = assertFailsWith<UpdateException> { client.download(apk(), part) }
        assertEquals(UpdateError.OFFLINE, e.error)
        assertTrue(part.length() in 1..<content.size.toLong())
        dropAfter = null
        client.download(apk(), part)
        assertContentEquals(content, part.readBytes())
    }

    @Test fun `cancel stops and keeps the part`() {
        val part = File(tmp.root, "a.part")
        var calls = 0
        val e = assertFailsWith<UpdateException> { client.download(apk(), part, cancelled = { ++calls > 1 }) }
        assertEquals(UpdateError.CANCELLED, e.error)
        assertTrue(part.exists())
    }

    @Test fun `a file larger than announced is refused`() {
        val part = File(tmp.root, "a.part")
        val e = assertFailsWith<UpdateException> { client.download(apk(size = 1000), part) }
        assertEquals(UpdateError.TOO_LARGE, e.error)
    }

    @Test fun `a complete part needs no request`() {
        val part = File(tmp.root, "a.part").apply { writeBytes(content) }
        client.download(apk(), part)
        assertTrue(ranges.isEmpty())
    }
}
