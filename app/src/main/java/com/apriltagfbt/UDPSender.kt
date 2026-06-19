package com.apriltagfbt

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * UDP 发送器 — 将 6DoF 数据发给 PC
 *
 * 协议格式（Little-Endian）：
 *   [0]      magic  = 0xAA
 *   [1]      type  = 0x02 (批量)
 *   [2-5]    poseCount (int32)
 *   [6+N×36]  poseData (36 bytes each)
 *
 * 单个 pose (36 bytes)：
 *   tagId (int32) + tx,ty,tz (float32×3) + qx,qy,qz,qw (float32×4) + confidence (float32)
 */
class UDPSender(private val host: String, private val port: Int) {

    private val socket = DatagramSocket()
    private val address = InetAddress.getByName(host)

    fun send(pose: FloatArray, tagId: Int, confidence: Float = 1.0f) {
        thread {
            try {
                val buf = ByteArray(6 + 36) // magic + type + count + 1 pose
                // magic + type
                buf[0] = 0xAA.toByte()
                buf[1] = 0x02
                // count = 1
                buf[2] = 1; buf[3] = 0; buf[4] = 0; buf[5] = 0

                // pose data (36 bytes)
                var off = 6
                // tagId (int32, LE)
                buf[off++] = (tagId        and 0xFF).toByte()
                buf[off++] = (tagId shr 8   and 0xFF).toByte()
                buf[off++] = (tagId shr 16  and 0xFF).toByte()
                buf[off++] = (tagId shr 24  and 0xFF).toByte()
                // tx,ty,tz + qx,qy,qz,qw + confidence (7×float32)
                for (f in pose + confidence) {
                    val bits = f.toRawBits()
                    buf[off++] = (bits       and 0xFF).toByte()
                    buf[off++] = (bits shr 8   and 0xFF).toByte()
                    buf[off++] = (bits shr 16  and 0xFF).toByte()
                    buf[off++] = (bits shr 24  and 0xFF).toByte()
                }
                val pkt = DatagramPacket(buf, buf.size, address, port)
                socket.send(pkt)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun close() { socket.close() }
}
