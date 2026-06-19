package com.apriltagfbt

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置界面：IP/端口/tag 尺寸/相机分辨率
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val prefs = getSharedPreferences("apriltagfbt", Context.MODE_PRIVATE)

        fun addField(label: String, key: String, default: String): EditText {
            val tv = android.widget.TextView(this).apply {
                text = label
                textSize = 16f
            }
            val et = EditText(this).apply {
                setText(prefs.getString(key, default))
            }
            layout.addView(tv)
            layout.addView(et)
            return et
        }

        val etHost     = addField("PC IP 地址", "udp_host", "192.168.1.100")
        val etPort     = addField("UDP 端口", "udp_port", "4242")
        val etMarker   = addField("Tag 尺寸（米）", "marker_size", "0.10")
        val etCamWidth = addField("相机宽度", "cam_width", "640")
        val etCamHeight= addField("相机高度", "cam_height", "480")

        val btnSave = Button(this).apply {
            text = "保存"
            setOnClickListener {
                prefs.edit().apply {
                    putString("udp_host", etHost.text.toString())
                    putInt("udp_port", etPort.text.toString().toIntOrNull() ?: 4242)
                    putFloat("marker_size", etMarker.text.toString().toFloatOrNull() ?: 0.10f)
                    putInt("cam_width", etCamWidth.text.toString().toIntOrNull() ?: 640)
                    putInt("cam_height", etCamHeight.text.toString().toIntOrNull() ?: 480)
                    apply()
                }
                finish()
            }
        }
        layout.addView(btnSave)

        // 校准入口
        val btnCalib = Button(this).apply {
            text = "相机校准（ChArUco）"
            setOnClickListener {
                startActivity(android.content.Intent(this@SettingsActivity, CalibrationActivity::class.java))
            }
        }
        layout.addView(btnCalib)

        setContentView(layout)
    }
}
