package com.screenpilot.ai

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screenpilot.ai.accessibility.ScreenAgentAccessibilityService
import com.screenpilot.ai.agent.AgentLoopManager
import com.screenpilot.ai.ai.AgentMode
import com.screenpilot.ai.config.SecurePrefs
import com.screenpilot.ai.databinding.ActivityMainBinding
import com.screenpilot.ai.overlay.FloatingOverlayService
import com.screenpilot.ai.voice.VoiceOption

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var loopManager: AgentLoopManager
    private lateinit var prefs: SecurePrefs

    private val modelOptions = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.5-pro"
    )

    private val delayLabels = listOf(
        "0 мс — Максимальная скорость",
        "120 мс — Быстрый отклик",
        "350 мс — Сбалансировано",
        "800 мс — Плавно"
    )
    private val delayValues = listOf(0L, 120L, 350L, 800L)

    private var voiceOptions: List<VoiceOption> = emptyList()

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissionsUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loopManager = AgentLoopManager.getInstance(this)
        prefs = loopManager.prefs

        setupSettingsControls()
        setupPermissionButtons()
        refreshPermissionsUi()
    }

    override fun onResume() {
        super.onResume()
        binding.swFreeModeDefault.isChecked = prefs.freeModeEnabled
        refreshPermissionsUi()
        refreshVoicesUi()
    }

    private fun setupSettingsControls() {
        binding.etApiKey.setText(prefs.apiKey)
        binding.etMaxSteps.setText(prefs.maxSteps.toString())
        binding.swVoiceEnabled.isChecked = prefs.voiceEnabled
        binding.swFreeModeDefault.isChecked = prefs.freeModeEnabled

        val modelAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modelOptions
        )
        binding.spModel.adapter = modelAdapter
        val savedModelIdx = modelOptions.indexOf(prefs.modelName).takeIf { it >= 0 } ?: 0
        binding.spModel.setSelection(savedModelIdx)

        val delayAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            delayLabels
        )
        binding.spDelay.adapter = delayAdapter
        val savedDelayIdx = delayValues.indexOf(prefs.stepDelayMs).takeIf { it >= 0 } ?: 1
        binding.spDelay.setSelection(savedDelayIdx)

        binding.swFreeModeDefault.setOnCheckedChangeListener { _, isChecked ->
            loopManager.setMode(if (isChecked) AgentMode.FREE_ROAM else AgentMode.TASK)
        }

        binding.swVoiceEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.voiceEnabled = isChecked
            if (!isChecked) loopManager.speaker.stop()
        }

        binding.btnTestVoice.setOnClickListener {
            saveSettings(showToast = false)
            if (!prefs.voiceEnabled) {
                Toast.makeText(this, "Включите озвучку переключателем выше", Toast.LENGTH_SHORT).show()
            } else {
                loopManager.speaker.speak("Голосовой модуль готов. Жду вашу задачу.")
            }
        }

        binding.btnSaveSettings.setOnClickListener {
            saveSettings(showToast = true)
        }

        binding.btnToggleOverlay.setOnClickListener {
            saveSettings(showToast = false)
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала выдайте разрешение «Поверх других окон»", Toast.LENGTH_SHORT).show()
                openOverlaySettings()
                return@setOnClickListener
            }

            if (FloatingOverlayService.isOverlayRunning) {
                FloatingOverlayService.stop(this)
            } else {
                FloatingOverlayService.start(this)
            }
            binding.root.postDelayed({ refreshPermissionsUi() }, 200)
        }

        refreshVoicesUi()
    }

    private fun refreshVoicesUi() {
        binding.tvTtsEngine.text = "Движок: ${loopManager.speaker.getEngineName()}"
        voiceOptions = loopManager.speaker.getAvailableVoices()
        val labels = if (voiceOptions.isEmpty()) {
            listOf("Голос по умолчанию")
        } else {
            voiceOptions.map { it.label }
        }

        val voiceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        binding.spVoice.adapter = voiceAdapter

        val savedIdx = voiceOptions.indexOfFirst { it.name == prefs.voiceName }
        if (savedIdx >= 0) {
            binding.spVoice.setSelection(savedIdx)
        }

        binding.spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val chosen = voiceOptions.getOrNull(position) ?: return
                prefs.voiceName = chosen.name
                loopManager.speaker.applyVoiceByName(chosen.name)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupPermissionButtons() {
        binding.btnGrantOverlay.setOnClickListener {
            openOverlaySettings()
        }

        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun saveSettings(showToast: Boolean) {
        prefs.apiKey = binding.etApiKey.text?.toString().orEmpty()
        prefs.modelName = modelOptions.getOrElse(binding.spModel.selectedItemPosition) {
            SecurePrefs.DEFAULT_MODEL
        }
        prefs.stepDelayMs = delayValues.getOrElse(binding.spDelay.selectedItemPosition) { 120L }
        val maxSteps = binding.etMaxSteps.text?.toString()?.toIntOrNull() ?: 25
        prefs.maxSteps = maxSteps
        prefs.voiceEnabled = binding.swVoiceEnabled.isChecked
        loopManager.setMode(
            if (binding.swFreeModeDefault.isChecked) AgentMode.FREE_ROAM else AgentMode.TASK
        )

        if (showToast) {
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPermissionsUi() {
        val overlayOk = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (overlayOk) "Выдано" else "Не выдано"
        binding.tvOverlayStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (overlayOk) R.color.accent_green else R.color.accent_amber
            )
        )

        val accOk = isAccessibilityEnabled()
        binding.tvAccessibilityStatus.text = if (accOk) "Включено" else "Выключено"
        binding.tvAccessibilityStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (accOk) R.color.accent_green else R.color.accent_amber
            )
        )

        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        binding.tvNotificationStatus.text = if (notifOk) "Разрешено" else "Требуется для фоновой кнопки"
        binding.tvNotificationStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (notifOk) R.color.accent_green else R.color.accent_amber
            )
        )
        binding.btnGrantNotification.isEnabled = !notifOk

        val overlayRunning = FloatingOverlayService.isOverlayRunning
        binding.tvAgentSummary.text = if (overlayRunning) {
            "Плавающая кнопка активна поверх экрана"
        } else {
            "Плавающая кнопка не запущена"
        }
        binding.btnToggleOverlay.text = if (overlayRunning) {
            "Скрыть плавающую кнопку"
        } else {
            "Показать кнопку поверх окон"
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (ScreenAgentAccessibilityService.getInstance() != null) return true
        val expected = ComponentName(this, ScreenAgentAccessibilityService::class.java)
            .flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
