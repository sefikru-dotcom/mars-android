package ru.mars.mobile.asr

/**
 * Контекстная коррекция финального транскрипта: «марш» → «Марс» ТОЛЬКО в позиции обращения.
 *
 * ## Зачем
 * Модель `zipformer-small-ru-vosk-int8` на имени «Марс» в начале фразы устойчиво отдаёт
 * «марш» («марш открой порт восемь тысяч девяносто»). Hotwords это НЕ лечат: изолированный
 * spike (Python, та же модель, тот же bpe.model) показал, что с текстовым BPE-словарём
 * распознавание не меняется, а с бинарным `bpe.model` native умирает на `SHERPA_ONNX_EXIT(-1)`
 * ещё до декодирования. Поэтому биасинг в Android не включён, а расхождение правится здесь —
 * на уже готовом тексте, где у нас есть контекст.
 *
 * ## Почему нельзя просто replace("марш", "Марс")
 * «марш» — обычное русское слово: военный марш, марш бросок, шагом марш, «марш отсюда».
 * Глобальная замена искажает нормальную речь, а искажённая речь уходит в LLM как факт.
 * Поэтому правится только **вокатив** — слово, которым человек обращается к Марсу:
 *
 * | Вход | Выход | Почему |
 * |---|---|---|
 * | `марш, открой порт` | `Марс, открой порт` | начало фразы + запятая обращения |
 * | `марш открой порт 8090` | `Марс открой порт 8090` | начало фразы, дальше не «маршевое» слово |
 * | `как дела, марш?` | `как дела, Марс?` | после запятой в конце фразы |
 * | `военный марш` | без изменений | определение перед словом — это существительное |
 * | `марш бросок` | без изменений | устойчивое сочетание |
 * | `шагом марш` | без изменений | командная формула |
 * | `марш отсюда` | без изменений | команда-побуждение, не обращение |
 * | `маршем по площади` | без изменений | другая словоформа, границы слова не совпадают |
 *
 * Другие имена собственные («Стеф», «Альпен», «Квартал») НЕ трогаются: для них нет
 * безопасного контекстного признака, а ложная замена имени дороже, чем ошибка распознавания.
 *
 * Класс чистый (без Android), поэтому проверяется JVM-тестами.
 */
object AsrFinalCorrector {

    /**
     * Результат коррекции.
     *
     * @param raw исходный транскрипт (сохраняется для диагностики, в логи не пишется);
     * @param text текст после коррекции — его и получает цикл диалога;
     * @param replacements сколько вхождений реально исправлено (0 — речь не тронута).
     */
    data class Correction(
        val raw: String,
        val text: String,
        val replacements: Int,
    ) {
        val changed: Boolean get() = replacements > 0
    }

    /** Слово «марш» целиком: границы по буквам, чтобы «маршем»/«марша» не совпадали. */
    private val WORD = Regex("""(?<![а-яё])марш(?![а-яё])""", RegexOption.IGNORE_CASE)

    /**
     * Слова перед «марш», которые делают его существительным (марш как жанр/строй).
     * Вокатив никогда не идёт после определения.
     */
    private val NOMINAL_PRECEDERS: Set<String> = setOf(
        "военный", "военного", "военном", "военные",
        "духовой", "духового", "духовом",
        "торжественный", "торжественного", "торжественном",
        "похоронный", "похоронного", "прощальный", "прощального",
        "парадный", "парадного", "парадном",
        "свадебный", "свадебного", "победный", "победного",
        "спортивный", "спортивного", "маршевый", "маршевого",
        "наш", "нашего", "этот", "этого", "тот", "его", "их",
    )

    /** Слова после «марш», образующие устойчивое сочетание (марш-бросок и т.п.). */
    private val NOMINAL_TAILS: Set<String> = setOf(
        "бросок", "броска", "броске", "броском", "броски",
        "победы", "славы", "протеста", "протест",
    )

    /** Слова после «марш», превращающие его в команду-побуждение («марш отсюда»). */
    private val COMMAND_TAILS: Set<String> = setOf(
        "отсюда", "домой", "спать", "вперёд", "вперед", "обратно", "вон", "на", "в", "к",
    )

    /** Чем заменяем: имя собственное, всегда с заглавной. */
    private const val NAME = "Марс"

    /** Само слово, которое ищем (в нижнем регистре) — для проверки повторов. */
    private const val WORD_VALUE = "марш"

    /**
     * Исправить финальный текст. Идемпотентно: повторный вызов на результате ничего не меняет.
     *
     * @param raw финальный транскрипт движка (может быть пустым).
     */
    fun correct(raw: String): Correction {
        if (raw.isEmpty() || !raw.contains("марш", ignoreCase = true)) {
            return Correction(raw, raw, 0)
        }

        val out = StringBuilder(raw.length + 2)
        var cursor = 0
        var replaced = 0

        for (match in WORD.findAll(raw)) {
            val before = raw.substring(0, match.range.first)
            val after = raw.substring(match.range.last + 1)
            if (!isVocative(before, after)) continue
            out.append(raw, cursor, match.range.first)
            out.append(NAME)
            cursor = match.range.last + 1
            replaced++
        }

        if (replaced == 0) return Correction(raw, raw, 0)
        out.append(raw, cursor, raw.length)
        return Correction(raw, out.toString(), replaced)
    }

    /**
     * Вокатив ли это: слово стоит в позиции обращения и рядом нет признаков обычного «марша».
     *
     * @param before текст до слова, @param after текст после слова.
     */
    private fun isVocative(before: String, after: String): Boolean {
        val initial = before.isBlank()
        val followedByComma = after.trimStart().startsWith(",")
        val precededByComma = before.trimEnd().endsWith(",")
        val prev = lastWord(before)
        val next = firstWord(after)

        if (prev in NOMINAL_PRECEDERS) return false
        if (next in NOMINAL_TAILS) return false
        if (next in COMMAND_TAILS) return false
        // Повтор («марш, марш!») — команда, а не обращение, даже если первое слово правилось бы.
        if (prev == WORD_VALUE || (followedByComma && next == WORD_VALUE)) return false

        return if (initial) {
            // Одиночное «марш»/«марш!» без продолжения — команда, а не обращение.
            followedByComma || next.isNotEmpty()
        } else {
            precededByComma
        }
    }

    /** Последнее слово перед позицией, в нижнем регистре, без пунктуации. */
    private fun lastWord(before: String): String {
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return ""
        val token = trimmed.substringAfterLast(' ')
        return token.trim { !it.isLetter() }.lowercase()
    }

    /** Первое слово после позиции, в нижнем регистре, без пунктуации. */
    private fun firstWord(after: String): String {
        val trimmed = after.trimStart().removePrefix(",").trimStart()
        if (trimmed.isEmpty()) return ""
        val token = trimmed.substringBefore(' ')
        return token.trim { !it.isLetter() }.lowercase()
    }
}
