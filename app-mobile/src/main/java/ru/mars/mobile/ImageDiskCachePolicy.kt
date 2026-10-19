package ru.mars.mobile

import java.security.MessageDigest

/**
 * Одна запись дискового кэша обложек: имя файла, размер и время последнего обращения.
 *
 * Политика намеренно не знает про [java.io.File] и [android.content.Context]: хост
 * ([ImageDiskCache]) отдаёт ей снимок каталога, а она возвращает имена файлов на удаление.
 * Так правила вытеснения проверяются обычными JVM-тестами — без эмулятора и без диска.
 *
 * @property fileName имя файла внутри каталога кэша (не путь: подкаталогов в кэше нет);
 * @property sizeBytes размер файла в байтах;
 * @property lastAccessMs время последнего чтения (UTC millis) — основа LRU-порядка.
 */
data class ImageDiskCacheEntry(
    val fileName: String,
    val sizeBytes: Long,
    val lastAccessMs: Long,
)

/**
 * Чистые правила дискового кэша обложек mobile-клиента.
 *
 * Зачем вообще диск: до этой политики обложки жили только в LruCache
 * ([ImageLoaderPolicy.cacheBudgetBytes], ≤ 24 МБ). После смерти процесса или системного
 * trim весь список музыки качался заново с сервера — на мобильном интернете это заметно.
 * Диск переживает и то, и другое, поэтому кэш обложек становится двухуровневым:
 * память → диск → сеть.
 *
 * Почему ключ — `sha256(url)`, а не сам URL: URL содержит `/`, `?` и `=`, то есть имя файла
 * из него не собрать без потери однозначности; хеш даёт 64 символа `[0-9a-f]` — безопасно
 * для любой файловой системы и одинаково на всех устройствах. Расширение дописывается
 * по `Content-Type` только затем, чтобы файлы можно было опознать глазами при отладке
 * (декодеру расширение не нужно — байты декодируются по сигнатуре).
 *
 * Осознанные решения, зафиксированные здесь, а не в хосте:
 * - TTL ([TTL_MS]) ограничивает «вечность» обложки: сервер меняет картинку трека, и через
 *   две недели мы хотим её увидеть, а не показывать устаревшую до очистки кэша;
 * - бюджет ([BUDGET_BYTES]) ограничен по объёму, а не по числу файлов: обложки одного
 *   размера, но разного веса (JPEG против PNG), поэтому байты — честная мера;
 * - потолок числа записей ([MAX_ENTRIES]) нужен отдельно: каталог кэша сканируется на
 *   старте процесса, и 10 000 крошечных файлов стоили бы дороже, чем 32 МБ данных.
 */
object ImageDiskCachePolicy {

    /** Подкаталог `cacheDir`, в котором лежат обложки. */
    const val DIRECTORY_NAME: String = "covers"

    /**
     * Бюджет каталога обложек. 32 МБ — примерно 300–400 обложек по 80–100 КБ: этого
     * хватает на несколько экранов музыки и на повторный запуск, но кэш остаётся
     * вежливым к чужому месту на диске (cacheDir система вправе очистить сама).
     */
    const val BUDGET_BYTES: Long = 32L * 1024 * 1024

    /**
     * Срок жизни записи с момента последнего чтения. 14 дней: за это время обложка
     * трека либо не меняется вовсе, либо её уже пора перезапросить.
     */
    const val TTL_MS: Long = 14L * 24 * 60 * 60 * 1000

    /**
     * Потолок числа файлов в каталоге. Индекс каталога строится сканированием на первом
     * обращении после старта процесса — ограничение держит это сканирование дешёвым
     * даже если обложки окажутся совсем мелкими.
     */
    const val MAX_ENTRIES: Int = 512

    /**
     * Потолок одной записи. Совпадает с сетевым лимитом [ImageLoaderPolicy.MAX_DOWNLOAD_BYTES]:
     * то, что не прошло сетевой лимит, не должно попадать и на диск — иначе кэш
     * превращается в свалку ошибочных HTML-страниц.
     */
    const val MAX_ENTRY_BYTES: Long = ImageLoaderPolicy.MAX_DOWNLOAD_BYTES

    /** Расширение для неизвестного/отсутствующего `Content-Type`. */
    const val FALLBACK_EXTENSION: String = ".img"

    /**
     * Белый список расширений. Именно белый, а не «взять из Content-Type как есть»:
     * серверный заголовок — недоверенный ввод, а имя файла строится из него.
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
     * Допустимые хвосты имени файла. [FALLBACK_EXTENSION] обязан входить сюда: обложка,
     * сохранённая при неизвестном `Content-Type` (типовой случай за прокси, который
     * срезает заголовок), лежит как `<hash>.img`. Если `.img` не в белом списке,
     * [isEntryNameFor] не признаёт собственный файл кэша — и такая запись не читается
     * никогда, то есть кэш молча не работает ровно там, где он нужнее всего.
     */
    private val ALLOWED_EXTENSIONS: Set<String> = CONTENT_TYPE_EXTENSIONS.values.toSet() + FALLBACK_EXTENSION

    /** Длина `sha256` в hex-виде: 32 байта → 64 символа. */
    const val KEY_LENGTH: Int = 64

    /**
     * Ключ записи: `sha256(url)` в нижнем hex. Пустой URL не особый случай — он даёт
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
     * Расширение по `Content-Type`. Параметры (`; charset=…`) и регистр отбрасываются:
     * сервер музыки отдаёт `image/jpeg`, но прокси может добавить параметры. Неизвестный
     * тип — [FALLBACK_EXTENSION]: файл всё равно декодируется по сигнатуре.
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
     * на этом шаге неизвестен (мы ещё не ходили в сеть), поэтому расширение любое из
     * белого списка, а префикс — ровно хеш URL. Заодно это проверка на побег из каталога:
     * в имени не может быть `/` или `..`, потому что хвост обязан быть из белого списка.
     */
    fun isEntryNameFor(fileName: String, url: String): Boolean {
        if (fileName.length <= KEY_LENGTH) return false
        val key = baseKey(url)
        if (!fileName.startsWith(key)) return false
        return ALLOWED_EXTENSIONS.contains(fileName.substring(KEY_LENGTH))
    }

    /**
     * Можно ли писать объект такого размера. Ноль и отрицательный размер — битый ответ
     * (пустое тело, ошибка стрима), такой файл только занимал бы место.
     */
    fun shouldStore(sizeBytes: Long): Boolean =
        sizeBytes > 0 && sizeBytes <= minOf(MAX_ENTRY_BYTES, BUDGET_BYTES)

    /**
     * Протухла ли запись. Неизвестное время обращения (`<= 0`, например файл с битой
     * датой) считается протухшим: мусор в каталоге надо убирать, а не хранить вечно.
     * Время из будущего (сбитые часы) не протухает — иначе кэш стирался бы при каждом
     * переводе часов назад.
     */
    fun isExpired(entry: ImageDiskCacheEntry, nowMs: Long): Boolean {
        if (entry.lastAccessMs <= 0L) return true
        return nowMs - entry.lastAccessMs > TTL_MS
    }

    /**
     * Какие файлы удалить, чтобы после добавления [incoming] каталог уложился в бюджет
     * и в потолок числа записей.
     *
     * Порядок вытеснения: сначала протухшие (их не жалко в любом случае), затем LRU —
     * самые давно читанные. Запись с тем же именем, что у [incoming], в кандидаты не
     * попадает: она будет перезаписана, а не продублирована.
     *
     * Объект, который сам больше бюджета, вытеснять живое не заставляет: такой размер
     * отсекает [shouldStore], и сюда он попадать не должен. Если всё же попал —
     * возвращаются только протухшие, кэш не уничтожается.
     */
    fun selectEvictions(
        entries: List<ImageDiskCacheEntry>,
        incoming: ImageDiskCacheEntry,
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
