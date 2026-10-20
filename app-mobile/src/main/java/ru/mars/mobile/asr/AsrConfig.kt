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
     * Слова-подсказки для контекстного биасинга. Пустой список — обычный `greedy_search`
     * (быстрее и точнее на числах). Непустой список переключает декодер на
     * `modified_beam_search`: только в этом режиме sherpa-onnx вообще читает hotwords
     * (см. комментарий в [SherpaAsrBackend]).
     */
    val hotwords: List<String> = DEFAULT_HOTWORDS,

    /** Вес подсказки: больше — сильнее тянет к hotword, но растёт риск «услышать» его в шуме. */
    val hotwordsScore: Float = 1.5f,

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
