package ru.mars.mobile

import ru.mars.network.model.SessionItem

/**
 * Чистая политика шаринга экспортированного диалога (пункт паритета A3).
 *
 * Серверные ручки `/api/share/create` и `/api/share/revoke` на device-токене
 * отвечают 401: публичный share-эндпоинт рассчитан на session-cookie WebUI. Это
 * серверный блокер, и клиент его НЕ обходит — вместо публичной ссылки диалог
 * экспортируется в Markdown локально и уходит в системный share-sheet Android
 * (`ACTION_SEND`), который работает офлайн и никуда не авторизуется.
 *
 * Здесь собраны только данные интента (mime/subject/подпись chooser'а и координаты
 * FileProvider) — без Android-типов, поэтому политика покрыта обычными JVM-тестами.
 */
object SessionExportSharePolicy {

    /** Подкаталог кэша приложения, куда пишется markdown-файл экспорта. */
    const val EXPORT_DIR = "session-exports"

    /** Суффикс authority FileProvider'а (объявлен в AndroidManifest). */
    const val AUTHORITY_SUFFIX = ".sessionexports"

    /** MIME экспорта — markdown; в Android-шаринг уходит как text/markdown. */
    const val MIME_TYPE = "text/markdown"

    /** Подпись системного chooser'а. */
    const val CHOOSER_TITLE = "Экспорт диалога"

    /** Тема письма/заметки — заголовок диалога, с откатом на «Диалог Марса». */
    fun subject(session: SessionItem?): String =
        session?.title?.takeIf { it.isNotBlank() } ?: "Диалог Марса"

    /** Authority FileProvider для конкретного applicationId. */
    fun authority(applicationId: String): String = applicationId + AUTHORITY_SUFFIX

    /**
     * Что показать, когда сервер не отдал содержимое диалога для экспорта
     * (`GET /api/session/export` упал): сообщение должно называть причину, а не
     * молча открывать пустой шар-шит.
     */
    fun failureMessage(reason: String?): String =
        if (reason.isNullOrBlank()) {
            "Не удалось получить диалог для экспорта"
        } else {
            "Не удалось получить диалог для экспорта: $reason"
        }

    /** Подтверждение успешного экспорта (файл уже в кэше и отдан в share-sheet). */
    fun successMessage(): String = "Экспорт готов — выберите, куда отправить"
}
