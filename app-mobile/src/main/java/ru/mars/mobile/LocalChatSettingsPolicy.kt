package ru.mars.mobile

/**
 * Состав окна «Контекст и параметры» локального чата — целиком без Android.
 *
 * Почему состав окна вынесен в политику, а не собирается в самом экране. У окна три
 * источника правды: подписи (что человек читает), значения (что реально настроено) и
 * решения (какие строки вообще показывать). Пока они живут в UI-коде, их нельзя ни
 * проверить без телефона, ни удержать от расхождения: подпись «Piper офлайн» осталась
 * от версии, где голос был только локальным, а рядом уже стоял выбор из шести голосов —
 * и человек читал про Ирину, слушая Алису. Здесь всё это чистые функции над
 * [VoiceProsody] и флагами состояния, поэтому каждая строка проверяется JVM-тестом
 * (`LocalChatSettingsPolicyTest`).
 *
 * Что именно пересобрано (волна 21) и почему:
 * 1. **Убрано устаревшее утверждение об озвучке.** Строка «Голос и темп речи
 *    (Piper офлайн)» с подписью «Голос Ирины готов (22 кГц)» описывала мир, в котором
 *    единственным голосом был локальный piper. После волны 20 голосов шесть, основной —
 *    облачный `plusvibe-alice`, а piper остался резервом ([ServerTtsVoices.VOICE_DIALOG]).
 *    Теперь строка показывает ФАКТИЧЕСКИ выбранный голос и темп
 *    ([prosodyValue]), а готовность офлайн-голоса живёт отдельной подсказкой
 *    ([offlineVoiceHint]) — она про резерв, а не про то, чем Марс говорит сейчас.
 * 2. **Разведены две разные настройки с одинаковым названием.** «Фоновый слух
 *    („Марс, слушай“)» в настройках приложения — это флаг `wake_word` для детектора
 *    в чате ([ru.mars.mobile.asr.WakeWordSetting]). Строка в этом окне управляет
 *    ДРУГИМ: foreground-сервисом, который слушает при погашенном экране
 *    ([ru.mars.mobile.service.MarsVoiceService]). Одинаковая подпись у двух разных
 *    механизмов и была той «дублирующей настройкой»: человек выключал одну и не понимал,
 *    почему вторая продолжает работать. Теперь строка называется по своему смыслу
 *    ([WAKE_TITLE]) и НЕ пишет флаг `wake_word` — у каждого механизма остаётся ровно
 *    один писатель.
 * 3. **Ничего работающего не потеряно.** Модель, уровень размышлений, параметры хода,
 *    полноэкранный разговор, голос и темп, слух с погашенным экраном, память — те же
 *    действия с теми же обработчиками; меняются подписи и то, какие строки дублируют
 *    друг друга.
 *
 * Контракт значений: политика ничего не хранит и не сохраняет. Она описывает, что
 * показать, и не знает ни про `SharedPreferences`, ни про View — поэтому «сохрани
 * существующие данные» проверяется отдельно: ключи [VoiceProsodyPrefs] не менялись,
 * и тест держит их от переименования.
 */
object LocalChatSettingsPolicy {

    /** Заголовок окна. */
    const val TITLE: String = "Контекст и параметры"

    /** Полочки окна: та же разбивка, что была у шторки, — по вопросам, а не по типам полей. */
    enum class Section(val title: String) {
        /** Заполнение окна, расход и входы в провайдера/новый диалог. */
        CONTEXT("ОКНО КОНТЕКСТА И РАСХОД"),

        /** Чем отвечает модель и с какими параметрами. */
        MODEL("МОДЕЛЬ, МЫШЛЕНИЕ И ГЕНЕРАЦИЯ"),

        /** Как звучит ответ, кто слушает фон и что Марс помнит. */
        VOICE("ГОЛОС, ПАМЯТЬ И ФОНОВЫЙ СЛУХ"),
    }

    /**
     * Идентификаторы строк.
     *
     * Нужны, чтобы тест мог утверждать «ровно одна строка на вопрос»: подписи меняются,
     * а id — контракт между окном и обработчиками.
     */
    enum class RowId {
        MODEL,
        REASONING,
        SAMPLING,
        VOICE_MODE,
        PROSODY,
        WAKE_SERVICE,
        MEMORY,
    }

    /**
     * Одна строка окна.
     *
     * [hint] — вторая, тихая строка: она объясняет состояние, но не является значением.
     * [enabled] == false означает «строка-факт»: тап по ней ничего не делает, потому что
     * настройки не существует (модель без reasoning), и мёртвый тап хуже честной подписи.
     */
    data class Row(
        val id: RowId,
        val title: String,
        val value: String,
        val hint: String? = null,
        val enabled: Boolean = true,
    )

    /** Значение, когда модель ещё не выбрана (первый запуск, пустой профиль). */
    const val MODEL_UNSET: String = "Не выбрана"

    /** Значение, когда модель не умеет reasoning: строку не прячем, а объясняем. */
    const val REASONING_UNSUPPORTED: String = "Не поддерживается моделью"

    /** Параметры хода правились вручную: звёздочка — та же метка, что на чипе. */
    const val SAMPLING_CUSTOM: String = "Настроены вручную *"

    const val SAMPLING_DEFAULT: String = "По умолчанию"

    const val VOICE_MODE_TITLE: String = "Полноэкранный голосовой разговор"
    const val VOICE_MODE_VALUE: String = "Живой диалог с орбом и перебиванием"

    /**
     * Строка голоса и темпа: БЕЗ названия движка в заголовке.
     *
     * Движок (piper, edge, ElevenLabs) — подробность маршрута, и он меняется от голоса к
     * голосу; заголовок описывает то, что человек настраивает, а чем именно это звучит —
     * видно в значении.
     */
    const val PROSODY_TITLE: String = "Голос и темп речи"

    /**
     * Строка слуха с погашенным экраном.
     *
     * Название намеренно НЕ «Фоновый слух»: так называется настройка в приложении
     * ([ru.mars.mobile.asr.WakeWordSetting]), и одинаковые подписи у двух разных
     * механизмов — ровно та путаница, из-за которой человек выключал не то.
     */
    const val WAKE_TITLE: String = "Слух с погашенным экраном"
    const val WAKE_ON: String = "Включён — отвечает, пока экран погашен"
    const val WAKE_OFF: String = "Выключен"
    const val WAKE_HINT: String = "«Марс, слушай» работает поверх заблокированного экрана"

    const val MEMORY_TITLE: String = "Память владельца и факты"
    const val MEMORY_VALUE: String = "Профиль и долгосрочная память на телефоне"

    /** Готовность офлайн-голоса: он резерв, а не основной путь. */
    const val OFFLINE_READY: String = "Офлайн-голос: готов"
    const val OFFLINE_ABSENT: String = "Офлайн-голос не скачан — звучит облако или системный голос"

    /**
     * Строки полочки «модель, мышление и генерация».
     *
     * @param model выбранная модель провайдера; `null`/пусто — [MODEL_UNSET].
     * @param supportsReasoning умеет ли модель reasoning (решает каталог, а не окно).
     * @param reasoningLabel человеческая подпись уровня ([LocalChatModelPicker.label]);
     *   при `null` показывается пустое значение, а не выдуманный уровень.
     * @param turnSettingsCustom правил ли человек параметры хода (звёздочка).
     */
    fun modelRows(
        model: String?,
        supportsReasoning: Boolean,
        reasoningLabel: String?,
        turnSettingsCustom: Boolean,
    ): List<Row> = listOf(
        Row(
            id = RowId.MODEL,
            title = "Модель провайдера",
            value = model?.takeIf { it.isNotBlank() } ?: MODEL_UNSET,
        ),
        Row(
            id = RowId.REASONING,
            title = "Уровень размышлений (Reasoning)",
            // У модели без reasoning_controls строки-пикера нет: мёртвый тап хуже честной
            // подписи «не поддерживается».
            value = if (supportsReasoning) reasoningLabel.orEmpty() else REASONING_UNSUPPORTED,
            enabled = supportsReasoning,
        ),
        Row(
            id = RowId.SAMPLING,
            title = "Параметры сэмплинга (Temp, Top-P, Лимит)",
            value = if (turnSettingsCustom) SAMPLING_CUSTOM else SAMPLING_DEFAULT,
        ),
    )

    /**
     * Строки полочки «голос»: ровно три, по одной на вопрос.
     *
     * Раньше здесь стояли четыре строки, и одна из них врала: «Голос и темп речи
     * (Piper офлайн)» с подписью «Голос Ирины готов (22 кГц) / Подготовка / системный
     * резерв» описывала версию с единственным локальным голосом. Сейчас голосов шесть и
     * основной — облачный ([ServerTtsVoices.VOICE_DIALOG]), поэтому строка показывает
     * ФАКТ: выбранный голос и темп ([prosodyValue]), а готовность офлайн-резерва ушла в
     * подсказку ([offlineVoiceHint]) — она про запасной путь, а не про то, чем Марс
     * говорит сейчас.
     *
     * Вторая правка — название строки слуха: «Фоновый слух» в этом окне и «Фоновый слух»
     * в настройках приложения оказались двумя РАЗНЫМИ механизмами с одинаковой подписью
     * (флаг `wake_word` для детектора в чате против foreground-сервиса, который слушает
     * при погашенном экране). Человек выключал одну настройку и не понимал, почему
     * вторая продолжает работать; теперь строка названа по своему смыслу ([WAKE_TITLE]).
     *
     * Полноэкранный разговор остаётся строкой: это вход в режим, а не настройка, и в окне
     * его ищут так же, как чип в шапке.
     *
     * @param prosody сохранённые голос и темп ([VoiceProsodyPrefs]).
     * @param offlineVoiceReady поднят ли офлайн-синтез прямо сейчас (`OfflineVoice`).
     *   Это факт, а не настройка: окно ничего не скачивает.
     * @param wakeArmed запущен ли сервис слуха с погашенным экраном.
     */
    fun voiceRows(
        prosody: VoiceProsody,
        offlineVoiceReady: Boolean,
        wakeArmed: Boolean,
    ): List<Row> = listOf(
        Row(
            id = RowId.VOICE_MODE,
            title = VOICE_MODE_TITLE,
            value = VOICE_MODE_VALUE,
        ),
        Row(
            id = RowId.PROSODY,
            title = PROSODY_TITLE,
            value = prosodyValue(prosody),
            hint = offlineVoiceHint(offlineVoiceReady),
        ),
        Row(
            id = RowId.WAKE_SERVICE,
            title = WAKE_TITLE,
            value = wakeValue(wakeArmed),
            hint = WAKE_HINT,
        ),
    )

    /** Строка памяти: вход в профиль и факты, отдельно от голоса по смыслу. */
    fun memoryRow(): Row = Row(
        id = RowId.MEMORY,
        title = MEMORY_TITLE,
        value = MEMORY_VALUE,
    )

    /**
     * Значение строки голоса: «Алиса (PlusVibe) · обычный темп».
     *
     * Имя голоса берётся из [VoiceProsodyPolicy.voiceLabel], а не из сохранённого id:
     * `plusvibe-alice` человеку ничего не говорит, а «Алиса (PlusVibe)» — говорит; при
     * `voiceId == null` это основной голос приложения, то есть тот, который он и услышит.
     * Темп — [VoiceProsody.speedLabel], чтобы окно и шторка не показывали «1.15×» и
     * «1.2×» для одного значения.
     */
    fun prosodyValue(prosody: VoiceProsody): String =
        "${VoiceProsodyPolicy.voiceLabel(prosody)} · ${prosody.speedLabel()}"

    /**
     * Подсказка офлайн-резерва.
     *
     * Не «Голос Ирины готов (22 кГц)»: частота дискретизации человеку ничего не решает, а
     * «готов» рядом с выбранной Алисой читалось как «звучит Ирина». Здесь сказано ровно
     * то, что верно в обоих случаях: есть ли резерв и что звучит, если его нет.
     */
    fun offlineVoiceHint(ready: Boolean): String = if (ready) OFFLINE_READY else OFFLINE_ABSENT

    /** Значение строки слуха: состояние сервиса, а не флага `wake_word`. */
    fun wakeValue(armed: Boolean): String = if (armed) WAKE_ON else WAKE_OFF

    /** Все строки окна в порядке отрисовки — для тестов на «одна строка на вопрос». */
    fun allRows(
        model: String?,
        supportsReasoning: Boolean,
        reasoningLabel: String?,
        turnSettingsCustom: Boolean,
        prosody: VoiceProsody,
        offlineVoiceReady: Boolean,
        wakeArmed: Boolean,
    ): List<Row> = modelRows(model, supportsReasoning, reasoningLabel, turnSettingsCustom) +
        voiceRows(prosody, offlineVoiceReady, wakeArmed) +
        memoryRow()
}
