package ru.mars.mobile

import android.content.Context
import ru.mars.network.credential.DeviceCredentialStore
import ru.mars.network.repository.PairingRepository

/**
 * P0-фабрика единого контракта выхода из авторизации для всех экранов.
 *
 * Продуктовое правило: logout возможен только из [SettingsActivity], и любой путь выхода
 * (logout или принудительная реавторизация) обязан:
 *  1. синхронно стереть локальные plaintext-данные ДО finish() — [AuthExitCleanup] поверх
 *     полного набора хранилищ (черновики, история поиска музыки, офлайн-кэш сессий,
 *     очередь неотправленных, офлайн-кэши задач, проектов и базы знаний);
 *  2. отозвать credential на сервере best-effort и безусловно очистить локальный
 *     credential — [LogoutFlow].
 *
 * Сетевые контракты не меняются: revoke использует существующий
 * [PairingRepository.revokeCredential] (тот же вызов, что уже применялся в
 * [ChatActivity.leaveChat]). Дублировавшаяся ручная сборка списка хранилищ заменена
 * этой фабрикой, чтобы новый экран не мог забыть часть cleanup-набора.
 */
object AuthExitStores {

    /** Черновики композитора диалогов (plaintext). */
    const val DRAFTS_PREFS = "mars-session-drafts"

    /** История поиска музыки (plaintext). */
    const val MUSIC_HISTORY_PREFS = "mars-music-search-history"

    /** Офлайн-кэш сессий/транскриптов. */
    const val OFFLINE_CACHE_PREFS = "mars-offline-cache"

    /** Офлайн-кэш задач (TodosActivity). */
    const val TODOS_OFFLINE_PREFS = "mars_todos_offline"

    /** Офлайн-кэш метрик проектов (ProjectsActivity). */
    const val PROJECTS_OFFLINE_PREFS = "mars_projects_offline"

    /**
     * Офлайн-кэш базы знаний/скиллов ([ru.mars.network.cache.KnowledgeOfflineCache]).
     * Имя следует конвенции остальных офлайн-кэшей; стирание несозданного файла —
     * безопасный no-op, зато подключение кэша к экрану не сможет обойти гигиену
     * auth-exit и очистку кэша в настройках.
     */
    const val KNOWLEDGE_OFFLINE_PREFS = "mars_knowledge_offline"

    /** Очередь неотправленных сообщений. */
    const val PENDING_QUEUE_PREFS = "mars-pending-messages"

    /**
     * Полный упорядоченный набор приватных prefs-файлов, стираемых на каждом пути выхода
     * из авторизации. Порядок зафиксирован: сначала пользовательские plaintext-данные,
     * затем транспортная очередь.
     */
    val PREFS_FILES: List<String> = listOf(
        DRAFTS_PREFS,
        MUSIC_HISTORY_PREFS,
        OFFLINE_CACHE_PREFS,
        TODOS_OFFLINE_PREFS,
        PROJECTS_OFFLINE_PREFS,
        KNOWLEDGE_OFFLINE_PREFS,
        PENDING_QUEUE_PREFS,
    )

    /**
     * Чистая (JVM-тестируемая) фабрика [AuthExitCleanup]: по одному хранилищу на каждый
     * файл из [PREFS_FILES], построение — через [storeFor].
     */
    fun cleanup(storeFor: (String) -> ClearableLocalStore): AuthExitCleanup =
        AuthExitCleanup(PREFS_FILES.map(storeFor))

    /**
     * Android-вариант фабрики: приватные SharedPreferences на каждый файл из [PREFS_FILES].
     * [overrides] позволяет экрану передать уже живой экземпляр хранилища (например,
     * очередь неотправленных, которую экран использует для отправки), чтобы cleanup и
     * рабочая логика делили один объект.
     */
    @JvmOverloads
    fun cleanup(
        context: Context,
        overrides: Map<String, ClearableLocalStore> = emptyMap(),
    ): AuthExitCleanup = cleanup { name ->
        overrides[name] ?: defaultStore(context, name)
    }

    /**
     * Фабрика [LogoutFlow] с существующим сетевым контрактом revoke
     * ([PairingRepository.revokeCredential]); [revoke] переопределяем для тестов.
     * Семантика неизменна: revoke best-effort, локальный credential чистится безусловно.
     */
    fun logoutFlow(
        store: DeviceCredentialStore,
        baseUrl: String,
        revoke: suspend (deviceId: String, accessToken: String) -> Result<Unit> = { deviceId, accessToken ->
            PairingRepository(baseUrl).revokeCredential(deviceId, accessToken)
        },
    ): LogoutFlow = LogoutFlow(store, revoke)

    /**
     * Хранилище приватного prefs-файла по имени из набора фабрики. internal:
     * «Очистить кэш» в настройках ([SettingsCachePolicy.CACHE_PREFS_FILES]) строит
     * хранилища через ту же фабрику — контракт очистки очереди (свой ключ, не весь
     * файл) не дублируется.
     */
    internal fun defaultStore(context: Context, name: String): ClearableLocalStore {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        // Очередь неотправленных чистится своим ключом (контракт PrefsPendingMessageStore),
        // остальные файлы стираются целиком.
        return if (name == PENDING_QUEUE_PREFS) PrefsPendingMessageStore(prefs) else PrefsClearableStore(prefs)
    }
}
