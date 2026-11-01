package ru.mars.mobile

import android.content.Context
import ru.mars.network.local.MemoryStore
import java.io.File

/**
 * Стартовая память владельца: то, что он сам продиктовал при первом запуске.
 *
 * Зачем отдельный объект. Память на телефоне — это файл в приватном каталоге
 * приложения, и на release-сборке (`run-as` недоступен) снаружи туда не попасть.
 * Просить владельца вбить полтора десятка фактов рукой через экран «Память» —
 * это полчаса работы и гарантированные опечатки. Поэтому список кладётся в
 * `assets/owner-memory-seed.txt` (разделитель тот же, что у [MemoryStore] — «§»)
 * и один раз переписывается в хранилище при первом же запуске.
 *
 * Правила, которые нельзя нарушать:
 * - сажать ОДИН раз: если владелец потом удалил факт, он не должен воскреснуть;
 * - не трогать непустую память: чужие записи не перезаписываются;
 * - отсутствие или порча asset не должны ронять запуск.
 */
object OwnerMemorySeed {

    /** Имя файла в assets. Держать в согласии с самим файлом. */
    private const val ASSET = "owner-memory-seed.txt"

    /** Отдельный маркер: по нему видно, что посадка уже была, даже если память потом стёрли. */
    private const val MARKER = ".owner-seed-applied"

    /**
     * Посадить стартовую память, если она ещё не посажена и хранилище пусто.
     *
     * @return число добавленных записей (0 — ничего не делали).
     */
    fun applyOnce(context: Context, memory: MemoryStore): Int {
        val dir = File(context.filesDir, "memory")
        val marker = File(dir, MARKER)
        // Маркер проверяем первым: если он есть, значит владелец уже получил память
        // и мог что-то удалить сознательно — воскрешать удалённое нельзя.
        if (marker.exists()) return 0
        if (!memory.isEmpty()) {
            // Память уже наполнена иным путём: помечаем и не вмешиваемся.
            runCatching { marker.parentFile?.mkdirs(); marker.writeText("skipped", Charsets.UTF_8) }
            return 0
        }
        val seed = runCatching { readAsset(context) }.getOrNull()
        if (seed.isNullOrBlank()) return 0
        var added = 0
        for (entry in seed.split(MemoryStore.SEPARATOR)) {
            val text = entry.trim()
            if (text.isEmpty()) continue
            // Отказ по лимиту или мусорным символам — не повод падать: пропускаем
            // запись и продолжаем, остальные должны лечь.
            if (memory.add(text) is ru.mars.network.local.AddResult.Ok) added++
        }
        runCatching { marker.parentFile?.mkdirs(); marker.writeText("applied=$added", Charsets.UTF_8) }
        return added
    }

    private fun readAsset(context: Context): String =
        context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
}
