package ru.mars.mobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Сохранить в галерею» для сгенерированной картинки.
 *
 * Два пути, и оба нужны:
 *  - Android 10+ (API 29+, у нас minSdk 28) — `MediaStore` с `RELATIVE_PATH`, без единого
 *    разрешения: файл пишется в общий альбом `Pictures/Mars`;
 *  - Android 9 — там `RELATIVE_PATH` нет, а прямой `File` в `getExternalStoragePublicDirectory`
 *    требует WRITE_EXTERNAL_STORAGE. Разрешения не просим: это лишний диалог ради одной
 *    кнопки, и человек с Android 9 получает честный отказ с причиной, а не молчание.
 *
 * Копируем, а не переносим: файл в `cacheDir` остаётся источником для ленты (иначе
 * картинка на экране стала бы битой сразу после сохранения).
 *
 * Возвращается `Uri` записи MediaStore — по нему экран говорит «сохранено» и может
 * открыть галерею; ошибка — исключение с русским текстом (его экран показывает в тосте).
 */
internal object GallerySaver {

    /** Альбом, в который кладётся картинка: относительный путь внутри общей картинковой папки. */
    // Не `const`: склейка строки с константой Android — не константное выражение
    // для Kotlin, и `const val` здесь не компилируется.
    val RELATIVE_PATH: String = Environment.DIRECTORY_PICTURES + "/Mars"

    /** Отказ на Android 9: без разрешения писать в общую папку нельзя. */
    const val LEGACY_REFUSAL: String =
        "На этой версии Android сохранить в галерею можно через «Поделиться» → «Сохранить в…»"

    fun canSave(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Записать файл в галерею. Только API 29+; на более старых — [LEGACY_REFUSAL].
     *
     * @throws IllegalStateException с текстом для человека, если запись не вышла.
     */
    suspend fun save(context: Context, file: File, displayName: String? = null): Uri =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error(LEGACY_REFUSAL)
            if (!file.isFile || file.length() == 0L) error("файл картинки пропал — сохранять нечего")

            val resolver = context.contentResolver
            val name = displayName?.takeIf { it.isNotBlank() }
                ?: (file.nameWithoutExtension + ".jpg")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                // Без IS_PENDING картинка видна галерее недописанной — обрезанный превью.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("галерея не приняла запись")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("галерея не открыла поток записи")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (error: Exception) {
                // Недописанная запись висит в галерее битым файлом — убираем её.
                runCatching { resolver.delete(uri, null, null) }
                throw IllegalStateException(
                    "не сохранилось: " + (error.message ?: error.javaClass.simpleName),
                )
            }
        }
}
