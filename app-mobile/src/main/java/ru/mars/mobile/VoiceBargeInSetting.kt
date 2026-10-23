package ru.mars.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * Флаг L3 «перебивать ответ голосом» — единственное место, где он читается и пишется.
 *
 * Раньше флаг только читался ([ChatInitSection]) и жил в конструкторе цикла: у настройки не было
 * writer'а, поэтому поведение «как в ChatGPT» нельзя было ни выключить, ни включить обратно.
 * Два независимых места чтения (`getBoolean(KEY_VOICE_BARGE_IN, …)` в настройках и в чате) завели
 * бы второй источник правды о дефолте — здесь он один: [DEFAULT_VOICE_BARGE_IN].
 *
 * Чтение/запись передаются лямбдами, а не `SharedPreferences` напрямую, чтобы JVM-тест проверял
 * дефолт и сохранение без Android Runtime; в приложении их даёт [of].
 *
 * @param read `(key, default) -> value` — чтение булева значения из хранилища.
 * @param write `(key, value) -> Unit` — запись; асинхронность (`apply`) — забота хранилища.
 */
internal class VoiceBargeInSetting(
    private val read: (String, Boolean) -> Boolean,
    private val write: (String, Boolean) -> Unit,
) {

    /** Текущее значение: отсутствие ключа = [DEFAULT_VOICE_BARGE_IN] (перебивание включено). */
    fun isEnabled(): Boolean = read(KEY_VOICE_BARGE_IN, DEFAULT_VOICE_BARGE_IN)

    /** Сохранить выбор пользователя. Дефолт не участвует: записанное значение его перекрывает. */
    fun setEnabled(enabled: Boolean) = write(KEY_VOICE_BARGE_IN, enabled)

    companion object {
        /** Настройка поверх реального prefs-файла голосового вывода ([VOICE_OUTPUT_PREFS]). */
        fun of(prefs: SharedPreferences): VoiceBargeInSetting = VoiceBargeInSetting(
            read = prefs::getBoolean,
            write = { key, value -> prefs.edit().putBoolean(key, value).apply() },
        )
    }
}

/**
 * Флаг перебивания поверх того же `SharedPreferences`, что читает голосовой слой чата.
 *
 * Отдельная функция, потому что значение нужно в двух точках жизни экрана: при создании
 * [VoiceDialogLoop] и при каждом возврате в чат ([ChatActivity.resumeChat]) — вторая точка и даёт
 * применение настройки без перезапуска приложения.
 */
internal fun ChatActivity.bargeInSetting(): VoiceBargeInSetting =
    VoiceBargeInSetting.of(getSharedPreferences(VOICE_OUTPUT_PREFS, Context.MODE_PRIVATE))
