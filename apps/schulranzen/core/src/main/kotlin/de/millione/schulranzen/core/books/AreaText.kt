package de.millione.schulranzen.core.books

/**
 * The text of a marked area of a page, ready to become a card or a note:
 * words whose middle lies in the area, in reading order. Lines wrapped by the
 * page layout flow together again and words split by a hyphen are whole;
 * real line ends (a sentence ends, a list goes on) stay.
 */
object AreaText {
    /** Words of [page] inside [area] (unrotated page units); empty when there are none. */
    fun extract(page: PageText, area: UnitRect): String {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        var last = -1
        for (i in 0 until page.wordCount) {
            val b = page.box(i)
            val mx = (b.left + b.right) / 2; val my = (b.top + b.bottom) / 2
            if (mx < area.left || mx > area.right || my < area.top || my > area.bottom) continue
            val newLine = last >= 0 && page.text.substring(page.ends[last], page.starts[i]).contains('\n')
            if (newLine) { lines += current.toString(); current.clear() }
            if (current.isNotEmpty()) current.append(' ')
            current.append(page.text, page.starts[i], page.ends[i])
            last = i
        }
        if (current.isNotEmpty()) lines += current.toString()
        return join(lines)
    }

    /** Lines as they stand on the page -> flowing text. */
    fun join(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) { sb.append(line); continue }
            val end = sb.last()
            val next = line.first()
            when {
                HYPHENS.indexOf(end) >= 0 && next.isLowerCase() -> sb.setLength(sb.length - 1)
                HYPHENS.indexOf(end) >= 0 -> Unit
                end in ".:;!?" -> sb.append('\n')
                next.isLowerCase() || lineIsLong(sb) -> sb.append(' ')
                else -> sb.append('\n')
            }
            sb.append(line)
        }
        return sb.toString()
    }

    /** A capital after a line without a full stop: a German noun goes on after a full line, a short list item does not. */
    private fun lineIsLong(sb: StringBuilder): Boolean = sb.length - (sb.lastIndexOf("\n") + 1) >= LONG_LINE

    /** At most about [max] characters of [text] in one line, cut at a word, for labels. */
    fun quote(text: String, max: Int = 60): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        if (one.length <= max) return one
        val cut = one.lastIndexOf(' ', max).takeIf { it >= max / 2 } ?: max
        return one.substring(0, cut).trimEnd(',', ';', ':', ' ') + " …"
    }

    private const val HYPHENS = "-­‐‑"
    private const val LONG_LINE = 40
}
