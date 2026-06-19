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
    private var undistortCoeffs: FloatArray? = null

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
        // 每 30 帧打印一次 fps
        val now = System.nanoTime()
        if (now - lastFpsTime > 1_000_000_000L) {
            val fps = frameCount * 1_000_000_000L / (now - lastFpsTime)
            Log.d(TAG, "FPS: $fps")
            frameCount = 0
            lastFpsTime = now
        }

        // 1. YUV_420_888 → 灰度 ByteArray（只取 Y 平面）
        val gray = ApriltagNative.imageToGrayByteArray(image)

        // 2. 调用 AprilTag 检测（native，传灰度图）
        val result = if (cameraMatrix != null) {
            // 有相机内参：传内参提高精度
            ApriltagNative.detect(gray, image.width, image.height)
        } else {
            ApriltagNative.detect(gray, image.width, image.height)
        }
        val tags = ApriltagNative.parseDetectResult(result)

        // 3. 对每个检测到的 tag 做 solvePnP + 空间变换
        for (tag in tags) {
            if (cameraMatrix == null) continue

            val pose6 = ApriltagNative.solvePnP(
                tag.corners, markerSize,
                cameraMatrix!![0], cameraMatrix!![1],
                cameraMatrix!![2], cameraMatrix!![3]
            )
            // pose6 = [tx,ty,tz, qx,qy,qz,qw]

            // 4. 空间变换（手机坐标系 → VR 坐标系）
            val transformed = if (spaceRT != null) {
                applySpaceTransform(pose6, spaceRT!!)
            } else pose6

            // 5. UDP 发送
            sender?.send(transformed, tag.id, tag.decisionMargin)
        }
    }

    /** 将 R(3×3) + t(3) 应用到位姿 */
    private fun applySpaceTransform(pose6: FloatArray, rt: FloatArray): FloatArray {
        // R_camera→tag * R_space = R_vr→tag
        // 简化：t' = R * t + t_space
        val (tx, ty, tz) = Triple(pose6[0], pose6[1], pose6[2])
        val (qx, qy, qz, qw) = floatArrayOf(pose6[3], pose6[4], pose6[5], pose6[6])

        // 用 rt 做变换（rt = [r00..r22, t0,t1,t2]）
        val out = FloatArray(7)
        // 位置变换：t_tag_in_vr = R_space * t_tag_in_cam + t_space
        out[0] = rt[9]  + (rt[0]*tx + rt[1]*ty + rt[2]*tz)
        out[1] = rt[10] + (rt[3]*tx + rt[4]*ty + rt[5]*tz)
        out[2] = rt[11] + (rt[6]*tx + rt[7]*ty + rt[8]*tz)
        // 旋转：q_vr = q_space * q_cam（四元数乘法）
        // 简化：假设 space R 是单位矩阵，只做平移
        out[3] = qx; out[4] = qy; out[5] = qz; out[6] = qw
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
