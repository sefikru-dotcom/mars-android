package ru.mars.network.local

import org.json.JSONObject
import java.io.File

/**
 * Владелец телефона: кто он и что о нём стоит помнить всегда.
 *
 * Отдельно от [MemoryStore] намеренно: профиль — короткий и правится руками в
 * настройках, память — растущий список фактов, который модель дописывает сама.
 * Смешав их, нельзя было бы ни показать «кто я» одним экраном, ни очистить память,
 * не потеряв имя.
 *
 * Поля необязательные и по умолчанию пустые: пустой профиль — нормальное состояние
 * нового устройства, а не ошибка. `updatedAt` в миллисекундах эпохи, `0` — «никогда
 * не сохраняли».
 */
data class OwnerProfile(
    val name: String = "",
    val city: String = "",
    val about: String = "",
    val updatedAt: Long = 0L,
) {

    /** Заполнено ли хоть что-то, кроме отметки времени. */
    val isEmpty: Boolean
        get() = name.isBlank() && city.isBlank() && about.isBlank()

    /**
     * Блок «О владельце» для системного промпта. Пустая строка, если все поля пусты —
     * тогда вызывающий не добавляет в промпт пустой раздел.
     *
     * Пустые поля не печатаются: строка «Город: » учит модель считать, что город
     * известен, и она начинает его выдумывать.
     *
     * Блок заканчивается переводом строки — координатор склеивает его с памятью и
     * правилами конкатенацией.
     */
    fun toPromptSection(): String {
        if (isEmpty) return ""
        val sb = StringBuilder(HEADER).append('\n')
        if (name.isNotBlank()) sb.append("- Имя: ").append(name.trim()).append('\n')
        if (city.isNotBlank()) sb.append("- Город: ").append(city.trim()).append('\n')
        if (about.isNotBlank()) sb.append("- О себе: ").append(about.trim()).append('\n')
        return sb.toString()
    }

    private companion object {
        const val HEADER = "О владельце:"
    }
}

/**
 * Хранилище профиля владельца: один маленький JSON-файл, атомарная запись.
 *
 * JSON, а не плоский текст: полей станет больше (часовой пояс, язык, обращение), и
 * добавлять их тогда нужно без ломки старого файла — отсутствующее поле читается как
 * пустая строка, лишнее молча игнорируется. Ручной разбор здесь не нужен: `org.json`
 * уже есть в зависимостях модуля (`android.jar`), новых зависимостей волна не вводит.
 *
 * Битый или отсутствующий файл — [OwnerProfile] со значениями по умолчанию, никогда
 * исключение: профиль не тот объект, ради которого стоит ронять экран чата.
 *
 * Путь передаёт вызывающий ([file]) — модуль чистый, без `android.*`.
 */
class OwnerProfileStore(private val file: File) {

    /** Прочитать профиль. Нет файла / битый JSON — пустой профиль. */
    fun load(): OwnerProfile {
        val raw = LocalFileIo.readText(file) ?: return OwnerProfile()
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(OwnerProfile())
    }

    /**
     * Записать профиль. Поля обрезаются по краям, `updatedAt` = сейчас, если не задан.
     *
     * @return `true`, если файл на диске; `false` — записать не удалось (место, права).
     * Исключение наружу не летит: экран покажет «не сохранилось» по `false`.
     */
    fun save(profile: OwnerProfile): Boolean {
        val normalized = profile.copy(
            name = profile.name.trim(),
            city = profile.city.trim(),
            about = profile.about.trim(),
            updatedAt = profile.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
        return LocalFileIo.writeAtomic(file, encode(normalized).toString())
    }

    /** Блок для системного промпта по сохранённому профилю (см. [OwnerProfile.toPromptSection]). */
    fun toPromptSection(): String = load().toPromptSection()

    /** Есть ли файл профиля (для экрана настроек: «заполнен» / «не заполнен»). */
    fun exists(): Boolean = file.isFile

    private fun encode(profile: OwnerProfile): JSONObject = JSONObject().apply {
        put(KEY_VERSION, VERSION)
        put(KEY_NAME, profile.name)
        put(KEY_CITY, profile.city)
        put(KEY_ABOUT, profile.about)
        put(KEY_UPDATED_AT, profile.updatedAt)
    }

    /**
     * Разбор терпимый: неизвестные ключи игнорируются, отсутствующие дают пустые
     * строки, `null` в значении — тоже пустая строка (а не строка «null»).
     */
    private fun decode(json: JSONObject): OwnerProfile = OwnerProfile(
        name = string(json, KEY_NAME),
        city = string(json, KEY_CITY),
        about = string(json, KEY_ABOUT),
        updatedAt = json.optLong(KEY_UPDATED_AT, 0L),
    )

    private fun string(json: JSONObject, key: String): String =
        if (json.isNull(key)) "" else json.optString(key, "").trim()

    private companion object {
        const val VERSION = 1

        const val KEY_VERSION = "version"
        const val KEY_NAME = "name"
        const val KEY_CITY = "city"
        const val KEY_ABOUT = "about"
        const val KEY_UPDATED_AT = "updatedAt"
    }
}
