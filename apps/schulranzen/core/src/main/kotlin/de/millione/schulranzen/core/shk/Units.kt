package de.millione.schulranzen.core.shk

/**
 * A unit as an affine map to its quantity's base unit: base = value · factor + offset.
 * Only temperatures need an offset; everything else is a plain factor.
 */
data class MeasureUnit(
    val symbol: String,
    val name: String,
    val factor: Double,
    val offset: Double = 0.0,
) {
    fun toBase(value: Double) = value * factor + offset
    fun fromBase(base: Double) = (base - offset) / factor
}

/** Physical quantities offered in the unit assistant, each with its units (base unit first). */
enum class Quantity(val title: String, val units: List<MeasureUnit>) {
    PRESSURE(
        "Druck",
        listOf(
            MeasureUnit("Pa", "Pascal", 1.0),
            MeasureUnit("hPa", "Hektopascal", 100.0),
            MeasureUnit("kPa", "Kilopascal", 1_000.0),
            MeasureUnit("MPa", "Megapascal", 1_000_000.0),
            MeasureUnit("mbar", "Millibar", 100.0),
            MeasureUnit("bar", "Bar", 100_000.0),
            MeasureUnit("mWS", "Meter Wassersäule", Shk.G * Shk.WATER_DENSITY),
            MeasureUnit("mmWS", "Millimeter Wassersäule", Shk.G * Shk.WATER_DENSITY / 1000.0),
            MeasureUnit("psi", "Pfund pro Quadratzoll", 6_894.757293168),
        ),
    ),
    FLOW(
        "Volumenstrom",
        listOf(
            MeasureUnit("m³/s", "Kubikmeter pro Sekunde", 1.0),
            MeasureUnit("m³/h", "Kubikmeter pro Stunde", 1.0 / 3600.0),
            MeasureUnit("l/s", "Liter pro Sekunde", 1.0 / 1000.0),
            MeasureUnit("l/min", "Liter pro Minute", 1.0 / 60_000.0),
            MeasureUnit("l/h", "Liter pro Stunde", 1.0 / 3_600_000.0),
        ),
    ),
    POWER(
        "Leistung",
        listOf(
            MeasureUnit("W", "Watt", 1.0),
            MeasureUnit("kW", "Kilowatt", 1_000.0),
            MeasureUnit("MW", "Megawatt", 1_000_000.0),
            MeasureUnit("kcal/h", "Kilokalorien pro Stunde", 4186.8 / 3600.0),
            MeasureUnit("PS", "Pferdestärke", 735.49875),
        ),
    ),
    ENERGY(
        "Energie",
        listOf(
            MeasureUnit("J", "Joule", 1.0),
            MeasureUnit("kJ", "Kilojoule", 1_000.0),
            MeasureUnit("MJ", "Megajoule", 1_000_000.0),
            MeasureUnit("Wh", "Wattstunde", 3_600.0),
            MeasureUnit("kWh", "Kilowattstunde", 3_600_000.0),
            MeasureUnit("MWh", "Megawattstunde", 3_600_000_000.0),
            MeasureUnit("kcal", "Kilokalorie", 4_186.8),
        ),
    ),
    LENGTH(
        "Länge",
        listOf(
            MeasureUnit("m", "Meter", 1.0),
            MeasureUnit("mm", "Millimeter", 0.001),
            MeasureUnit("cm", "Zentimeter", 0.01),
            MeasureUnit("dm", "Dezimeter", 0.1),
            MeasureUnit("km", "Kilometer", 1_000.0),
            MeasureUnit("Zoll", "Zoll (″)", 0.0254),
        ),
    ),
    AREA(
        "Fläche",
        listOf(
            MeasureUnit("m²", "Quadratmeter", 1.0),
            MeasureUnit("mm²", "Quadratmillimeter", 1e-6),
            MeasureUnit("cm²", "Quadratzentimeter", 1e-4),
            MeasureUnit("dm²", "Quadratdezimeter", 1e-2),
        ),
    ),
    VOLUME(
        "Volumen",
        listOf(
            MeasureUnit("m³", "Kubikmeter", 1.0),
            MeasureUnit("l", "Liter", 0.001),
            MeasureUnit("ml", "Milliliter", 1e-6),
            MeasureUnit("dm³", "Kubikdezimeter", 0.001),
            MeasureUnit("cm³", "Kubikzentimeter", 1e-6),
        ),
    ),
    VELOCITY(
        "Geschwindigkeit",
        listOf(
            MeasureUnit("m/s", "Meter pro Sekunde", 1.0),
            MeasureUnit("km/h", "Kilometer pro Stunde", 1.0 / 3.6),
            MeasureUnit("m/min", "Meter pro Minute", 1.0 / 60.0),
        ),
    ),
    TEMPERATURE(
        "Temperatur",
        listOf(
            MeasureUnit("°C", "Grad Celsius", 1.0, 273.15),
            MeasureUnit("K", "Kelvin", 1.0),
            MeasureUnit("°F", "Grad Fahrenheit", 5.0 / 9.0, 273.15 - 32.0 * 5.0 / 9.0),
        ),
    );

    val base: MeasureUnit get() = units.first()

    fun unit(symbol: String): MeasureUnit =
        units.firstOrNull { it.symbol == symbol } ?: throw IllegalArgumentException("Unbekannte Einheit „$symbol“ für $title")
}

object Units {
    /** Converts [value] between two units of the same [quantity]. */
    fun convert(quantity: Quantity, value: Double, from: String, to: String): Double {
        require(value.isFinite()) { "Ungültiger Wert" }
        if (from == to) return value
        return quantity.unit(to).fromBase(quantity.unit(from).toBase(value))
    }

    /** One value in every unit of its quantity, for the "all at once" table. */
    fun convertAll(quantity: Quantity, value: Double, from: String): List<Pair<MeasureUnit, Double>> {
        val base = quantity.unit(from).toBase(value)
        return quantity.units.map { it to it.fromBase(base) }
    }
}
