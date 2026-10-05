package com.example.floatingwindow

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 悬浮窗服务：白色悬浮球显示时间/电量/帧率，
 * 点击展开面板，列出已安装的游戏与应用（可点击启动）。
 */
class FloatingWindowService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_window"
        private const val NOTIFICATION_ID = 1
        @Volatile
        private var running = false
        val isRunning: Boolean get() = running
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var tvTime: TextView? = null
    private var tvBattery: TextView? = null
    private var tvFps: TextView? = null
    private var tvCpu: TextView? = null
    private var tvMemory: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 悬浮球拖动状态
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialBubbleX = 0
    private var initialBubbleY = 0

    // 电池
    private var charging = false
    private var batteryRegistered = false

    // 帧率（基于 Choreographer 的 vsync 计数，即屏幕刷新帧率）
    private var fpsFrameCount = 0
    private var fpsLastTime = 0L
    private var currentFps = 0
    private var fpsRegistered = false

    // CPU（读取 /proc/stat 做差值计算）
    private var prevCpuTotal = 0L
    private var prevCpuIdle = 0L

    private val timeRunnable = object : Runnable {
        override fun run() {
            updateTime()
            updateCpu()
            updateMemory()
            mainHandler.postDelayed(this, 500)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            charging = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            updateBattery()
        }
    }

    private val fpsCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            fpsFrameCount++
            val now = System.currentTimeMillis()
            if (now - fpsLastTime >= 1000) {
                currentFps = fpsFrameCount
                fpsFrameCount = 0
                fpsLastTime = now
                tvFps?.text = "帧率 $currentFps"
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        setupBubble()
        mainHandler.post(timeRunnable)
        registerBatteryReceiver()
        startFps()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        mainHandler.removeCallbacks(timeRunnable)
        unregisterBatteryReceiver()
        stopFps()
        removePanel()
        removeBubble()
    }

    // ------------------------- 悬浮球 -------------------------

    private fun setupBubble() {
        val bubble = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        tvTime = bubble.findViewById(R.id.tvTime)
        tvBattery = bubble.findViewById(R.id.tvBattery)
        tvFps = bubble.findViewById(R.id.tvFps)
        tvCpu = bubble.findViewById(R.id.tvCpu)
        tvMemory = bubble.findViewById(R.id.tvMemory)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(16)
        params.y = dp(240)

        bubble.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialBubbleX = params.x
                    initialBubbleY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = initialBubbleX + dx
                    params.y = initialBubbleY + dy
                    windowManager?.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - initialTouchX) > 10 ||
                        abs(event.rawY - initialTouchY) > 10
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        bubbleParams = params
        windowManager?.addView(bubble, params)
        updateTime()
        updateBattery()
    }

    // ------------------------- 面板 -------------------------

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        val bubble = bubbleView ?: return
        val bParams = bubbleParams ?: return

        val panel = LayoutInflater.from(this).inflate(R.layout.panel_apps, null)
        val tvTitle = panel.findViewById<TextView>(R.id.tvPanelTitle)
        val lvApps = panel.findViewById<ListView>(R.id.lvApps)
        panel.findViewById<View>(R.id.btnClose).setOnClickListener { removePanel() }

        val apps = loadInstalledApps()
        tvTitle.text = "应用与游戏 (${apps.size})"
        lvApps.adapter = AppListAdapter(apps)
        lvApps.setOnItemClickListener { _, _, position, _ ->
            launchApp(apps[position].packageName)
        }

        val panelW = dp(320)
        val panelH = dp(440)
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // 测量悬浮球实际尺寸
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val bubbleW = bubble.measuredWidth
        val bubbleH = bubble.measuredHeight

        var panelX = bParams.x
        var panelY = bParams.y + bubbleH + dp(8)
        if (panelY + panelH > screenH) {
            panelY = bParams.y - panelH - dp(8)
        }
        panelX = panelX.coerceIn(dp(8), (screenW - panelW - dp(8)).coerceAtLeast(dp(8)))

        val params = WindowManager.LayoutParams(
            panelW,
            panelH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = panelX
        params.y = panelY

        panelView = panel
        windowManager?.addView(panel, params)
    }

    private fun removePanel() {
        panelView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelView = null
    }

    private fun removeBubble() {
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
    }

    // ------------------------- 数据 -------------------------

    private fun updateTime() {
        tvTime?.text = timeFormatter.format(Date())
    }

    private fun updateBattery() {
        val level = try {
            (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            -1
        }
        tvBattery?.text = if (level >= 0) {
            "电量 $level%" + if (charging) " · 充电中" else ""
        } else {
            "电量 --"
        }
    }

    private fun updateCpu() {
        val usage = readCpuUsage()
        tvCpu?.text = if (usage >= 0f) {
            "CPU ${usage.toInt()}%"
        } else {
            "CPU --"
        }
    }

    // 读取 /proc/stat 第一行 cpu 行，通过两次采样差值计算整体 CPU 占用率
    private fun readCpuUsage(): Float {
        return try {
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/stat"))
            val line = reader.readLine()
            reader.close()
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 5) return -1f
            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val iowait = parts.getOrNull(5)?.toLong() ?: 0L
            val irq = parts.getOrNull(6)?.toLong() ?: 0L
            val softirq = parts.getOrNull(7)?.toLong() ?: 0L
            val steal = parts.getOrNull(8)?.toLong() ?: 0L
            val idleAll = idle + iowait
            val total = user + nice + system + idleAll + irq + softirq + steal
            val diffTotal = total - prevCpuTotal
            val diffIdle = idleAll - prevCpuIdle
            prevCpuTotal = total
            prevCpuIdle = idleAll
            if (diffTotal <= 0) return -1f
            (diffTotal - diffIdle) * 100f / diffTotal
        } catch (_: Exception) {
            -1f
        }
    }

    private fun updateMemory() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem
        val used = total - mi.availMem
        val pct = if (total > 0) used * 100 / total else 0
        tvMemory?.text = "内存 $pct%"
    }

    private fun startFps() {
        if (fpsRegistered) return
        fpsRegistered = true
        fpsFrameCount = 0
        fpsLastTime = System.currentTimeMillis()
        Choreographer.getInstance().postFrameCallback(fpsCallback)
    }

    private fun stopFps() {
        if (!fpsRegistered) return
        fpsRegistered = false
        Choreographer.getInstance().removeFrameCallback(fpsCallback)
    }

    private fun registerBatteryReceiver() {
        if (batteryRegistered) return
        batteryRegistered = true
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun unregisterBatteryReceiver() {
        if (!batteryRegistered) return
        batteryRegistered = false
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
    }

    private fun loadInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val apps = mutableListOf<AppInfo>()
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(main, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            val name = ri.loadLabel(pm).toString()
            val icon: Drawable = ri.loadIcon(pm)
            var isGame = false
            try {
                isGame = pm.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME
            } catch (_: Exception) {
            }
            apps.add(AppInfo(name, pkg, icon, isGame))
        }
        apps.sortWith(compareByDescending<AppInfo> { it.isGame }.thenBy { it.name.lowercase() })
        return apps
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        removePanel()
    }

    // ------------------------- 通知 -------------------------

    private fun buildNotification(): Notification {
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮窗运行中")
            .setContentText("时间 · 电量 · 帧率 · 应用列表")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------- 适配器 -------------------------

    private data class AppInfo(
        val name: String,
        val packageName: String,
        val icon: Drawable,
        val isGame: Boolean
    )

    private inner class AppListAdapter(private val apps: List<AppInfo>) : BaseAdapter() {
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val holder: ViewHolder
            if (convertView == null) {
                view = LayoutInflater.from(this@FloatingWindowService)
                    .inflate(R.layout.item_app, parent, false)
                holder = ViewHolder()
                holder.icon = view.findViewById(R.id.ivIcon)
                holder.name = view.findViewById(R.id.tvName)
                holder.tag = view.findViewById(R.id.tvTag)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as ViewHolder
            }
            val app = apps[position]
            holder.icon?.setImageDrawable(app.icon)
            holder.name?.text = app.name
            holder.tag?.visibility = if (app.isGame) View.VISIBLE else View.GONE
            return view
        }

        private class ViewHolder {
            var icon: ImageView? = null
            var name: TextView? = null
            var tag: TextView? = null
        }
    }
}