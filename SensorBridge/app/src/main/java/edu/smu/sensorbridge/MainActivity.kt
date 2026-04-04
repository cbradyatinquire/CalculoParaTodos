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
import androidx.compose.foundation.Canvas
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

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun csvEscape(s: String): String {
    val needs = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
    if (!needs) return s
    return "\"" + s.replace("\"", "\"\"") + "\""
}

private fun buildCsv(seriesMap: Map<String, List<Sample>>): String {
    val sb = StringBuilder()
    sb.append("var,tMs,y\n")
    // Flatten all variables; you can change this to export only selectedVar if desired.
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
    val file = File(exportsDir, "sensorbridge_$ts.csv")
    file.writeText(csvText, Charsets.UTF_8)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "SensorBridge data ($ts)")
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
            MaterialTheme {
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

    // --- Streaming storage: per-variable time series ---
    val seriesMap = remember { mutableMapOf<String, MutableList<Sample>>() }
    var seriesVersion by remember { mutableStateOf(0) } // triggers UI updates

    var selectedVar by remember { mutableStateOf<String?>(null) }

    var showLog by remember { mutableStateOf(false) }
    var frozenNowMs by remember { mutableStateOf<Long?>(null) }

    val maxSamplesPerVar = 20_000

    var t0Ms by remember { mutableStateOf<Long?>(null) }
    var t0AbsMs by remember { mutableStateOf<Long?>(null) }      // absolute baseline
    var frozenNowRelMs by remember { mutableStateOf<Long?>(null) } // relative freeze

    fun nowRelMs(): Long {
        val abs = SystemClock.elapsedRealtime()
        val t0 = t0AbsMs ?: abs
        return abs - t0
    }

    fun clearData() {
        seriesMap.clear()
        selectedVar = null
        seriesVersion++          // force UI refresh
        t0AbsMs = null           // or t0Ms if that’s your baseline name
        frozenNowRelMs = null    // or frozenNowMs if you renamed it
        // optional: log = listOf("Cleared data")  (or addLog("Cleared data"))
    }

    fun addSample(varName: String, y: Float) {
        val nowMs = SystemClock.elapsedRealtime()
        // when you add the first sample, set baseline once
        if (t0AbsMs == null) t0AbsMs = SystemClock.elapsedRealtime() //new
        //if (t0Ms == null) t0Ms = nowMs  //new
        val relMs = nowMs - (t0AbsMs ?: nowMs) //new
        val s = Sample(relMs, y)   // now Sample.tMs is “ms since start of connection”

        val list = seriesMap.getOrPut(varName) { mutableListOf() }
        list.add(s)
        if (list.size > maxSamplesPerVar) {
            // drop oldest in chunks to reduce churn
            val drop = minOf(200, list.size - maxSamplesPerVar)
            list.subList(0, drop).clear()
        }

        if (selectedVar == null) selectedVar = varName
        seriesVersion++
    }

    // --- Plot window controls ---
    // We specify time range as "seconds ago" to avoid confusing absolute timestamps.
    var followLive by remember { mutableStateOf(true) }
    var tMinAgoSecText by remember { mutableStateOf("10") } // e.g., 10 seconds ago
    var tMaxAgoSecText by remember { mutableStateOf("0") }  // e.g., now

    var autoY by remember { mutableStateOf(true) }
    var yMinText by remember { mutableStateOf("") }
    var yMaxText by remember { mutableStateOf("") }

    fun computeWindow(nowMs: Long): Pair<Long, Long>? {
        val tMinAgo = tMinAgoSecText.toFloatOrNull() ?: return null
        val tMaxAgo = tMaxAgoSecText.toFloatOrNull() ?: return null
        // ensure tMinAgo >= tMaxAgo (older to newer)
        val older = maxOf(tMinAgo, tMaxAgo)
        val newer = minOf(tMinAgo, tMaxAgo)
        val tMinMs = nowMs - (older * 1000f).toLong()
        val tMaxMs = nowMs - (newer * 1000f).toLong()
        return if (tMaxMs > tMinMs) tMinMs to tMaxMs else null
    }

    // --- Log ---
    var log by remember { mutableStateOf(listOf<String>()) }
    fun addLog(s: String) {
        log = (listOf(s) + log).take(80)
    }

    // --- Permission receiver (registered once) ---
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

    // --- RX line handler (supports your var;type;value plus fallbacks) ---
    fun handleRxLine(lineRaw: String) {
        val line = lineRaw.trim()
        if (line.isEmpty()) return

        // Format A: name;type;value  (e.g., distance;n;123.4)
        if (line.contains(';')) {
            val parts = line.split(';')
            if (parts.size >= 3) {
                val name = parts[0].trim()
                val type = parts[1].trim().lowercase()
                val valueStr = parts[2].trim()

                //adding - hoping to remove garbage variable names
                fun isCleanVarName(name: String) =
                    name.isNotBlank() && name.all { it.code in 32..126 } // printable ASCII
                // or stricter: it.isLetterOrDigit() || it=='_' || it=='-'
                if (!isCleanVarName(name)) return

                if (type.startsWith("n")) {
                    val y = valueStr.toFloatOrNull()
                    if (y != null && name.isNotEmpty()) {
                        addSample(name, y)
                        return
                    }
                }

                // For now: ignore non-numeric types, but log once in a while if you want.
                return
            }
        }

        // Format B: S,<value> or S,<ms>,<value>
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

        // Format C: raw numeric line
        val yRaw = line.toFloatOrNull()
        if (yRaw != null) {
            addSample("value", yRaw)
            return
        }
    }

    // --- UI layout: top controls fixed, rest scrolls ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(stringResource(R.string.sensorbridge), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.status, status))

        // USB controls
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                devices = usbHelper.listDevices()
                status = context.getString(R.string.found_usb_device_s, devices.size)
            }) { Text(stringResource(R.string.refresh_usb)) }

            Button(
                enabled = devices.isNotEmpty(),
                onClick = {
                    val dev = devices.first()
                    if (usbHelper.hasPermission(dev)) {
                        status =
                            context.getString(R.string.already_have_permission_for, deviceName(dev))
                    } else {
                        status =
                            context.getString(R.string.requesting_permission_for, deviceName(dev))
                        usbHelper.requestPermission(dev)
                    }
                    addLog(status)
                }
            ) { Text(stringResource(R.string.permission)) }

            Button(
                enabled = seriesMap.isNotEmpty(),
                onClick = { clearData() }
            ) { Text("Clear Data") }
        }

        // Serial controls
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = devices.isNotEmpty() && usbHelper.hasPermission(devices.first()),
                onClick = {
                    val dev = devices.first()
                    val msg = serialManager.connect(dev, 115200) { rxLine ->
                        // serial callback is background thread -> hop to main
                        mainHandler.post {
                            addLog("RX: $rxLine")
                            if (followLive) {
                                // keep window anchored to "now" by default
                                // (computeWindow uses current elapsedRealtime)
                                // no state change needed here; the plot recomposes on seriesVersion
                            }
                            handleRxLine(rxLine)
                        }
                    }
                    status = msg
                    addLog(msg)
                }
            ) { Text(stringResource(R.string.connect)) }



            Button(onClick = {
                val msg = serialManager.disconnect()
                status = msg
                addLog(msg)
            }) { Text(stringResource(R.string.disconnect)) }

            Button(
                enabled = seriesMap.isNotEmpty(),
                onClick = { shareCsv(context, buildCsv(seriesMap)) }
            ) { Text("Export CSV") }
        }

        Divider()

        // Scrollable content area (devices + log + live + plot)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // Device list
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.no_usb_devices_detected_plug_arduino_via_otg_then_refresh))
                } else {
                    Text(stringResource(R.string.detected_devices), fontWeight = FontWeight.Bold)
                    devices.forEach { dev ->
                        Text(
                            "- ${deviceName(dev)}  VID=0x${dev.vendorId.toString(16)}  PID=0x${dev.productId.toString(16)}  perm=${usbHelper.hasPermission(dev)}"
                        )
                    }
                }
            }


            item {
                // Log
                Text(stringResource(R.string.log), fontWeight = FontWeight.Bold)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = log.firstOrNull() ?: "—",
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    TextButton(onClick = { showLog = !showLog }) {
                        Text(if (showLog) stringResource(R.string.hide) else stringResource(R.string.show))
                    }
                }

                if (showLog) {
                    // show a bounded list so it doesn't dominate
                    log.take(20).forEach { Text(it) }
                }

            }

            item {
                // Live/Plot panel
                val vars = remember(seriesVersion) { seriesMap.keys.sorted() }
                val currentVar = selectedVar
                val currentSeries = remember(seriesVersion, currentVar) {
                    val list = if (currentVar == null) null else seriesMap[currentVar]
                    list?.toList() ?: emptyList()
                }
                val latest = currentSeries.lastOrNull()

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.live_monitor_plot), style = MaterialTheme.typography.titleMedium)

                        // Variable selector (if you have >1 var)
                        if (vars.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Var:", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { expanded = true }) {
                                        Text(currentVar ?: vars.first())
                                    }
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        vars.forEach { v ->
                                            DropdownMenuItem(
                                                text = { Text(v) },
                                                onClick = {
                                                    selectedVar = v
                                                    expanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = if (latest == null)
                                stringResource(R.string.latest)
                            else
                                stringResource(R.string.latest_t_ms_y, latest.tMs, latest.y)
                        )

                        // Time window controls
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = tMinAgoSecText,
                                    onValueChange = { tMinAgoSecText = it },
                                    label = { Text(stringResource(R.string.tmin_s_ago)) },
                                    singleLine = true,
                                    modifier = Modifier.width(140.dp)
                                )
                                OutlinedTextField(
                                    value = tMaxAgoSecText,
                                    onValueChange = { tMaxAgoSecText = it },
                                    label = { Text(stringResource(R.string.tmax_s_ago)) },
                                    singleLine = true,
                                    modifier = Modifier.width(140.dp)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = followLive,
                                    onCheckedChange = { checked ->
                                        followLive = checked
                                        //frozenNowMs = if (checked) null else SystemClock.elapsedRealtime()
                                        frozenNowRelMs = if (checked) null else nowRelMs()
                                    }
                                )
                                Text(stringResource(R.string.follow_live))
                            }
                        }


                        // Y range controls
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = autoY, onCheckedChange = { autoY = it })
                                Text("Auto Y")
                            }
                            OutlinedTextField(
                                value = yMinText,
                                onValueChange = { yMinText = it },
                                label = { Text("yMin") },
                                singleLine = true,
                                enabled = !autoY,
                                modifier = Modifier.width(120.dp)
                            )
                            OutlinedTextField(
                                value = yMaxText,
                                onValueChange = { yMaxText = it },
                                label = { Text("yMax") },
                                singleLine = true,
                                enabled = !autoY,
                                modifier = Modifier.width(120.dp)
                            )
                        }

                        // Plot
                        val nowMs = frozenNowRelMs ?: nowRelMs()
                        val (tMinMs, tMaxMs) = computeWindow(nowMs) ?: (nowMs - 10_000L to nowMs)
                        //val nowMs = frozenNowMs ?: SystemClock.elapsedRealtime()
                        //val (tMinMs, tMaxMs) = computeWindow(nowMs) ?: (nowMs - 10_000L to nowMs)
                        if (currentSeries.isNotEmpty()) {
                            val (plotYMin, plotYMax) =
                                if (autoY) {
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
                        } else {
                            Text(stringResource(R.string.no_samples_yet_stream_numeric_data_to_plot))
                        }
                    }
                }
            }
        }
    }
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

    // Colors tuned for a light background Card
    val traceColor = Color(0xFF202124) // dark gray
    val axisColor  = Color(0xFF5F6368) // medium gray
    val gridColor  = Color(0xFFBDC1C6) // light gray
    val labelColor = Color(0xFF202124)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Leave padding for labels
        val padLeft = 54f
        val padBottom = 28f
        val padTop = 10f
        val padRight = 10f

        val plotW = (w - padLeft - padRight).coerceAtLeast(1f)
        val plotH = (h - padTop - padBottom).coerceAtLeast(1f)

        fun xOf(t: Long): Float =
            padLeft + ((t - tMinMs).toFloat() / spanT.toFloat()) * plotW

        fun yOf(y: Float): Float =
            padTop + (1f - ((y - yMin) / spanY)) * plotH

        // --- Gridlines (fixed count; works fine with variable yMin/yMax) ---
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

        // --- Axes box ---
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

        // --- Data in window ---
        val inWin = samples.asSequence()
            .filter { it.tMs in tMinMs..tMaxMs }
            .toList()

        if (inWin.size >= 2) {
            // Decimate: about 1 point per pixel column
            val maxPts = plotW.toInt().coerceAtLeast(200)
            val step = (inWin.size / maxPts).coerceAtLeast(1)

            val path = Path()
            var first = true
            for (i in inWin.indices step step) {
                val s = inWin[i]
                val x = xOf(s.tMs)
                val y = yOf(s.y)
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
            }
            drawPath(path = path, color = traceColor, style = Stroke(width = 3f))
        }

        // --- Labels (yMin/yMax + time range) ---
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
            }

            fun fmtY(v: Float): String = if (kotlin.math.abs(v) >= 1000f) {
                "%.0f".format(v)
            } else if (kotlin.math.abs(v) >= 10f) {
                "%.2f".format(v)
            } else {
                "%.3f".format(v)
            }

            // Left axis labels
            canvas.nativeCanvas.drawText(fmtY(yMax), 6f, padTop + 22f, paint)
            canvas.nativeCanvas.drawText(fmtY(yMin), 6f, padTop + plotH, paint)

            // Bottom time labels: show seconds span (not absolute time)
            val spanSec = spanT / 1000f
            val leftLabel = "${"%.1f".format(spanSec)}s ago"
            val rightLabel = "0.0s ago"
            canvas.nativeCanvas.drawText(leftLabel, padLeft, h - 6f, paint)
            // right aligned
            val rightWidth = paint.measureText(rightLabel)
            canvas.nativeCanvas.drawText(rightLabel, padLeft + plotW - rightWidth, h - 6f, paint)
        }
    }
}


private fun deviceName(d: UsbDevice): String =
    d.productName ?: d.deviceName
