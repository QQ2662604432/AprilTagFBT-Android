package com.apriltagfbt

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer
import java.util.*

/**
 * Camera2 取帧 → AprilTag 检测 → UDP 发送
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var imageReader: ImageReader
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    // 追踪参数（后续从设置读）
    private var markerSize = 0.10f // 10cm
    private var udpHost   = "192.168.1.100"
    private var udpPort   = 4242

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        sender = UDPSender(udpHost, udpPort)
        startCamera()
    }

    private fun startCamera() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run {
            Log.e(TAG, "No back camera found")
            return
        }

        // ImageReader: YUV_420_888，640×480，按需改分辨率
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
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

    private fun processFrame(image: android.media.Image) {
        // 将 YUV_420_888 转为 NV21 字节数组（传给 native）
        val planes = image.planes
        val yBuf  = planes[0].buffer
        val uBuf  = planes[1].buffer
        val vBuf  = planes[2].buffer
        val ySize = yBuf.remaining()
        val uvSize = ySize / 2 // NV21 = Y + VU 交错
        val nv21 = ByteArray(ySize + uvSize)

        yBuf.get(nv21, 0, ySize)
        // 简化的 NV21 打包：实际项目用完整实现
        // 这里直接传 Y 平面给 native（灰度检测够用）
        val gray = ByteArray(ySize)
        System.arraycopy(nv21, 0, gray, 0, ySize)

        // 调用 native 检测
        val result = ApriltagNative.detect(gray, image.width, image.height)
        val tags = ApriltagNative.parseDetectResult(result)

        // 对每个检测到的 tag 做 solvePnP，然后发送
        for (tag in tags) {
            val pose6 = ApriltagNative.solvePnP(
                tag.corners, markerSize,
                fx = 500f, fy = 500f,  // 相机内参，实际需要校准
                cx = image.width / 2f, cy = image.height / 2f
            )
            sender?.send(pose6, tag.id)
        }
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
        val surface = imageReader.surface
        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                }
                session.setRepeatingRequest(request.build(), null, handler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
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
        sender?.close()
    }

    companion object {
        private const val TAG = "CameraActivity"
        var isRunning = false
            private set
        private var sender: UDPSender? = null

        fun startTracking(ctx: Context) {
            isRunning = true
            // 实际通过 startActivity 启动
        }
        fun stopTracking() { isRunning = false }
    }
}
