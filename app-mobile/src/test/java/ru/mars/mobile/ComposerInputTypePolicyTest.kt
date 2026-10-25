package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Юнит-тесты [ComposerInputTypePolicy] — поле ввода композера: капитализация предложений,
 * многострочность с переносом по словам, IME-отправка и потолок высоты.
 *
 * Тесты не только фиксируют значения флагов, но и объясняют ВЫБОР: почему капитализация именно
 * по предложениям (а не по словам/буквам), почему `NO_SUGGESTIONS` не выставлен, почему нет
 * `IME_FLAG_NO_ENTER_ACTION`. Последний блок — контракт проводки: политика реально применена
 * в [ChatScreenScaffold] в правильном порядке, а окно чата ужимается под клавиатуру.
 */
class ComposerInputTypePolicyTest {

    private fun source(relative: String): String {
        // Unit-тесты Gradle работают из каталога модуля app-mobile; подъём вверх — на случай
        // worktree/CI с другим cwd (тот же приём, что в ChatClickPathContractTest).
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

    private val scaffold by lazy { source("src/main/java/ru/mars/mobile/ChatScreenScaffold.kt") }
    private val manifest by lazy { source("src/main/AndroidManifest.xml") }

    // --- inputType: класс и капитализация --------------------------------------------------------

    @Test
    fun `input type is a plain text field`() {
        // Обычный текст (не пароль, не телефон, не число): иначе клавиатура не покажет
        // ни подсказок, ни капитализации, а ввод превратится в цифровую/скрытую панель.
        assertEquals(
            ComposerInputTypePolicy.TYPE_CLASS_TEXT,
            ComposerInputTypePolicy.INPUT_TYPE and ComposerInputTypePolicy.TYPE_MASK_CLASS,
        )
    }

    @Test
    fun `input type is not a password or uri variation`() {
        // Вариация NORMAL (0): поле чата — свободный текст. URI/PASSWORD/EMAIL-вариации
        // отключили бы подсказки и автозамену и включили бы маскировку ввода.
        val variationMask = 0x00000FF0
        assertEquals(0, ComposerInputTypePolicy.INPUT_TYPE and variationMask)
    }

    @Test
    fun `capitalizes sentences but never words or characters`() {
        // CAP_SENTENCES: заглавная после точки/в начале поля — это и есть «первая буква заглавная»,
        // которой ждёт владелец. Флаг понимает локаль и не трогает середину предложения.
        assertTrue(
            "Первая буква предложения должна быть заглавной",
            ComposerInputTypePolicy.INPUT_TYPE and ComposerInputTypePolicy.TYPE_TEXT_FLAG_CAP_SENTENCES != 0,
        )
        // CAP_WORDS («Каждое Слово С Заглавной») ломает обычный русский текст и аббревиатуры.
        assertEquals(
            "Капитализация каждого слова не должна включаться",
            0,
            ComposerInputTypePolicy.INPUT_TYPE and ComposerInputTypePolicy.TYPE_TEXT_FLAG_CAP_WORDS,
        )
        // CAP_CHARACTERS — это Caps Lock на весь ввод, а не капитализация.
        assertEquals(
            "Caps Lock на весь ввод не должен включаться",
            0,
            ComposerInputTypePolicy.INPUT_TYPE and ComposerInputTypePolicy.TYPE_TEXT_FLAG_CAP_CHARACTERS,
        )
    }

    @Test
    fun `input type is multiline`() {
        // MULTI_LINE — единственный способ сказать клавиатуре «Enter = перевод строки».
        // Без него EditText остаётся однострочным и длинный текст скроллится вбок.
        assertTrue(
            "Поле должно быть многострочным",
            ComposerInputTypePolicy.INPUT_TYPE and ComposerInputTypePolicy.TYPE_TEXT_FLAG_MULTI_LINE != 0,
        )
    }

    @Test
    fun `suggestions stay enabled`() {
        // NO_SUGGESTIONS выключил бы подсказки и автозамену — в чате они нужны.
        assertEquals(
            "Подсказки не должны отключаться",
            0,
            ComposerInputTypePolicy.INPUT_TYPE and ComposerInputTypePolicy.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
    }

    @Test
    fun `input type value is documented platform constant`() {
        // 0x00000001 (CLASS_TEXT) | 0x00004000 (CAP_SENTENCES) | 0x00020000 (MULTI_LINE).
        // Литерал зафиксирован, чтобы случайная правка флагов не проехала незамеченной.
        assertEquals(0x00024001, ComposerInputTypePolicy.INPUT_TYPE)
    }

    // --- imeOptions: отправка не мешает многострочности -------------------------------------------

    @Test
    fun `ime action stays send`() {
        // Младший байт imeOptions — идентификатор действия; SEND (4) = «Отправить».
        assertEquals(
            ComposerInputTypePolicy.IME_ACTION_SEND,
            ComposerInputTypePolicy.IME_OPTIONS and 0x000000FF,
        )
    }

    @Test
    fun `extract ui is disabled so text never leaves the capsule`() {
        // Без NO_EXTRACT_UI клавиатура на маленьком/ландшафтном поле открывает полноэкранный
        // редактор — текст «вылезает за рамки» капсулы в отдельное окно.
        assertTrue(
            "Полноэкранный extract-редактор должен быть запрещён",
            ComposerInputTypePolicy.IME_OPTIONS and ComposerInputTypePolicy.IME_FLAG_NO_EXTRACT_UI != 0,
        )
    }

    @Test
    fun `enter action is not forbidden so keyboards can still show send`() {
        // NO_ENTER_ACTION заставляет Enter всегда вставлять перевод строки и у части IME
        // убирает кнопку действия — отправка с клавиатуры пропала бы. Нам нужны обе
        // возможности: кнопка отправки есть, а Enter (без действия) делает новую строку.
        assertEquals(
            "IME_FLAG_NO_ENTER_ACTION не должен выставляться",
            0,
            ComposerInputTypePolicy.IME_OPTIONS and ComposerInputTypePolicy.IME_FLAG_NO_ENTER_ACTION,
        )
    }

    @Test
    fun `only explicit send action sends the message`() {
        assertTrue(ComposerInputTypePolicy.isSendAction(4))
        // IME_NULL(0) — обычный Enter в многострочном поле: отдаём полю, будет перевод строки.
        // DONE(6)/GO(2)/SEARCH(3)/NEXT(5)/PREVIOUS(7) — не наши действия, поле обработает само.
        listOf(0, 1, 2, 3, 5, 6, 7).forEach { action ->
            assertFalse("Действие $action не должно отправлять сообщение", ComposerInputTypePolicy.isSendAction(action))
        }
    }

    @Test
    fun `ime options value is documented platform constant`() {
        // 0x00000004 (IME_ACTION_SEND) | 0x10000000 (IME_FLAG_NO_EXTRACT_UI).
        assertEquals(0x10000004, ComposerInputTypePolicy.IME_OPTIONS)
    }

    // --- геометрия: рост до пяти строк и пиксельный потолок ---------------------------------------

    @Test
    fun `max lines come from the composer presenter`() {
        // Единственный источник истины — презентер: поле и состояние композера не разъедутся.
        assertEquals(ComposerLayoutPresenter.MAX_INPUT_LINES, ComposerInputTypePolicy.maxLines())
        assertEquals(5, ComposerInputTypePolicy.maxLines())
    }

    @Test
    fun `max height is five lines plus vertical padding`() {
        // 5 строк × 40 px + 8 px отступов = 208 px: выше капсула не растёт.
        assertEquals(208, ComposerInputTypePolicy.maxHeightPx(lineHeightPx = 40, verticalPaddingPx = 8))
    }

    @Test
    fun `max height scales with the line count`() {
        assertEquals(48, ComposerInputTypePolicy.maxHeightPx(lineHeightPx = 40, verticalPaddingPx = 8, lines = 1))
        assertEquals(128, ComposerInputTypePolicy.maxHeightPx(lineHeightPx = 40, verticalPaddingPx = 8, lines = 3))
        // Одна строка текста без отступов — ровно высота строки.
        assertEquals(40, ComposerInputTypePolicy.maxHeightPx(lineHeightPx = 40, verticalPaddingPx = 0, lines = 1))
    }

    @Test
    fun `unknown font metrics never collapse the field`() {
        // Метрики до раскладки могут быть нулевыми/отрицательными: тогда потолок не навязываем
        // (иначе maxHeight = 0 схлопнул бы поле в невидимую полоску). Предел остаётся за maxLines.
        assertEquals(Int.MAX_VALUE, ComposerInputTypePolicy.maxHeightPx(lineHeightPx = 0, verticalPaddingPx = 8))
        assertEquals(Int.MAX_VALUE, ComposerInputTypePolicy.maxHeightPx(lineHeightPx = -3, verticalPaddingPx = 8))
        assertEquals(Int.MAX_VALUE, ComposerInputTypePolicy.maxHeightPx(lineHeightPx = 40, verticalPaddingPx = 8, lines = 0))
    }

    // --- контракт проводки ------------------------------------------------------------------------

    @Test
    fun `composer input applies the policy`() {
        assertTrue(
            "Поле обязано брать inputType из политики",
            scaffold.contains("inputType = ComposerInputTypePolicy.INPUT_TYPE"),
        )
        assertTrue(
            "Поле обязано брать imeOptions из политики",
            scaffold.contains("imeOptions = ComposerInputTypePolicy.IME_OPTIONS"),
        )
        assertTrue(
            "Предел строк — из политики",
            scaffold.contains("maxLines = ComposerInputTypePolicy.maxLines()"),
        )
        assertTrue(
            "Пиксельный потолок высоты — из политики",
            scaffold.contains("maxHeight = ComposerInputTypePolicy.maxHeightPx("),
        )
        assertTrue(
            "Перенос по словам вместо горизонтального скролла",
            scaffold.contains("setHorizontallyScrolling(false)"),
        )
        assertTrue(
            "IME-действие разбирается политикой",
            scaffold.contains("ComposerInputTypePolicy.isSendAction(action)"),
        )
    }

    @Test
    fun `max lines is applied after single line is disabled`() {
        // Ловушка TextView: setSingleLine(false) -> applySingleLine(..., changeMaxLines = true)
        // -> setMaxLines(Integer.MAX_VALUE). Если maxLines поставить РАНЬШЕ, он будет сброшен,
        // поле станет бесконечно высоким и вылезет за капсулу на панель чипов.
        val inputTypeAt = scaffold.indexOf("inputType = ComposerInputTypePolicy.INPUT_TYPE")
        val singleLineAt = scaffold.indexOf("isSingleLine = false")
        val maxLinesAt = scaffold.indexOf("maxLines = ComposerInputTypePolicy.maxLines()")
        assertTrue("inputType должен применяться до isSingleLine", inputTypeAt >= 0 && inputTypeAt < singleLineAt)
        assertTrue(
            "maxLines обязан идти ПОСЛЕ isSingleLine = false, иначе сбрасывается в MAX_VALUE",
            singleLineAt in 0 until maxLinesAt,
        )
    }

    @Test
    fun `chat window resizes for the keyboard`() {
        // Без adjustResize окно не ужимается: композер уезжает под клавиатуру и «прыгает».
        val chatBlock = manifest.substringAfter("android:name=\".ChatActivity\"")
            .substringBefore("/>")
        assertTrue(
            "ChatActivity должен ужимать окно под клавиатуру",
            chatBlock.contains("android:windowSoftInputMode=\"adjustResize\""),
        )
    }
}
