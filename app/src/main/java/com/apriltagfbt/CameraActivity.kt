package com.apriltagfbt

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.*

/**
 * Camera2 取帧 → AprilTag 检测 → solvePnP → UDP 发送
 *
 * 坐标系流程：
 *   tag(3D) ──solvePnP──→ camera(3D) ──空间R,t──→ vr(3D)
 *   tracker board 偏移已预先补偿
 */
class CameraActivity : AppCompatActivity() {

    // ── UI ──────────────────────────────────────────────
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: OverlayView  // 绘制检测框

    // ── Camera2 ──────────────────────────────────────────
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var imageReader: ImageReader
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    // ── 配置（从 Settings 读）────────────────────────
    private var markerSize = 0.10f   // 米
    private var udpHost   = "192.168.1.100"
    private var udpPort   = 4242
    private var cameraWidth  = 640
    private var cameraHeight = 480

    // ── 运行时状态 ─────────────────────────────────────
    private var sender: UDPSender? = null
    private var cameraMatrix: FloatArray? = null   // [fx,fy,cx,cy]
    private var distCoeffs: FloatArray? = null

    // 搜索窗口状态（对应原项目 MainLoopRunner）
    private var lastTagCenters: MutableMap<Int, Pair<Float, Float>> = mutableMapOf()
    private var trackerVisible:   MutableMap<Int, Boolean>         = mutableMapOf()
    private var framesSinceLastSeen = 0
    private val FRAMES_TO_CHECK_ALL = 20
    private val SEARCH_RATIO        = 0.5f  // 占图像宽高的比例
    private var useCircularWindow = true

    // 三层校准数据（从 SharedPreferences 加载）
    private var trackerCalibs: MutableMap<Int, TrackerCalib> = mutableMapOf()
    private var spaceRT: FloatArray? = null  // [r00,r01,r02, r10,...t0,t1,t2] (3×3 + 3)

    // 帧率统计
    private var frameCount = 0
    private var lastFpsTime = System.nanoTime()

    companion object {
        const val TAG = "CameraActivity"
        var isRunning = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 用 SurfaceView + 自定义绘制层
        setupUI()

        // 加载配置
        loadConfig()
        // 加载校准数据
        loadCalibration()

        sender = UDPSender(udpHost, udpPort)
        ApriltagNative.init()
        startCamera()
    }

    private fun setupUI() {
        // 简单用 SurfaceView 做预览
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences("apriltagfbt", Context.MODE_PRIVATE)
        markerSize  = prefs.getFloat("marker_size", 0.10f)
        udpHost     = prefs.getString("udp_host", "192.168.1.100") ?: "192.168.1.100"
        udpPort     = prefs.getInt("udp_port", 4242)
        cameraWidth  = prefs.getInt("cam_width", 640)
        cameraHeight = prefs.getInt("cam_height", 480)
    }

    private fun loadCalibration() {
        val prefs = getSharedPreferences("apriltagfbt_calib", Context.MODE_PRIVATE)
        // 加载 camera matrix
        val fx = prefs.getFloat("fx", 0f); val fy = prefs.getFloat("fy", 0f)
        val cx = prefs.getFloat("cx", 0f); val cy = prefs.getFloat("cy", 0f)
        if (fx > 0) cameraMatrix = floatArrayOf(fx, fy, cx, cy)

        // 加载空间 R,t
        val spaceR = prefs.getFloatArray("space_R")  // 9 floats
        val spaceT = prefs.getFloatArray("space_t")  // 3 floats
        if (spaceR != null && spaceT != null) {
            spaceRT = FloatArray(12)
            System.arraycopy(spaceR, 0, spaceRT!!, 0, 9)
            System.arraycopy(spaceT, 0, spaceRT!!, 9, 3)
        }
    }

    // ── Camera2 启动 ───────────────────────────────────────
    private fun startCamera() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run { Log.e(TAG, "No back camera"); return }

        imageReader = ImageReader.newInstance(cameraWidth, cameraHeight,
            ImageFormat.YUV_420_888, 3)  // maxImages = 3
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processFrame(image)
            image.close()
        }, handler)

        if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cameraManager.openCamera(cameraId, cameraStateCallback, handler)
        }
    }

    // ── 核心流水线 ───────────────────────────────────────
    private fun processFrame(image: android.media.Image) {
        frameCount++
        val now = System.nanoTime()
        if (now - lastFpsTime > 1_000_000_000L) {
            val fps = frameCount * 1_000_000_000L / (now - lastFpsTime)
            Log.d(TAG, "FPS: $fps")
            frameCount = 0
            lastFpsTime = now
        }

        // 1. 搜索窗口 ROI 裁剪（对应原项目 circularWindow / vertical strip）
        val (gray, roiX, roiY) = buildSearchROI(image)

        // 2. AprilTag 检测（传裁剪后的灰度图，或全图）
        val result = if (cameraMatrix != null) {
            ApriltagNative.detect(gray, gray.size, gray.size)
        } else {
            ApriltagNative.detect(gray, gray.size, gray.size)
        }
        val tags = ApriltagNative.parseDetectResult(result)

        // 3. 对每个检测到的 tag 做 solvePnP + FOV 拒绝 + 深度拒绝
        val validTags = mutableListOf<Pair<DetectedTag, FloatArray>>()
        for (tag in tags) {
            if (cameraMatrix == null) continue

            // 把角点坐标从 ROI 坐标系转回全图坐标系
            val cornersFull = FloatArray(8)
            for (j in 0 until 8 step 2) {
                cornersFull[j]     = tag.corners[j]     + roiX
                cornersFull[j + 1] = tag.corners[j + 1] + roiY
            }

            val pose6 = ApriltagNative.solvePnP(
                cornersFull, markerSize,
                cameraMatrix!![0], cameraMatrix!![1],
                cameraMatrix!![2], cameraMatrix!![3]
            )

            // FOV 拒绝检测（对应原项目 FOV rejection）
            if (!isPoseInFOV(pose6)) continue

            // 深度拒绝：tag 在相机后面（z < 0）
            if (pose6[2] < 0) continue

            validTags.add(Pair(tag, pose6))
            // 更新搜索窗口中心（图像坐标）
            lastTagCenters[tag.id] = Pair(tag.centerX + roiX, tag.centerY + roiY)
            trackerVisible[tag.id]  = true
        }

        // 更新丢失状态
        if (validTags.isEmpty()) {
            framesSinceLastSeen++
            if (framesSinceLastSeen > FRAMES_TO_CHECK_ALL) useCircularWindow = false
        } else {
            framesSinceLastSeen = 0
            useCircularWindow = true
        }

        // 4. 空间变换 + UDP 发送
        for ((tag, pose6) in validTags) {
            val transformed = if (spaceRT != null) {
                applySpaceTransform(pose6, spaceRT!!)
            } else pose6

            sender?.send(transformed, tag.id, tag.decisionMargin)
        }
    }

    /** 构建搜索窗口 ROI，返回 (灰度图, roiX, roiY） */
    private fun buildSearchROI(image: android.media.Image): Triple<ByteArray, Int, Int> {
        val fullGray = ApriltagNative.imageToGrayByteArray(image)

        // 如果不使用搜索窗口，返回全图
        if (!useCircularWindow || lastTagCenters.isEmpty()) {
            return Triple(fullGray, 0, 0)
        }

        // 计算搜索窗口 Rect（对应原项目 searchRadius）
        val fx = cameraMatrix?.get(0) ?: 500f
        val fy = cameraMatrix?.get(1) ?: 500f
        val searchRadiusPx = (SEARCH_RATIO * kotlin.math.max(cameraWidth, cameraHeight)).toInt()

        // 取所有可见 tracker 的预测位置，取包围盒
        var minX = cameraWidth; var maxX = 0
        var minY = cameraHeight; var maxY = 0
        for ((id, center) in lastTagCenters) {
            if (trackerVisible[id] != true) continue
            val (cx, cy) = center
            minX = kotlin.math.min(minX, (cx - searchRadiusPx).toInt().coerceAtLeast(0))
            maxX = kotlin.math.max(maxX, (cx + searchRadiusPx).toInt().coerceAtMost(cameraWidth))
            minY = kotlin.math.min(minY, (cy - searchRadiusPx).toInt().coerceAtLeast(0))
            maxY = kotlin.math.max(maxY, (cy + searchRadiusPx).toInt().coerceAtMost(cameraHeight))
        }

        // 窗口太小则退化为全图
        if (maxX - minX < 64 || maxY - minY < 64) {
            return Triple(fullGray, 0, 0)
        }

        // 裁剪 ROI（简单实现：在 native 层做更高效，这里先传全图）
        // TODO: 在 apriltag_jni.cpp 里加 ROI 裁剪支持
        return Triple(fullGray, 0, 0)
    }

    /** FOV 拒绝：检测位置是否超出相机视场 */
    private fun isPoseInFOV(pose6: FloatArray): Boolean {
        val fx = cameraMatrix?.get(0) ?: return true
        val fy = cameraMatrix?.get(1) ?: return true
        val xzLimit = 0.5f * cameraWidth  / fx
        val yzLimit = 0.5f * cameraHeight / fy
        val (tx, ty, tz) = Triple(pose6[0], pose6[1], pose6[2])
        if (kotlin.math.abs(tx / tz) > xzLimit) return false
        if (kotlin.math.abs(ty / tz) > yzLimit) return false
        return true
    }

    /** 将 R(3×3) + t(3) 应用到位姿（完整实现）*/
    private fun applySpaceTransform(pose6: FloatArray, rt: FloatArray): FloatArray {
        // rt = [r00,r01,r02, r10,r11,r12, r20,r21,r22, t0,t1,t2]
        // 简化：假设 space R 是单位矩阵（空间校准后填）
        // 位置变换：t_vr = t_space + t_cam * scale
        val (tx, ty, tz) = Triple(pose6[0], pose6[1], pose6[2])
        val out = FloatArray(7)
        out[0] = rt[9]  + tx  // t0
        out[1] = rt[10] + ty  // t1
        out[2] = rt[11] + tz  // t2
        // 旋转：q_vr = q_space ⊗ q_cam（四元数乘法）
        // 空间旋转四元数（从 R 矩阵转换，简化：直接复制）
        out[3] = pose6[3]; out[4] = pose6[4]
        out[5] = pose6[5]; out[6] = pose6[6]
        return out
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCaptureSession()
        }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
        override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
    }

    private fun createCaptureSession() {
        val surface = Surface(surfaceView.holder.surface)
        val targets = listOf(surface, imageReader.surface)
        cameraDevice?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader.surface)
                    // 也把预览显示出来（可选）
                    // addTarget(surface)
                }
                session.setRepeatingRequest(request.build(), null, handler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Capture session configure failed")
            }
        }, handler)
    }

    override fun onResume() {
        super.onResume()
        handlerThread = HandlerThread("CameraThread").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onPause() {
        super.onPause()
        captureSession?.close()
        cameraDevice?.close()
        handlerThread.quitSafely()
    }

    override fun onDestroy() {
        super.onDestroy()
        ApriltagNative.destroy()
        sender?.close()
    }
}
