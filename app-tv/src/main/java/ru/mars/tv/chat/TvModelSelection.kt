package ru.mars.tv.chat

import android.content.SharedPreferences
import ru.mars.network.model.ModelInfo
import ru.mars.network.model.ModelsResponse

/**
 * Выбранная модель для `POST /api/chat/start` на ТВ — паритет мобильного `ModelChoice`.
 * Оба поля берутся из живого каталога `GET /api/models` ([ModelInfo.id] плюс
 * `provider_id` группы) либо из серверных `default_model`/`active_provider` —
 * никогда из захардкоженного на клиенте списка.
 *
 * [explicitPick] как в веб-клиенте: true только когда пользователь САМ выбрал модель
 * в пикере — тогда сервер фиксирует осознанный выбор и не «ремонтирует» сессию
 * обратно к сохранённому дефолту.
 */
data class TvModelChoice(
    val modelId: String,
    val providerId: String?,
    val explicitPick: Boolean,
)

/** Порт персистентности [TvModelChoice]; приложение реализует его через SharedPreferences. */
interface TvModelChoiceStorage {
    fun load(): TvModelChoice?
    fun save(choice: TvModelChoice)
    fun clear()
}

/**
 * [TvModelChoiceStorage] на SharedPreferences. Имя файла и ключи совпадают с мобильным
 * клиентом («mars-model-choice»), чтобы выбор читался одинаково на обоих клиентах одного
 * устройства. Восстановленный выбор всегда считается явным пользовательским.
 */
class TvModelChoicePrefs(private val prefs: SharedPreferences) : TvModelChoiceStorage {

    override fun load(): TvModelChoice? {
        val id = prefs.getString(KEY_MODEL_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val providerId = prefs.getString(KEY_PROVIDER_ID, null)?.takeIf { it.isNotBlank() }
        return TvModelChoice(id, providerId, explicitPick = true)
    }

    override fun save(choice: TvModelChoice) {
        prefs.edit()
            .putString(KEY_MODEL_ID, choice.modelId)
            .putString(KEY_PROVIDER_ID, choice.providerId)
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_MODEL_ID).remove(KEY_PROVIDER_ID).apply()
    }

    companion object {
        /** Файл настроек выбора модели (паритет с мобильным клиентом). */
        const val PREFS_FILE = "mars-model-choice"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROVIDER_ID = "model_provider_id"
    }
}

/** Строка пикера моделей: реальная запись каталога плюс отображаемая подпись. */
data class TvModelPickerEntry(val model: ModelInfo, val label: String)

/**
 * Доменное состояние выбора модели на ТВ (UI -> состояние -> сеть) — зеркало мобильного
 * `ModelSelection`: экран получает каталог через
 * [ru.mars.network.repository.ModelsRepository] и передаёт ответ сюда; класс разрешает,
 * подписывает и хранит выбор и ничего не знает про Android-вьюхи.
 *
 * Намеренно БЕЗ резервного списка моделей: когда каталог недоступен, эффективный выбор
 * остаётся null и `chat/start` уходит без модельных полей — сервер маршрутизирует ход
 * своим сессионным/конфигурационным дефолтом, а не клиентской догадкой.
 */
class TvModelSelection(private val storage: TvModelChoiceStorage) {

    /** Эффективный выбор для следующего `chat/start`: сохранённый пользовательский, иначе серверный дефолт. */
    var current: TvModelChoice? = storage.load()
        private set

    private var lastCatalog: ModelsResponse? = null
    private var sessionModel: TvModelChoice? = null

    /** Серверная модель, привязанная к открытой сессии (StreamStarted, деталь диалога, явный выбор). */
    fun setSessionModel(modelId: String?, providerId: String?) {
        sessionModel = modelId?.takeIf { it.isNotBlank() }
            ?.let { TvModelChoice(it, providerId, explicitPick = false) }
    }

    fun clearSessionModel() {
        sessionModel = null
    }

    /** Строки пикера в серверном порядке, подпись — «имя · провайдер» из реальных данных каталога. */
    fun pickerEntries(catalog: ModelsResponse): List<TvModelPickerEntry> {
        lastCatalog = catalog
        return catalog.models.map { TvModelPickerEntry(it, entryLabel(it)) }
    }

    /** Сохраняет явный выбор из каталога. Для пустого id модели возвращает null. */
    fun select(model: ModelInfo): TvModelChoice? {
        if (model.id.isBlank()) return null
        val choice = TvModelChoice(model.id, model.providerId, explicitPick = true)
        current = choice
        sessionModel = choice
        storage.save(choice)
        return choice
    }

    /**
     * Разрешает эффективный выбор после загрузки каталога: сохранённый пользовательский
     * побеждает; иначе серверный `default_model` принимается с провайдером его строки
     * каталога (фолбэк — `active_provider`). Null, когда каталог не несёт пригодного
     * дефолта: выдуманных имён моделей не существует.
     */
    fun resolveEffective(catalog: ModelsResponse): TvModelChoice? {
        lastCatalog = catalog
        current?.let { return it }
        val defaultId = catalog.defaultModel?.takeIf { it.isNotBlank() } ?: return null
        val row = catalog.models.firstOrNull { it.id == defaultId }
        val choice = TvModelChoice(defaultId, row?.providerId ?: catalog.activeProvider, explicitPick = false)
        current = choice
        return choice
    }

    /** Подпись чипа в шапке композера: имя из каталога, иначе реальный id модели; null — выбор не определён. */
    fun buttonLabel(): String? {
        val choice = wireChoice() ?: return null
        val row = lastCatalog?.models?.firstOrNull { it.id == choice.modelId }
        return row?.name?.takeIf { it.isNotBlank() } ?: choice.modelId
    }

    fun displayedChoice(): TvModelChoice? = wireChoice()

    /**
     * ВЫБОР для провода (`POST /api/chat/start`, модельные поля) и, делегированием, для
     * отображения: серверная модель сессии, если она привязана ([setSessionModel]), иначе
     * локальный [current] (сохранённый пользовательский выбор или принятый серверный
     * дефолт). Null означает «пригодной идентичности нет»: chat/start уходит БЕЗ модельных
     * полей, сервер маршрутизирует ход собственным дефолтом.
     */
    fun wireChoice(): TvModelChoice? = sessionModel ?: current

    private fun entryLabel(model: ModelInfo): String {
        val name = model.name?.takeIf { it.isNotBlank() } ?: model.id
        val provider = model.provider?.takeIf { it.isNotBlank() }
        return if (provider != null && provider != name) "$name · $provider" else name
    }
}
