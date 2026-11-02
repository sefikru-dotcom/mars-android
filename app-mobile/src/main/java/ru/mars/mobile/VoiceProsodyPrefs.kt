package ru.mars.mobile

import android.content.Context

/**
 * Хранение темпа и голоса между запусками: `SharedPreferences` за портом [VoiceProsodyStore].
 *
 * Зачем понадобился отдельный файл, хотя порт объявлен рядом с [VoiceProsody]: порт —
 * чистая JVM (его проверяют фейком), а `SharedPreferences` тянет `android.*`. Держать
 * реализацию в том же файле значило бы либо затащить Android в чистый тип, либо оставить
 * вызывающий слой самому придумывать имена ключей — и тогда два экрана (серверный чат и
 * локальный диалог) рано или поздно писали бы в разные ключи, а человек видел бы «настройка
 * не сохранилась» без единой ошибки.
 *
 * Правила:
 * - при чтении значения зажимаются в границы [VoiceProsody] — файл настроек мог остаться
 *   от версии с другими пределами, и «3.0×» вместо речи дал бы кашу;
 * - состояние по умолчанию НЕ хранится: `isDefault` означает «как в вебе и как в модели»,
 *   и запись дефолта маскировала бы будущую смену дефолта в коде;
 * - пустой [VoiceProsody.voiceId] читается как `null` («голос не выбран»), а не как строка
 *   нулевой длины: иначе [VoiceProsodyPolicy.selected] не нашёл бы вариант и подсветка
 *   потерялась бы.
 *
 * @param prefsName имя файла настроек; параметр нужен тестам и будущему разделению профилей.
 */
internal class VoiceProsodyPrefs(
    context: Context,
    prefsName: String = PREFS_NAME,
) : VoiceProsodyStore {

    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun load(): VoiceProsody {
        val speed = prefs.getFloat(KEY_SPEED, VoiceProsody.DEFAULT_SPEED)
        val pitch = prefs.getFloat(KEY_PITCH, VoiceProsody.DEFAULT_PITCH)
        val voice = prefs.getString(KEY_VOICE, null)?.trim().orEmpty()
        return VoiceProsody(
            speed = VoiceProsody.clampSpeed(speed),
            pitch = VoiceProsody.clampPitch(pitch),
            voiceId = voice.ifEmpty { null },
        )
    }

    override fun save(prosody: VoiceProsody) {
        val editor = prefs.edit()
        if (prosody.isDefault) {
            // Дефолт — это отсутствие настройки, а не значение: хранить «обычный темп»
            // значило бы навсегда зафиксировать сегодняшние пределы и дефолтный голос.
            editor.clear()
        } else {
            editor.putFloat(KEY_SPEED, prosody.systemSpeechRate())
            editor.putFloat(KEY_PITCH, prosody.systemPitch())
            val voice = prosody.voiceId?.trim()
            if (voice.isNullOrEmpty()) editor.remove(KEY_VOICE) else editor.putString(KEY_VOICE, voice)
        }
        editor.apply()
    }

    companion object {
        /** Отдельный файл от `mars-voice-output`: там живёт флаг автоозвучки, здесь — тембр. */
        const val PREFS_NAME: String = "mars-voice-prosody"

        const val KEY_SPEED: String = "speed"
        const val KEY_PITCH: String = "pitch"

        /** Идентификатор профиля из [ru.mars.network.repository.ServerTtsVoices]. */
        const val KEY_VOICE: String = "voice_id"
    }
}