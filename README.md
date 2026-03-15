<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%208.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Platform"/>
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Language"/>
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="UI"/>
  <img src="https://img.shields.io/badge/BLE-GATT%20Client-0082FC?style=for-the-badge&logo=bluetooth&logoColor=white" alt="BLE"/>
  <img src="https://img.shields.io/badge/Protocol-SLE%20Mesh%20v3-00b96b?style=for-the-badge" alt="Protocol"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey?style=for-the-badge" alt="License"/>
</p>

<h1 align="center">📱 MeshGateway — 星闪 SLE Mesh 手机图传控制端</h1>

<p align="center">
  <b>配套 <a href="https://github.com/time2024/NearLink-Mesh-ePaper">SLE Mesh 固件</a> 的 Android 控制 App</b><br>
  手机 ←BLE→ 网关 ←SLE Mesh 多跳→ 7.3 寸六色电子墨水屏
</p>

<p align="center">
  <a href="#-核心特性">特性</a> •
  <a href="#-系统架构">架构</a> •
  <a href="#-BLE-协议">协议</a> •
  <a href="#-图片处理管线">图片处理</a> •
  <a href="#-快速开始">快速开始</a> •
  <a href="#-项目结构">结构</a> •
  <a href="#-常见问题">FAQ</a>
</p>

---

## 📖 项目简介

**MeshGateway** 是 [SLE Mesh 星闪自组网图传系统](https://github.com/time2024/NearLink-Mesh-ePaper) 的**配套 Android 控制端**，基于 Kotlin + Jetpack Compose 开发。通过 BLE 连接星闪网关节点，实现对 SLE Mesh 网络中多块电子墨水屏的**图片裁剪、编码、传输和组播同步控制**。

> **🎯 一句话概括：** 选图 → 交互式裁剪 → 六色抖动/JPEG/RLE 编码 → BLE 分包发送 → Mesh 多跳中继 → 墨水屏刷新

### 应用信息

| 项目 | 值 |
|------|-----|
| **包名** | `com.meshgateway` |
| **版本** | 1.26 |
| **最低 SDK** | Android 8.0 (API 26) |
| **目标 SDK** | Android 15 (API 35) |
| **UI 框架** | Jetpack Compose + Material 3 |
| **架构** | MVVM + StateFlow |
| **代码量** | ~3,500 行 Kotlin |
| **外部依赖** | 零 (纯 AndroidX，无第三方网络库) |

<details>
<summary><b>English Summary</b></summary>

**MeshGateway** is the companion Android app for the [SLE Mesh networking system](https://github.com/time2024/NearLink-Mesh-ePaper). Built with Kotlin and Jetpack Compose, it connects to a SparkLink (NearLink) gateway node via BLE and provides interactive image cropping, three encoding formats (4bpp 6-color dithering / JPEG / RLE), FAST and ACK transfer modes, multicast delivery to up to 8 e-ink displays, real-time topology visualization, and a complete 11-state transfer state machine.

</details>

---

## ✨ 核心特性

<table>
<tr>
<td width="50%">

**🔗 BLE 连接管理**
- 自动扫描并筛选 `SLE_GW_XXXX` 网关设备
- 动态 MTU 请求 (247 字节) + GATT 服务发现
- CCCD 通知/指示自动订阅
- 串行写入队列保证消息顺序 + 背压处理
- 连接状态四态机：DISCONNECTED → SCANNING → CONNECTING → CONNECTED
- Android 12+ / 旧版权限自适应

</td>
<td width="50%">

**🎨 图片处理管线**
- **交互式裁剪**：四角缩放 (对角锚点保持宽高比) + 拖动平移 + 实时旋转
- **三分线辅助** + 屏幕自适应布局
- **两种分辨率**：240×360 (小屏) / 480×800 (大屏)
- **三种编码**：
  - 4bpp 六色抖动 (Floyd-Steinberg → 黑/白/黄/红/蓝/绿)
  - JPEG 自适应压缩 (初始 Q=40，自动降质 ≤43KB)
  - RLE 游程编码 (1bpp 二值图，与固件完全兼容)
- **原图 vs 墨水屏效果**实时对比预览

</td>
</tr>
<tr>
<td width="50%">

**📡 Mesh 协议 (v1~v3)**
- **单播** (0x01)：发送数据到指定 Mesh 节点
- **广播** (0x02)：全网洪泛消息
- **拓扑查询** (0x03/0x83)：获取网关地址 + 全网节点列表
- **图传协议** (0x04~0x06)：START/DATA/END 三阶段 + CRC16 校验
- **v2 流控**：网关进度通知 (0x89) + 缺包位图 (0x87)
- **v3 组播** (0x0A/0x8A)：最多 8 节点同步 + 独立状态追踪

</td>
<td width="50%">

**📊 传输与可视化**
- **FAST 模式**：5ms/包快速 BLE 上传 → 网关缓存 → 网关自主流控
- **ACK 模式**：逐包确认 + 自动重传 (3s 超时)
- **11 态状态机**：Idle → Sending → WaitingAck → MeshTransfer → Done...
- **组播传输**：长按多选节点 → 一键同步多屏
- **拓扑网格**：网关 (路由器图标/绿) + 节点 (芯片图标) + 跳数标签
- **通信日志**：50 条彩色编码 (上行青蓝/下行绿/其他灰)
- **30s 自动刷新**拓扑 + 传输完成后自动查询

</td>
</tr>
</table>

---

## 🏗️ 系统架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                   MeshGateway Android App                            │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  MainActivity.kt (Jetpack Compose)                              │ │
│  │                                                                  │ │
│  │  ScanPage ─────► ConnectedPage ─────► CropImageDialog           │ │
│  │  (扫描/设备列表)  (拓扑/节点网格)      (交互式裁剪)              │ │
│  │                      │                      │                    │ │
│  │                      ▼                      ▼                    │ │
│  │              NodeActionDialog        ImagePreviewDialog          │ │
│  │              (发图/发文/查看)         (编码预览/参数选择)         │ │
│  │                      │                      │                    │ │
│  │                      └──────┬───────────────┘                    │ │
│  │                             ▼                                    │ │
│  │                    ImageProgressBar (10 种状态动画)               │ │
│  └─────────────────────┬───────────────────────────────────────────┘ │
│                         │ StateFlow                                  │
│  ┌─────────────────────▼───────────────────────────────────────────┐ │
│  │  BleManager.kt (核心 BLE 引擎)                                  │ │
│  │                                                                  │ │
│  │  扫描 → 连接 → MTU → GATT → CCCD订阅 → 串行写入队列            │ │
│  │                                                                  │ │
│  │  图传状态机 (11 态):                                             │ │
│  │  Idle → Sending → WaitingAck → MeshTransfer → Finishing → Done  │ │
│  │    ├→ MulticastTransfer → MulticastDone                         │ │
│  │    └→ Cancelled                                                  │ │
│  │                                                                  │ │
│  │  分包: 237B/包 · CRC16-XMODEM · 动态超时                       │ │
│  └─────────────────────┬───────────────────────────────────────────┘ │
│                         │                                            │
│  ┌─────────────────────▼────────────┐  ┌─────────────────────────┐  │
│  │  MeshProtocol.kt                 │  │  ImageRleEncoder.kt     │  │
│  │  协议编解码 (v1~v3)              │  │  RLE 游程编码 (1bpp)    │  │
│  │  12 种消息类型                   │  │  Literal + Varint 格式  │  │
│  │  UpstreamMessage sealed class    │  │  与固件 rle_decode 兼容  │  │
│  └──────────────────────────────────┘  └─────────────────────────┘  │
└──────────────────────────┬───────────────────────────────────────────┘
                            │ BLE GATT (Service: 0000ffe0, TX: ffe1, RX: ffe2)
                            │ MTU: 247 bytes, Notify/Indicate
                            ▼
              ┌──────────────────────────────────┐
              │  Gateway Node (BearPi-Pico H3863) │
              │  BLE ←→ SLE Mesh 多跳 ←→ ePaper   │
              │  详见固件仓库                       │
              └──────────────────────────────────┘
```

### 图片处理管线

```
选图 (Android ImagePicker)
  │
  ▼
CropImageDialog (交互式裁剪)
  │  归一化坐标系 [0,1]
  │  四角缩放 (对角锚点, 保持宽高比)
  │  拖动平移 + 实时旋转标记
  │  三分线辅助 + Canvas 绘制
  │
  ▼
ImageUtils.processFromCropped()
  │
  ├─ 小分辨率 (≤240×360):
  │   │  Bitmap.createScaledBitmap()
  │   │  Floyd-Steinberg 六色误差扩散抖动
  │   │  ┌──────────────────────────────────┐
  │   │  │  6 色调色板:                      │
  │   │  │  黑(0,0,0) 白(255,255,255)       │
  │   │  │  黄(255,243,56) 红(191,0,0)      │
  │   │  │  蓝(100,64,255) 绿(67,138,28)    │
  │   │  │                                   │
  │   │  │  误差扩散权重:                     │
  │   │  │       当前  | 7/16 (右)           │
  │   │  │  3/16 | 5/16 | 1/16              │
  │   │  └──────────────────────────────────┘
  │   │  → 4bpp nibble 打包 (MSB first)
  │   │  → 180° 旋转 (墨水屏物理修正)
  │   └─→ 编码模式 0x00 (H_LSB)
  │
  └─ 大分辨率 (480×800):
      │  JPEG 压缩 (初始 Q=40)
      │  自适应降质循环：若 >43KB 则 Q -= 5 重试
      │  仅 90° CCW 旋转 (固件端解码)
      └─→ 编码模式 0x02 (JPEG)

  可选: RLE 编码模式 0x01
      │  Bitmap → 1bpp 二值化
      │  ImageRleEncoder.encode()
      │  Literal 7-bit + RLE Varint
      └─→ 压缩率 50-80%
  │
  ▼
ImagePreviewDialog (确认参数)
  │  原图 vs 墨水屏效果实时对比
  │  JPEG 质量选择 (25/40/55)
  │  传输模式选择 (FAST/ACK)
  │  压缩大小 + 分包数显示
  │
  ▼
BleManager.sendImage() / sendImageMulticast()
  │  分包发送 (237B/包, 5ms 间隔)
  │  CRC16-XMODEM 校验
  │  11 态状态机驱动 UI 进度
  │
  ▼
传输完成 → NodeImageStore 本地缓存 (PNG)
```

---

## 📡 BLE 协议

### 连接参数

| 参数 | 值 |
|------|-----|
| **Service UUID** | `0000ffe0-0000-1000-8000-00805f9b34fb` |
| **TX Characteristic** | `0000ffe1` (读/通知 — 接收网关数据) |
| **RX Characteristic** | `0000ffe2` (写 — 发送命令到网关) |
| **CCCD UUID** | `00002902-0000-1000-8000-00805f9b34fb` |
| **MTU** | 247 字节 |
| **扫描过滤** | 设备名包含 `SLE_GW_` |
| **扫描超时** | 12 秒 |

### 下行消息 (App → 网关)

| 码 | 命令 | 格式 | 说明 |
|----|------|------|------|
| `0x01` | UNICAST | `AA 01 DST(2) LEN PAYLOAD` | 单播到指定节点 |
| `0x02` | BROADCAST | `AA 02 FFFF LEN PAYLOAD` | 广播全网 |
| `0x03` | TOPO_QUERY | `AA 03` | 查询全网拓扑 |
| `0x04` | IMG_START | `AA 04 DST(2) TOTAL(2) PKT(2) W(2) H(2) MODE(1) XFER(1)` | 图传开始 |
| `0x05` | IMG_DATA | `AA 05 DST(2) SEQ(2) LEN PAYLOAD` | 图片数据分包 |
| `0x06` | IMG_END | `AA 06 DST(2) CRC16(2)` | 图传结束 + 校验 |
| `0x07` | IMG_CANCEL | `AA 07` | 取消传输 |
| `0x0A` | MCAST_START | `AA 0A N ADDR1(2)..ADDRn(2) TOTAL(2) PKT(2) W(2) H(2) MODE(1)` | 组播开始 (v3) |

### 上行响应 (网关 → App)

| 码 | 响应 | 说明 |
|----|------|------|
| `0x81` | DATA_UP | 节点数据透传 |
| `0x83` | TOPO_RESP | 拓扑响应：网关地址 + 节点列表 (addr, hops) |
| `0x85` | IMG_ACK | 分包确认 (seq, status) — ACK 模式 |
| `0x86` | IMG_RESULT | 最终结果：0=OK, 1=OOM, 2=TIMEOUT, 3=CANCEL, 4=CRC_ERR |
| `0x87` | IMG_MISSING | 缺包通知 (总缺包数) |
| `0x89` | IMG_PROGRESS | 流控进度：phase(初始/补包) + rxCount + total |
| `0x8A` | MCAST_PROGRESS | 组播进度：completedCount / totalTargets / latestAddr / status |

### 图片编码模式

| 模式 | 码 | 说明 | 适用场景 |
|------|:--:|------|---------|
| **4bpp 六色** | `0x00` | Floyd-Steinberg 抖动 → 4bpp nibble 打包 | 小图 (≤240×360) 六色彩显 |
| **RLE** | `0x01` | 1bpp 二值 → 变长游程编码 | 黑白图高压缩 |
| **JPEG** | `0x02` | 有损压缩 (自适应质量, ≤43KB) | 大图 (480×800) 彩色 |

### 传输模式

| 模式 | 说明 | 超时策略 |
|------|------|---------|
| **FAST** | 5ms/包快速上传 → 网关缓存 → 网关自主 AIMD 流控注入 Mesh | 30s + 0.5s×包数 + 10s×组播目标数 |
| **ACK** | 逐包发送 → 等待 0x85 确认 → 失败自动重发 (3s 超时) | 同上 |

---

## 🎯 图传状态机

App 通过 11 态有限状态机管理整个图片传输生命周期：

```
                    ┌─────────────────────────────────────────┐
                    │                                         │
  Idle ──sendImage()──► Sending ──全部发完──► Finishing       │
    ▲                     │                     │             │
    │                     │ (ACK 模式)          │ (收到结果)  │
    │                     ▼                     ▼             │
    │               WaitingAck             Done / Error       │
    │                     │                     │             │
    │                     │ (FAST 模式)         │             │
    │                     ▼                     │             │
    │              MeshTransfer                  │             │
    │                (等待网关进度)               │             │
    │                     │                     │             │
    │              ┌──────┘                     │             │
    │              │ (v3 组播)                   │             │
    │              ▼                            │              │
    │       MulticastTransfer                  │              │
    │        (等待各节点结果)                    │              │
    │              │                            │              │
    │              ▼                            │              │
    │       MulticastDone                       │              │
    │        (results: Map)                     │              │
    │              │                            │              │
    └──────────────┴────────────────────────────┘              │
                                                               │
    Cancelled ◄──────────(任意状态可取消)──────────────────────┘
```

---

## 🚀 快速开始

### 环境要求

- Android Studio Iguana (2024.1) 或更高版本
- JDK 17+
- Android SDK 35
- 一台 Android 8.0+ 手机（需支持 BLE）

### 编译运行

```bash
# 克隆仓库
git clone https://github.com/time2024/MeshGatewayAPP.git
cd MeshGatewayAPP/MeshGatewayApp

# Android Studio 打开项目，等待 Gradle 同步
# 或命令行编译：
./gradlew assembleDebug

# APK 输出路径：
# app/build/outputs/apk/debug/app-debug.apk
```

### 使用流程

1. **确保硬件就绪**：至少 2 块 BearPi-Pico H3863 开发板已烧录 [SLE Mesh 固件](https://github.com/time2024/NearLink-Mesh-ePaper) 并上电组网
2. **安装 App**：编译安装或直接安装 APK
3. **授予权限**：首次启动需授予蓝牙和位置权限（BLE 扫描需要）
4. **扫描连接**：点击"扫描"，找到 `SLE_GW_XXXX` 设备，点击连接
5. **查看拓扑**：连接成功后自动查询拓扑，显示网关和所有 Mesh 节点
6. **发送图片**：
   - 点击任意节点 → "发送图片" → 选图 → 裁剪 → 预览 → 确认发送
   - 或长按多个节点 → 进入组播模式 → 一键同步多块屏幕
7. **查看进度**：底部进度条实时显示传输状态

---

## 📂 项目结构

```
MeshGatewayApp/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml           # 权限声明 + BLE 硬件要求
│   │   ├── java/com/meshgateway/
│   │   │   ├── BleManager.kt            # 🔗 BLE 核心引擎 (1,100+ 行)
│   │   │   │   ├── 扫描 / 连接 / MTU / GATT / CCCD
│   │   │   │   ├── 串行写入队列 + 背压处理
│   │   │   │   ├── 图传状态机 (11 态)
│   │   │   │   ├── CRC16-XMODEM 校验
│   │   │   │   └── 单播 / 组播 / 拓扑查询
│   │   │   │
│   │   │   ├── MeshProtocol.kt           # 📡 协议编解码 (300+ 行)
│   │   │   │   ├── 协议版本 v1 ~ v3
│   │   │   │   ├── 12 种消息类型定义
│   │   │   │   ├── MeshNode / UpstreamMessage 数据类
│   │   │   │   └── 图片模式枚举 (H_LSB / RLE / JPEG)
│   │   │   │
│   │   │   ├── ImageRleEncoder.kt        # 🗜️ RLE 编码器 (50 行)
│   │   │   │   ├── Literal 7-bit + RLE Varint
│   │   │   │   └── MSB-first 像素提取
│   │   │   │
│   │   │   └── MainActivity.kt           # 🖥️ Compose UI (2,000+ 行)
│   │   │       ├── ScanPage (设备扫描列表)
│   │   │       ├── ConnectedPage (拓扑网格 + 日志)
│   │   │       ├── CropImageDialog (交互式裁剪)
│   │   │       ├── ImagePreviewDialog (编码预览)
│   │   │       ├── NodeActionDialog (节点操作)
│   │   │       ├── ImageProgressBar (10 种状态动画)
│   │   │       ├── ImageUtils (六色抖动 + JPEG 压缩)
│   │   │       └── NodeImageStore (本地 PNG 缓存)
│   │   │
│   │   └── res/
│   │       └── values/
│   │           ├── strings.xml
│   │           ├── colors.xml
│   │           └── themes.xml
│   │
│   └── build.gradle.kts                  # App 级构建配置
│
├── build.gradle.kts                      # 项目级构建配置
├── settings.gradle.kts                   # Gradle 设置
└── gradle.properties                     # Gradle 属性
```

---

## 🔧 依赖库

```gradle
// 核心 Android
androidx.core:core-ktx:1.13.1
androidx.lifecycle:lifecycle-runtime-ktx:2.8.4
androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4
androidx.activity:activity-compose:1.9.1

// Jetpack Compose (2024.08.00 BOM)
androidx.compose.ui:ui
androidx.compose.ui:ui-graphics
androidx.compose.ui:ui-tooling-preview
androidx.compose.material3:material3
androidx.compose.material:material-icons-extended

// 编译配置
Kotlin: 1.9.24
Compose Compiler: 1.5.14
Gradle Plugin: 8.2.2
Java: 1.8
```

> ✅ **零外部依赖**：不使用任何第三方网络库、图片库或 BLE 库，100% 基于 Android 原生 API。

---

## 📋 权限说明

```xml
<!-- BLE 权限 (Android 11 及以下) -->
BLUETOOTH / BLUETOOTH_ADMIN   (maxSdkVersion 30)

<!-- BLE 权限 (Android 12+) -->
BLUETOOTH_SCAN                (neverForLocation)
BLUETOOTH_CONNECT

<!-- 位置权限 (BLE 扫描需要) -->
ACCESS_FINE_LOCATION
ACCESS_COARSE_LOCATION

<!-- 硬件声明 -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>
```

App 会根据系统版本自适应请求所需权限集。

---

## ❓ 常见问题

<details>
<summary><b>Q: 扫描不到网关设备？</b></summary>

1. 确认网关开发板已上电且 BLE 广播正常（串口应有 `ble_adv started` 日志）
2. 确认手机蓝牙已开启
3. 确认已授予**位置权限**（Android BLE 扫描硬性要求）
4. App 按设备名 `SLE_GW_` 前缀过滤，确认固件 BLE 广播名格式正确
5. 扫描超时为 12 秒，可多试几次

</details>

<details>
<summary><b>Q: 连接后看不到 Mesh 节点？</b></summary>

1. 连接成功后 App 会自动查询拓扑（200ms 延迟）
2. 手动点击"查询拓扑"按钮刷新
3. 检查网关串口日志确认 SLE Mesh 已组网（应有 `HELLO sent, neighbors=N`）
4. 拓扑查询需要 5 秒收集窗口，等待片刻

</details>

<details>
<summary><b>Q: 图片传输失败？</b></summary>

1. **OOM (0x01)**：图片数据超过接收端缓冲区 (96KB)，尝试降低 JPEG 质量或使用小分辨率
2. **TIMEOUT (0x02)**：Mesh 链路不稳定，检查节点间距离和信号质量
3. **CRC_ERR (0x04)**：数据校验失败，尝试切换到 ACK 模式获得逐包确认
4. FAST 模式下建议 1-2 跳距离，3 跳以上建议用 ACK 模式

</details>

<details>
<summary><b>Q: 组播只有部分节点成功？</b></summary>

组播 v3 每个节点独立回复结果。部分失败可能因为：
- 该节点 Mesh 路由不可达（检查拓扑）
- 该节点接收端正忙（上一张图还在处理）
- 信号质量差导致丢包（网关会自动补包最多 3 轮）

App 会显示每个节点的独立状态码。

</details>

<details>
<summary><b>Q: 支持哪些墨水屏分辨率？</b></summary>

| 分辨率 | 编码模式 | 说明 |
|--------|---------|------|
| 240×360 | 4bpp 六色 / RLE | 小屏，黑白或六色 |
| 480×800 | JPEG | 大屏 (7.3 寸)，固件端解码+抖动 |

裁剪时可选择目标分辨率。App 会自动选择最佳编码方式。

</details>

---

## 🤝 相关项目

| 仓库 | 说明 |
|------|------|
| [NearLink-Mesh-ePaper](https://github.com/time2024/NearLink-Mesh-ePaper) | SLE Mesh 固件 — 完整协议栈 + ePaper 驱动 + WiFi 网关 |
| **MeshGatewayAPP** (本仓库) | Android 控制端 — BLE 连接 + 图片处理 + 组播控制 |

---

## 📄 License

本项目基于 [Apache License 2.0](LICENSE) 开源。

---

<p align="center">
  <b>📱 让星闪 Mesh 图传触手可及</b><br>
  <sub>Made with ❤️ for the NearLink / SparkLink open-source community</sub>
</p>
