package com.apriltagfbt

import android.graphics.ImageFormat
import android.media.Image

/**
 * AprilTag Native 封装
 *
 *  so 库：libapriltagfbt.so
 *  功能：AprilTag 检测 + solvePnP 位姿估计
 */
object ApriltagNative {

    init {
        System.loadLibrary("apriltagfbt")
    }

    // ── 初始化 / 销毁 ──────────────────────────────────────
    external fun init()
    external fun destroy()

    // ── 检测 AprilTag
    //  输入：NV21 (YUV420) 字节数组、宽、高
    //  输出：float[]，格式：
    //    [nTags,
    //     id0, cx0, cy0, x00,y00, x01,y01, x02,y02, x03,y03, margin0,
    //     id1, ...]
    //    margin = decision margin，越大越可靠
    external fun detect(yuvData: ByteArray, width: Int, height: Int): FloatArray

    // ── solvePnP 位姿估计
    //  输入：4个角点（图像坐标，CCW）、标记物理尺寸（米）、相机内参
    //  输出：[tx, ty, tz, qx, qy, qz, qw]
    external fun solvePnP(
        cornersXY: FloatArray,
        markerSize: Float,
        fx: Float, fy: Float,
        cx: Float, cy: Float
    ): FloatArray

    // ── 便利方法：从 Camera2 Image (YUV_420_888) 转为 NV21 字节数组 ──
    fun imageToNV21(image: Image): ByteArray {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val ySize = yPlane.buffer.remaining()
        val uvSize = uPlane.buffer.remaining() + vPlane.buffer.remaining()

        val nv21 = ByteArray(ySize + uvSize)

        // 复制 Y 平面
        yPlane.buffer.get(nv21, 0, ySize)

        // NV21: Y + 交错 VU
        // YUV_420_888 的 UV 平面需要手动交错
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvStride = uPlane.pixelStride  // 通常为 2
        var pos = ySize
        val width  = image.width
        val height = image.height
        // 半采样
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIdx = row * uPlane.rowStride + col * uvStride
                val vIdx = row * vPlane.rowStride + col * uvStride
                nv21[pos++] = vBuffer.get(vIdx)  // V first in NV21
                nv21[pos++] = uBuffer.get(uIdx)  // U second
            }
        }
        return nv21
    }

    // ── 解析检测结果 ──────────────────────────────────────
    data class DetectedTag(
        val id: Int,
        val centerX: Float,
        val centerY: Float,
        val corners: FloatArray, // [x0,y0, x1,y1, x2,y2, x3,y3]
        val decisionMargin: Float,
    )

    fun parseDetectResult(data: FloatArray): List<DetectedTag> {
        if (data.isEmpty()) return emptyList()
        val nTags = data[0].toInt()
        val list = mutableListOf<DetectedTag>()
        var idx = 1
        repeat(nTags) {
            val id       = data[idx].toInt()
            val centerX  = data[idx + 1]
            val centerY  = data[idx + 2]
            val corners  = FloatArray(8)
            for (j in 0 until 8) corners[j] = data[idx + 3 + j]
            val margin   = data[idx + 11]
            list.add(DetectedTag(id, centerX, centerY, corners, margin))
            idx += 12
        }
        return list
    }
}
