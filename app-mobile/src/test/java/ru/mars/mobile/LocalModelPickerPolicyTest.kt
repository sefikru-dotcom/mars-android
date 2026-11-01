package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalModelInfo

/**
 * Каталог подсказок и правила пикера.
 *
 * Проверяемое свойство — честность: известная модель получает название из каталога,
 * незнакомая показывается как есть (без выдуманного описания), отсутствие зрения
 * берётся из каталога провайдера, но не из догадки по имени, а эмбеддинги и реранкеры
 * в пикер чата не попадают вовсе.
 */
class LocalModelPickerPolicyTest {

    private fun info(
        id: String,
        window: Int? = null,
        modalities: List<String> = emptyList(),
        reasoning: Boolean = false,
    ) = LocalModelInfo(
        id = id,
        contextWindow = window,
        supportsReasoningEffort = reasoning,
        inputModalities = modalities,
    )

    // --- каталог подсказок -------------------------------------------------------------

    @Test
    fun knownModelsGetHumanTitles() {
        assertEquals("Claude Haiku 5.5", LocalModelHints.title("claude-haiku-5.5"))
        assertEquals("Gemini 3.8 Flash", LocalModelHints.title("google/gemini-3.8-flash"))
        assertEquals("GLM 4.5 Flash", LocalModelHints.title("glm-4.5-flash"))
        assertEquals("Kimi K3", LocalModelHints.title("moonshotai/kimi-k3"))
        assertEquals("быстрый основной", LocalModelHints.purpose("claude-haiku-5.5"))
    }

    @Test
    fun unknownModelIsShownAsIsWithoutInventedPurpose() {
        assertEquals("acme-ultra-9", LocalModelHints.title("acme-ultra-9"))
        assertEquals("", LocalModelHints.purpose("acme-ultra-9"))
        assertNull(LocalModelHints.forId("acme-ultra-9"))
        // Безымянный id не превращается в «?».
        assertEquals("", LocalModelHints.title("   "))
    }

    @Test
    fun idMatchingIgnoresCaseAndUnknownPrefix() {
        assertEquals("claude-haiku-5.5", LocalModelHints.normalizeId("  Claude-Haiku-5.5 "))
        // Полный ключ есть — значит префикс часть id, а не украшение.
        assertEquals("anthropic/claude-opus-5", LocalModelHints.normalizeId("anthropic/claude-opus-5"))
        assertEquals("Claude Opus 5", LocalModelHints.title("anthropic/claude-opus-5"))
        // qwen/qwen3.8-max — сам по себе ключ; срезать префикс тут нельзя.
        assertEquals("qwen/qwen3.8-max", LocalModelHints.normalizeId("qwen/qwen3.8-max"))
        assertEquals("Qwen 3.8 Max", LocalModelHints.title("qwen/qwen3.8-max"))
    }

    @Test
    fun catalogHasNoModelsMissingFromLiveCatalog() {
        // Первая редакция контракта: этих id в живом /models нет — подсказка обещала бы
        // несуществующую модель и человек ловил бы ошибку провайдера.
        listOf("claude-sonnet-5.5:kiro", "kimi-k2.8", "kimi-k3.1-pro").forEach { id ->
            assertNull("в каталоге лишний $id", LocalModelHints.forId(id))
            assertEquals(id, LocalModelHints.title(id))
        }
        assertEquals("Claude Sonnet 5.5", LocalModelHints.title("claude-sonnet-5.5"))
    }

    @Test
    fun rolesMatchServerConfig() {
        val roles = mapOf(
            "grok-4.3" to "свежая и быстрая",
            "grok-4.7" to "свежая и быстрая",
            "google/gemini-3.8-flash" to "быстрый, смотрит картинки",
            "qwen3.8-omni-flash" to "быстрый, смотрит картинки",
            "perplexity/sonar-pro" to "поиск в вебе с источниками",
            "doubao-seed-2.0-code" to "код",
            "moonshotai/kimi-k2.7-code" to "код",
            "qwen3.8-2.4t-a95b" to "тяжёлая, сложные задачи",
            "anthropic/claude-opus-5" to "тяжёлая, сложные задачи",
        )
        roles.forEach { (id, purpose) ->
            assertEquals("назначение $id", purpose, LocalModelHints.purpose(id))
            assertFalse("$id не чат-модель", LocalModelPickerPolicy.isNonChat(id))
        }
        // У остальных моделей назначение пустое: догадка правдоподобнее не становится.
        assertEquals("", LocalModelHints.purpose("gemma-4"))
        assertEquals("", LocalModelHints.purpose("gpt-6.1-terra"))
    }

    @Test
    fun catalogOnlyContainsChatModels() {
        // Защита от возврата эмбеддингов в таблицу: они не умеют chat/completions.
        listOf(
            "bge-m3", "embeddinggemma-300m", "giga-embeddings", "jina-embeddings-v4",
            "multilingual-e5-large", "nemotron-embed-vl", "qwen3-embedding-0.6b",
            "qwen3-embedding-4b", "qwen3-embedding-8b", "qwen3-embedding-8b-rus1",
            "qwen3-reranker-8b-rus1", "text-embedding-3-small", "frida", "hy4-preview",
        ).forEach { id -> assertNull("эмбеддинг в подсказках: $id", LocalModelHints.forId(id)) }
    }

    // --- vision ------------------------------------------------------------------------

    @Test
    fun visionComesFromCatalogFirst() {
        // Каталог сказал «только текст» — верим ему даже для модели из таблицы.
        assertEquals(false, LocalModelPickerPolicy.vision(info("claude-haiku-5.5", modalities = listOf("text"))))
        // Каталог сказал «image» — верим.
        assertEquals(true, LocalModelPickerPolicy.vision(info("some-vlm", modalities = listOf("text", "image"))))
    }

    @Test
    fun emptyModalitiesFallBackToHintThenUnknown() {
        // Провайдер промолчал, таблица знает про Gemini — подсказываем зрение.
        assertEquals(true, LocalModelPickerPolicy.vision(info("google/gemini-3.8-flash")))
        assertEquals(true, LocalModelPickerPolicy.vision(info("qwen3.8-omni-flash")))
        // Провайдер промолчал, в таблице ничего — «неизвестно», а не «не умеет».
        assertNull(LocalModelPickerPolicy.vision(info("acme-ultra-9")))
    }

    // --- фильтр чат-моделей ------------------------------------------------------------

    @Test
    fun embeddingsAndRerankersAreFilteredOut() {
        val excluded = listOf(
            "bge-m3", "embeddinggemma-300m", "giga-embeddings", "jina-embeddings-v4",
            "multilingual-e5-large", "nemotron-embed-vl", "qwen3-embedding-0.6b",
            "qwen3-embedding-4b", "qwen3-embedding-8b", "qwen3-embedding-8b-rus1",
            "qwen3-reranker-8b-rus1", "text-embedding-3-small", "frida", "hy4-preview",
        )
        excluded.forEach { id ->
            assertTrue("не отфильтрована $id", LocalModelPickerPolicy.isNonChat(id))
            assertTrue("регистр не учтён: $id", LocalModelPickerPolicy.isNonChat(id.uppercase()))
        }
        // Чат-модели не должны попасть под фильтр: самые близкие по буквам имена.
        listOf(
            "qwen3.8-27b", "gpt-oss-120b", "nemotron-3-super-120b-a12b", "qwen3.8-2.4t-a95b",
            "claude-haiku-5.5", "google/gemini-4-argon", "grok-4.7", "gemma-4",
        ).forEach { id ->
            assertFalse("ложно отфильтрована $id", LocalModelPickerPolicy.isNonChat(id))
        }
    }

    @Test
    fun chatCatalogKeepsOnlyChatModels() {
        val raw = listOf(
            info("claude-haiku-5.5"),
            info("bge-m3"),
            info("qwen3-reranker-8b-rus1"),
            info("multilingual-e5-large"),
            info("frida"),
            info("grok-4.7"),
        )
        assertEquals(
            listOf("claude-haiku-5.5", "grok-4.7"),
            LocalModelPickerPolicy.chatModels(raw).map { it.id },
        )
    }

    // --- группировка и поиск -----------------------------------------------------------

    private val catalog = listOf(
        info("claude-haiku-5.5", window = 200_000),
        info("moonshotai/kimi-k3", window = 256_000),
        info("acme-ultra-9", window = 8_000),
        info("google/gemini-3.8-flash", window = 1_000_000),
    )

    @Test
    fun entriesCarryTitlesAndProvider() {
        val entries = LocalModelPickerPolicy.entries(catalog, "PlusVibe")
        val haiku = entries.first { it.id == "claude-" + "haiku-5.5" }
        assertEquals("Claude " + "Haiku 5.5", haiku.title)
        assertEquals("PlusVibe", haiku.provider)
        // Без вендорского префикса модель идёт напрямую через профиль.
        assertEquals("PlusVibe", haiku.vendor)
        assertTrue(haiku.known)
        val kimi = entries.first { it.id == "moonshotai/kimi-k3" }
        // Запрос всё равно уходит на адрес профиля, но модель — Moonshot.
        assertEquals("PlusVibe", kimi.provider)
        assertEquals("Moonshot", kimi.vendor)
        val unknown = entries.first { it.id == "acme-ultra-9" }
        assertEquals("acme-ultra-9", unknown.title)
        assertFalse(unknown.known)
    }

    @Test
    fun vendorComesFromIdPrefix() {
        val expected = mapOf(
            "anthropic/" to "Anthropic",
            "openai/gpt-oss-120b" to "Open" + "AI",
            "google/gemini-3.8-flash" to "Google",
            "qwen/qwen3.8-max" to "Alibaba",
            "moonshotai/kimi-k3" to "Moonshot",
            "x-ai/grok-4.6" to "xAI",
            "z-ai/glm-5.2" to "Z.AI",
            "xiaomi/mimo-v2.5" to "Xiaomi",
            "tencent/hy3" to "Tencent",
        )
        expected.forEach { (id, vendor) ->
            assertEquals("площадка $id", vendor, LocalModelPickerPolicy.vendorOf(id, "PlusVibe"))
        }
        // Регистр id не превращает знакомый префикс в незнакомый.
        assertEquals("Anthropic", LocalModelPickerPolicy.vendorOf("Anthropic/", "PlusVibe"))
        // Голый id и незнакомый префикс не выдумывают площадку: маршрут — профиль.
        assertEquals("PlusVibe", LocalModelPickerPolicy.vendorOf("deepseek-v4.1-flash", "PlusVibe"))
        assertEquals("PlusVibe", LocalModelPickerPolicy.vendorOf("acme/ultra-9", "PlusVibe"))
        // Имени профиля нет — прочерк, а не пустая строка.
        assertEquals("—", LocalModelPickerPolicy.vendorOf("glm-5.1", ""))
    }

    @Test
    fun sectionVendorIsSingleOrNullWhenGroupIsMixed() {
        val same = LocalModelPickerPolicy.sections(
            LocalModelPickerPolicy.entries(
                listOf(info("anthropic/"), info("anthropic/"), info("google/gemini-3.8-flash")),
                "PlusVibe",
            ),
        )
        val claude = same.first { it.family == "Claude" }
        // Одна площадка на группу — её место в заголовке, а не в каждой строке.
        assertEquals("Anthropic", claude.vendor)
        assertEquals(listOf("Anthropic"), claude.vendors)

        val mixed = LocalModelPickerPolicy.sections(
            LocalModelPickerPolicy.entries(listOf(info("anthropic/"), info("claude-opus-5.5")), "PlusVibe"),
        ).first { it.family == "Claude" }
        // Anthropic и PlusVibe в одной группе: площадка переезжает в строки.
        assertNull(mixed.vendor)
        assertEquals(listOf("Anthropic", "PlusVibe"), mixed.vendors)
    }

    @Test
    fun searchFindsModelByVendor() {
        val entries = LocalModelPickerPolicy.entries(catalog, "PlusVibe")
        assertEquals(
            listOf("moonshotai/kimi-k3"),
            LocalModelPickerPolicy.filter(entries, "moonshot").map { it.id },
        )
        assertEquals(
            listOf("google/gemini-3.8-flash"),
            LocalModelPickerPolicy.filter(entries, "google").map { it.id },
        )
    }

    @Test
    fun groupsSplitByProviderAndPutPreferredFirst() {
        val a = LocalModelPickerPolicy.entries(catalog, "PlusVibe")
        val b = LocalModelPickerPolicy.entries(listOf(info("glm-4.5-flash")), "Z.AI")
        val groups = LocalModelPickerPolicy.group(a + b, preferredId = "moonshotai/kimi-k3")
        assertEquals(2, groups.size)
        assertEquals("PlusVibe", groups[0].provider)
        // Заводская модель — первой, затем названные по алфавиту, затем безымянные.
        assertEquals("moonshotai/kimi-k3", groups[0].entries[0].id)
        assertEquals("claude-haiku-5.5", groups[0].entries[1].id)
        assertEquals("google/gemini-3.8-flash", groups[0].entries[2].id)
        assertEquals("acme-ultra-9", groups[0].entries[3].id)
        assertEquals("Z.AI", groups[1].provider)
    }

    @Test
    fun searchFindsByTitlePurposeIdAndProvider() {
        val entries = LocalModelPickerPolicy.entries(catalog, "PlusVibe")
        assertEquals(
            listOf("claude-haiku-5.5"),
            LocalModelPickerPolicy.filter(entries, "Claude Haiku").map { it.id },
        )
        // По назначению: слова «быстрый» нет ни в одном id (обе «быстрых» модели находятся).
        assertEquals(
            setOf("claude-haiku-5.5", "google/gemini-3.8-flash"),
            LocalModelPickerPolicy.filter(entries, "быстрый").map { it.id }.toSet(),
        )
        // Два слова сужают до одной: «картинки» есть только у Gemini.
        assertEquals(
            listOf("google/gemini-3.8-flash"),
            LocalModelPickerPolicy.filter(entries, "быстрый картинки").map { it.id },
        )
        // По id подстрокой, регистр не важен.
        assertEquals(
            listOf("moonshotai/kimi-k3"),
            LocalModelPickerPolicy.filter(entries, "KIMI").map { it.id },
        )
        // По провайдеру.
        assertEquals(4, LocalModelPickerPolicy.filter(entries, "plusvibe").size)
        // Все слова запроса обязаны найтись.
        assertTrue(LocalModelPickerPolicy.filter(entries, "kimi haiku").isEmpty())
        // Пустой запрос — весь каталог.
        assertEquals(4, LocalModelPickerPolicy.filter(entries, "  ").size)
    }

    @Test
    fun providerOfRecognisesKnownHosts() {
        assertEquals("PlusVibe", LocalModelPickerPolicy.providerOf("https://plusvibeapi.ru/v1"))
        assertEquals("Yeta", LocalModelPickerPolicy.providerOf("https://platform.yeta.ai/v1"))
        assertEquals("Z.AI", LocalModelPickerPolicy.providerOf("https://api.z.ai/api/paas/v4"))
        // Незнакомый хост показывается хостом, а не выдуманным именем.
        assertEquals("llm.example.com", LocalModelPickerPolicy.providerOf("https://llm.example.com/v1"))
    }

    // --- предупреждение про картинку ----------------------------------------------------

    @Test
    fun imageWarningOnlyForModelsWithoutVision() {
        val warning = LocalModelPickerPolicy.imageWarning("Claude Haiku 5.5", vision = false)
        assertNotNull(warning)
        assertTrue(warning!!.contains("не принимает картинки"))
        // Неизвестно — не пугаем: пустой список modality не доказательство.
        assertNull(LocalModelPickerPolicy.imageWarning("Acme", vision = null))
        assertNull(LocalModelPickerPolicy.imageWarning("Gemini", vision = true))
    }

    @Test
    fun visionAlternativePrefersNamedModelWithVision() {
        val entries = LocalModelPickerPolicy.entries(catalog, "PlusVibe")
        val alt = LocalModelPickerPolicy.visionAlternative(entries)
        assertNotNull(alt)
        assertEquals("google/gemini-3.8-flash", alt!!.id)
        assertNull(
            LocalModelPickerPolicy.visionAlternative(
                LocalModelPickerPolicy.entries(listOf(info("acme-ultra-9")), "PlusVibe"),
            ),
        )
    }

    @Test
    fun noVisionCountCountsOnlyKnownRefusals() {
        val entries = LocalModelPickerPolicy.entries(
            listOf(
                info("claude-haiku-5.5", modalities = listOf("text")),
                info("acme-ultra-9"),
                info("some-vlm", modalities = listOf("text", "image")),
            ),
            "PlusVibe",
        )
        assertEquals(1, LocalModelPickerPolicy.noVisionCount(entries))
    }

    // --- семейства и секции ------------------------------------------------------------

    @Test
    fun familyRecognisesLiveCatalogIds() {
        val expected = mapOf(
            "claude-haiku-5.5" to "Claude",
            "anthropic/claude-opus-5" to "Claude",
            "gpt-6.1-sol" to "GPT",
            "openai/gpt-oss-120b" to "GPT",
            "google/gemini-3.8-flash" to "Gemini",
            "gemma-4" to "Gemma",
            "deepseek-v4.1-flash" to "DeepSeek",
            "deepseek/deepseek-v4-pro-0813" to "DeepSeek",
            "qwen3.8-flash" to "Qwen",
            "qwen/qwen3.8-max" to "Qwen",
            "moonshotai/kimi-k3" to "Kimi",
            "glm-5.3-flash" to "GLM",
            "z-ai/glm-5.2" to "GLM",
            "grok-4.7" to "Grok",
            "x-ai/grok-4.6" to "Grok",
            "minimax/minimax-m3" to "MiniMax",
            "doubao-seed-2.1-turbo" to "Doubao",
            "xiaomi/mimo-v2.5" to "MiMo",
            "llama-4-maverick" to "Llama",
            "nemotron-3.5-lightning" to "Nemotron",
            "perplexity/sonar-pro" to "Perplexity",
            "tencent/hy3" to "Hunyuan",
        )
        expected.forEach { (id, family) ->
            assertEquals("семейство $id", family, LocalModelPickerPolicy.family(id))
        }
        // Регистр и пробелы не мешают; незнакомое не выдумывается.
        assertEquals("Claude", LocalModelPickerPolicy.family("  CLAUDE-Opus-5.5 "))
        assertEquals("Прочие", LocalModelPickerPolicy.family("acme-ultra-9"))
        assertEquals("Прочие", LocalModelPickerPolicy.family(""))
    }

    @Test
    fun sectionsSplitOneProviderByFamilyInFixedOrder() {
        val entries = LocalModelPickerPolicy.entries(
            listOf(
                info("qwen3.8-flash"),
                info("claude-haiku-5.5"),
                info("gpt-6-sol"),
                info("claude-sonnet-5.5"),
                info("acme-ultra-9"),
                info("google/gemini-3.8-flash"),
            ),
            "PlusVibe",
        )
        val sections = LocalModelPickerPolicy.sections(entries)
        // Заголовок при одном провайдере — семейство, а не провайдер: иначе одна
        // группа на весь каталог, то есть тот самый плоский список.
        assertEquals(listOf("Claude", "GPT", "Gemini", "Qwen", "Прочие"), sections.map { it.title })
        assertEquals(listOf(2, 1, 1, 1, 1), sections.map { it.count })
        assertEquals("Claude Haiku 5.5", sections[0].entries[0].title)
        assertEquals("Claude Sonnet 5.5", sections[0].entries[1].title)
        // «Прочие» — в конце: выдуманное семейство не встаёт первым.
        assertEquals("acme-ultra-9", sections.last().entries.single().id)
    }

    @Test
    fun sectionsCarryProviderWhenCatalogIsMixed() {
        val mixed = LocalModelPickerPolicy.entries(catalog, "PlusVibe") +
            LocalModelPickerPolicy.entries(listOf(info("glm-4.5-flash")), "Z.AI")
        val titles = LocalModelPickerPolicy.sections(mixed).map { it.title }
        // Два Claude из разных серверов не должны слипнуться в одну секцию.
        assertTrue(titles.contains("PlusVibe · Claude"))
        assertTrue(titles.contains("Z.AI · GLM"))
        assertTrue(LocalModelPickerPolicy.sections(mixed).all { it.provider != null })
    }

    @Test
    fun sectionsPutPreferredModelFirstInsideItsSection() {
        val entries = LocalModelPickerPolicy.entries(catalog, "PlusVibe")
        val claude = LocalModelPickerPolicy.sections(entries, preferredId = "claude-haiku-5.5")
            .first { it.family == "Claude" }
        assertEquals("claude-haiku-5.5", claude.entries.first().id)
    }

    @Test
    fun emptyCatalogProducesNoSections() {
        assertTrue(LocalModelPickerPolicy.sections(emptyList()).isEmpty())
    }
}
