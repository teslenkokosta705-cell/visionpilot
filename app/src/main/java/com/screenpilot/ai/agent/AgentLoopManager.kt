package com.screenpilot.ai.agent

import android.content.Context
import com.screenpilot.ai.accessibility.ScreenAgentAccessibilityService
import com.screenpilot.ai.ai.AgentMode
import com.screenpilot.ai.ai.AgentStepDecision
import com.screenpilot.ai.ai.GeminiAgentClient
import com.screenpilot.ai.config.SecurePrefs
import com.screenpilot.ai.voice.VoiceSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AgentUiState(
    val mode: AgentMode = AgentMode.TASK,
    val isRunning: Boolean = false,
    val isWaitingUserReply: Boolean = false,
    val currentMiniGoal: String = "",
    val statusText: String = "Готов к работе",
    val logText: String = "Введите короткую задачу или нажмите СТАРТ с пустым полем для Свободного режима.",
    val currentStep: Int = 0,
    val activeTarget: TargetVisual? = null
)

data class TargetVisual(
    val x: Int,
    val y: Int,
    val endX: Int?,
    val endY: Int?
)

class AgentLoopManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    val prefs = SecurePrefs(appContext)
    val speaker = VoiceSpeaker(appContext, prefs)
    private val gemini = GeminiAgentClient()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    private val _state = MutableStateFlow(
        AgentUiState(
            mode = if (prefs.freeModeEnabled) AgentMode.FREE_ROAM else AgentMode.TASK
        )
    )
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var currentTask: String = ""
    private val logLines = ArrayDeque<String>()

    fun setMode(mode: AgentMode) {
        prefs.freeModeEnabled = (mode == AgentMode.FREE_ROAM)
        updateState(mode = mode)
    }

    fun start(taskInput: String, explicitMode: AgentMode? = null) {
        val text = taskInput.trim()

        val isReply = _state.value.isWaitingUserReply && _state.value.mode == AgentMode.TASK && currentTask.isNotBlank()

        // Empty input on START automatically launches Free Mode as configured
        val targetMode = when {
            isReply -> AgentMode.TASK
            text.isEmpty() -> AgentMode.FREE_ROAM
            explicitMode != null -> explicitMode
            else -> AgentMode.TASK
        }
        prefs.freeModeEnabled = (targetMode == AgentMode.FREE_ROAM)

        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            updateState(
                mode = targetMode,
                isRunning = false,
                statusText = "Нет Gemini API Key",
                activeTarget = null
            )
            appendLog("Укажите Gemini API Key в настройках приложения.")
            speaker.speak("Укажите ключ Gemini API в настройках.")
            return
        }

        val service = ScreenAgentAccessibilityService.getInstance()
        if (service == null) {
            updateState(
                mode = targetMode,
                isRunning = false,
                statusText = "Accessibility выключен",
                activeTarget = null
            )
            appendLog("Включите службу Accessibility в настройках Android.")
            speaker.speak("Включите службу специальных возможностей.")
            return
        }

        val userReply = if (isReply) text else null
        if (!isReply) {
            currentTask = text
            logLines.clear()
            if (targetMode == AgentMode.FREE_ROAM) {
                appendLog("Режим: Свободный полёт (до $FREE_MODE_MAX_STEPS шагов)")
            } else {
                appendLog("Задача: $currentTask")
            }
        } else {
            appendLog("Ваш ответ: $text")
        }

        loopJob?.cancel()
        loopJob = scope.launch {
            runAgentLoop(service, targetMode, currentTask, userReply)
        }
    }

    fun stop(reason: String = "Остановлено пользователем") {
        loopJob?.cancel()
        loopJob = null
        speaker.stop()
        updateState(
            isRunning = false,
            isWaitingUserReply = false,
            statusText = reason,
            activeTarget = null
        )
        appendLog("■ $reason")
    }

    private suspend fun runAgentLoop(
        service: ScreenAgentAccessibilityService,
        mode: AgentMode,
        task: String,
        initialReply: String?
    ) {
        val maxSteps = if (mode == AgentMode.FREE_ROAM) FREE_MODE_MAX_STEPS else prefs.maxSteps
        val recentScreenHashes = ArrayDeque<Int>()
        val recentSignatures = ArrayDeque<String>()
        val historySteps = ArrayDeque<String>()
        val installedAppsHint = if (mode == AgentMode.FREE_ROAM) {
            service.getSafeInstalledAppsHint()
        } else {
            ""
        }

        var pendingReply = initialReply
        var activeMiniGoal = ""
        var lastSpokenMiniGoal = ""
        var stepsOnSameScreen = 0
        var lastVisualHash = 0

        updateState(
            mode = mode,
            isRunning = true,
            isWaitingUserReply = false,
            currentMiniGoal = "",
            statusText = if (mode == AgentMode.FREE_ROAM) {
                "Свободный полёт · старт..."
            } else {
                "Выполняю задачу..."
            },
            currentStep = 0
        )

        try {
            for (step in 1..maxSteps) {
                if (!scope.isActive) break

                updateState(
                    statusText = "Шаг $step/$maxSteps · снимок экрана...",
                    currentStep = step,
                    activeTarget = null
                )

                val snapshot = service.captureScreenSnapshot()
                if (snapshot.visualHash == lastVisualHash) {
                    stepsOnSameScreen++
                } else {
                    stepsOnSameScreen = 1
                    lastVisualHash = snapshot.visualHash
                }

                recentScreenHashes.addLast(snapshot.visualHash)
                if (recentScreenHashes.size > 4) recentScreenHashes.removeFirst()

                // Anti-stuck automatic recovery in Free Mode (3 identical screenshots in a row)
                val stuckIdenticalScreens =
                    recentScreenHashes.size >= 3 &&
                        recentScreenHashes.takeLast(3).distinct().size == 1

                if (mode == AgentMode.FREE_ROAM && stuckIdenticalScreens) {
                    appendLog("↺ Застревание на одном экране — выхожу на рабочий стол")
                    service.performNavHome()
                    activeMiniGoal = ""
                    recentScreenHashes.clear()
                    recentSignatures.clear()
                    historySteps.addLast("Шаг $step: HOME (выход из застрявшего экрана)")
                    if (historySteps.size > 8) historySteps.removeFirst()
                    delay(650L)
                    continue
                }

                var loopWarning: String? = null
                if (mode == AgentMode.FREE_ROAM && stepsOnSameScreen >= 3) {
                    loopWarning =
                        "Ты уже $stepsOnSameScreen шага на одном экране. Смени экран (OPEN_APP или HOME) и выбери новую мини-цель."
                } else if (recentSignatures.size >= 3 &&
                    recentSignatures.takeLast(3).distinct().size == 1
                ) {
                    loopWarning =
                        "Повторяется одинаковое действие. Выбери другой элемент, пролистай или нажми BACK."
                }

                updateState(statusText = "Шаг $step/$maxSteps · Gemini анализирует...")
                val decision: AgentStepDecision = gemini.decideNextStep(
                    apiKey = prefs.apiKey,
                    modelName = prefs.modelName,
                    mode = mode,
                    task = task,
                    userReply = pendingReply,
                    currentMiniGoal = activeMiniGoal,
                    installedAppsHint = installedAppsHint,
                    screenshotBase64 = snapshot.screenshotBase64,
                    uiTreeSummary = snapshot.uiTreeSummary,
                    historySummary = historySteps.joinToString("\n"),
                    screenWidth = snapshot.width,
                    screenHeight = snapshot.height,
                    loopWarning = loopWarning
                )
                pendingReply = null

                val incomingGoal = decision.miniGoal.trim()
                val goalChanged = incomingGoal.isNotBlank() &&
                    !incomingGoal.equals(activeMiniGoal, ignoreCase = true)
                if (incomingGoal.isNotBlank()) {
                    activeMiniGoal = incomingGoal
                }

                // Safety Guardrails in Kotlin BEFORE executing any action in Free Mode
                if (mode == AgentMode.FREE_ROAM) {
                    val targetLabel = if (decision.action == "TAP") {
                        service.findNodeLabelNear(decision.x, decision.y)
                    } else {
                        ""
                    }
                    when (val verdict = SafetyGuardrails.evaluateFreeModeAction(decision, snapshot, targetLabel)) {
                        is GuardrailVerdict.Blocked -> {
                            appendLog("🛡 Блок: ${verdict.reason}. Возврат HOME.")
                            service.performNavHome()
                            activeMiniGoal = ""
                            historySteps.addLast("Шаг $step: ЗАБЛОКИРОВАНО (${verdict.reason}) -> HOME")
                            if (historySteps.size > 8) historySteps.removeFirst()
                            delay(650L)
                            continue
                        }
                        GuardrailVerdict.Allowed -> Unit
                    }
                }

                // Voice output rules:
                // - FREE_ROAM: speak ONLY when a new mini-goal is chosen/switched
                // - TASK: speak concise commentary on actions / questions
                if (decision.voice.isNotBlank()) {
                    if (mode == AgentMode.TASK) {
                        speaker.speak(decision.voice)
                    } else if (goalChanged || lastSpokenMiniGoal.isBlank()) {
                        lastSpokenMiniGoal = activeMiniGoal
                        speaker.speak(decision.voice)
                    }
                }

                val goalTag = if (activeMiniGoal.isNotBlank()) " [$activeMiniGoal]" else ""
                appendLog(
                    "#$step [${decision.action}]$goalTag (${decision.latencyMs}мс) ${decision.thought}"
                )

                val signature = "${decision.action}:${decision.x / 40}:${decision.y / 40}:${snapshot.visualHash}"
                recentSignatures.addLast(signature)
                if (recentSignatures.size > 5) recentSignatures.removeFirst()

                historySteps.addLast(
                    "Шаг $step: ${decision.action} (${decision.x},${decision.y}) text='${decision.text}' -> ${decision.thought}"
                )
                if (historySteps.size > 8) historySteps.removeFirst()

                updateState(currentMiniGoal = activeMiniGoal)

                when (decision.action) {
                    "DONE" -> {
                        if (mode == AgentMode.FREE_ROAM) {
                            // In Free Mode, DONE just means mini-goal finished -> pivot to next goal
                            activeMiniGoal = ""
                            service.performNavHome()
                            delay(500L)
                        } else {
                            val doneMsg = decision.voice.ifBlank { "Задача выполнена." }
                            updateState(
                                isRunning = false,
                                isWaitingUserReply = false,
                                statusText = "Готово: $doneMsg",
                                activeTarget = null
                            )
                            appendLog("✓ $doneMsg")
                            return
                        }
                    }

                    "ASK_USER" -> {
                        if (mode == AgentMode.FREE_ROAM) {
                            // Free Mode stays autonomous: pivot instead of blocking on user
                            activeMiniGoal = ""
                            service.performNavBack()
                            delay(400L)
                        } else {
                            val question = decision.voice.ifBlank { decision.thought }
                            updateState(
                                isRunning = false,
                                isWaitingUserReply = true,
                                statusText = "Уточнение: $question",
                                activeTarget = null
                            )
                            appendLog("? Агент спрашивает: $question")
                            speaker.speak(question)
                            return
                        }
                    }

                    "TAP" -> {
                        updateState(
                            statusText = "Тап (${decision.x}, ${decision.y})",
                            activeTarget = TargetVisual(decision.x, decision.y, null, null)
                        )
                        delay(110L)
                        service.performTap(decision.x, decision.y)
                    }

                    "SWIPE" -> {
                        updateState(
                            statusText = "Свайп (${decision.x},${decision.y}) → (${decision.x2},${decision.y2})",
                            activeTarget = TargetVisual(
                                decision.x,
                                decision.y,
                                decision.x2,
                                decision.y2
                            )
                        )
                        delay(110L)
                        service.performSwipe(decision.x, decision.y, decision.x2, decision.y2)
                    }

                    "TYPE" -> {
                        updateState(
                            statusText = "Ввод: ${decision.text.take(24)}",
                            activeTarget = TargetVisual(decision.x, decision.y, null, null)
                        )
                        service.performInputText(decision.text, decision.x, decision.y)
                    }

                    "BACK" -> {
                        updateState(statusText = "Назад", activeTarget = null)
                        service.performNavBack()
                    }

                    "HOME" -> {
                        updateState(statusText = "Рабочий стол", activeTarget = null)
                        service.performNavHome()
                    }

                    "OPEN_APP" -> {
                        updateState(statusText = "Открываю ${decision.text}", activeTarget = null)
                        val opened = service.openAppOrUrl(decision.text)
                        if (!opened) {
                            appendLog("! Не удалось открыть «${decision.text}»")
                        }
                    }

                    else -> {
                        service.performNavBack()
                    }
                }

                updateState(activeTarget = null)
                val stepDelay = if (prefs.stepDelayMs == 0L) 0L else decision.delayMs.coerceAtMost(prefs.stepDelayMs + 400L)
                if (stepDelay > 0L) {
                    delay(stepDelay)
                }
            }

            val finishMsg = if (mode == AgentMode.FREE_ROAM) {
                "Свободный полёт завершён ($maxSteps шагов)"
            } else {
                "Достигнут лимит шагов ($maxSteps)"
            }
            updateState(
                isRunning = false,
                isWaitingUserReply = false,
                statusText = finishMsg,
                activeTarget = null
            )
            appendLog("■ $finishMsg")
            speaker.speak(finishMsg)
        } catch (_: CancellationException) {
            // Instant stop requested by user
        } catch (err: Throwable) {
            val msg = err.message ?: "Неизвестная ошибка"
            updateState(
                isRunning = false,
                isWaitingUserReply = false,
                statusText = "Ошибка: $msg",
                activeTarget = null
            )
            appendLog("Ошибка: $msg")
            speaker.speak("Произошла ошибка.")
        }
    }

    private fun updateState(
        mode: AgentMode = _state.value.mode,
        isRunning: Boolean = _state.value.isRunning,
        isWaitingUserReply: Boolean = _state.value.isWaitingUserReply,
        currentMiniGoal: String = _state.value.currentMiniGoal,
        statusText: String = _state.value.statusText,
        currentStep: Int = _state.value.currentStep,
        activeTarget: TargetVisual? = _state.value.activeTarget
    ) {
        _state.value = _state.value.copy(
            mode = mode,
            isRunning = isRunning,
            isWaitingUserReply = isWaitingUserReply,
            currentMiniGoal = currentMiniGoal,
            statusText = statusText,
            currentStep = currentStep,
            activeTarget = activeTarget
        )
    }

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 35) {
                logLines.removeFirst()
            }
            _state.value = _state.value.copy(logText = logLines.joinToString("\n"))
        }
    }

    companion object {
        const val FREE_MODE_MAX_STEPS = 20

        @Volatile
        private var instance: AgentLoopManager? = null

        fun getInstance(context: Context): AgentLoopManager {
            return instance ?: synchronized(this) {
                instance ?: AgentLoopManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
