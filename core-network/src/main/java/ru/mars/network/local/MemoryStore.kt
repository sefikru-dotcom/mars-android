package ru.mars.network.local

import java.io.File
import java.io.FileOutputStream

/**
 * Долгая память модели: факты о владельце и рабочие заметки в одном файле.
 *
 * Формат — как у серверного Марса: обычный текст, записи разделены строкой `§`.
 * Такой файл читается глазами, правится блокнотом и переносится между телефоном и
 * сервером без конвертации. Разделитель обязан стоять **на отдельной строке**:
 * запись сама может быть многострочной, и `§` внутри текста не должен её рвать
 * (такая запись отклоняется в [add] с понятной причиной).
 *
 * Почему файл, а не база: записей единицы десятков, объём ограничен
 * [MAX_CHARS], читаются они один раз на сборку системного промпта. Room/KSP в
 * `core-network` нет и тянуть его ради списка строк нельзя.
 *
 * Гарантии:
 *  - запись атомарная ([LocalFileIo.writeAtomic]): `<name>.tmp` + `fsync` + `renameTo`,
 *    после сбоя питания на диске либо старый файл, либо новый, но не половина;
 *  - лимит [MAX_CHARS] символов на **всю** память (как `memory_char_limit` на сервере),
 *    переполнение — отказ с причиной, а не молчаливая обрезка: молча потерять факт
 *    хуже, чем сказать «не влезло»;
 *  - битый, пустой или отсутствующий файл не роняет чтение: [entries] отдаёт пустой
 *    список, а не исключение.
 *
 * Путь передаёт вызывающий ([file]) — модуль чистый, `android.*` не знает, поэтому
 * вся логика проверяется на JVM обычным `File` во временном каталоге.
 */
class MemoryStore(private val file: File) {

    /** Записи в порядке добавления. Файла нет или он битый — пустой список. */
    fun entries(): List<String> = readEntries()

    /**
     * Добавить запись. Текст обрезается по краям; пустой — отказ.
     *
     * Переполнение считается по сумме символов всех записей (разделители `§` не в
     * счёт: они служебные). Ничего не обрезается — при нехватке места возвращается
     * [AddResult.Rejected] с числами, чтобы экран мог показать «занято N из 8000».
     */
    fun add(text: String): AddResult {
        val entry = normalize(text)
        if (entry.isEmpty()) return AddResult.Rejected(REASON_EMPTY)
        if (hasSeparatorLine(entry)) return AddResult.Rejected(REASON_SEPARATOR)
        // Мусорные символы надо отклонять ЗДЕСЬ, а не только на чтении: readEntries
        // выбросит такую запись, и факт исчезнет, хотя add() ответил Ok.
        if (hasBrokenChars(entry)) return AddResult.Rejected(REASON_BROKEN)
        val current = readEntries()
        val used = usedChars(current)
        val overflow = used + entry.length - MAX_CHARS
        if (overflow > 0) {
            return AddResult.Rejected(
                "память заполнена: занято $used из $MAX_CHARS символов, " +
                    "запись на ${entry.length} не влезает (лишние $overflow)"
            )
        }
        return if (write(current + entry)) {
            AddResult.Ok
        } else {
            AddResult.Rejected(REASON_WRITE_FAILED)
        }
    }

    /** Удалить запись по индексу. `false` — индекса нет (в том числе отрицательного). */
    fun remove(index: Int): Boolean {
        val current = readEntries()
        if (index !in current.indices) return false
        val next = current.toMutableList().apply { removeAt(index) }
        write(next)
        return true
    }

    /**
     * Заменить запись по индексу. `false` — индекса нет, текст пустой или замена
     * выводит память за [MAX_CHARS]. При отказе файл не трогается.
     */
    fun replace(index: Int, text: String): Boolean {
        val entry = normalize(text)
        if (entry.isEmpty()) return false
        if (hasSeparatorLine(entry)) return false
        if (hasBrokenChars(entry)) return false
        val current = readEntries()
        if (index !in current.indices) return false
        val used = usedChars(current) - current[index].length + entry.length
        if (used > MAX_CHARS) return false
        val next = current.toMutableList().apply { set(index, entry) }
        return write(next)
    }

    /** Стереть память целиком: файл удаляется, а не переписывается пустым. */
    fun clear() {
        LocalFileIo.delete(file)
    }

    /**
     * Готовый блок для системного промпта. Пустая строка, если записей нет, — тогда
     * вызывающий просто ничего не добавляет в промпт, а не отправляет пустой заголовок.
     *
     * Блок заканчивается переводом строки: координатор склеивает его с профилем и
     * правилами конкатенацией, и заголовок не должен прилипнуть к следующему разделу.
     */
    fun systemPromptBlock(): String {
        val list = readEntries()
        if (list.isEmpty()) return ""
        val sb = StringBuilder(HEADER).append('\n')
        list.forEach { entry ->
            // Многострочная запись продолжается с отступом: иначе её вторая строка
            // читается моделью как отдельный пункт списка.
            sb.append("- ").append(entry.replace("\n", "\n  ")).append('\n')
        }
        return sb.toString()
    }

    /** Занято символов по сумме записей (без служебных разделителей). */
    fun usedChars(): Int = usedChars(readEntries())

    /** Есть ли что показывать в UI: пустая память — пустой блок. */
    fun isEmpty(): Boolean = readEntries().isEmpty()

    private fun readEntries(): List<String> {
        val raw = LocalFileIo.readText(file) ?: return emptyList()
        return raw.split(SEPARATOR_LINE)
            .map { it.trim() }
            // Испорченная запись (декодер UTF-8 подставил U+FFFD, либо в файле нули —
            // признак не текста, а мусора) не попадает ни в промпт, ни в счёт лимита:
            // «��\u0000» в контексте модели хуже, чем потеря одной строки. Следующий
            // [add] перепишет файл уже без мусора.
            .filter { entry -> entry.isNotEmpty() && entry.none { it == '\uFFFD' || it == '\u0000' } }
    }

    private fun write(entries: List<String>): Boolean {
        if (entries.isEmpty()) {
            LocalFileIo.delete(file)
            return true
        }
        // Результат записи обязан доходить до вызывающего: иначе add() рапортует Ok,
        // а факт не сохранился (нет прав, полный диск, путь занят) — и владелец
        // считает, что модель запомнила, хотя память пуста.
        return LocalFileIo.writeAtomic(file, entries.joinToString(SEPARATOR_JOIN))
    }

    private fun usedChars(entries: List<String>): Int = entries.sumOf { it.length }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun hasSeparatorLine(text: String): Boolean =
        text.lineSequence().any { it.trim() == SEPARATOR }

    /** Те же символы, что отбрасывает [readEntries]: запись с ними не доживёт до чтения. */
    private fun hasBrokenChars(text: String): Boolean =
        text.any { it == '\uFFFD' || it == '\u0000' }

    companion object {
        /** Лимит символов на всю память — как `memory_char_limit` на сервере Марса. */
        const val MAX_CHARS = 8000

        /** Разделитель записей на отдельной строке. */
        const val SEPARATOR = "§"

        private const val SEPARATOR_JOIN = "\n§\n"

        private val SEPARATOR_LINE = Regex("(?m)^[ \\t]*§[ \\t]*$")

        private const val HEADER = "Память (факты, сохранённые ранее):"

        private const val REASON_EMPTY = "пустая запись: нечего сохранять"

        private const val REASON_SEPARATOR = "запись содержит разделитель «§» на отдельной строке"

        /** Нечитаемые символы: попадут в файл, но при чтении запись будет отброшена. */
        private const val REASON_BROKEN = "запись содержит нечитаемые символы — они потеряются при чтении"

        /** Диск не принял запись: нет прав, места или путь занят. */
        private const val REASON_WRITE_FAILED = "не удалось записать память на диск"
    }
}

/**
 * Результат [MemoryStore.add].
 *
 * Не `Boolean`: причину отказа показывают человеку («память заполнена: занято 7980
 * из 8000»), и строка для этого уже готова. Запечатанный класс, чтобы вызывающий
 * не мог придумать третий исход.
 */
sealed class AddResult {
    /** Запись сохранена. */
    object Ok : AddResult()

    /** Запись отклонена; [reason] — текст для человека. */
    data class Rejected(val reason: String) : AddResult()
}

/**
 * Файловые примитивы, общие для [MemoryStore] и [OwnerProfileStore].
 *
 * Повторяет `LocalSessionRetention.writeAtomic` из `app-mobile`, но живёт в этом
 * модуле: `core-network` не может зависеть от `app-mobile` (направление зависимости
 * обратное), а копия в десять строк дешевле, чем вынос общего модуля.
 */
internal object LocalFileIo {

    private const val TEMP_SUFFIX = ".tmp"

    /** Текст файла или `null`, если файла нет / прочитать нельзя. BOM срезается. */
    fun readText(file: File): String? {
        if (!file.isFile) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return raw.removePrefix("\uFEFF")
    }

    /**
     * Атомарная запись: `.tmp` → `fsync` → `renameTo`.
     *
     * `renameTo` поверх существующего файла на части ФС (и на Windows) отказывает,
     * поэтому при неудаче цель удаляется и rename повторяется. Остаться должен ровно
     * один файл: либо прежний, либо новый.
     *
     * @return `true`, если файл на диске; `false` — записать не удалось (не бросаем:
     * вызывающий решает, что показать).
     */
    fun writeAtomic(target: File, payload: String): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
        val temp = File(parent, target.name + TEMP_SUFFIX)
        val written = runCatching {
            FileOutputStream(temp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                // fsync: без него rename может опередить данные и после сбоя питания
                // на диске окажется пустой файл с правильным именем.
                runCatching { out.fd.sync() }
            }
            true
        }.getOrDefault(false)
        if (!written) {
            runCatching { temp.delete() }
            return false
        }
        if (temp.renameTo(target)) return true
        runCatching { target.delete() }
        if (temp.renameTo(target)) return true
        runCatching { temp.delete() }
        return false
    }

    /** Удалить файл и осиротевший `.tmp`; ошибки глотаются — файла может не быть. */
    fun delete(file: File) {
        runCatching { file.delete() }
        val parent = file.parentFile ?: return
        runCatching { File(parent, file.name + TEMP_SUFFIX).delete() }
    }
}
