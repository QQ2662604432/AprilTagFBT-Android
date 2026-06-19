package com.apriltagfbt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        btnStart   = findViewById(R.id.btn_start)
        btnSettings = findViewById(R.id.btn_settings)

        checkPermissions()

        btnStart.setOnClickListener {
            if (CameraActivity.isRunning) {
                CameraActivity.stopTracking()
                btnStart.text = "启动追踪"
                statusText.text = "状态：已停止"
            } else {
                CameraActivity.startTracking(this)
                btnStart.text = "停止追踪"
                statusText.text = "状态：追踪中"
            }
        }

        btnSettings.setOnClickListener {
            Toast.makeText(this, "设置界面开发中", Toast.LENGTH_SHORT).show()
        }

        // 初始化 native
        ApriltagNative.init()
    }

    override fun onDestroy() {
        super.onDestroy()
        ApriltagNative.destroy()
    }

    private fun checkPermissions() {
        val perms = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE
        )
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "需要相机和网络权限", Toast.LENGTH_LONG).show()
            }
        }
    }
}
