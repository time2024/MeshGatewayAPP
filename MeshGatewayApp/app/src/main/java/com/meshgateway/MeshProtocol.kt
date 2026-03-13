package com.meshgateway

/**
 * BLE-Mesh 网关通信协议
 *
 * v2 新增:
 *   AA 08 DST(2) SEG_ID                              — 检查点 (网关→目标)
 *   AA 87 SRC(2) TOTAL_HI TOTAL_LO BITMAP[30]        — 缺包位图 (目标→网关→APP)
 *   AA 88 SRC(2) SEG_ID RX_COUNT(2)                   — 检查点应答 (目标→网关, 不转发APP)
 *   AA 89 SRC(2) PHASE(1) RX_COUNT(2) TOTAL(2)       — 流控进度 (网关→APP)
 *
 * v2.1 修复:
 *   IMG_START 新增 XFER(1) 字段: 0=FAST(网关流控), 1=ACK(逐包确认立即注入)
 *   帧格式: AA 04 DST(2) TOTAL(2) PKT(2) W(2) H(2) MODE(1) XFER(1)  共14字节
 */
object MeshProtocol {

    const val MAGIC = 0xAA

    /* ── 下行命令码 ── */
    const val CMD_UNICAST       = 0x01
    const val CMD_BROADCAST     = 0x02
    const val CMD_TOPO_QUERY    = 0x03
    const val CMD_IMG_START     = 0x04
    const val CMD_IMG_DATA      = 0x05
    const val CMD_IMG_END       = 0x06
    const val CMD_IMG_CANCEL    = 0x07
    const val CMD_IMG_CHECKPOINT = 0x08  // v2: 检查点

    // === v3: 组播命令 ===
    const val CMD_IMG_MCAST_START    = 0x0A  // 组播图片传输开始
    const val CMD_IMG_MCAST_PROGRESS = 0x8A  // 组播进度通知 (网关→APP)

    /* ── 上行命令码 ── */
    const val CMD_DATA_UP       = 0x81
    const val CMD_TOPO_RESP     = 0x83
    const val CMD_IMG_ACK       = 0x85
    const val CMD_IMG_RESULT    = 0x86
    const val CMD_IMG_MISSING   = 0x87  // v2: 位图格式
    const val CMD_IMG_CHKPT_ACK = 0x88  // v2: 检查点应答 (网关内部处理, 不转发)
    const val CMD_IMG_PROGRESS  = 0x89  // v2: 流控进度

    /* ── 图片 ACK 状态 ── */
    const val IMG_ACK_OK        = 0x00
    const val IMG_ACK_RESEND    = 0x01
    const val IMG_ACK_DONE      = 0xFF

    /* ── 图片传输结果 ── */
    const val IMG_RESULT_OK     = 0x00
    const val IMG_RESULT_OOM    = 0x01
    const val IMG_RESULT_TIMEOUT= 0x02
    const val IMG_RESULT_CANCEL = 0x03
    const val IMG_RESULT_CRC_ERR= 0x04

    /* ── 取模模式 ── */
    const val IMG_MODE_H_LSB    = 0x00
    const val IMG_MODE_RLE      = 0x01   // RLE 游程编码压缩
    const val IMG_MODE_JPEG     = 0x02   // JPEG 有损压缩

    /* ── 传输模式 ── */
    const val IMG_XFER_FAST     = 0x00   // 网关流控 (v2 默认)
    const val IMG_XFER_ACK      = 0x01   // 逐包确认 (立即注入 mesh)

    /* ── 分包参数 ── */
    const val IMG_PKT_PAYLOAD   = 237   // 与固件 IMG_GW_PKT_PAYLOAD / Qt IMG_PKT_PAYLOAD 保持一致

    /** 构造单播帧 */
    fun buildUnicast(dstAddr: Int, data: ByteArray): ByteArray {
        val frame = ByteArray(5 + data.size)
        frame[0] = MAGIC.toByte()
        frame[1] = CMD_UNICAST.toByte()
        frame[2] = (dstAddr shr 8 and 0xFF).toByte()
        frame[3] = (dstAddr and 0xFF).toByte()
        frame[4] = data.size.toByte()
        data.copyInto(frame, 5)
        return frame
    }

    /** 构造广播帧 */
    fun buildBroadcast(data: ByteArray): ByteArray {
        val frame = ByteArray(5 + data.size)
        frame[0] = MAGIC.toByte()
        frame[1] = CMD_BROADCAST.toByte()
        frame[2] = 0xFF.toByte()
        frame[3] = 0xFF.toByte()
        frame[4] = data.size.toByte()
        data.copyInto(frame, 5)
        return frame
    }

    /** 构造拓扑查询帧: AA 03 */
    fun buildTopoQuery(): ByteArray = byteArrayOf(MAGIC.toByte(), CMD_TOPO_QUERY.toByte())

    /* ════════════════ 图片传输 — 下行帧 ════════════════ */

    /**
     * 构造图片 START 帧 (14 字节)
     * AA 04 DST(2) TOTAL(2) PKT(2) W(2) H(2) MODE(1) XFER(1)
     *
     * @param xfer 传输模式: 0=FAST(网关流控), 1=ACK(逐包确认)
     */
    fun buildImageStart(dstAddr: Int, totalBytes: Int, pktCount: Int,
                        width: Int, height: Int, mode: Int = IMG_MODE_H_LSB,
                        xfer: Int = IMG_XFER_FAST): ByteArray {
        val frame = ByteArray(14)
        frame[0]  = MAGIC.toByte()
        frame[1]  = CMD_IMG_START.toByte()
        frame[2]  = (dstAddr shr 8 and 0xFF).toByte()
        frame[3]  = (dstAddr and 0xFF).toByte()
        frame[4]  = (totalBytes shr 8 and 0xFF).toByte()
        frame[5]  = (totalBytes and 0xFF).toByte()
        frame[6]  = (pktCount shr 8 and 0xFF).toByte()
        frame[7]  = (pktCount and 0xFF).toByte()
        frame[8]  = (width shr 8 and 0xFF).toByte()
        frame[9]  = (width and 0xFF).toByte()
        frame[10] = (height shr 8 and 0xFF).toByte()
        frame[11] = (height and 0xFF).toByte()
        frame[12] = mode.toByte()
        frame[13] = xfer.toByte()
        return frame
    }

    fun buildImageData(dstAddr: Int, seq: Int, data: ByteArray): ByteArray {
        val frame = ByteArray(7 + data.size)
        frame[0] = MAGIC.toByte()
        frame[1] = CMD_IMG_DATA.toByte()
        frame[2] = (dstAddr shr 8 and 0xFF).toByte()
        frame[3] = (dstAddr and 0xFF).toByte()
        frame[4] = (seq shr 8 and 0xFF).toByte()
        frame[5] = (seq and 0xFF).toByte()
        frame[6] = data.size.toByte()
        data.copyInto(frame, 7)
        return frame
    }

    fun buildImageEnd(dstAddr: Int, crc16: Int): ByteArray {
        val frame = ByteArray(6)
        frame[0] = MAGIC.toByte()
        frame[1] = CMD_IMG_END.toByte()
        frame[2] = (dstAddr shr 8 and 0xFF).toByte()
        frame[3] = (dstAddr and 0xFF).toByte()
        frame[4] = (crc16 shr 8 and 0xFF).toByte()
        frame[5] = (crc16 and 0xFF).toByte()
        return frame
    }

    fun buildImageCancel(dstAddr: Int): ByteArray {
        val frame = ByteArray(4)
        frame[0] = MAGIC.toByte()
        frame[1] = CMD_IMG_CANCEL.toByte()
        frame[2] = (dstAddr shr 8 and 0xFF).toByte()
        frame[3] = (dstAddr and 0xFF).toByte()
        return frame
    }

    /**
     * 构建组播图片传输 START 帧
     *
     * 帧格式: AA 0A N(1) ADDR1(2)...ADDRn(2) TOTAL(2) PKT(2) W(2) H(2) MODE(1)
     */
    fun buildImageMulticastStart(
        targets: List<Int>,
        totalBytes: Int,
        pktCount: Int,
        width: Int,
        height: Int,
        mode: Int = 0
    ): ByteArray {
        val n = targets.size.coerceAtMost(8)
        val buf = ByteArray(2 + 1 + n * 2 + 9)
        var pos = 0
        buf[pos++] = MAGIC.toByte()
        buf[pos++] = CMD_IMG_MCAST_START.toByte()
        buf[pos++] = n.toByte()
        for (i in 0 until n) {
            buf[pos++] = (targets[i] shr 8).toByte()
            buf[pos++] = (targets[i] and 0xFF).toByte()
        }
        buf[pos++] = (totalBytes shr 8).toByte()
        buf[pos++] = (totalBytes and 0xFF).toByte()
        buf[pos++] = (pktCount shr 8).toByte()
        buf[pos++] = (pktCount and 0xFF).toByte()
        buf[pos++] = (width shr 8).toByte()
        buf[pos++] = (width and 0xFF).toByte()
        buf[pos++] = (height shr 8).toByte()
        buf[pos++] = (height and 0xFF).toByte()
        buf[pos++] = mode.toByte()
        return buf
    }

    /* ════════════════ 上行解析 ════════════════ */

    fun parseNotification(data: ByteArray): UpstreamMessage? {
        if (data.size < 2 || (data[0].toInt() and 0xFF) != MAGIC) return null
        val cmd = data[1].toInt() and 0xFF
        return when (cmd) {
            CMD_DATA_UP      -> parseDataMessage(data)
            CMD_TOPO_RESP    -> parseTopoResponse(data)
            CMD_IMG_ACK      -> parseImageAck(data)
            CMD_IMG_RESULT   -> parseImageResult(data)
            CMD_IMG_MISSING  -> parseImageMissing(data)
            CMD_IMG_PROGRESS -> parseImageProgress(data)
            CMD_IMG_MCAST_PROGRESS -> parseMulticastProgress(data)
            else -> null
        }
    }

    private fun parseDataMessage(data: ByteArray): UpstreamMessage? {
        if (data.size < 5) return null
        val srcAddr = (data[2].toInt() and 0xFF shl 8) or (data[3].toInt() and 0xFF)
        val payloadLen = data[4].toInt() and 0xFF
        if (data.size < 5 + payloadLen) return null
        val payload = data.copyOfRange(5, 5 + payloadLen)

        if (payload.isNotEmpty()) {
            when (payload[0].toInt() and 0xFF) {
                CMD_IMG_RESULT -> {
                    if (payload.size >= 2) {
                        val status = payload[1].toInt() and 0xFF
                        return UpstreamMessage.ImageResult(srcAddr, status)
                    }
                }
                CMD_IMG_ACK -> {
                    if (payload.size >= 4) {
                        val status = payload[1].toInt() and 0xFF
                        val seq = (payload[2].toInt() and 0xFF shl 8) or (payload[3].toInt() and 0xFF)
                        return UpstreamMessage.ImageAck(srcAddr, status, seq)
                    }
                }
            }
        }

        return UpstreamMessage.DataFromNode(srcAddr, payload)
    }

    private fun parseTopoResponse(data: ByteArray): UpstreamMessage.Topology? {
        if (data.size < 5) return null
        val gwAddr = (data[2].toInt() and 0xFF shl 8) or (data[3].toInt() and 0xFF)
        val count = data[4].toInt() and 0xFF
        if (data.size < 5 + count * 3) return null

        val nodes = mutableListOf<MeshNode>()
        for (i in 0 until count) {
            val offset = 5 + i * 3
            val addr = (data[offset].toInt() and 0xFF shl 8) or (data[offset + 1].toInt() and 0xFF)
            val hops = data[offset + 2].toInt() and 0xFF
            nodes.add(MeshNode(addr, hops))
        }
        return UpstreamMessage.Topology(gwAddr, nodes)
    }

    private fun parseImageAck(data: ByteArray): UpstreamMessage.ImageAck? {
        if (data.size < 7) return null
        val srcAddr = (data[2].toInt() and 0xFF shl 8) or (data[3].toInt() and 0xFF)
        val status = data[4].toInt() and 0xFF
        val seq = (data[5].toInt() and 0xFF shl 8) or (data[6].toInt() and 0xFF)
        return UpstreamMessage.ImageAck(srcAddr, status, seq)
    }

    private fun parseImageResult(data: ByteArray): UpstreamMessage.ImageResult? {
        if (data.size < 5) return null
        val srcAddr = (data[2].toInt() and 0xFF shl 8) or (data[3].toInt() and 0xFF)
        val status = data[4].toInt() and 0xFF
        return UpstreamMessage.ImageResult(srcAddr, status)
    }

    /** v2: 解析缺包位图: AA 87 SRC(2) TOTAL_HI TOTAL_LO BITMAP[30] */
    private fun parseImageMissing(data: ByteArray): UpstreamMessage.ImageMissing? {
        if (data.size < 6) return null
        val srcAddr = (data[2].toInt() and 0xFF shl 8) or (data[3].toInt() and 0xFF)
        val totalMissing = (data[4].toInt() and 0xFF shl 8) or (data[5].toInt() and 0xFF)
        return UpstreamMessage.ImageMissing(srcAddr, totalMissing)
    }

    /** v2: 解析流控进度: AA 89 SRC(2) PHASE(1) RX_COUNT(2) TOTAL(2) */
    private fun parseImageProgress(data: ByteArray): UpstreamMessage.ImageProgress? {
        if (data.size < 9) return null
        val srcAddr  = (data[2].toInt() and 0xFF shl 8) or (data[3].toInt() and 0xFF)
        val phase    = data[4].toInt() and 0xFF
        val rxCount  = (data[5].toInt() and 0xFF shl 8) or (data[6].toInt() and 0xFF)
        val total    = (data[7].toInt() and 0xFF shl 8) or (data[8].toInt() and 0xFF)
        return UpstreamMessage.ImageProgress(srcAddr, phase, rxCount, total)
    }

    /** v3: 解析组播进度: AA 8A COMPLETED(1) TOTAL(1) ADDR(2) STATUS(1) */
    private fun parseMulticastProgress(data: ByteArray): UpstreamMessage.MulticastProgress? {
        if (data.size < 7) return null
        val completed = data[2].toInt() and 0xFF
        val total     = data[3].toInt() and 0xFF
        val addr      = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val status    = data[6].toInt() and 0xFF
        return UpstreamMessage.MulticastProgress(completed, total, addr, status)
    }

    /* ════════════════ CRC16 ════════════════ */

    fun crc16(data: ByteArray): Int {
        var crc = 0x0000
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (i in 0 until 8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}

data class MeshNode(val addr: Int, val hops: Int)

sealed class UpstreamMessage {
    data class DataFromNode(val srcAddr: Int, val payload: ByteArray) : UpstreamMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DataFromNode) return false
            return srcAddr == other.srcAddr && payload.contentEquals(other.payload)
        }
        override fun hashCode() = 31 * srcAddr + payload.contentHashCode()
    }

    data class Topology(val gatewayAddr: Int, val nodes: List<MeshNode>) : UpstreamMessage()

    data class ImageAck(val srcAddr: Int, val status: Int, val seq: Int) : UpstreamMessage()

    data class ImageResult(val srcAddr: Int, val status: Int) : UpstreamMessage()

    /** v2: 缺包位图 (网关自主处理, APP 只看总数) */
    data class ImageMissing(val srcAddr: Int, val totalMissing: Int) : UpstreamMessage()

    /** v2: 网关流控进度 (phase: 0=首轮, 1=补包) */
    data class ImageProgress(val srcAddr: Int, val phase: Int,
                             val rxCount: Int, val total: Int) : UpstreamMessage()

    /** v3: 组播进度通知 */
    data class MulticastProgress(
        val completedCount: Int,
        val totalTargets: Int,
        val latestAddr: Int,
        val latestStatus: Int
    ) : UpstreamMessage()
}

fun ByteArray.toHexString(): String =
    joinToString("-") { String.format("%02X", it) }
