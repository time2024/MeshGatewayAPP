package com.meshgateway

/**
 * BLE-Mesh 网关通信协议
 *
 * 下行 (手机 → 网关):
 *   AA 01 DST_HI DST_LO LEN PAYLOAD   — 单播到指定节点
 *   AA 02 FF FF LEN PAYLOAD            — 广播到所有节点
 *   AA 03                               — 查询 mesh 拓扑
 *
 * 上行 (网关 → 手机):
 *   AA 81 SRC_HI SRC_LO LEN PAYLOAD   — 某节点发来的数据
 *   AA 83 GW_HI GW_LO COUNT [ADDR_HI ADDR_LO HOPS]...  — 拓扑响应
 */
object MeshProtocol {

    const val MAGIC = 0xAA

    /** 构造单播帧 */
    fun buildUnicast(dstAddr: Int, data: ByteArray): ByteArray {
        val frame = ByteArray(5 + data.size)
        frame[0] = MAGIC.toByte()
        frame[1] = 0x01  // CMD_UNICAST
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
        frame[1] = 0x02  // CMD_BROADCAST
        frame[2] = 0xFF.toByte()
        frame[3] = 0xFF.toByte()
        frame[4] = data.size.toByte()
        data.copyInto(frame, 5)
        return frame
    }

    /** 构造拓扑查询帧: AA 03 */
    fun buildTopoQuery(): ByteArray = byteArrayOf(MAGIC.toByte(), 0x03)

    /** 解析上行 Notify 数据 */
    fun parseNotification(data: ByteArray): UpstreamMessage? {
        if (data.size < 2 || (data[0].toInt() and 0xFF) != MAGIC) return null
        val cmd = data[1].toInt() and 0xFF
        return when (cmd) {
            0x81 -> parseDataMessage(data)
            0x83 -> parseTopoResponse(data)
            else -> null
        }
    }

    private fun parseDataMessage(data: ByteArray): UpstreamMessage.DataFromNode? {
        if (data.size < 5) return null
        val srcAddr = (data[2].toInt() and 0xFF shl 8) or (data[3].toInt() and 0xFF)
        val payloadLen = data[4].toInt() and 0xFF
        if (data.size < 5 + payloadLen) return null
        val payload = data.copyOfRange(5, 5 + payloadLen)
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
}

/** Mesh 网络节点信息 */
data class MeshNode(
    val addr: Int,
    val hops: Int
)

/** 上行消息类型 */
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
}

/** 工具: ByteArray 转 hex 字符串 */
fun ByteArray.toHexString(): String =
    joinToString("-") { String.format("%02X", it) }
