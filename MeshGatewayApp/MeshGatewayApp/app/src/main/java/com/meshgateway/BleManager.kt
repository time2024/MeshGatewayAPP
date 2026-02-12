package com.meshgateway

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        val SERVICE_UUID: UUID  = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID  = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID  = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 12000L
    }

    enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    data class ScannedDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val device: BluetoothDevice
    )

    private val _connState = MutableStateFlow(ConnState.DISCONNECTED)
    val connState: StateFlow<ConnState> = _connState

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private val _debugInfo = MutableStateFlow("初始化中...")
    val debugInfo: StateFlow<String> = _debugInfo

    var onMessage: ((UpstreamMessage) -> Unit)? = null

    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 检查是否有BLE扫描权限 */
    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun init(): String {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            adapter = bm?.adapter
            val info = "BT=${adapter != null}, on=${adapter?.isEnabled}, SDK=${Build.VERSION.SDK_INT}, perm=${hasScanPermission()}"
            Log.d(TAG, "init: $info")
            _debugInfo.value = info
            info
        } catch (e: Exception) {
            val err = "init失败: ${e.message}"
            Log.e(TAG, err, e)
            _debugInfo.value = err
            err
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return try { adapter?.isEnabled == true } catch (_: Exception) { false }
    }

    @Suppress("MissingPermission")
    fun startScan(): String {
        // Step 1: check adapter
        val a = adapter
        if (a == null) {
            _debugInfo.value = "FAIL: adapter=null"
            return "adapter=null"
        }

        // Step 2: check enabled
        if (!a.isEnabled) {
            _debugInfo.value = "FAIL: BT未开启"
            return "BT未开启"
        }

        // Step 3: check permission
        if (!hasScanPermission()) {
            _debugInfo.value = "FAIL: 无扫描权限"
            return "无扫描权限"
        }

        // Step 4: get scanner
        val s: BluetoothLeScanner?
        try {
            s = a.bluetoothLeScanner
        } catch (e: Exception) {
            val msg = "getScanner异常: ${e.message}"
            _debugInfo.value = msg
            return msg
        }
        if (s == null) {
            _debugInfo.value = "FAIL: scanner=null"
            return "scanner=null"
        }

        // Step 5: prepare
        _scannedDevices.value = emptyList()
        _connState.value = ConnState.SCANNING
        scanner = s

        // Step 6: build settings
        val settings: ScanSettings
        try {
            settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
        } catch (e: Exception) {
            val msg = "ScanSettings异常: ${e.message}"
            _debugInfo.value = msg
            _connState.value = ConnState.DISCONNECTED
            return msg
        }

        // Step 7: actually start scan
        try {
            _debugInfo.value = "正在调用 startScan..."
            s.startScan(ArrayList<ScanFilter>(), settings, scanCallback)
            _debugInfo.value = "扫描中... (12秒)"
            handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
            return "OK"
        } catch (e: SecurityException) {
            val msg = "SEC异常: ${e.message}"
            _debugInfo.value = msg
            _connState.value = ConnState.DISCONNECTED
            return msg
        } catch (e: IllegalStateException) {
            val msg = "State异常: ${e.message}"
            _debugInfo.value = msg
            _connState.value = ConnState.DISCONNECTED
            return msg
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message}"
            _debugInfo.value = msg
            _connState.value = ConnState.DISCONNECTED
            return msg
        }
    }

    @Suppress("MissingPermission")
    fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (_connState.value == ConnState.SCANNING) {
            _connState.value = ConnState.DISCONNECTED
            _debugInfo.value = "扫描结束, 找到 ${_scannedDevices.value.size} 个设备"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val dev = result.device ?: return
                val name: String
                try {
                    name = dev.name ?: return
                } catch (_: SecurityException) {
                    return
                }
                if (!name.uppercase().startsWith("SLE_GW_")) return

                val item = ScannedDevice(name, dev.address, result.rssi, dev)
                val list = _scannedDevices.value.toMutableList()
                val idx = list.indexOfFirst { it.address == item.address }
                if (idx >= 0) list[idx] = item else list.add(item)
                _scannedDevices.value = list
                _debugInfo.value = "扫描中... 找到 ${list.size} 个网关"
            } catch (e: Exception) {
                Log.e(TAG, "onScanResult error", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            handler.post {
                _connState.value = ConnState.DISCONNECTED
                _debugInfo.value = "扫描失败(错误码$errorCode)"
            }
        }
    }

    @Suppress("MissingPermission")
    fun connect(device: ScannedDevice) {
        try {
            stopScan()
            _connState.value = ConnState.CONNECTING
            _deviceName.value = device.name
            gatt = device.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "connect error", e)
            _debugInfo.value = "连接失败: ${e.message}"
            _connState.value = ConnState.DISCONNECTED
        }
    }

    @Suppress("MissingPermission")
    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        rxChar = null
        _connState.value = ConnState.DISCONNECTED
        _deviceName.value = ""
    }

    @Suppress("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            try {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        handler.post {
                            _connState.value = ConnState.CONNECTED
                            _debugInfo.value = "已连接, 请求MTU..."
                        }
                        try { g.requestMtu(247) } catch (_: Exception) {
                            try { g.discoverServices() } catch (_: Exception) {}
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        handler.post {
                            _connState.value = ConnState.DISCONNECTED
                            _deviceName.value = ""
                        }
                        try { g.close() } catch (_: Exception) {}
                        gatt = null
                        rxChar = null
                    }
                }
            } catch (_: Exception) {}
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU=$mtu status=$status")
            handler.post { _debugInfo.value = "MTU=$mtu, 发现服务..." }
            try { g.discoverServices() } catch (_: Exception) {}
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post { _debugInfo.value = "服务发现失败 status=$status" }
                return
            }
            try {
                // 列出所有服务
                val allSvc = g.services?.map { it.uuid.toString().substring(4, 8) } ?: emptyList()
                Log.d(TAG, "Services: $allSvc")

                val svc = g.getService(SERVICE_UUID)
                if (svc == null) {
                    handler.post { _debugInfo.value = "未找到FFE0服务! 服务列表: $allSvc" }
                    return
                }

                // 列出所有特征
                val allChar = svc.characteristics?.map {
                    val uuid4 = it.uuid.toString().substring(4, 8)
                    val props = it.properties
                    "$uuid4(p=$props)"
                } ?: emptyList()
                Log.d(TAG, "Chars: $allChar")

                rxChar = svc.getCharacteristic(RX_CHAR_UUID)
                val txChar = svc.getCharacteristic(TX_CHAR_UUID)

                if (rxChar == null) {
                    handler.post { _debugInfo.value = "未找到FFE2(写)! 特征: $allChar" }
                    return
                }
                if (txChar == null) {
                    handler.post { _debugInfo.value = "未找到FFE1(通知)! 特征: $allChar" }
                    return
                }

                // 检查特征属性
                val txProps = txChar.properties
                val hasNotify = (txProps and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val hasIndicate = (txProps and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                Log.d(TAG, "TX props=$txProps notify=$hasNotify indicate=$hasIndicate")

                g.setCharacteristicNotification(txChar, true)

                val cccd = txChar.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    // 没有CCCD，列出所有描述符
                    val descs = txChar.descriptors?.map { it.uuid.toString().substring(4, 8) } ?: emptyList()
                    handler.post { _debugInfo.value = "FFE1无CCCD! props=$txProps N=$hasNotify I=$hasIndicate descs=$descs (仍尝试接收)" }
                    // 不 return，setCharacteristicNotification 已调用，某些设备仍可工作
                } else {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val writeResult = if (hasIndicate) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    } else {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    }
                    handler.post { _debugInfo.value = "CCCD写入: result=$writeResult N=$hasNotify I=$hasIndicate" }
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = if (hasIndicate) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    @Suppress("DEPRECATION")
                    val ok = g.writeDescriptor(cccd)
                    handler.post { _debugInfo.value = "CCCD写入: ok=$ok N=$hasNotify I=$hasIndicate" }
                }
                } // end else cccd != null
            } catch (e: Exception) {
                handler.post { _debugInfo.value = "服务发现异常: ${e.message}" }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid4 = descriptor.uuid.toString().substring(4, 8)
            Log.d(TAG, "DescriptorWrite: $uuid4 status=$status")
            handler.post { _debugInfo.value = "CCCD订阅${if (status == 0) "成功✓" else "失败($status)"}" }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val data = c.value ?: return
            Log.d(TAG, "Notify(old): ${data.toHexString()}")
            handler.post { _debugInfo.value = "收到: ${data.toHexString()}" }
            try { handleNotify(data) } catch (_: Exception) {}
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            Log.d(TAG, "Notify(new): ${value.toHexString()}")
            handler.post { _debugInfo.value = "收到: ${value.toHexString()}" }
            try { handleNotify(value) } catch (_: Exception) {}
        }
    }

    private fun handleNotify(data: ByteArray) {
        Log.d(TAG, "handleNotify: ${data.toHexString()}")
        val msg = MeshProtocol.parseNotification(data)
        if (msg == null) {
            Log.w(TAG, "parseNotification returned null for: ${data.toHexString()}")
            // 即使解析失败也通知UI显示原始数据
            handler.post {
                onMessage?.invoke(UpstreamMessage.DataFromNode(0, data))
            }
            return
        }
        handler.post { onMessage?.invoke(msg) }
    }

    @Suppress("MissingPermission")
    private fun sendRaw(data: ByteArray): Boolean {
        return try {
            val g = gatt ?: return false
            val c = rxChar ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (_: Exception) { false }
    }

    fun sendToNode(dstAddr: Int, payload: ByteArray) = sendRaw(MeshProtocol.buildUnicast(dstAddr, payload))
    fun broadcast(payload: ByteArray) = sendRaw(MeshProtocol.buildBroadcast(payload))
    fun queryTopology() = sendRaw(MeshProtocol.buildTopoQuery())
}
