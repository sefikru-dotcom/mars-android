package ru.mars.mobile.asr

/**
 * Настройки потокового распознавателя.
 *
 * Значения по умолчанию подобраны под замеры на сервере (RTF 0.04-0.05, первый partial
 * 24-29 мс при `num_threads=2`) и под словарь Марса: имена собственные («Альпен», «Caddy»,
 * «Квартал») модель без подсказки слышит как «ады»/«кадыза», поэтому по умолчанию включены
 * hotwords.
 */
data class AsrConfig(
    /**
     * Слова-подсказки для контекстного биасинга.
     *
     * ПО УМОЛЧАНИЮ ПУСТО, и это не забывчивость. Hotwords в sherpa-onnx требуют
     * `modified_beam_search` + `modeling_unit=bpe` + текстовый BPE-словарь. У модели
     * `zipformer-small-ru-vosk-int8` рядом лежит `bpe.model` — БИНАРНЫЙ, а native ждёт
     * текстовый файл «токен пробел score» на каждой строке. При несовпадении формата
     * C++-инициализатор вызывает `SHERPA_ONNX_EXIT(-1)`: процесс умирает сигналом 6
     * (SIGABRT) без исключения, без tombstone от приложения. Проверено на POCO F5:
     * `attention_dims does not exist in the metadata` в логе и мгновенная смерть процесса.
     * Поэтому включать hotwords можно только вместе с подготовленным текстовым словарём.
     *
     * Изолированный spike (Python + sherpa-onnx 1.13.8, та же модель и тот же `bpe.model`,
     * без телефона) это подтвердил и добавил второй факт: с ПОДГОТОВЛЕННЫМ текстовым
     * словарём (500 токенов из `bpe.model`) процесс жив (`rc=0`), но текст не меняется —
     * «марш открой порт…» остаётся «марш…» и с подсказкой «марс», и с посторонней
     * «квартал». То есть совместимость формата доказана, а польза для качества — нет.
     * Вывод: hotwords остаются ВЫКЛЮЧЕНЫ (experimental, off by default) до отдельного
     * замера на устройстве; имя правит [AsrFinalCorrector] на финальном тексте.
     */
    val hotwords: List<String> = emptyList(),

    /** Вес подсказки: больше — сильнее тянет к hotword, но растёт риск «услышать» его в шуме. */
    val hotwordsScore: Float = 1.5f,

    /**
     * Контекстная коррекция финального текста: «марш» → «Марс» в позиции обращения.
     *
     * Включена по умолчанию, потому что hotwords на этой модели имя не исправляют (см.
     * [hotwords]), а обращение «марш, открой порт» ломает и понимание, и ответ. Правится
     * только вокатив — обычное слово «марш» (военный марш, марш бросок) не трогается;
     * правила и негативные случаи — в [AsrFinalCorrector].
     */
    val correctVocativeName: Boolean = true,

    /** Потоки ONNX Runtime. 2 — компромисс: замеренный RTF и нагрев телефона. */
    val numThreads: Int = 2,

    /** `auto` | `greedy_search` | `modified_beam_search`. `auto` — по наличию hotwords. */
    val decodingMethod: String = AUTO,

    /**
     * Правило 1 endpoint-детекции (тишина без речи): 2.4 с. Значения — дефолты sherpa-onnx,
     * вынесены сюда, чтобы диалог мог подстроить паузу под себя.
     */
    val endpointRule1MinTrailingSilence: Float = 2.4f,

    /** Правило 2 (тишина после речи): 1.4 с — основной триггер «человек замолчал». */
    val endpointRule2MinTrailingSilence: Float = 1.4f,

    /** Правило 3 (речь без пауз дольше N секунд): 20 с — страховка от бесконечной реплики. */
    val endpointRule3MinUtteranceLength: Float = 20.0f,
) {
    /** Копия без подсказок: откат, если распознаватель не поднялся с hotwords. */
    fun withoutHotwords(): AsrConfig = copy(hotwords = emptyList())

    companion object {
        const val AUTO = "auto"
        const val GREEDY_SEARCH = "greedy_search"
        const val MODIFIED_BEAM_SEARCH = "modified_beam_search"

        /**
         * Рабочий словарь Марса: имена проектов/сервисов, латиница, порты и код ошибки,
         * которые модель путает чаще всего.
         */
        val DEFAULT_HOTWORDS: List<String> = listOf(
            "марс", "стеф", "квартал", "альпен", "argo", "caddy", "порт", "8090", "502",
        )
    }
}
