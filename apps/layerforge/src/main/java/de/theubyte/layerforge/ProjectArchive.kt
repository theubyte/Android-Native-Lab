package de.theubyte.layerforge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Application-specific project bundle, not a 3MF file.
 * Safely stores one source mesh plus its editing/slicing state. No external paths.
 */
object ProjectArchive {
    data class Snapshot(val model: LoadedModel, val profile: SliceProfile, val transform: ModelTransform)
    private const val MAX_MESH_BYTES = 60 * 1024 * 1024
    private const val MAX_METADATA_BYTES = 64 * 1024
    fun write(destination: OutputStream, value: Snapshot) {
        require(value.model.bytes.isNotEmpty() && value.model.bytes.size <= MAX_MESH_BYTES)
        val p=value.profile; val t=value.transform
        val settings=JSONObject()
            .put("layerHeight",p.layerHeight.toDouble()).put("lineWidth",p.lineWidth.toDouble())
            .put("walls",p.walls).put("infill",p.infill)
            .put("printSpeed",p.printSpeed.toDouble()).put("travelSpeed",p.travelSpeed.toDouble())
            .put("nozzleC",p.nozzleC).put("bedC",p.bedC)
            .put("topBottomLayers",p.topBottomLayers)
            .put("retractionMm",p.retractionMm.toDouble())
            .put("retractMinTravelMm",p.retractMinTravelMm.toDouble())
            .put("fanPercent",p.fanPercent)
            .put("filamentDiameter",p.filamentDiameter)
            .put("bedX",p.bedX).put("bedY",p.bedY).put("bedZ",p.bedZ)
        val pose=JSONObject().put("scale",t.scale.toDouble())
            .put("rotationX",t.rotationX.toDouble()).put("rotationY",t.rotationY.toDouble())
            .put("rotationZ",t.rotationZ.toDouble()).put("offsetX",t.offsetX.toDouble())
            .put("offsetY",t.offsetY.toDouble())
        val manifest=JSONObject().put("format","layerforge").put("version",1)
            .put("modelName",value.model.name.take(200))
            .put("profile",settings).put("transform",pose)
        ZipOutputStream(destination).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("model.bin"))
            zip.write(value.model.bytes)
            zip.closeEntry()
        }
    }
    private fun bounded(entry: ZipInputStream, cap: Int): ByteArray {
        val output=ByteArrayOutputStream()
        val buffer=ByteArray(32768)
        while(true) {
            val read=entry.read(buffer)
            if(read<0) break
            if(output.size()+read>cap) throw IOException("Projektarchiv überschreitet die Größenbegrenzung")
            output.write(buffer,0,read)
        }
        return output.toByteArray()
    }
    fun read(source: InputStream): Snapshot {
        var meta: ByteArray?=null
        var raw: ByteArray?=null
        ZipInputStream(source).use { zip ->
            var count=0
            while(true) {
                val entry=zip.nextEntry ?: break
                if(++count>4) throw IOException("Zu viele Archiveinträge")
                if(entry.isDirectory) throw IOException("Verzeichnisse im Projektarchiv nicht erlaubt")
                when(entry.name) {
                    "project.json" -> {
                        if(meta!=null)throw IOException("Doppeltes Projektmanifest")
                        meta=bounded(zip,MAX_METADATA_BYTES)
                    }
                    "model.bin" -> {
                        if(raw!=null)throw IOException("Doppelte Modelldatei")
                        raw=bounded(zip,MAX_MESH_BYTES)
                    }
                    else -> throw IOException("Unbekannter Projektarchiv-Eintrag")
                }
                zip.closeEntry()
            }
        }
        val metadata=meta ?: throw IOException("Manifest fehlt")
        val bytes=raw ?: throw IOException("Modell fehlt")
        val json=JSONObject(String(metadata,Charsets.UTF_8))
        if(json.optString("format")!="layerforge"||json.optInt("version")!=1)
            throw IOException("Unbekanntes Projektformat oder Version")
        val q=json.getJSONObject("profile"); val r=json.getJSONObject("transform")
        fun d(obj:JSONObject,key:String,low:Double,high:Double):Double {
            val v=obj.getDouble(key)
            if(!v.isFinite()||v<low||v>high)throw IOException("Ungültiger Wert: $key")
            return v
        }
        fun n(key:String,lo:Int,hi:Int):Int=d(q,key,lo.toDouble(),hi.toDouble()).toInt()
        val p=SliceProfile(
            layerHeight=d(q,"layerHeight",0.05,0.6).toFloat(),
            lineWidth=d(q,"lineWidth",0.2,1.2).toFloat(),
            walls=n("walls",1,6),infill=n("infill",0,100),
            printSpeed=d(q,"printSpeed",5.0,300.0).toFloat(),
            travelSpeed=d(q,"travelSpeed",10.0,600.0).toFloat(),
            nozzleC=n("nozzleC",0,350),bedC=n("bedC",0,150),
            topBottomLayers=n("topBottomLayers",0,20),
            retractionMm=d(q,"retractionMm",0.0,5.0).toFloat(),
            retractMinTravelMm=d(q,"retractMinTravelMm",0.1,50.0).toFloat(),
            fanPercent=n("fanPercent",0,100),
            filamentDiameter=d(q,"filamentDiameter",1.0,3.2),
            bedX=d(q,"bedX",20.0,1000.0),
            bedY=d(q,"bedY",20.0,1000.0),
            bedZ=d(q,"bedZ",20.0,2000.0))
        val t=ModelTransform(
            scale=d(r,"scale",0.01,10.0).toFloat(),
            rotationX=d(r,"rotationX",-360.0,360.0).toFloat(),
            rotationY=d(r,"rotationY",-360.0,360.0).toFloat(),
            rotationZ=d(r,"rotationZ",-360.0,360.0).toFloat(),
            offsetX=d(r,"offsetX",-1000.0,1000.0).toFloat(),
            offsetY=d(r,"offsetY",-1000.0,1000.0).toFloat()
        )
        val shape=NativeSlicer.inspect(bytes)
        if(shape.size<13)throw IOException("Projekt enthält kein gültiges STL-/OBJ-Modell")
        val model=LoadedModel(json.optString("modelName","Modell").take(200),
            bytes,shape[0].toInt(),shape[1],shape[2],shape[3],
            shape.copyOfRange(4,shape.size))
        return Snapshot(model,p,t)
    }
}
