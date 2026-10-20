package com.placeholder.hrvbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.edit
import com.placeholder.hrvbridge.core.HeartRateSource
import java.util.UUID

/** Runtime permissions needed to scan for and connect to the strap. */
object BlePermissions {
    @SuppressLint("InlinedApi") // the S+ constants are only used when sdkInt >= S
    fun required(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 and lower: BLE scanning needs location permission.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean =
        required().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

/**
 * Heart rate strap over plain BluetoothGatt: scan filtered by 0x180D, connect,
 * enable notifications on 0x2A37. Prefers the last used strap; otherwise
 * takes the strongest one seen during a short scan. Every GATT callback is
 * reported via [HeartRateSource.Listener.onEvent]. Untested on a device.
 */
@SuppressLint("MissingPermission") // checked in start() via BlePermissions
class BleHeartRateSource(private val context: Context) : HeartRateSource {
    override val description = "Bluetooth strap (0x180D)"

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("strap", Context.MODE_PRIVATE)
    private var listener: HeartRateSource.Listener? = null
    private var gatt: BluetoothGatt? = null
    private var scanning: ScanCallback? = null
    private val seen = LinkedHashMap<String, Pair<BluetoothDevice, Int>>()
    private var subscribed = false

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun start(listener: HeartRateSource.Listener) {
        this.listener = listener
        subscribed = false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            !BlePermissions.granted(context) -> fail("missing permissions ${BlePermissions.required()}")
            adapter == null -> fail("no Bluetooth adapter")
            !adapter.isEnabled -> fail("Bluetooth is off")
            adapter.bluetoothLeScanner == null -> fail("no BLE scanner")
            else -> scan(adapter.bluetoothLeScanner)
        }
    }

    override fun stop() {
        listener = null
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    private fun scan(scanner: android.bluetooth.le.BluetoothLeScanner) {
        val remembered = prefs.getString(KEY_ADDRESS, null)
        event("scan start, remembered=${remembered ?: "none"}")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                main.post {
                    if (scanning !== this) return@post
                    if (d.address !in seen) event("scan found ${d.address} name=${d.name} rssi=${result.rssi}")
                    seen[d.address] = d to result.rssi
                    if (d.address == remembered) connect(d)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post { if (scanning === this) fail("scan failed, error $errorCode") }
            }
        }
        scanning = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        // Remembered strap not seen quickly: take the strongest one found.
        main.postDelayed({ if (scanning != null) seen.values.maxByOrNull { it.second }?.let { connect(it.first) } }, PICK_MS)
        main.postDelayed({ if (scanning != null) fail("no heart rate strap found in ${SCAN_TIMEOUT_MS / 1000} s") }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        val cb = scanning ?: return
        scanning = null
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    private fun connect(device: BluetoothDevice) {
        if (gatt != null) return
        stopScan()
        event("connecting to ${device.address} name=${device.name}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        main.postDelayed({ if (listener != null && !subscribed) fail("connect timeout") }, CONNECT_TIMEOUT_MS)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                event("connection state $newState status $status")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> if (!g.discoverServices()) fail("discoverServices refused")
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        g.close()
                        gatt = null
                        listener?.onDisconnected()
                        listener = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                event("services discovered status $status: ${g.services.map { it.uuid }}")
                val ch = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
                val cccd = ch?.getDescriptor(CCCD)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null || cccd == null) {
                    fail("no 0x2A37 characteristic with notify descriptor")
                    return@post
                }
                g.setCharacteristicNotification(ch, true)
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                event("enable notifications requested: $ok")
                if (!ok) fail("writeDescriptor refused")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                event("descriptor ${d.uuid} write status $status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("enabling notifications failed")
                    return@post
                }
                subscribed = true
                prefs.edit { putString(KEY_ADDRESS, g.device.address) }
                listener?.onConnected()
            }
        }

        // Android 13+.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(g, c, value.copyOf())
        }

        // Android 12 and lower.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(g, c, c.value?.copyOf() ?: ByteArray(0))
        }
    }

    private fun deliver(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid != HEART_RATE_MEASUREMENT) return
        // Timestamp on arrival, before the hop to the main thread.
        val t = nowMs()
        main.post { if (g === gatt) listener?.onPacket(value, t) }
    }

    private fun event(message: String) {
        listener?.onEvent(message)
    }

    private fun fail(reason: String) {
        val l = listener ?: return
        l.onEvent("failed: $reason")
        stop()
        l.onDisconnected()
    }

    private companion object {
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val KEY_ADDRESS = "lastAddress"
        const val PICK_MS = 3_000L
        const val SCAN_TIMEOUT_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
