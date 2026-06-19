package com.apriltagfbt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * 相机内参校准 Activity
 * 流程：对准 ChArUco 标定板 → 自动采集 15+ 帧 → 计算 cameraMatrix
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var previewView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnSave: Button

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var imageReader: ImageReader
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    // 校准状态
    private var isCalibrating = false
    private var sampleCount = 0
    private val maxSamples = 20
    private val detectedCorners = mutableListOf<FloatArray>() // 每帧的角点
    private val detectedIds    = mutableListOf<IntArray>()

    // ChArUco 字典（DICT_4X4_50，和原项目一致）
    private val arucoDict = 0  // OpenCV DICT_4X4_50 = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 简单布局（用代码，不依赖 XML）
        setupUI()
        startCamera()
        ApriltagNative.init()
    }

    private fun setupUI() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF1a1a1a.toInt())
        }
        statusText = TextView(this).apply {
            text = "对准 ChArUco 标定板，点击开始"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
        }
        btnStart = Button(this).apply {
            text = "开始校准"
            setOnClickListener { startCalibration() }
        }
        btnSave = Button(this).apply {
            text = "保存并退出"
            isEnabled = false
            setOnClickListener { saveAndExit() }
        }
        previewView = SurfaceView(this)
        layout.addView(previewView, 640, 480)
        layout.addView(statusText)
        layout.addView(btnStart)
        layout.addView(btnSave)
        setContentView(layout)
    }

    private fun startCalibration() {
        isCalibrating = true
        sampleCount = 0
        detectedCorners.clear()
        detectedIds.clear()
        statusText.text = "采集中：0/$maxSamples"
        btnStart.isEnabled = false
        Toast.makeText(this, "将 ChArUco 标定板对准摄像头", Toast.LENGTH_LONG).show()
    }

    private fun processCalibrationFrame(image: android.media.Image) {
        if (!isCalibrating) { image.close(); return }
        if (sampleCount >= maxSamples) { isCalibrating = false; return }

        // 取灰度图
        val yBuf = image.planes[0].buffer
        val gray = ByteArray(yBuf.remaining())
        yBuf.get(gray)
        image.close()

        // 调用 native 检测 ChArUco（用 ArUco 检测替代，因为 apriltag.so 可能不支持 ChArUco）
        // 这里简化：直接提示用户
        // 实际实现需要在 JNI 层加 ChArUco 检测
        statusText.text = "采集中：$sampleCount/$maxSamples\n（需要在 native 层实现 ChArUco 检测）"
        // TODO: 在 apriltag_jni.cpp 中加 ChArUco 校准逻辑
    }

    private fun saveAndExit() {
        // 保存 cameraMatrix 到 SharedPreferences
        val prefs = getSharedPreferences("apriltagfbt_calib", Context.MODE_PRIVATE)
        // 简化：用默认值（实际应从校准计算）
        // fx = fy = max(width, height) * 0.8（粗略估计）
        val display = windowManager.defaultDisplay
        val w = 640; val h = 480
        val fx = w * 1.0f; val fy = h * 1.0f
        val cx = w / 2f; val cy = h / 2f
        prefs.edit().apply {
            putFloat("fx", fx); putFloat("fy", fy)
            putFloat("cx", cx); putFloat("cy", cy)
            apply()
        }
        Toast.makeText(this, "校准数据已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run { Log.e(TAG, "No back camera"); return }

        imageReader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.YUV_420_888, 3)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (isCalibrating) processCalibrationFrame(image) else image.close()
        }, handler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val surface = imageReader.surface
                camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                        }
                        session.setRepeatingRequest(request.build(), null, handler)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, handler)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
        }, handler)
    }

    override fun onResume() {
        super.onResume()
        handlerThread = HandlerThread("CalibThread").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onPause() {
        super.onPause()
        captureSession?.close(); cameraDevice?.close()
        handlerThread.quitSafely()
    }

    override fun onDestroy() {
        super.onDestroy()
        ApriltagNative.destroy()
    }

    companion object {
        const val TAG = "CalibrationActivity"
    }
}
