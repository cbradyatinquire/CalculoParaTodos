package edu.smu.sensorbridge

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class SerialManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    fun connect(device: UsbDevice, baudRate: Int = 115200, onLine: (String) -> Unit): String {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: return "No USB serial driver found for device."

        val connection = usbManager.openDevice(device)
            ?: return "Failed to open USB device (no permission?)."

        val p = driver.ports.firstOrNull()
            ?: return "No serial ports on this device."

        p.open(connection)
        p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        port = p

        val listener = object : SerialInputOutputManager.Listener {
            private val buffer = StringBuilder()

            override fun onNewData(data: ByteArray) {
                val text = data.toString(Charsets.UTF_8)
                buffer.append(text)
                var idx: Int
                while (true) {
                    idx = buffer.indexOf("\n")
                    if (idx < 0) break
                    val line = buffer.substring(0, idx).trimEnd('\r')
                    buffer.delete(0, idx + 1)
                    onLine(line)
                }
            }

            override fun onRunError(e: Exception) {
                onLine("IO error: ${e.message}")
            }
        }

        ioManager = SerialInputOutputManager(p, listener).also {
            executor.submit(it)
        }

        return "Connected @ $baudRate"
    }

    fun sendLine(line: String): String {
        val p = port ?: return "Not connected."
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        p.write(bytes, 1000)
        return "Sent: $line"
    }

    fun disconnect(): String {
        try {
            ioManager?.stop()
        } catch (_: Exception) {}
        ioManager = null

        try {
            port?.close()
        } catch (_: Exception) {}
        port = null

        return "Disconnected"
    }
}
