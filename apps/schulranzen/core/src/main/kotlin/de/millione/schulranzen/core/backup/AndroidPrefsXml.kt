package de.millione.schulranzen.core.backup

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads an Android SharedPreferences file (`shared_prefs/<name>.xml`):
 * `<map>` with `<string name>`, `<int|long|float|boolean name value>` and
 * `<set name><string/>…</set>`. DTDs and external entities are refused.
 */
object AndroidPrefsXml {
    fun parse(bytes: ByteArray): Map<String, Any> {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isExpandEntityReferences = false
        f.isXIncludeAware = false
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val doc = try {
            f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw IOException("Einstellungsdatei ist beschädigt", e)
        }
        val out = LinkedHashMap<String, Any>()
        val root = doc.documentElement ?: return out
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
            val value = el.getAttribute("value")
            when (el.tagName) {
                "string" -> out[name] = el.textContent ?: ""
                "int" -> value.toIntOrNull()?.let { out[name] = it }
                "long" -> value.toLongOrNull()?.let { out[name] = it }
                "float" -> value.toFloatOrNull()?.let { out[name] = it }
                "boolean" -> out[name] = value == "true"
                "set" -> {
                    val items = el.getElementsByTagName("string")
                    out[name] = (0 until items.length).map { items.item(it).textContent ?: "" }.toSet()
                }
            }
        }
        return out
    }
}
