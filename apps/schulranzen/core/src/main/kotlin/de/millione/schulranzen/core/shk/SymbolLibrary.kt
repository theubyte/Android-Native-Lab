package de.millione.schulranzen.core.shk

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Point in schema units (one grid cell = 1). y points down, as on screen. */
@kotlinx.serialization.Serializable
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    fun dist(o: Pt): Double = kotlin.math.hypot(x - o.x, y - o.y)
}

/**
 * Drawing primitive of a symbol in local units around its centre. The UI
 * renders these on screen and into PNG/PDF exports, so the library is drawn
 * by one code path and can be checked in tests.
 */
sealed interface Prim {
    data class Line(val a: Pt, val b: Pt, val dashed: Boolean = false) : Prim
    data class Circle(val c: Pt, val r: Double, val filled: Boolean = false) : Prim
    data class Poly(val points: List<Pt>, val closed: Boolean = true, val filled: Boolean = false) : Prim
    /** Text centred at [at], [size] in units. Not rotated with the symbol, so it stays readable. */
    data class Label(val at: Pt, val text: String, val size: Double = 0.6) : Prim
}

/** A connection point. [name] is shown in the editor ("VL", "RL" …). */
data class PortDef(val at: Pt, val name: String = "")

enum class SymbolGroup(val title: String) { GENERATOR("Wärmeerzeuger"), CONSUMER("Verbraucher"), VALVE("Armaturen"), COMPONENT("Bauteile") }

/** Symbol types of the schema editor. */
enum class SymbolType { PUMP, MAG, STORAGE, BOILER, HEAT_PUMP, RADIATOR, FLOOR_HEATING, VALVE, CHECK_VALVE, THERMO_VALVE, THREE_WAY, SAFETY_VALVE, MIXER, VENT, STRAINER, HEAT_EXCHANGER, SENSOR }

/**
 * One library symbol: outline, ports and the calculator it opens.
 * @property halfW half width of the hit box in units, @property halfH half height.
 * @property calculatorId quick calculator opened from the symbol; its inputs are the symbol's properties.
 */
data class SymbolDef(
    val type: SymbolType,
    val title: String,
    val group: SymbolGroup,
    val halfW: Double,
    val halfH: Double,
    val ports: List<PortDef>,
    val prims: List<Prim>,
    val calculatorId: String? = null,
    /** Material list group for counting. */
    val materialGroup: MaterialGroup = MaterialGroup.COMPONENT,
)

/**
 * Symbols drawn after the DIN EN ISO 10628 / DIN 2481 style used in SHK
 * schematics, simplified for clarity on the tablet.
 */
object SymbolLibrary {
    private fun p(x: Double, y: Double) = Pt(x, y)
    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, dashed: Boolean = false) = Prim.Line(p(x1, y1), p(x2, y2), dashed)
    private fun rect(x1: Double, y1: Double, x2: Double, y2: Double, filled: Boolean = false) =
        Prim.Poly(listOf(p(x1, y1), p(x2, y1), p(x2, y2), p(x1, y2)), closed = true, filled = filled)
    private fun tri(vararg xy: Double, filled: Boolean = false) =
        Prim.Poly(xy.toList().chunked(2).map { p(it[0], it[1]) }, closed = true, filled = filled)

    /** Rounded rectangle as a polygon, for vessels. */
    private fun capsule(x1: Double, y1: Double, x2: Double, y2: Double, r: Double): Prim.Poly {
        val pts = ArrayList<Pt>()
        fun arc(cx: Double, cy: Double, from: Double) {
            for (i in 0..6) { val a = (from + i * 15.0) * PI / 180.0; pts += p(cx + r * cos(a), cy + r * sin(a)) }
        }
        arc(x2 - r, y1 + r, 270.0); arc(x2 - r, y2 - r, 0.0); arc(x1 + r, y2 - r, 90.0); arc(x1 + r, y1 + r, 180.0)
        return Prim.Poly(pts)
    }

    /** Valve body: two triangles meeting in the centre, ports at ±1. */
    private fun bowtie(leftFilled: Boolean = false, rightFilled: Boolean = false) = listOf(
        tri(-1.0, -0.5, -1.0, 0.5, 0.0, 0.0, filled = leftFilled),
        tri(1.0, -0.5, 1.0, 0.5, 0.0, 0.0, filled = rightFilled),
    )

    private fun zigzag(x1: Double, y1: Double, x2: Double, y2: Double, n: Int, amp: Double): Prim.Poly {
        val pts = ArrayList<Pt>()
        val dx = x2 - x1; val dy = y2 - y1
        val len = kotlin.math.hypot(dx, dy)
        val nx = -dy / len * amp; val ny = dx / len * amp
        pts += p(x1, y1)
        for (i in 1 until 2 * n) {
            val t = i / (2.0 * n)
            val s = if (i % 2 == 1) 1 else -1
            pts += p(x1 + dx * t + nx * s, y1 + dy * t + ny * s)
        }
        pts += p(x2, y2)
        return Prim.Poly(pts, closed = false)
    }

    private val inOut = listOf(PortDef(p(-1.0, 0.0), "ein"), PortDef(p(1.0, 0.0), "aus"))
    private val threeWay = listOf(PortDef(p(-1.0, 0.0), "A"), PortDef(p(1.0, 0.0), "AB"), PortDef(p(0.0, 1.0), "B"))
    private val generatorPorts = listOf(PortDef(p(2.0, -1.0), "VL"), PortDef(p(2.0, 1.0), "RL"))

    val all: List<SymbolDef> = listOf(
        SymbolDef(
            SymbolType.BOILER, "Heizkessel", SymbolGroup.GENERATOR, 1.0, 1.5, generatorPorts,
            listOf(
                rect(-1.0, -1.5, 1.0, 1.5),
                line(1.0, -1.0, 2.0, -1.0), line(1.0, 1.0, 2.0, 1.0),
                // Flame
                Prim.Poly(listOf(p(0.0, -0.8), p(0.45, -0.1), p(0.35, 0.5), p(0.0, 0.8), p(-0.35, 0.5), p(-0.45, -0.1), p(-0.1, 0.1)), closed = true),
            ),
            calculatorId = "safety_valve",
        ),
        SymbolDef(
            SymbolType.HEAT_PUMP, "Wärmepumpe", SymbolGroup.GENERATOR, 1.0, 1.5, generatorPorts,
            listOf(
                rect(-1.0, -1.5, 1.0, 1.5),
                line(1.0, -1.0, 2.0, -1.0), line(1.0, 1.0, 2.0, 1.0),
                zigzag(-0.7, 0.6, 0.7, 0.6, 3, 0.25),
                Prim.Label(p(0.0, -0.4), "WP", 0.8),
            ),
            calculatorId = "flow_from_power",
        ),
        SymbolDef(
            SymbolType.STORAGE, "Speicher", SymbolGroup.GENERATOR, 1.0, 2.0,
            listOf(PortDef(p(-1.0, -1.0), "WT VL"), PortDef(p(-1.0, 1.0), "WT RL"), PortDef(p(1.0, -1.0), "WW"), PortDef(p(1.0, 1.0), "KW")),
            listOf(
                capsule(-1.0, -2.0, 1.0, 2.0, 0.6),
                // Heating coil from the flow to the return connection.
                Prim.Poly(listOf(p(-1.0, -1.0), p(-0.2, -1.0), p(-0.6, -0.6), p(-0.2, -0.2), p(-0.6, 0.2), p(-0.2, 0.6), p(-0.6, 1.0), p(-1.0, 1.0)), closed = false),
            ),
            calculatorId = "heating_time",
        ),
        SymbolDef(
            SymbolType.HEAT_EXCHANGER, "Wärmetauscher", SymbolGroup.GENERATOR, 1.0, 1.5,
            listOf(PortDef(p(-2.0, -1.0), "prim. ein"), PortDef(p(-2.0, 1.0), "prim. aus"), PortDef(p(2.0, -1.0), "sek. aus"), PortDef(p(2.0, 1.0), "sek. ein")),
            listOf(
                rect(-1.0, -1.5, 1.0, 1.5), line(-1.0, 1.5, 1.0, -1.5),
                line(-2.0, -1.0, -1.0, -1.0), line(-2.0, 1.0, -1.0, 1.0), line(1.0, -1.0, 2.0, -1.0), line(1.0, 1.0, 2.0, 1.0),
            ),
            calculatorId = "flow_from_power",
        ),
        SymbolDef(
            SymbolType.RADIATOR, "Heizkörper", SymbolGroup.CONSUMER, 1.5, 1.0,
            listOf(PortDef(p(-1.0, 1.0), "VL"), PortDef(p(1.0, 1.0), "RL")),
            listOf(rect(-1.5, -1.0, 1.5, 0.5)) + (-1..1).map { i -> line(i * 0.5 * 1.5, -0.8, i * 0.5 * 1.5, 0.3) } +
                listOf(line(-1.0, 0.5, -1.0, 1.0), line(1.0, 0.5, 1.0, 1.0)),
            calculatorId = "radiator", materialGroup = MaterialGroup.RADIATOR,
        ),
        SymbolDef(
            SymbolType.FLOOR_HEATING, "Fußbodenheizkreis", SymbolGroup.CONSUMER, 1.5, 1.0,
            listOf(PortDef(p(-1.0, 1.0), "VL"), PortDef(p(1.0, 1.0), "RL")),
            listOf(
                rect(-1.5, -1.0, 1.5, 0.5),
                Prim.Poly(
                    listOf(p(-1.0, 1.0), p(-1.0, -0.7), p(-0.5, -0.7), p(-0.5, 0.2), p(0.0, 0.2), p(0.0, -0.7), p(0.5, -0.7), p(0.5, 0.2), p(1.0, 0.2), p(1.0, 1.0)),
                    closed = false,
                ),
            ),
            calculatorId = "heat_load", materialGroup = MaterialGroup.RADIATOR,
        ),
        SymbolDef(
            SymbolType.PUMP, "Umwälzpumpe", SymbolGroup.COMPONENT, 1.0, 0.8, inOut,
            listOf(
                Prim.Circle(p(0.0, 0.0), 0.75), line(-1.0, 0.0, -0.75, 0.0), line(0.75, 0.0, 1.0, 0.0),
                tri(-0.37, -0.65, -0.37, 0.65, 0.75, 0.0),
            ),
            calculatorId = "pump_head",
        ),
        SymbolDef(
            SymbolType.MAG, "Ausdehnungsgefäß", SymbolGroup.COMPONENT, 0.8, 1.6,
            listOf(PortDef(p(0.0, 2.0), "")),
            listOf(capsule(-0.8, -1.6, 0.8, 1.2, 0.6), line(-0.8, -0.2, 0.8, -0.2), line(0.0, 1.2, 0.0, 2.0)),
            calculatorId = "mag",
        ),
        SymbolDef(SymbolType.VALVE, "Absperrventil", SymbolGroup.VALVE, 1.0, 0.5, inOut, bowtie() + Prim.Circle(p(0.0, 0.0), 0.14, filled = true), materialGroup = MaterialGroup.VALVE),
        SymbolDef(SymbolType.CHECK_VALVE, "Rückschlagventil", SymbolGroup.VALVE, 1.0, 0.5, inOut, bowtie(rightFilled = true), materialGroup = MaterialGroup.VALVE),
        SymbolDef(
            SymbolType.THERMO_VALVE, "Thermostatventil", SymbolGroup.VALVE, 1.0, 1.4, inOut,
            bowtie() + listOf(line(0.0, 0.0, 0.0, -0.8), rect(-0.4, -1.4, 0.4, -0.8), zigzag(-0.3, -1.1, 0.3, -1.1, 2, 0.15)),
            calculatorId = "radiator", materialGroup = MaterialGroup.VALVE,
        ),
        SymbolDef(
            SymbolType.THREE_WAY, "Dreiwegeventil", SymbolGroup.VALVE, 1.0, 1.0, threeWay,
            bowtie() + tri(-0.5, 1.0, 0.5, 1.0, 0.0, 0.0), materialGroup = MaterialGroup.VALVE,
        ),
        SymbolDef(
            SymbolType.MIXER, "Mischer (motorisch)", SymbolGroup.VALVE, 1.0, 1.6, threeWay,
            bowtie() + tri(-0.5, 1.0, 0.5, 1.0, 0.0, 0.0) + listOf(line(0.0, 0.0, 0.0, -0.9), Prim.Circle(p(0.0, -1.3), 0.4), Prim.Label(p(0.0, -1.3), "M", 0.5)),
            calculatorId = "mixing_temperature", materialGroup = MaterialGroup.VALVE,
        ),
        SymbolDef(
            SymbolType.SAFETY_VALVE, "Sicherheitsventil", SymbolGroup.VALVE, 1.0, 1.0,
            listOf(PortDef(p(0.0, 1.0), "ein"), PortDef(p(1.0, 0.0), "abblasen")),
            listOf(tri(-0.5, 1.0, 0.5, 1.0, 0.0, 0.0), tri(1.0, -0.5, 1.0, 0.5, 0.0, 0.0), zigzag(0.0, 0.0, -0.8, -0.8, 3, 0.18)),
            calculatorId = "safety_valve", materialGroup = MaterialGroup.VALVE,
        ),
        SymbolDef(
            SymbolType.VENT, "Entlüfter", SymbolGroup.COMPONENT, 0.5, 1.0,
            listOf(PortDef(p(0.0, 1.0), "")),
            listOf(line(0.0, 1.0, 0.0, 0.3), rect(-0.4, -0.3, 0.4, 0.3), line(0.0, -0.3, 0.0, -1.0), line(-0.25, -0.75, 0.0, -1.0), line(0.25, -0.75, 0.0, -1.0)),
            materialGroup = MaterialGroup.VALVE,
        ),
        SymbolDef(
            SymbolType.STRAINER, "Schmutzfänger", SymbolGroup.COMPONENT, 1.0, 0.7, inOut,
            listOf(
                Prim.Poly(listOf(p(-0.7, 0.0), p(0.0, -0.7), p(0.7, 0.0), p(0.0, 0.7))),
                line(-1.0, 0.0, -0.7, 0.0), line(0.7, 0.0, 1.0, 0.0), line(-0.35, 0.35, 0.35, -0.35, dashed = true),
            ),
            materialGroup = MaterialGroup.VALVE,
        ),
        SymbolDef(
            SymbolType.SENSOR, "Temperaturfühler", SymbolGroup.COMPONENT, 0.5, 1.0,
            listOf(PortDef(p(0.0, 1.0), "")),
            listOf(Prim.Circle(p(0.0, -0.5), 0.45), Prim.Label(p(0.0, -0.5), "T", 0.5), line(0.0, -0.05, 0.0, 1.0)),
        ),
    )

    fun def(type: SymbolType): SymbolDef = all.first { it.type == type }

    init {
        check(all.map { it.type }.toSet() == SymbolType.entries.toSet()) { "Every symbol type needs a definition" }
    }
}
