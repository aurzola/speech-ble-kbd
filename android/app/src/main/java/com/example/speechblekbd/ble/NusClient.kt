package com.example.speechblekbd.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

/**
 * Minimal BLE central for the Nordic UART Service (NUS).
 *
 * Scans for the NUS service, connects, enables TX notifications and writes
 * newline-terminated commands to RX.
 */
class NusClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(text: String)
        fun onConnected()
        fun onDisconnected()
        fun onData(text: String)
    }

    companion object {
        private const val TAG = "NusClient"

        val NUS_SERVICE: ParcelUuid = ParcelUuid.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: java.util.UUID = java.util.UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX: java.util.UUID = java.util.UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD: java.util.UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    private var scanning = false
    private var connecting = false

    private var usableMtu = 20
    private var writeInProgress = false
    private val writeQueue = ArrayDeque<ByteArray>()

    private val scanTimeout = Runnable {
        if (scanning) {
            listener.onStatus("No se encontró el dispositivo. Reintenta «Conectar».")
            stopScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (connecting || gatt != null) return
            val device = result.device
            val name = runCatching { device.name }.getOrNull()
            Log.i(TAG, "found ${device.address} name=$name")
            listener.onStatus("Encontrado: ${name ?: device.address}, conectando…")
            connecting = true
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            listener.onStatus("Escaneo falló (código $errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "stateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        listener.onStatus("Conexión falló (status $status)")
                        return
                    }
                    listener.onStatus("Conectado. Descubriendo servicios…")
                    val started = g.discoverServices()
                    Log.i(TAG, "discoverServices returned $started")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "disconnected with status=$status")
                    }
                    listener.onDisconnected()
                    gatt = null
                    rxChar = null
                    txChar = null
                    writeQueue.clear()
                    writeInProgress = false
                    scanning = false
                    connecting = false
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            usableMtu = (mtu - 3).coerceAtLeast(20)
            Log.i(TAG, "MTU=$mtu usable=$usableMtu")
            val tx = txChar
            if (tx == null) {
                listener.onConnected()
                return
            }
            enableNotifications(g, tx)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.i(TAG, "descriptorWrite status=$status")
            listener.onConnected()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "servicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("Descubrimiento de servicios falló (status $status)")
                return
            }
            val service = g.getService(NUS_SERVICE.uuid)
            if (service == null) {
                listener.onStatus("NUS no encontrado en el dispositivo")
                return
            }
            rxChar = service.getCharacteristic(NUS_RX)
            txChar = service.getCharacteristic(NUS_TX)

            if (rxChar == null) {
                listener.onStatus("Característica RX ausente")
                return
            }
            // Run one GATT op at a time: MTU -> enable notifications -> onConnected.
            g.requestMtu(247)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.i(TAG, "writeDone status=$status")
            writeInProgress = false
            drainQueue()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            listener.onData(String(value, Charsets.UTF_8))
        }

        @Deprecated("Used on API < 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            listener.onData(String(value, Charsets.UTF_8))
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning) {
            listener.onStatus("Ya buscando…")
            return
        }
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            listener.onStatus("BLE no disponible")
            return
        }
        if (!bluetoothManager.adapter.isEnabled) {
            listener.onStatus("Activa el Bluetooth del teléfono")
            return
        }

        val filter = ScanFilter.Builder().setServiceUuid(NUS_SERVICE).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        listener.onStatus("Buscando dispositivo (servicio NUS)…")
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanning = false
        handler.removeCallbacks(scanTimeout)
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt?.close()
        connecting = true
        // Pass the main-looper handler so GATT callbacks are delivered on the UI
        // thread; otherwise they arrive on a binder thread and touching views
        // (listener.onStatus) throws CalledFromWrongThreadException.
        val g = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            handler,
        )
        if (g == null) {
            connecting = false
            listener.onStatus("No se pudo iniciar la conexión")
        }
        gatt = g
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, tx: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(tx, true)
        val cccd = tx.getDescriptor(CCCD)
        if (cccd == null) {
            listener.onConnected()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    fun isConnected(): Boolean = gatt != null && rxChar != null

    /** Queue a command; a trailing '\n' is appended as the line terminator. */
    fun send(command: String) {
        if (gatt == null || rxChar == null) {
            listener.onStatus("No conectado (no se envió)")
            return
        }
        val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
        Log.i(TAG, "send ${bytes.size}B: $command")
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + usableMtu).coerceAtMost(bytes.size)
            writeQueue.addLast(bytes.copyOfRange(offset, end))
            offset = end
        }
        drainQueue()
    }

    @SuppressLint("MissingPermission")
    private fun drainQueue() {
        val g = gatt ?: return
        val c = rxChar ?: return
        if (writeInProgress) return
        val next = writeQueue.removeFirstOrNull() ?: return

        writeInProgress = true
        Log.i(TAG, "write ${next.size}B")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            c.value = next
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxChar = null
        txChar = null
        connecting = false
    }
}
