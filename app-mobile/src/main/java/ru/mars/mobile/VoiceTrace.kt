package ru.mars.mobile

import android.util.Log

/**
 * Правда о голосе — в лог через `Log.i`.
 *
 * Почему не `Log.d`: на MIUI в release D-строки приложения в logcat не доходят вообще
 * (проверено на устройстве: ноль D-строк при живом запросе). Диагностика, которую нельзя
 * прочитать с телефона, бесполезна — поэтому здесь только `Log.i`.
 *
 * Почему отдельный объект, а не `Log.i` по месту: логика проверяется JVM-тестами, где
 * `android.util.Log` — заглушка. Тест подменяет [sink] и читает ровно те строки, что человек
 * видит в `adb logcat -s MarsVoice`, поэтому формат и «ничего лишнего» проверяются без сборки
 * и без устройства. Приём тот же, что в [MarsModelLog].
 *
 * Секретов и текста реплики здесь нет и быть не может: в строку попадают только id профиля,
 * канал, код причины и время — всё остальное отсекает [field].
 */
internal object VoiceTrace {

    const val TAG: String = "MarsVoice"

    /** Куда писать; `null` — в logcat. Ставится тестом, в приложении всегда `null`. */
    var sink: ((String) -> Unit)? = null

    /** Одна строка на реплику: что хотели, что прозвучало, почему не то. */
    fun report(report: VoiceReport) {
        emit(line(report))
    }

    /** Готова ли цепочка к речи: есть ли профиль PlusVibe, ключ, модель Piper. */
    fun readiness(paidReady: Boolean, piperReady: Boolean, serverReady: Boolean) {
        emit(readinessLine(paidReady, piperReady, serverReady))
    }

    /**
     * Формат строки реплики. Чистая функция без Android — чтобы формат проверялся обычным
     * JUnit-тестом и не ломался незаметно при правках.
     *
     * `at=` стоит последним, поэтому префикс строки читается глазами как в контракте,
     * а машине время всё равно доступно.
     */
    internal fun line(report: VoiceReport): String = buildString {
        append("voice wanted=")
        append(field(report.wantedProfileId))
        append(" actual=")
        append(report.actualChannel.name)
        append("(profile=")
        append(field(report.actualProfileId))
        append(") reason=")
        append(field(report.reason))
        append(" substituted=")
        append(report.substituted)
        append(" at=")
        append(report.atMs)
    }

    /** Формат строки готовности: одна строка на вход в разговор, до первой реплики. */
    internal fun readinessLine(paidReady: Boolean, piperReady: Boolean, serverReady: Boolean): String =
        "voice ready paid=$paidReady piper=$piperReady server=$serverReady"

    private fun emit(message: String) {
        val hook = sink
        if (hook != null) {
            hook(message)
            return
        }
        Log.i(TAG, message)
    }

    /**
     * Приводит значение поля к тому, что безопасно печатать.
     *
     * Поля приходят из разных слоёв, и туда по ошибке может попасть текст реплики или заголовок
     * с ключом. Писать это в logcat нельзя: лог читают люди и снимают скриншоты. Поэтому
     * разрешён только алфавит идентификаторов (`plusvibe-alice`, `no_api_key`), всё остальное
     * обрезается, а похожее на секрет помечается `redacted` — так по логу видно, что поле
     * пришло грязным, но содержимое не утекает. Переводы строк невозможны по построению:
     * иначе одна реплика давала бы несколько строк и ломала разбор `adb logcat`.
     */
    private fun field(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return NONE
        if (SECRET_HINT.containsMatchIn(value)) return "redacted"
        val head = value.takeWhile { it.isIdChar() }
        if (head.isEmpty()) return "unprintable"
        val safe = head.take(MAX_FIELD)
        return if (safe.length < value.length) "$safe~" else safe
    }

    private fun Char.isIdChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in "._:-"

    private const val NONE = "none"

    /** Длиннее в лог не нужно: id профилей и коды причин короткие, а хвост — это уже мусор. */
    private const val MAX_FIELD = 48

    /**
     * Ловим не слово «ключ», а присвоение ключа: `apiKey=…`, `token=…`, `Bearer …`, `sk-…`.
     * Иначе под нож попали бы штатные коды причин (`no_api_key`) — их как раз надо видеть
     * в логе, они и есть диагноз.
     */
    private val SECRET_HINT = Regex("bearer|api[_]?key\\s*=|secret\\s*=|token\\s*=|sk-", RegexOption.IGNORE_CASE)
}
