@file:OptIn(ExperimentalMaterial3Api::class)
package edu.smu.sensorbridge

import androidx.compose.material3.ExperimentalMaterial3Api
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign

import android.content.Intent
import androidx.core.content.FileProvider
import edu.smu.sensorbridge.theme.SensorBridgeTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private fun csvEscape(s: String): String {
    val needs = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
    if (!needs) return s
    return "\"" + s.replace("\"", "\"\"") + "\""
}

private fun buildCsv(seriesMap: Map<String, List<Sample>>): String {
    val sb = StringBuilder()
    sb.append("var,tMs,y\n")
    seriesMap.keys.sorted().forEach { varName ->
        seriesMap[varName].orEmpty().forEach { s ->
            sb.append(csvEscape(varName)).append(',')
            sb.append(s.tMs).append(',')
            sb.append(s.y).append('\n')
        }
    }
    return sb.toString()
}

private fun shareCsv(context: android.content.Context, csvText: String) {
    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val file = File(exportsDir, "arduinodata_$ts.csv")
    file.writeText(csvText, Charsets.UTF_8)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Arduino Sensor data ($ts)")
        putExtra(Intent.EXTRA_TEXT, "CSV export attached.")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share CSV"))
}

data class Sample(val tMs: Long, val y: Float)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SensorBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen()
                }
            }
        }
    }
}

@Composable
fun AppScreen() {
    val context = LocalContext.current
    val usbHelper = remember { UsbHelper(context) }
    val serialManager = remember { SerialManager(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var status by remember { mutableStateOf("Idle") }
    var devices by remember { mutableStateOf<List<UsbDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var isConnected by remember { mutableStateOf(false) }

    val seriesMap = remember { mutableMapOf<String, MutableList<Sample>>() }
    var seriesVersion by remember { mutableStateOf(0) }

    var selectedVar by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val maxSamplesPerVar = 20_000
    var t0AbsMs by remember { mutableStateOf<Long?>(null) }
    var frozenNowRelMs by remember { mutableStateOf<Long?>(null) }

    // Wave 4: Hz tracking
    var deviceCardExpanded by remember { mutableStateOf(false) }
    var rxHz by remember { mutableStateOf(0f) }
    var lastRxElapsedMs by remember { mutableStateOf<Long?>(null) }
    val rxTimestamps = remember { ArrayDeque<Long>() }

    // Wave 4: clock tick for live watchdog display (updates every 500 ms while connected)
    var clockTick by remember { mutableStateOf(0) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            while (true) {
                delay(500L)
                clockTick++
            }
        }
    }

    fun nowRelMs(): Long {
        val abs = SystemClock.elapsedRealtime()
        val t0 = t0AbsMs ?: abs
        return abs - t0
    }

    fun clearData() {
        seriesMap.clear()
        selectedVar = null
        seriesVersion++
        t0AbsMs = null
        frozenNowRelMs = null
        rxTimestamps.clear()
        rxHz = 0f
        lastRxElapsedMs = null
    }

    fun addSample(varName: String, y: Float) {
        val nowMs = SystemClock.elapsedRealtime()
        if (t0AbsMs == null) t0AbsMs = nowMs
        val relMs = nowMs - (t0AbsMs ?: nowMs)
        val s = Sample(relMs, y)

        val list = seriesMap.getOrPut(varName) { mutableListOf() }
        list.add(s)
        if (list.size > maxSamplesPerVar) {
            val drop = minOf(200, list.size - maxSamplesPerVar)
            list.subList(0, drop).clear()
        }
        if (selectedVar == null) selectedVar = varName

        // Track Hz with a rolling 3-second window
        lastRxElapsedMs = nowMs
        rxTimestamps.addLast(nowMs)
        val cutoff = nowMs - 3000L
        while (rxTimestamps.isNotEmpty() && rxTimestamps.first() < cutoff) rxTimestamps.removeFirst()
        rxHz = if (rxTimestamps.size >= 2) {
            val span = rxTimestamps.last() - rxTimestamps.first()
            if (span > 0) (rxTimestamps.size - 1).toFloat() / (span / 1000f) else 0f
        } else 0f

        seriesVersion++
    }

    val baudRates = listOf(9600, 19200, 38400, 57600, 115200)
    var selectedBaudRate by remember { mutableStateOf(115200) }
    var baudMenuExpanded by remember { mutableStateOf(false) }

    var followLive by remember { mutableStateOf(true) }
    var tMinAgoSecText by remember { mutableStateOf("30") }
    var tMaxAgoSecText by remember { mutableStateOf("0") }

    var autoY by remember { mutableStateOf(true) }
    var yMinText by remember { mutableStateOf("") }
    var yMaxText by remember { mutableStateOf("") }

    fun computeWindow(nowMs: Long): Pair<Long, Long>? {
        val tMinAgo = tMinAgoSecText.toFloatOrNull() ?: return null
        val tMaxAgo = tMaxAgoSecText.toFloatOrNull() ?: return null
        val older = maxOf(tMinAgo, tMaxAgo)
        val newer = minOf(tMinAgo, tMaxAgo)
        val tMinMs = nowMs - (older * 1000f).toLong()
        val tMaxMs = nowMs - (newer * 1000f).toLong()
        return if (tMaxMs > tMinMs) tMinMs to tMaxMs else null
    }

    var log by remember { mutableStateOf(listOf<String>()) }
    fun addLog(s: String) {
        log = (listOf(s) + log).take(80)
    }

    DisposableEffect(Unit) {
        onDispose { serialManager.close() }
    }

    DisposableEffect(Unit) {
        val receiver = usbHelper.makePermissionReceiver { device, granted ->
            val msg = if (device == null) {
                context.getString(R.string.permission_result_no_device)
            } else {
                "Permission for ${deviceName(device)}: ${if (granted) "GRANTED" else "DENIED"}"
            }
            status = msg
            addLog(msg)
        }
        val filter = android.content.IntentFilter(UsbHelper.ACTION_USB_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun handleRxLine(lineRaw: String) {
        val line = lineRaw.trim()
        if (line.isEmpty()) return

        if (line.contains(';')) {
            val parts = line.split(';')
            if (parts.size >= 3) {
                val name = parts[0].trim()
                val type = parts[1].trim().lowercase()
                val valueStr = parts[2].trim()

                fun isCleanVarName(name: String) =
                    name.isNotBlank() && name.all { it.code in 32..126 }
                if (!isCleanVarName(name)) return

                if (type.startsWith("n")) {
                    val y = valueStr.toFloatOrNull()
                    if (y != null && name.isNotEmpty()) {
                        addSample(name, y)
                        return
                    }
                }
                return
            }
        }

        if (line.startsWith("S,")) {
            val parts = line.split(',')
            val y = when (parts.size) {
                2 -> parts[1].toFloatOrNull()
                3 -> parts[2].toFloatOrNull()
                else -> null
            }
            if (y != null) addSample("S", y)
            return
        }

        val yRaw = line.toFloatOrNull()
        if (yRaw != null) {
            addSample("value", yRaw)
            return
        }
    }

    // ── Root layout ──────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        // ── Title row + connection badge ──────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.sensorbridge),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            // Connection badge
            val badgeBg = if (isConnected) Color(0xFFD4EDDA) else MaterialTheme.colorScheme.surfaceVariant
            val badgeFg = if (isConnected) Color(0xFF1A6630) else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(shape = MaterialTheme.shapes.large, color = badgeBg) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(color = badgeFg) }
                    Text(
                        text = if (isConnected) "Connected" else "Disconnected",
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeFg,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = WindowInsets.navigationBars.asPaddingValues()
        ) {

            // ── USB Device Card (collapsible) ─────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    // Header row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deviceCardExpanded = !deviceCardExpanded }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "USB DEVICE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        if (!deviceCardExpanded && selectedDevice != null) {
                            Text(
                                deviceName(selectedDevice!!),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            if (usbHelper.hasPermission(selectedDevice!!)) {
                                Text("✓", color = Color(0xFF1A6630), fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            if (deviceCardExpanded) "▲" else "▼",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Collapsible body
                    AnimatedVisibility(visible = deviceCardExpanded) {
                        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                            Divider()
                            Spacer(Modifier.height(8.dp))

                            if (devices.isEmpty()) {
                                Text(
                                    stringResource(R.string.no_usb_devices_detected_plug_arduino_via_otg_then_refresh),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                devices.forEach { dev ->
                                    val isSelected = dev.deviceId == selectedDevice?.deviceId
                                    val hasPerm = usbHelper.hasPermission(dev)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !isConnected) { selectedDevice = dev }
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                else Color.Transparent,
                                                shape = MaterialTheme.shapes.small
                                            )
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (isSelected) "▶" else "  ",
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                deviceName(dev),
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                "VID:0x${dev.vendorId.toString(16).uppercase()}  PID:0x${dev.productId.toString(16).uppercase()}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = if (hasPerm) "✓ Permission" else "No permission",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (hasPerm) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))

                            // Baud rate picker
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(stringResource(R.string.baud_rate))
                                Box {
                                    OutlinedButton(
                                        onClick = { if (!isConnected) baudMenuExpanded = true },
                                        enabled = !isConnected
                                    ) { Text("$selectedBaudRate") }
                                    DropdownMenu(
                                        expanded = baudMenuExpanded,
                                        onDismissRequest = { baudMenuExpanded = false }
                                    ) {
                                        baudRates.forEach { rate ->
                                            DropdownMenuItem(
                                                text = { Text("$rate") },
                                                onClick = {
                                                    selectedBaudRate = rate
                                                    baudMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                if (isConnected) {
                                    Text(
                                        stringResource(R.string.baud_locked),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Connection Card ───────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "CONNECTION",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Connect / Disconnect / Refresh
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = selectedDevice != null
                                        && selectedDevice?.let { usbHelper.hasPermission(it) } == true
                                        && !isConnected,
                                onClick = {
                                    val dev = selectedDevice ?: return@Button
                                    followLive = true
                                    frozenNowRelMs = null
                                    val msg = serialManager.connect(
                                        dev, selectedBaudRate,
                                        onLine = { rxLine ->
                                            mainHandler.post {
                                                addLog("RX: $rxLine")
                                                handleRxLine(rxLine)
                                            }
                                        },
                                        onError = { errMsg ->
                                            mainHandler.post {
                                                isConnected = false
                                                status = errMsg
                                                addLog("ERR: $errMsg")
                                                frozenNowRelMs = nowRelMs()
                                            }
                                        }
                                    )
                                    isConnected = msg.startsWith("Connected")
                                    status = msg
                                    addLog(msg)
                                }
                            ) { Text(stringResource(R.string.connect)) }

                            Button(
                                enabled = isConnected,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                onClick = {
                                    val msg = serialManager.disconnect()
                                    isConnected = false
                                    status = msg
                                    addLog(msg)
                                    followLive = false
                                    frozenNowRelMs = nowRelMs()
                                }
                            ) { Text(stringResource(R.string.disconnect)) }

                            OutlinedButton(onClick = {
                                val found = usbHelper.listDevices()
                                devices = found
                                selectedDevice = found.firstOrNull { it.deviceId == selectedDevice?.deviceId }
                                    ?: found.singleOrNull()
                                status = context.getString(R.string.found_usb_device_s, found.size)
                            }) { Text(stringResource(R.string.refresh_usb)) }
                        }

                        // Permission / Export / Clear
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = selectedDevice != null,
                                onClick = {
                                    val dev = selectedDevice ?: return@OutlinedButton
                                    if (usbHelper.hasPermission(dev)) {
                                        status = context.getString(R.string.already_have_permission_for, deviceName(dev))
                                    } else {
                                        status = context.getString(R.string.requesting_permission_for, deviceName(dev))
                                        usbHelper.requestPermission(dev)
                                    }
                                    addLog(status)
                                }
                            ) { Text(stringResource(R.string.permission)) }

                            OutlinedButton(
                                enabled = seriesMap.isNotEmpty(),
                                onClick = { shareCsv(context, buildCsv(seriesMap)) }
                            ) { Text(stringResource(R.string.export)) }

                            OutlinedButton(
                                enabled = seriesMap.isNotEmpty(),
                                onClick = { showClearDialog = true }
                            ) { Text(stringResource(R.string.clear)) }
                        }

                        if (showClearDialog) {
                            AlertDialog(
                                onDismissRequest = { showClearDialog = false },
                                title = { Text(stringResource(R.string.eraseall)) },
                                text = { Text(stringResource(R.string.areyousureerase)) },
                                confirmButton = {
                                    TextButton(onClick = { clearData(); showClearDialog = false }) {
                                        Text(stringResource(R.string.yesconfirm))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showClearDialog = false }) {
                                        Text(stringResource(R.string.cancelconfirm))
                                    }
                                }
                            )
                        }

                        // Streaming strip (Wave 4: watchdog + Hz)
                        if (isConnected || lastRxElapsedMs != null) {
                            val nowElapsed = remember(clockTick) { SystemClock.elapsedRealtime() }
                            val agoSec = lastRxElapsedMs?.let { (nowElapsed - it) / 1000f } ?: 0f
                            val isStale = agoSec > 2f
                            val stripBg = if (isStale)
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            val dotColor = if (isStale) MaterialTheme.colorScheme.error
                                          else Color(0xFF1A6630)

                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = stripBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Canvas(Modifier.size(8.dp)) { drawCircle(color = dotColor) }
                                    Text(
                                        text = if (isStale) "Stalled" else "Streaming",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "· last packet ${"%.1f".format(agoSec)} s ago",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.weight(1f))
                                    if (rxHz > 0f) {
                                        Text(
                                            "~${"%.1f".format(rxHz)} Hz",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Live Monitor Card ─────────────────────────────────────────
            item {
                val vars = remember(seriesVersion) { seriesMap.keys.sorted() }
                val currentVar = selectedVar
                val currentSeries = remember(seriesVersion, currentVar) {
                    val list = if (currentVar == null) null else seriesMap[currentVar]
                    list?.toList() ?: emptyList()
                }
                val latest = currentSeries.lastOrNull()
                val totalSamples = remember(seriesVersion) { seriesMap.values.sumOf { it.size } }

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "LIVE MONITOR",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Variable selector
                        if (vars.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Variable:", fontWeight = FontWeight.Medium)
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { expanded = true }) {
                                        Text(currentVar ?: vars.first())
                                    }
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false }
                                    ) {
                                        vars.forEach { v ->
                                            DropdownMenuItem(
                                                text = { Text(v) },
                                                onClick = { selectedVar = v; expanded = false }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Large latest value
                        if (latest != null) {
                            Text(
                                text = formatValue(latest.y),
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            // Hz · samples · age meta row
                            val nowElapsed = remember(clockTick, seriesVersion) { SystemClock.elapsedRealtime() }
                            val agoSec = lastRxElapsedMs?.let { (nowElapsed - it) / 1000f } ?: 0f
                            Text(
                                text = buildString {
                                    if (rxHz > 0f) append("~${"%.1f".format(rxHz)} Hz · ")
                                    append("%,d".format(totalSamples))
                                    append(" samples")
                                    if (lastRxElapsedMs != null) append(" · ${"%.1f".format(agoSec)} s ago")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                stringResource(R.string.no_samples_yet_stream_numeric_data_to_plot),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Plot
                        if (currentSeries.isNotEmpty()) {
                            val nowMs = frozenNowRelMs ?: nowRelMs()
                            val (tMinMs, tMaxMs) = computeWindow(nowMs) ?: (nowMs - 30_000L to nowMs)
                            val (plotYMin, plotYMax) = if (autoY) {
                                val inWin = currentSeries.filter { it.tMs in tMinMs..tMaxMs }
                                val ymin = inWin.minOfOrNull { it.y } ?: 0f
                                val ymax = inWin.maxOfOrNull { it.y } ?: (ymin + 1f)
                                val pad = ((ymax - ymin).takeIf { it > 1e-6f } ?: 1f) * 0.1f
                                (ymin - pad) to (ymax + pad)
                            } else {
                                val ymin = yMinText.toFloatOrNull() ?: 0f
                                val ymax = yMaxText.toFloatOrNull() ?: (ymin + 1f)
                                if (ymax > ymin) ymin to ymax else ymin to (ymin + 1f)
                            }

                            val samplesInWindow = remember(seriesVersion, tMinMs, tMaxMs) {
                                currentSeries.count { it.tMs in tMinMs..tMaxMs }
                            }

                            // Plot with PAUSED overlay
                            Box {
                                TimeSeriesPlot(
                                    samples = currentSeries,
                                    tMinMs = tMinMs,
                                    tMaxMs = tMaxMs,
                                    yMin = plotYMin,
                                    yMax = plotYMax,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                )
                                if (!followLive) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            "⏸ PAUSED",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }

                            // "Show last X s to Y s ago"
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("Show last", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = tMinAgoSecText,
                                    onValueChange = { tMinAgoSecText = it },
                                    singleLine = true,
                                    modifier = Modifier.width(68.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center)
                                )
                                Text("s to", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = tMaxAgoSecText,
                                    onValueChange = { tMaxAgoSecText = it },
                                    singleLine = true,
                                    modifier = Modifier.width(68.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center)
                                )
                                Text("s ago", style = MaterialTheme.typography.bodySmall)
                            }

                            // Follow live + Auto Y
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = followLive,
                                        onCheckedChange = { checked ->
                                            followLive = checked
                                            frozenNowRelMs = if (checked) null else nowRelMs()
                                        }
                                    )
                                    Text(stringResource(R.string.follow_live))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = autoY, onCheckedChange = { autoY = it })
                                    Text("Auto Y")
                                }
                            }

                            // Manual Y range (when Auto Y is off)
                            if (!autoY) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = yMinText,
                                        onValueChange = { yMinText = it },
                                        label = { Text("yMin") },
                                        singleLine = true,
                                        modifier = Modifier.width(120.dp)
                                    )
                                    OutlinedTextField(
                                        value = yMaxText,
                                        onValueChange = { yMaxText = it },
                                        label = { Text("yMax") },
                                        singleLine = true,
                                        modifier = Modifier.width(120.dp)
                                    )
                                }
                            }

                            // "Showing X of Y samples in window"
                            Text(
                                "Showing %,d of %,d samples in window".format(samplesInWindow, totalSamples),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }

            // ── Log ───────────────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.log),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { showLog = !showLog }) {
                                Text(if (showLog) stringResource(R.string.hide) else stringResource(R.string.show))
                            }
                        }
                        Text(
                            text = log.firstOrNull() ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (showLog) {
                            Spacer(Modifier.height(4.dp))
                            log.take(20).forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Format a sensor value for large display
private fun formatValue(y: Float): String = when {
    kotlin.math.abs(y) >= 10_000f -> "%.0f".format(y)
    kotlin.math.abs(y) >= 100f    -> "%.1f".format(y)
    kotlin.math.abs(y) >= 1f      -> "%.2f".format(y)
    else                           -> "%.3f".format(y)
}

@Composable
fun TimeSeriesPlot(
    samples: List<Sample>,
    tMinMs: Long,
    tMaxMs: Long,
    yMin: Float,
    yMax: Float,
    modifier: Modifier = Modifier
) {
    val spanT = (tMaxMs - tMinMs).coerceAtLeast(1L)
    val spanY = (yMax - yMin).takeIf { it > 1e-9f } ?: 1f

    val traceColor = Color(0xFF202124)
    val axisColor  = Color(0xFF5F6368)
    val gridColor  = Color(0xFFBDC1C6)
    val labelColor = Color(0xFF202124)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val padLeft   = 54f
        val padBottom = 28f
        val padTop    = 10f
        val padRight  = 10f

        val plotW = (w - padLeft - padRight).coerceAtLeast(1f)
        val plotH = (h - padTop - padBottom).coerceAtLeast(1f)

        fun xOf(t: Long): Float =
            padLeft + ((t - tMinMs).toFloat() / spanT.toFloat()) * plotW

        fun yOf(y: Float): Float =
            padTop + (1f - ((y - yMin) / spanY)) * plotH

        // Gridlines
        val yDivs = 4
        for (i in 0..yDivs) {
            val yy = padTop + (i.toFloat() / yDivs) * plotH
            drawLine(
                color = gridColor.copy(alpha = 0.6f),
                start = androidx.compose.ui.geometry.Offset(padLeft, yy),
                end = androidx.compose.ui.geometry.Offset(padLeft + plotW, yy),
                strokeWidth = 1f
            )
        }
        val xDivs = 4
        for (i in 0..xDivs) {
            val xx = padLeft + (i.toFloat() / xDivs) * plotW
            drawLine(
                color = gridColor.copy(alpha = 0.35f),
                start = androidx.compose.ui.geometry.Offset(xx, padTop),
                end = androidx.compose.ui.geometry.Offset(xx, padTop + plotH),
                strokeWidth = 1f
            )
        }

        // Axes
        drawLine(
            color = axisColor,
            start = androidx.compose.ui.geometry.Offset(padLeft, padTop),
            end = androidx.compose.ui.geometry.Offset(padLeft, padTop + plotH),
            strokeWidth = 2f
        )
        drawLine(
            color = axisColor,
            start = androidx.compose.ui.geometry.Offset(padLeft, padTop + plotH),
            end = androidx.compose.ui.geometry.Offset(padLeft + plotW, padTop + plotH),
            strokeWidth = 2f
        )

        // Data trace
        val inWin = samples.asSequence()
            .filter { it.tMs in tMinMs..tMaxMs }
            .toList()

        if (inWin.size >= 2) {
            val maxPts = plotW.toInt().coerceAtLeast(200)
            val step = (inWin.size / maxPts).coerceAtLeast(1)

            val path = Path()
            var first = true
            for (i in inWin.indices step step) {
                val s = inWin[i]
                val x = xOf(s.tMs)
                val y = yOf(s.y)
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            drawPath(path = path, color = traceColor, style = Stroke(width = 3f))
        }

        // Labels
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(
                    255,
                    (labelColor.red * 255).toInt(),
                    (labelColor.green * 255).toInt(),
                    (labelColor.blue * 255).toInt()
                )
                textSize = 26f
                // Wave 4: right-align labels flush to the axis line
                textAlign = android.graphics.Paint.Align.RIGHT
            }

            fun fmtY(v: Float): String = when {
                kotlin.math.abs(v) >= 1000f -> "%.0f".format(v)
                kotlin.math.abs(v) >= 10f   -> "%.1f".format(v)
                else                         -> "%.2f".format(v)
            }

            // Y labels at each gridline (top, mid, bottom + intermediate)
            for (i in 0..yDivs) {
                val ratio = i.toFloat() / yDivs
                val yVal  = yMax - ratio * (yMax - yMin)
                val yy    = padTop + ratio * plotH
                // vertically center text on the gridline
                val baseline = yy + paint.textSize / 3f
                canvas.nativeCanvas.drawText(fmtY(yVal), padLeft - 4f, baseline, paint)
            }

            // X labels: left, right (and mid)
            paint.textAlign = android.graphics.Paint.Align.LEFT
            val spanSec = spanT / 1000f
            val leftLabel  = "${"%.1f".format(spanSec)}s ago"
            val midLabel   = "${"%.1f".format(spanSec / 2)}s ago"
            val rightLabel = "0s ago"

            paint.textAlign = android.graphics.Paint.Align.LEFT
            canvas.nativeCanvas.drawText(leftLabel, padLeft, h - 4f, paint)
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.nativeCanvas.drawText(midLabel, padLeft + plotW / 2f, h - 4f, paint)
            paint.textAlign = android.graphics.Paint.Align.RIGHT
            canvas.nativeCanvas.drawText(rightLabel, padLeft + plotW, h - 4f, paint)
        }
    }
}

private fun deviceName(d: UsbDevice): String =
    d.productName ?: d.deviceName
