package com.screenpilot.ai.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.screenpilot.ai.MainActivity
import com.screenpilot.ai.R
import com.screenpilot.ai.agent.AgentLoopManager
import com.screenpilot.ai.agent.AgentUiState
import com.screenpilot.ai.ai.AgentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var loopManager: AgentLoopManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelAttached = false

    private var targetView: TargetIndicatorView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isOverlayRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loopManager = AgentLoopManager.getInstance(this)

        startForegroundNotification()
        setupTargetOverlay()
        setupFloatingBubble()
        observeAgentState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_AGENT) {
            loopManager.stop("Остановлено из уведомления")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isOverlayRunning = false
        stateJob?.cancel()
        serviceScope.cancel()
        loopManager.stop("Сервис оверлея закрыт")

        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        if (panelAttached) {
            panelView?.let { runCatching { windowManager.removeView(it) } }
            panelAttached = false
        }
        targetView?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val channelId = "screenpilot_overlay_channel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "ScreenPilot AI Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingOverlayService::class.java).setAction(ACTION_STOP_AGENT),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenPilot AI активен")
            .setContentText("Плавающая кнопка управления поверх окон")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_media_pause, "СТОП", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun setupTargetOverlay() {
        val tv = TargetIndicatorView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        targetView = tv
        windowManager.addView(tv, params)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupFloatingBubble() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_floating_button, null)
        val btn = view.findViewById<TextView>(R.id.btnFloatingBubble)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = loopManager.prefs.bubbleX
            y = loopManager.prefs.bubbleY
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                    }
                    params.x = (initialX + dx).coerceAtLeast(0)
                    params.y = (initialY + dy).coerceAtLeast(0)
                    windowManager.updateViewLayout(view, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        loopManager.prefs.bubbleX = params.x
                        loopManager.prefs.bubbleY = params.y
                    } else {
                        onBubbleTapped()
                    }
                    true
                }

                else -> false
            }
        }

        bubbleView = view
        bubbleParams = params
        windowManager.addView(view, params)
    }

    private fun onBubbleTapped() {
        val state = loopManager.state.value
        if (state.isRunning) {
            loopManager.stop("Остановлено плавающей кнопкой")
            showControlPanel()
        } else {
            if (panelAttached) {
                hideControlPanel()
            } else {
                showControlPanel()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun ensurePanelCreated() {
        if (panelView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_control_panel, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 140
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val dragHeader = view.findViewById<View>(R.id.panelDragHeader)
        var startY = 0
        var startX = 0
        var rawStartX = 0f
        var rawStartY = 0f

        dragHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    rawStartX = event.rawX
                    rawStartY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - rawStartX).toInt()
                    params.y = (startY + (event.rawY - rawStartY).toInt()).coerceAtLeast(40)
                    if (panelAttached) {
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }

                else -> false
            }
        }

        val btnMinimize = view.findViewById<Button>(R.id.btnPanelMinimize)
        val btnVoice = view.findViewById<Button>(R.id.btnPanelVoice)
        val btnModeTask = view.findViewById<Button>(R.id.btnModeTask)
        val btnModeFree = view.findViewById<Button>(R.id.btnModeFree)
        val btnStart = view.findViewById<Button>(R.id.btnPanelStart)
        val btnStop = view.findViewById<Button>(R.id.btnPanelStop)
        val etInput = view.findViewById<EditText>(R.id.etPanelInput)

        btnVoice.setOnClickListener {
            val next = !loopManager.prefs.voiceEnabled
            loopManager.prefs.voiceEnabled = next
            if (!next) loopManager.speaker.stop()
            btnVoice.text = if (next) "Голос: ВКЛ" else "Голос: ВЫКЛ"
        }

        btnModeTask.setOnClickListener {
            loopManager.setMode(AgentMode.TASK)
        }

        btnModeFree.setOnClickListener {
            loopManager.setMode(AgentMode.FREE_ROAM)
        }

        btnMinimize.setOnClickListener {
            hideControlPanel()
        }

        btnStart.setOnClickListener {
            val rawText = etInput.text?.toString().orEmpty().trim()
            val selectedMode = if (rawText.isEmpty()) {
                AgentMode.FREE_ROAM
            } else {
                AgentMode.TASK
            }
            if (rawText.isNotEmpty()) {
                etInput.setText("")
            }
            hideControlPanel()
            loopManager.start(rawText, selectedMode)
        }

        btnStop.setOnClickListener {
            loopManager.stop("Остановлено пользователем")
        }

        panelView = view
        panelParams = params
    }

    private fun showControlPanel() {
        ensurePanelCreated()
        val view = panelView ?: return
        val params = panelParams ?: return
        if (!panelAttached) {
            windowManager.addView(view, params)
            panelAttached = true
            syncPanelViews(loopManager.state.value)
        }
    }

    private fun hideControlPanel() {
        val view = panelView ?: return
        if (panelAttached) {
            runCatching { windowManager.removeView(view) }
            panelAttached = false
        }
    }

    private fun observeAgentState() {
        stateJob = serviceScope.launch {
            loopManager.state.collectLatest { state ->
                val bubbleBtn = bubbleView?.findViewById<TextView>(R.id.btnFloatingBubble)
                if (bubbleBtn != null) {
                    if (state.isRunning) {
                        bubbleBtn.text = "СТОП"
                        bubbleBtn.setBackgroundResource(R.drawable.bg_floating_btn_running)
                    } else {
                        bubbleBtn.text = if (state.mode == AgentMode.FREE_ROAM) "FREE" else "AI"
                        bubbleBtn.setBackgroundResource(R.drawable.bg_floating_btn_idle)
                    }
                }

                val tvTarget = targetView
                val target = state.activeTarget
                if (tvTarget != null) {
                    if (target != null) {
                        tvTarget.showTarget(target.x, target.y, target.endX, target.endY)
                    } else {
                        tvTarget.clearTarget()
                    }
                }

                if (state.isWaitingUserReply && !panelAttached) {
                    showControlPanel()
                }

                if (panelAttached) {
                    syncPanelViews(state)
                }
            }
        }
    }

    private fun syncPanelViews(state: AgentUiState) {
        val view = panelView ?: return
        val tvStatus = view.findViewById<TextView>(R.id.tvPanelStatus)
        val tvLog = view.findViewById<TextView>(R.id.tvPanelLog)
        val svLog = view.findViewById<ScrollView>(R.id.svPanelLog)
        val btnVoice = view.findViewById<Button>(R.id.btnPanelVoice)
        val btnModeTask = view.findViewById<Button>(R.id.btnModeTask)
        val btnModeFree = view.findViewById<Button>(R.id.btnModeFree)
        val etInput = view.findViewById<EditText>(R.id.etPanelInput)

        val activeTint = ColorStateList.valueOf(Color.parseColor("#2563EB"))
        val inactiveTint = ColorStateList.valueOf(Color.parseColor("#1E293B"))

        if (state.mode == AgentMode.FREE_ROAM) {
            btnModeFree.backgroundTintList = activeTint
            btnModeFree.setTextColor(Color.WHITE)
            btnModeTask.backgroundTintList = inactiveTint
            btnModeTask.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            btnModeTask.backgroundTintList = activeTint
            btnModeTask.setTextColor(Color.WHITE)
            btnModeFree.backgroundTintList = inactiveTint
            btnModeFree.setTextColor(Color.parseColor("#94A3B8"))
        }

        val goalSuffix = if (state.mode == AgentMode.FREE_ROAM && state.currentMiniGoal.isNotBlank()) {
            " · Цель: ${state.currentMiniGoal}"
        } else {
            ""
        }
        tvStatus.text = "${state.statusText}$goalSuffix"
        tvLog.text = state.logText
        btnVoice.text = if (loopManager.prefs.voiceEnabled) "Голос: ВКЛ" else "Голос: ВЫКЛ"

        etInput.hint = when {
            state.isWaitingUserReply -> "Ответьте на вопрос агента..."
            state.mode == AgentMode.FREE_ROAM -> "Пусто = Свободный полёт (или введите задачу)"
            else -> "Команда (пусто = Свободный полёт)"
        }
        svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val NOTIF_ID = 1042
        const val ACTION_STOP_AGENT = "com.screenpilot.ai.ACTION_STOP_AGENT"

        @Volatile
        var isOverlayRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }
}
