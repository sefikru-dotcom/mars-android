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
 * `engine=edge, voice=ru-RU-SvetlanaNeural, rate=+8%` → `200 audio/mpeg`, TTFB ≈ 1.7 с;
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
 * мужской Dmitry (тоже edge-нейросеть), локальный резерв irina — последним. Менять
 * порядок местами нельзя: это превратило бы резерв в «навязанный» голос и вернуло бы
 * ровно ту проблему, ради которой профили и заведены.
 */
object ServerTtsVoices {

    /**
     * Естественный голос веба: edge-tts, `ru-RU-SvetlanaNeural`, темп +8 %.
     *
     * Живой замер: `200`, mp3, TTFB ≈ 1.7 с (2.5 с без ускорения). На боевом сервере
     * Марса `edge-tts` установлен и синтез этим голосом отвечает аудио (проверено живым
     * запросом к `/api/tts`, ~28 КБ mp3), поэтому «edge не работает» НЕ является
     * причиной держать локальный голос основным.
     *
     * Почему не Dmitry: в вебе Марс говорит Svetlana, и Dmitry — другой голос, а не «тот
     * же голос другим движком». Прежняя заметка «2/5 успехов и зависание на 90 с»
     * относилась к периоду, когда `edge-tts` на сервере не был установлен (движок отвечал
     * 503), и к ней нельзя возвращаться как к доводу против edge: доступность голоса
     * определяется фактическим ответом `api/tts` (см. `SpeechVoiceProfileSelector`), а не
     * заметкой в коде.
     */
    val NATURAL_SVETLANA: ServerTtsVoiceProfile = ServerTtsVoiceProfile(
        id = "natural-svetlana",
        engine = "edge",
        voice = "ru-RU-SvetlanaNeural",
        rate = "+8%",
        pitch = "+0Hz",
        natural = true,
        needsNetwork = true,
    )

    /**
     * Мужской естественный голос веба: edge-tts, `ru-RU-DmitryNeural`, темп +5 %.
     *
     * Зачем второй сетевой голос: человеку нужен ВЫБОР пола голоса, а не одна Светлана.
     * Dmitry — тот же движок (`edge`), поэтому доступность у него та же, что у Svetlana:
     * если edge на сервере установлен, работает и он. Свой темп `+5%` вместо `+10%`:
     * мужская нейросеть на ускорении Светланы звучит торопливо.
     *
     * Стоит ВТОРЫМ в [VOICE_DIALOG], а не первым: голос веба по умолчанию — Светлана, и
     * Dmitry обязан быть выбором, а не подменой.
     */
    val NATURAL_DMITRY: ServerTtsVoiceProfile = ServerTtsVoiceProfile(
        id = "natural-dmitry",
        engine = "edge",
        voice = "ru-RU-DmitryNeural",
        rate = "+5%",
        pitch = "+0Hz",
        natural = true,
        needsNetwork = true,
    )

    /**
     * Тёплый мультиязычный нейро-голос в стиле ChatGPT: `en-US-AvaMultilingualNeural`.
     *
     * Зачем третий сетевой голос: Светлана и Дмитрий — «дикторы», ровная новостная подача.
     * Ava читает свободно и по-русски, и по-английски, с живой разговорной интонацией —
     * это третий РАЗНЫЙ голос, а не перекраска двух первых.
     *
     * Ограничение сервера: в allowlist `/api/tts` только `ru-RU-SvetlanaNeural` и
     * `ru-RU-DmitryNeural`, поэтому серверный маршрут отвечает `400` на Ava. Именно для
     * этого голоса прямой WebSocket ([EdgeNeuralTtsClient]) — основной путь: Microsoft
     * отдаёт Ava без allowlist, и голос звучит с телефона напрямую.
     */
    val NATURAL_AVA: ServerTtsVoiceProfile = ServerTtsVoiceProfile(
        id = "natural-ava",
        engine = "edge",
        voice = "en-US-AvaMultilingualNeural",
        rate = "+5%",
        pitch = "+0Hz",
        natural = true,
        needsNetwork = true,
    )

    /**
     * Локальный резерв: piper `ru_RU-irina-medium`, TTFB ≈ 0.22 с, RTF 0.052.
     *
     * Почему именно irina: единственный проверенный piper-голос, который произносит
     * «Слэш» правильно без словаря (dmitri/denis/ruslan коверкают имя). Используется
     * ТОЛЬКО когда естественный голос недоступен (нет сети, движок выключен, 4xx по
     * голосу): это резерв, а не альтернатива. Право на попытку даёт фактический отказ
     * естественного профиля, а не догадка клиента; каталог сервера содержит
     * `ru_RU-irina-medium` (проверено живым синтезом, WAV ~157 КБ).
     */
    val LOCAL_IRINA: ServerTtsVoiceProfile = ServerTtsVoiceProfile(
        id = "local-irina",
        engine = "piper",
        voice = "ru_RU-irina-medium",
        rate = null,
        natural = false,
        needsNetwork = false,
    )

    /**
     * Политика голосового диалога: Светлана, Дмитрий, Ава, локальный резерв Ирина.
     *
     * `first()` обязан оставаться Светланой: это голос веба по умолчанию, и понижение
     * профиля в селекторе голоса отсчитывается именно от него.
     *
     * Четыре РАЗНЫХ голоса — требование владельца: раньше «Как в вебе», «Светлана» и
     * «Дмитрий» звучали одним системным голосом Android, потому что серверный синтез
     * падал (`429`/`401`) и движок молча уходил в фолбэк. Дубля «Как в вебе» больше нет:
     * `voiceId = null` подсвечивает Светлану ([VoiceProsodyPolicy.selected]).
     */
    val VOICE_DIALOG: List<ServerTtsVoiceProfile> =
        listOf(NATURAL_SVETLANA, NATURAL_DMITRY, NATURAL_AVA, LOCAL_IRINA)

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
