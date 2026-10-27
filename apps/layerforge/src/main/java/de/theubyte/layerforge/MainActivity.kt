package de.theubyte.layerforge

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LayerForgeApp() }
    }
}

private val accent = Color(0xFF36D6C7)
private val dark = darkColorScheme(
    primary = accent, background = Color(0xFF0A121B), surface = Color(0xFF182531),
    onSurface = Color(0xFFE8F0F7), onBackground = Color(0xFFE8F0F7)
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerForgeApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var profile by remember { mutableStateOf(ProfileStore.load(context)) }
    var model by remember { mutableStateOf<LoadedModel?>(null) }
    var transform by remember { mutableStateOf(ModelTransform()) }
    var transformedPreview by remember { mutableStateOf<LoadedModel?>(null) }
    LaunchedEffect(model,transform) {
        val current=model ?: run { transformedPreview=null;return@LaunchedEffect }
        val value=transform
        delay(140)
        val info=withContext(Dispatchers.Default) {
            NativeSlicer.inspectTransformed(current.bytes,value.scale.toDouble(),
                value.rotationX.toDouble(),value.rotationY.toDouble(),value.rotationZ.toDouble(),
                value.offsetX.toDouble(),value.offsetY.toDouble())
        }
        transformedPreview=if(info.size>=13)
            current.copy(width=info[1],depth=info[2],height=info[3],
                previewVertices=info.copyOfRange(4,info.size))
        else null
    }
    var gcodeFile by remember { mutableStateOf<File?>(null) }
    var toolpath by remember { mutableStateOf<Toolpath?>(null) }
    var status by remember { mutableStateOf("Bereit. STL laden und lokal slicen.") }
    var busy by remember { mutableStateOf(false) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val data = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val length = stream.read(buffer)
                            if (length < 0) break
                            if (output.size() + length > 60 * 1024 * 1024) throw IOException("Modell größer als 60 MB")
                            output.write(buffer, 0, length)
                        }
                        output.toByteArray()
                    } ?: throw IOException("Datei konnte nicht geöffnet werden")
                    val info = NativeSlicer.inspect(data)
                    if (info.size < 13) throw IOException("Kein gültiges STL/OBJ oder keine Dreiecke")
                    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if(cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Modell"
                    LoadedModel(displayName, data, info[0].toInt(), info[1], info[2], info[3], info.copyOfRange(4, info.size))
                }
            }
            result.onSuccess { model = it; transform=ModelTransform(); transformedPreview=null; gcodeFile = null; toolpath = null; status = "${it.name} importiert"; page = 0 }
                .onFailure { status = "Importfehler: ${it.message}" }
            busy = false
        }
    }

    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if(uri!=null) scope.launch {
            busy=true
            val result=withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { ProjectArchive.read(it) }
                        ?: throw IOException("Projektdatei konnte nicht geöffnet werden")
                }
            }
            result.onSuccess { snapshot ->
                model=snapshot.model; transform=snapshot.transform
                profile=snapshot.profile;ProfileStore.save(context,snapshot.profile)
                transformedPreview=null;gcodeFile=null;toolpath=null;page=0
                status="Projekt geladen: ${snapshot.model.name}"
            }.onFailure { status="Projektfehler: ${it.message}" }
            busy=false
        }
    }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val original=model
        val snapshot=if(original!=null) ProjectArchive.Snapshot(original,profile,transform) else null
        if(uri!=null && snapshot!=null) scope.launch {
            busy=true
            val result=withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri,"wt")?.use { ProjectArchive.write(it,snapshot) }
                        ?: throw IOException("Projektdatei konnte nicht gespeichert werden")
                }
            }
            status=result.fold({"Projekt gespeichert"},{"Speicherfehler: ${it.message}"})
            busy=false
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val source = gcodeFile
        if (uri != null && source != null) scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { target ->
                        source.inputStream().use { it.copyTo(target) }
                    } ?: throw IOException("Zieldatei kann nicht geschrieben werden")
                }
            }
            status = result.fold({ "G-Code exportiert" }, { "Exportfehler: ${it.message}" })
            busy = false
        }
    }
    fun changeProfile(value: SliceProfile) { profile = value; ProfileStore.save(context, value); gcodeFile = null; toolpath = null; status = "Parameter gespeichert – erneut slicen" }
    fun changeTransform(value: ModelTransform) { transform=value;gcodeFile=null;toolpath=null;status="Modell geändert – erneut slicen" }
    MaterialTheme(colorScheme = dark) {
        Scaffold(containerColor = dark.background, topBar = {
            TopAppBar(title = {
                Column {
                    Text("LAYERFORGE", fontWeight=FontWeight.Bold)
                    Text("Native Android Slicer · Alpha 0.5", style=MaterialTheme.typography.labelSmall)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor=dark.background))
        }) { inset ->
            Column(Modifier.fillMaxSize().padding(inset)) {
                TabRow(selectedTabIndex = page, containerColor=dark.background) {
                    listOf("Modell", "Parameter", "G-Code").forEachIndexed { index, title ->
                        Tab(selected=page==index, onClick={page=index}, text={Text(title)})
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    when(page) {
                        0 -> {
                            Text("Dein 3D-Druckprojekt", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            ModelPreview(transformedPreview ?: model)
                            Spacer(Modifier.height(12.dp))
                            if(model!=null) {
                                Text("Modell bearbeiten",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text("Rotation XYZ um Modellmitte · XY relativ zur Druckplattenmitte · Z automatisch auf Druckplatte.",style=MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                ParameterSlider("Skalieren",transform.scale,0.25f,3f,"×",2,{changeTransform(transform.copy(scale=it))})
                                ParameterSlider("Drehen X",transform.rotationX,-180f,180f,"°",0,{changeTransform(transform.copy(rotationX=it))})
                                ParameterSlider("Drehen Y",transform.rotationY,-180f,180f,"°",0,{changeTransform(transform.copy(rotationY=it))})
                                ParameterSlider("Drehen Z",transform.rotationZ,-180f,180f,"°",0,{changeTransform(transform.copy(rotationZ=it))})
                                ParameterSlider("Position X",transform.offsetX,-(profile.bedX/2).toFloat(),(profile.bedX/2).toFloat(),"mm",1,{changeTransform(transform.copy(offsetX=it))})
                                ParameterSlider("Position Y",transform.offsetY,-(profile.bedY/2).toFloat(),(profile.bedY/2).toFloat(),"mm",1,{changeTransform(transform.copy(offsetY=it))})
                                OutlinedButton(onClick={changeTransform(ModelTransform())},modifier=Modifier.fillMaxWidth()){Text("Modellbearbeitung zurücksetzen")}
                            }
                            Spacer(Modifier.height(18.dp))
                            OutlinedButton(onClick={importFile.launch(arrayOf("*/*"))}, enabled=!busy, modifier=Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(10.dp)); Text("STL-/OBJ-Modell importieren")
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                OutlinedButton(onClick={openProject.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.weight(1f)){Text("Projekt öffnen")}
                                OutlinedButton(onClick={saveProject.launch("LayerForge.lfproj")},enabled=!busy && model!=null,modifier=Modifier.weight(1f)){Text("Projekt speichern")}
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("Druckraum: ${profile.bedX.toInt()} × ${profile.bedY.toInt()} × ${profile.bedZ.toInt()} mm · Generisches FDM-Profil", style=MaterialTheme.typography.bodyMedium)
                            Text("Kamera per Geste drehen/zoomen. Regler ändern die wirkliche Geometrie des G-Codes.", style=MaterialTheme.typography.bodySmall, color=Color(0xFF9FB2C3))
                        }
                        1 -> {
                            Text("Druckeinstellungen", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Text("Generisches FDM-Profil – kein validiertes K1C-Startprofil", color=Color(0xFFFFC78E), style=MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(14.dp))
                            ParameterSlider("Schichthöhe", profile.layerHeight,0.10f,0.40f,"mm",2,{ changeProfile(profile.copy(layerHeight=it)) })
                            ParameterSlider("Linienbreite", profile.lineWidth,0.30f,0.70f,"mm",2,{ changeProfile(profile.copy(lineWidth=it)) })
                            ParameterSlider("Wände", profile.walls.toFloat(),1f,5f,"",0,{ changeProfile(profile.copy(walls=it.toInt())) })
                            ParameterSlider("Infill",profile.infill.toFloat(),0f,100f,"%",0,{ changeProfile(profile.copy(infill=it.toInt())) })
                            ParameterSlider("Druckgeschwindigkeit",profile.printSpeed,10f,180f,"mm/s",0,{ changeProfile(profile.copy(printSpeed=it)) })
                            ParameterSlider("Reisegeschwindigkeit",profile.travelSpeed,30f,400f,"mm/s",0,{ changeProfile(profile.copy(travelSpeed=it)) })
                            ParameterSlider("Düsentemperatur",profile.nozzleC.toFloat(),180f,260f,"°C",0,{ changeProfile(profile.copy(nozzleC=it.toInt())) })
                            ParameterSlider("Betttemperatur",profile.bedC.toFloat(),0f,110f,"°C",0,{ changeProfile(profile.copy(bedC=it.toInt())) })
                            ParameterSlider("Top-/Bottom-Schichten",profile.topBottomLayers.toFloat(),0f,10f,"",0,{changeProfile(profile.copy(topBottomLayers=it.toInt()))})
                            ParameterSlider("Retraktion",profile.retractionMm,0f,3f,"mm",2,{changeProfile(profile.copy(retractionMm=it))})
                            ParameterSlider("Retraktionsweg",profile.retractMinTravelMm,1f,15f,"mm",1,{changeProfile(profile.copy(retractMinTravelMm=it))})
                            ParameterSlider("Bauteillüfter ab Schicht 2",profile.fanPercent.toFloat(),0f,100f,"%",0,{changeProfile(profile.copy(fanPercent=it.toInt()))})
                            Spacer(Modifier.height(8.dp))
                            Text("Drucker – generischer Bauraum",style=MaterialTheme.typography.titleMedium)
                            ParameterSlider("Bauraum X",profile.bedX.toFloat(),100f,450f,"mm",0,{changeProfile(profile.copy(bedX=it.toDouble()))})
                            ParameterSlider("Bauraum Y",profile.bedY.toFloat(),100f,450f,"mm",0,{changeProfile(profile.copy(bedY=it.toDouble()))})
                            ParameterSlider("Bauraum Z",profile.bedZ.toFloat(),100f,500f,"mm",0,{changeProfile(profile.copy(bedZ=it.toDouble()))})
                            OutlinedButton(onClick={changeProfile(SliceProfile())}, modifier=Modifier.fillMaxWidth()) { Text("Generische Standardwerte wiederherstellen") }
                            Text("Gespeicherte Werte sind generisch und nicht für einen bestimmten Drucker validiert.",style=MaterialTheme.typography.bodySmall)
                        }
                        else -> {
                            Text("G-Code erzeugen", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Text("Der native C++-Slicer läuft vollständig auf deinem Android-Gerät, ohne PC oder Cloud.")
                            if (toolpath != null) {
                                Spacer(Modifier.height(16.dp))
                                ToolpathPreview(toolpath!!, profile.bedX, profile.bedY)
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Enthalten: STL-Schnitte, einfache Perimeter, alternierendes Linien-Infill und Top-/Bottom-Füllung. Noch nicht enthalten: robuste Polygonkorrektur, Supports, 3MF und gerätespezifische Startmakros.", style=MaterialTheme.typography.bodySmall, color=Color(0xFF9FB2C3))
                            Spacer(Modifier.height(22.dp))
                            Button(
                                onClick={
                                    val loaded = model
                                    if (loaded != null) scope.launch {
                                        busy=true;status="Berechne lokal..."
                                        val current=profile
                                        val pose=transform
                                        val result=withContext(Dispatchers.Default){
                                            runCatching {
                                                val file=File(context.cacheDir,"layerforge-output.gcode")
                                                val message=NativeSlicer.slice(loaded.bytes,file.absolutePath,
                                                    current.layerHeight.toDouble(),current.lineWidth.toDouble(),
                                                    current.walls,current.infill,current.printSpeed.toDouble(),
                                                    current.travelSpeed.toDouble(),current.nozzleC,current.bedC,
                                                    current.filamentDiameter,current.topBottomLayers,
                                                    current.bedX,current.bedY,current.bedZ,
                                                    current.retractionMm.toDouble(), current.retractMinTravelMm.toDouble(),current.fanPercent,
                                                    pose.scale.toDouble(),pose.rotationX.toDouble(),pose.rotationY.toDouble(),
                                                    pose.rotationZ.toDouble(),pose.offsetX.toDouble(),pose.offsetY.toDouble())
                                                if(!message.startsWith("OK:"))throw IOException(message)
                                                Triple(file, message, parseToolpath(file,current.filamentDiameter))
                                            }
                                        }
                                        result.onSuccess { (file,message,preview) -> gcodeFile=file;toolpath=preview;status=message }
                                              .onFailure { gcodeFile=null;status="Slicing-Fehler: ${it.message}" }
                                        busy=false
                                    }
                                }, enabled=model!=null&&!busy, modifier=Modifier.fillMaxWidth()
                            ) { Text("Auf Android slicen") }
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick={exportFile.launch("layerforge-output.gcode")},
                                enabled=gcodeFile!=null&&!busy, modifier=Modifier.fillMaxWidth()
                            ) { Icon(Icons.Default.SaveAlt,null);Spacer(Modifier.width(8.dp));Text("G-Code exportieren") }
                            Spacer(Modifier.height(18.dp))
                            Text("Hinweis: Alpha-G-Code nur nach externer Prüfung und für kontrollierte Tests verwenden; noch nicht direkt zum Drucker senden.", color=Color(0xFFFFC78E), style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider(color=Color(0xFF29404D))
                Text(status, modifier=Modifier.fillMaxWidth().padding(14.dp), style=MaterialTheme.typography.bodySmall)
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
@Composable
private fun ParameterSlider(
    name:String,value:Float,min:Float,max:Float,unit:String,decimals:Int,onChange:(Float)->Unit
){
    val format = when(decimals) { 2 -> "%.2f"; 1 -> "%.1f"; else -> "%.0f" }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        Text(name, style=MaterialTheme.typography.bodyMedium)
        Text(String.format(Locale.US,format,value)+" "+unit, color=accent, fontWeight=FontWeight.SemiBold)
    }
    Slider(value=value.coerceIn(min,max),onValueChange=onChange,valueRange=min..max)
    Spacer(Modifier.height(4.dp))
}
