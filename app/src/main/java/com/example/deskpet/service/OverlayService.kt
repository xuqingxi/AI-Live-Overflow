package com.example.deskpet.service

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import com.example.deskpet.perception.UsageTracker
import com.example.deskpet.perception.ScreenshotObserver
import com.example.deskpet.sync.SupabaseSync
import java.util.Calendar

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var usageTracker: UsageTracker? = null
    private var screenshotObserver: ScreenshotObserver? = null
    private var supabaseSync: SupabaseSync? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 60
        private const val PET_HEIGHT_DP = 80
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("..."))
        setupOverlay()
        startPerception()
        startWhisperRotation()
        supabaseSync = SupabaseSync()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // ========== GESTURE ==========
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private val tapTimes = mutableListOf<Long>()   // 2s 窗口连击计数
    private var lastVelocityX = 0f
    private var lastVelocityY = 0f
    private var pendingFling = false

    // 手势检测器：可靠区分单击/双击/长按
    private var gestureDetector: GestureDetector? = null
    private fun createTouchListener(): View.OnTouchListener {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // 单击
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                registerTap()
                return true
            }
            // 双击
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap()
                return true
            }
            // 长按
            override fun onLongPress(e: MotionEvent) {
                onLongPress()
            }
        })

        return View.OnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    lastVelocityX = event.rawX - initialTouchX
                    lastVelocityY = event.rawY - initialTouchY
                    if (Math.abs(dx) > 20 || Math.abs(dy) > 20) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        if (windowManager != null && overlayView != null) {
                            windowManager!!.updateViewLayout(overlayView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (hasMoved) {
                        val flingVx = lastVelocityX
                        val flingVy = lastVelocityY
                        val speed = Math.sqrt((flingVx*flingVx + flingVy*flingVy).toDouble())
                        pendingFling = speed > 1200.0
                        returnHome(speed, flingVx.toInt(), flingVy.toInt())
                    } else {
                        // 单击/双击/长按交给 gestureDetector 处理
                        if (elapsed > 600) {
                            onLongPress()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // 连击计数：2 秒窗口内第 3/5/8 次触发递进
    private fun registerTap() {
        val now = System.currentTimeMillis()
        tapTimes.add(now)
        while (tapTimes.isNotEmpty() && now - tapTimes[0] > 2000) tapTimes.removeAt(0)
        val n = tapTimes.size
        when {
            n == 3 -> overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onCombo(3)", null)
            n == 5 -> overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onCombo(5)", null)
            n == 8 -> overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onCombo(8)", null)
            else -> onTap()
        }
    }

    // 松手后：Fling 甩出或普通拖拽，kotlin 驱动窗口移动（甩出+走回动画）
    private fun returnHome(speed: Double, vx: Int, vy: Int) {
        val wm = windowManager ?: return
        val curX = params?.x ?: 0
        val curY = params?.y ?: 0
        val scrW = resources.displayMetrics.widthPixels
        val scrH = resources.displayMetrics.heightPixels
        val winW = dpToPx(PET_SIZE_DP)
        val winH = dpToPx(PET_SIZE_DP)
        // 锚点：屏幕右侧内侧
        val homeX = scrW - winW - dpToPx(24)
        // Y 保持在屏幕内
        val homeY = Math.max(dpToPx(10), Math.min(scrH - winH - dpToPx(40), curY))

        // 甩出动画：沿速度方向快速滑出，能越过屏幕边缘（不做 clamp，可直接跑出可见区）
        val flingOut = android.animation.ValueAnimator.ofFloat(0f, 1f)
        flingOut.duration = if (pendingFling) 260L else 200L
        flingOut.addUpdateListener { a ->
            val t = a.animatedValue as Float
            params?.x = (curX + (vx * 1.5f * t)).toInt()
            params?.y = (curY + (vy * 1.5f * t)).toInt()
            if (wm != null && params != null) wm.updateViewLayout(overlayView, params)
        }
        flingOut.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                val fx = params?.x ?: curX
                val fy = params?.y ?: curY
                // 从甩出点爬回锚点，走很长的路，像走路回来
                val ret = android.animation.ValueAnimator.ofFloat(0f, 1f)
                ret.duration = if (pendingFling) 2600L else 800L
                ret.addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    val eased = 1 - ((1 - t) * (1 - t))
                    params?.x = (fx + (homeX - fx) * eased).toInt()
                    params?.y = (fy + (homeY - fy) * eased).toInt()
                    if (wm != null && params != null) wm.updateViewLayout(overlayView, params)
                }
                ret.start()
            }
        })
        if (pendingFling) {
            flingOut.start()
        } else {
            // 普通拖拽：直接从当前位置爬回
            val ret = android.animation.ValueAnimator.ofFloat(0f, 1f)
            ret.duration = 800L
            ret.addUpdateListener { a ->
                val t = a.animatedValue as Float
                val eased = 1 - ((1 - t) * (1 - t))
                params?.x = (curX + (homeX - curX) * eased).toInt()
                params?.y = (curY + (homeY - curY) * eased).toInt()
                if (wm != null && params != null) wm.updateViewLayout(overlayView, params)
            }
            ret.start()
        }
        // 通知 JS 做表情/气泡
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onReleased(${curX}, ${curY}, ${speed}, $vx, $vy)", null
        )
        supabaseSync?.logGesture(if (pendingFling) "fling" else "drag", curX, curY)
    }
    private fun onTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null)
        supabaseSync?.logGesture("tap", params?.x ?: 0, params?.y ?: 0)
    }

    private fun onDoubleTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDoubleTap()", null)
        supabaseSync?.logGesture("double_tap", params?.x ?: 0, params?.y ?: 0)
    }
    private fun onLongPress() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null)
        supabaseSync?.logGesture("long_press", params?.x ?: 0, params?.y ?: 0)
    }

    // ========== PERCEPTION ==========

    private fun startPerception() {
        usageTracker = UsageTracker(this) { pkg ->
            Handler(Looper.getMainLooper()).post {
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.onAppChanged('$pkg')", null
                )
                supabaseSync?.logAppUsage(pkg)
            }
        }
        usageTracker?.start()

        screenshotObserver = ScreenshotObserver {
            Handler(Looper.getMainLooper()).post {
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.onScreenshot()", null
                )
            }
        }
        screenshotObserver?.start()
    }

    // ========== NOTIFICATION ==========

    private val generalWhispers = listOf(
        "看着你呢",
        "戳我干嘛",
        "哼",
        "不许关掉我",
        "你又在刷什么",
        "理我一下嘛"
    )

    private fun getWhisper(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour in 0..5 -> "还不睡？"
            hour in 6..8 -> "早啊"
            hour in 12..13 -> "记得吃饭"
            else -> generalWhispers.random()
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("\uD83D\uDC3E")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Pet", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startWhisperRotation() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(getWhisper()))
                handler.postDelayed(this, 3600_000L)
            }
        }, 3600_000L)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        usageTracker?.stop()
        screenshotObserver?.stop()
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}