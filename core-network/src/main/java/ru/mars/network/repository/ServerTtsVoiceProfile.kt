package ru.mars.network.repository

/**
 * Голосовой профиль серверного синтеза: ЧТО просить у `POST /api/tts`.
 *
 * Зачем отдельный тип: «движок» и «голос» — это два поля запроса, которые должны ехать
 * вместе и не разъезжаться. Раньше Android слал только `{"text": …}`, и сервер подставлял
 * свой дефолт (`piper` + `ru_RU-dmitri-medium`) — то есть приложение говорило не тем
 * голосом, который выбран в вебе. Профиль фиксирует пару (engine, voice) + темп одним
 * значением, поэтому «естественный голос» — это явный объект, а не набор констант по
 * вызовам.
 *
 * [natural] — «желанный» голос (тот, что нравится в вебе: нейросеть Svetlana). Он ВСЕГДА
 * идёт первым в списке кандидатов; локальный piper — только резерв, а не замена.
 * [needsNetwork] — профиль требует сети (edge-tts). Локальный профиль работает и без неё.
 *
 * Контракт проверен живьём (см. `.mars/voice-upgrade/research/REPORT.md`):
 * `engine=edge, voice=ru-RU-SvetlanaNeural, rate=+10%` → `200 audio/mpeg`, TTFB ≈ 1.7 с;
 * `engine=piper, voice=ru_RU-irina-medium` → `200 audio/wav`, TTFB ≈ 0.22 с.
 */
data class ServerTtsVoiceProfile(
    /** Стабильный идентификатор профиля (для логов и настроек, не для сервера). */
    val id: String,
    /** Поле `engine` запроса: `edge` или `piper`. */
    val engine: String,
    /** Поле `voice` запроса: имя голоса движка. */
    val voice: String,
    /** Поле `rate` (`+10%`) или null — темп движка по умолчанию. */
    val rate: String? = null,
    /** Поле `pitch` (`-5Hz`) или null — высота по умолчанию. */
    val pitch: String? = null,
    /** Желанный («естественный») голос: приоритет в выборе, не вытесняется резервом. */
    val natural: Boolean = false,
    /** Нужна сеть: локальный piper синтезирует на сервере и без выхода наружу. */
    val needsNetwork: Boolean = true,
) {

    /** Локальный (offline-способный) профиль — резерв, а не основной голос. */
    val isLocal: Boolean
        get() = !needsNetwork

    /** Короткая подпись для логов: без текста и без секретов. */
    val logTag: String
        get() = "$id($engine/$voice)"
}

/**
 * Профили голоса, которыми пользуется приложение.
 *
 * Порядок [VOICE_DIALOG] — это и есть политика: сначала естественный Svetlana, затем
 * локальный резерв irina. Менять порядок местами нельзя: это превратило бы резерв в
 * «навязанный» голос и вернуло бы ровно ту проблему, ради которой профили и заведены.
 */
object ServerTtsVoices {

    /**
     * Естественный голос веба: edge-tts, `ru-RU-SvetlanaNeural`, темп +10 %.
     *
     * Живой замер: `200 audio/mpeg`, 48 528 байт, TTFB 1.70 с (2.53 с без ускорения).
     * Dmitry через edge НЕ используется: 2/5 успехов и зависание на 90 с.
     */
    val NATURAL_SVETLANA: ServerTtsVoiceProfile = ServerTtsVoiceProfile(
        id = "natural-svetlana",
        engine = "edge",
        voice = "ru-RU-SvetlanaNeural",
        rate = "+10%",
        natural = true,
        needsNetwork = true,
    )

    /**
     * Локальный резерв: piper `ru_RU-irina-medium`, TTFB ≈ 0.22 с, RTF 0.052.
     *
     * Почему именно irina: единственный проверенный piper-голос, который произносит
     * «Слэш» правильно без словаря (dmitri/denis/ruslan коверкают имя). Используется
     * ТОЛЬКО когда естественный голос недоступен (нет сети, движок выключен, 4xx по
     * голосу) и только если сервер подтвердил наличие модели.
     */
    val LOCAL_IRINA: ServerTtsVoiceProfile = ServerTtsVoiceProfile(
        id = "local-irina",
        engine = "piper",
        voice = "ru_RU-irina-medium",
        rate = null,
        natural = false,
        needsNetwork = false,
    )

    /** Политика голосового диалога: естественный первым, локальный резерв вторым. */
    val VOICE_DIALOG: List<ServerTtsVoiceProfile> = listOf(NATURAL_SVETLANA, LOCAL_IRINA)

    /** Профиль по [id] или null (для настроек и миграции сохранённого выбора). */
    fun byId(id: String?): ServerTtsVoiceProfile? =
        VOICE_DIALOG.firstOrNull { it.id == id?.trim() }
}

/**
 * Один запрос синтеза: текст + профиль, уже разложенный на поля тела.
 *
 * Отдельный тип нужен транспорту ([ServerTtsTransport] в app-mobile): пять отдельных
 * аргументов (text, rate, pitch, engine, voice) слишком легко перепутать местами, а
 * ошибка здесь стоит неверного голоса или 400 от сервера.
 */
data class ServerTtsSpeechRequest(
    val text: String,
    val engine: String? = null,
    val voice: String? = null,
    val rate: String? = null,
    val pitch: String? = null,
) {

    companion object {

        /** Запрос по профилю; null-профиль — «пусть сервер выберет сам» (старое поведение). */
        fun of(text: String, profile: ServerTtsVoiceProfile?): ServerTtsSpeechRequest =
            ServerTtsSpeechRequest(
                text = text,
                engine = profile?.engine,
                voice = profile?.voice,
                rate = profile?.rate,
                pitch = profile?.pitch,
            )
    }
}
