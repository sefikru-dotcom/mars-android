package ru.mars.mobile

/**
 * Чистая политика полноэкранного просмотрщика изображений (lightbox):
 * что считать картинкой по URL и как подписать изображение в транскрипте.
 * Резолв относительных путей доверяется [ThumbnailUrlResolver] (тот же контракт).
 * Без Android-импортов — тестируется на JVM.
 */
object ImageLightboxPolicy {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")

    /**
     * True, когда путь URL заканчивается расширением картинки (query/fragment
     * отбрасываются, регистр не важен). Определяет, открывать ли ссылку в
     * lightbox вместо внешнего браузера.
     */
    fun isImageUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "")
        return ext.lowercase() in IMAGE_EXTENSIONS
    }

    /**
     * Абсолютный URL изображения из сырого значения: http(s) проходит как есть,
     * относительный путь приклеивается к [baseUrl], прочие схемы -> null.
     */
    fun resolveUrl(rawUrl: String?, baseUrl: String): String? =
        ThumbnailUrlResolver.resolve(rawUrl, baseUrl)

    /**
     * Текстовая подпись картинки в транскрипте (векторных иконок в TextView нет,
     * эмодзи запрещены): alt из markdown или нейтральное «Изображение».
     */
    fun inlineLabel(alt: String?): String =
        alt?.trim()?.takeIf { it.isNotEmpty() } ?: "Изображение"
}
