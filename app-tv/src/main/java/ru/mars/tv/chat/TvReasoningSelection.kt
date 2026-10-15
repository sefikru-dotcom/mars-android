package ru.mars.tv.chat

import android.content.SharedPreferences
import ru.mars.network.model.ReasoningStatus

/**
 * Явный пользовательский выбор уровня размышлений для `/api/reasoning` на ТВ —
 * паритет мобильного `ReasoningChoice`.
 *
 * [effort] null = «Авто» (POST отправляет `effort:''`, снимая переопределение); любое
 * другое значение — серверный уровень, нормализованный в нижний регистр. Выбор
 * записывается ТОЛЬКО после успеха `POST /api/reasoning` — отказавшая запись никогда
 * не становится локальной правдой. `chat/start` поле reasoning не несёт: эффективный
 * уровень на каждый ход разрешает сервер.
 */
data class TvReasoningChoice(
    val effort: String?,
    val explicitPick: Boolean,
)

/** Порт персистентности [TvReasoningChoice]; приложение реализует его через SharedPreferences. */
interface TvReasoningChoiceStorage {
    fun load(): TvReasoningChoice?
    fun save(choice: TvReasoningChoice)
    fun clear()
}

/**
 * [TvReasoningChoiceStorage] на SharedPreferences. Имя файла и ключ совпадают с мобильным
 * клиентом («mars-reasoning-choice»), чтобы выбор читался одинаково на обоих клиентах
 * одного устройства. Восстановленный выбор всегда считается явным пользовательским.
 */
class TvReasoningChoicePrefs(private val prefs: SharedPreferences) : TvReasoningChoiceStorage {

    override fun load(): TvReasoningChoice? {
        if (!prefs.contains(KEY_EFFORT)) return null
        return TvReasoningChoice(prefs.getString(KEY_EFFORT, null)?.takeIf { it.isNotBlank() }, explicitPick = true)
    }

    override fun save(choice: TvReasoningChoice) {
        prefs.edit().putString(KEY_EFFORT, choice.effort.orEmpty()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_EFFORT).apply()
    }

    companion object {
        /** Файл настроек уровня размышлений (паритет с мобильным клиентом). */
        const val PREFS_FILE = "mars-reasoning-choice"
        const val KEY_EFFORT = "reasoning_effort"
    }
}

/** Строка пикера уровней: серверное значение (`null` = Авто) плюс отображаемая подпись. */
data class TvReasoningOption(val effort: String?, val label: String)

/**
 * Доменное состояние выбора уровня размышлений на ТВ (UI -> состояние -> сеть) — зеркало
 * мобильного `ReasoningEffortSelection`: экран получает статус через
 * [ru.mars.network.repository.ReasoningRepository] и передаёт его в [applyStatus]; класс
 * разрешает опции/подписи и хранит явные выборы. Про Android-вьюхи не знает.
 *
 * Намеренно серверно-управляемый: лестница уровней берётся только из `supported_efforts`
 * (никаких захардкоженных уровней), неизвестное/упавшее состояние скрывает чип вместо
 * догадки.
 */
class TvReasoningSelection(private val storage: TvReasoningChoiceStorage) {

    /** Последний явный выбор (восстанавливается из хранилища на старте); null до первого выбора. */
    var current: TvReasoningChoice? = storage.load()
        private set

    /** Последний серверный статус; null до первой успешной загрузки и после любого сбоя. */
    var status: ReasoningStatus? = null
        private set

    /** Фиксирует результат загрузки статуса: payload при успехе, null при сбое/неизвестности. */
    fun applyStatus(newStatus: ReasoningStatus?) {
        status = newStatus
    }

    /**
     * Строки пикера: сначала «Авто», затем серверные уровни в серверном порядке
     * (нормализованные и без дублей). Пусто при неизвестном состоянии или когда у модели
     * нет лестницы — вызывающий код скрывает чип и не открывает пикер.
     */
    fun options(): List<TvReasoningOption> {
        val supported = normalizedSupported()
        if (supported.isEmpty()) return emptyList()
        return listOf(TvReasoningOption(null, label(null))) +
            supported.map { TvReasoningOption(it, label(it)) }
    }

    /**
     * Сохраняет явный выбор ([effort] null = Авто). Вызывать ТОЛЬКО после того, как сервер
     * подтвердил изменение (успех `POST /api/reasoning`).
     */
    fun select(effort: String?): TvReasoningChoice {
        val choice = TvReasoningChoice(normalize(effort), explicitPick = true)
        current = choice
        storage.save(choice)
        return choice
    }

    /**
     * Эффективный уровень для отображения/отметки: серверное принудительное значение,
     * когда статус известен (он отражает глобальное/помодельное/сессионное разрешение),
     * иначе восстановленный локальный выбор.
     */
    fun effectiveEffort(): String? {
        val serverEffort = normalize(status?.reasoningEffort)
        return if (status != null) serverEffort else current?.effort
    }

    /** Подпись чипа; null, пока чип должен быть скрыт (нет ни статуса, ни выбора). */
    fun buttonLabel(): String? {
        if (status == null && current == null) return null
        if (status != null && normalizedSupported().isEmpty()) return null
        return label(effectiveEffort())
    }

    /** Подпись уровня: серверное значение с заглавной; null/пусто = «Авто» (паритет i18n WebUI). */
    fun label(effort: String?): String =
        normalize(effort)?.replaceFirstChar(Char::uppercaseChar) ?: AUTO_LABEL

    private fun normalizedSupported(): List<String> {
        if (status == null) return emptyList()
        return status?.supportedEfforts.orEmpty().mapNotNull { normalize(it) }.distinct()
    }

    private fun normalize(effort: String?): String? =
        effort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    companion object {
        /** Подпись уровня по умолчанию (паритет мобильного селектора и WebUI). */
        const val AUTO_LABEL = "Авто"
    }
}
