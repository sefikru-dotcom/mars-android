package ru.mars.mobile

import ru.mars.network.repository.SessionShareLink

/**
 * Чистая политика публичной share-ссылки диалога (долг O4).
 *
 * Серверные `POST /api/share/create` и `POST /api/share/revoke` входят в
 * device-Bearer allowlist (`hermes-webui/api/auth.py`, `NATIVE_BEARER_EXACT_PATHS`),
 * поэтому клиент ходит в них напрямую: никакого «локального шаринга вместо
 * серверного» здесь больше нет. Локальный Markdown-экспорт
 * ([SessionExportSharePolicy]) остаётся отдельной функцией — он нужен для файла,
 * а не для ссылки.
 *
 * Здесь только данные представления (адрес, тексты), без Android-типов: политика
 * покрыта обычными JVM-тестами.
 */
object SessionSharePolicy {

    /**
     * Абсолютный адрес ссылки: сервер отдаёт ОТНОСИТЕЛЬНЫЙ путь `/share/<token>`
     * (он не знает, по какому хосту к нему пришёл клиент), а в буфер обмена должен
     * попасть адрес, который откроется с телефона. Абсолютный адрес сервера
     * (если сервер когда-нибудь начнёт отдавать его сам) принимается как есть.
     */
    fun absoluteUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val suffix = path.trim()
        if (suffix.isEmpty()) return base
        if (suffix.startsWith("http://") || suffix.startsWith("https://")) return suffix
        return base + if (suffix.startsWith("/")) suffix else "/$suffix"
    }

    /** Ссылка для буфера обмена: абсолютный адрес из ответа сервера. */
    fun clipboardText(baseUrl: String, link: SessionShareLink): String =
        absoluteUrl(baseUrl, link.path)

    /**
     * Текст диалога после успешного создания. Намеренно говорит, что открывающий
     * увидит СНИМОК: ссылка не живёт синхронно с диалогом, повторное «поделиться»
     * обновляет снимок.
     */
    fun dialogMessage(link: SessionShareLink): String = buildString {
        append("Публичная ссылка на диалог готова.")
        if (link.messageCount > 0) append(" В снимке сообщений: ${link.messageCount}.")
        append(" Открывающий увидит снимок диалога на момент создания ссылки.")
    }

    /** Подтверждение копирования. */
    fun copiedMessage(): String = "Ссылка скопирована"

    /** Отказ сервера/сети при создании ссылки — с его причиной, если она есть. */
    fun failureMessage(reason: String?): String =
        if (reason.isNullOrBlank()) {
            "Не удалось создать публичную ссылку"
        } else {
            "Не удалось создать публичную ссылку: $reason"
        }

    /** Подтверждение отзыва. */
    fun revokeSuccessMessage(): String = "Публичная ссылка отозвана"

    /**
     * Отзыв у диалога без ссылки: сервер отвечает `404 Session not found` (у ручки
     * нет отдельного кода «ссылки нет»), поэтому UI переводит этот случай в
     * понятную фразу вместо серверного текста.
     */
    fun revokeFailureMessage(reason: String?): String =
        if (reason.isNullOrBlank() || reason.contains("not found", ignoreCase = true)) {
            "Публичной ссылки у диалога нет"
        } else {
            "Не удалось отозвать ссылку: $reason"
        }
}
