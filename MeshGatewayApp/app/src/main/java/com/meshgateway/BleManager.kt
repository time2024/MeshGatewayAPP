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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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

    /** 图片发送模式 */
    enum class ImageSendMode { FAST, ACK }

    /** 图片发送状态 (UI 观察用) */
    sealed class ImageSendState {


        object Idle : ImageSendState()
        data class Sending(val seq: Int, val total: Int, val mode: ImageSendMode) : ImageSendState() {
            val progress: Float get() = if (total > 0) seq.toFloat() / total else 0f
            val percent: Int get() = (progress * 100).toInt()
        }
        data class WaitingAck(val seq: Int, val total: Int) : ImageSendState()
        /** v2: BLE 上传完成, 网关正在流控传输到目标节点 */
        data class MeshTransfer(val rxCount: Int, val total: Int, val phase: Int) : ImageSendState() {
            val progress: Float get() = if (total > 0) rxCount.toFloat() / total else 0f
            val percent: Int get() = (progress * 100).toInt()
            val phaseText: String get() = if (phase == 0) "传输中" else "补包中"
        }
        object Finishing : ImageSendState()
        data class Done(val success: Boolean, val msg: String) : ImageSendState()
        data class Cancelled(val msg: String = "已取消") : ImageSendState()

        /** v3: 组播传输中 */
        data class MulticastTransfer(
            val completedCount: Int,
            val totalTargets: Int,
            val results: Map<Int, Int>  // addr → status (0=OK, 1=OOM, 2=timeout, 4=CRC_ERR)
        ) : ImageSendState()

        /** v3: 组播完成 */
        data class MulticastDone(
            val results: Map<Int, Int>  // 最终每个节点的状态
        ) : ImageSendState()
    }

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

    private val _cccdReady = MutableStateFlow(false)
    val cccdReady: StateFlow<Boolean> = _cccdReady

    private val _topoQuerying = MutableStateFlow(false)
    val topoQuerying: StateFlow<Boolean> = _topoQuerying
    private val topoQueryTimeout = Runnable { _topoQuerying.value = false }

    /* ── BLE GATT 写入队列 ── */
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private val writePending = AtomicBoolean(false)

    /* ── 图片发送引擎 ── */
    private val _imageSendState = MutableStateFlow<ImageSendState>(ImageSendState.Idle)
    val imageSendState: StateFlow<ImageSendState> = _imageSendState

    /** 图片传输是否正在进行中 (传输期间禁止查询拓扑和发送消息) */
    val isImageBusy: Boolean get() {
        val s = _imageSendState.value
        return s is ImageSendState.Sending || s is ImageSendState.WaitingAck
                || s is ImageSendState.Finishing || s is ImageSendState.MeshTransfer
                || s is ImageSendState.MulticastTransfer
    }

    private var imgDstAddr: Int = 0
    private var imgData: ByteArray? = null
    private var imgPackets: List<ByteArray> = emptyList()
    private var imgTotalPkts: Int = 0
    private var imgNextSeq: Int = 0
    private var imgMode: ImageSendMode = ImageSendMode.FAST
    private var imgCancelled = AtomicBoolean(false)
    private var imgWidth: Int = 0
    private var imgHeight: Int = 0
    private var imgFastSeq: Int = 0
    private val imgAckTimeout = Runnable { onImageAckTimeout() }
    private val imgResultTimeout = Runnable { onImageResultTimeout() }
    private val imgFastPacedSender = Runnable { imgSendNextFastPaced() }
    private var imgMulticastTargets: List<Int>? = null  // null=单播, non-null=组播

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
            _debugInfo.value = err
            err
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return try { adapter?.isEnabled == true } catch (_: Exception) { false }
    }

    @Suppress("MissingPermission")
    fun startScan(): String {
        val a = adapter ?: run { _debugInfo.value = "FAIL: adapter=null"; return "adapter=null" }
        if (!a.isEnabled) { _debugInfo.value = "FAIL: BT未开启"; return "BT未开启" }
        if (!hasScanPermission()) { _debugInfo.value = "FAIL: 无扫描权限"; return "无扫描权限" }
        val s: BluetoothLeScanner?
        try { s = a.bluetoothLeScanner } catch (e: Exception) {
            val msg = "getScanner异常: ${e.message}"; _debugInfo.value = msg; return msg
        }
        if (s == null) { _debugInfo.value = "FAIL: scanner=null"; return "scanner=null" }

        _scannedDevices.value = emptyList()
        _connState.value = ConnState.SCANNING
        scanner = s

        val settings: ScanSettings
        try {
            settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        } catch (e: Exception) {
            _connState.value = ConnState.DISCONNECTED; return "ScanSettings异常: ${e.message}"
        }

        try {
            _debugInfo.value = "扫描中... (12秒)"
            s.startScan(ArrayList<ScanFilter>(), settings, scanCallback)
            handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
            return "OK"
        } catch (e: Exception) {
            _connState.value = ConnState.DISCONNECTED
            return "${e.javaClass.simpleName}: ${e.message}"
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
                try { name = dev.name ?: return } catch (_: SecurityException) { return }
                if (!name.uppercase().startsWith("SLE_GW_")) return
                val item = ScannedDevice(name, dev.address, result.rssi, dev)
                val list = _scannedDevices.value.toMutableList()
                val idx = list.indexOfFirst { it.name == item.name }
                if (idx >= 0) list[idx] = item else list.add(item)
                _scannedDevices.value = list
                _debugInfo.value = "扫描中... 找到 ${list.size} 个网关"
            } catch (_: Exception) {}
        }
        override fun onScanFailed(errorCode: Int) {
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
            _debugInfo.value = "连接失败: ${e.message}"
            _connState.value = ConnState.DISCONNECTED
        }
    }

    fun updateDeviceName(name: String) { _deviceName.value = name }

    @Suppress("MissingPermission")
    fun disconnect() {
        imgCancelled.set(true)
        handler.removeCallbacks(imgAckTimeout)
        handler.removeCallbacks(imgResultTimeout)
        handler.removeCallbacks(fastProgressTracker)
        handler.removeCallbacks(imgFastPacedSender)
        _imageSendState.value = ImageSendState.Idle
        imgCleanup()

        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; rxChar = null
        writeQueue.clear()
        writePending.set(false)
        handler.removeCallbacks(topoQueryTimeout)
        _topoQuerying.value = false
        _cccdReady.value = false
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
                        imgCancelled.set(true)
                        handler.removeCallbacks(imgAckTimeout)
                        handler.removeCallbacks(imgResultTimeout)
                        handler.removeCallbacks(fastProgressTracker)
                        handler.removeCallbacks(imgFastPacedSender)
                        handler.post {
                            _connState.value = ConnState.DISCONNECTED
                            _deviceName.value = ""
                            _cccdReady.value = false
                            _topoQuerying.value = false
                            if (_imageSendState.value !is ImageSendState.Idle) {
                                _imageSendState.value = ImageSendState.Done(false, "连接断开")
                            }
                        }
                        writeQueue.clear(); writePending.set(false)
                        handler.removeCallbacks(topoQueryTimeout)
                        imgCleanup()
                        try { g.close() } catch (_: Exception) {}
                        gatt = null; rxChar = null
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
                val svc = g.getService(SERVICE_UUID)
                if (svc == null) {
                    handler.post { _debugInfo.value = "未找到FFE0服务!" }
                    return
                }
                rxChar = svc.getCharacteristic(RX_CHAR_UUID)
                val txChar = svc.getCharacteristic(TX_CHAR_UUID)
                if (rxChar == null || txChar == null) {
                    handler.post { _debugInfo.value = "未找到必需特征!" }
                    return
                }

                val txProps = txChar.properties
                val hasNotify = (txProps and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val hasIndicate = (txProps and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                g.setCharacteristicNotification(txChar, true)

                val cccd = txChar.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    handler.post { _debugInfo.value = "FFE1无CCCD (仍尝试接收)" }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val writeResult = if (hasIndicate) {
                            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                        } else {
                            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        }
                        handler.post { _debugInfo.value = "CCCD写入: result=$writeResult" }
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = if (hasIndicate) {
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        }
                        @Suppress("DEPRECATION")
                        val ok = g.writeDescriptor(cccd)
                        handler.post { _debugInfo.value = "CCCD写入: ok=$ok" }
                    }
                }
            } catch (e: Exception) {
                handler.post { _debugInfo.value = "服务发现异常: ${e.message}" }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                _debugInfo.value = "CCCD订阅${if (status == 0) "成功✓" else "失败($status)"}"
                if (status == 0) _cccdReady.value = true
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val data = c.value ?: return
            try { handleNotify(data) } catch (_: Exception) {}
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            try { handleNotify(value) } catch (_: Exception) {}
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            writePending.set(false)
            drainWriteQueue()
        }
    }

    private fun handleNotify(data: ByteArray) {
        Log.d(TAG, "handleNotify: ${data.toHexString()}")
        val msg = MeshProtocol.parseNotification(data)
        if (msg == null) {
            handler.post { onMessage?.invoke(UpstreamMessage.DataFromNode(0, data)) }
            return
        }
        handler.post {
            if (msg is UpstreamMessage.Topology) {
                handler.removeCallbacks(topoQueryTimeout)
                _topoQuerying.value = false
            }
            if (msg is UpstreamMessage.ImageAck) handleImageAck(msg)
            if (msg is UpstreamMessage.ImageResult) handleImageResult(msg)
            if (msg is UpstreamMessage.ImageProgress) handleImageProgress(msg)
            if (msg is UpstreamMessage.ImageMissing) handleImageMissing(msg)
            if (msg is UpstreamMessage.MulticastProgress) handleMulticastProgress(msg)
            onMessage?.invoke(msg)
        }
    }

    /* ── BLE GATT 串行写入队列 ── */

    @Suppress("MissingPermission")
    private fun doWriteRaw(data: ByteArray): Boolean {
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

    private fun drainWriteQueue() {
        if (writePending.get()) return
        val data = writeQueue.poll() ?: return
        writePending.set(true)
        if (!doWriteRaw(data)) {
            writePending.set(false)
            Log.w(TAG, "writeCharacteristic failed, dropping ${data.size} bytes")
            drainWriteQueue()
        }
    }

    private fun sendRaw(data: ByteArray): Boolean {
        if (gatt == null || rxChar == null) return false
        writeQueue.offer(data)
        drainWriteQueue()
        return true
    }

    fun sendToNode(dstAddr: Int, payload: ByteArray): Boolean {
        if (isImageBusy) return false
        return sendRaw(MeshProtocol.buildUnicast(dstAddr, payload))
    }
    fun broadcast(payload: ByteArray): Boolean {
        if (isImageBusy) return false
        return sendRaw(MeshProtocol.buildBroadcast(payload))
    }

    fun queryTopology(): Boolean {
        if (_topoQuerying.value) return false
        if (isImageBusy) return false
        val sent = sendRaw(MeshProtocol.buildTopoQuery())
        if (sent) {
            _topoQuerying.value = true
            handler.removeCallbacks(topoQueryTimeout)
            handler.postDelayed(topoQueryTimeout, 5000L)
        }
        return sent
    }

    /* ════════════════════════════════════════════════════════════
     *  图片发送引擎
     *
     *  v2 架构变化:
     *    APP 通过 BLE 快速上传所有数据到网关缓存 (5ms/包)
     *    → 网关自主进行流控注入 mesh
     *    → 网关通过 AA 89 (ImageProgress) 报告进度
     *    → 最终通过 AA 86 (ImageResult) 报告结果
     *
     *  APP 的角色简化为:
     *    1. BLE 上传 (START + DATA×N + END)
     *    2. 显示 BLE 上传进度 → Mesh 传输进度 → 结果
     *
     *  v2.1: ACK 模式通过 xfer=1 告知网关立即注入 mesh, 不缓存
     *
     *  v3: 组播模式 (MCAST_START + DATA×N + END, 网关通过 0x8A 通知各节点状态)
     * ════════════════════════════════════════════════════════════ */

    /** v3: 组播发送图片到多个目标节点 */
    fun sendImageMulticast(targets: List<Int>, data: ByteArray, width: Int, height: Int,
                           imageMode: Int = MeshProtocol.IMG_MODE_H_LSB): Boolean {
        if (targets.isEmpty() || targets.size > 8) return false
        val curState = _imageSendState.value
        if (curState is ImageSendState.Sending || curState is ImageSendState.WaitingAck
            || curState is ImageSendState.Finishing || curState is ImageSendState.MeshTransfer
            || curState is ImageSendState.MulticastTransfer) {
            Log.w(TAG, "sendImageMulticast: already in progress")
            return false
        }
        if (gatt == null || rxChar == null) return false

        imgDstAddr = 0xFFFF  // 组播数据包使用广播地址
        imgData = data
        imgWidth = width
        imgHeight = height
        imgMode = ImageSendMode.FAST
        imgCancelled.set(false)
        imgNextSeq = 0
        imgMulticastTargets = targets

        imgPackets = data.toList().chunked(MeshProtocol.IMG_PKT_PAYLOAD).map { it.toByteArray() }
        imgTotalPkts = imgPackets.size

        Log.d(TAG, "sendImageMulticast: targets=${targets.map { "0x${String.format("%04X", it)}" }} " +
                "${width}x${height} data=${data.size}B pkts=$imgTotalPkts")

        // 1) 发送 MCAST_START
        sendRaw(MeshProtocol.buildImageMulticastStart(targets, data.size, imgTotalPkts, width, height, imageMode))

        // 2) 开始发送数据分包 (FAST 模式)
        _imageSendState.value = ImageSendState.Sending(0, imgTotalPkts, ImageSendMode.FAST)
        imgSendAllPacketsFast()

        return true
    }

    fun sendImage(dstAddr: Int, data: ByteArray, width: Int, height: Int,
                  mode: ImageSendMode = ImageSendMode.FAST,
                  imageMode: Int = MeshProtocol.IMG_MODE_H_LSB): Boolean {

        val curState = _imageSendState.value
        if (curState is ImageSendState.Sending || curState is ImageSendState.WaitingAck
            || curState is ImageSendState.Finishing || curState is ImageSendState.MeshTransfer) {
            Log.w(TAG, "sendImage: already in progress")
            return false
        }
        if (gatt == null || rxChar == null) return false

        imgDstAddr = dstAddr
        imgData = data
        imgWidth = width
        imgHeight = height
        imgMode = mode
        imgCancelled.set(false)
        imgNextSeq = 0
        imgMulticastTargets = null  // 单播模式

        imgPackets = data.toList().chunked(MeshProtocol.IMG_PKT_PAYLOAD).map { it.toByteArray() }
        imgTotalPkts = imgPackets.size

        Log.d(TAG, "sendImage: dst=0x${String.format("%04X", dstAddr)} " +
                "${width}x${height} data=${data.size}B pkts=$imgTotalPkts mode=$mode")

        // 1) 发送 START 帧 (xfer: FAST=0, ACK=1)
        val xfer = if (mode == ImageSendMode.ACK) MeshProtocol.IMG_XFER_ACK else MeshProtocol.IMG_XFER_FAST
        sendRaw(MeshProtocol.buildImageStart(dstAddr, data.size, imgTotalPkts, width, height, mode = imageMode, xfer = xfer))

        // 2) 开始发送数据分包
        _imageSendState.value = ImageSendState.Sending(0, imgTotalPkts, mode)

        when (mode) {
            ImageSendMode.FAST -> {
                // v2: 快速 BLE 上传到网关 (5ms/包, 网关缓存后自主流控)
                imgSendAllPacketsFast()
            }
            ImageSendMode.ACK -> {
                imgSendNextPacketAck()
            }
        }
        return true
    }

    /**
     * v2: 快速模式 — BLE 上传到网关缓存
     * 减小包间延时从 50ms → 5ms (BLE 写入队列保证顺序)
     * 网关收完后自主做 mesh 流控注入
     */
    private fun imgSendAllPacketsFast() {
        imgFastSeq = 0
        imgSendNextFastPaced()
        imgStartFastProgressTracker()
    }

    private fun imgSendNextFastPaced() {
        if (imgCancelled.get()) return

        val seq = imgFastSeq
        val pkts = imgPackets
        if (seq >= imgTotalPkts || seq >= pkts.size) {
            // 所有数据包发完, 发 END 帧
            val crc = MeshProtocol.crc16(imgData!!)
            sendRaw(MeshProtocol.buildImageEnd(imgDstAddr, crc))
            Log.d(TAG, "imgFastPaced: all $imgTotalPkts pkts + END sent to gateway")
            return
        }

        val frame = MeshProtocol.buildImageData(imgDstAddr, seq, pkts[seq])
        sendRaw(frame)
        imgFastSeq = seq + 1

        // v2: 5ms 包间延时 (BLE 串行队列保证顺序, 不用等 mesh)
        handler.postDelayed(imgFastPacedSender, 5L)
    }

    private val fastProgressTracker = Runnable { imgUpdateFastProgress() }

    private fun imgStartFastProgressTracker() {
        handler.postDelayed(fastProgressTracker, 50)
    }

    private fun imgUpdateFastProgress() {
        if (imgCancelled.get()) return
        val state = _imageSendState.value
        if (state !is ImageSendState.Sending && state !is ImageSendState.MeshTransfer
            && state !is ImageSendState.Finishing && state !is ImageSendState.MulticastTransfer) return

        val sent = imgFastSeq.coerceIn(0, imgTotalPkts)

        if (sent >= imgTotalPkts) {
            // BLE 上传完成 → 等待网关流控/组播
            if (state is ImageSendState.Sending) {
                val mcastTargets = imgMulticastTargets
                if (mcastTargets != null) {
                    _imageSendState.value = ImageSendState.MulticastTransfer(0, mcastTargets.size, emptyMap())
                    _debugInfo.value = "上传完成, 等待组播通知..."
                } else {
                    _imageSendState.value = ImageSendState.MeshTransfer(0, 0, 0)
                    _debugInfo.value = "上传完成, 等待网关通知..."
                }
            }
            // v2: 动态超时 = 30s + 0.5s × 包数 (组播额外加时)
            val timeoutMs = 30000L + imgTotalPkts * 500L + (imgMulticastTargets?.size ?: 0) * 10000L
            handler.removeCallbacks(imgResultTimeout)
            handler.postDelayed(imgResultTimeout, timeoutMs)
            Log.d(TAG, "BLE upload done, waiting for gateway FC result (timeout=${timeoutMs}ms)")
        } else {
            // 仅在 Sending 状态时更新上传进度, 不覆盖已收到的 MulticastTransfer 结果
            if (state is ImageSendState.Sending) {
                _imageSendState.value = ImageSendState.Sending(sent, imgTotalPkts, ImageSendMode.FAST)
                _debugInfo.value = "上传到网关 $sent/$imgTotalPkts"
            }
            handler.postDelayed(fastProgressTracker, 50)
        }
    }

    /** ACK 模式: 发送下一个分包 */
    private fun imgSendNextPacketAck() {
        if (imgCancelled.get()) return

        val seq = imgNextSeq
        if (seq >= imgTotalPkts) {
            val crc = MeshProtocol.crc16(imgData!!)
            sendRaw(MeshProtocol.buildImageEnd(imgDstAddr, crc))
            _imageSendState.value = ImageSendState.Finishing
            _debugInfo.value = "图片数据发送完毕, 等待确认..."
            handler.removeCallbacks(imgResultTimeout)
            handler.postDelayed(imgResultTimeout, 15000L)
            return
        }

        val frame = MeshProtocol.buildImageData(imgDstAddr, seq, imgPackets[seq])
        sendRaw(frame)
        _imageSendState.value = ImageSendState.WaitingAck(seq, imgTotalPkts)
        _debugInfo.value = "发送图片 ${seq + 1}/$imgTotalPkts (等待ACK)"
        handler.removeCallbacks(imgAckTimeout)
        handler.postDelayed(imgAckTimeout, 3000L)
    }

    /** 处理图片分包 ACK (AA 85) */
    private fun handleImageAck(ack: UpstreamMessage.ImageAck) {
        Log.d(TAG, "handleImageAck: status=${ack.status} seq=${ack.seq}")

        val curState = _imageSendState.value
        if (curState !is ImageSendState.Sending && curState !is ImageSendState.Finishing
            && curState !is ImageSendState.MeshTransfer && curState !is ImageSendState.WaitingAck) return

        if (imgMode != ImageSendMode.ACK) {
            // 快速模式: ACK 不驱动发包, 但重置超时
            if (curState is ImageSendState.Finishing || curState is ImageSendState.MeshTransfer) {
                handler.removeCallbacks(imgResultTimeout)
                val timeoutMs = 30000L + imgTotalPkts * 500L
                handler.postDelayed(imgResultTimeout, timeoutMs)
            }
            return
        }
        if (imgCancelled.get()) return

        handler.removeCallbacks(imgAckTimeout)
        when (ack.status) {
            MeshProtocol.IMG_ACK_OK -> {
                imgNextSeq = ack.seq + 1
                _imageSendState.value = ImageSendState.Sending(imgNextSeq, imgTotalPkts, ImageSendMode.ACK)
                imgSendNextPacketAck()
            }
            MeshProtocol.IMG_ACK_RESEND -> {
                imgNextSeq = ack.seq
                imgSendNextPacketAck()
            }
            MeshProtocol.IMG_ACK_DONE -> {
                val crc = MeshProtocol.crc16(imgData!!)
                sendRaw(MeshProtocol.buildImageEnd(imgDstAddr, crc))
                _imageSendState.value = ImageSendState.Finishing
                handler.removeCallbacks(imgResultTimeout)
                handler.postDelayed(imgResultTimeout, 15000L)
            }
        }
    }

    /** v2: 处理网关流控进度 (AA 89) */
    private fun handleImageProgress(progress: UpstreamMessage.ImageProgress) {
        Log.d(TAG, "handleImageProgress: phase=${progress.phase} rx=${progress.rxCount}/${progress.total}")

        val curState = _imageSendState.value
        if (curState !is ImageSendState.MeshTransfer && curState !is ImageSendState.Finishing
            && curState !is ImageSendState.Sending) return

        _imageSendState.value = ImageSendState.MeshTransfer(
            progress.rxCount, progress.total, progress.phase
        )
        val phaseText = if (progress.phase == 0) "传输中" else "补包中"
        _debugInfo.value = "Mesh$phaseText: ${progress.rxCount}/${progress.total}"

        // 重置超时 (有进度就说明还活着)
        handler.removeCallbacks(imgResultTimeout)
        val timeoutMs = 30000L + imgTotalPkts * 500L
        handler.postDelayed(imgResultTimeout, timeoutMs)
    }

    /** v3: 处理组播进度通知 (AA 8A) */
    private fun handleMulticastProgress(progress: UpstreamMessage.MulticastProgress) {
        Log.d(TAG, "handleMulticastProgress: ${progress.completedCount}/${progress.totalTargets} " +
                "addr=0x${String.format("%04X", progress.latestAddr)} status=${progress.latestStatus}")

        val curState = _imageSendState.value
        if (curState !is ImageSendState.MulticastTransfer && curState !is ImageSendState.Sending) return

        val newResults = if (curState is ImageSendState.MulticastTransfer)
            curState.results.toMutableMap() else mutableMapOf()
        newResults[progress.latestAddr] = progress.latestStatus

        if (progress.completedCount >= progress.totalTargets) {
            // 所有目标完成
            _imageSendState.value = ImageSendState.MulticastDone(newResults)
            _debugInfo.value = "组播完成: ${newResults.count { it.value == 0 }}/${newResults.size} 成功"
            handler.removeCallbacks(imgResultTimeout)
            handler.removeCallbacks(fastProgressTracker)
            imgCleanup()
        } else {
            _imageSendState.value = ImageSendState.MulticastTransfer(
                progress.completedCount, progress.totalTargets, newResults
            )
            val statusText = when (progress.latestStatus) { 0 -> "成功"; 1 -> "OOM"; 2 -> "超时"; 4 -> "CRC"; else -> "失败" }
            _debugInfo.value = "组播进度: ${progress.completedCount}/${progress.totalTargets} [0x${String.format("%04X", progress.latestAddr)}=$statusText]"

            // 重置超时
            handler.removeCallbacks(imgResultTimeout)
            val timeoutMs = 30000L + imgTotalPkts * 500L + (imgMulticastTargets?.size ?: 0) * 10000L
            handler.postDelayed(imgResultTimeout, timeoutMs)
        }
    }

    /** v2: 处理缺包通知 (AA 87) — 网关自主补包, APP 只显示信息 */
    private fun handleImageMissing(missing: UpstreamMessage.ImageMissing) {
        Log.d(TAG, "handleImageMissing: ${missing.totalMissing} packets missing")
        _debugInfo.value = "网关补包中 (缺${missing.totalMissing}包)..."

        // 重置超时
        handler.removeCallbacks(imgResultTimeout)
        val timeoutMs = 30000L + imgTotalPkts * 500L
        handler.postDelayed(imgResultTimeout, timeoutMs)
    }

    /** 处理图片传输结果 (AA 86) */
    private fun handleImageResult(result: UpstreamMessage.ImageResult) {
        Log.d(TAG, "handleImageResult: status=${result.status}")

        val curState = _imageSendState.value
        if (curState !is ImageSendState.Sending && curState !is ImageSendState.Finishing
            && curState !is ImageSendState.MeshTransfer && curState !is ImageSendState.WaitingAck) {
            Log.w(TAG, "handleImageResult: ignored (state=${curState::class.simpleName})")
            return
        }

        handler.removeCallbacks(imgAckTimeout)
        handler.removeCallbacks(imgResultTimeout)
        handler.removeCallbacks(fastProgressTracker)
        handler.removeCallbacks(imgFastPacedSender)

        val msg = when (result.status) {
            MeshProtocol.IMG_RESULT_OK      -> "图片接收成功"
            MeshProtocol.IMG_RESULT_OOM     -> "目标节点内存不足"
            MeshProtocol.IMG_RESULT_TIMEOUT -> "目标节点接收超时"
            MeshProtocol.IMG_RESULT_CANCEL  -> "目标节点已取消"
            MeshProtocol.IMG_RESULT_CRC_ERR -> "CRC校验失败(数据传输有丢包)"
            else -> "未知状态(${result.status})"
        }
        val success = result.status == MeshProtocol.IMG_RESULT_OK
        _imageSendState.value = ImageSendState.Done(success, msg)
        _debugInfo.value = msg
        imgCleanup()
    }

    private fun onImageAckTimeout() {
        if (imgCancelled.get()) return
        val retrySeq = imgNextSeq
        if (retrySeq < imgTotalPkts) {
            _debugInfo.value = "ACK超时, 重发 ${retrySeq + 1}/$imgTotalPkts"
            imgSendNextPacketAck()
        } else {
            _imageSendState.value = ImageSendState.Done(false, "ACK超时, 传输失败")
            imgCleanup()
        }
    }

    private fun onImageResultTimeout() {
        Log.w(TAG, "onImageResultTimeout")
        val curState = _imageSendState.value
        if (curState is ImageSendState.Finishing || curState is ImageSendState.MeshTransfer) {
            _imageSendState.value = ImageSendState.Done(false,
                "未收到目标节点确认(网关传输可能超时)")
            _debugInfo.value = "传输超时, 建议检查网络后重试"
            imgCleanup()
        } else if (curState is ImageSendState.MulticastTransfer) {
            _imageSendState.value = ImageSendState.MulticastDone(curState.results)
            _debugInfo.value = "组播超时: ${curState.completedCount}/${curState.totalTargets} 已完成"
            imgCleanup()
        }
    }

    fun cancelImageSend() {
        if (imgCancelled.getAndSet(true)) return
        handler.removeCallbacks(imgAckTimeout)
        handler.removeCallbacks(imgResultTimeout)
        handler.removeCallbacks(fastProgressTracker)
        handler.removeCallbacks(imgFastPacedSender)
        writeQueue.clear()
        if (imgDstAddr != 0) sendRaw(MeshProtocol.buildImageCancel(imgDstAddr))
        _imageSendState.value = ImageSendState.Cancelled()
        _debugInfo.value = "图片传输已取消"
        imgCleanup()
    }

    fun resetImageSendState() { _imageSendState.value = ImageSendState.Idle }

    private fun imgCleanup() {
        imgCancelled.set(true)
        handler.removeCallbacks(imgFastPacedSender)
        handler.removeCallbacks(fastProgressTracker)
        imgData = null
        imgPackets = emptyList()
        imgMulticastTargets = null
    }
}
