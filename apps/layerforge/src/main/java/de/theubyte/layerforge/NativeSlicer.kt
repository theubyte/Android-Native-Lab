package de.theubyte.layerforge

/** No network or PC connection is required. G-code is generated on the device. */
object NativeSlicer {
    init { System.loadLibrary("layerforge") }
    /** [triangleCount, width, depth, height, sampled XYZ vertices...] */
    external fun inspect(stl: ByteArray): FloatArray
    /** Writes the full result to the local cache path; returns `OK:` or `ERROR:` status. */
    external fun slice(
        stl: ByteArray, outputPath: String, layerHeight: Double, lineWidth: Double,
        walls: Int, infill: Int, speed: Double, travel: Double, nozzleC: Int, bedC: Int,
        filament: Double, topBottom: Int, bedX: Double, bedY: Double, bedZ: Double,
        retractionMm: Double, retractMinTravelMm: Double, fanPercent: Int
    ): String
}

data class SliceProfile(
    val layerHeight: Float = 0.20f,
    val lineWidth: Float = 0.45f,
    val walls: Int = 2,
    val infill: Int = 15,
    val printSpeed: Float = 50f,
    val travelSpeed: Float = 150f,
    val nozzleC: Int = 210,
    val bedC: Int = 60,
    val topBottomLayers: Int = 4,
    val retractionMm: Float = 0.8f,
    val retractMinTravelMm: Float = 2f,
    val fanPercent: Int = 80,
    val filamentDiameter: Double = 1.75,
    val bedX: Double = 220.0,
    val bedY: Double = 220.0,
    val bedZ: Double = 250.0
)

data class LoadedModel(
    val name: String,
    val bytes: ByteArray,
    val triangles: Int,
    val width: Float,
    val depth: Float,
    val height: Float,
    val previewVertices: FloatArray
)
