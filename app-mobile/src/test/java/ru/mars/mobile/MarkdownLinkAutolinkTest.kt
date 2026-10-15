package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Автолинк «голых» URL в чате: `https://…` и домены вида `marscore.ru/path`
 * становятся кликабельными [InlineSpan.Link] прямо в прозе.
 *
 * Инварианты, которые здесь закреплены:
 * - код (инлайн `` `…` `` и блоки ``` … ```) ссылкой не становится;
 * - хвостовая пунктуация и непарные закрывающие скобки в URL не попадают;
 * - парные скобки внутри URL сохраняются целиком;
 * - markdown-форма `[title](url)` по-прежнему работает;
 * - fast-path `hasInlineMarkers` не деградирует на обычном тексте (числа, `итого: 5`).
 */
class MarkdownLinkAutolinkTest {

    private fun InlineSpan.visible(): String = when (this) {
        is InlineSpan.Normal -> text
        is InlineSpan.Bold -> text
        is InlineSpan.Italic -> text
        is InlineSpan.Monospace -> text
        is InlineSpan.Link -> title
        is InlineSpan.Strikethrough -> text
        is InlineSpan.Image -> alt
    }

    private fun List<InlineSpan>.plainText(): String = joinToString("") { it.visible() }

    private fun links(raw: String): List<InlineSpan.Link> =
        MarkdownFormatter.parseInline(raw).filterIsInstance<InlineSpan.Link>()

    // ── Голый URL со схемой ──────────────────────────────────────────────────

    @Test
    fun bareHttpsUrlBecomesClickableLink() {
        val spans = MarkdownFormatter.parseInline("Смотри https://marscore.ru/docs/1 тут")
        assertEquals(3, spans.size)
        assertEquals("Смотри ", (spans[0] as InlineSpan.Normal).text)
        val link = spans[1] as InlineSpan.Link
        assertEquals("https://marscore.ru/docs/1", link.title)
        assertEquals("https://marscore.ru/docs/1", link.url)
        assertEquals(" тут", (spans[2] as InlineSpan.Normal).text)
        assertEquals("Смотри https://marscore.ru/docs/1 тут", spans.plainText())
    }

    @Test
    fun bareHttpUrlAndUrlAtLineStartAreLinked() {
        assertEquals("http://example.com/x", links("http://example.com/x").single().url)
        val spans = MarkdownFormatter.parseInline("https://example.com")
        assertEquals(1, spans.size)
        assertEquals("https://example.com", (spans.single() as InlineSpan.Link).url)
    }

    // ── Голый домен без схемы ────────────────────────────────────────────────

    @Test
    fun bareDomainIsLinkedWithHttpsTarget() {
        val link = links("Зашёл на marscore.ru/docs/2 вчера").single()
        // Видимый текст — как в сообщении, а цель получает схему, иначе
        // Intent.ACTION_VIEW по адресу без схемы не откроет браузер.
        assertEquals("marscore.ru/docs/2", link.title)
        assertEquals("https://marscore.ru/docs/2", link.url)
    }

    @Test
    fun abbreviatedTldsAreLinkedAcrossTheDefaultList() {
        for (host in listOf("example.ru", "example.com", "example.net", "example.org", "example.io", "example.dev", "example.me", "example.ai", "example.su", "example.рф")) {
            val span = links("ссылка $host и всё").single()
            assertEquals(host, span.title)
            assertEquals("https://$host", span.url)
        }
    }

    @Test
    fun unknownTldAndVersionNumbersAreNotLinks() {
        // Иначе `файл.pdf` и `версия 1.5.4` в отчётах стали бы ссылками.
        assertTrue(links("открой файл.pdf потом").isEmpty())
        assertTrue(links("версия 1.5.4 и 2.0.1").isEmpty())
        assertTrue(links("привет, 42!").isEmpty())
    }

    @Test
    fun emailAddressIsNotLinked() {
        assertTrue(links("пиши a+b@example.ru сегодня").isEmpty())
        assertEquals(
            "пиши a+b@example.ru сегодня",
            MarkdownFormatter.parseInline("пиши a+b@example.ru сегодня").plainText(),
        )
    }

    // ── Хвостовая пунктуация и скобки ────────────────────────────────────────

    @Test
    fun trailingSentencePunctuationStaysOutsideTheUrl() {
        val spans = MarkdownFormatter.parseInline("См. https://marscore.ru/docs.")
        val link = spans.filterIsInstance<InlineSpan.Link>().single()
        assertEquals("https://marscore.ru/docs", link.url)
        // Точка прозы — обычный текст, а не часть адреса.
        assertEquals(".", (spans.last() as InlineSpan.Normal).text)
        assertEquals("См. https://marscore.ru/docs.", spans.plainText())
    }

    @Test
    fun commaAndBracketTailAreTrimmed() {
        assertEquals(
            "https://x.ru/a",
            links("список: https://x.ru/a, https://y.ru/b.").map { it.url }.toList().first(),
        )
        assertEquals(
            "https://x.ru/a",
            links("(см. https://x.ru/a)").single().url,
        )
        assertEquals(
            "https://x.ru/a",
            links("ссылка https://x.ru/a;").single().url,
        )
    }

    @Test
    fun balancedParenthesesInsideUrlArePreserved() {
        val link = links("wiki https://en.wikipedia.org/wiki/Foo_(bar) тут").single()
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", link.url)
    }

    @Test
    fun closingParenPairIsKeptWhenBalanced() {
        // Двойная закрывающая скобка: одна принадлежит прозе, одна — URL.
        val link = links("(https://en.wikipedia.org/wiki/Foo_(bar))").single()
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", link.url)
    }

    // ── Код не линкуется ────────────────────────────────────────────────────

    @Test
    fun urlInsideInlineCodeIsNotLinked() {
        val spans = MarkdownFormatter.parseInline("запусти `curl https://x.ru` сейчас")
        assertTrue("в инлайн-коде ссылок быть не должно", spans.none { it is InlineSpan.Link })
        assertEquals(1, spans.filterIsInstance<InlineSpan.Monospace>().size)
        assertEquals("curl https://x.ru", spans.filterIsInstance<InlineSpan.Monospace>().single().text)
        assertEquals("запусти curl https://x.ru сейчас", spans.plainText())
    }

    @Test
    fun urlInsideFencedCodeBlockIsNotLinked() {
        val blocks = MarkdownFormatter.parseBlocks("Пример:\n\n```bash\ncurl https://x.ru/api\n```\n")
        val code = blocks.filterIsInstance<ContentBlock.Code>().single()
        assertEquals("curl https://x.ru/api", code.code)
        // Код-блок вообще не проходит через parseInline — ссылок в нём нет по построению.
        assertTrue(blocks.none { it is ContentBlock.Table })
    }

    @Test
    fun urlInBoldStaysBoldAndIsNotSplit() {
        val spans = MarkdownFormatter.parseInline("**https://x.ru**")
        assertEquals(1, spans.size)
        assertEquals("https://x.ru", (spans.single() as InlineSpan.Bold).text)
    }

    // ── Регресс markdown-формы ───────────────────────────────────────────────

    @Test
    fun markdownLinkStillWinsOverAutolink() {
        val link = links("[Квартал](https://kvartalpro.ru)").single()
        assertEquals("Квартал", link.title)
        assertEquals("https://kvartalpro.ru", link.url)
    }

    @Test
    fun markdownLinkAndBareUrlCoexist() {
        val spans = MarkdownFormatter.parseInline("сайт [Квартал](https://kvartalpro.ru) и голый marscore.ru/api")
        val all = spans.filterIsInstance<InlineSpan.Link>()
        assertEquals(2, all.size)
        assertEquals("Квартал", all[0].title)
        assertEquals("https://marscore.ru/api", all[1].url)
        assertEquals("сайт Квартал и голый marscore.ru/api", spans.plainText())
    }

    @Test
    fun imageSyntaxIsUnaffectedByAutolink() {
        val spans = MarkdownFormatter.parseInline("![Скриншот](https://x.ru/a.png)")
        assertEquals(1, spans.size)
        assertEquals("https://x.ru/a.png", (spans.single() as InlineSpan.Image).url)
    }

    @Test
    fun linkInsideBulletAndTableStillParses() {
        val bullet = MarkdownFormatter.parseLines("- пункт с [ссылкой](https://example.com)")[0] as RichLine.Bullet
        assertTrue(bullet.spans.any { it is InlineSpan.Link && it.url == "https://example.com" })

        val table = MarkdownFormatter.parseBlocks("| a | b |\n|---|---|\n| marscore.ru/x | [с](https://example.com) |").single() as ContentBlock.Table
        assertEquals("marscore.ru/x", (table.rows.single()[0].single() as InlineSpan.Link).title)
        assertEquals("https://marscore.ru/x", (table.rows.single()[0].single() as InlineSpan.Link).url)
        assertEquals("https://example.com", (table.rows.single()[1].single() as InlineSpan.Link).url)
    }

    // ── Fast-path ───────────────────────────────────────────────────────────

    @Test
    fun plainTextWithoutUrlKeepsTheFastPath() {
        for (plain in listOf("просто текст без разметки", "итого: 5", "версия 1.5.4 и 2.0.1", "Привет, Марс! Как дела?")) {
            assertFalse("ложный маркер ломает fast-path: $plain", MarkdownFormatter.hasInlineMarkers(plain))
            assertEquals(listOf<InlineSpan>(InlineSpan.Normal(plain)), MarkdownFormatter.parseInline(plain))
        }
    }

    @Test
    fun urlMarkersTurnTheFastPathOn() {
        assertTrue(MarkdownFormatter.hasInlineMarkers("см. https://x.ru"))
        assertTrue(MarkdownFormatter.hasInlineMarkers("см. marscore.ru/x"))
        assertTrue(MarkdownFormatter.hasInlineMarkers("a `b"))
        assertTrue(MarkdownFormatter.hasInlineMarkers("a *b"))
        assertTrue(MarkdownFormatter.hasInlineMarkers("a [b"))
        assertTrue(MarkdownFormatter.hasInlineMarkers("a ~b"))
    }

    @Test
    fun fastPathStaysEquivalentToFullParseForUrlText() {
        for (raw in listOf("см. https://x.ru/a.", "голый marscore.ru/x", "итого: 5", "версия 1.5.4")) {
            assertEquals(
                "fast path разошёлся с полным разбором для: $raw",
                MarkdownFormatter.parseBlocksUncached(raw),
                MarkdownFormatter.parseBlocks(raw),
            )
        }
    }
}
