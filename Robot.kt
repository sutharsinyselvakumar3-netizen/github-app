package com.aicompanion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/** Setting key, label, default. Endpoint paths are configurable so they can match your firmware. No IPs are hard-coded. */
val NET = listOf(
    Triple("esp_ip", "ESP32 IP (192.168.x.x)", ""), Triple("esp_port", "ESP32 HTTP port", "80"),
    Triple("cam_ip", "ESP32-CAM IP (192.168.x.x)", ""), Triple("cam_port", "Camera stream port", "81"),
    Triple("conn_to", "Connection timeout (ms)", "3000"), Triple("req_to", "Request timeout (ms)", "5000"))
val EP = listOf(
    Triple("ep_status", "Status endpoint", "/status"), Triple("ep_preflight", "Pre-start check endpoint", "/preflight"),
    Triple("ep_mode", "Mode endpoint", "/mode"), Triple("ep_move", "Move endpoint", "/move"),
    Triple("ep_speed", "Speed endpoint", "/speed"), Triple("ep_servo", "Servo endpoint", "/servo"),
    Triple("ep_relay", "Relay endpoint", "/relay"), Triple("ep_estop", "E-stop endpoint", "/estop"),
    Triple("ep_config", "Config endpoint", "/config"), Triple("ep_stream", "Stream path", "/stream"),
    Triple("ep_capture", "Capture path", "/capture"))
val AUTO_CFG = listOf(
    Triple("weed_conf", "Weed confidence threshold %", "70"), Triple("disease_thr", "Disease threshold", ""),
    Triple("obstacle_cm", "Obstacle safety distance cm", "20"), Triple("soil_dry", "Soil dry threshold %", "35"),
    Triple("chem_min", "Chemical minimum %", "20"), Triple("water_min", "Water minimum %", "20"),
    Triple("soil_min", "Soil test interval (min)", "5"), Triple("cut_s", "Cutter duration (s, max 7)", "7"),
    Triple("spray_s", "Spray duration (s, max 10)", "10"), Triple("pump_s", "Water pump duration (s, max 5)", "5"),
    Triple("pan_min", "Pan min deg", "0"), Triple("pan_max", "Pan max deg", "180"),
    Triple("tilt_min", "Tilt min deg", "0"), Triple("tilt_max", "Tilt max deg", "180"))

class RobotVM(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ai_companion", 0)
    private val logFile = File(app.filesDir, "events.log")
    var st by mutableStateOf<JSONObject?>(null); private set
    var espOnline by mutableStateOf(false); private set
    var camOnline by mutableStateOf(false)
    var msg by mutableStateOf("")
    var booted by mutableStateOf(false)
    val log = mutableStateListOf<String>()

    fun get(k: String, d: String = "") = prefs.getString(k, d) ?: d
    fun put(k: String, v: String) = prefs.edit().putString(k, v).apply()
    fun ep(k: String) = get(k, (EP.firstOrNull { it.first == k }?.third ?: ""))
    fun v(k: String): String = st?.opt(k)?.takeIf { it != JSONObject.NULL }?.toString() ?: "--"
    val mode get() = v("mode"); val estop get() = v("estop") == "ACTIVE"
    val manualOk get() = espOnline && mode != "AUTO" && !estop
    fun camUrl(path: String) = "http://${get("cam_ip")}${if (path.endsWith(get("ep_stream", "/stream"))) ":" + get("cam_port", "81") else ""}$path"
    fun streamUrl() = camUrl(ep("ep_stream")); fun captureUrl() = camUrl(ep("ep_capture"))

    fun event(text: String, sensor: String = "", result: String = "") {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} — $mode — $text" +
            (if (sensor.isNotEmpty()) " — $sensor" else "") + (if (result.isNotEmpty()) " — $result" else "")
        log.add(0, line); runCatching { logFile.appendText(line + "\n") }
    }

    private fun client(ms: String) = OkHttpClient.Builder()
        .connectTimeout(get("conn_to", "3000").toLongOrNull() ?: 3000, TimeUnit.MILLISECONDS)
        .callTimeout(get(ms, "5000").toLongOrNull() ?: 5000, TimeUnit.MILLISECONDS).build()

    /** Real HTTP call to the ESP32. Returns parsed JSON or a readable error. Never fabricates data. */
    suspend fun call(path: String, params: Map<String, String> = emptyMap()): Result<JSONObject> = withContext(Dispatchers.IO) {
        if (get("esp_ip").isBlank()) return@withContext Result.failure(Exception("Set the ESP32 IP address in Settings."))
        try {
            val url = okhttp3.HttpUrl.Builder().scheme("http").host(get("esp_ip")).port(get("esp_port", "80").toIntOrNull() ?: 80)
                .encodedPath(path).apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            client("req_to").newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when (r.code) {
                    200 -> Result.success(runCatching { JSONObject(body) }.getOrDefault(JSONObject().put("ok", true)))
                    400 -> Result.failure(Exception("Robot rejected the command (bad request)."))
                    404 -> Result.failure(Exception("Endpoint not found. Check endpoint names in Settings."))
                    408 -> Result.failure(Exception("Robot timed out. Check Wi-Fi connection."))
                    in 500..599 -> Result.failure(Exception("Robot reported an internal error."))
                    else -> Result.failure(Exception("Unexpected robot response (${r.code})."))
                }
            }
        } catch (e: SocketTimeoutException) { Result.failure(Exception("Robot timed out. Check Wi-Fi connection.")) }
        catch (e: IOException) { Result.failure(Exception("Robot command failed. Check ESP32 IP and Wi-Fi connection.")) }
        catch (e: IllegalArgumentException) { Result.failure(Exception("Invalid ESP32 address in Settings.")) }
    }

    fun cmd(ep: String, params: Map<String, String>, ok: String, onOk: () -> Unit = {}) = viewModelScope.launch {
        call(ep(ep), params).onSuccess { msg = ok; event(ok, result = "OK"); onOk() }
            .onFailure { msg = it.message ?: "Command failed"; event(ok, result = "FAILED") }
    }
    fun estopNow() = viewModelScope.launch {
        // Fire repeatedly until acknowledged; ESP32 performs the actual shutdown.
        repeat(3) { if (call(ep("ep_estop"), mapOf("state" to "on")).isSuccess) { msg = "EMERGENCY STOP ACTIVE"; event("EMERGENCY STOP", result = "OK"); return@launch } }
        msg = "E-STOP not acknowledged! Use the physical switch."; event("EMERGENCY STOP", result = "NOT ACKNOWLEDGED")
    }
    fun clearEstop() = cmd("ep_estop", mapOf("state" to "off"), "Emergency stop cleared")
    fun pushConfig() = viewModelScope.launch {
        val p = (AUTO_CFG + listOf(Triple("dummy", "", ""))).filter { it.first != "dummy" && get(it.first, it.third).isNotBlank() }.associate { it.first to get(it.first, it.third) }
        call(ep("ep_config"), p).onSuccess { msg = "Settings sent to robot" }.onFailure { msg = it.message ?: "" }
    }

    /** AUTO start: app checks camera, ESP32 reports its own readiness (hardware checks). Blocks on any failure. */
    fun startAuto(onBlocked: (String) -> Unit) = viewModelScope.launch {
        if (!camOnline) return@launch onBlocked("Camera stream not available")
        val r = call(ep("ep_preflight"))
        val j = r.getOrElse { return@launch onBlocked(it.message ?: "ESP32 not reachable") }
        val bad = j.keys().asSequence().filter { j.opt(it) == false }.toList()
        if (bad.isNotEmpty()) return@launch onBlocked(bad.joinToString { it.replace('_', ' ') })
        call(ep("ep_mode"), mapOf("mode" to "auto")).onSuccess { msg = "Automatic operation started"; event("AUTO start", result = "OK") }
            .onFailure { onBlocked(it.message ?: "") }
    }
    fun stopAuto() = cmd("ep_mode", mapOf("mode" to "manual"), "Automatic operation stopped")

    init {
        viewModelScope.launch {
            while (true) {
                val r = call(ep("ep_status"))
                st = r.getOrNull(); espOnline = r.isSuccess
                if (!booted) booted = true
                delay(1000)
            }
        }
        log.addAll(runCatching { logFile.readLines().takeLast(200).reversed() }.getOrDefault(emptyList()))
    }
}

/** Reads a real MJPEG stream and emits decoded frames. Throws on failure so UI shows STREAM NOT AVAILABLE. */
suspend fun mjpeg(url: String, onFrame: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
    val c = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    c.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        val s = r.body!!.byteStream().buffered(); val buf = ByteArrayOutputStream()
        var prev = -1; var inJpg = false
        while (isActive) {
            val b = s.read(); if (b < 0) throw IOException("closed")
            if (!inJpg) { if (prev == 0xFF && b == 0xD8) { inJpg = true; buf.reset(); buf.write(0xFF); buf.write(0xD8) } }
            else { buf.write(b); if (prev == 0xFF && b == 0xD9) {
                val a = buf.toByteArray(); BitmapFactory.decodeByteArray(a, 0, a.size)?.let(onFrame); inJpg = false } }
            prev = b
        }
    }
}
