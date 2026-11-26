package com.screenpilot.ai.agent

import com.screenpilot.ai.accessibility.ScreenCaptureSnapshot
import com.screenpilot.ai.ai.AgentStepDecision

sealed class GuardrailVerdict {
    data object Allowed : GuardrailVerdict()
    data class Blocked(val reason: String) : GuardrailVerdict()
}

object SafetyGuardrails {

    private val blockedPackageKeywords = listOf(
        "bank", "sber", "tinkoff", "tbank", "vtb", "alfa", "raiffeisen", "gazprom",
        "qiwi", "yoomoney", "wallet", "pay", "paypal", "binance", "bybit",
        "whatsapp", "telegram", "viber", "signal", "messenger", "mms", "sms",
        "vkontakte", "com.vk.", "discord", "skype", "vending", "billing",
        "packageinstaller", "recovery", "updater"
    )

    private val blockedActionKeywords = listOf(
        "сброс", "удалить", "удаление", "стереть", "форматир", "деинсталл",
        "factory reset", "wipe", "erase", "uninstall", "delete", "remove",
        "оплат", "купить", "покупк", "перевести", "перевод", "карт",
        "оформить заказ", "pay", "purchase", "checkout", "subscribe", "подписк",
        "отправить", "сообщение", "написать", "позвонить", "call", "send",
        "банк", "пароль", "пин-код", "pin", "биллинг", "счет"
    )

    fun evaluateFreeModeAction(
        decision: AgentStepDecision,
        snapshot: ScreenCaptureSnapshot,
        targetNodeLabel: String
    ): GuardrailVerdict {
        val pkgLower = snapshot.activePackage.lowercase()

        // 1. Check active foreground package
        if (pkgLower.isNotBlank() && blockedPackageKeywords.any { pkgLower.contains(it) }) {
            return GuardrailVerdict.Blocked("Запрещённое приложение в Свободном режиме ($pkgLower)")
        }

        // Allow safe navigation back out of anything
        if (decision.action == "HOME" || decision.action == "BACK" || decision.action == "DONE") {
            return GuardrailVerdict.Allowed
        }

        // 2. Check target app/URL/text parameter
        val textLower = decision.text.lowercase()
        if (decision.action == "OPEN_APP") {
            if (blockedPackageKeywords.any { textLower.contains(it) } ||
                blockedActionKeywords.any { textLower.contains(it) } ||
                textLower.contains("настрой") || textLower.contains("settings")
            ) {
                return GuardrailVerdict.Blocked("Запрещён запуск системных/опасных приложений ($textLower)")
            }
        }

        if (decision.action == "TYPE") {
            if (blockedActionKeywords.any { textLower.contains(it) }) {
                return GuardrailVerdict.Blocked("Запрещён ввод потенциально опасного текста")
            }
        }

        // 3. Check target UI node label under tap coordinates
        val labelLower = targetNodeLabel.lowercase()
        if (decision.action == "TAP" && labelLower.isNotBlank()) {
            if (blockedActionKeywords.any { labelLower.contains(it) }) {
                return GuardrailVerdict.Blocked("Заблокировано нажатие на «$targetNodeLabel»")
            }
        }

        // 4. Also guard against settings reset screens
        if (pkgLower.contains("settings") || pkgLower.contains("setting")) {
            val uiLower = snapshot.uiTreeSummary.lowercase()
            if (uiLower.contains("сброс") || uiLower.contains("reset") || uiLower.contains("удалить")) {
                return GuardrailVerdict.Blocked("Заблокирован экран системного сброса/удаления")
            }
        }

        return GuardrailVerdict.Allowed
    }
}
