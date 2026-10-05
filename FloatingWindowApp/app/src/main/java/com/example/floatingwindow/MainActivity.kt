package com.example.floatingwindow

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 主界面：申请悬浮窗权限，启动/关闭悬浮窗服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateStatus()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                startFloatingService()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, FloatingWindowService::class.java))
            Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "请授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        val canOverlay = Settings.canDrawOverlays(this)
        val running = FloatingWindowService.isRunning
        tvStatus.text = buildString {
            append("悬浮窗权限：").append(if (canOverlay) "已授权" else "未授权").append("\n")
            append("悬浮窗状态：").append(if (running) "运行中" else "未运行")
        }
    }
}