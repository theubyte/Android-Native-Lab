package de.millione.schulranzen.core.shk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    private fun c(q: Quantity, v: Double, from: String, to: String) = Units.convert(q, v, from, to)

    @Test fun pressure() {
        assertEquals(10.19716, c(Quantity.PRESSURE, 1.0, "bar", "mWS"), 1e-5)
        assertEquals(1.0, c(Quantity.PRESSURE, 100.0, "kPa", "bar"), 1e-12)
        assertEquals(1000.0, c(Quantity.PRESSURE, 1.0, "bar", "mbar"), 1e-9)
        assertEquals(14.5038, c(Quantity.PRESSURE, 1.0, "bar", "psi"), 1e-4)
    }

    @Test fun flow() {
        assertEquals(3.6, c(Quantity.FLOW, 60.0, "l/min", "m³/h"), 1e-12)
        assertEquals(1000.0, c(Quantity.FLOW, 1.0, "m³/h", "l/h"), 1e-9)
        assertEquals(1.0, c(Quantity.FLOW, 60.0, "l/min", "l/s"), 1e-12)
    }

    @Test fun powerAndEnergy() {
        assertEquals(1.163, c(Quantity.POWER, 1.0, "kcal/h", "W"), 1e-12)
        assertEquals(3600.0, c(Quantity.ENERGY, 1.0, "kWh", "kJ"), 1e-9)
        assertEquals(3.6, c(Quantity.ENERGY, 1.0, "kWh", "MJ"), 1e-12)
        assertEquals(1.163, c(Quantity.ENERGY, 1.0, "kcal", "Wh"), 1e-12)
    }

    @Test fun lengthAreaVolume() {
        assertEquals(25.4, c(Quantity.LENGTH, 1.0, "Zoll", "mm"), 1e-12)
        assertEquals(1000.0, c(Quantity.VOLUME, 1.0, "m³", "l"), 1e-9)
        assertEquals(10_000.0, c(Quantity.AREA, 1.0, "m²", "cm²"), 1e-9)
        assertEquals(3.6, c(Quantity.VELOCITY, 1.0, "m/s", "km/h"), 1e-12)
    }

    @Test fun temperature() {
        assertEquals(68.0, c(Quantity.TEMPERATURE, 20.0, "°C", "°F"), 1e-9)
        assertEquals(273.15, c(Quantity.TEMPERATURE, 0.0, "°C", "K"), 1e-9)
        assertEquals(-40.0, c(Quantity.TEMPERATURE, -40.0, "°F", "°C"), 1e-9)
        assertEquals(100.0, c(Quantity.TEMPERATURE, 373.15, "K", "°C"), 1e-9)
    }

    @Test fun roundTripAllUnits() {
        Quantity.entries.forEach { q ->
            q.units.forEach { a ->
                q.units.forEach { b ->
                    val there = c(q, 12.5, a.symbol, b.symbol)
                    assertEquals("${q.title} ${a.symbol}→${b.symbol}", 12.5, c(q, there, b.symbol, a.symbol), 1e-9)
                }
            }
        }
    }

    @Test fun convertAllAndUnknownUnit() {
        val all = Units.convertAll(Quantity.PRESSURE, 2.0, "bar")
        assertEquals(Quantity.PRESSURE.units.size, all.size)
        assertEquals(200_000.0, all.first { it.first.symbol == "Pa" }.second, 1e-6)
        assertTrue(runCatching { c(Quantity.PRESSURE, 1.0, "bar", "kg") }.isFailure)
    }
}
