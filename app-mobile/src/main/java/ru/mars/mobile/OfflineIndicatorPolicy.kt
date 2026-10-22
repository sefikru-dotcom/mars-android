package ru.mars.mobile

/**
 * Тексты индикатора офлайна — чистая политика, без Android-зависимостей.
 *
 * Зачем отдельный объект: индикатор нужен двум экранам (диалог и список диалогов), и оба
 * обязаны говорить одно и то же. Плюс возраст копии — это не украшение: «нет связи» без
 * даты читается как «данные свежие», а через сутки офлайна это уже неправда. Подпись
 * строится из [OfflineCacheUiPolicy.cacheBadgeText] (те же «5 мин назад / 3 ч назад»,
 * что у кэшей задач и проектов), поэтому правила возраста не дублируются.
 *
 * Индикатор информационный: он никогда не скрывает историю и не блокирует ввод —
 * офлайн-слой существует ровно для того, чтобы читать и писать без сети.
 */
object OfflineIndicatorPolicy {

    /** Разделитель между текстом баннера и возрастом копии. */
    const val SEPARATOR = " · "

    /** Базовая строка индикатора диалога (кэш показан вместо живой истории). */
    const val CHAT_OFFLINE_BASE = "Нет связи — показана сохранённая копия диалога"

    /** Базовая строка индикатора списка диалогов (список показан из кэша). */
    const val LIST_OFFLINE_BASE = "Нет связи — показаны сохранённые диалоги"

    /**
     * Строка статуса диалога. [savedAtEpochMillis] — момент сохранения копии
     * (`null`/0 — неизвестен: тогда только базовая строка, без выдуманного возраста).
     */
    fun chatHint(savedAtEpochMillis: Long?, nowEpochMillis: Long): String =
        appendCacheAge(CHAT_OFFLINE_BASE, savedAtEpochMillis, nowEpochMillis)

    /** Индикатор списка диалогов: текст сетевого баннера + возраст копии, если он известен. */
    fun listHint(baseText: String, savedAtEpochMillis: Long?, nowEpochMillis: Long): String =
        appendCacheAge(baseText, savedAtEpochMillis, nowEpochMillis)

    /**
     * Дописывает возраст кэша к любому тексту баннера. Неизвестный момент сохранения
     * (`null`/0) не превращается в «только что»: пользователю показывается только то,
     * что действительно известно.
     */
    fun appendCacheAge(baseText: String, savedAtEpochMillis: Long?, nowEpochMillis: Long): String {
        if (savedAtEpochMillis == null || savedAtEpochMillis <= 0L) return baseText
        return baseText + SEPARATOR + OfflineCacheUiPolicy.cacheBadgeText(savedAtEpochMillis, nowEpochMillis)
    }
}
