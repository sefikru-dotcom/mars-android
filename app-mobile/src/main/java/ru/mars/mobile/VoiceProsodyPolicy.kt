package ru.mars.mobile

import ru.mars.network.repository.ServerTtsVoiceProfile
import ru.mars.network.repository.ServerTtsVoices

/**
 * Один голос в списке выбора: что показать и что применить.
 *
 * Зачем `id` отдельно от `prosody.voiceId`: `id` — идентификатор СТРОКИ списка (по нему
 * шторка решает, какая пилюля подсвечена), а `prosody.voiceId` — то, что реально уедет
 * в настройки. Сегодня они совпадают, но совпадение — не контракт: списку может
 * понадобиться строка-сброс («как в вебе»), у которой `voiceId` нет вовсе.
 *
 * `label` и `hint` разделены, потому что это разные уровни чтения: подпись пилюли
 * («Светлана») человек видит краем глаза, пояснение («нужна сеть») — только когда
 * выбирает осознанно. Слепить их в одну строку значило бы заставить каждую пилюлю
 * читаться целиком.
 *
 * `prosody` — темп и высота, с которыми этот голос звучит по умолчанию. Здесь всегда
 * `speed = 1.0`: собственный темп голоса (у Svetlana это `rate = +10%` в
 * [ServerTtsVoiceProfile]) — свойство ПРОФИЛЯ на сервере, и дублировать его в множителе
 * значило бы применить ускорение дважды и показать человеку «1.1×» там, где он ничего
 * не крутил.
 */
data class VoiceOption(
    val id: String,
    val label: String,
    val hint: String,
    val prosody: VoiceProsody,
)

/**
 * Правила шторки «Голос и темп»: какие голоса предлагать, какая ступень темпа следующая
 * и как это назвать по-русски.
 *
 * Зачем отдельная политика, а не код в шторке. Список голосов обязан совпадать с тем,
 * чем реально говорит приложение, а порядок — с [ServerTtsVoices.VOICE_DIALOG]: там
 * естественный голос веба объявлен основным, а piper — резервом. Если бы шторка
 * собирала список сама, однажды появился бы третий «красивый» голос, которого нет в
 * `voices_dir` сервера, и человек выбирал бы молчание. Поэтому здесь ровно два
 * источника правды: [ServerTtsVoices] для серверных голосов и системный TTS как путь
 * деградации (он не пункт списка — его выбирает движок, когда серверный голос отказал;
 * отдельной строкой он был бы мёртвой кнопкой, потому что выбрать «системный» нельзя,
 * не отказавшись от серверного синтеза целиком).
 *
 * Почему темп — ступени, а не ползунок. Ступени ([VoiceProsody.STEP_CHOICES]) — это
 * осмысленные значения, которые человек различает на слух: 0.9 и 1.0 звучат по-разному
 * только в сравнении, а 1.3 и 1.35 — никак. Ползунок на 0.5..2.0 дал бы 150 состояний,
 * 140 из которых неотличимы, и «плюс» на нём не имел бы предсказуемого шага. Шаг
 * дискретный ещё и потому, что темп уезжает в три разных представления
 * ([VoiceProsody.serverRate], [VoiceProsody.systemSpeechRate], [VoiceProsody.offlineSpeed]):
 * целые проценты и целые сотые множителя не дают накопить расхождение между ними.
 *
 * Политика ничего не сохраняет и ничего не синтезирует: [VoiceProsodyStore] пишет хост,
 * «Проверить» проигрывает вызывающий. Здесь нет ни одного `android.*`, поэтому правила
 * проверяются юнит-тестами, а не эмулятором.
 */
object VoiceProsodyPolicy {

    /** Заголовок шторки. */
    const val TITLE: String = "Голос и темп"

    /** Надпись над списком голосов. */
    const val VOICE_CAPTION: String = "Голос"

    /** Надпись над строкой темпа. */
    const val SPEED_CAPTION: String = "Темп речи"

    /** Пункт «послушать выбранное» — саму фразу синтезирует вызывающий. */
    const val PREVIEW_LABEL: String = "Проверить"

    /** Подтверждение выбора. */
    const val APPLY_LABEL: String = "Готово"

    const val CANCEL_LABEL: String = "Отмена"

    /**
     * Строка сброса: «не выбирать голос».
     *
     * Нужна, потому что `voiceId = null` — законное состояние («как в вебе»), а не
     * отсутствие настройки: человек, выбравший Ирину, должен иметь путь назад, не
     * вспоминая, какой голос был до этого.
     */
    const val DEFAULT_VOICE_LABEL: String = "Как в вебе"

    const val DEFAULT_VOICE_HINT: String = "Алиса — основной голос приложения (PlusVibe)"

    /**
     * Фраза для «Проверить».
     *
     * Короткая и с «р» и шипящими: на ней слышно и темп, и то, что голос не «бурундук».
     * Живёт в политике, а не в шторке, чтобы текст правки и текст проверки не разъехались.
     */
    const val PREVIEW_TEXT: String = "Привет! Так я буду читать ответы."

    private const val EPSILON: Float = 0.001f

    /**
     * Список голосов: основной PlusVibe, три нейро-голоса Edge, дешёвый PlusVibe и локальный
     * резерв Ирина.
     *
     * Порядок наследуется от [ServerTtsVoices.VOICE_DIALOG] и не пересортировывается:
     * «основной первым» — это и есть политика голоса, а не удобство отрисовки.
     * Собственного фильтра по доступности здесь нет намеренно: доступность выясняется
     * фактическим ответом `api/tts` ([SpeechVoiceProfileSelector]), а не догадкой клиента,
     * и прятать голос из списка по недоказанной причине значило бы врать человеку.
     */
    fun options(): List<VoiceOption> = ServerTtsVoices.VOICE_DIALOG.map { profile -> option(profile) }

    /**
     * Выбранный вариант по сохранённому темпу; `null` — только если списка нет.
     *
     * `voiceId == null` («как в вебе») подсвечивает ОСНОВНОЙ голос приложения
     * ([ServerTtsVoices.DEFAULT_VOICE], сегодня PlusVibe Alice): именно он звучит, когда
     * человек ничего не выбирал, и подсветить вместо него Светлану значило бы соврать о том,
     * что он услышит.
     *
     * Сравнение только по [VoiceProsody.voiceId], а не по всему объекту: темп и высота
     * меняются отдельно от голоса, и выбор «Алиса, 1.3×» обязан подсвечивать Алису.
     * Неизвестный `voiceId` (голос убрали из сборки, настройка приехала из старой версии)
     * даёт `null`, а не «первый из списка»: подсветить чужой голос значило бы соврать о
     * текущем состоянии, и человек услышал бы не то, что видит.
     */
    fun selected(
        prosody: VoiceProsody,
        options: List<VoiceOption> = VoiceProsodyPolicy.options(),
    ): VoiceOption? {
        val id = prosody.voiceId ?: return options.firstOrNull {
            it.id == ServerTtsVoices.DEFAULT_VOICE.id
        }
        return options.firstOrNull { it.id == id }
    }

    /** Подпись выбранного голоса для шапки шторки; при `null` — [DEFAULT_VOICE_LABEL]. */
    fun voiceLabel(
        prosody: VoiceProsody,
        options: List<VoiceOption> = VoiceProsodyPolicy.options(),
    ): String = selected(prosody, options)?.label ?: DEFAULT_VOICE_LABEL

    /**
     * Следующая ступень темпа.
     *
     * На верхней ступени возвращает её же: «+» не перескакивает через пресеты и не
     * заворачивается в начало (заворот читался бы как поломка — темп вдруг падал бы).
     * Если сохранённый темп выше всех ступеней (например 2.0 из старой настройки), он
     * остаётся собой: понижать чужое значение молча нельзя, а «+» уже некуда.
     */
    fun stepUp(speed: Float): Float {
        val current = VoiceProsody.clampSpeed(speed)
        val next = VoiceProsody.STEP_CHOICES.firstOrNull { it > current + EPSILON }
        return VoiceProsody.clampSpeed(next ?: maxOf(current, VoiceProsody.STEP_CHOICES.last()))
    }

    /** Предыдущая ступень темпа; на нижней — она же. Симметрично [stepUp]. */
    fun stepDown(speed: Float): Float {
        val current = VoiceProsody.clampSpeed(speed)
        val previous = VoiceProsody.STEP_CHOICES.lastOrNull { it < current - EPSILON }
        return VoiceProsody.clampSpeed(previous ?: minOf(current, VoiceProsody.STEP_CHOICES.first()))
    }

    /**
     * Есть ли куда шагнуть: шторка гасит «−»/«+» на краях, а не показывает кнопку,
     * которая ничего не делает.
     */
    fun canStepUp(speed: Float): Boolean = stepUp(speed) > VoiceProsody.clampSpeed(speed) + EPSILON

    fun canStepDown(speed: Float): Boolean = stepDown(speed) < VoiceProsody.clampSpeed(speed) - EPSILON

    /**
     * Подпись темпа: «1.15× — быстрее обычного», «обычный темп».
     *
     * Число берётся из [VoiceProsody.speedLabel], чтобы шторка и шапка не показывали
     * «1.15×» и «1.2×» для одного и того же значения. Пояснение — по полосам, а не по
     * точным ступеням: смысл подписи в том, ЧТО человек услышит, а «1.3× — ровно ступень
     * номер пять» ему не говорит ничего.
     */
    fun speedHint(speed: Float): String {
        val safe = VoiceProsody.clampSpeed(speed)
        val label = VoiceProsody(speed = safe).speedLabel()
        val word = when {
            safe < SLOW_MARK -> "значительно медленнее обычного"
            safe < FAST_MARK_START -> "немного медленнее обычного"
            safe <= FAST_MARK_END -> return VoiceProsody.DEFAULT_SPEED_LABEL
            safe < VERY_FAST_MARK -> "быстрее обычного"
            else -> "значительно быстрее обычного"
        }
        return "$label — $word"
    }

    /**
     * Профиль сервера → строка списка.
     *
     * Подписи привязаны к СМЫСЛУ профиля (`natural` / локальный), а не к строке `voice`:
     * `ru-RU-SvetlanaNeural` человеку ничего не говорит, а «Светлана» — говорит. Два
     * сетевых голоса различаются по [ServerTtsVoiceProfile.id], а не по флагу `natural`:
     * флаг отвечает на вопрос «желанный ли это голос», а не «как его зовут».
     * Для неизвестного профиля подпись падает на имя голоса движка, а не на выдуманное
     * имя: показать не то имя лучше, чем показать несуществующее.
     */
    private fun option(profile: ServerTtsVoiceProfile): VoiceOption {
        val (label, hint) = when (profile.id) {
            // ОСНОВНОЙ голос приложения: подпись говорит и о провайдере, и о цене — человек
            // должен понимать, что этот голос платный и почему он звучит по умолчанию.
            ServerTtsVoices.PLUSVIBE_ALICE.id ->
                "Алиса (PlusVibe)" to "Основной живой голос: ElevenLabs turbo, платный (≈5 ₽/мин)"
            ServerTtsVoices.NATURAL_SVETLANA.id ->
                "Светлана" to "Женский нейросетевой голос, живая интонация"
            ServerTtsVoices.NATURAL_DMITRY.id ->
                "Дмитрий" to "Мужской нейросетевой голос, глубокий тембр"
            ServerTtsVoices.NATURAL_AVA.id ->
                "Ава (ChatGPT)" to "Тёплый мультиязычный нейро-голос (RU / EN)"
            // Второй платный голос: подпись говорит, за что платят и почему он дешевле.
            ServerTtsVoices.PLUSVIBE_ONYX.id ->
                "Оникс (PlusVibe)" to "Дешёвый облачный голос gpt-4o-mini-tts (≈0,06 ₽/мин)"
            // Русские голоса из библиотеки ElevenLabs. Подпись называет ЦЕНУ и РЕЗЕРВ:
            // голос платный, а канал библиотечных голосов на 18.11.2026 не отдавал аудио
            // (задача висела в `processing`), поэтому человек обязан заранее знать, что при
            // сбое фразу прочитает Дмитрий, а не «его голос вдруг пропал».
            ServerTtsVoices.PLUSVIBE_LARISA.id ->
                "Лариса (PlusVibe)" to "Русский голос ElevenLabs, платный (≈5 ₽/мин); при сбое — Дмитрий"
            ServerTtsVoices.PLUSVIBE_KATE.id ->
                "Катя (PlusVibe)" to "Русский голос ElevenLabs, платный (≈5 ₽/мин); при сбое — Дмитрий"
            ServerTtsVoices.LOCAL_IRINA.id ->
                "Ирина (офлайн)" to "Локальный голос на телефоне, работает без сети"
            // Неизвестный профиль: имя голоса движка честнее выдуманного имени.
            else -> profile.voice to ""
        }

        return VoiceOption(
            id = profile.id,
            label = label,
            hint = hint,
            prosody = VoiceProsody(voiceId = profile.id),
        )
    }

    /** Ниже — «значительно медленнее». */
    private const val SLOW_MARK: Float = 0.85f

    /** Отсюда и до [FAST_MARK_END] — обычный темп (погрешность float в хранилище). */
    private const val FAST_MARK_START: Float = 0.97f

    private const val FAST_MARK_END: Float = 1.03f

    /** Выше — «быстрее обычного», ещё выше — «значительно быстрее». */
    private const val VERY_FAST_MARK: Float = 1.25f
}