package com.meshgateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var ble: BleManager
    private val handler = Handler(Looper.getMainLooper())

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
            if (result != "OK") {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            }
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

        if (needed.isEmpty()) {
            doScan()
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    // 崩溃日志
    private val _crashLog = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 读取上次崩溃信息
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val lastCrash = prefs.getString("last_crash", "") ?: ""
        if (lastCrash.isNotEmpty()) {
            _crashLog.value = "上次崩溃: $lastCrash"
            prefs.edit().remove("last_crash").apply()
        }

        // 设置崩溃捕获 - 保存到 SharedPrefs
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val msg = "${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTrace.take(5).joinToString("\n") { "  at ${it}" }}"
                getSharedPreferences("crash", MODE_PRIVATE)
                    .edit().putString("last_crash", msg).commit()
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        ble = BleManager(applicationContext)
        ble.init()

        setContent {
            MeshTheme {
                MeshApp(ble = ble, onScan = { scanWithPermission() }, crashLog = _crashLog)
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
            primary = Color(0xFF4FC3F7),
            secondary = Color(0xFF81C784),
            surface = Color(0xFF1E1E2E),
            background = Color(0xFF121220),
            onSurface = Color.White,
            onBackground = Color.White,
        ),
        content = content
    )
}

data class LogEntry(
    val msg: String,
    val time: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshApp(ble: BleManager, onScan: () -> Unit, crashLog: MutableState<String> = mutableStateOf("")) {

    val state by ble.connState.collectAsState()
    val devices by ble.scannedDevices.collectAsState()
    val devName by ble.deviceName.collectAsState()
    val debug by ble.debugInfo.collectAsState()

    var nodes by remember { mutableStateOf(listOf<MeshNode>()) }
    var gwAddr by remember { mutableStateOf(0) }
    var logs by remember { mutableStateOf(listOf<LogEntry>()) }
    var dlgNode by remember { mutableStateOf<MeshNode?>(null) }

    fun addLog(s: String) { logs = (logs + LogEntry(s)).takeLast(50) }

    DisposableEffect(ble) {
        ble.onMessage = { msg ->
            when (msg) {
                is UpstreamMessage.Topology -> {
                    gwAddr = msg.gatewayAddr
                    // 包含网关自身 (hop=0)
                    val allNodes = mutableListOf(MeshNode(msg.gatewayAddr, 0))
                    allNodes.addAll(msg.nodes)
                    nodes = allNodes
                    addLog("拓扑: 网关 0x${h4(msg.gatewayAddr)}, ${msg.nodes.size} 节点")
                }
                is UpstreamMessage.DataFromNode -> {
                    val txt = msg.payload.decodeToStringOrHex()
                    addLog("← [0x${h4(msg.srcAddr)}] $txt")
                }
            }
        }
        onDispose { ble.onMessage = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NearLink Mesh", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    Box(
                        Modifier.padding(end = 16.dp).size(12.dp).clip(CircleShape)
                            .background(
                                when (state) {
                                    BleManager.ConnState.CONNECTED -> Color(0xFF4CAF50)
                                    BleManager.ConnState.CONNECTING -> Color(0xFFFFC107)
                                    BleManager.ConnState.SCANNING -> Color(0xFF2196F3)
                                    else -> Color(0xFF757575)
                                }
                            )
                    )
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)) {
            when (state) {
                BleManager.ConnState.DISCONNECTED,
                BleManager.ConnState.SCANNING ->
                    ScanPage(state == BleManager.ConnState.SCANNING, devices, onScan, debug, crashLog.value) { ble.connect(it) }

                BleManager.ConnState.CONNECTING ->
                    CenterContent("正在连接 $devName ...")

                BleManager.ConnState.CONNECTED ->
                    ConnectedPage(devName, gwAddr, nodes, logs, debug,
                        onQueryTopo = { ble.queryTopology(); addLog("→ 查询拓扑") },
                        onDisconnect = { ble.disconnect() },
                        onNodeClick = { dlgNode = it },
                        onBroadcast = { txt -> ble.broadcast(txt.toByteArray()); addLog("→ [广播] $txt") }
                    )
            }
        }

        dlgNode?.let { node ->
            SendDialog(node,
                onDismiss = { dlgNode = null },
                onSend = { txt ->
                    ble.sendToNode(node.addr, txt.toByteArray())
                    addLog("→ [0x${h4(node.addr)}] $txt")
                    dlgNode = null
                }
            )
        }
    }
}

@Composable
fun ScanPage(
    scanning: Boolean,
    devices: List<BleManager.ScannedDevice>,
    onScan: () -> Unit,
    debugText: String,
    crashLog: String,
    onPick: (BleManager.ScannedDevice) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {

        // 调试信息
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(debugText, Modifier.padding(8.dp),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = Color(0xFFFFEB3B))
            if (crashLog.isNotEmpty()) {
                Text(crashLog, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = Color(0xFFFF5252))
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onScan, enabled = !scanning,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("扫描中...")
            } else {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("扫描 Mesh 网关")
            }
        }

        Spacer(Modifier.height(16.dp))

        if (devices.isEmpty() && !scanning) {
            Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                Text("点击上方按钮扫描\n自动查找 SLE_GW_XXXX 设备",
                    color = Color.Gray, textAlign = TextAlign.Center, lineHeight = 24.sp)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { dev ->
                Card(
                    Modifier.fillMaxWidth().clickable { onPick(dev) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(dev.name, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(dev.address, fontSize = 12.sp, color = Color.Gray)
                        }
                        Text("${dev.rssi} dBm", fontSize = 12.sp,
                            color = if (dev.rssi > -65) Color(0xFF4CAF50) else Color(0xFFFFA726))
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectedPage(
    devName: String, gwAddr: Int, nodes: List<MeshNode>, logs: List<LogEntry>, debugText: String,
    onQueryTopo: () -> Unit, onDisconnect: () -> Unit,
    onNodeClick: (MeshNode) -> Unit, onBroadcast: (String) -> Unit
) {
    var bcastText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {

        // 调试信息
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A3A)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(debugText, Modifier.padding(8.dp),
                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = Color(0xFFFFEB3B))
        }

        // === 固定区域：设备信息 + 查询按钮 + 节点列表 + 广播输入 ===
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.25f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BluetoothConnected, null, tint = Color(0xFF4CAF50))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(devName, fontWeight = FontWeight.Bold, color = Color.White)
                    if (gwAddr != 0) Text("网关 0x${h4(gwAddr)}", fontSize = 12.sp, color = Color(0xFF81C784))
                }
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Default.Close, "断开", tint = Color(0xFFEF5350))
                }
            }
        }

        Button(
            onClick = onQueryTopo,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.AccountTree, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("查询节点")
        }

        // 节点列表（固定，不滚动，两列网格）
        if (nodes.isNotEmpty()) {
            Text("  Mesh 节点 (${nodes.size})",
                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                nodes.chunked(2).forEach { rowNodes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowNodes.forEach { node ->
                            Card(
                                Modifier.weight(1f).clickable { onNodeClick(node) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (node.hops == 0) Color(0xFF4CAF50).copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        ), Alignment.Center) {
                                        Icon(
                                            if (node.hops == 0) Icons.Default.Router else Icons.Default.Memory,
                                            null,
                                            tint = if (node.hops == 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("0x${h4(node.addr)}", fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace, color = Color.White, fontSize = 13.sp)
                                        Text(
                                            when (node.hops) {
                                                0 -> "网关"
                                                1 -> "直连"
                                                else -> "${node.hops} 跳"
                                            },
                                            fontSize = 11.sp,
                                            color = when (node.hops) {
                                                0 -> Color(0xFF4CAF50)
                                                1 -> Color(0xFF4CAF50)
                                                else -> Color(0xFFFFA726)
                                            }
                                        )
                                    }
                                    Icon(Icons.Default.Send, "发送", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        // 如果奇数个节点，右边补空位
                        if (rowNodes.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 广播输入框（固定）
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = bcastText, onValueChange = { bcastText = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("输入广播数据...", color = Color.Gray) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f), focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White, cursorColor = MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { if (bcastText.isNotBlank()) { onBroadcast(bcastText); bcastText = "" } }) {
                    Icon(Icons.Default.Send, "广播", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // === 通信日志（独立滚动区域） ===
        Text("  通信日志", Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(logs.reversed()) { entry ->
                Text("${entry.time}  ${entry.msg}", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = when { entry.msg.startsWith("→") -> Color(0xFF64B5F6); entry.msg.startsWith("←") -> Color(0xFF81C784); else -> Color(0xFFBDBDBD) },
                    modifier = Modifier.padding(vertical = 1.dp))
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun SendDialog(node: MeshNode, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("发送到 0x${h4(node.addr)}", color = Color.White) },
        text = {
            Column {
                Text(when (node.hops) { 0 -> "网关 (本机)"; 1 -> "直连节点"; else -> "${node.hops} 跳路由" }, fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it },
                    placeholder = { Text("输入数据...", color = Color.Gray) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f), focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White, cursorColor = MaterialTheme.colorScheme.primary))
            }
        },
        confirmButton = { Button(onClick = { if (text.isNotBlank()) onSend(text) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("发送") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Color.Gray) } })
}

@Composable
fun CenterContent(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(text, color = Color.White)
        }
    }
}

fun h4(addr: Int): String = String.format("%04X", addr)
fun ByteArray.decodeToStringOrHex(): String = try {
    val s = toString(Charsets.UTF_8)
    // 接受可打印ASCII和常见Unicode字符（包括中文）
    if (s.all { it.code >= 0x20 && it != '\uFFFD' }) s else toHexString()
} catch (_: Exception) { toHexString() }
