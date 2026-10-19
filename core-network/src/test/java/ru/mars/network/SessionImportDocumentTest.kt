package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.export.SessionImportDocument

/**
 * Подготовка JSON-документа к `POST /api/session/import` (долг O5).
 *
 * Сервер (`_handle_session_import`) требует объект с массивом `messages`; файл мог
 * быть сохранён другой версией клиента или быть обёрнут в `{"session": {...}}`.
 * Отказ по форме файла должен приходить БЕЗ запроса к серверу.
 */
class SessionImportDocumentTest {

    @Test
    fun flatExportBecomesReadyAsIs() {
        val raw = """{"title":"Отчёт","messages":[{"role":"user","content":"привет"}],"workspace":"/w"}"""
        val document = SessionImportDocument.prepare(raw)
        assertTrue(document is SessionImportDocument.Ready)
        val fields = (document as SessionImportDocument.Ready).fields
        assertEquals("Отчёт", fields["title"])
        assertEquals("/w", fields["workspace"])
        assertEquals(1, (fields["messages"] as List<*>).size)
    }

    @Test
    fun wrappedDetailFormIsUnwrapped() {
        val raw = """{"session":{"title":"Обёртка","messages":[{"role":"user","content":"x"}]}}"""
        val document = SessionImportDocument.prepare(raw)
        assertTrue(document is SessionImportDocument.Ready)
        val fields = (document as SessionImportDocument.Ready).fields
        assertEquals("Обёртка", fields["title"])
        assertTrue(fields["messages"] is List<*>)
    }

    @Test
    fun emptyWrapperDoesNotHideFlatFields() {
        // Пустой объект "session" не должен заслонять плоские поля экспорта.
        val raw = """{"session":{},"title":"Плоский","messages":[]}"""
        val document = SessionImportDocument.prepare(raw)
        assertTrue(document is SessionImportDocument.Ready)
        assertEquals("Плоский", (document as SessionImportDocument.Ready).fields["title"])
    }

    @Test
    fun bomAndOuterWhitespaceAreTolerated() {
        val raw = "\uFEFF\n  {\"messages\":[]}  \n"
        assertTrue(SessionImportDocument.prepare(raw) is SessionImportDocument.Ready)
    }

    @Test
    fun emptyFileIsRejected() {
        assertEquals(SessionImportDocument.Invalid("Файл пуст"), SessionImportDocument.prepare("   \n"))
    }

    @Test
    fun brokenJsonIsRejectedWithoutNetwork() {
        assertEquals(
            SessionImportDocument.Invalid("Файл не является JSON-объектом"),
            SessionImportDocument.prepare("{\"messages\": ["),
        )
        assertEquals(
            SessionImportDocument.Invalid("Файл не является JSON-объектом"),
            SessionImportDocument.prepare("[1,2,3]"),
        )
    }

    @Test
    fun documentWithoutMessagesIsRejected() {
        assertEquals(
            SessionImportDocument.Invalid("В файле нет массива messages"),
            SessionImportDocument.prepare("""{"title":"Без сообщений"}"""),
        )
        assertEquals(
            SessionImportDocument.Invalid("В файле нет массива messages"),
            SessionImportDocument.prepare("""{"messages":"не массив"}"""),
        )
    }

    @Test
    fun wrongFieldTypesAreRejectedBeforeRequest() {
        assertEquals(
            SessionImportDocument.Invalid("Поле tool_calls должно быть массивом"),
            SessionImportDocument.prepare("""{"messages":[],"tool_calls":{}}"""),
        )
        assertEquals(
            SessionImportDocument.Invalid("Поле title должно быть строкой"),
            SessionImportDocument.prepare("""{"messages":[],"title":42}"""),
        )
    }
}
