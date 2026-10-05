package com.aicompanion

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Green = Color(0xFF2E7D32); val Dark = Color(0xFF14201A); val Card = Color(0xFF1F2E26)
val Red = Color(0xFFD32F2F); val Blue = Color(0xFF1E88E5); val Orange = Color(0xFFF9A825); val Gray = Color(0xFF757575)

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Dark, surface = Card)) { Root() } }
    }
}

@Composable fun Root(vm: RobotVM = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.msg) { if (vm.msg.isNotEmpty()) snack.showSnackbar(vm.msg) }
    if (!vm.booted) return Box(Modifier.fillMaxSize().background(Dark), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🌱 AI COMPANION", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("See the Weed. Protect the Onion. Work Smart.", color = Color(0xFF9CCC65))
            Spacer(Modifier.height(16.dp)); CircularProgressIndicator(color = Green)
        }
    }
    val tabs = listOf("HOME" to Icons.Default.Home, "ROBOT" to Icons.Default.SportsEsports, "AUTO" to Icons.Default.AutoMode,
        "SENSORS" to Icons.Default.Sensors, "SETTINGS" to Icons.Default.Settings)
    Scaffold(containerColor = Dark, snackbarHost = { SnackbarHost(snack) }, bottomBar = {
        NavigationBar(containerColor = Card) { tabs.forEachIndexed { i, t ->
            NavigationBarItem(tab == i, { tab = i }, { Icon(t.second, null) }, label = { Text(t.first, fontSize = 10.sp) }) } }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!vm.espOnline) Banner("ESP32 OFFLINE — controls disabled", Gray)
            if (vm.estop) Banner("EMERGENCY STOP ACTIVE", Red)
            when (tab) { 0 -> Home(vm); 1 -> Manual(vm); 2 -> Auto(vm); 3 -> Sensors(vm); else -> Settings(vm) }
        }
    }
}

@Composable fun Banner(t: String, c: Color) = Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c).padding(12.dp), Alignment.Center) {
    Text(t, fontWeight = FontWeight.Bold, color = Color.White) }
@Composable fun Tile(title: String, value: String, c: Color = Color.White, mod: Modifier = Modifier) =
    Column(mod.clip(RoundedCornerShape(10.dp)).background(Card).padding(12.dp)) {
        Text(title, fontSize = 11.sp, color = Color.LightGray); Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c) }
@Composable fun Btn(t: String, on: Boolean, c: Color = Green, mod: Modifier = Modifier, f: () -> Unit) =
    Button(f, mod.heightIn(min = 56.dp), enabled = on, colors = ButtonDefaults.buttonColors(containerColor = c)) { Text(t, fontWeight = FontWeight.Bold) }
@Composable fun Row2(vararg items: @Composable RowScope.() -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items.forEach { it() } }
@Composable fun Title(t: String) = Text(t, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9CCC65))
fun pct(s: String) = s.toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)
@Composable fun Bar(label: String, value: String, unit: String = "") { Column { Text("$label: $value$unit"); 
    LinearProgressIndicator(progress = { pct(value) ?: 0f }, Modifier.fillMaxWidth().height(12.dp), color = Green) } }

@Composable fun CameraStream(vm: RobotVM) {
    var frame by remember { mutableStateOf<Bitmap?>(null) }; var failed by remember { mutableStateOf(false) }; var key by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, vm.get("cam_ip")) {
        failed = false; frame = null
        if (vm.get("cam_ip").isBlank()) { failed = true; vm.camOnline = false; return@LaunchedEffect }
        try { mjpeg(vm.streamUrl()) { frame = it; vm.camOnline = true } } catch (e: Exception) { failed = true; frame = null; vm.camOnline = false }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { key++ }) }, Alignment.Center) {
        val f = frame
        if (f != null) Image(f.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("STREAM NOT AVAILABLE", color = Orange, fontWeight = FontWeight.Bold); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ key++ }) { Text("RETRY") }; Text("(Camera settings: SETTINGS tab)", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.align(Alignment.CenterVertically)) } }
        else CircularProgressIndicator(color = Green)
    }
}

@Composable fun EStop(vm: RobotVM) =
    if (vm.estop) Btn("CLEAR EMERGENCY STOP", vm.espOnline, Orange, Modifier.fillMaxWidth()) { vm.clearEstop() }
    else Btn("EMERGENCY STOP", true, Red, Modifier.fillMaxWidth().height(72.dp)) { vm.estopNow() }

@Composable fun Home(vm: RobotVM) {
    Text("AI COMPANION", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text("Smart Onion Garden Maintainer Robot", color = Color.LightGray)
    Row2({ Tile("ESP32", if (vm.espOnline) "ONLINE" else "OFFLINE", if (vm.espOnline) Green else Gray, Modifier.weight(1f)) },
        { Tile("ESP32-CAM", if (vm.camOnline) "ONLINE" else "OFFLINE", if (vm.camOnline) Green else Gray, Modifier.weight(1f)) })
    Row2({ Tile("MODE", vm.mode, if (vm.mode == "AUTO") Blue else Color.White, Modifier.weight(1f)) },
        { Tile("MOTOR", vm.v("motor"), modifier = Modifier.weight(1f)) }, { Tile("SPEED", vm.v("speed"), modifier = Modifier.weight(1f)) })
    Tile("EMERGENCY STOP", if (vm.estop) "ACTIVE" else if (vm.espOnline) "SAFE" else "--", if (vm.estop) Red else Green, Modifier.fillMaxWidth())
    CameraStream(vm)
    Row2({ Tile("SOIL", vm.v("soil") + "%", modifier = Modifier.weight(1f)) }, { Tile("TEMP", vm.v("temp") + "°C", modifier = Modifier.weight(1f)) },
        { Tile("HUMIDITY", vm.v("hum") + "%", modifier = Modifier.weight(1f)) })
    Row2({ Tile("WATER", vm.v("water") + "%", modifier = Modifier.weight(1f)) }, { Tile("CHEMICAL", vm.v("chem") + "%", modifier = Modifier.weight(1f)) },
        { Tile("OBSTACLE", vm.v("dist") + " cm", modifier = Modifier.weight(1f)) })
    Row2({ Tile("ARM", vm.v("arm"), modifier = Modifier.weight(1f)) }, { Tile("CUTTER", vm.v("cutter"), modifier = Modifier.weight(1f)) })
    Row2({ Tile("SPRAYER", vm.v("sprayer"), modifier = Modifier.weight(1f)) }, { Tile("WATER PUMP", vm.v("pump"), modifier = Modifier.weight(1f)) })
    EStop(vm)
}

@Composable fun Manual(vm: RobotVM) {
    val on = vm.manualOk
    CameraStream(vm)
    Title("MOVEMENT")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("▲ FORWARD", on) { vm.cmd("ep_move", mapOf("dir" to "forward"), "Forward command sent") }
        Row2({ Btn("◀ LEFT", on) { vm.cmd("ep_move", mapOf("dir" to "left"), "Left command sent") } },
            { Btn("RIGHT ▶", on) { vm.cmd("ep_move", mapOf("dir" to "right"), "Right command sent") } })
        Btn("▼ REVERSE", on) { vm.cmd("ep_move", mapOf("dir" to "backward"), "Reverse command sent") }
    }
    Title("SPEED")
    Row2(*listOf("slow", "medium", "fast").map { s -> @Composable { Btn(s.uppercase(), on, if (vm.v("speed").equals(s, true)) Blue else Green, Modifier.weight(1f)) {
        vm.cmd("ep_speed", mapOf("level" to s), "Speed $s") } } }.toTypedArray())
    Title("PAN / TILT")
    fun servo(a: String, d: String) = vm.cmd("ep_servo", mapOf("axis" to a, "dir" to d), "Camera $a $d")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("▲ TILT UP", on) { servo("tilt", "up") }
        Row2({ Btn("◀ PAN LEFT", on) { servo("pan", "left") } }, { Btn("PAN RIGHT ▶", on) { servo("pan", "right") } })
        Btn("▼ TILT DOWN", on) { servo("tilt", "down") }
        Btn("CENTER", on) { servo("all", "center") }
    }
    Title("RELAY 1")
    Row2({ Btn("ON", on, Orange, Modifier.weight(1f)) { vm.cmd("ep_relay", mapOf("id" to "1", "state" to "on"), "Relay 1 ON") } },
        { Btn("OFF", on, Gray, Modifier.weight(1f)) { vm.cmd("ep_relay", mapOf("id" to "1", "state" to "off"), "Relay 1 OFF") } })
    val d = vm.v("dist").toFloatOrNull(); val lim = vm.get("obstacle_cm", "20").toFloatOrNull() ?: 20f
    Tile("FRONT DISTANCE", if (d == null) "-- cm" else "$d cm  •  " + if (d < lim) "OBSTACLE DETECTED" else "PATH CLEAR", if (d != null && d < lim) Red else Green, Modifier.fillMaxWidth())
    EStop(vm)
}

@Composable fun Auto(vm: RobotVM) {
    var confirm by remember { mutableStateOf(false) }; var stop by remember { mutableStateOf(false) }; var blocked by remember { mutableStateOf<String?>(null) }
    val running = vm.mode == "AUTO"
    Title("AUTO MODE"); Banner(if (running) "AUTO RUNNING" else "AUTO OFF", if (running) Blue else Gray)
    if (running) {
        Tile("CURRENT OPERATION", vm.v("auto_state"), Blue, Modifier.fillMaxWidth())
        val t = vm.v("timer_elapsed"); val tot = vm.v("timer_total")
        if (t != "--" && tot != "--") { Text("$t / $tot sec"); LinearProgressIndicator(progress = { (t.toFloat() / tot.toFloat()).coerceIn(0f, 1f) }, Modifier.fillMaxWidth()) }
        Tile("WEED TARGET", "X:${vm.v("weed_x")} Y:${vm.v("weed_y")}  conf ${vm.v("weed_conf")}%", modifier = Modifier.fillMaxWidth())
        Tile("DISEASE", vm.v("disease"), modifier = Modifier.fillMaxWidth())
    }
    Text("🌿 Weed removal → 🍃 Onion disease → 🌱 Soil test every ${vm.get("soil_min", "5")} min", color = Color.LightGray)
    if (running) Btn("STOP AUTO → MANUAL", vm.espOnline, Orange, Modifier.fillMaxWidth()) { stop = true }
    else Btn("START AUTO", vm.espOnline && !vm.estop, Blue, Modifier.fillMaxWidth()) { confirm = true }
    EStop(vm)
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Start automatic operation?") },
        text = { Text("Manual controls will be disabled. Safety checks run first.") },
        confirmButton = { TextButton({ confirm = false; vm.startAuto { blocked = it } }) { Text("START") } },
        dismissButton = { TextButton({ confirm = false }) { Text("CANCEL") } })
    if (stop) AlertDialog({ stop = false }, title = { Text("Stop automatic operation?") },
        text = { Text("Cutter and pumps turn OFF and the arm returns HOME.") },
        confirmButton = { TextButton({ stop = false; vm.stopAuto() }) { Text("STOP") } }, dismissButton = { TextButton({ stop = false }) { Text("CANCEL") } })
    blocked?.let { AlertDialog({ blocked = null }, title = { Text("AUTO START BLOCKED") }, text = { Text("Failed: $it") },
        confirmButton = { TextButton({ blocked = null }) { Text("OK") } }) }
}

@Composable fun Sensors(vm: RobotVM) {
    Title("SENSORS")
    Tile("FRONT ULTRASONIC", vm.v("dist") + " cm", modifier = Modifier.fillMaxWidth())
    Bar("Soil moisture", vm.v("soil"), "%"); Bar("Humidity", vm.v("hum"), "%"); Tile("TEMPERATURE", vm.v("temp") + " °C", modifier = Modifier.fillMaxWidth())
    Tile("TCS3200", "R ${vm.v("r")}  G ${vm.v("g")}  B ${vm.v("b")}  C ${vm.v("c")}  •  ${vm.v("tcs")}", modifier = Modifier.fillMaxWidth())
    Bar("Chemical tank", vm.v("chem"), "%"); Bar("Water tank", vm.v("water"), "%")
    Row2({ Tile("SYSTEM VOLTAGE", vm.v("volt") + " V", modifier = Modifier.weight(1f)) },
        { Tile("E-STOP", if (vm.estop) "ACTIVE" else if (vm.espOnline) "SAFE" else "--", if (vm.estop) Red else Green, Modifier.weight(1f)) })
    Title("3-DOF ARM"); Tile("SERVOS", "S1 ${vm.v("s1")}°  S2 ${vm.v("s2")}°  S3 ${vm.v("s3")}°  •  ${vm.v("arm")}", modifier = Modifier.fillMaxWidth())
}

@Composable fun Field(vm: RobotVM, k: String, label: String, d: String) {
    var t by remember { mutableStateOf(vm.get(k, d)) }
    OutlinedTextField(t, { t = it; vm.put(k, it) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (k.startsWith("ep_")) KeyboardType.Uri else KeyboardType.Text))
}

@Composable fun Settings(vm: RobotVM) {
    val scope = rememberCoroutineScope()
    Title("NETWORK"); NET.forEach { Field(vm, it.first, it.second, it.third) }
    Row2({ Btn("TEST ESP32", true, modifier = Modifier.weight(1f)) { scope.launch { vm.call(vm.ep("ep_status")).onSuccess { vm.msg = "ESP32 reachable" }.onFailure { vm.msg = it.message ?: "" } } } },
        { Btn("TEST CAMERA", true, modifier = Modifier.weight(1f)) { scope.launch { vm.msg = if (testUrl(vm.captureUrl())) "Camera capture OK" else "Camera capture failed" } } })
    Title("AUTO / SAFETY (sent to ESP32, which enforces timeouts)"); AUTO_CFG.forEach { Field(vm, it.first, it.second, it.third) }
    Btn("SAVE & SEND TO ROBOT", vm.espOnline, Modifier.fillMaxWidth()) { vm.pushConfig() }
    Title("ENDPOINTS"); EP.forEach { Field(vm, it.first, it.second, it.third) }
    Title("SYSTEM"); Text("App 1.0  •  ESP32 FW ${vm.v("fw")}  •  Camera FW ${vm.v("cam_fw")}", color = Color.LightGray)
    Title("EVENT LOG"); vm.log.take(100).forEach { Text(it, fontSize = 12.sp, color = Color.LightGray) }
}
suspend fun testUrl(u: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching { okhttp3.OkHttpClient.Builder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
        .newCall(okhttp3.Request.Builder().url(u).build()).execute().use { it.isSuccessful } }.getOrDefault(false) }
