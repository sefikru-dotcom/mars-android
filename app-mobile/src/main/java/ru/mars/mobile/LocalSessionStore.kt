package ru.mars.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.mars.network.local.LocalMessage
import ru.mars.network.local.LocalRole
import ru.mars.network.local.LocalSession
import ru.mars.network.local.LocalToolCall
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Хранилище диалогов локального чата: один JSON-файл на диалог в `filesDir/local-chat/`.
 *
 * Почему файлы, а не Room: в проекте Room нет, а тянуть KSP ради волны 1 нельзя
 * (см. `docs/CONTRACT-WAVE1-LOCAL.md` §4 W3.8). Диалогов единицы десятков, сообщений —
 * сотни, поэтому цена полного чтения каталога на экране списка незначительна.
 *
 * Гарантии:
 *  - запись атомарная: сначала `<id>.json.tmp` + `fsync`, потом `renameTo` (после
 *    выключения питания на диске либо старый файл, либо новый, но не половина);
 *  - лимит [MAX_MESSAGES] сообщений на диалог (обрезаются самые старые);
 *  - лимит [MAX_SESSIONS] диалогов (удаляются самые старые по `updatedAt`);
 *  - битый/недописанный JSON не роняет чтение: файл пропускается, каталог живёт.
 *
 * Каталог передаётся снаружи ([File]), а не берётся из `Context`, — так вся логика
 * (кодек, обрезка, эвикция) тестируется на JVM обычным `tempDir`, без Android.
 *
 * Метаданные диалога — [StoredSession]: кроме `id`/`title`/`updatedAt` в файле лежат
 * необязательные поля `model`, `totalPromptTokens`, `totalTokens`, `reasoningEffort`.
 * Все они с значениями по умолчанию, поэтому файл, записанный до волны 2, читается
 * без ошибки (`model = null`, токены = 0) — см. [LocalSessionJson.decode].
 *
 * `LocalSession` (тип ядра `core-network`) эти поля не несёт и не меняется; там по-прежнему
 * только `id`/`title`/`updatedAt`. Кто хочет расход/модель — берёт [meta] или [metas].
 */
class LocalSessionStore(private val dir: File) {

    /** Все диалоги, свежие сверху. Битые файлы молча пропускаются. */
    fun sessions(): List<LocalSession> = LocalSessionRetention.listSessions(dir)

    /**
     * Мета всех диалогов, свежие сверху: `id`/`title`/`updatedAt` плюс модель и расход.
     * Сообщения не разбираются ([StoredSession.messages] пуст) — это дешёвая замена
     * [sessions] для списка, которому нужны модель и токены в строке.
     */
    fun metas(): List<StoredSession> = LocalSessionRetention.listMetas(dir)

    /** Метаданные диалога или `null`, если файла нет/он битый. */
    fun session(sessionId: String): LocalSession? =
        LocalSessionRetention.readSession(dir, sessionId)?.session

    /** Метаданные с моделью и расходом, но без сообщений. `null` — нет файла/битый. */
    fun meta(sessionId: String): StoredSession? =
        LocalSessionRetention.readMetaEntry(LocalSessionRetention.fileFor(dir, sessionId))

    /** Сообщения диалога; битый файл — пустой список, а не исключение. */
    fun messages(sessionId: String): List<LocalMessage> =
        LocalSessionRetention.readSession(dir, sessionId)?.messages.orEmpty()

    /** Новый пустой диалог. Заголовок меняется на производный от первого сообщения. */
    fun create(
        title: String = DEFAULT_TITLE,
        now: Long = System.currentTimeMillis(),
    ): LocalSession {
        val session = LocalSession(
            id = newSessionId(now),
            title = LocalSessionsPolicy.cleanTitle(title, DEFAULT_TITLE),
            updatedAt = now,
        )
        persist(StoredSession(session, emptyList()))
        return session
    }

    /**
     * Записать диалог. Возвращает сохранённую мету: `updatedAt` переставлен на [now],
     * `title` — как передали (экран решает, когда выводить его из первого сообщения).
     *
     * Модель и расход из файла **сохраняются**: этот вызов идёт из `onPersist` на каждое
     * сообщение и не должен затирать мету, которую пишет [save] или [updateUsage].
     */
    fun save(
        session: LocalSession,
        messages: List<LocalMessage>,
        now: Long = System.currentTimeMillis(),
    ): LocalSession {
        val previous = meta(session.id)
        val updated = session.copy(updatedAt = now)
        persist(
            StoredSession(
                session = updated,
                messages = messages,
                model = previous?.model,
                totalPromptTokens = previous?.totalPromptTokens ?: 0L,
                totalTokens = previous?.totalTokens ?: 0L,
                reasoningEffort = previous?.reasoningEffort,
                turnSettings = previous?.turnSettings,
            )
        )
        return updated
    }

    /**
     * Сохранить снимок параметров хода, не трогая сообщения и расход.
     *
     * Отдельный метод, потому что пишется в другой момент: сообщения — после каждого
     * хода, параметры — когда человек их изменил в шторке. `null` — диалога нет или
     * файл битый (писать некуда).
     */
    fun updateTurnSettings(sessionId: String, settingsJson: String?): StoredSession? {
        val current = LocalSessionRetention.readSession(dir, sessionId) ?: return null
        val updated = current.copy(turnSettings = settingsJson)
        persist(updated)
        return updated
    }

    /**
     * Записать диалог вместе с метой расхода/модели (явный вариант [save]).
     * `updatedAt` переставляется на [now], остальные поля — как в [stored].
     */
    fun save(
        stored: StoredSession,
        now: Long = System.currentTimeMillis(),
    ): StoredSession {
        val updated = stored.copy(session = stored.session.copy(updatedAt = now))
        persist(updated)
        return updated
    }

    /**
     * Обновить только накопленный расход, не трогая сообщения, заголовок и модель.
     * `null` — диалога нет или файл битый (тогда писать некуда).
     */
    fun updateUsage(
        sessionId: String,
        promptTokens: Long,
        totalTokens: Long,
    ): StoredSession? {
        val current = LocalSessionRetention.readSession(dir, sessionId) ?: return null
        val updated = current.copy(
            totalPromptTokens = promptTokens.coerceAtLeast(0L),
            totalTokens = totalTokens.coerceAtLeast(0L),
        )
        persist(updated)
        return updated
    }

    fun delete(sessionId: String) {
        LocalSessionRetention.fileFor(dir, sessionId).delete()
    }

    /**
     * Переименовать диалог по его мете. Заголовок чистится
     * ([LocalSessionsPolicy.cleanTitle]), сообщения и мета расхода сохраняются.
     */
    fun rename(
        session: LocalSession,
        title: String,
        now: Long = System.currentTimeMillis(),
    ): LocalSession = applyRename(
        stored = LocalSessionRetention.readSession(dir, session.id),
        fallback = session,
        title = title,
        now = now,
    )

    /**
     * Переименовать по идентификатору — то, что нужно списку диалогов (у него на руках
     * только `id`). `null` — такого диалога нет или файл битый: новый файл не создаётся.
     */
    fun rename(
        sessionId: String,
        title: String,
        now: Long = System.currentTimeMillis(),
    ): LocalSession? {
        val stored = LocalSessionRetention.readSession(dir, sessionId) ?: return null
        return applyRename(stored = stored, fallback = stored.session, title = title, now = now)
    }

    /**
     * Поиск по истории: регистронезависимо по заголовку **и** по тексту сообщений.
     * Пустой запрос — «фильтра нет», возвращаются все диалоги ([sessions]).
     *
     * Читает сообщения (в отличие от [sessions]): искать по тому, чего не прочитал, нельзя.
     * Битые файлы пропускаются, порядок — свежие сверху.
     */
    fun search(query: String): List<LocalSession> {
        val needle = LocalSessionsPolicy.normalize(query) ?: return sessions()
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.name.endsWith(LocalSessionRetention.FILE_SUFFIX) }
            .mapNotNull { file -> LocalSessionRetention.readFile(file) }
            .filter { stored ->
                LocalSessionsPolicy.matches(
                    normalizedQuery = needle,
                    title = stored.session.title,
                    texts = stored.messages.map { it.text },
                )
            }
            .map { stored -> stored.session }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    private fun applyRename(
        stored: StoredSession?,
        fallback: LocalSession,
        title: String,
        now: Long,
    ): LocalSession {
        val base = stored?.session ?: fallback
        val updated = base.copy(
            title = LocalSessionsPolicy.cleanTitle(title, DEFAULT_TITLE),
            updatedAt = now,
        )
        persist(stored?.copy(session = updated) ?: StoredSession(updated, emptyList()))
        return updated
    }

    private fun persist(stored: StoredSession) {
        val trimmed = LocalSessionRetention.trimMessages(stored.messages, MAX_MESSAGES)
        val payload = LocalSessionJson.encode(stored.session, trimmed, stored)
        LocalSessionRetention.writeAtomic(LocalSessionRetention.fileFor(dir, stored.session.id), payload)
        // Эвикция строго после записи: новый диалог участвует в отборе наравне со
        // старыми, иначе на границе лимита он выжил бы лишним.
        LocalSessionRetention.prune(dir, MAX_SESSIONS)
    }

    companion object {
        /** Сообщений на диалог. Хвост важнее начала: обрезаем самые старые. */
        const val MAX_MESSAGES = 200

        /** Диалогов на устройстве. */
        const val MAX_SESSIONS = 100

        const val DEFAULT_TITLE = "Новый диалог"

        /** Каталог под `filesDir` — только имя, путь собирает [forContext]. */
        const val DIR_NAME = "local-chat"

        fun forContext(context: Context): LocalSessionStore =
            LocalSessionStore(File(context.filesDir, DIR_NAME))

        /** Идентификатор диалога: время (сортировка) + случайный хвост (уникальность). */
        fun newSessionId(now: Long = System.currentTimeMillis()): String =
            "s" + now.toString(36) + "-" + UUID.randomUUID().toString().replace("-", "").take(8)
    }
}

/**
 * Прочитанный файл диалога: мета + сообщения.
 *
 * Поля `model`/`totalPromptTokens`/`totalTokens`/`reasoningEffort` — мета волны 2.
 * Все с значениями по умолчанию, поэтому старый файл (где их нет) даёт `model = null`
 * и нулевой расход, а не ошибку. `messages` пуст у мета-чтения ([LocalSessionStore.metas]).
 */
data class StoredSession(
    val session: LocalSession,
    val messages: List<LocalMessage>,
    /** Модель, на которой шёл диалог; `null` — неизвестна (старый файл или «Авто»). */
    val model: String? = null,
    /** Накопленный `prompt_tokens` по всем шагам диалога. */
    val totalPromptTokens: Long = 0L,
    /** Накопленный `total_tokens` по всем шагам диалога. */
    val totalTokens: Long = 0L,
    /** Уровень reasoning (`low`/`medium`/`high`); `null` — «Авто». */
    val reasoningEffort: String? = null,
    /**
     * Снимок параметров хода (сэмпсинг) в JSON — как он был у диалога в момент
     * последнего сохранения.
     *
     * Строкой, а не типом ядра, намеренно: хранилище не обязано знать про
     * [ru.mars.network.local.LocalTurnSettings], иначе любое расширение параметров
     * ломало бы чтение старых файлов. Кто сохраняет — сериализует сам, кто читает —
     * разбирает сам и при ошибке получает `null` («настройки не сохранены»), а не
     * диалог без сообщений.
     */
    val turnSettings: String? = null,
)

/**
 * Чистые операции над каталогом диалогов: обрезка, чтение, эвикция, атомарная запись.
 *
 * Ни одного обращения к Android — поэтому `LocalSessionStoreTest` (W4) работает с
 * обычным `File`-каталогом. Лимиты приходят параметрами, чтобы тест не зависел от
 * констант экрана.
 */
object LocalSessionRetention {

    const val FILE_SUFFIX = ".json"
    const val TEMP_SUFFIX = ".tmp"

    /** Оставить последние [limit] сообщений (хвост диалога важнее начала). */
    fun trimMessages(
        messages: List<LocalMessage>,
        limit: Int = LocalSessionStore.MAX_MESSAGES,
    ): List<LocalMessage> {
        if (limit <= 0) return emptyList()
        if (messages.size <= limit) return messages
        return messages.subList(messages.size - limit, messages.size).toList()
    }

    /**
     * Файл диалога. Идентификатор санируется: он приходит извне (Intent, файл на
     * диске), и `../` в нём не должен вывести запись за пределы каталога.
     */
    fun fileFor(dir: File, sessionId: String): File =
        File(dir, sanitize(sessionId) + FILE_SUFFIX)

    fun sanitize(raw: String): String {
        val cleaned = raw.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
        return cleaned.takeIf { it.isNotBlank() } ?: "session"
    }

    /** Чтение одного диалога. `null` — файла нет или JSON битый/не того формата. */
    fun readSession(dir: File, sessionId: String): StoredSession? = readFile(fileFor(dir, sessionId))

    fun readFile(file: File): StoredSession? {
        if (!file.isFile) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return LocalSessionJson.decode(raw)
    }

    /** Каталог диалогов, свежие сверху. Битые файлы и `.tmp` не участвуют. */
    fun listSessions(dir: File): List<LocalSession> =
        listMetas(dir).map { stored -> stored.session }

    /** То же, но с метой (модель, расход) и без сообщений. */
    fun listMetas(dir: File): List<StoredSession> {
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            .mapNotNull { file -> readMetaEntry(file) }
            .sortedByDescending { stored -> stored.session.updatedAt }
            .toList()
    }

    /**
     * Только метаданные диалога — без разбора сообщений.
     *
     * Список диалогов читается на каждый `onResume`; декодировать 100 файлов по 200
     * сообщений ради трёх полей заголовка незачем. Битый файл — `null`, а не исключение.
     */
    fun readMeta(file: File): LocalSession? = readMetaEntry(file)?.session

    /** Мета без сообщений: `id`/`title`/`updatedAt` + модель и расход. */
    fun readMetaEntry(file: File): StoredSession? {
        if (!file.isFile) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return LocalSessionJson.decodeMetaEntry(raw)
    }

    /**
     * Эвикция лишних диалогов: удаляет самые старые, возвращает их id (для лога/теста).
     * Ошибки удаления не бросаются — файл, который нельзя удалить, просто останется.
     */
    fun prune(dir: File, limit: Int = LocalSessionStore.MAX_SESSIONS): List<String> {
        val sessions = listSessions(dir)
        if (sessions.size <= limit) return emptyList()
        val excess = sessions.sortedBy { it.updatedAt }.take(sessions.size - limit)
        return excess.mapNotNull { session ->
            val file = fileFor(dir, session.id)
            if (file.delete()) session.id else null
        }
    }

    /**
     * Атомарная запись: `.tmp` → `fsync` → `renameTo`.
     *
     * `renameTo` поверх существующего файла на части ФС (и на Windows в JVM-тестах)
     * отказывает, поэтому при неудаче цель удаляется и rename повторяется. Остаться
     * должен ровно один файл: либо прежний, либо новый.
     */
    fun writeAtomic(target: File, payload: String) {
        val parent = target.parentFile ?: error("нет каталога для ${target.name}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            error("не удалось создать каталог ${parent.path}")
        }
        val temp = File(parent, target.name + TEMP_SUFFIX)
        FileOutputStream(temp).use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()
            // fsync: без него rename может опередить данные и после сбоя питания
            // на диске окажется пустой файл с правильным именем.
            runCatching { out.fd.sync() }
        }
        if (temp.renameTo(target)) return
        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            error("не удалось заменить ${target.name}")
        }
    }
}

/**
 * Кодек диалога. Формат свой (Room нет), но стабильный:
 * `{ "version":2, "id", "title", "updatedAt", "model"?, "totalPromptTokens"?,
 * "totalTokens"?, "reasoningEffort"?, "messages":[...] }`.
 *
 * Декодер терпим к мусору: неизвестные поля игнорируются, отсутствующие необязательные
 * поля меты дают значения по умолчанию (`model = null`, токены = 0) — файл волны 1
 * читается без ошибки, `version` при чтении не проверяется вовсе. Сообщение без id
 * получает синтетический, нераспознанная роль — пропуск сообщения. Исключение наружу
 * не летит никогда: битый файл должен стоить одного диалога, а не экрана.
 */
object LocalSessionJson {

    /** 2 — добавлена мета `model`/токены/`reasoningEffort`. Чтение версию не проверяет. */
    const val VERSION = 2

    /**
     * @param meta носитель необязательных полей меты ([StoredSession.model] и др.).
     *   `session`/`messages` из него не берутся — они приходят отдельными аргументами.
     */
    fun encode(
        session: LocalSession,
        messages: List<LocalMessage>,
        meta: StoredSession? = null,
    ): String {
        val array = JSONArray()
        messages.forEach { message -> array.put(encodeMessage(message)) }
        return JSONObject().apply {
            put("version", VERSION)
            put("id", session.id)
            put("title", session.title)
            put("updatedAt", session.updatedAt)
            // Необязательные поля пишем только когда они есть: свежий диалог остаётся
            // ровно таким же, каким его писала волна 1.
            meta?.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            meta?.reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoningEffort", it) }
            meta?.turnSettings?.takeIf { it.isNotBlank() }?.let { put("turnSettings", it) }
            val promptTokens = meta?.totalPromptTokens ?: 0L
            if (promptTokens > 0L) put("totalPromptTokens", promptTokens)
            val totalTokens = meta?.totalTokens ?: 0L
            if (totalTokens > 0L) put("totalTokens", totalTokens)
            put("messages", array)
        }.toString()
    }

    fun decode(raw: String): StoredSession? = runCatching {
        val root = JSONObject(raw)
        val meta = decodeMetaEntry(root) ?: return@runCatching null
        meta.copy(messages = decodeMessages(root))
    }.getOrNull()

    /** Метаданные без разбора сообщений — для списка диалогов. */
    fun decodeMeta(raw: String): LocalSession? = decodeMetaEntry(raw)?.session

    /** Мета с моделью и расходом, без сообщений. */
    fun decodeMetaEntry(raw: String): StoredSession? = runCatching {
        decodeMetaEntry(JSONObject(raw))
    }.getOrNull()

    private fun decodeMetaEntry(root: JSONObject): StoredSession? {
        val id = root.optText("id") ?: return null
        return StoredSession(
            session = LocalSession(
                id = id,
                title = root.optText("title") ?: LocalSessionStore.DEFAULT_TITLE,
                updatedAt = root.optLong("updatedAt", 0L),
            ),
            messages = emptyList(),
            model = root.optText("model"),
            totalPromptTokens = root.optTokens("totalPromptTokens"),
            totalTokens = root.optTokens("totalTokens"),
            reasoningEffort = root.optText("reasoningEffort"),
            turnSettings = root.optText("turnSettings"),
        )
    }

    private fun decodeMessages(root: JSONObject): List<LocalMessage> {
        val array = root.optJSONArray("messages") ?: return emptyList()
        val messages = ArrayList<LocalMessage>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            decodeMessage(item, index)?.let { messages.add(it) }
        }
        return messages
    }

    fun encodeMessage(message: LocalMessage): JSONObject = JSONObject().apply {
        put("id", message.id)
        put("role", message.role.name)
        put("text", message.text)
        put("createdAt", message.createdAt)
        message.toolCallId?.let { put("toolCallId", it) }
        if (message.toolCalls.isNotEmpty()) {
            put("toolCalls", JSONArray().apply {
                message.toolCalls.forEach { call ->
                    put(JSONObject().apply {
                        put("id", call.id)
                        put("name", call.name)
                        put("arguments", call.argumentsJson)
                    })
                }
            })
        }
    }

    fun decodeMessage(item: JSONObject, index: Int = 0): LocalMessage? {
        val role = roleOf(item.optString("role")) ?: return null
        val calls = ArrayList<LocalToolCall>()
        item.optJSONArray("toolCalls")?.let { array ->
            for (i in 0 until array.length()) {
                val call = array.optJSONObject(i) ?: continue
                val callId = call.optString("id").takeIf { it.isNotBlank() } ?: continue
                calls.add(
                    LocalToolCall(
                        id = callId,
                        name = call.optString("name"),
                        argumentsJson = call.optString("arguments"),
                    )
                )
            }
        }
        return LocalMessage(
            id = item.optString("id").takeIf { it.isNotBlank() } ?: "m-$index",
            role = role,
            text = item.optString("text"),
            toolCalls = calls,
            toolCallId = item.optString("toolCallId").takeIf { it.isNotBlank() },
            createdAt = item.optLong("createdAt", 0L),
        )
    }

    /**
     * Строковое поле меты. Отсутствие, `null` и контейнер — `null`; пустая строка — тоже
     * `null` (иначе «модель» стала бы `""`). `JSONObject.optString` для этого не годится:
     * он вернул бы строку `"null"` для явного JSON-`null`.
     */
    private fun JSONObject.optText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is String -> raw.trim().takeIf { it.isNotEmpty() }
            is Number, is Boolean -> raw.toString()
            else -> null
        }
    }

    /**
     * Счётчик токенов. Старый файл без поля — 0; строка с числом принимается (провайдер
     * мог прислать `"123"`); отрицательное и мусор приводятся к 0, а не к исключению.
     */
    private fun JSONObject.optTokens(key: String): Long {
        if (!has(key) || isNull(key)) return 0L
        val value = when (val raw = opt(key)) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
        return value.coerceAtLeast(0L)
    }

    private fun roleOf(raw: String): LocalRole? =
        LocalRole.values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
}
