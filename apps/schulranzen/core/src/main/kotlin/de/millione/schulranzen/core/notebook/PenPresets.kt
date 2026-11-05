package de.millione.schulranzen.core.notebook

/** A saved pen: tool code (see ToolKind), colour (ARGB) and size in page units. */
data class PenPreset(val tool: Byte, val color: Int, val size: Float)

/** Favourite pens and recently used colours, stored as short strings in the preferences. */
object PenPresets {
    const val MAX_PRESETS = 8
    const val MAX_RECENT = 6

    /** [color] to the front, no doubles, at most [limit]. */
    fun pushColor(recent: List<Int>, color: Int, limit: Int = MAX_RECENT): List<Int> =
        (listOf(color) + recent.filter { it != color }).take(limit)

    /** Colours for a fixed number of places: recent first, topped up from [palette]. */
    fun fill(recent: List<Int>, palette: List<Int>, count: Int): List<Int> =
        (recent + palette).distinct().take(count)

    /** Adds [p] at the front; the same pen twice stays one. */
    fun add(presets: List<PenPreset>, p: PenPreset, limit: Int = MAX_PRESETS): List<PenPreset> =
        (listOf(p) + presets.filter { it != p }).take(limit)

    fun encodeColors(colors: List<Int>): String = colors.joinToString(",") { it.toUInt().toString(16) }

    fun decodeColors(text: String?): List<Int> =
        text.orEmpty().split(',').mapNotNull { it.trim().toUIntOrNull(16)?.toInt() }.distinct()

    fun encode(presets: List<PenPreset>): String =
        presets.joinToString(";") { "${it.tool}:${it.color.toUInt().toString(16)}:${it.size}" }

    /** Broken entries are skipped: a damaged preference never costs the rest. */
    fun decode(text: String?): List<PenPreset> =
        text.orEmpty().split(';').mapNotNull { e ->
            val p = e.split(':')
            if (p.size != 3) return@mapNotNull null
            val tool = p[0].toByteOrNull() ?: return@mapNotNull null
            val color = p[1].toUIntOrNull(16)?.toInt() ?: return@mapNotNull null
            val size = p[2].toFloatOrNull()?.takeIf { it > 0f && it.isFinite() } ?: return@mapNotNull null
            PenPreset(tool, color, size)
        }
}
