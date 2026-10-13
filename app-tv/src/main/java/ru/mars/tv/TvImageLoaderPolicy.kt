package ru.mars.tv

/**
 * Чистые, JVM-тестируемые политики загрузчика обложек [TvImageLoader]
 * (порт mobile [ru.mars.mobile.ImageLoaderPolicy] — инварианты те же).
 *
 * Сам загрузчик привязан к Android (LruCache/BitmapFactory), поэтому каждое решение,
 * которое выражается числами, живёт здесь: байтовый бюджет кеша, целевой размер
 * декодирования и коэффициент downsampling. Чистота означает, что правила OOM-безопасности
 * покрыты обычными unit-тестами, а не инструментальными.
 */
object TvImageLoaderPolicy {

    /** Пол для кеша битмапов: ниже — обложки перезагружаются на каждой прокрутке. */
    const val MIN_CACHE_BYTES: Int = 2 * 1024 * 1024

    /**
     * Потолок кеша битмапов. Процесс-глобальный синглтон обязан быть вежливым:
     * даже на куче 512 МБ под обложки берётся не больше 24 МБ.
     */
    const val MAX_CACHE_BYTES: Int = 24 * 1024 * 1024

    /** Доля кучи процесса, которую кеш обложек может занять (1/8). */
    const val CACHE_MEMORY_FRACTION: Int = 8

    /**
     * Жёсткий лимит одной загрузки картинки. Обложка — это несколько сотен КБ;
     * всё, что крупнее, — неверный URL (цепочка HTML-страниц ошибки, видеофайл),
     * и в кучу это попадать не должно.
     */
    const val MAX_DOWNLOAD_BYTES: Long = 8L * 1024 * 1024

    /** Запасной целевой размер декодирования, когда ImageView ещё не размечен (0x0). */
    const val DEFAULT_TARGET_PX: Int = 256

    /** Абсолютный потолок целевого размера; защита от абсурдных вычисленных значений. */
    const val MAX_TARGET_PX: Int = 1024

    /**
     * Байтовый бюджет LruCache: 1/[CACHE_MEMORY_FRACTION] от максимальной кучи процесса,
     * зажатая в [[MIN_CACHE_BYTES], [MAX_CACHE_BYTES]]. Никогда ноль и никогда вся куча.
     */
    fun cacheBudgetBytes(maxMemoryBytes: Long): Int {
        val share = maxMemoryBytes / CACHE_MEMORY_FRACTION
        return share.coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong()).toInt()
    }

    /**
     * Целевой размер декодирования (сторона квадрата, px) для вьюхи
     * [viewWidth]x[viewHeight]. Не размеченные вьюхи (0) получают [fallbackPx];
     * результат зажат в [MAX_TARGET_PX] и никогда не ниже 1.
     */
    fun targetSizePx(viewWidth: Int, viewHeight: Int, fallbackPx: Int = DEFAULT_TARGET_PX): Int {
        val edge = maxOf(viewWidth, viewHeight).takeIf { it > 0 } ?: fallbackPx
        return edge.coerceIn(1, MAX_TARGET_PX)
    }

    /**
     * Степень двойки `inSampleSize`, при которой декодированный битмап всё ещё
     * покрывает [targetPx] по обеим осям. Неизвестные размеры источника (<= 0)
     * или уже достаточно маленький источник дают 1 — вверх никогда не масштабируем.
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, targetPx: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (srcWidth / (sample * 2) >= targetPx && srcHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return sample
    }

    /**
     * Можно ли начинать загрузку при объявленном `Content-Length`
     * (<= 0 означает неизвестный/chunked и разрешён — потоковый лимит всё равно действует).
     */
    fun acceptDownload(contentLength: Long): Boolean =
        contentLength <= 0 || contentLength <= MAX_DOWNLOAD_BYTES
}
