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
    var profile by remember { mutableStateOf(SliceProfile()) }
    var model by remember { mutableStateOf<LoadedModel?>(null) }
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
                            if (output.size() + length > 60 * 1024 * 1024) throw IOException("STL größer als 60 MB")
                            output.write(buffer, 0, length)
                        }
                        output.toByteArray()
                    } ?: throw IOException("Datei konnte nicht geöffnet werden")
                    val info = NativeSlicer.inspect(data)
                    if (info.size < 13) throw IOException("Kein gültiges STL oder keine Dreiecke")
                    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if(cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Modell.stl"
                    LoadedModel(displayName, data, info[0].toInt(), info[1], info[2], info[3], info.copyOfRange(4, info.size))
                }
            }
            result.onSuccess { model = it; gcodeFile = null; toolpath = null; status = "${it.name} importiert"; page = 0 }
                .onFailure { status = "Importfehler: ${it.message}" }
            busy = false
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
    fun changeProfile(value: SliceProfile) { profile = value; gcodeFile = null; toolpath = null; status = "Parameter geändert – erneut slicen" }
    MaterialTheme(colorScheme = dark) {
        Scaffold(containerColor = dark.background, topBar = {
            TopAppBar(title = {
                Column {
                    Text("LAYERFORGE", fontWeight=FontWeight.Bold)
                    Text("Native Android Slicer · Alpha 0.1", style=MaterialTheme.typography.labelSmall)
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
                            ModelPreview(model)
                            Spacer(Modifier.height(18.dp))
                            OutlinedButton(onClick={importFile.launch(arrayOf("*/*"))}, enabled=!busy, modifier=Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(10.dp)); Text("STL-Modell importieren")
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("Druckraum: ${profile.bedX.toInt()} × ${profile.bedY.toInt()} × ${profile.bedZ.toInt()} mm · Generisches FDM-Profil", style=MaterialTheme.typography.bodyMedium)
                            Text("Mit einem Finger drehen, mit zwei Fingern zoomen. Vorschau zeigt das Modell, keine Druckbahnen.", style=MaterialTheme.typography.bodySmall, color=Color(0xFF9FB2C3))
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
                            ParameterSlider("Düsentemperatur",profile.nozzleC.toFloat(),180f,260f,"°C",0,{ changeProfile(profile.copy(nozzleC=it.toInt())) })
                            ParameterSlider("Betttemperatur",profile.bedC.toFloat(),0f,110f,"°C",0,{ changeProfile(profile.copy(bedC=it.toInt())) })
                            ParameterSlider("Top-/Bottom-Schichten",profile.topBottomLayers.toFloat(),0f,10f,"",0,{changeProfile(profile.copy(topBottomLayers=it.toInt()))})
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
                            Text("Enthalten: STL-Schnitte, einfache Perimeter, alternierendes Linien-Infill und Top-/Bottom-Füllung. Noch nicht enthalten: komplexe Polygonkorrektur, Retracts, Supports, 3MF und gerätespezifische Startmakros.", style=MaterialTheme.typography.bodySmall, color=Color(0xFF9FB2C3))
                            Spacer(Modifier.height(22.dp))
                            Button(
                                onClick={
                                    val loaded = model
                                    if (loaded != null) scope.launch {
                                        busy=true;status="Berechne lokal..."
                                        val current=profile
                                        val result=withContext(Dispatchers.Default){
                                            runCatching {
                                                val file=File(context.cacheDir,"layerforge-output.gcode")
                                                val message=NativeSlicer.slice(loaded.bytes,file.absolutePath,
                                                    current.layerHeight.toDouble(),current.lineWidth.toDouble(),
                                                    current.walls,current.infill,current.printSpeed.toDouble(),
                                                    current.travelSpeed.toDouble(),current.nozzleC,current.bedC,
                                                    current.filamentDiameter,current.topBottomLayers,
                                                    current.bedX,current.bedY,current.bedZ)
                                                if(!message.startsWith("OK:"))throw IOException(message)
                                                Triple(file, message, parseToolpath(file))
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
    val format = if(decimals==2) "%.2f" else "%.0f"
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        Text(name, style=MaterialTheme.typography.bodyMedium)
        Text(String.format(Locale.US,format,value)+" "+unit, color=accent, fontWeight=FontWeight.SemiBold)
    }
    Slider(value=value.coerceIn(min,max),onValueChange=onChange,valueRange=min..max)
    Spacer(Modifier.height(4.dp))
}
