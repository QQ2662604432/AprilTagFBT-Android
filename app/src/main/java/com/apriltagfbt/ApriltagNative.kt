package com.apriltagfbt

import android.media.Image

/**
 * AprilTag Native 封装
 *
 * so 库：libapriltagfbt.so
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
    //  输入：灰度 ByteArray（Y 平面）、宽、高
    //  输出：float[]，格式：
    //    [nTags,
    //     id0, cx0, cy0, x00,y00, x01,y01, x02,y02, x03,y03, margin0,
    //     id1, ...]
    //    margin = decision margin，越大越可靠
    external fun detect(grayData: ByteArray, width: Int, height: Int): FloatArray

    // ── solvePnP 位姿估计
    //  输入：4个角点（图像坐标，CCW）、标记物理尺寸（米）、相机内参
    //  输出：[tx, ty, tz, qx, qy, qz, qw]
    external fun solvePnP(
        cornersXY: FloatArray,
        markerSize: Float,
        fx: Float, fy: Float,
        cx: Float, cy: Float
    ): FloatArray

    // ── 便利方法：从 Camera2 Image (YUV_420_888) 提取灰度 ByteArray ──
    //  AprilTag 检测只需要 Y（亮度）平面，不需要 UV
    fun imageToGrayByteArray(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val gray = ByteArray(yPlane.buffer.remaining())
        yPlane.buffer.get(gray)
        return gray
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
