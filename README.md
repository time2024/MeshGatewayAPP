# NearLink Mesh Gateway App

基于 BLE 的星闪（NearLink）SLE Mesh 网络调试与控制工具，适用于 Android 平台。

通过 BLE 连接任意一块运行网关固件的 Hi3863 开发板，即可查看整个 Mesh 网络拓扑、向指定节点发送数据或进行全网广播。

---

## 功能概览

- **网关扫描**：自动发现所有 `sle_gw_XXXX` BLE 网关设备，显示名称、MAC 地址和信号强度
- **一键连接**：点击任意网关即可建立 BLE GATT 连接，连接后自动查询拓扑
- **拓扑可视化**：2×2 网格展示所有 Mesh 节点，区分网关（0 跳）、直连（1 跳）和多跳路由节点
- **单播通信**：点击任意节点卡片，弹出对话框输入数据后定向发送
- **全网广播**：底部输入框一键向所有节点广播消息
- **通信日志**：实时滚动显示上下行通信记录，带时间戳和颜色区分

## 页面功能

| 扫描页面 | 连接页面 |
|---------|---------|
| 扫描并列出所有 Mesh 网关设备 | 查看拓扑、发送单播/广播 |

## 系统要求

- Android 8.0（API 26）及以上
- 支持 BLE 4.0+ 的设备
- 需授予蓝牙扫描、连接和定位权限

## 硬件配合

本 App 配合海思 Hi3863 星闪开发板使用，固件需包含以下模块：

| 固件模块 | 说明 |
|---------|------|
| `mesh_main.c` | SLE Mesh 组网核心，HELLO 路由学习 |
| `mesh_transport.c` | SLE 多连接传输层（1 主 8 从） |
| `mesh_forward.c` | 单播转发与广播洪泛 |
| `mesh_route.c` | 2 跳路由表管理 |
| `ble_gateway.c` | BLE GATT Server，桥接手机与 Mesh |

每块开发板同时运行 SLE（星闪近场通信）和 BLE 双协议栈，SLE 用于板间 Mesh 组网，BLE 用于手机连接。

## 通信协议

App 与网关之间通过 BLE GATT 自定义 Service（UUID `0xFFE0`）通信：

### 下行（手机 → 网关）

| 帧格式 | 说明 |
|-------|------|
| `AA 01 DST_HI DST_LO LEN PAYLOAD` | 单播到指定节点 |
| `AA 02 FF FF LEN PAYLOAD` | 广播到所有节点 |
| `AA 03` | 查询 Mesh 拓扑 |

### 上行（网关 → 手机）

| 帧格式 | 说明 |
|-------|------|
| `AA 81 SRC_HI SRC_LO LEN PAYLOAD` | 某节点发来的数据 |
| `AA 83 GW_HI GW_LO COUNT [ADDR_HI ADDR_LO HOPS]...` | 拓扑响应 |

### GATT 特征

| UUID | 方向 | 说明 |
|------|------|------|
| `0xFFE1` | Notify | 网关 → 手机（上行数据） |
| `0xFFE2` | Write | 手机 → 网关（下行指令） |

## 项目结构

```
MeshGatewayApp/
├── app/src/main/
│   ├── AndroidManifest.xml          # 权限声明
│   └── java/com/meshgateway/
│       ├── MainActivity.kt          # Compose UI（扫描页 + 连接页 + 对话框）
│       ├── BleManager.kt            # BLE 扫描、连接、GATT 读写、Notify 订阅
│       └── MeshProtocol.kt          # 协议帧编解码、数据模型
├── app/build.gradle.kts             # 依赖配置
├── build.gradle.kts                 # 项目级构建
├── settings.gradle.kts
└── gradle/
```

### 核心模块说明

**`BleManager.kt`** — BLE 通信管理

- 扫描过滤 `SLE_GW_` 前缀设备，按名称去重（解决多板共享 MAC 问题）
- GATT 连接、MTU 协商（247 字节）、服务发现
- `0xFFE1` Notify 订阅 + `0xFFE2` Write 下发
- 连接状态通过 StateFlow 暴露给 UI 层

**`MeshProtocol.kt`** — 协议编解码

- `buildUnicast()` / `buildBroadcast()` / `buildTopoQuery()` 构造下行帧
- `parseNotification()` 解析上行 Notify 数据，分发为 `Topology` 或 `DataFromNode`

**`MainActivity.kt`** — Jetpack Compose UI

- `ScanPage`：扫描按钮 + 设备列表
- `ConnectedPage`：设备信息卡片 + 节点网格 + 广播输入 + 通信日志
- `SendDialog`：单播发送对话框
- 连接后自动查询拓扑（`LaunchedEffect`），拓扑返回后更新设备名

## 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 1.9.x |
| Jetpack Compose | BOM 2024.08 |
| Material 3 | Compose Material3 |
| minSdk | 26 (Android 8.0) |
| targetSdk | 35 (Android 15) |
| 构建工具 | Gradle Kotlin DSL |

## 构建

```bash
# 克隆项目
git clone <repo_url>
cd MeshGatewayApp

# Android Studio 打开后自动同步 Gradle

# 或命令行构建
./gradlew assembleDebug

# 输出 APK 位于
# app/build/outputs/apk/debug/app-debug.apk
```

## 使用流程

1. 将 Mesh 固件烧录到各 Hi3863 开发板并上电
2. 等待各板完成 SLE Mesh 组网（约 10 秒）
3. 打开 App，点击「扫描 Mesh 网关」
4. 从列表中选择一个 `sle_gw_XXXX` 设备连接
5. App 自动查询拓扑，网格中显示所有 Mesh 节点
6. 点击节点卡片可发送单播数据
7. 底部输入框可发送全网广播

## 设计要点

**按名称去重扫描**：各开发板 BLE MAC 由 Mesh 地址派生（`CC:BB:AA:00:XX:XX`），但作为双重保险，App 扫描列表按 `sle_gw_XXXX` 名称去重而非 MAC 地址，确保多板场景下每块板独立显示。

**连接后自动校准**：用户在扫描页点击的设备名可能与实际连接的网关不一致（BLE 广播竞争），连接成功后 App 自动发送拓扑查询，用返回的实际网关地址更新显示名。

**UTF-8 全支持**：数据载荷支持中文等 Unicode 字符，日志区自动判断可打印文本或回退 Hex 显示。

## License

MIT
