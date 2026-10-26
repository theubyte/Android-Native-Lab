package de.theubyte.layerforge

import android.content.Context

/** Offline durable parameters. This is a generic machine profile, not a validated vendor preset. */
object ProfileStore {
    private const val NAME = "layerforge.slice.profile.v1"
    fun load(context: Context): SliceProfile {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val defaults = SliceProfile()
        return SliceProfile(
            layerHeight = p.getFloat("layerHeight", defaults.layerHeight),
            lineWidth = p.getFloat("lineWidth", defaults.lineWidth),
            walls = p.getInt("walls", defaults.walls),
            infill = p.getInt("infill", defaults.infill),
            printSpeed = p.getFloat("printSpeed", defaults.printSpeed),
            travelSpeed = p.getFloat("travelSpeed", defaults.travelSpeed),
            nozzleC = p.getInt("nozzleC", defaults.nozzleC),
            bedC = p.getInt("bedC", defaults.bedC),
            topBottomLayers = p.getInt("topBottomLayers", defaults.topBottomLayers),
            retractionMm = p.getFloat("retractionMm", defaults.retractionMm),
            retractMinTravelMm = p.getFloat("retractMinTravelMm", defaults.retractMinTravelMm),
            fanPercent = p.getInt("fanPercent", defaults.fanPercent)
        )
    }
    fun save(context: Context, value: SliceProfile) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putFloat("layerHeight", value.layerHeight)
            .putFloat("lineWidth", value.lineWidth)
            .putInt("walls", value.walls)
            .putInt("infill", value.infill)
            .putFloat("printSpeed", value.printSpeed)
            .putFloat("travelSpeed", value.travelSpeed)
            .putInt("nozzleC", value.nozzleC)
            .putInt("bedC", value.bedC)
            .putInt("topBottomLayers", value.topBottomLayers)
            .putFloat("retractionMm", value.retractionMm)
            .putFloat("retractMinTravelMm", value.retractMinTravelMm)
            .putInt("fanPercent", value.fanPercent)
            .apply()
    }
}
