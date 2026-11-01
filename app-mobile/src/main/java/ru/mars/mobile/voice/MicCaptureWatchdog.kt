package ru.mars.mobile.voice

/**
 * Сторож микрофона: «захват открыт, но звука нет».
 *
 * ## Зачем это нужно
 * `AudioRecord` умеется открываться «успешно» и при этом молчать: устройство занято другим
 * приложением, источник отвалился, поток чтения умер на первом же read, у микрофона выкручен
 * аппаратный мьют. Для вызывающего это неотличимо от «человек молчит»: ошибки нет, фаза
 * LISTENING, кнопка мигает — и так до бесконечности. Человек в этот момент говорит в мёртвый
 * микрофон и не понимает, почему Марс не отвечает. Молчание здесь хуже ошибки, поэтому:
 * один автоматический перезапуск (транзиентный сбой лечится), затем сдача с сообщением.
 *
 * ## Почему это чистый объект
 * Все переходы — арифметика на часах, которую надо проверять БЕЗ телефона: неправильный
 * переход даёт либо вечное listening, либо перезапуск живого захвата (и потерю начала фразы).
 * Класс ничего не знает про `AudioRecord`, потоки и Android: он получает время и события
 * (`onCaptureStarted` / `onAudioChunk` / `tick`) и отвечает решением.
 *
 * ## Как этим пользуется хост
 * 1. захват открыт → [onCaptureStarted];
 * 2. каждый прочитанный буфер (ПЕРВЫЙ же достаточно) → [onAudioChunk];
 * 3. раз в [TICK_INTERVAL_MS] → [tick]: [Decision.RESTART] — закрыть и открыть захват заново,
 *    затем [onRestartStarted]; [Decision.GIVE_UP] — остановить режим и сказать человеку.
 *
 * @param timeoutMs сколько ждать первого буфера; 3 с — выше паузы на инициализацию
 *   `AudioRecord` и ниже терпения человека.
 */
class MicCaptureWatchdog(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    /**
     * Состояние ожидания. `HEALTHY` и `FAILED` нужны, чтобы [tick] не повторял решение дважды.
     */
    enum class State {
        /** Захват не открыт либо сторож не задействован. */
        IDLE,

        /** Ждём первый буфер в пределах [timeoutMs]. */
        WAITING,

        /** Перезапуск объявлен, ждём первый буфер уже от нового захвата. */
        RESTARTED,

        /** Звук приходил: сторож больше ничего не решает до нового [onCaptureStarted]. */
        HEALTHY,

        /** Перезапуск не помог: режим надо остановить и сказать человеку. */
        FAILED,
    }

    /** Что хосту делать по результату [tick]. */
    enum class Decision {
        /** Пока ничего: либо звук уже идёт, либо время ещё не вышло. */
        WAIT,

        /** Первый буфер так и не пришёл — перезапустить захват (один раз). */
        RESTART,

        /** Перезапуск не помог — остановить голосовой режим и сообщить человеку. */
        GIVE_UP,
    }

    /**
     * Текущее состояние ожидания.
     *
     * `@Volatile` не для красоты: [onAudioChunk] зовётся с потока чтения микрофона, а [tick] —
     * с главного. Без видимости тик мог не увидеть «звук уже идёт» и перезапустить живой
     * захват, потеряв начало фразы.
     */
    @Volatile
    var state: State = State.IDLE
        private set

    /** Звук уже приходил: таймер хосту больше не нужен. */
    val healthy: Boolean get() = state == State.HEALTHY

    /** Сколько перезапусков уже израсходовано в текущей реплике (0 или 1). */
    @Volatile
    var restarts: Int = 0
        private set

    private var deadlineMs: Long = 0L

    /**
     * Новая реплика: захват только что открыт, счётчик перезапусков сбрасывается.
     *
     * Именно сброс на новом старте (а не на каждом [onRestartStarted]) даёт правило «один
     * перезапуск на реплику»: следующий тап человека — новый шанс, иначе после одного сбоя
     * микрофон отказывал бы до перезапуска приложения.
     */
    fun onCaptureStarted(nowMs: Long) {
        restarts = 0
        state = State.WAITING
        deadlineMs = nowMs + timeoutMs
    }

    /** Захват переоткрыт после [Decision.RESTART]: счётчик перезапусков сохраняется. */
    fun onRestartStarted(nowMs: Long) {
        state = State.RESTARTED
        deadlineMs = nowMs + timeoutMs
    }

    /**
     * Пришёл аудиочанк — захват живой.
     *
     * Источник правды именно сырой PCM, а не «начало речи»: человек может молчать, и это
     * нормальный режим, а буферы при этом обязаны идти.
     */
    fun onAudioChunk() {
        if (state == State.WAITING || state == State.RESTARTED) state = State.HEALTHY
    }

    /** Проверка часов. Идемпотентна: без нового [onCaptureStarted] второе срабатывание не выдаст. */
    fun tick(nowMs: Long): Decision {
        if (state != State.WAITING && state != State.RESTARTED) return Decision.WAIT
        if (nowMs < deadlineMs) return Decision.WAIT
        if (state == State.WAITING && restarts == 0) {
            restarts = 1
            // Ждём уже перезапущенный захват: следующий срок — его собственные 3 секунды.
            state = State.RESTARTED
            deadlineMs = nowMs + timeoutMs
            return Decision.RESTART
        }
        state = State.FAILED
        return Decision.GIVE_UP
    }

    /** Полный сброс (выход из голосового режима, остановка по сообщению). */
    fun reset() {
        state = State.IDLE
        restarts = 0
        deadlineMs = 0L
    }

    companion object {
        /** Три секунды: дольше человек уже говорит в пустоту, меньше — ловим ложные срабатывания. */
        const val DEFAULT_TIMEOUT_MS: Long = 3_000L

        /** Частота проверки: 250 мс — точность в четверть секунды и никакой нагрузки. */
        const val TICK_INTERVAL_MS: Long = 250L
    }
}
