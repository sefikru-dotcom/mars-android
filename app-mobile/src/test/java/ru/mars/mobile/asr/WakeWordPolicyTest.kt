package ru.mars.mobile.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Тесты [WakeWordPolicy] — чистый JVM: ни Android, ни native.
 *
 * Словарь берётся НАСТОЯЩИЙ — `tokens.txt` модели `sherpa-onnx-streaming-zipformer-small-ru-vosk-int8`
 * из ресурсов теста. Это принципиально: посимвольная запись ключевых фраз не падала бы на
 * синтетическом словаре, а на реальном падает с `Cannot find ID for token ▁л`. Здесь закрепляется
 * именно та последовательность токенов, которую принимает native (`▁ма р с`, `▁ма р ш`,
 * `▁с лу ша й`) — та, что сработала в замере.
 */
class WakeWordPolicyTest {

    private companion object {
        /** Ожидаемые последовательности из замера (0.7 с, «марс» на синтезе). */
        const val MARS = "▁ма р с"
        const val MARSH = "▁ма р ш"
        const val LISTEN = "▁с лу ша й"

        /** Словарь реальной модели: 502 строки «token id». */
        fun realVocabulary(): List<String> {
            val stream = checkNotNull(
                WakeWordPolicyTest::class.java.classLoader?.getResourceAsStream("asr/tokens-ru-vosk.txt"),
            ) { "нет ресурса asr/tokens-ru-vosk.txt" }
            return stream.bufferedReader(Charsets.UTF_8).use { WakeWordPolicy.vocabularyOf(it.readText()) }
        }
    }

    private fun policy(
        tokens: Collection<String> = realVocabulary(),
        config: WakeWordConfig = WakeWordConfig(),
    ): WakeWordPolicy = WakeWordPolicy(tokens, config)

    // --- BPE-кодирование: настоящий словарь модели ---

    @Test
    fun `марс кодируется последовательностью из замера`() {
        assertEquals(listOf("▁ма", "р", "с"), policy().encodeWord("марс"))
        assertEquals("марс должен давать ровно ту строку, что сработала в замере", MARS, policy().encodeWord("марс").joinToString(" "))
    }

    @Test
    fun `марш кодируется последовательностью из замера`() {
        assertEquals(listOf("▁ма", "р", "ш"), policy().encodeWord("марш"))
        assertEquals(MARSH, policy().encodeWord("марш").joinToString(" "))
    }

    @Test
    fun `слушай кодируется последовательностью из замера`() {
        assertEquals(listOf("▁с", "лу", "ша", "й"), policy().encodeWord("слушай"))
        assertEquals(LISTEN, policy().encodeWord("слушай").joinToString(" "))
    }

    @Test
    fun `регистр и пробелы вокруг слова не меняют кодирование`() {
        assertEquals(policy().encodeWord("марс"), policy().encodeWord("  МАРС  "))
    }

    @Test
    fun `фраза склеивает слова в одну последовательность токенов`() {
        assertEquals(
            "$MARS $LISTEN",
            policy().encodePhrase("марс слушай").joinToString(" "),
        )
    }

    @Test
    fun `keywords-файл содержит по фразе на строку и токены через пробел`() {
        val content = policy().keywordsFileContent()

        assertEquals(
            "формат native: одна фраза на строку, токены через пробел, файл кончается переводом строки",
            "$MARS\n$MARS $LISTEN\n$MARSH\n",
            content,
        )
        // Каждая строка обязана быть BPE-последовательностью: посимвольная запись падает в native.
        content.trim().lines().forEach { line ->
            val tokens = line.split(" ")
            assertTrue("строка «$line» не должна быть посимвольной", tokens.any { it.length > 1 })
        }
    }

    @Test
    fun `фразы по умолчанию собраны из проверенных форм`() {
        assertEquals(listOf("марс", "марс слушай", "марш"), WakeWordConfig.DEFAULT_PHRASES)
    }

    // --- Ошибки словаря ---

    @Test
    fun `нет токена - понятная ошибка, а не молчаливый пропуск`() {
        // Словарь без «ш»: «марс» кодируется, «марш» — нет.
        val policy = policy(tokens = listOf("▁ма", "р", "с"))
        assertEquals(listOf("▁ма", "р", "с"), policy.encodeWord("марс"))

        try {
            policy.encodeWord("марш")
            fail("отсутствие токена обязано падать: иначе детектор искал бы в потоке не ту фразу")
        } catch (error: WakeWordEncodingException) {
            assertTrue("в сообщении должно быть слово: ${error.message}", error.message!!.contains("марш"))
            assertTrue("в сообщении должен быть фрагмент: ${error.message}", error.message!!.contains("«ш»"))
            assertTrue(
                "сообщение должно объяснять BPE, а не только факт ошибки: ${error.message}",
                error.message!!.contains("tokens.txt"),
            )
        }
    }

    @Test
    fun `пустое слово и пустой список фраз - ошибки конфигурации`() {
        try {
            policy().encodeWord("   ")
            fail("пустое слово кодировать нечего")
        } catch (expected: WakeWordEncodingException) {
            assertTrue(expected.message!!.contains("Пустое слово"))
        }
        try {
            policy(config = WakeWordConfig(phrases = emptyList())).keywordsFileContent()
            fail("пустой keywords-файл native не примет")
        } catch (expected: WakeWordEncodingException) {
            assertTrue(expected.message!!.contains("пуст"))
        }
    }

    // --- Порог и скор: значения из замера, а не дефолты sherpa-onnx ---

    @Test
    fun `порог по умолчанию 0_10, а не дефолт sherpa-onnx 0_25`() {
        assertEquals(0.10f, WakeWordConfig.DEFAULT_THRESHOLD, 0.0001f)
        assertTrue(
            "0.25 — дефолт апстрима, на нём детектор не срабатывал ни на одном файле замера",
            WakeWordConfig.DEFAULT_THRESHOLD != 0.25f,
        )
        assertEquals(0.10f, policy().threshold, 0.0001f)
    }

    @Test
    fun `скор по умолчанию нейтральный - как в замере`() {
        assertEquals(1.0f, WakeWordConfig.DEFAULT_SCORE, 0.0001f)
        assertEquals(1.0f, policy().config.score, 0.0001f)
    }

    // --- Дебаунс ---

    @Test
    fun `срабатывание принимается, повтор внутри окна - нет`() {
        val policy = policy()
        val start = 10_000L

        assertTrue("первое срабатывание обязано пройти", policy.acceptDetection(start))
        assertFalse(
            "хвост той же фразы не должен открывать диалог второй раз",
            policy.acceptDetection(start + WakeWordConfig.REPEAT_SUPPRESS_MS - 1),
        )
        assertTrue(
            "после окна подавления фраза снова принимается",
            policy.acceptDetection(start + WakeWordConfig.REPEAT_SUPPRESS_MS),
        )
    }

    @Test
    fun `часы, ушедшие назад, считаются повтором`() {
        val policy = policy()
        assertTrue(policy.acceptDetection(5_000L))
        assertFalse("отрицательная дельта не должна обходить дебаунс", policy.acceptDetection(1_000L))
    }

    @Test
    fun `reset снимает окно подавления`() {
        val policy = policy()
        assertTrue(policy.acceptDetection(1_000L))
        assertFalse(policy.acceptDetection(1_100L))
        policy.reset()
        assertTrue("новая сессия прослушивания не наследует паузу прошлой", policy.acceptDetection(1_200L))
    }

    @Test
    fun `окно подавления настраивается`() {
        val policy = policy(config = WakeWordConfig(repeatSuppressMs = 100L))
        assertTrue(policy.acceptDetection(0L))
        assertFalse(policy.acceptDetection(99L))
        assertTrue(policy.acceptDetection(100L))
    }
}
