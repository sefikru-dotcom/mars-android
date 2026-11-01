package ru.mars.network.local

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты долгой памяти ([MemoryStore]) и профиля владельца ([OwnerProfile], [OwnerProfileStore]).
 *
 * Файлы — только во временном каталоге JUnit: ни один тест не пишет в репозиторий и не зависит
 * от предыдущего прогона. Проверяется то, что нельзя увидеть на устройстве без ручного сценария:
 *
 * - лимит [MemoryStore.MAX_CHARS] (отказ с числами, а не молчаливая обрезка) и то, что отказ
 *   не портит уже сохранённое;
 * - разделитель `§` на отдельной строке: запись с ним отклоняется, а `§` внутри строки — нет;
 * - перечитывание после пересоздания объекта (данные реально на диске, а не в памяти процесса);
 * - битый файл (мусор UTF-8, нули, BOM, только разделители) не роняет чтение;
 * - `systemPromptBlock`/`toPromptSection`: пустая память и пустой профиль не дают мусорного
 *   заголовка в промпте, многострочная запись продолжается с отступом.
 *
 * ВАЖНО про `org.json`: `OwnerProfileStore` разбирает JSON через `org.json`, который в
 * `core-network` приходит из `android.jar`. В JVM-тестах это заглушки, а
 * `testImplementation("org.json:json:…")` в `core-network/build.gradle.kts` нет (в app-mobile
 * он есть). Поэтому тесты хранилища профиля, которым нужен настоящий `org.json`, вызывают
 * [requireOrgJson] и **пропускаются** (Assumption), а не падают: сам код профиля при этом
 * проверяется полностью (data-класс, промпт-секция, отсутствие файла, битый JSON).
 */
class MemoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(): File = File(folder.root, "memory.txt")

    private fun store(): MemoryStore = MemoryStore(file())

    // ─────────────────────────────── добавление и чтение ───────────────────────────────

    @Test
    fun addKeepsOrderAndTrimsEdges() {
        val store = store()

        assertEquals(AddResult.Ok, store.add("  первый факт  "))
        assertEquals(AddResult.Ok, store.add("\n второй факт\n"))

        assertEquals(listOf("первый факт", "второй факт"), store.entries())
        assertTrue("файл памяти должен появиться на диске", file().isFile)
    }

    @Test
    fun blankEntryIsRejectedWithReason() {
        val store = store()

        val result = store.add("   \n\t  ")

        assertTrue("пустая запись должна быть отклонена, а не сохранена", result is AddResult.Rejected)
        assertTrue((result as AddResult.Rejected).reason.contains("пустая"))
        assertTrue(store.entries().isEmpty())
        assertFalse("на отказе файл не создаётся", file().exists())
    }

    @Test
    fun separatorOnItsOwnLineIsRejected() {
        val store = store()

        val result = store.add("первый\n§\nвторой")

        assertTrue(result is AddResult.Rejected)
        assertTrue("причина должна называть разделитель", (result as AddResult.Rejected).reason.contains("§"))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun separatorWithSurroundingSpacesIsRejectedToo() {
        val store = store()

        val result = store.add("текст\n   §   \nещё текст")

        assertTrue("§ в окружении пробелов всё равно рвёт структуру", result is AddResult.Rejected)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun separatorInsideLineIsAllowed() {
        val store = store()

        assertEquals(AddResult.Ok, store.add("ставка § 5% и § 10%"))

        assertEquals(listOf("ставка § 5% и § 10%"), store.entries())
        assertEquals(1, store.entries().size)
    }

    @Test
    fun overflowIsRejectedWithNumbersAndKeepsExistingEntries() {
        val store = store()
        assertEquals(AddResult.Ok, store.add("a".repeat(MemoryStore.MAX_CHARS - 2)))
        assertEquals(MemoryStore.MAX_CHARS - 2, store.usedChars())

        val result = store.add("xyz")

        assertTrue(result is AddResult.Rejected)
        val reason = (result as AddResult.Rejected).reason
        assertTrue("в причине нет занятого объёма: $reason", reason.contains("занято ${MemoryStore.MAX_CHARS - 2} из ${MemoryStore.MAX_CHARS}"))
        assertTrue("в причине нет переполнения: $reason", reason.contains("лишние 1"))
        assertEquals("отказ не должен ничего перезаписывать", 1, store.entries().size)

        // Ровно до лимита — влезает.
        assertEquals(AddResult.Ok, store.add("ab"))
        assertEquals(MemoryStore.MAX_CHARS, store.usedChars())
    }

    @Test
    fun usedCharsCountsOnlyEntriesNotSeparators() {
        val store = store()
        store.add("12345")
        store.add("678")

        assertEquals(8, store.usedChars())
    }

    // ─────────────────────────────── удаление и замена ───────────────────────────────

    @Test
    fun removeDropsExactEntryAndRejectsBadIndexes() {
        val store = store()
        store.add("a")
        store.add("b")
        store.add("c")

        assertTrue(store.remove(1))
        assertEquals(listOf("a", "c"), store.entries())
        assertFalse(store.remove(5))
        assertFalse(store.remove(-1))
        assertEquals(listOf("a", "c"), store.entries())
    }

    @Test
    fun removingLastEntryDeletesFile() {
        val store = store()
        store.add("единственная")

        assertTrue(store.remove(0))

        assertFalse("пустой файл не нужен: он удаляется", file().exists())
        assertTrue(store.isEmpty())
        assertEquals("", store.systemPromptBlock())
    }

    @Test
    fun replaceChangesEntryAndRejectsInvalidInput() {
        val store = store()
        store.add("a")
        store.add("b")

        assertTrue(store.replace(0, "  A  "))
        assertEquals(listOf("A", "b"), store.entries())

        assertFalse("пустая замена отклоняется", store.replace(0, "   "))
        assertFalse("индекса нет", store.replace(9, "x"))
        assertFalse("отрицательный индекс", store.replace(-1, "x"))
        assertFalse("§ на отдельной строке рвёт структуру", store.replace(0, "x\n§\ny"))
        assertEquals("при отказе файл не трогается", listOf("A", "b"), store.entries())
    }

    @Test
    fun replaceBeyondLimitIsRejected() {
        val store = store()
        store.add("a".repeat(MemoryStore.MAX_CHARS - 1))
        assertEquals(MemoryStore.MAX_CHARS - 1, store.usedChars())

        assertFalse(
            "замена вывела память за лимит",
            store.replace(0, "b".repeat(MemoryStore.MAX_CHARS + 1)),
        )
        assertEquals("при отказе файл не трогается", MemoryStore.MAX_CHARS - 1, store.usedChars())

        // Ровно до лимита — влезает: лимит включительный, как и в add.
        assertTrue(store.replace(0, "b".repeat(MemoryStore.MAX_CHARS)))
        assertEquals(MemoryStore.MAX_CHARS, store.usedChars())
    }

    @Test
    fun clearDeletesFileAndLeftovers() {
        val store = store()
        store.add("a")
        store.add("b")

        store.clear()

        assertFalse(file().exists())
        assertTrue(store.isEmpty())
        assertEquals("после clear не должно остаться .tmp", 0, folder.root.listFiles()?.size ?: 0)
    }

    // ─────────────────────────────── промпт-блок ───────────────────────────────

    @Test
    fun systemPromptBlockIsEmptyWhenMemoryIsEmpty() {
        assertEquals("", store().systemPromptBlock())
    }

    @Test
    fun systemPromptBlockListsEntriesAndIndentsContinuationLines() {
        val store = store()
        store.add("первый")
        store.add("второй\nпродолжение")

        val block = store.systemPromptBlock()
        val lines = block.split("\n")

        assertEquals("Память (факты, сохранённые ранее):", lines[0])
        assertEquals("- первый", lines[1])
        assertEquals("- второй", lines[2])
        assertEquals("  продолжение", lines[3])
        assertTrue("блок обязан заканчиваться переводом строки", block.endsWith("\n"))
    }

    // ─────────────────────────────── файл на диске ───────────────────────────────

    @Test
    fun entriesSurviveNewInstance() {
        store().add("факт о владельце")

        val reopened = MemoryStore(file())

        assertEquals(listOf("факт о владельце"), reopened.entries())
        assertEquals(16, reopened.usedChars())
        assertEquals(store().usedChars(), reopened.usedChars())
    }

    @Test
    fun missingFileGivesEmptyList() {
        val store = store()

        assertTrue(store.entries().isEmpty())
        assertTrue(store.isEmpty())
        assertFalse(file().exists())
    }

    @Test
    fun fileOfOnlySeparatorsGivesEmptyList() {
        file().writeText("\n§\n\n§\n   \n", Charsets.UTF_8)

        assertTrue(MemoryStore(file()).entries().isEmpty())
    }

    @Test
    fun bomInFileIsStrippedOnRead() {
        file().writeText("\uFEFFпервая\n§\nвторая", Charsets.UTF_8)

        assertEquals(listOf("первая", "вторая"), MemoryStore(file()).entries())
    }

    @Test
    fun corruptEntriesAreSkippedAndRewrittenOnNextAdd() {
        // U+FFFD — след битого UTF-8, NUL — признак мусора вместо текста. Такие записи не
        // должны ни попадать в промпт, ни считаться в лимите.
        file().writeText("хорошая\n§\n\uFFFDмусор\n§\nс\u0000нулём", Charsets.UTF_8)
        val store = MemoryStore(file())

        assertEquals(listOf("хорошая"), store.entries())
        assertEquals(7, store.usedChars())

        assertEquals(AddResult.Ok, store.add("новая"))
        assertEquals(listOf("хорошая", "новая"), store.entries())
        assertFalse("мусор должен уйти при перезаписи", file().readText(Charsets.UTF_8).contains("\uFFFD"))
    }

    @Test
    fun multilineEntrySurvivesRoundTrip() {
        val store = store()
        val entry = "строка1\nстрока2\nстрока3"

        assertEquals(AddResult.Ok, store.add(entry))

        assertEquals(listOf(entry), MemoryStore(file()).entries())
    }

    /**
     * ФИКСИРУЕТ ДЕФЕКТ (не эталон): результат [LocalFileIo.writeAtomic] в `MemoryStore.write`
     * (MemoryStore.kt:127-133) отбрасывается, поэтому `add` отвечает [AddResult.Ok], хотя на
     * диске ничего нет: полный диск, нет прав, путь занят файлом — и факт потерян молча.
     * Когда `add`/`replace` начнут отдавать отказ на неудачную запись, ассерт переворачивается.
     */
    @Test
    fun addRejectsWhenNothingCouldBeWritten() {
        // Родитель — обычный файл: создать каталог нельзя, запись невозможна.
        val blocker = File(folder.root, "не-каталог")
        blocker.writeText("x", Charsets.UTF_8)
        val store = MemoryStore(File(blocker, "memory.txt"))

        val result = store.add("важный факт")

        assertTrue("add обязан сообщить об отказе, а не Ok: $result", result is AddResult.Rejected)
        assertTrue("на диске ничего не записалось", store.entries().isEmpty())
        assertFalse(File(blocker, "memory.txt").exists())
    }

    /**
     * ФИКСИРУЕТ ДЕФЕКТ (не эталон): `add` возвращает [AddResult.Ok] для записи, которую
     * `readEntries` тут же выбрасывает — фильтр `\uFFFD`/`\u0000` (MemoryStore.kt:116-125)
     * срабатывает только на чтении. Пользователь видит «сохранено», факт исчезает.
     * Когда фильтр переедет в `add`/`normalize`, ассерты ниже меняются на «Ok → 1 запись».
     */
    @Test
    fun addRejectsEntryThatWouldBeDroppedOnRead() {
        val store = store()

        val broken = store.add("факт\u0000мусор")
        assertTrue("запись с NUL надо отклонять сразу: $broken", broken is AddResult.Rejected)
        assertTrue(store.entries().isEmpty())
        assertEquals(0, store.usedChars())

        assertEquals(AddResult.Ok, store.add("обычный факт"))
        assertEquals(listOf("обычный факт"), store.entries())
    }
}

/** Профиль владельца: чистые данные и промпт-секция, без файлов и без `org.json`. */
class OwnerProfileTest {

    @Test
    fun emptyProfileGivesNoPromptSection() {
        val profile = OwnerProfile()

        assertTrue(profile.isEmpty)
        assertEquals("", profile.toPromptSection())
    }

    @Test
    fun whitespaceOnlyFieldsCountAsEmpty() {
        val profile = OwnerProfile(name = "   ", city = "\n\t", about = " ")

        assertTrue(profile.isEmpty)
        assertEquals("", profile.toPromptSection())
    }

    @Test
    fun updatedAtAloneDoesNotMakeProfileFilled() {
        val profile = OwnerProfile(updatedAt = 1_700_000_000_000L)

        assertTrue("отметка времени — не данные о человеке", profile.isEmpty)
        assertEquals("", profile.toPromptSection())
    }

    @Test
    fun promptSectionContainsNameAndCity() {
        val section = OwnerProfile(name = "Иван", city = "Москва").toPromptSection()

        assertEquals("О владельце:\n- Имя: Иван\n- Город: Москва\n", section)
    }

    @Test
    fun promptSectionSkipsBlankFieldsInsteadOfEmptyLabels() {
        val section = OwnerProfile(name = "Иван", city = "  ", about = "").toPromptSection()

        assertTrue(section.contains("- Имя: Иван"))
        assertFalse("«Город: » без значения учит модель выдумывать город", section.contains("Город"))
        assertFalse(section.contains("О себе"))
    }

    @Test
    fun promptSectionTrimsValues() {
        val section = OwnerProfile(name = "  Иван  ", city = " Москва ").toPromptSection()

        assertTrue(section.contains("- Имя: Иван\n"))
        assertTrue(section.contains("- Город: Москва\n"))
    }

    @Test
    fun aboutOnlyProfilePrintsOnlyAbout() {
        val section = OwnerProfile(about = "Любит краткость").toPromptSection()

        assertTrue(section.startsWith("О владельце:"))
        assertTrue(section.contains("- О себе: Любит краткость"))
        assertFalse(section.contains("Имя"))
        assertFalse(section.contains("Город"))
        assertTrue("блок склеивается конкатенацией — нужен хвостовой перевод строки", section.endsWith("\n"))
    }

    @Test
    fun multilineAboutKeepsBothLines() {
        val section = OwnerProfile(name = "Иван", about = "первая строка\nвторая строка").toPromptSection()

        assertTrue(section.contains("первая строка"))
        assertTrue(section.contains("вторая строка"))
    }
}

/** Хранилище профиля: файл в [TemporaryFolder], атомарная запись, терпимый разбор. */
class OwnerProfileStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(): File = File(folder.root, "owner.json")

    private fun store(): OwnerProfileStore = OwnerProfileStore(file())

    @Test
    fun loadWithoutFileGivesEmptyProfile() {
        val store = store()

        assertEquals(OwnerProfile(), store.load())
        assertFalse(store.exists())
        assertEquals("", store.toPromptSection())
    }

    @Test
    fun loadOfBrokenJsonGivesEmptyProfileInsteadOfCrash() {
        file().writeText("{это не json", Charsets.UTF_8)

        val store = store()

        assertEquals("битый файл не должен ронять экран чата", OwnerProfile(), store.load())
        assertEquals("", store.toPromptSection())
    }

    @Test
    fun loadOfEmptyFileGivesEmptyProfile() {
        file().writeText("", Charsets.UTF_8)

        assertEquals(OwnerProfile(), store().load())
    }

    @Test
    fun existsReflectsFileState() {
        val store = store()
        assertFalse(store.exists())

        file().writeText("{}", Charsets.UTF_8)

        assertTrue(store.exists())
    }

    @Test
    fun saveLoadRoundTrip() {
        requireOrgJson()
        val store = store()
        val profile = OwnerProfile(
            name = "Иван",
            city = "Москва",
            about = "любит краткие ответы",
            updatedAt = 1_700_000_000_000L,
        )

        assertTrue(store.save(profile))

        assertEquals(profile, store.load())
        assertTrue(store.exists())
        assertTrue(store.toPromptSection().contains("- Имя: Иван"))
    }

    @Test
    fun saveTrimsFieldsAndStampsUpdatedAt() {
        requireOrgJson()
        val store = store()

        assertTrue(store.save(OwnerProfile(name = "  Иван  ", city = " Москва ")))

        val loaded = store.load()
        assertEquals("Иван", loaded.name)
        assertEquals("Москва", loaded.city)
        assertTrue("updatedAt должен проставиться сам", loaded.updatedAt > 0L)
    }

    @Test
    fun saveEmptyProfileKeepsFileButGivesNoSection() {
        requireOrgJson()
        val store = store()

        assertTrue(store.save(OwnerProfile()))

        assertTrue(store.exists())
        assertTrue(store.load().isEmpty)
        assertEquals("", store.toPromptSection())
    }

    @Test
    fun secondSaveReplacesFirstAndLeavesNoTempFile() {
        requireOrgJson()
        val store = store()
        store.save(OwnerProfile(name = "Первый"))
        store.save(OwnerProfile(name = "Второй", city = "Тверь"))

        val loaded = store.load()
        assertEquals("Второй", loaded.name)
        assertEquals("Тверь", loaded.city)
        assertNotEquals("Первый", loaded.name)
        val leftovers = folder.root.listFiles()?.map { it.name }.orEmpty()
        assertEquals("после атомарной записи должен остаться ровно один файл: $leftovers", listOf("owner.json"), leftovers)
    }

    @Test
    fun cyrillicAndQuotesSurviveJsonRoundTrip() {
        requireOrgJson()
        val store = store()
        val about = "Он сказал: \"привет\" & ушёл\nвторая строка"

        assertTrue(store.save(OwnerProfile(name = "Пётр Ильич", about = about)))

        val loaded = store.load()
        assertEquals("Пётр Ильич", loaded.name)
        assertEquals(about, loaded.about)
    }

    @Test
    fun loadIgnoresUnknownKeysAndMissingFields() {
        requireOrgJson()
        file().writeText("""{"version":1,"name":"Иван","timezone":"MSK"}""", Charsets.UTF_8)

        val loaded = store().load()

        assertEquals("Иван", loaded.name)
        assertEquals("", loaded.city)
        assertEquals("", loaded.about)
        assertEquals(0L, loaded.updatedAt)
    }

    @Test
    fun loadTreatsJsonNullAsEmptyString() {
        requireOrgJson()
        file().writeText("""{"name":null,"city":"Москва"}""", Charsets.UTF_8)

        val loaded = store().load()

        assertEquals("", loaded.name)
        assertEquals("Москва", loaded.city)
        assertFalse("«null» не должен попасть в промпт строкой", loaded.toPromptSection().contains("null"))
    }

    @Test
    fun bomInProfileFileIsStripped() {
        requireOrgJson()
        file().writeText("\uFEFF" + """{"name":"Иван"}""", Charsets.UTF_8)

        assertEquals("Иван", store().load().name)
    }

    /**
     * `OwnerProfileStore` разбирает JSON через `org.json`, который в JVM-тестах `core-network`
     * подменён заглушкой из `android.jar` (методы бросают «Stub!»), потому что в
     * `core-network/build.gradle.kts` нет `testImplementation("org.json:json:…")`.
     * Тест не падает, а честно пропускается — иначе зелёная сборка врала бы о покрытии.
     */
    private fun requireOrgJson() {
        val available = runCatching {
            JSONObject().put("k", "v").toString() == "{\"k\":\"v\"}"
        }.getOrDefault(false)
        assumeTrue(
            "нужен настоящий org.json: добавьте testImplementation(\"org.json:json:20240303\") в core-network",
            available,
        )
    }
}
