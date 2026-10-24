package ru.mars.mobile.asr

import android.content.Context
import android.content.SharedPreferences
import ru.mars.mobile.ChatActivity
import ru.mars.mobile.VOICE_OUTPUT_PREFS

/**
 * Ключ флага wake word в prefs голосового слоя ([VOICE_OUTPUT_PREFS]).
 *
 * Живёт рядом с настройкой, а не в `ChatVoiceStrings` вместе с [ru.mars.mobile.KEY_VOICE_BARGE_IN]:
 * у флага ровно один потребитель (настройки пишут, чат читает), и связь «ключ — класс» важнее
 * соседства с остальными голосовыми строками.
 */
internal const val KEY_WAKE_WORD = "wake_word"

/**
 * Дефолт wake word — ВЫКЛЮЧЕНО.
 *
 * Требование владельца, а не перестраховка: пока экран активен, детектор держит микрофон открытым
 * постоянно — это расход батареи и постоянный индикатор приватности системы. Такое поведение
 * нельзя включать по умолчанию: человек выбирает его сам, зная про расход (подсказка стоит рядом
 * с тумблером в настройках).
 */
internal const val DEFAULT_WAKE_WORD_ENABLED = false

/**
 * Флаг «слушать „Марс“» — единственное место, где он читается и пишется.
 *
 * Устроен точно как [ru.mars.mobile.VoiceBargeInSetting]: чтение/запись передаются лямбдами,
 * поэтому JVM-тест проверяет дефолт и сохранение без Android Runtime, а приложение получает их
 * через [of]. Второй источник правды о дефолте недопустим: экран настроек и чат разошлись бы
 * по разным ключам — ровно так уже было с перебиванием голосом, когда у настройки не было writer'а.
 *
 * @param read `(key, default) -> value` — чтение булева значения из хранилища.
 * @param write `(key, value) -> Unit` — запись; асинхронность (`apply`) — забота хранилища.
 */
internal class WakeWordSetting(
    private val read: (String, Boolean) -> Boolean,
    private val write: (String, Boolean) -> Unit,
) {

    /** Значение: отсутствие ключа = [DEFAULT_WAKE_WORD_ENABLED] (выключено). */
    fun isEnabled(): Boolean = read(KEY_WAKE_WORD, DEFAULT_WAKE_WORD_ENABLED)

    /** Сохранить выбор пользователя: записанное значение перекрывает дефолт. */
    fun setEnabled(enabled: Boolean) = write(KEY_WAKE_WORD, enabled)

    companion object {
        /** Настройка поверх prefs-файла голосового вывода ([VOICE_OUTPUT_PREFS]). */
        fun of(prefs: SharedPreferences): WakeWordSetting = WakeWordSetting(
            read = prefs::getBoolean,
            write = { key, value -> prefs.edit().putBoolean(key, value).apply() },
        )
    }
}

/**
 * Флаг wake word поверх того же `SharedPreferences`, что читает голосовой слой чата.
 *
 * Значение нужно в двух точках жизни экрана: при возврате на передний план (решается, слушать ли)
 * и после выключения голосового диалога (пере-взвод детектора). Оба раза флаг читается заново,
 * поэтому переключатель в настройках применяется без перезапуска приложения.
 */
internal fun ChatActivity.wakeWordSetting(): WakeWordSetting =
    WakeWordSetting.of(getSharedPreferences(VOICE_OUTPUT_PREFS, Context.MODE_PRIVATE))
