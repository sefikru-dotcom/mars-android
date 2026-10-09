package ru.mars.mobile

import android.content.SharedPreferences

/**
 * Cleanup локальных plaintext-данных при выходе из авторизации (logout и принудительная
 * реавторизация). Приватные prefs-файлы (черновики диалогов, история поиска музыки) хранят
 * набранный пользователем текст открыто; после выхода они не должны быть видны следующей
 * авторизации на этом устройстве.
 *
 * Политика [AuthExitCleanup] чистая (JVM-тестируема): cleanup выполняется ровно один раз
 * ДО finish() экрана и с этого момента запрещает повторное сохранение черновика — onPause
 * закрывающегося экрана не записывает текст обратно в уже очищенное хранилище. Очистка не
 * зависит от best-effort revoke на сервере: она синхронная и локальная, revoke может
 * идти асинхронно и завершиться как угодно.
 */

/** Порт: локальное хранилище plaintext-данных пользователя, очищаемое при выходе из аккаунта. */
interface ClearableLocalStore {
    /** Удаляет всё содержимое хранилища. */
    fun clearAll()
}

/** [ClearableLocalStore] поверх приватного SharedPreferences-файла (commit — синхронно). */
class PrefsClearableStore(private val prefs: SharedPreferences) : ClearableLocalStore {
    override fun clearAll() {
        prefs.edit().clear().commit()
    }
}

/**
 * Одноразовый cleanup всех подключённых хранилищ + флаг, разрешающий ли ещё сохранение
 * черновиков. Падающее хранилище не блокирует остальные (best-effort, как и revoke),
 * но выход из аккаунта в любом случае считается состоявшимся: повторная запись
 * plaintext-данных запрещается после первого запуска.
 */
class AuthExitCleanup(private val stores: List<ClearableLocalStore>) {
    /** Cleanup уже выполнен (экран выходит из авторизации). */
    var hasRun: Boolean = false
        private set

    /** Разрешено ли сохранять черновик композитора; false после [run] (onPause не пишет обратно). */
    val draftPersistenceAllowed: Boolean get() = !hasRun

    /** Выполняет cleanup один раз; true — при первом вызове, false — повторный no-op. */
    fun run(): Boolean {
        if (hasRun) return false
        hasRun = true
        stores.forEach { runCatching { it.clearAll() } }
        return true
    }
}
