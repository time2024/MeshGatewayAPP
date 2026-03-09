package com.meshgateway

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {

    private lateinit var ble: BleManager
    private val handler = Handler(Looper.getMainLooper())

    private val _pickedImageUri = mutableStateOf<Uri?>(null)

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        _pickedImageUri.value = uri
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isEmpty()) {
            handler.postDelayed({ doScan() }, 600)
        } else {
            Toast.makeText(this, "权限被拒绝: $denied", Toast.LENGTH_LONG).show()
        }
    }

    private fun doScan() {
        try {
            val result = ble.startScan()
            if (result != "OK") Toast.makeText(this, result, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "doScan崩溃: ${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scanWithPermission() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) doScan() else permLauncher.launch(needed.toTypedArray())
    }

    private val _crashLog = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val lastCrash = prefs.getString("last_crash", "") ?: ""
        if (lastCrash.isNotEmpty()) {
            _crashLog.value = "上次崩溃: $lastCrash"
            prefs.edit().remove("last_crash").apply()
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val msg = "${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTrace.take(5).joinToString("\n") { "  at ${it}" }}"
                getSharedPreferences("crash", MODE_PRIVATE).edit().putString("last_crash", msg).commit()
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        ble = BleManager(applicationContext)
        ble.init()

        setContent {
            MeshTheme {
                MeshApp(
                    ble = ble, onScan = { scanWithPermission() }, crashLog = _crashLog,
                    pickedImageUri = _pickedImageUri,
                    onPickImage = { imagePicker.launch("image/*") },
                    contentResolver = { uri -> contentResolver.openInputStream(uri) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ble.disconnect() } catch (_: Exception) {}
    }
}

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4FC3F7), secondary = Color(0xFF81C784),
            surface = Color(0xFF1E1E2E), background = Color(0xFF121220),
            onSurface = Color.White, onBackground = Color.White,
        ), content = content
    )
}

data class LogEntry(
    val msg: String,
    val time: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)

data class ImageResolution(val width: Int, val height: Int, val label: String)

val IMG_RESOLUTIONS = listOf(
    ImageResolution(240, 360, "240×360"),
    ImageResolution(480, 800, "480×800"),
)

/* ════════════════════════════════════════════════════════════
 *  节点图片持久化工具
 * ════════════════════════════════════════════════════════════ */

object NodeImageStore {
    private const val DIR_NAME = "node_images"

    private fun getDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun save(context: Context, nodeAddr: Int, bitmap: Bitmap) {
        try {
            val file = File(getDir(context), "node_${String.format("%04X", nodeAddr)}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {}
    }

    fun load(context: Context, nodeAddr: Int): Bitmap? {
        return try {
            val file = File(getDir(context), "node_${String.format("%04X", nodeAddr)}.png")
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        } catch (_: Exception) { null }
    }

    fun loadAll(context: Context): Map<Int, Bitmap> {
        val dir = getDir(context)
        val map = mutableMapOf<Int, Bitmap>()
        dir.listFiles()?.forEach { file ->
            try {
                val name = file.nameWithoutExtension // e.g. "node_0A01"
                if (name.startsWith("node_")) {
                    val addrHex = name.removePrefix("node_")
                    val addr = addrHex.toInt(16)
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) map[addr] = bmp
                }
            } catch (_: Exception) {}
        }
        return map
    }
}

/* ════════════════════════════════════════════════════════════
 *  MeshApp — 主 Composable
 * ════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshApp(
    ble: BleManager, onScan: () -> Unit, crashLog: MutableState<String> = mutableStateOf(""),
    pickedImageUri: MutableState<Uri?>, onPickImage: () -> Unit,
    contentResolver: (Uri) -> java.io.InputStream?
) {
    val state by ble.connState.collectAsState()
    val devices by ble.scannedDevices.collectAsState()
    val devName by ble.deviceName.collectAsState()
    val debug by ble.debugInfo.collectAsState()

    var nodes by remember { mutableStateOf(listOf<MeshNode>()) }
    var gwAddr by remember { mutableStateOf(0) }
    var logs by remember { mutableStateOf(listOf<LogEntry>()) }

    val cccdReady by ble.cccdReady.collectAsState()
    val topoQuerying by ble.topoQuerying.collectAsState()
    val imgSendState by ble.imageSendState.collectAsState()

    var dlgNode by remember { mutableStateOf<MeshNode?>(null) }
    var textDlgNode by remember { mutableStateOf<MeshNode?>(null) }
    var imgTargetNode by remember { mutableStateOf<MeshNode?>(null) }
    var imgCropBitmap by remember { mutableStateOf<Bitmap?>(null) }           // 原图 → 裁剪对话框
    var imgPreviewData by remember { mutableStateOf<ImagePreviewData?>(null) } // 裁剪后 → 预览

    val appContext = LocalContext.current.applicationContext
    var lastSentBitmaps by remember { mutableStateOf(NodeImageStore.loadAll(appContext)) }

    var mcastSelected by remember { mutableStateOf(setOf<Int>()) }
    var mcastSendTargets by remember { mutableStateOf<List<Int>>(emptyList()) }
    var pendingSaveBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingSaveAddrs by remember { mutableStateOf<List<Int>>(emptyList()) }

    val imageBusy = imgSendState is BleManager.ImageSendState.Sending
            || imgSendState is BleManager.ImageSendState.WaitingAck
            || imgSendState is BleManager.ImageSendState.Finishing
            || imgSendState is BleManager.ImageSendState.MeshTransfer
            || imgSendState is BleManager.ImageSendState.MulticastTransfer

    fun clearTopologyState() { nodes = listOf(); gwAddr = 0; logs = listOf(); mcastSelected = emptySet() }
    fun addLog(s: String) { logs = (logs + LogEntry(s)).takeLast(50) }

    DisposableEffect(ble) {
        ble.onMessage = { msg ->
            when (msg) {
                is UpstreamMessage.Topology -> {
                    gwAddr = msg.gatewayAddr
                    val gwHex = h4(msg.gatewayAddr)
                    ble.updateDeviceName("sle_gw_$gwHex")
                    val allNodes = mutableListOf(MeshNode(msg.gatewayAddr, 0))
                    allNodes.addAll(msg.nodes)
                    nodes = allNodes
                    addLog("拓扑: 网关 0x$gwHex, ${msg.nodes.size} 节点")
                }
                is UpstreamMessage.DataFromNode -> {
                    addLog("← [0x${h4(msg.srcAddr)}] ${msg.payload.decodeToStringOrHex()}")
                }
                is UpstreamMessage.ImageAck -> {
                    addLog("← [0x${h4(msg.srcAddr)}] 图片ACK seq=${msg.seq} status=${msg.status}")
                }
                is UpstreamMessage.ImageResult -> {
                    val t = when (msg.status) { MeshProtocol.IMG_RESULT_OK -> "成功"; MeshProtocol.IMG_RESULT_OOM -> "内存不足"; MeshProtocol.IMG_RESULT_TIMEOUT -> "超时"; MeshProtocol.IMG_RESULT_CANCEL -> "已取消"; MeshProtocol.IMG_RESULT_CRC_ERR -> "CRC校验失败"; else -> "未知(${msg.status})" }
                    addLog("← [0x${h4(msg.srcAddr)}] 图片结果: $t")
                }
                is UpstreamMessage.ImageMissing -> {
                    addLog("← [0x${h4(msg.srcAddr)}] 缺包${msg.totalMissing}个, 网关自主补包中")
                }
                is UpstreamMessage.ImageProgress -> {
                    val phase = if (msg.phase == 0) "传输" else "补包"
                    addLog("← [0x${h4(msg.srcAddr)}] Mesh${phase}: ${msg.rxCount}/${msg.total}")
                }
                is UpstreamMessage.MulticastProgress -> {
                    val statusText = when (msg.latestStatus) { 0 -> "成功"; 1 -> "OOM"; 2 -> "超时"; 4 -> "CRC"; else -> "失败(${msg.latestStatus})" }
                    addLog("← 组播 ${msg.completedCount}/${msg.totalTargets} [0x${h4(msg.latestAddr)}] $statusText")
                }
            }
        }
        onDispose { ble.onMessage = null }
    }

    LaunchedEffect(cccdReady) {
        if (cccdReady && state == BleManager.ConnState.CONNECTED) {
            kotlinx.coroutines.delay(200)
            if (ble.queryTopology()) addLog("→ 自动查询拓扑")
        }
    }

    // 定时自动查询拓扑 (每30秒, 图片传输中/操作对话框打开时跳过)
    LaunchedEffect(state, cccdReady) {
        if (state == BleManager.ConnState.CONNECTED && cccdReady) {
            while (isActive) {
                delay(30_000L)
                val inSendFlow = dlgNode != null || textDlgNode != null
                        || imgTargetNode != null || imgCropBitmap != null || imgPreviewData != null
                if (!ble.isImageBusy && !inSendFlow
                    && state == BleManager.ConnState.CONNECTED && mcastSelected.isEmpty()) {
                    if (ble.queryTopology()) addLog("→ 自动刷新拓扑")
                }
            }
        }
    }

    // 用户选图 → 打开裁剪
    LaunchedEffect(pickedImageUri.value) {
        val uri = pickedImageUri.value ?: return@LaunchedEffect
        pickedImageUri.value = null
        try {
            val s = contentResolver(uri)
            if (s != null) {
                val bmp = BitmapFactory.decodeStream(s); s.close()
                if (bmp != null) imgCropBitmap = bmp
            }
        } catch (e: Exception) { addLog("图片读取失败: ${e.message}") }
    }

    BackHandler(enabled = state == BleManager.ConnState.CONNECTED || state == BleManager.ConnState.CONNECTING) {
        clearTopologyState(); ble.disconnect()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NearLink Mesh", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    Box(Modifier.padding(end = 16.dp).size(12.dp).clip(CircleShape).background(
                        when (state) { BleManager.ConnState.CONNECTED -> Color(0xFF4CAF50); BleManager.ConnState.CONNECTING -> Color(0xFFFFC107); BleManager.ConnState.SCANNING -> Color(0xFF2196F3); else -> Color(0xFF757575) }
                    ))
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)) {
            when (state) {
                BleManager.ConnState.DISCONNECTED, BleManager.ConnState.SCANNING ->
                    ScanPage(state == BleManager.ConnState.SCANNING, devices, onScan, debug, crashLog.value) {
                        clearTopologyState(); ble.connect(it)
                    }
                BleManager.ConnState.CONNECTING -> CenterContent("正在连接 $devName ...")
                BleManager.ConnState.CONNECTED ->
                    ConnectedPage(devName, gwAddr, nodes, logs, debug, topoQuerying, cccdReady, imgSendState,
                        multicastSelected = mcastSelected,
                        onQueryTopo = { if (!ble.isImageBusy && ble.queryTopology()) addLog("→ 查询拓扑") },
                        onDisconnect = { clearTopologyState(); ble.disconnect() },
                        onNodeClick = { if (!imageBusy) dlgNode = it },
                        onCancelImage = { ble.cancelImageSend(); addLog("→ 取消图片传输") },
                        onToggleMulticast = { addr ->
                            mcastSelected = if (addr in mcastSelected) mcastSelected - addr else mcastSelected + addr
                        },
                        onMulticastSend = {
                            if (mcastSelected.isNotEmpty() && !imageBusy) {
                                mcastSendTargets = mcastSelected.toList()
                                imgTargetNode = MeshNode(0xFFFF, 0)
                                onPickImage()
                            }
                        },
                        onNodeLongPress = { node ->
                            if (!imageBusy) {
                                if (mcastSelected.isNotEmpty()) {
                                    mcastSelected = emptySet()
                                } else {
                                    mcastSelected = mcastSelected + node.addr
                                }
                            }
                        }
                    )
            }

            /* ── 节点操作选择 ── */
            dlgNode?.let { node ->
                NodeActionDialog(node, lastSentBitmap = lastSentBitmaps[node.addr], onDismiss = { dlgNode = null },
                    onSendText = { dlgNode = null; textDlgNode = node },
                    onSendImage = { dlgNode = null; imgTargetNode = node; onPickImage() }
                )
            }

            /* ── 文本发送 ── */
            textDlgNode?.let { node ->
                SendDialog(node, onDismiss = { textDlgNode = null }, onSend = { txt ->
                    if (!imageBusy) { ble.sendToNode(node.addr, txt.toByteArray()); addLog("→ [0x${h4(node.addr)}] $txt") }
                    textDlgNode = null
                })
            }

            /* ── 裁剪对话框 ── */
            val cropBmp = imgCropBitmap
            val cropNode = imgTargetNode
            if (cropBmp != null && cropNode != null) {
                CropImageDialog(
                    originalBitmap = cropBmp, node = cropNode,
                    multicastCount = mcastSendTargets.size,
                    onDismiss = { imgCropBitmap = null; imgTargetNode = null; mcastSendTargets = emptyList() },
                    onConfirm = { cropped, res ->
                        imgCropBitmap = null
                        imgPreviewData = ImagePreviewData(cropNode, cropped, res, mcastSendTargets)
                    }
                )
            }

            /* ── 预览 & 发送 ── */
            imgPreviewData?.let { data ->
                ImagePreviewDialog(data, onDismiss = { imgPreviewData = null; imgTargetNode = null; mcastSendTargets = emptyList() },
                    onSend = { processedData, w, h, mode, imgMode, previewBmp ->
                        val rleTip = if (imgMode == MeshProtocol.IMG_MODE_RLE) " RLE" else ""
                        if (data.multicastTargets.isNotEmpty()) {
                            val ok = ble.sendImageMulticast(data.multicastTargets, processedData, w, h, imgMode)
                            if (ok) {
                                pendingSaveBitmap = previewBmp
                                pendingSaveAddrs = data.multicastTargets
                                addLog("→ 组播图片 ${w}x${h} → ${data.multicastTargets.size}个节点 ${processedData.size}B$rleTip")
                            } else addLog("组播发送失败 (正在发送中或未连接)")
                        } else {
                            val ok = ble.sendImage(data.node.addr, processedData, w, h, mode, imgMode)
                            if (ok) {
                                pendingSaveBitmap = previewBmp
                                pendingSaveAddrs = listOf(data.node.addr)
                                addLog("→ 图片 ${w}x${h} → 0x${h4(data.node.addr)} ${processedData.size}B$rleTip ${if (mode == BleManager.ImageSendMode.FAST) "快速" else "ACK"} ${data.node.hops}跳")
                            } else addLog("图片发送失败 (正在发送中或未连接)")
                        }
                        imgPreviewData = null; imgTargetNode = null; mcastSendTargets = emptyList()
                    }
                )
            }

            LaunchedEffect(imgSendState) {
                val curState = imgSendState
                val bmp = pendingSaveBitmap
                if (bmp != null) {
                    when (curState) {
                        is BleManager.ImageSendState.Done -> {
                            if (curState.success) {
                                for (addr in pendingSaveAddrs) {
                                    lastSentBitmaps = lastSentBitmaps + (addr to bmp)
                                    NodeImageStore.save(appContext, addr, bmp)
                                }
                            }
                            pendingSaveBitmap = null; pendingSaveAddrs = emptyList()
                        }
                        is BleManager.ImageSendState.MulticastDone -> {
                            val successAddrs = curState.results.filter { it.value == 0 }.keys
                            for (addr in successAddrs) {
                                lastSentBitmaps = lastSentBitmaps + (addr to bmp)
                                NodeImageStore.save(appContext, addr, bmp)
                            }
                            pendingSaveBitmap = null; pendingSaveAddrs = emptyList()
                        }
                        is BleManager.ImageSendState.Cancelled -> {
                            pendingSaveBitmap = null; pendingSaveAddrs = emptyList()
                        }
                        else -> {}
                    }
                }
                if (curState is BleManager.ImageSendState.Done || curState is BleManager.ImageSendState.Cancelled
                    || curState is BleManager.ImageSendState.MulticastDone) {
                    // queryTopology() 是非挂起函数，发出 BLE 包后立即返回，响应由 BleManager 异步处理
                    // 必须在 resetImageSendState() 之前调用，否则 LaunchedEffect 重新触发导致协程被取消
                    if (ble.queryTopology()) addLog("→ 传输后自动刷新拓扑")
                    kotlinx.coroutines.delay(3000) // 卡片固定展示 3s，拓扑在此期间异步刷新
                    ble.resetImageSendState()
                }
            }
        }
    }
}

/* ════════════════════════════════════════════════════════════
 *  星闪 (NearLink/SLE) 图标 — 4 角星形
 * ════════════════════════════════════════════════════════════ */

@Composable
fun NearLinkIcon(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier = modifier) {
        val path = androidx.compose.ui.graphics.Path()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r1 = size.minDimension / 2f   // 外半径
        val r2 = size.minDimension / 9f   // 内半径（尖锐4角星）
        for (i in 0 until 8) {
            val angle = Math.PI * i / 4.0 - Math.PI / 2.0
            val r = if (i % 2 == 0) r1 else r2
            val x = cx + (r * Math.cos(angle)).toFloat()
            val y = cy + (r * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

/* ════════════════════════════════════════════════════════════
 *  扫描页
 * ════════════════════════════════════════════════════════════ */

@Composable
fun ScanPage(scanning: Boolean, devices: List<BleManager.ScannedDevice>, onScan: () -> Unit,
             debugText: String, crashLog: String, onPick: (BleManager.ScannedDevice) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)), shape = RoundedCornerShape(8.dp)) {
            Text(debugText, Modifier.padding(8.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFFFEB3B))
            if (crashLog.isNotEmpty()) Text(crashLog, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFFF5252))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onScan, enabled = !scanning, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
            if (scanning) { CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("扫描中...") }
            else { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("扫描 Mesh 网关") }
        }
        Spacer(Modifier.height(16.dp))
        if (devices.isEmpty() && !scanning) Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { Text("点击上方按钮扫描\n自动查找 SLE_GW_XXXX 设备", color = Color.Gray, textAlign = TextAlign.Center, lineHeight = 24.sp) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { dev ->
                Card(Modifier.fillMaxWidth().clickable { onPick(dev) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        NearLinkIcon(modifier = Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(dev.name, fontWeight = FontWeight.Bold, color = Color.White); Text(dev.address, fontSize = 12.sp, color = Color.Gray) }
                        Text("${dev.rssi} dBm", fontSize = 12.sp, color = if (dev.rssi > -65) Color(0xFF4CAF50) else Color(0xFFFFA726))
                    }
                }
            }
        }
    }
}

/* ════════════════════════════════════════════════════════════
 *  连接页 (不变)
 * ════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectedPage(
    devName: String, gwAddr: Int, nodes: List<MeshNode>, logs: List<LogEntry>, debugText: String,
    topoQuerying: Boolean, cccdReady: Boolean, imgSendState: BleManager.ImageSendState,
    multicastSelected: Set<Int> = emptySet(),
    onQueryTopo: () -> Unit, onDisconnect: () -> Unit, onNodeClick: (MeshNode) -> Unit,
    onCancelImage: () -> Unit,
    onToggleMulticast: (Int) -> Unit = {}, onMulticastSend: () -> Unit = {},
    onNodeLongPress: (MeshNode) -> Unit = {}
) {
    val inMulticastMode = multicastSelected.isNotEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)), shape = RoundedCornerShape(8.dp)) {
            Text(debugText, Modifier.padding(8.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFFFEB3B))
        }
        ImageProgressBar(imgSendState, onCancelImage)
        Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.25f)), shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BluetoothConnected, null, tint = Color(0xFF4CAF50)); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) { Text(devName, fontWeight = FontWeight.Bold, color = Color.White); if (gwAddr != 0) Text("网关 0x${h4(gwAddr)}", fontSize = 12.sp, color = Color(0xFF81C784)) }
                IconButton(onClick = onDisconnect) { Icon(Icons.Default.Close, "断开", tint = Color(0xFFEF5350)) }
            }
        }
        Button(onClick = onQueryTopo, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), enabled = cccdReady && !topoQuerying && !isImageBusy(imgSendState), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
            if (!cccdReady) { CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)); Text("等待就绪...") }
            else if (topoQuerying) { CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)); Text("查询中...") }
            else if (isImageBusy(imgSendState)) { Text("图片传输中...", color = Color.Gray) }
            else { Icon(Icons.Default.AccountTree, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("查询节点") }
        }
        if (nodes.isNotEmpty()) {
            Text("  Mesh 节点 (${nodes.size})", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                nodes.chunked(2).forEach { rowNodes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowNodes.forEach { node ->
                            val isSelected = node.addr in multicastSelected
                            Card(
                                Modifier.weight(1f).combinedClickable(
                                    onClick = {
                                        if (inMulticastMode) onToggleMulticast(node.addr)
                                        else onNodeClick(node)
                                    },
                                    onLongClick = { onNodeLongPress(node) }
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFF9C27B0).copy(alpha = 0.2f)
                                                     else MaterialTheme.colorScheme.surface
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(if (node.hops == 0) Color(0xFF4CAF50).copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), Alignment.Center) {
                                        Icon(if (node.hops == 0) Icons.Default.Router else Icons.Default.Memory, null, tint = if (node.hops == 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("0x${h4(node.addr)}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White, fontSize = 13.sp)
                                        Text(when (node.hops) { 0 -> "网关"; 1 -> "直连"; else -> "${node.hops} 跳" }, fontSize = 11.sp, color = when (node.hops) { 0 -> Color(0xFF4CAF50); 1 -> Color(0xFF4CAF50); else -> Color(0xFFFFA726) })
                                    }
                                    if (inMulticastMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { onToggleMulticast(node.addr) },
                                            modifier = Modifier.size(20.dp),
                                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF9C27B0), uncheckedColor = Color.Gray)
                                        )
                                    }
                                }
                            }
                        }
                        if (rowNodes.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (multicastSelected.isNotEmpty()) {
            Button(
                onClick = onMulticastSend,
                enabled = !isImageBusy(imgSendState),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
            ) {
                Icon(Icons.Default.Image, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("组播发送到 ${multicastSelected.size} 个节点")
            }
        }
        Text("  通信日志", Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        LazyColumn(Modifier.heightIn(max = 300.dp).padding(horizontal = 12.dp)) {
            items(logs.reversed()) { entry ->
                Text("${entry.time}  ${entry.msg}", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = when { entry.msg.startsWith("→") -> Color(0xFF64B5F6); entry.msg.startsWith("←") -> Color(0xFF81C784); else -> Color(0xFFBDBDBD) }, modifier = Modifier.padding(vertical = 1.dp))
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/* ════════════════════════════════════════════════════════════
 *  动画条纹进度条 (向右流动)
 * ════════════════════════════════════════════════════════════ */

@Composable
fun AnimatedStripeBar(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4FC3F7),
    trackColor: Color = Color.Gray.copy(alpha = 0.3f),
    barHeight: Float = 4f,
    stripeWidth: Float = 24f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "stripe")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = stripeWidth * 2,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "stripeOffset"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight.dp)
            .clip(RoundedCornerShape(2.dp))
    ) {
        val w = size.width
        val h = size.height
        // 背景轨道
        drawRect(color = trackColor, size = Size(w, h))
        // 绘制斜条纹
        clipRect(0f, 0f, w, h) {
            val step = stripeWidth * 2
            var x = -step + (offset % step)
            while (x < w + step) {
                // 绘制斜向平行四边形作为条纹
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, h)
                    lineTo(x + stripeWidth * 0.5f, 0f)
                    lineTo(x + stripeWidth * 1.5f, 0f)
                    lineTo(x + stripeWidth, h)
                    close()
                }
                drawPath(path, color = color)
                x += step
            }
        }
    }
}

/* ════════════════════════════════════════════════════════════
 *  图片传输进度条
 * ════════════════════════════════════════════════════════════ */

@Composable
fun ImageProgressBar(state: BleManager.ImageSendState, onCancel: () -> Unit) {
    when (state) {
        is BleManager.ImageSendState.Idle -> {}
        is BleManager.ImageSendState.Sending -> {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A237E).copy(alpha = 0.4f)), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("发送图片 ${state.seq}/${state.total}", fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f))
                        Text("${state.percent}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7))
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "取消", tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = Color(0xFF4FC3F7), trackColor = Color.Gray.copy(alpha = 0.3f))
                }
            }
        }
        is BleManager.ImageSendState.WaitingAck -> {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A237E).copy(alpha = 0.4f)), shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFFFFA726), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                    val p = if (state.total > 0) state.seq * 100 / state.total else 0
                    Text("等待ACK ${state.seq + 1}/${state.total} ($p%)", fontSize = 12.sp, color = Color(0xFFFFA726), modifier = Modifier.weight(1f))
                    IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "取消", tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp)) }
                }
            }
        }
        is BleManager.ImageSendState.MeshTransfer -> {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D47A1).copy(alpha = 0.4f)), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.total > 0) {
                            Text("Mesh${state.phaseText} ${state.rxCount}/${state.total}", fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f))
                        } else {
                            Text("等待网关通知...", fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "取消", tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    AnimatedStripeBar(color = if (state.phase == 0) Color(0xFF4FC3F7) else Color(0xFFCE93D8))
                }
            }
        }
        is BleManager.ImageSendState.Finishing -> {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A237E).copy(alpha = 0.4f)), shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF81C784), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("数据已发送完毕, 等待确认...", fontSize = 12.sp, color = Color(0xFF81C784))
                }
            }
        }
        is BleManager.ImageSendState.Done -> {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = if (state.success) Color(0xFF1B5E20).copy(alpha = 0.3f) else Color(0xFFB71C1C).copy(alpha = 0.3f)), shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.success) Icons.Default.CheckCircle else Icons.Default.Error, null, tint = if (state.success) Color(0xFF4CAF50) else Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(state.msg, fontSize = 12.sp, color = if (state.success) Color(0xFF81C784) else Color(0xFFEF9A9A))
                }
            }
        }
        is BleManager.ImageSendState.Cancelled -> {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE65100).copy(alpha = 0.3f)), shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cancel, null, tint = Color(0xFFFFA726), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(state.msg, fontSize = 12.sp, color = Color(0xFFFFCC02))
                }
            }
        }
        is BleManager.ImageSendState.MulticastTransfer -> {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF4A148C).copy(alpha = 0.4f)), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("组播传输中 ${state.completedCount}/${state.totalTargets}", fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "取消", tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    AnimatedStripeBar(color = Color(0xFFCE93D8))
                }
            }
        }
        is BleManager.ImageSendState.MulticastDone -> {
            val successCount = state.results.count { it.value == 0 }
            val allSuccess = successCount == state.results.size
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = if (allSuccess) Color(0xFF1B5E20).copy(alpha = 0.3f) else Color(0xFFB71C1C).copy(alpha = 0.3f)), shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (allSuccess) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (allSuccess) Color(0xFF4CAF50) else Color(0xFFFFA726), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("组播完成 $successCount/${state.results.size}", fontSize = 12.sp, color = if (allSuccess) Color(0xFF81C784) else Color(0xFFEF9A9A))
                }
            }
        }
    }
}

/* ════════════════════════════════════════════════════════════
 *  节点操作选择 — 左右并排居中
 * ════════════════════════════════════════════════════════════ */

@Composable
fun NodeActionDialog(node: MeshNode, lastSentBitmap: Bitmap? = null, onDismiss: () -> Unit, onSendText: () -> Unit, onSendImage: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("0x${h4(node.addr)}", color = Color.White) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(when (node.hops) { 0 -> "网关 (本机)"; 1 -> "直连节点"; else -> "${node.hops} 跳路由" }, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.align(Alignment.Start))
                Spacer(Modifier.height(12.dp))

                // 显示最后一次发送的二值化图片
                if (lastSentBitmap != null) {
                    Text("上次发送的图片", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.align(Alignment.Start))
                    Spacer(Modifier.height(6.dp))
                    // 限制预览图最大高度，避免竖版图片在对话框中过高
                    val maxPrevH = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
                    Box(
                        Modifier.fillMaxWidth(0.6f)
                            .heightIn(max = maxPrevH)
                            .aspectRatio(
                                lastSentBitmap.width.toFloat() / lastSentBitmap.height,
                                matchHeightConstraintsFirst = lastSentBitmap.height > lastSentBitmap.width
                            )
                            .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp)).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(lastSentBitmap.asImageBitmap(), "上次发送", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                } else {
                    Text("暂无发送记录", fontSize = 11.sp, color = Color.Gray.copy(alpha = 0.5f))
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Card(Modifier.weight(1f).clickable { onSendText() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.TextFields, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("发送文本", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    Card(Modifier.weight(1f).clickable { onSendImage() }, colors = CardDefaults.cardColors(containerColor = Color(0xFF9C27B0).copy(alpha = 0.15f)), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Image, null, tint = Color(0xFFCE93D8), modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("发送图片", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Color.Gray) } }
    )
}

/* ════════════════════════════════════════════════════════════
 *  裁剪对话框 — 交互式裁剪框
 * ════════════════════════════════════════════════════════════ */

@Composable
fun CropImageDialog(originalBitmap: Bitmap, node: MeshNode, multicastCount: Int = 0, onDismiss: () -> Unit,
                    onConfirm: (Bitmap, ImageResolution) -> Unit) {

    var selectedRes by remember { mutableStateOf(IMG_RESOLUTIONS[0]) }

    // 旋转后的图片（顺时针每次 90°）
    var rotatedBitmap by remember { mutableStateOf(originalBitmap) }

    // 裁剪框 — 归一化坐标 [0,1] 相对于图片
    // normAR = 裁剪框在归一化空间中的宽高比: (right-left)/(bottom-top)
    val imgW = rotatedBitmap.width.toFloat()
    val imgH = rotatedBitmap.height.toFloat()
    fun calcNormAR(res: ImageResolution) = res.width.toFloat() * imgH / (res.height.toFloat() * imgW)

    var cropL by remember { mutableFloatStateOf(0f) }
    var cropT by remember { mutableFloatStateOf(0f) }
    var cropR by remember { mutableFloatStateOf(1f) }
    var cropB by remember { mutableFloatStateOf(1f) }

    // 当分辨率或图片旋转变化时重置裁剪框
    LaunchedEffect(selectedRes, rotatedBitmap) {
        val nar = calcNormAR(selectedRes)
        if (imgW / imgH > selectedRes.width.toFloat() / selectedRes.height) {
            val cw = nar; cropL = (1f - cw) / 2f; cropR = cropL + cw; cropT = 0f; cropB = 1f
        } else {
            val ch = 1f / nar; cropT = (1f - ch) / 2f; cropB = cropT + ch; cropL = 0f; cropR = 1f
        }
    }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val normAR = calcNormAR(selectedRes)
    val handleRadius = 40f  // 触摸热区 (px)

    // 图片画布最大高度：屏幕高度 90% 减去标题/分辨率选择/按钮等固定高度约 210dp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val maxImageHeight = (screenHeightDp * 0.9f - 220.dp).coerceAtLeast(160.dp)
    val isPortraitImage = imgH > imgW

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth(0.92f), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))) {
            Column(Modifier.padding(16.dp)) {
                Text(if (multicastCount > 0) "裁剪图片 → 组播 $multicastCount 个节点" else "裁剪图片 → 0x${h4(node.addr)}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("拖动裁剪框调整区域，拖动四角缩放", fontSize = 11.sp, color = Color.Gray)
                Spacer(Modifier.height(12.dp))

                // 分辨率选择
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("比例", fontSize = 12.sp, color = Color(0xFFB0BEC5))
                    IMG_RESOLUTIONS.forEach { res ->
                        FilterChip(selected = selectedRes == res, onClick = { selectedRes = res },
                            label = { Text(res.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), selectedLabelColor = Color.White))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 图片 + 裁剪框叠加
                // 竖版图片：以 maxImageHeight 为高度上限，宽度由宽高比推算（避免超出屏幕）
                // 横版图片：以对话框宽度为准，高度由宽高比决定（行为与原来一致）
                Box(
                    Modifier
                        .heightIn(max = maxImageHeight)
                        .aspectRatio(imgW / imgH, matchHeightConstraintsFirst = isPortraitImage)
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(8.dp))
                        .onSizeChanged { boxSize = it }
                ) {
                    // 修复：显示旋转后的图片
                    Image(bitmap = rotatedBitmap.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)

                    val bw = boxSize.width.toFloat()
                    val bh = boxSize.height.toFloat()

                    // 拖动手势
                    var dragMode by remember { mutableIntStateOf(0) } // 0=none 1=move 2=TL 3=TR 4=BL 5=BR
                    val primaryColor = MaterialTheme.colorScheme.primary
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(normAR, boxSize) {
                            if (bw <= 0f || bh <= 0f) return@pointerInput
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val corners = listOf(
                                        Offset(cropL * bw, cropT * bh),
                                        Offset(cropR * bw, cropT * bh),
                                        Offset(cropL * bw, cropB * bh),
                                        Offset(cropR * bw, cropB * bh)
                                    )
                                    val dists = corners.map { (pos - it).getDistance() }
                                    val minIdx = dists.indices.minByOrNull { dists[it] } ?: -1
                                    dragMode = if (minIdx >= 0 && dists[minIdx] < handleRadius * 2.5f) {
                                        minIdx + 2  // 2=TL 3=TR 4=BL 5=BR
                                    } else if (pos.x in cropL * bw..cropR * bw && pos.y in cropT * bh..cropB * bh) {
                                        1 // move
                                    } else 0
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    val dx = drag.x / bw
                                    val dy = drag.y / bh
                                    val minS = 0.08f  // 最小裁剪尺寸

                                    when (dragMode) {
                                        1 -> { // move
                                            val w = cropR - cropL; val h = cropB - cropT
                                            var nl = cropL + dx; var nt = cropT + dy
                                            nl = nl.coerceIn(0f, 1f - w); nt = nt.coerceIn(0f, 1f - h)
                                            cropL = nl; cropT = nt; cropR = nl + w; cropB = nt + h
                                        }
                                        2 -> { // TL — 锚点 BR
                                            var nl = (cropL + dx).coerceIn(0f, cropR - minS)
                                            var w = cropR - nl; var h = w / normAR
                                            var nt = cropB - h
                                            if (nt < 0f) { nt = 0f; h = cropB; w = h * normAR; nl = cropR - w }
                                            if (w >= minS && h >= minS) { cropL = nl; cropT = nt }
                                        }
                                        3 -> { // TR — 锚点 BL
                                            var nr = (cropR + dx).coerceIn(cropL + minS, 1f)
                                            var w = nr - cropL; var h = w / normAR
                                            var nt = cropB - h
                                            if (nt < 0f) { nt = 0f; h = cropB; w = h * normAR; nr = cropL + w }
                                            if (w >= minS && h >= minS) { cropR = nr; cropT = nt }
                                        }
                                        4 -> { // BL — 锚点 TR
                                            var nl = (cropL + dx).coerceIn(0f, cropR - minS)
                                            var w = cropR - nl; var h = w / normAR
                                            var nb = cropT + h
                                            if (nb > 1f) { nb = 1f; h = nb - cropT; w = h * normAR; nl = cropR - w }
                                            if (w >= minS && h >= minS) { cropL = nl; cropB = nb }
                                        }
                                        5 -> { // BR — 锚点 TL
                                            var nr = (cropR + dx).coerceIn(cropL + minS, 1f)
                                            var w = nr - cropL; var h = w / normAR
                                            var nb = cropT + h
                                            if (nb > 1f) { nb = 1f; h = nb - cropT; w = h * normAR; nr = cropL + w }
                                            if (w >= minS && h >= minS) { cropR = nr; cropB = nb }
                                        }
                                    }
                                },
                                onDragEnd = { dragMode = 0 }
                            )
                        }
                    ) {
                        if (bw <= 0f || bh <= 0f) return@Canvas
                        val cL = cropL * bw; val cT = cropT * bh
                        val cR = cropR * bw; val cB = cropB * bh

                        // 半透明遮罩 (裁剪区域外)
                        val mask = Color.Black.copy(alpha = 0.55f)
                        drawRect(mask, Offset.Zero, Size(bw, cT))                       // 上
                        drawRect(mask, Offset(0f, cB), Size(bw, bh - cB))               // 下
                        drawRect(mask, Offset(0f, cT), Size(cL, cB - cT))               // 左
                        drawRect(mask, Offset(cR, cT), Size(bw - cR, cB - cT))          // 右

                        // 裁剪框白色边框
                        drawRect(Color.White, Offset(cL, cT), Size(cR - cL, cB - cT), style = Stroke(2.dp.toPx()))

                        // 三分线
                        val w3 = (cR - cL) / 3f; val h3 = (cB - cT) / 3f
                        for (i in 1..2) {
                            drawLine(Color.White.copy(alpha = 0.3f), Offset(cL + w3 * i, cT), Offset(cL + w3 * i, cB), strokeWidth = 0.5.dp.toPx())
                            drawLine(Color.White.copy(alpha = 0.3f), Offset(cL, cT + h3 * i), Offset(cR, cT + h3 * i), strokeWidth = 0.5.dp.toPx())
                        }

                        // 四角手柄
                        val hr = 8.dp.toPx()
                        listOf(Offset(cL, cT), Offset(cR, cT), Offset(cL, cB), Offset(cR, cB)).forEach { c ->
                            drawCircle(Color.White, hr, c)
                            drawCircle(primaryColor, hr - 2.dp.toPx(), c)
                        }
                    }
                    // 旋转按钮置于 Canvas 之后，确保触摸层级高于 Canvas，点击可响应
                    IconButton(
                        onClick = {
                            val matrix = Matrix()
                            matrix.postRotate(90f)
                            rotatedBitmap = Bitmap.createBitmap(
                                rotatedBitmap, 0, 0,
                                rotatedBitmap.width, rotatedBitmap.height,
                                matrix, true
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.White, CircleShape)
                            .size(34.dp)
                    ) {
                        Icon(Icons.Default.RotateRight, contentDescription = "旋转",
                            tint = Color(0xFF212121), modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 按钮行：右对齐（取消 / 确认裁剪）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Color.Gray) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val px = (cropL * imgW).toInt().coerceAtLeast(0)
                        val py = (cropT * imgH).toInt().coerceAtLeast(0)
                        val pw = ((cropR - cropL) * imgW).toInt().coerceAtLeast(1).coerceAtMost(imgW.toInt() - px)
                        val ph = ((cropB - cropT) * imgH).toInt().coerceAtLeast(1).coerceAtMost(imgH.toInt() - py)
                        val cropped = Bitmap.createBitmap(rotatedBitmap, px, py, pw, ph)
                        onConfirm(cropped, selectedRes)
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Default.Crop, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("确认裁剪")
                    }
                }
            }
        }
    }
}

/* ════════════════════════════════════════════════════════════
 *  图片预览数据
 * ════════════════════════════════════════════════════════════ */

data class ImagePreviewData(
    val node: MeshNode,
    val croppedBitmap: Bitmap,     // 用户裁剪后的原图
    val resolution: ImageResolution,
    val multicastTargets: List<Int> = emptyList() // 组播目标节点地址 (空=单播)
)

/* ════════════════════════════════════════════════════════════
 *  图片预览 & 发送 — 左右对比 (相同显示大小) + 点击放大
 * ════════════════════════════════════════════════════════════ */

@Composable
fun ImagePreviewDialog(data: ImagePreviewData, onDismiss: () -> Unit,
                       onSend: (ByteArray, Int, Int, BleManager.ImageSendMode, Int, Bitmap) -> Unit) {

    var selectedMode by remember { mutableStateOf(BleManager.ImageSendMode.FAST) }
    var zoomBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var zoomTitle by remember { mutableStateOf("") }

    val processed = remember(data.croppedBitmap, data.resolution) {
        ImageUtils.processFromCropped(data.croppedBitmap, data.resolution.width, data.resolution.height)
    }

    zoomBitmap?.let { ZoomImageDialog(it, zoomTitle) { zoomBitmap = null } }

    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (data.multicastTargets.isNotEmpty()) "组播发送到 ${data.multicastTargets.size} 个节点" else "发送到 0x${h4(data.node.addr)}", color = Color.White, fontSize = 16.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {

                // 左右对比 — 相同显示大小
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 左: 裁剪原图
                    Box(
                        Modifier.weight(1f)
                            .aspectRatio(data.resolution.width.toFloat() / data.resolution.height)
                            .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp)).background(Color.White)
                            .clickable { zoomBitmap = data.croppedBitmap; zoomTitle = "裁剪原图" },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(data.croppedBitmap.asImageBitmap(), "裁剪原图", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }

                    // 右: 二值化
                    Box(
                        Modifier.weight(1f)
                            .aspectRatio(data.resolution.width.toFloat() / data.resolution.height)
                            .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp)).background(Color.White)
                            .clickable { zoomBitmap = processed.previewBitmap; zoomTitle = "二值化效果" },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(processed.previewBitmap.asImageBitmap(), "二值化", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                }

                Spacer(Modifier.height(2.dp))
                val compressionText = if (processed.isCompressed) {
                    val ratio = processed.dataSize * 100 / processed.rawSize
                    "原始${processed.rawSize}B → RLE ${processed.dataSize}B ($ratio%)"
                } else "${processed.dataSize}B"
                Text("点击图片放大 | ${data.resolution.label} | $compressionText | ${processed.packetCount}包",
                    fontSize = 10.sp, color = Color.Gray)

                Spacer(Modifier.height(12.dp))
                if (data.multicastTargets.isEmpty()) {
                    Text("传输模式", fontSize = 12.sp, color = Color(0xFFB0BEC5), modifier = Modifier.align(Alignment.Start))
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = selectedMode == BleManager.ImageSendMode.FAST, onClick = { selectedMode = BleManager.ImageSendMode.FAST },
                            label = { Text("快速", fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.3f), selectedLabelColor = Color.White))
                        FilterChip(selected = selectedMode == BleManager.ImageSendMode.ACK, onClick = { selectedMode = BleManager.ImageSendMode.ACK },
                            label = { Text("逐包确认", fontSize = 12.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFA726).copy(alpha = 0.3f), selectedLabelColor = Color.White))
                    }
                }
            }
        },
        confirmButton = {
            val sizeLabel = if (processed.isCompressed) "${processed.dataSize}B RLE" else "${processed.dataSize}B"
            Button(onClick = { onSend(processed.imageData, data.resolution.width, data.resolution.height, selectedMode, processed.imageMode, processed.previewBitmap) },
                colors = ButtonDefaults.buttonColors(containerColor = if (data.multicastTargets.isNotEmpty()) Color(0xFF9C27B0) else MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Default.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (data.multicastTargets.isNotEmpty()) "组播发送 ($sizeLabel)" else "发送 ($sizeLabel)")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Color.Gray) } }
    )
}

/* ════════════════════════════════════════════════════════════
 *  图片放大查看
 * ════════════════════════════════════════════════════════════ */

@Composable
fun ZoomImageDialog(bitmap: Bitmap, title: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF0A0A14), modifier = Modifier.fillMaxWidth(0.95f),
        title = { Text(title, color = Color.White, fontSize = 14.sp) },
        text = {
            // 限制最大高度，避免竖版图片过高导致对话框溢出
            val maxZoomHeight = (LocalConfiguration.current.screenHeightDp * 0.65f).dp
            Box(Modifier.fillMaxWidth().heightIn(max = maxZoomHeight).border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                Image(bitmap.asImageBitmap(), title, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = MaterialTheme.colorScheme.primary) } }
    )
}

/* ════════════════════════════════════════════════════════════
 *  文本发送对话框
 * ════════════════════════════════════════════════════════════ */

@Composable
fun SendDialog(node: MeshNode, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("发送到 0x${h4(node.addr)}", color = Color.White) },
        text = {
            Column {
                Text(when (node.hops) { 0 -> "网关 (本机)"; 1 -> "直连节点"; else -> "${node.hops} 跳路由" }, fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("输入数据...", color = Color.Gray) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f), focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = MaterialTheme.colorScheme.primary))
            }
        },
        confirmButton = { Button(onClick = { if (text.isNotBlank()) onSend(text) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("发送") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Color.Gray) } })
}

@Composable
fun CenterContent(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(16.dp)); Text(text, color = Color.White)
        }
    }
}

/* ════════════════════════════════════════════════════════════
 *  图片处理工具
 * ════════════════════════════════════════════════════════════ */

data class ProcessedImage(
    val previewBitmap: Bitmap,
    val imageData: ByteArray,
    val dataSize: Int,
    val packetCount: Int,
    val rawSize: Int = dataSize,
    val isCompressed: Boolean = false,
    val imageMode: Int = MeshProtocol.IMG_MODE_H_LSB
)

object ImageUtils {

    /** 从裁剪后的 Bitmap 处理: 缩放 → Floyd-Steinberg 抖动二值化 → 取模 (MSB-first) */
    fun processFromCropped(cropped: Bitmap, targetW: Int, targetH: Int): ProcessedImage {
        val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)

        /* ── 1. 提取灰度矩阵（浮点，便于误差扩散）── */
        val gray = FloatArray(targetW * targetH)
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val pixel = scaled.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                gray[y * targetW + x] = (0.299f * r + 0.587f * g + 0.114f * b)
            }
        }

        /* ── 2. Floyd-Steinberg 抖动二值化 ──
         *  ┌────┬────┬────┐
         *  │    │ ** │ 7  │  /16
         *  ├────┼────┼────┤
         *  │ 3  │ 5  │ 1  │  /16
         *  └────┴────┴────┘
         */
        val bwBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val idx = y * targetW + x
                val oldPx = gray[idx].coerceIn(0f, 255f)
                val newPx = if (oldPx >= 128f) 255f else 0f
                val err = oldPx - newPx
                bwBitmap.setPixel(x, y, if (newPx > 0f) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())

                // 扩散误差给相邻像素
                if (x + 1 < targetW)
                    gray[idx + 1] += err * 7f / 16f
                if (y + 1 < targetH) {
                    if (x > 0)
                        gray[(y + 1) * targetW + (x - 1)] += err * 3f / 16f
                    gray[(y + 1) * targetW + x] += err * 5f / 16f
                    if (x + 1 < targetW)
                        gray[(y + 1) * targetW + (x + 1)] += err * 1f / 16f
                }
            }
        }
        if (scaled !== cropped) scaled.recycle()

        /* ── 3. 1bpp 取模 (MSB-first: pixel 0 → bit 7) ── */
        val bytesPerRow = (targetW + 7) / 8
        val totalBytes = bytesPerRow * targetH
        val data = ByteArray(totalBytes)
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val pixel = bwBitmap.getPixel(x, y)
                val isBlack = (pixel and 0x00FFFFFF) == 0
                if (isBlack) {
                    val byteIndex = y * bytesPerRow + (x / 8)
                    val bitIndex = 7 - (x % 8)    // MSB-first: 与 RLE 编解码器、EPD 驱动一致
                    data[byteIndex] = (data[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                }
            }
        }

        // RLE 压缩: 仅当压缩后更小时使用
        val totalPixels = targetW * targetH
        val compressed = ImageRleEncoder.encode(data, totalPixels)
        val useRle = compressed.size < totalBytes
        val sendData = if (useRle) compressed else data
        val imgMode = if (useRle) MeshProtocol.IMG_MODE_RLE else MeshProtocol.IMG_MODE_H_LSB

        val pktCount = (sendData.size + MeshProtocol.IMG_PKT_PAYLOAD - 1) / MeshProtocol.IMG_PKT_PAYLOAD
        return ProcessedImage(bwBitmap, sendData, sendData.size, pktCount, totalBytes, useRle, imgMode)
    }
}

/* ════════════════════════════════════════════════════════════
 *  工具函数
 * ════════════════════════════════════════════════════════════ */

fun h4(addr: Int): String = String.format("%04X", addr)

fun isImageBusy(state: BleManager.ImageSendState): Boolean =
    state is BleManager.ImageSendState.Sending || state is BleManager.ImageSendState.WaitingAck
            || state is BleManager.ImageSendState.Finishing || state is BleManager.ImageSendState.MeshTransfer
            || state is BleManager.ImageSendState.MulticastTransfer
fun ByteArray.decodeToStringOrHex(): String = try {
    val s = toString(Charsets.UTF_8)
    if (s.all { it.code >= 0x20 && it != '\uFFFD' }) s else toHexString()
} catch (_: Exception) { toHexString() }
