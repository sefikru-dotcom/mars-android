package ru.mars.tv.memory

import org.json.JSONObject
import ru.mars.tv.TvOfflineAgeBadge

/**
 * Снимок `GET /api/memory` (контракт hermes-webui api/route_memory.py):
 * четыре markdown-секции + имя файла проектного контекста и mtime-метки
 * (epoch-секунды, `null` когда файла нет). Пути и сервисные флаги сервера
 * UI не нужны и в снимок не переносятся.
 */
data class TvMemorySnapshot(
    val memory: String = "",
    val user: String = "",
    val soul: String = "",
    val projectContext: String = "",
    val projectContextName: String = "",
    val memoryMtime: Double? = null,
    val userMtime: Double? = null,
    val soulMtime: Double? = null,
    val projectContextMtime: Double? = null,
)

/**
 * Табы экрана «Память»: секция ответа `GET /api/memory` ↔ подпись таба ↔
 * имя файла на сервере (mobile `MemoryTab` parity; подпись «Контекст» —
 * проектный контекст AGENTS.md активного workspace).
 */
enum class TvMemoryTab(val label: String, val fileName: String) {
    MEMORY("Память", "MEMORY.md"),
    USER("Профиль", "USER.md"),
    SOUL("Душа", "SOUL.md"),
    PROJECT("Контекст", "AGENTS.md"),
}

/**
 * Pure presentation policy of the TV «Память» screen (no Android framework
 * beyond org.json, no I/O — JVM-testable, [ru.mars.tv.spaces.TvSpacesPolicy]
 * parity). Раздел строго read-only: `GET /api/memory` входит в
 * `NATIVE_BEARER_EXACT_PATHS` белого списка нативного device-токена, а
 * мутация `/api/memory/write` — нет, поэтому экран только показывает секции.
 *
 * Все решения — здесь; [TvMemoryActivity] только рисует.
 */
object TvMemoryPolicy {

    /** Максимальный возраст кэшированного снимка — память меняется редко (24 ч). */
    const val CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Префикс строки свежести секции («Обновлено 3 ч. назад»). */
    const val UPDATED_PREFIX = "Обновлено "

    private const val MS_PER_SECOND = 1000L

    /**
     * Парсер ответа `GET /api/memory`: отсутствующие/NULL поля становятся
     * пустыми строками и `null`-метками; не-JSON — `null` (транспортный слой
     * озвучит это ошибкой, а не «пустой памятью»).
     */
    fun parse(payload: String): TvMemorySnapshot? = try {
        val json = JSONObject(payload)
        TvMemorySnapshot(
            memory = json.text("memory"),
            user = json.text("user"),
            soul = json.text("soul"),
            projectContext = json.text("project_context"),
            projectContextName = json.text("project_context_name"),
            memoryMtime = json.mtime("memory_mtime"),
            userMtime = json.mtime("user_mtime"),
            soulMtime = json.mtime("soul_mtime"),
            projectContextMtime = json.mtime("project_context_mtime"),
        )
    } catch (_: Exception) {
        null
    }

    /** Полный текст секции (без trim — trim делает [bodyText]). */
    fun textFor(tab: TvMemoryTab, snapshot: TvMemorySnapshot): String = when (tab) {
        TvMemoryTab.MEMORY -> snapshot.memory
        TvMemoryTab.USER -> snapshot.user
        TvMemoryTab.SOUL -> snapshot.soul
        TvMemoryTab.PROJECT -> snapshot.projectContext
    }

    /** Готовый к отрисовке текст секции (trimmed). */
    fun bodyText(tab: TvMemoryTab, snapshot: TvMemorySnapshot): String = textFor(tab, snapshot).trim()

    /**
     * Имя файла секции для подписи: у проектного контекста — серверное
     * `project_context_name` (может отличаться от AGENTS.md), fallback —
     * имя из таба.
     */
    fun fileNameFor(tab: TvMemoryTab, snapshot: TvMemorySnapshot): String =
        if (tab == TvMemoryTab.PROJECT) {
            snapshot.projectContextName.trim().ifBlank { tab.fileName }
        } else {
            tab.fileName
        }

    /** mtime-метка секции (epoch-секунды); `null` когда файла нет. */
    fun mtimeFor(tab: TvMemoryTab, snapshot: TvMemorySnapshot): Double? = when (tab) {
        TvMemoryTab.MEMORY -> snapshot.memoryMtime
        TvMemoryTab.USER -> snapshot.userMtime
        TvMemoryTab.SOUL -> snapshot.soulMtime
        TvMemoryTab.PROJECT -> snapshot.projectContextMtime
    }

    /**
     * Пустая секция — валидное состояние, не ошибка: строка «Секция MEMORY.md
     * пуста» (mobile `MemoryTabPolicy` parity). `null` когда текст есть.
     */
    fun emptyLine(tab: TvMemoryTab, snapshot: TvMemorySnapshot): String? =
        if (bodyText(tab, snapshot).isEmpty()) "Секция ${fileNameFor(tab, snapshot)} пуста" else null

    /**
     * Строка свежести «Обновлено N назад» по mtime секции; `null` когда метки
     * нет. Возраст клампится нулём (откат часов — «только что», формат
     * [TvOfflineAgeBadge.ageText] — единый с офлайн-плашками).
     */
    fun updatedLine(tab: TvMemoryTab, snapshot: TvMemorySnapshot, nowMs: Long): String? =
        mtimeFor(tab, snapshot)?.let { epochSeconds ->
            val savedAtMs = epochSeconds.toLong() * MS_PER_SECOND
            "$UPDATED_PREFIX${TvOfflineAgeBadge.ageText(TvOfflineAgeBadge.ageMs(savedAtMs, nowMs))}"
        }

    /** Подпись под табами: имя файла + свежесть (что есть из двух). */
    fun metaLine(tab: TvMemoryTab, snapshot: TvMemorySnapshot, nowMs: Long): String {
        val fileName = fileNameFor(tab, snapshot)
        val updated = updatedLine(tab, snapshot, nowMs)
        return if (updated == null) fileName else "$fileName · $updated"
    }

    /** Сколько секций содержат непробельный текст (0..4). */
    fun filledCount(snapshot: TvMemorySnapshot): Int =
        TvMemoryTab.values().count { textFor(it, snapshot).isNotBlank() }

    /** Live-регион: «Заполнено разделов: 3 из 4» (+ плашку источника собирает Activity). */
    fun summaryLine(snapshot: TvMemorySnapshot): String =
        "Заполнено разделов: ${filledCount(snapshot)} из ${TvMemoryTab.values().size}"

    /** Текст из JSON-поля: строка как есть, всё прочее (NULL/число/отсутствие) — "". */
    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()

    /** mtime из JSON-поля: положительное конечное число, иначе null (файла нет). */
    private fun JSONObject.mtime(key: String): Double? =
        (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
}
