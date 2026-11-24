package ru.mars.mobile

import java.io.File
import java.util.Base64

/**
 * Копия сгенерированной картинки внутри TOOL-сообщения истории.
 *
 * Зачем копия, если есть файл: `generate_image` кладёт JPEG в `cacheDir` (так требует
 * контракт), а `cacheDir` система вправе вычистить в любой момент — и тогда картинка
 * исчезла бы из старого диалога навсегда. Копия в тексте сообщения переживает очистку.
 *
 * Формат — строки после обычного текста результата, маркер в начале строки:
 * ```
 * Картинка готова: …            ← текстовый результат инструмента, как был
 * <image-marker>sha256<image-marker>
 * <base64 JPEG>                  ← может быть разбит на несколько строк
 * ```
 * Почему маркер именно такой: текст результата уходит провайдеру, и модель должна
 * видеть понятную строку, а не мегабайт base64. Разбор идёт с конца, поэтому любое
 * содержимое результата до маркера не мешает.
 *
 * Почему не `LocalAttachment`: вложения в истории принимаются только у сообщения
 * пользователя (`attachmentRefusal`), а тут — TOOL. Ходить в `core-network` ради
 * нового варианта роли значило бы менять модель диалога чужой зоне ради одной картинки.
 *
 * Класс без `android.*`: и кодирование, и разбор проверяются JVM-тестом.
 */
internal object GeneratedImageStorage {

    /** Маркер строки-заголовка копии. */
    const val MARKER = "<!-- mars-image:"

    /** Потолок копии: 8 МБ исходника — это ~10.7 МБ base64, а сообщение и так ограничено. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** Потолок на одну строку base64: сообщение сессии читается построчно. */
    private const val WRAP = 76

    /**
     * Добавить копию файла к тексту результата. Файл слишком большой, пропал или число
     * байт не совпало — возвращается исходный текст: картинка останется файлом, а
     * история не получит битую копию, которая не декодируется.
     *
     * Имя файла в заголовке нужно «Сохранить в галерею» (осмысленное имя в альбоме);
     * [name] — для байтов, развёрнутых не из файла.
     */
    fun withImage(text: String, file: File): String {
        if (text.contains(MARKER)) return text
        if (!file.isFile || file.length() == 0L || file.length() > MAX_BYTES) return text
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return text
        if (bytes.size > MAX_BYTES) return text
        return withBytes(text, bytes, file.name)
    }

    /** То же, но для байтов: файл в cacheDir мог уже исчезнуть, а копия осталась. */
    fun withBytes(text: String, bytes: ByteArray, name: String = "image.jpeg"): String {
        if (text.contains(MARKER) || bytes.isEmpty() || bytes.size > MAX_BYTES) return text
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return buildString {
            append(text)
            if (!text.endsWith("\n")) append('\n')
            append(MARKER).append(name).append('\n')
            // Переносы по WRAP символов: одна строка base64 на мегабайты — это строка,
            // которую неудобно ни читать в диффе, ни резать построчно в диагностике.
            var index = 0
            while (index < encoded.length) {
                val end = minOf(index + WRAP, encoded.length)
                append(encoded, index, end).append('\n')
                index = end
            }
        }
    }

    /** Текст результата без копии: то, что уходит модели и показывается в превью. */
    fun strip(text: String): String {
        val index = text.indexOf(MARKER)
        return if (index <= 0) text.trimEnd() else text.substring(0, index).trimEnd()
    }

    /**
     * Байты картинки из текста сообщения; null — копии нет или base64 битый.
     *
     * Отсутствие копии — нормальный случай: старые сессии, картинка больше [MAX_BYTES],
     * файл пропал в момент сохранения.
     */
    fun imageBytes(text: String): ByteArray? {
        val index = text.indexOf(MARKER)
        if (index < 0) return null
        val body = text.substring(index + MARKER.length)
        val newline = body.indexOf('\n')
        if (newline < 0) return null
        val payload = body.substring(newline + 1).filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        if (payload.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
    }

    /** Имя файла, под которым копия была сохранена (для галереи и подписи). */
    fun imageName(text: String): String? {
        val index = text.indexOf(MARKER)
        if (index < 0) return null
        val body = text.substring(index + MARKER.length)
        val newline = body.indexOf('\n')
        if (newline < 0) return null
        return body.substring(0, newline).trim().takeIf { it.isNotEmpty() }
    }
}
