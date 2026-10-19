package ru.mars.tv

import java.security.MessageDigest

/**
 * Одна запись дискового кэша обложек ТВ: имя файла, размер и время последнего обращения.
 *
 * Политика не знает про [java.io.File] и [android.content.Context]: хост
 * ([TvImageDiskCache]) отдаёт снимок каталога, а получает имена файлов на удаление.
 * Поэтому правила вытеснения проверяются обычными JVM-тестами, без эмулятора и диска.
 *
 * @property fileName имя файла внутри каталога кэша (подкаталогов в кэше нет);
 * @property sizeBytes размер файла в байтах;
 * @property lastAccessMs время последнего чтения (UTC millis) — основа LRU-порядка.
 */
data class TvImageDiskCacheEntry(
    val fileName: String,
    val sizeBytes: Long,
    val lastAccessMs: Long,
)

/**
 * Чистые правила дискового кэша обложек ТВ-клиента (порт mobile
 * [ru.mars.mobile.ImageDiskCachePolicy]; отдельная копия, а не общий модуль: app-tv
 * и app-mobile не должны тянуть код друг друга, а стиль проекта уже допускает
 * раздельные загрузчики — [TvImageLoader] тоже порт, а не зависимость).
 *
 * Зачем диск: до этой политики обложки жили только в LruCache
 * ([TvImageLoaderPolicy.cacheBudgetBytes], <= 24 МБ). После перезапуска приложения
 * (а ТВ-приставку выключают каждый вечер) весь список музыки качался заново. Диск
 * переживает смерть процесса, поэтому кэш становится двухуровневым: память -> диск -> сеть.
 *
 * Почему ключ — `sha256(url)`: URL содержит `/`, `?` и `=`, имя файла из него не собрать
 * без потери однозначности. 64 символа `[0-9a-f]` безопасны для любой ФС. Расширение
 * дописывается по `Content-Type` только для опознания файла глазами при отладке —
 * декодеру оно не нужно, байты распознаются по сигнатуре.
 *
 * Никаких новых зависимостей: только `java.security.MessageDigest` (в app-tv внешние
 * библиотеки запрещены).
 *
 * Осознанные решения:
 * - TTL ([TTL_MS]) не даёт обложке жить вечно: сервер меняет картинку трека, и через
 *   две недели мы хотим видеть новую;
 * - бюджет ([BUDGET_BYTES]) — по объёму, а не по числу файлов: обложки одного размера
 *   весят по-разному (JPEG против PNG), байты — честная мера;
 * - потолок числа записей ([MAX_ENTRIES]) нужен отдельно: индекс каталога строится
 *   сканированием при первом обращении, и тысячи мелких файлов стоили бы дороже 32 МБ.
 */
object TvImageDiskCachePolicy {

    /** Подкаталог `cacheDir`, в котором лежат обложки. */
    const val DIRECTORY_NAME: String = "covers"

    /**
     * Бюджет каталога обложек. ТВ-приставки бедны флеш-памятью сильнее телефонов,
     * поэтому те же 32 МБ, что и на mobile, — верхняя граница, а не цель.
     */
    const val BUDGET_BYTES: Long = 32L * 1024 * 1024

    /** Срок жизни записи с момента последнего чтения — 14 дней. */
    const val TTL_MS: Long = 14L * 24 * 60 * 60 * 1000

    /** Потолок числа файлов: держит дешёвым сканирование каталога на старте процесса. */
    const val MAX_ENTRIES: Int = 512

    /**
     * Потолок одной записи — равен сетевому лимиту [TvImageLoaderPolicy.MAX_DOWNLOAD_BYTES]:
     * что не прошло сетевой лимит, не должно попадать и на диск.
     */
    const val MAX_ENTRY_BYTES: Long = TvImageLoaderPolicy.MAX_DOWNLOAD_BYTES

    /** Расширение для неизвестного/отсутствующего `Content-Type`. */
    const val FALLBACK_EXTENSION: String = ".img"

    /**
     * Белый список расширений: `Content-Type` — недоверенный ввод, а имя файла строится
     * из него, поэтому берём только известные типы, а не заголовок «как есть».
     */
    private val CONTENT_TYPE_EXTENSIONS: Map<String, String> = mapOf(
        "image/jpeg" to ".jpg",
        "image/jpg" to ".jpg",
        "image/pjpeg" to ".jpg",
        "image/png" to ".png",
        "image/webp" to ".webp",
        "image/gif" to ".gif",
        "image/avif" to ".avif",
        "image/heic" to ".heic",
    )

    /**
     * Допустимые хвосты имени файла. [FALLBACK_EXTENSION] входит сюда обязательно: обложка,
     * сохранённая при неизвестном `Content-Type`, лежит как `<hash>.img`. Без `.img` в
     * белом списке [isEntryNameFor] не узнаёт собственный файл кэша — запись не читается
     * никогда, и кэш молча не работает ровно там, где он нужнее всего.
     */
    private val ALLOWED_EXTENSIONS: Set<String> = CONTENT_TYPE_EXTENSIONS.values.toSet() + FALLBACK_EXTENSION

    /** Длина `sha256` в hex-виде: 32 байта -> 64 символа. */
    const val KEY_LENGTH: Int = 64

    /**
     * Ключ записи: `sha256(url)` в нижнем hex. Пустой URL — не особый случай: он даёт
     * стабильный хеш пустой строки, поэтому вызывающему не нужно защищаться от null/"".
     */
    fun baseKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(KEY_LENGTH)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    /**
     * Расширение по `Content-Type`: параметры (`; charset=…`) и регистр отбрасываются,
     * неизвестный тип даёт [FALLBACK_EXTENSION] — файл всё равно декодируется по сигнатуре.
     */
    fun extensionFor(contentType: String?): String {
        val mediaType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?: return FALLBACK_EXTENSION
        return CONTENT_TYPE_EXTENSIONS[mediaType] ?: FALLBACK_EXTENSION
    }

    /** Имя файла кэша для [url] с расширением по [contentType]. */
    fun fileName(url: String, contentType: String?): String =
        baseKey(url) + extensionFor(contentType)

    /**
     * Принадлежит ли файл [fileName] записи для [url]. Нужен при чтении: `Content-Type`
     * там ещё неизвестен (в сеть мы не ходили), поэтому подходит любое расширение из
     * белого списка, а префикс — ровно хеш URL. Заодно это защита от побега из каталога:
     * в хвосте не может быть `/` или `..`, он обязан быть из белого списка.
     */
    fun isEntryNameFor(fileName: String, url: String): Boolean {
        if (fileName.length <= KEY_LENGTH) return false
        val key = baseKey(url)
        if (!fileName.startsWith(key)) return false
        return ALLOWED_EXTENSIONS.contains(fileName.substring(KEY_LENGTH))
    }

    /**
     * Можно ли писать объект такого размера. Ноль и отрицательный размер — битый ответ
     * (пустое тело, ошибка стрима): такой файл только занимал бы место.
     */
    fun shouldStore(sizeBytes: Long): Boolean =
        sizeBytes > 0 && sizeBytes <= minOf(MAX_ENTRY_BYTES, BUDGET_BYTES)

    /**
     * Протухла ли запись. Неизвестное время обращения (`<= 0`, битая дата файла) считается
     * протухшим — мусор надо убирать. Время из будущего (сбитые часы приставки) не
     * протухает: иначе кэш стирался бы при каждом переводе часов назад.
     */
    fun isExpired(entry: TvImageDiskCacheEntry, nowMs: Long): Boolean {
        if (entry.lastAccessMs <= 0L) return true
        return nowMs - entry.lastAccessMs > TTL_MS
    }

    /**
     * Какие файлы удалить, чтобы после добавления [incoming] каталог уложился в бюджет
     * и в потолок числа записей.
     *
     * Порядок: сначала протухшие (их не жалко в любом случае), затем LRU — самые давно
     * читанные. Запись с тем же именем, что у [incoming], в кандидаты не попадает: она
     * будет перезаписана, а не продублирована.
     *
     * Объект больше бюджета вытеснять живое не заставляет: такой размер отсекает
     * [shouldStore]. Если он всё же попал сюда — возвращаются только протухшие, кэш
     * не уничтожается.
     */
    fun selectEvictions(
        entries: List<TvImageDiskCacheEntry>,
        incoming: TvImageDiskCacheEntry,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val victims = LinkedHashSet<String>()
        for (entry in entries) {
            if (isExpired(entry, nowMs)) victims += entry.fileName
        }
        if (incoming.sizeBytes <= 0L || incoming.sizeBytes > BUDGET_BYTES) {
            return victims.toList()
        }
        val survivors = entries.filter { it.fileName !in victims && it.fileName != incoming.fileName }
        var totalBytes = survivors.sumOf { it.sizeBytes } + incoming.sizeBytes
        var count = survivors.size + 1
        val oldestFirst = survivors.sortedBy { it.lastAccessMs }
        var index = 0
        while ((totalBytes > BUDGET_BYTES || count > MAX_ENTRIES) && index < oldestFirst.size) {
            val victim = oldestFirst[index++]
            victims += victim.fileName
            totalBytes -= victim.sizeBytes
            count -= 1
        }
        return victims.toList()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
