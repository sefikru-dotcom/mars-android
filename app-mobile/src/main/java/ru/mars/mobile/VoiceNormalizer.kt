package ru.mars.mobile

/**
 * Приводит технические строки ответа к произносимой русской форме ПЕРЕД синтезом.
 *
 * Зачем отдельный шаг: серверный TTS читает `8090` как «восемь тысяч девяносто» только
 * случайно, а `1.7.6`, `45.129.2.36`, `77%` и `api/tts` — почти всегда неверно. Здесь
 * числа, версии, проценты, время и аббревиатуры превращаются в слова, а нечитаемое
 * (ссылки, пути, хэши, эмодзи, markdown) — в короткие замены или удаляется.
 *
 * Правила намеренно простые и детерминированные, без морфологического анализа:
 * - целые числа — словами; род берётся по следующему слову (окончание `-а`/`-я` —
 *   женский, `-о`/`-е` — средний, иначе мужской): `1 комната` → «одна комната»,
 *   `2 окна` → «два окна», `порт 8090` → «восемь тысяч девяносто»;
 * - падеж остаётся именительным: это озвучка, а не грамматически выверенный текст;
 * - версии и дроби — «X точка Y»: `1.7.6` → «один точка семь точка шесть»;
 * - IPv4 — короткая замена «ай-пи адрес» (по цифрам адрес всё равно не разобрать на слух);
 * - проценты — число + «процент/процента/процентов» по правилу множественного числа;
 * - время `ЧЧ:ММ` — «семь часов ровно» / «семь часов тридцать минут»;
 * - аббревиатуры — из словаря [DEFAULT_ABBREVIATIONS] (HTTP, SSH, API, URL, CPU, RAM,
 *   GB/МБ/ГБ, IP, VPN, APK, TTS, STT и соседние);
 * - ссылки и пути — «ссылка» и «путь», хэши пропускаются, эмодзи и markdown-символы
 *   удаляются.
 *
 * Класс чистый JVM и без состояния: один экземпляр можно держать на всё приложение.
 *
 * @param abbreviations словарь произношения аббревиатур (ключ — как в тексте, значение —
 *   как произносить); по умолчанию [DEFAULT_ABBREVIATIONS].
 */
class VoiceNormalizer(
    private val abbreviations: Map<String, String> = DEFAULT_ABBREVIATIONS,
) {

    /** Род числительного: влияет только на 1 и 2 («один/одна/одно», «два/две»). */
    private enum class Gender { MASCULINE, FEMININE, NEUTER }

    /** Привести [text] к виду, который синтез прочитает по-русски. */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        var out = text
        out = stripCodeFences(out)
        out = IMAGE.replace(out, " ")
        out = MD_LINK.replace(out, " $1 ")
        out = URL.replace(out, " ссылка ")
        out = WINDOWS_PATH.replace(out, " путь ")
        out = UNIX_PATH.replace(out, " путь ")
        out = HASH.replace(out, " ")
        out = EMOJI.replace(out, " ")
        out = HTML_TAG.replace(out, " ")
        out = IPV4.replace(out, " ай-пи адрес ")
        out = VERSION.replace(out) { match -> versionToWords(match.value) }
        out = DECIMAL.replace(out) { match -> decimalToWords(match.value) }
        out = PERCENT.replace(out) { match -> percentToWords(match.value) }
        out = TIME.replace(out) { match -> timeToWords(match.value) }
        out = replaceAbbreviations(out)
        out = replaceNumbers(out)
        out = MARKDOWN_SYMBOLS.replace(out, " ")
        return cleanup(out)
    }

    // --- подготовка ----------------------------------------------------------------------------

    /** Блоки кода из озвучки исключаются целиком (и вместе с закрывающим ```). */
    private fun stripCodeFences(text: String): String =
        CODE_FENCE.replace(text, " ")

    /** Аббревиатуры по словарю; границы слова — буквы и цифры любого алфавита. */
    private fun replaceAbbreviations(text: String): String {
        if (abbreviations.isEmpty()) return text
        val keys = abbreviations.keys.sortedByDescending { it.length }.joinToString("|") {
            Regex.escape(it)
        }
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}])($keys)(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )
        return pattern.replace(text) { match ->
            abbreviations[match.value] ?: abbreviations[match.value.uppercase()] ?: match.value
        }
    }

    /**
     * Целые числа словами. Род определяется по следующему слову: в русской речи именно оно
     * задаёт «один/одна» и «два/две».
     */
    private fun replaceNumbers(text: String): String = NUMBER.replace(text) { match ->
        val raw = match.value
        val value = raw.toLongOrNull() ?: return@replace raw
        if (value > MAX_SPOKEN_NUMBER) return@replace raw
        // Число с ведущими нулями (код, артикул) читается по цифрам: 007 → «ноль ноль семь».
        if (raw.length > 1 && raw.startsWith("0")) {
            return@replace raw.map { digitWord(it, Gender.MASCULINE) }.joinToString(" ")
        }
        val gender = genderOf(nextWordAfter(text, match.range.last + 1))
        numberToWords(value, gender)
    }

    /** Слово сразу после позиции [from] — нужно только для выбора рода. */
    private fun nextWordAfter(text: String, from: Int): String {
        var index = from
        while (index < text.length && (text[index].isWhitespace() || text[index] == '-')) index++
        val start = index
        while (index < text.length && !text[index].isWhitespace()) index++
        return text.substring(start, index)
    }

    /** Род по окончанию следующего слова — простое правило без словаря. */
    private fun genderOf(word: String): Gender {
        val clean = word.trim { it.isWhitespace() || it in PUNCTUATION_TRAIL }.lowercase()
        if (clean.isEmpty()) return Gender.MASCULINE
        // Родительный падеж среднего рода тоже кончается на -а/-я: «два окна», «два письма».
        if (clean in NEUTER_GENITIVE) return Gender.NEUTER
        return when {
            clean.endsWith("а") || clean.endsWith("я") -> Gender.FEMININE
            // Множественное число и родительный падеж женского рода: «две комнаты».
            clean.endsWith("ы") || clean.endsWith("и") -> Gender.FEMININE
            clean.endsWith("о") || clean.endsWith("е") || clean.endsWith("ё") -> Gender.NEUTER
            else -> Gender.MASCULINE
        }
    }

    // --- числа словами -------------------------------------------------------------------------

    /** [value] словами; [gender] влияет на 1 и 2 (и на «тысячу», которая всегда женская). */
    private fun numberToWords(value: Long, gender: Gender): String {
        if (value == 0L) return "ноль"
        if (value < 0) return "минус " + numberToWords(-value, gender)
        val parts = ArrayList<String>()
        val millions = value / 1_000_000
        val thousands = (value / 1_000) % 1_000
        val rest = (value % 1_000).toInt()
        if (millions > 0) {
            parts += triad(millions.toInt(), Gender.MASCULINE) +
                " " + plural(millions, "миллион", "миллиона", "миллионов")
        }
        if (thousands > 0) {
            parts += triad(thousands.toInt(), Gender.FEMININE) +
                " " + plural(thousands, "тысяча", "тысячи", "тысяч")
        }
        if (rest > 0) parts += triad(rest, gender)
        return parts.joinToString(" ")
    }

    /** Триада 1..999 (сотни, десятки, единицы) с учётом рода. */
    private fun triad(value: Int, gender: Gender): String {
        val parts = ArrayList<String>()
        val hundreds = value / 100
        if (hundreds > 0) parts += HUNDREDS[hundreds]
        val remainder = value % 100
        if (remainder in 10..19) {
            parts += TEENS[remainder - 10]
        } else {
            val tens = remainder / 10
            if (tens > 0) parts += TENS[tens]
            val unit = remainder % 10
            if (unit > 0) parts += unitWord(unit, gender)
        }
        return parts.joinToString(" ")
    }

    private fun unitWord(unit: Int, gender: Gender): String = when (unit) {
        1 -> when (gender) {
            Gender.FEMININE -> "одна"
            Gender.NEUTER -> "одно"
            Gender.MASCULINE -> "один"
        }
        2 -> if (gender == Gender.FEMININE) "две" else "два"
        else -> UNITS[unit]
    }

    /** Слово для одной цифры — версии и коды читаются по цифрам. */
    private fun digitWord(digit: Char, gender: Gender): String {
        val index = digit - '0'
        if (index !in 0..9) return digit.toString()
        if (index == 1 || index == 2) return unitWord(index, gender)
        return UNITS[index]
    }

    /** Форма слова по числу: 1 процент, 2 процента, 5 процентов. */
    private fun plural(value: Long, one: String, few: String, many: String): String {
        val mod100 = (value % 100).toInt()
        if (mod100 in 11..14) return many
        return when (value % 10) {
            1L -> one
            2L, 3L, 4L -> few
            else -> many
        }
    }

    // --- версии, проценты, время ---------------------------------------------------------------

    /** `1.7.6` → «один точка семь точка шесть». */
    private fun versionToWords(raw: String): String =
        raw.split('.').joinToString(" точка ") { part ->
            val number = part.toLongOrNull() ?: return@joinToString part
            if (part.length == 1) digitWord(part[0], Gender.MASCULINE)
            else numberToWords(number, Gender.MASCULINE)
        }

    /** `3.14` → «три точка четырнадцать» (дробная часть читается как целое). */
    private fun decimalToWords(raw: String): String {
        val separator = raw.indexOfFirst { it == '.' || it == ',' }
        if (separator <= 0) return raw
        val whole = raw.substring(0, separator).toLongOrNull() ?: return raw
        val fraction = raw.substring(separator + 1)
        val fractionWords = fraction.map { digitWord(it, Gender.MASCULINE) }.joinToString(" ")
        return numberToWords(whole, Gender.MASCULINE) + " точка " + fractionWords
    }

    /** `77%` → «семьдесят семь процентов». */
    private fun percentToWords(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val value = digits.toLongOrNull() ?: return raw
        return numberToWords(value, Gender.MASCULINE) + " " +
            plural(value, "процент", "процента", "процентов")
    }

    /** `07:00` → «семь часов ровно», `07:30` → «семь часов тридцать минут». */
    private fun timeToWords(raw: String): String {
        val parts = raw.split(':')
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: return raw
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: return raw
        val hourWords = numberToWords(hours.toLong(), Gender.MASCULINE) + " " +
            plural(hours.toLong(), "час", "часа", "часов")
        if (minutes == 0) return "$hourWords ровно"
        val minuteWords = numberToWords(minutes.toLong(), Gender.FEMININE) + " " +
            plural(minutes.toLong(), "минута", "минуты", "минут")
        return "$hourWords $minuteWords"
    }

    // --- финальная чистка ----------------------------------------------------------------------

    /** Пробелы и пунктуация после замен: без « ,» и двойных запятых. */
    private fun cleanup(text: String): String {
        var out = text.replace(WHITESPACE, " ")
        out = out.replace(Regex("""\s+([,.;:!?…)])"""), "$1")
        out = out.replace(Regex("""\(\s+"""), "(")
        out = out.replace(Regex("""\s+\)"""), ")")
        out = out.replace(Regex("""([,;:])\s*(?=[,;:])"""), "$1")
        out = out.replace(Regex("""([,;:])(?=[^\s,;:])"""), "$1 ")
        out = out.replace(Regex("""\s+"""), " ")
        return out.trim()
    }

    companion object {
        /** Выше этого числа словами не читаем: длинные идентификаторы бессмысленны на слух. */
        private const val MAX_SPOKEN_NUMBER: Long = 999_999_999_999L

        private val PUNCTUATION_TRAIL: Set<Char> =
            setOf(',', '.', ';', ':', '!', '?', ')', '(', '»', '«', '"', '…')

        /** Средний род в родительном падеже кончается на -а/-я — не путать с женским. */
        private val NEUTER_GENITIVE: Set<String> = setOf(
            "окна", "яблока", "письма", "места", "слова", "кресла", "числа", "правила",
            "задания", "решения", "значения", "времени", "имени",
        )

        private val UNITS: Array<String> = arrayOf(
            "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        )

        private val TEENS: Array<String> = arrayOf(
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
        )

        /** Индекс — десятки: TENS[2] = «двадцать». */
        private val TENS: Array<String> = arrayOf(
            "", "", "двадцать", "тридцать", "сорок", "пятьдесят",
            "шестьдесят", "семьдесят", "восемьдесят", "девяносто",
        )

        /** Индекс — сотни: HUNDREDS[5] = «пятьсот». */
        private val HUNDREDS: Array<String> = arrayOf(
            "", "сто", "двести", "триста", "четыреста",
            "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот",
        )

        /** Словарь произношения аббревиатур: как писать в тексте — как читать вслух. */
        val DEFAULT_ABBREVIATIONS: Map<String, String> = mapOf(
            "HTTPS" to "эйч-ти-ти-пи-эс",
            "HTTP" to "эйч-ти-ти-пи",
            "SSH" to "эс-эс-эйч",
            "API" to "эй-пи-ай",
            "URL" to "ю-ар-эл",
            "URI" to "ю-ар-ай",
            "CPU" to "си-пи-ю",
            "GPU" to "джи-пи-ю",
            "RAM" to "рам",
            "ROM" to "ром",
            "IP" to "ай-пи",
            "VPN" to "ви-пи-эн",
            "DNS" to "дэ-эн-эс",
            "APK" to "эй-пи-кей",
            "TTS" to "ти-ти-эс",
            "STT" to "эс-ти-ти",
            "LLM" to "эл-эл-эм",
            "SQL" to "эс-кью-эл",
            "JSON" to "джейсон",
            "SSD" to "эс-эс-ди",
            "HDD" to "эйч-ди-ди",
            "GB" to "гигабайт",
            "MB" to "мегабайт",
            "KB" to "килобайт",
            "TB" to "терабайт",
            "ГБ" to "гигабайт",
            "МБ" to "мегабайт",
            "КБ" to "килобайт",
            "ТБ" to "терабайт",
            "МГц" to "мегагерц",
            "ГГц" to "гигагерц",
            "ОЗУ" to "озу",
        )

        private val CODE_FENCE = Regex("""```[\s\S]*?```""")
        private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
        private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
        private val URL = Regex("""(?:https?://|www\.)\S+""")
        private val WINDOWS_PATH = Regex("""\b[A-Za-z]:\\[^\s]+""")
        private val UNIX_PATH = Regex("""(?<![\p{L}\p{N}])(?:~|\.{1,2})?/[\w.\-][\w./\-]*""")
        private val HASH = Regex(
            """\b(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{8,64}\b""",
            RegexOption.IGNORE_CASE,
        )
        private val EMOJI = Regex("""[\p{So}\p{Sk}\uFE0F\u200D\u20E3\u2190-\u21FF\u2B00-\u2BFF]""")
        private val HTML_TAG = Regex("""<[^<>]{1,120}>""")
        private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
        private val VERSION = Regex("""\b\d+(?:\.\d+){2,}\b""")
        private val DECIMAL = Regex("""\b\d+[.,]\d+\b""")
        private val PERCENT = Regex("""\b(\d+)\s*%""")
        private val TIME = Regex("""\b(?:[01]?\d|2[0-3]):[0-5]\d\b""")
        private val NUMBER = Regex("""\b\d+\b""")
        private val MARKDOWN_SYMBOLS = Regex("""[*_`#>|~\\]+""")
        private val WHITESPACE = Regex("""\s+""")
    }
}
