package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Орб слышит собственный голос Марса: политика выбора источника уровня.
 *
 * ## Что здесь доказывается и почему именно так
 * Живой `Visualizer` на JVM не поднять (эффект привязан к аудио-сервису Android), а контроллер
 * целиком не собрать (`Handler` главного `Looper`, `Activity`). Поэтому проверяются две вещи
 * по отдельности:
 * 1. **чистая политика** [SpeechLevelRouter] — фейковый источник отвечает `true`/`false`/бросает, а
 *    тест смотрит, чей уровень ушёл в орб и считается ли при этом синтетическая огибающая;
 * 2. **арифметика** [SpeechLevelMath] — центр 128 у 8-битной огибающей, усиление (тихая речь
 *    0.2–0.4, громкая 0.8+) и зажим битых значений.
 * Проводка (кто и когда зовёт `stop`) — контрактом по исходнику, тем же приёмом, что в
 * [BargeInMicLifecycleTest]: поднять контроллер на JVM нельзя, а «забыли отпустить эффект» — это
 * ровно тот класс ошибок, который ловится чтением, а не запуском.
 *
 * ## Чего эти тесты НЕ доказывают
 * Что `Visualizer` на POCO вообще выдаёт данные для сессии ExoPlayer/Piper: это проверяется только
 * приёмкой на устройстве. Тесты доказывают обратное и главное — что при любом отказе эффекта орб
 * не остаётся мёртвым и что битое значение не ломает кадр.
 */
class SpeechLevelTapTest {

    /** Источник-фейк: отвечает как настоящий и запоминает жизненный цикл. */
    private class FakeSource(
        private val available: Boolean = true,
        private val throwsOnStart: Boolean = false,
    ) : SpeechLevelSource {

        var starts: Int = 0
            private set
        var stops: Int = 0
            private set
        var lastSession: Int? = null
            private set

        private var listener: ((Float) -> Unit)? = null

        override fun start(sessionId: Int, onLevel: (Float) -> Unit): Boolean {
            starts++
            lastSession = sessionId
            if (throwsOnStart) throw RuntimeException("effect is already in use")
            if (!available) return false
            listener = onLevel
            return true
        }

        override fun stop() {
            stops++
            listener = null
        }

        /** «Платформа» отдала очередной кадр громкости. */
        fun emit(level: Float) {
            listener?.invoke(level)
        }
    }

    /** Часы политики: тест двигает их руками, чтобы проверить шаг повторов источника. */
    private var clock: Long = 1_000L

    private fun router(
        source: SpeechLevelSource?,
        session: Int? = SESSION,
        onLevel: (Float) -> Unit = {},
    ): SpeechLevelRouter = SpeechLevelRouter(
        source = source,
        sessionId = { session },
        onLevel = onLevel,
        nowMs = { clock },
    )

    // --- выбор источника ----------------------------------------------------------------------

    @Test
    fun `источник ответил true — уровень идёт из источника, огибающая не считается`() {
        val published = mutableListOf<Float>()
        val source = FakeSource(available = true)
        val levels = router(source, onLevel = { published += it })

        assertTrue("источник жив — резерв не нужен", levels.begin())
        assertTrue(levels.fromSource)
        assertEquals("сессия играющего аудио доехала до источника", SESSION, source.lastSession)

        // Главное: синтетика не просто не публикуется — она НЕ СЧИТАЕТСЯ.
        var envelopeFrames = 0
        repeat(5) {
            assertNull(
                "при живом источнике огибающая не должна считаться",
                levels.envelopeLevel {
                    envelopeFrames++
                    0.5f
                },
            )
        }
        assertEquals("ни одного кадра синтетики", 0, envelopeFrames)

        source.emit(0.63f)
        assertEquals(listOf(0.63f), published)
    }

    @Test
    fun `источник ответил false — включается резервная огибающая и кадр не залипает`() {
        // Эффект занят другим приложением / сессии ещё нет / нет разрешения — штатный отказ.
        val source = FakeSource(available = false)
        val levels = router(source)

        assertFalse("источника нет — остаёмся на резерве", levels.begin())
        assertFalse(levels.fromSource)

        // Кадр считается КАЖДЫЙ тик: иначе орб замёрз бы на первом значении.
        val frames = (1..5).map { levels.envelopeLevel { 0.4f } }
        assertEquals(List(5) { 0.4f }, frames)
        frames.forEach { value ->
            assertTrue("кадр огибающей обязан быть в 0..1: $value", value != null && value in 0f..1f)
        }
    }

    @Test
    fun `отказ источника не залипает — следующий старт речи пробует снова`() {
        // Эффект мог освободиться (другое приложение закрылось): «однажды не вышло» не приговор.
        val source = FakeSource(available = false)
        val levels = router(source)

        assertFalse(levels.begin())
        levels.stop()
        assertFalse(levels.begin())
        assertEquals("каждый старт речи — новая попытка", 2, source.starts)
    }

    @Test
    fun `без сессии источник не запускается вовсе`() {
        val source = FakeSource(available = true)
        val levels = router(source, session = null)

        assertFalse("мерить нечего", levels.begin())
        assertEquals("привязывать эффект к нулевой сессии нельзя", 0, source.starts)
        // Резерв обязан работать: иначе орб стоял бы неподвижно всю реплику.
        assertEquals(0.4f, levels.envelopeLevel { 0.4f })
    }

    @Test
    fun `исключение источника не роняет кадр`() {
        val levels = router(FakeSource(throwsOnStart = true))

        assertFalse("RuntimeException эффекта — это отказ, а не падение", levels.begin())
        assertEquals(0.4f, levels.envelopeLevel { 0.4f })
    }

    @Test
    fun `без источника поведение прежнее — огибающая`() {
        // Дефолт конструктора контроллера (`levelSource = null`) не должен ничего менять.
        val levels = router(source = null)

        assertFalse(levels.begin())
        assertEquals(0.4f, levels.envelopeLevel { 0.4f })
    }

    // --- опоздавшая сессия: почему одной попытки мало -----------------------------------------

    @Test
    fun `опоздавшая сессия подхватывается повтором посреди реплики`() {
        // Реальный порядок на телефоне: SPEAKING включается на ПРИНЯТОМ синтезе, то есть пока
        // ExoPlayer ещё пишет файл и сессии у платформы нет. Без повтора реальная громкость не
        // появилась бы НИКОГДА — вернулся бы ровно тот дефект, который волна закрывает.
        var session: Int? = null
        val source = FakeSource(available = true)
        val levels = SpeechLevelRouter(
            source = source,
            sessionId = { session },
            onLevel = {},
            nowMs = { clock },
        )

        assertFalse("сессии ещё нет", levels.begin())
        assertEquals("эффект не создавали зря", 0, source.starts)

        clock += 100
        assertFalse("шаг повтора ещё не прошёл", levels.retry())
        assertEquals(0, source.starts)

        clock += 200
        session = SESSION
        assertTrue("плеер заиграл — источник подхвачен", levels.retry())
        assertEquals(SESSION, source.lastSession)
        assertTrue(levels.fromSource)
        assertNull("синтетика выключается сразу", levels.envelopeLevel { 0.5f })
    }

    @Test
    fun `повторы ограничены и не дёргают эффект бесконечно`() {
        // Создание и освобождение Visualizer — обращение к аудио-сервису, а не бесплатная операция.
        val source = FakeSource(available = false)
        val levels = router(source)

        assertFalse(levels.begin())
        repeat(100) {
            clock += 300
            levels.retry()
        }

        assertEquals("первая попытка + 12 повторов, и всё", 13, source.starts)
    }

    @Test
    fun `stop запрещает повторы до следующей речи`() {
        val source = FakeSource(available = false)
        val levels = router(source)

        assertFalse(levels.begin())
        levels.stop()
        repeat(20) {
            clock += 300
            levels.retry()
        }
        assertEquals("после конца речи источник не трогаем", 1, source.starts)

        // Новая реплика — новый бюджет попыток.
        assertFalse(levels.begin())
        assertEquals(2, source.starts)
    }

    // --- жизненный цикл -----------------------------------------------------------------------

    @Test
    fun `уход из речи отпускает источник`() {
        val source = FakeSource(available = true)
        val levels = router(source)

        assertTrue(levels.begin())
        levels.stop()

        assertEquals("Visualizer обязан умереть вместе с речью", 1, source.stops)
        assertFalse(levels.fromSource)
        // И после остановки снова доступен резерв — речь не «сломалась».
        assertEquals(0.4f, levels.envelopeLevel { 0.4f })
    }

    @Test
    fun `повторный stop не дёргает аудио-сервис`() {
        // stopSpeechEnvelope зовётся из многих путей (стоп, уход экрана, release) — лишние
        // обращения к эффекту там не нужны.
        val source = FakeSource(available = true)
        val levels = router(source)

        levels.stop()
        assertEquals(0, source.stops)

        levels.begin()
        levels.stop()
        levels.stop()
        assertEquals(1, source.stops)
    }

    // --- битый уровень ------------------------------------------------------------------------

    @Test
    fun `битый уровень зажимается в 0-1 и не ломает кадр`() {
        val published = mutableListOf<Float>()
        val source = FakeSource(available = true)
        val levels = router(source, onLevel = { published += it })
        levels.begin()

        listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            2f,
            -3f,
            1.5f,
            0.42f,
        ).forEach { source.emit(it) }

        assertEquals(7, published.size)
        published.forEach { value ->
            assertFalse("NaN не имеет права дойти до кадра", value.isNaN())
            assertTrue("уровень вне 0..1: $value", value in 0f..1f)
        }
        // Нормальное значение не искажается зажимом.
        assertEquals(0.42f, published.last(), 0.0001f)
        assertEquals(0f, published[0], 0f)
        assertEquals(1f, published[1], 0f)
        assertEquals(0f, published[2], 0f)
    }

    @Test
    fun `зажим уровня не пускает NaN даже в границах`() {
        // `Float.coerceIn` NaN не зажимает: сравнения с NaN всегда ложны, значение проходит
        // насквозь и доезжает до кадра орба. Поэтому санитайзер отдельный и явный.
        assertEquals(0f, SpeechLevelMath.sanitize(Float.NaN), 0f)
        assertEquals(1f, SpeechLevelMath.sanitize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, SpeechLevelMath.sanitize(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1f, SpeechLevelMath.sanitize(7f), 0f)
        assertEquals(0f, SpeechLevelMath.sanitize(-0.5f), 0f)
        assertEquals(0.37f, SpeechLevelMath.sanitize(0.37f), 0.0001f)
    }

    // --- арифметика 8-битной огибающей ---------------------------------------------------------

    @Test
    fun `тишина это 128, а не ноль`() {
        // Воспринять байты как «есть» значило бы получить уровень 1.0 в полной тишине.
        assertEquals(0f, SpeechLevelMath.rmsOfWaveform(ByteArray(256) { 128.toByte() }), 0f)
    }

    @Test
    fun `RMS считается по отклонению от центра`() {
        // Постоянное отклонение 64 → ровно половина шкалы.
        assertEquals(0.5f, SpeechLevelMath.rmsOfWaveform(ByteArray(64) { 192.toByte() }), 0.001f)
        // Полный размах 0/255 → почти единица (127 и 128 в среднем квадратичном).
        val full = ByteArray(64) { index -> if (index % 2 == 0) 0.toByte() else 255.toByte() }
        assertEquals(0.996f, SpeechLevelMath.rmsOfWaveform(full), 0.01f)
        // Неиспользованный хвост буфера не участвует: длина уважается.
        val buffer = ByteArray(64) { 192.toByte() }.also { it[63] = 0.toByte() }
        assertEquals(0.5f, SpeechLevelMath.rmsOfWaveform(buffer, length = 32), 0.001f)
    }

    @Test
    fun `усиление даёт тихую речь 0_2-0_4 и громкую 0_8+`() {
        // Пороги из контракта: иначе орб либо не реагирует на тихое слово, либо всегда в потолке.
        val quiet = SpeechLevelMath.levelOf(0.05f)
        val quietHigher = SpeechLevelMath.levelOf(0.08f)
        val loud = SpeechLevelMath.levelOf(0.18f)

        assertTrue("тихая речь: $quiet", quiet in 0.2f..0.4f)
        assertTrue("тихая речь чуть громче: $quietHigher", quietHigher in 0.2f..0.4f)
        assertTrue("громкая речь: $loud", loud >= 0.8f)
        assertEquals("тишина — ноль", 0f, SpeechLevelMath.levelOf(0f), 0f)
        assertEquals("потолок — единица", 1f, SpeechLevelMath.levelOf(1f), 0f)
    }

    // --- проводка: контракт по исходнику ------------------------------------------------------

    @Test
    fun `контроллер принимает источник и провайдер сессии необязательными`() {
        val src = source(CONTROLLER)
        assertTrue(
            "дефолт null — существующие тесты и фейки не ломаются",
            src.contains("private val levelSource: SpeechLevelSource? = null"),
        )
        assertTrue(
            "сессия приходит лямбдой: она меняется от фразы к фразе",
            src.contains("private val audioSessionProvider: (() -> Int?)? = null"),
        )
        assertTrue("решение вынесено в чистую политику", src.contains("private val speechLevels = SpeechLevelRouter("))
    }

    @Test
    fun `на старте речи сначала пробуется источник`() {
        val start = body(source(CONTROLLER), "private fun startSpeechLevel(")
        assertTrue("источник первым", start.contains("speechLevels.begin()"))
        assertTrue("резерв — только при отказе", start.contains("startSpeechEnvelope()"))
        assertTrue(
            "при живом источнике тик огибающей снимается",
            start.contains("main.removeCallbacks(speechEnvelope)"),
        )
        assertFalse(
            "stopSpeechEnvelope погасил бы только что включённый источник",
            start.contains("stopSpeechEnvelope()"),
        )
    }

    @Test
    fun `старт речи зовёт политику, а кадр огибающей её спрашивает`() {
        assertTrue(
            "фаза SPEAKING — единственная точка старта уровня",
            body(source(CONTROLLER), "private fun onTtsSpeakingChanged(").contains("startSpeechLevel()"),
        )
        val envelope = body(source(CONTROLLER), "private val speechEnvelope")
        assertTrue(
            "кадр огибающей не считается при живом источнике",
            envelope.contains("speechLevels.envelopeLevel"),
        )
        assertTrue(
            "опоздавшая сессия подхватывается повтором, пока тикает резерв",
            envelope.contains("speechLevels.retry()"),
        )
        assertTrue(
            "политика живёт на монотонных часах контроллера",
            source(CONTROLLER).contains("nowMs = { watchdogClock() }"),
        )
    }

    @Test
    fun `уход из SPEAKING и release отпускают источник`() {
        val src = source(CONTROLLER)
        assertTrue(
            "смена фазы — самый надёжный признак конца речи",
            body(src, "private fun emit(").contains("stopSpeechEnvelope()"),
        )
        assertTrue(
            "стоп, уход экрана и release идут через одну точку",
            body(src, "private fun stopSpeechEnvelope(").contains("speechLevels.stop()"),
        )
        assertTrue(
            "освобождение контроллера обязано отпустить эффект",
            body(src, "fun release()").contains("stopSpeechEnvelope()"),
        )
        assertTrue(
            "стоп человека тоже",
            body(src, "private fun silenceSpeech(").contains("stopSpeechEnvelope()"),
        )
    }

    @Test
    fun `битый уровень санируется на выходе к орбу`() {
        assertTrue(
            "publishLevel — последняя граница перед кадром",
            body(source(CONTROLLER), "private fun publishLevel(").contains("SpeechLevelMath.sanitize(value)"),
        )
    }

    @Test
    fun `сессии играющего аудио отдаются наружу`() {
        assertTrue(
            "ExoPlayer",
            source(PLAYBACK).contains("fun currentSessionId(): Int?"),
        )
        assertTrue(
            "AudioTrack Piper",
            source(OFFLINE).contains("fun currentSessionId(): Int?"),
        )
    }

    @Test
    fun `источник реализован через Visualizer с обязательным release`() {
        val tap = source(TAP)
        assertTrue("привязка к сессии", tap.contains("Visualizer(sessionId)"))
        assertTrue("размер захвата — из диапазона платформы", tap.contains("getCaptureSizeRange()"))
        assertTrue("слушаем форму волны", tap.contains("onWaveFormDataCapture"))
        assertTrue("ошибка эффекта — это false, а не падение", tap.contains("catch (e: RuntimeException)"))
        assertTrue("release обязателен", tap.contains("effect.release()"))
        assertFalse(
            "исключение не имеет права выйти наружу",
            tap.contains("throw RuntimeException"),
        )
    }

    /** Тело функции/свойства по объявлению: от первой `{` до парной закрывающей. */
    private fun body(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        if (start < 0) throw AssertionError("Не найдено объявление: $declaration")
        val open = source.indexOf('{', start)
        if (open < 0) throw AssertionError("У объявления нет тела: $declaration")
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        throw AssertionError("Тело не закрыто: $declaration")
    }

    /** Исходник от текущего каталога вверх: тест может бежать из модуля и из корня репозитория. */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    private companion object {
        const val SESSION = 4212
        const val CONTROLLER = "src/main/java/ru/mars/mobile/LocalVoiceController.kt"
        const val TAP = "src/main/java/ru/mars/mobile/SpeechLevelTap.kt"
        const val PLAYBACK = "src/main/java/ru/mars/mobile/AndroidServerTtsPlayback.kt"
        const val OFFLINE = "src/main/java/ru/mars/mobile/tts/OfflineTtsBackend.kt"
    }
}
