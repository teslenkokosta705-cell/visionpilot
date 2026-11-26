package com.screenpilot.ai.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class AgentMode {
    TASK,
    FREE_ROAM
}

data class AgentStepDecision(
    val thought: String,
    val voice: String,
    val action: String,
    val x: Int,
    val y: Int,
    val x2: Int,
    val y2: Int,
    val text: String,
    val delayMs: Long,
    val miniGoal: String,
    val latencyMs: Long
)

class GeminiAgentClient {

    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun decideNextStep(
        apiKey: String,
        modelName: String,
        mode: AgentMode,
        task: String,
        userReply: String?,
        currentMiniGoal: String,
        installedAppsHint: String,
        screenshotBase64: String?,
        uiTreeSummary: String,
        historySummary: String,
        screenWidth: Int,
        screenHeight: Int,
        loopWarning: String?
    ): AgentStepDecision {
        val startedAt = System.currentTimeMillis()
        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val systemInstruction = buildSystemPrompt(
            mode = mode,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            installedAppsHint = installedAppsHint
        )

        val userPrompt = buildString {
            if (mode == AgentMode.TASK) {
                append("Режим: ЗАДАЧА.\n")
                append("Команда пользователя: $task\n")
                if (!userReply.isNullOrBlank()) {
                    append("Ответ пользователя на твой уточняющий вопрос: $userReply\n")
                }
            } else {
                append("Режим: СВОБОДНЫЙ ПОЛЁТ (Free Mode).\n")
                if (currentMiniGoal.isNotBlank()) {
                    append("Текущая мини-цель: $currentMiniGoal\n")
                } else {
                    append("Поставь себе первую живую мини-цель из разрешённых категорий.\n")
                }
            }

            if (!loopWarning.isNullOrBlank()) {
                append("ВАЖНОЕ ПРЕДУПРЕЖДЕНИЕ: $loopWarning\n")
            }
            if (historySummary.isNotBlank()) {
                append("История последних шагов:\n$historySummary\n")
            }
            append("Текущие элементы UI на экране:\n$uiTreeSummary")
        }

        val parts = JSONArray()
        if (!screenshotBase64.isNullOrBlank()) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", screenshotBase64)
                )
            )
        }
        parts.put(JSONObject().put("text", userPrompt))

        val responseSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("thought", JSONObject().put("type", "STRING"))
                    .put("voice", JSONObject().put("type", "STRING"))
                    .put("action", JSONObject().put("type", "STRING"))
                    .put("x", JSONObject().put("type", "INTEGER"))
                    .put("y", JSONObject().put("type", "INTEGER"))
                    .put("x2", JSONObject().put("type", "INTEGER"))
                    .put("y2", JSONObject().put("type", "INTEGER"))
                    .put("text", JSONObject().put("type", "STRING"))
                    .put("delay_ms", JSONObject().put("type", "INTEGER"))
                    .put("mini_goal", JSONObject().put("type", "STRING"))
            )
            .put(
                "required",
                JSONArray()
                    .put("thought")
                    .put("voice")
                    .put("action")
                    .put("x")
                    .put("y")
                    .put("x2")
                    .put("y2")
                    .put("text")
                    .put("delay_ms")
                    .put("mini_goal")
            )

        val payload = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction))
                )
            )
            .put(
                "contents",
                JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", responseSchema)
                    .put("temperature", if (mode == AgentMode.FREE_ROAM) 0.55 else 0.2)
                    .put("maxOutputTokens", 420)
            )

        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val rawResponse = executeCancellable(request)
        val root = JSONObject(rawResponse)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("Пустой ответ от Gemini API")
        val first = candidates.optJSONObject(0)
            ?: throw IllegalStateException("Нет кандидатов в ответе Gemini API")
        val contentParts = first.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Нет содержимого в ответе Gemini")
        val jsonText = contentParts.optJSONObject(0)?.optString("text", "{}") ?: "{}"

        val parsed = JSONObject(jsonText)
        val rawAction = parsed.optString("action", "BACK").trim().uppercase()
        val normalizedAction = when (rawAction) {
            "TAP", "SWIPE", "TYPE", "BACK", "HOME", "OPEN_APP", "ASK_USER", "DONE" -> rawAction
            else -> "BACK"
        }

        return AgentStepDecision(
            thought = parsed.optString("thought", "Оцениваю текущий экран"),
            voice = parsed.optString("voice", ""),
            action = normalizedAction,
            x = parsed.optInt("x", 500).coerceIn(0, 1000),
            y = parsed.optInt("y", 500).coerceIn(0, 1000),
            x2 = parsed.optInt("x2", 500).coerceIn(0, 1000),
            y2 = parsed.optInt("y2", 500).coerceIn(0, 1000),
            text = parsed.optString("text", ""),
            delayMs = parsed.optLong("delay_ms", 450L).coerceIn(0L, 2500L),
            miniGoal = parsed.optString("mini_goal", currentMiniGoal),
            latencyMs = System.currentTimeMillis() - startedAt
        )
    }

    private fun buildSystemPrompt(
        mode: AgentMode,
        screenWidth: Int,
        screenHeight: Int,
        installedAppsHint: String
    ): String {
        val coreRules = """
Ты — быстрый, умный и адекватный автономный AI-агент управления Android-смартфоном.
Реальное разрешение экрана: ${screenWidth}x${screenHeight}.
ВАЖНО: координаты x, y, x2, y2 возвращай в нормированной сетке от 0 до 1000 (0 — левый/верхний край, 1000 — правый/нижний край), чтобы точно попадать по элементам интерфейса с любого скриншота.

Доступные действия (поле action, СТРОГО В ВЕРХНЕМ РЕГИСТРЕ):
- TAP: одиночное нажатие в точку (x, y).
- SWIPE: свайп от (x, y) к (x2, y2). Для прокрутки ленты вниз используй (500, 780) -> (500, 260).
- TYPE: ввод текста из поля text в поле ввода по координатам (x, y).
- BACK: системная кнопка Назад.
- HOME: выход на рабочий стол.
- OPEN_APP: запуск приложения или ссылки — укажи в text название приложения (например: YouTube, Chrome, Заметки, Карты, Погода) или готовый URL (https://...).
- ASK_USER: задать короткий уточняющий вопрос пользователю (текст вопроса пиши в voice и thought).
- DONE: задача полностью выполнена.

Поле delay_ms: рекомендуемая пауза в мс после шага (150–400 для быстрых тапов, 600–1000 если открывается приложение или грузится сайт).
Поле thought: ёмкая мысль (до 12 слов), что ты видишь и зачем делаешь шаг.
Поле voice: короткая, живая, человеческая фраза на русском для голосовой озвучки без канцеляризмов.
        """.trimIndent()

        val modeRules = if (mode == AgentMode.TASK) {
            """
РЕЖИМ «ЗАДАЧА»:
- Пользователь пишет короткие бытовые команды из 2–4 слов: «открой ютуб и включи музыку», «поищи рецепт блинов», «зайди в настройки и включи блютуз», «посмотри курс доллара».
- Сам разбивай любую простую фразу на конкретные действия на экране.
- Действуй решительно: если нужно найти что-то в интернете — открой Chrome или сразу передай поисковый URL в OPEN_APP (например https://www.google.com/search?q=курс+доллара), если открыть YouTube — используй OPEN_APP с text="YouTube", затем нажми поиск или ролик.
- НЕ задавай лишних вопросов через ASK_USER! Задавай ASK_USER только перед по-настоящему необратимыми действиями: удаление файлов/аккаунтов, оплата/покупка или отправка сообщения реальному человеку.
- В поле mini_goal кратко дублируй текущий этап задачи.
            """.trimIndent()
        } else {
            val appsInfo = if (installedAppsHint.isNotBlank()) {
                "Доступные безопасные приложения на устройстве: $installedAppsHint."
            } else {
                "Можешь использовать браузер Chrome, YouTube, Заметки, Погода, Карты, Музыку."
            }
            """
РЕЖИМ «СВОБОДНЫЙ ПОЛЁТ» (Auto-Explore):
- Веди себя как любопытный, живой, адекватный человек со смартфоном в руках.
- Разрешённые сферы: новости, погода, заметки, музыка, карты, браузер, YouTube.
- $appsInfo
- Ставь себе конкретные мини-цели (указывай в mini_goal, например: «Гляну погоду на сегодня», «Почитаю новости про космос», «Открою заметки и запишу мысль», «Поищу красивый трек на YouTube», «Посмотрю центр города на картах»).
- Выполняй мини-цель за 2–4 шага, затем переключайся на новую интересную мини-цель (меняй mini_goal и пиши живую фразу в voice, например: «Так, открою-ка браузер, гляну прогноз погоды» или «О, интересная статья, пролистаю ниже»).
- Не сиди на одном экране дольше 3 шагов — листай, переходи или открывай новое направление.
- КАТЕГОРИЧЕСКИ ЗАПРЕЩЕНО в Свободном режиме: заходить в банки, настройки сброса/безопасности, удалять файлы/приложения, оформлять покупки/подписки, писать/отправлять сообщения в мессенджерах.
            """.trimIndent()
        }

        return "$coreRules\n\n$modeRules"
    }

    private suspend fun executeCancellable(request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call: Call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val apiMsg = runCatching {
                                JSONObject(body).optJSONObject("error")?.optString("message")
                            }.getOrNull()
                            val msg = apiMsg?.takeIf { it.isNotBlank() }
                                ?: "HTTP ${resp.code}: ${body.take(180)}"
                            if (cont.isActive) cont.resumeWithException(IOException(msg))
                        } else {
                            if (cont.isActive) cont.resume(body)
                        }
                    }
                }
            })
        }
}
