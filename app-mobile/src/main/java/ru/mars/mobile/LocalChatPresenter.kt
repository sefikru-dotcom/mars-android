package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import ru.mars.network.local.LocalAgentLoop
import ru.mars.network.local.LocalAttachment
import ru.mars.network.local.LocalChatClient
import ru.mars.network.local.LocalChatEvent
import ru.mars.network.local.LocalMessage
import ru.mars.network.local.LocalModelInfo
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.SettingsSource
import ru.mars.network.local.LocalRole
import ru.mars.network.local.LocalTurnSettings
import ru.mars.network.local.LocalToolCall
import ru.mars.network.local.LocalToolExecutor
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema
import ru.mars.network.local.LocalTurnMetrics
import ru.mars.network.local.LocalUsage
import java.util.Locale
import java.util.UUID

/** Состояние карточки инструмента в ленте. */
enum class LocalToolPhase {
    /** Модель попросила вызов; исполнение ещё не началось. */
    PENDING,

    /** Инструмент выполняется (или ждёт подтверждения человека). */
    RUNNING,

    /** Выполнен успешно. */
    DONE,

    /** Выполнен с ошибкой либо отклонён. */
    FAILED,
}

/** Карточка инструмента: имя, аргументы, результат. Свёрнута по умолчанию. */
data class LocalToolCard(
    val callId: String,
    val name: String,
    val argumentsJson: String,
    val phase: LocalToolPhase = LocalToolPhase.PENDING,
    val preview: String? = null,
    /**
     * Файл картинки, которую инструмент положил на диск (`generate_image`).
     *
     * Путь, а не байты: лента рисует его через `ImageView`, а не из истории. Сами байты
     * лежат в кэше, копия — во вложении истории (см. [LocalChatActivity.persistGenerated]).
     * null — инструмент картинку не возвращал (все остальные) или ответ её не содержал.
     */
    val imagePath: String? = null,
)

/** Строка ленты: сообщение или карточка инструмента. */
data class LocalChatItem(
    val id: String,
    val role: LocalRole,
    val text: String = "",
    val streaming: Boolean = false,
    val tool: LocalToolCard? = null,
    /**
     * Размышления reasoning-модели. Не ответ, а пометка «модель думает»: у
     * `deepseek-v4.1-flash-1017` и подобных весь бюджет токенов может уйти сюда, и без
     * этой строки человек видел пустой пузырь и решал, что ответ потерялся.
     */
    val thinking: String = "",
    /**
     * Расход хода по последнему кадру `usage` провайдера: сумма по шагам ЭТОГО хода.
     *
     * Нули — не «ноль токенов», а «провайдер не сообщил»: `usage` отдают не все
     * (см. [LocalUsage]), и выдумывать цифры ради красивого футера нельзя. Именно поэтому
     * все три поля по умолчанию нулевые, а футер рисуется только по непустым.
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    /**
     * Ответ оборван: поток кончился ошибкой или кнопкой «Стоп», но текст уже был.
     *
     * Флаг ставится только когда человек реально что-то увидел: текст сохраняется целиком,
     * ошибка его НЕ затирает (иначе «Стоп» выглядел бы как потеря ответа). Пустой ответ
     * при обрыве не помечается, а удаляется — помечать нечего.
     */
    val interrupted: Boolean = false,
    /**
     * Ответ упёрся в лимит выходных токенов модели: `finish_reason = length`.
     *
     * Единственный честный признак обрезки. Угадывать её по длине текста нельзя: у
     * короткого ответа и у обрезанного текста разницы в длине нет, а `stop`/`tool_calls`
     * означают, что модель закончила сама. Выставляется из
     * [ru.mars.network.local.LocalChatEvent.AssistantFinished.finishReason]; `null`
     * (провайдер причину не прислал) оставляет `false` — «неизвестно» здесь честнее
     * ложного «обрезан».
     */
    val truncated: Boolean = false,
    /**
     * Время до первого символа ответа, мс; 0 — не измеряли (ход ещё идёт или ответа нет).
     *
     * `ttft` измеряется честно: от старта хода до первой дельты текста или размышления.
     * В отличие от «токенов в секунду» это не оценка: момент старта и момент первого
     * байта известны точно, а вот число токенов в секунду из `usage` посчитать нельзя —
     * провайдер отдаёт их одним кадром в конце, без отметок времени.
     */
    val ttftMs: Long = 0,
)

/**
 * Строка футера хода — единственное место, где решается, что показать под ответом.
 *
 * Правила (те же, что у PocketPal `AssistantTurnFooter`): часть рисуется только при
 * непустом значении, ноль — не значение, нет ни одной части — футера нет вовсе.
 * Никакого ttft/токов-в-секунду: этого провайдер не сообщает, а считать самим —
 * значит показывать выдуманные числа.
 *
 * Чистая функция без `android.*`: покрывается JVM-тестом [LocalTurnFooterTest].
 */
object LocalTurnFooter {

    /** Приписка к оборванному ответу. */
    const val INTERRUPTED = "оборван"

    /**
     * Приписка к ответу, упёршемуся в лимит выходных токенов.
     *
     * Отдельное слово, а не общее «оборван»: обрыв — это сбой или «Стоп», а обрезка —
     * штатный предел модели, и человеку важно различать «связь упала» и «модель
     * договорила не всё».
     */
    const val TRUNCATED = "обрезан"

    /** Слово после числа токенов: «1.2k ток». */
    const val TOKENS = "ток"

    /**
     * Строка футера или null, если показывать нечего.
     *
     * [windowTokens] — размер окна контекста модели; null — окно неизвестно, и тогда
     * пара «запрос/окно» не рисуется (одно число без знаменателя читалось бы как «сколько
     * осталось»).
     *
     * Порядок частей: токены → запрос/окно → ttft → [INTERRUPTED] → [TRUNCATED].
     */
    fun line(
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        totalTokens: Int = 0,
        windowTokens: Int? = null,
        interrupted: Boolean = false,
        truncated: Boolean = false,
        ttftMs: Long = 0,
    ): String? {
        val parts = ArrayList<String>(5)
        // «Всего» — сумма шагов хода. Если провайдер прислал только ответ (бывает у
        // потоковых API без total), показываем хотя бы её: это тоже факт, а не догадка.
        val total = if (totalTokens > 0) totalTokens else completionTokens
        if (total > 0) parts += "${LocalContextPolicy.formatTokens(total)} $TOKENS"
        if (promptTokens > 0 && windowTokens != null && windowTokens > 0) {
            parts += "${LocalContextPolicy.formatTokens(promptTokens)}/" +
                LocalContextPolicy.formatTokens(windowTokens)
        }
        // Задержка первого символа: «0.8с» короче и читается лучше, чем «800 мс».
        if (ttftMs > 0) parts += formatTtft(ttftMs)
        if (interrupted) parts += INTERRUPTED
        // Обрезка — после обрыва: если случилось и то и другое, порядок фиксирован
        // (сбой мог прийти уже после `length`), и человек видит обе причины.
        if (truncated) parts += TRUNCATED
        return if (parts.isEmpty()) null else parts.joinToString(SEPARATOR)
    }

    /** Строка футера для элемента ленты. */
    fun line(item: LocalChatItem, windowTokens: Int? = null): String? = line(
        promptTokens = item.promptTokens,
        completionTokens = item.completionTokens,
        totalTokens = item.totalTokens,
        windowTokens = windowTokens,
        interrupted = item.interrupted,
        truncated = item.truncated,
        ttftMs = item.ttftMs,
    )

    /** Подпись задержки первого символа: до секунды — миллисекунды, дальше — секунды. */
    fun formatTtft(ms: Long): String = if (ms < 1000) {
        "первый символ ${ms}мс"
    } else {
        String.format(java.util.Locale.US, "первый символ %.1fс", ms / 1000.0)
    }

    /** Разделитель частей: точка-в-середине, как в подписях остального экрана. */
    const val SEPARATOR = " · "
}

/** Состояние экрана диалога. Один источник правды для рендера. */
data class LocalChatUiState(
    val items: List<LocalChatItem> = emptyList(),
    val streaming: Boolean = false,
    /**
     * Фаза остановки: «Стоп» нажат, цикл ещё выходит и финализирует строку.
     *
     * Отдельное поле, потому что [streaming] не различает «отвечает» и «доезжает до
     * финиша»: в этой фазе экран обязан показать «Останавливаю…» и заблокировать отправку,
     * хотя дельты уже не принимаются.
     */
    val stopping: Boolean = false,
    /** Нейтральная подсказка (не настроен провайдер, нет инструментов). */
    val notice: String? = null,
    /** Ошибка хода; показывается плашкой, не заменяет ленту. */
    val error: String? = null,
    /** Текущий расход диалога: сумма usage по всем шагам. Нули — «провайдер не сообщил». */
    val usage: LocalUsage = LocalUsage(),
    /**
     * Заполнение окна: `promptTokens` последнего шага / `contextWindow` модели.
     * null — показывать нечего (окно неизвестно или замера ещё нет): фейковый 0%
     * не рисуется, см. [LocalContextPolicy].
     */
    val context: LocalContextView? = null,
    /** Модель активного профиля (для кнопки в шапке и выбора). */
    val model: String? = null,
    /** `reasoning_effort` активного профиля; null — «Авто» (поле не отправляется). */
    val reasoningEffort: String? = null,
    /** Показывать ли переключатель reasoning: только если модель его принимает. */
    val supportsReasoning: Boolean = false,
    /**
     * Параметры сэмпсинга текущего хода.
     *
     * Живут в состоянии, а не в экране: их читает и шторка, и шапка (подпись «свои
     * настройки»), и тело запроса — одно место правды. `null` в полях означает
     * «провайдер сам», а не ноль.
     */
    val turnSettings: LocalTurnSettings = LocalTurnSettings(),
    /** Есть ли в настройках хоть одно значение, заданное человеком (для звёздочки в шапке). */
    val turnSettingsCustom: Boolean = false,
)

/**
 * Презентер диалога: история, стрим, карточки инструментов.
 *
 * Ни одного импорта `android.*` — состояние отдаётся [StateFlow], экран только рисует.
 * Работа с ядром идёт **только через интерфейсы** (`LocalChatClient`,
 * `LocalToolExecutor`, `LocalAgentLoop`), поэтому транспорт и инструменты заменяемы,
 * а презентер проверяется на JVM фейками (см. `LocalChatPresenterTest`, W4).
 *
 * Подтверждение деструктивных инструментов сюда не входит: оно живёт в обёртке
 * [LocalToolExecutor], которую собирает экран (`LocalChatToolSource.executor`).
 * Презентер лишь исполняет то, что ему дали, — и потому «ToolRequested без
 * подтверждения» физически не может выполниться в обход экрана.
 *
 * Расход считается в два разных числа, и путать их нельзя:
 *  - [LocalChatUiState.usage] — сумма `totalTokens` по всем шагам диалога (кумулятивно);
 *  - [LocalChatUiState.context] — заполнение окна по `promptTokens` ТОЛЬКО последнего шага.
 *    Сумма `promptTokens` по шагам дала бы удвоенный процент: каждый следующий шаг уже
 *    включает всю предыдущую историю.
 */
class LocalChatPresenter(
    private val client: LocalChatClient,
    baseExecutor: LocalToolExecutor,
    /** Сохранение истории (файл на диске). Вызывается после каждого хода. */
    private val onPersist: (List<LocalMessage>) -> Unit = {},
    private val newId: () -> String = { "m-" + UUID.randomUUID().toString().replace("-", "").take(10) },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxIterations: Int = LocalAgentLoop.MAX_ITERATIONS,
    /**
     * Сохранение расхода диалога в мету сессии.
     *
     * Отдельный колбэк, а не расширение [onPersist]: тот принимает только сообщения, и
     * менять его сигнатуру значило бы ломать `LocalChatActivity.persist` и
     * `LocalSessionStore.save` (ими владеет W3, не W2). Зовётся один раз за ход, с
     * накопленным итогом, на IO-потоке, и только если провайдер реально прислал `usage`.
     */
    private val onUsagePersist: (LocalUsage) -> Unit = {},
    /**
     * Системный промпт из памяти: профиль владельца + запомненные факты.
     *
     * Собирается на каждый ход, а не один раз: человек мог только что добавить факт
     * на экране памяти, и следующий же запрос обязан его видеть. Пустая строка —
     * памяти нет, системное сообщение не добавляется вовсе.
     */
    private val systemPrompt: () -> String = { "" },
    /**
     * Записать изменённый активный профиль (модель, `reasoning_effort`) и вернуть
     * сохранённый — окно контекста могло уточниться. null — отказ/профиля нет: тогда
     * презентер оставляет профиль у себя и ничего не сохраняет.
     */
    private val onProfileChange: (LocalProviderProfile) -> LocalProviderProfile? = { null },
    /**
     * Метаданные модели из каталога `/models`: окно контекста и поддержка reasoning.
     * null — модель в каталоге не найдена (или каталог ещё не загружен).
     */
    private val modelInfo: (String) -> LocalModelInfo? = { null },
    /**
     * Вернуть ввод в поле: ход упал ДО первого токена, и удалять текст человека молча
     * нельзя — он бы набирал вопрос заново.
     *
     * Экран подставляет и текст, и вложения (чипы файлов): провайдер мог не принять
     * картинку, а файл у человека уже выбран. Пара «сообщение + пустой ответ» к этому
     * моменту удалена из ленты и из истории — возврат ввода единственный след хода.
     *
     * Значение по умолчанию — [NO_UNDO], а не пустая лямбда нарочно: если экран возврат
     * ввода не подключил, пару НЕ удаляем. Иначе текст вопроса исчез бы из ленты молча —
     * это хуже, чем оставить сообщение без ответа.
     */
    private val onUndoInput: (text: String, attachments: List<LocalAttachment>) -> Unit = NO_UNDO,
) {

    private val _state = MutableStateFlow(LocalChatUiState())
    val state: StateFlow<LocalChatUiState> = _state.asStateFlow()

    /** История в формате провайдера (включая TOOL-сообщения) — её и сохраняем. */
    private var history: List<LocalMessage> = emptyList()

    /** Результаты инструментов текущего хода: нужны, чтобы TOOL-сообщение несло контент. */
    private val toolResults = HashMap<String, LocalToolResult>()

    /** Активный профиль: из него берутся модель, окно контекста и reasoning. */
    private var activeProfile: LocalProviderProfile? = null

    /** Накопленный расход диалога — сумма usage по шагам. */
    private var usageTotal: LocalUsage = LocalUsage()

    /**
     * `promptTokens` последнего шага — единственный честный источник процента заполнения.
     * Хранится отдельно от [usageTotal]: сумма promptTokens по шагам удвоила бы процент.
     */
    private var lastStepPromptTokens: Int = 0

    /**
     * Фаза 1 отмены: «Стоп» нажат, новые дельты уже не принимаются.
     *
     * Флаг, а не сравнение со [LocalChatUiState.stopping]: его читает горячий путь
     * `handle`, и лишняя аллокация состояния там ни к чему.
     */
    @Volatile
    private var cancelRequested: Boolean = false

    /**
     * Job текущего хода: по нему «Стоп» просит цикл выйти.
     *
     * null — цикла нет. Именно он отличает «стоп во время ответа» от «стоп нечего
     * останавливать»: во втором случае финализировать надо сразу.
     */
    @Volatile
    private var turnJob: Job? = null

    /** Id строки ассистента текущего хода — её и помечаем оборванной. */
    @Volatile
    private var turnAssistantId: String = ""

    /**
     * Расход ТЕКУЩЕГО хода — сумма шагов, а не диалога: из неё рисуется футер строки.
     * Нули означают «провайдер не сообщил», и футер тогда не рисуется вовсе.
     */
    private var turnUsage: LocalUsage = LocalUsage()

    /**
     * `finish_reason` последнего завершённого шага текущего хода; `null` — провайдер
     * причину не сообщил (или шаг ещё не закрыт).
     *
     * Хранится за ходом, а не за кадром: [LocalTurnMetrics] собирается один раз, когда
     * шаг уже закрыт, и причина ПОСЛЕДНЕГО шага — единственная, которая относится к
     * ответу (у промежуточных шагов с инструментами это `tool_calls`).
     */
    private var turnFinishReason: String? = null

    /**
     * Время до первого символа текущего хода, мс; 0 — не измерено.
     *
     * Дублирует [LocalChatItem.ttftMs] намеренно: метрики собираются в момент закрытия
     * шага, а читать их из ленты значило бы зависеть от того, отрисован ли элемент.
     */
    private var turnTtftMs: Long = 0L

    /**
     * Обёртка исполнения: запоминает полный результат для истории и пропускает вызов
     * дальше. Ошибку не глотает — решение об ошибке принимает нижестоящий исполнитель.
     */
    val executor: LocalToolExecutor = LocalToolExecutor { call ->
        val result = baseExecutor.execute(call)
        toolResults[call.id] = result
        result
    }

    /** Текущая история (копия) — для сохранения/диагностики. */
    fun historySnapshot(): List<LocalMessage> = history

    /**
     * Сообщить активный профиль (при старте экрана и после возврата из настроек).
     * Без этого модель и пилюля контекста неизвестны до первого сообщения.
     */
    fun bindProfile(profile: LocalProviderProfile?) {
        val previous = activeProfile
        activeProfile = profile
        // Смена модели — замер заполнения относился к окну прежней модели: показывать его
        // для новой значит показывать чужой процент. Расход диалога (`usage`) не трогаем.
        if (profile != null && previous != null && previous.model != profile.model) {
            lastStepPromptTokens = 0
        }
        _state.value = _state.value.copy(
            model = profile?.model,
            reasoningEffort = profile?.reasoningEffort,
            supportsReasoning = supportsReasoning(profile),
        )
        publishUsage()
    }

    /**
     * Переключить модель активного профиля.
     *
     * Контекст сбрасывается: замер `promptTokens` относился к окну прежней модели, и
     * показывать его для новой — значит показывать чужой процент. Расход диалога
     * (`usage`) сохраняется: это уже потраченные токены.
     *
     * Игнорируется, если профиль ещё не известен ([bindProfile] не звался и хода не было)
     * или строка пустая.
     */
    fun setModel(id: String) {
        val model = id.trim()
        if (model.isEmpty()) return
        val current = activeProfile ?: return
        if (current.model == model) return
        // Окно новой модели до ответа каталога неизвестно: null, а не окно прежней.
        applyProfile(current.copy(model = model, contextWindow = null))
    }

    /**
     * Переключить `reasoning_effort` активного профиля. null — «Авто» (поле в запрос не
     * отправляется). Значения вне `low/medium/high` игнорируются: провайдер принимает
     * только их, остальное проигнорировал бы молча — переключатель врал бы.
     */
    fun setReasoning(effort: String?) {
        val current = activeProfile ?: return
        // null — это «Авто». Пустая строка и мусор — не «Авто», а опечатка вызывающего:
        // молча сбрасывать уровень в «Авто» из-за опечатки нельзя.
        val normalized = if (effort == null) {
            null
        } else {
            effort.trim().lowercase(Locale.ROOT).takeIf { it in REASONING_EFFORTS } ?: return
        }
        if (normalized == current.reasoningEffort) return
        applyProfile(current.copy(reasoningEffort = normalized))
    }

    /**
     * Заменить параметры хода целиком (результат шторки «Сохранить»).
     *
     * Уровень reasoning синхронизируется с профилем, а не остаётся только в настройках
     * хода: его отправляет транспорт как поле профиля, и две расходящиеся копии дали бы
     * «в шторке high, а уходит medium». Приоритет у шторки — человек только что правил
     * именно её.
     */
    fun setTurnSettings(settings: LocalTurnSettings) {
        _state.value = _state.value.copy(
            turnSettings = settings,
            turnSettingsCustom = hasCustomValues(settings),
        )
        settings.reasoningEffort?.let { effort -> setReasoning(effort) }
    }

    /**
     * Сбросить параметры хода к дефолтам модели/профиля.
     *
     * Не «все поля в null», а именно [LocalTurnSettings.defaultsFor]: у модели может быть
     * известный потолок ответа из каталога, и после сброса он должен вернуться, иначе
     * человек получил бы «провайдер сам» там, где модель честно сообщила предел.
     */
    fun resetTurnSettings(defaults: LocalTurnSettings) {
        setTurnSettings(defaults)
    }

    /** Загрузить снимок параметров из меты сессии; `null` — снимка нет, оставить как есть. */
    fun restoreTurnSettings(settings: LocalTurnSettings?) {
        if (settings == null) return
        _state.value = _state.value.copy(
            turnSettings = settings,
            turnSettingsCustom = hasCustomValues(settings),
        )
    }

    /** Есть ли хоть одно значение, заданное не «по умолчанию» — для звёздочки в шапке. */
    private fun hasCustomValues(settings: LocalTurnSettings): Boolean =
        settings.source == SettingsSource.SESSION ||
            settings.stop.isNotEmpty()

    /**
     * Загрузить диалог с диска. Лента собирается из тех же сообщений, что ушли бы провайдеру.
     *
     * [usage] — сохранённый расход диалога из меты сессии (W3). Из него НЕ выводится
     * процент заполнения: в мете лежит сумма prompt по шагам, а не замер последнего шага,
     * поэтому пилюля остаётся скрытой до первого хода — честно, а не «примерно».
     *
     * Футер ответов при этом восстанавливается целиком: `ttft`, токены, «оборван» и
     * «обрезан» берутся из [LocalMessage.metrics] каждого сообщения ([buildItems]), поэтому
     * строка под ответом после перезапуска та же, что была до него. Сообщения без метрик
     * (файл волны 1) дают пустой футер — как и раньше.
     */
    fun restore(messages: List<LocalMessage>, usage: LocalUsage? = null) {
        history = messages
        val items = buildItems(messages)
        if (usage != null && usage.isMeasured) {
            usageTotal = usage
            lastStepPromptTokens = 0
            _state.value = _state.value.copy(items = items, usage = usageTotal, context = null, error = null)
            return
        }
        // Расход, модель и замер не трогаем: restore зовётся и на обновление экрана,
        // и стирание пилюли при каждом возврате было бы регрессом.
        _state.value = _state.value.copy(items = items, error = null)
    }

    /**
     * Отправить сообщение и довести ход до конца (включая шаги с инструментами).
     *
     * Отмена корутины — это и есть кнопка «Стоп»: [cancelLocally] глушит приём дельт,
     * цикл выходит, и ТОЛЬКО в `finally` строка финализируется (см. [finishTurn]). Так
     * частичный текст не может исчезнуть: его не затирает ни ошибка, ни отмена.
     *
     * Исключений наружу не бывает: сбой транспорта ложится в [LocalChatUiState.error].
     */
    suspend fun send(
        profile: LocalProviderProfile,
        apiKey: String,
        tools: List<LocalToolSchema>,
        text: String,
        attachments: List<LocalAttachment> = emptyList(),
        /**
         * Показывать ли реплику в ленте.
         *
         * `false` — служебная реплика: она уходит модели ([LocalMessage] с ролью USER
         * всё равно попадает в историю, иначе провайдер не поймёт, что от него хотят),
         * но в ленте не рисуется. Так выглядит «Продолжить»: человек нажал действие,
         * а не написал сообщение, и собственная реплика в ленте была бы шумом.
         */
        showInFeed: Boolean = true,
    ) {
        val body = text.trim()
        if (body.isEmpty() || _state.value.streaming) return

        // Профиль хода — источник модели, окна и reasoning для пилюли: экран мог не звать
        // bindProfile после смены профиля в настройках.
        activeProfile = profile
        // Единственная строка, по которой видно модель ЗАПРОСА (а не подписи чипа):
        // `adb logcat -s MarsModel`. Клиент в core-network собирает тело сам и `android.util.Log`
        // там недоступен, поэтому логируется здесь — за шаг до `LocalAgentLoop.run(profile…)`.
        MarsModelLog.log("request model=${profile.model} base=${profile.baseUrl}")
        _state.value = _state.value.copy(
            model = profile.model,
            reasoningEffort = profile.reasoningEffort,
            supportsReasoning = supportsReasoning(profile),
        )
        publishUsage()

        val user = LocalMessage(
            id = newId(),
            role = LocalRole.USER,
            text = body,
            createdAt = now(),
            // Вложения едут в исходящем сообщении: транспорт ядра собирает content
            // массивом частей (картинка как image_url, текстовый файл как текст).
            attachments = attachments,
        )
        history = history + user
        // Память — первым сообщением хода: профиль владельца и запомненные факты.
        // Роль SYSTEM уже поддерживается ядром и не рисуется в ленте (upsert её пропускает),
        // поэтому человек видит обычный диалог, а модель — свои знания о нём.
        history = withMemory(history)
        toolResults.clear()

        val assistantId = newId()
        _state.value = _state.value.copy(
            items = _state.value.items +
                (listOfNotNull(
                    LocalChatItem(user.id, LocalRole.USER, user.text).takeIf { showInFeed },
                ) +
                    LocalChatItem(assistantId, LocalRole.ASSISTANT, text = "", streaming = true)),
            streaming = true,
            // Новый ход снимает фазу остановки прошлого: иначе экран остался бы
            // заблокированным «Останавливаю…» после удачного следующего вопроса.
            stopping = false,
            notice = null,
            error = null,
        )

        var partial = ""
        var committed = false
        /** Был ли хоть один байт ответа (текст или reasoning) — от этого зависит undo. */
        var produced = false
        var failed = false
        // Задержка первого символа: старт хода фиксируется здесь, первый кадр — в handle().
        // Момент старта берётся до первого сетевого вызова, иначе в ttft попадёт
        // подготовка инструментов, а не ожидание ответа модели.
        var ttft = 0L
        val turnStartedAt = now()
        turnUsage = LocalUsage()
        turnFinishReason = null
        turnTtftMs = 0L
        cancelRequested = false
        turnAssistantId = assistantId
        turnJob = currentCoroutineContext()[Job]
        try {
            LocalAgentLoop(client, executor, maxIterations)
                .run(profile, apiKey, history, tools, _state.value.turnSettings)
                // Сеть и разбор SSE — на IO: экран не должен ловить
                // NetworkOnMainThreadException из чужой реализации клиента.
                .flowOn(Dispatchers.IO)
                .collect { event ->
                    val handled = handle(event, assistantId, partial)
                    // Первый реальный символ ответа: фиксируем один раз за ход.
                    if (ttft == 0L && handled.produced) {
                        ttft = (now() - turnStartedAt).coerceAtLeast(0L)
                        turnTtftMs = ttft
                        updateItem(assistantId) { it.copy(ttftMs = ttft) }
                    }
                    partial = handled.text
                    committed = committed || handled.committed
                    produced = produced || handled.produced
                    failed = failed || handled.failed
                }
        } catch (cancel: CancellationException) {
            // «Стоп» — решение человека, а не сбой: ввод не возвращаем (он никуда не
            // пропал), а строку финализирует finally — уже после выхода из цикла.
            cancelRequested = true
            throw cancel
        } catch (failure: Exception) {
            failed = true
            // Раньше исключение транспорта вылетало из send в scope экрана: человек
            // оставался с вечным «Печатает…» и без причины. Теперь это та же плашка,
            // что и у LocalChatEvent.Error.
            if (_state.value.error == null) {
                _state.value = _state.value.copy(error = errorText(failure))
            }
        } finally {
            // Фаза 3: цикл вышел — только теперь трогаем строку. Ниже — лента/история,
            // затем диск: сохранение обязано случиться и при отмене.
            finishTurn(
                assistantId = assistantId,
                userId = user.id,
                body = body,
                attachments = attachments,
                partial = partial,
                committed = committed,
                produced = produced,
                aborted = cancelRequested || failed,
                failed = failed,
            )
            val snapshot = history
            val spend = usageTotal
            // NonCancellable: при «Стоп» контекст уже отменён, а запись на диск
            // должна состояться — иначе ход терялся бы ровно в момент отмены.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { onPersist(snapshot) }
                // Расход — только если провайдер его прислал: записать нули значило бы
                // утверждать «ход ничего не стоил».
                if (spend.isMeasured) runCatching { onUsagePersist(spend) }
            }
            cancelRequested = false
            turnJob = null
            turnAssistantId = ""
        }
    }

    /**
     * Кнопка «Стоп», фаза 1 из 3.
     *
     * (1) Здесь ход помечается останавливаемым ([LocalChatUiState.stopping]) и глохнет
     * приём дельт. (2) Цикл просим выйти. (3) Финализацию делает `finally` в [send] —
     * то есть строго после выхода из цикла и только он. Ждать в этом методе нельзя:
     * он зовётся с UI-потока, а ход идёт в scope экрана; «дождаться» выражено тем, что
     * строку закрывает сам цикл, а не тот, кто нажал кнопку.
     */
    fun cancelLocally() {
        val job = turnJob
        if (job == null || !_state.value.streaming) {
            // Хода нет — останавливать нечего. Убираем возможный пустой хвост и снимаем
            // «Останавливаю…», если оно осталось от прошлого хода.
            cancelRequested = false
            finalizeStreaming("")
            if (_state.value.stopping) _state.value = _state.value.copy(stopping = false)
            return
        }
        cancelRequested = true
        _state.value = _state.value.copy(stopping = true)
        job.cancel()
        // Гонка: ход мог финализироваться между чтением job и записью stopping, и тогда
        // снимать «Останавливаю…» уже некому. Проверяем, что ход всё ещё тот же.
        if (turnJob !== job) {
            cancelRequested = false
            _state.value = _state.value.copy(stopping = false)
        }
    }

    /**
     * Фаза 3: привести ленту и историю к честному виду после выхода из цикла.
     *
     * Единственное место, где решается судьба строки ответа:
     *  - текст был, поток оборвался → строка остаётся, помечается `interrupted`
     *    (текст НЕ затирается ошибкой: «Стоп» не должен выглядеть как потеря ответа);
     *  - ни текста, ни reasoning → строки нет вовсе, помечать нечего;
     *  - сбой до первого токена → уходит и пара «сообщение человека + пустой ответ»,
     *    в историю она не пишется, а ввод возвращается экрану через [onUndoInput].
     *
     * Осознанный стоп до первого токена ввод НЕ возвращает: человек сам остановился,
     * а его сообщение остаётся в ленте — прятать его значило бы делать вид, что
     * вопроса не было.
     */
    private fun finishTurn(
        assistantId: String,
        userId: String,
        body: String,
        attachments: List<LocalAttachment>,
        partial: String,
        committed: Boolean,
        produced: Boolean,
        aborted: Boolean,
        failed: Boolean,
    ) {
        val row = _state.value.items.firstOrNull { it.id == assistantId }
        val hasText = partial.isNotBlank() || !row?.text.isNullOrBlank()
        val hasOutput = produced || hasText || !row?.thinking.isNullOrBlank()

        if (!committed && !hasText) {
            // Ответа нет вовсе: пустой пузырь — не ответ, его место в ленте не нужно.
            _state.value = _state.value.copy(
                items = _state.value.items.filterNot { it.id == assistantId },
            )
            if (!hasOutput && failed) {
                // Сбой до первого байта: ход не состоялся. Если экран подключил возврат
                // ввода — убираем и сообщение человека, и его след в истории (иначе
                // следующий запрос отправил бы провайдеру вопрос, которого человек
                // уже не видит), а текст возвращаем в поле. Без колбэка — оставляем
                // сообщение: молча стереть набранное человеком нельзя.
                if (onUndoInput !== NO_UNDO) {
                    _state.value = _state.value.copy(
                        items = _state.value.items.filterNot { it.id == userId },
                    )
                    history = history.filterNot { it.id == userId }
                    runCatching { onUndoInput(body, attachments) }
                }
            }
        } else if (!committed && hasText) {
            // Отмена/обрыв после первого токена: то, что человек уже видел, обязано
            // остаться и в истории, иначе после перезапуска ответ исчезнет.
            history = history + LocalMessage(
                id = newId(),
                role = LocalRole.ASSISTANT,
                text = partial,
                createdAt = now(),
                // Оборванный ответ тоже несёт метрики: «оборван», ttft и токены
                // обязаны пережить перезапуск, иначе футер после него менялся бы.
                metrics = turnMetrics(interrupted = aborted),
            )
            if (aborted) updateItem(assistantId) { it.copy(interrupted = true) }
        }
        // Завершённый шаг (committed) не помечаем даже при обрыве следующего: этот ответ
        // полный, и приписка «оборван» на нём была бы ложью.
        finalizeStreaming(assistantId)
        if (_state.value.stopping) _state.value = _state.value.copy(stopping = false)
    }

    /**
     * Метрики хода для истории — ровно то, что экран показал в футере.
     *
     * Собираются из уже посчитанного ([turnUsage], [turnTtftMs], [turnFinishReason]),
     * а не пересчитываются: второй счётчик токенов разошёлся бы с первым, и футер
     * после перезапуска показывал бы другое число, чем до него.
     *
     * `null`, когда шаг не измерялся ([LocalTurnMetrics.isMeasured] == false): писать
     * нули значило бы утверждать «шаг ничего не стоил», а рисовать пустой футер —
     * обещать цифры, которых нет. По той же причине `truncated` без токенов и ttft в
     * историю не попадает (политика §1 контракта волны 12).
     */
    private fun turnMetrics(interrupted: Boolean): LocalTurnMetrics? {
        val metrics = LocalTurnMetrics(
            promptTokens = turnUsage.promptTokens,
            completionTokens = turnUsage.completionTokens,
            totalTokens = turnUsage.totalTokens,
            ttftMs = turnTtftMs,
            interrupted = interrupted,
            truncated = turnFinishReason == LocalTurnMetrics.REASON_LENGTH,
            finishReason = turnFinishReason,
        )
        return metrics.takeIf { it.isMeasured }
    }

    /** Человеческий текст сбоя транспорта: без стектрейса и без «null» на экране. */
    private fun errorText(failure: Throwable): String {
        val reason = failure.message?.trim()?.takeIf { it.isNotEmpty() }
        return if (reason != null) "Соединение прервано: $reason" else "Соединение прервано"
    }

    /** Очистить ошибку (после показа плашки). */
    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    /** Показать/снять нейтральную подсказку экрана. */
    fun setNotice(notice: String?) {
        if (_state.value.notice != notice) _state.value = _state.value.copy(notice = notice)
    }

    /**
     * Отрезать историю до сообщения [messageId]: правка своей реплики.
     *
     * Что удаляется и почему именно так:
     *  - сама правимая реплика человека — потому что её текст заменяется новым, а
     *    добавлять вторую реплику поверх значило бы отправить провайдеру два вопроса;
     *  - ВСЁ после неё, без исключений. Соблазн оставить ответ на прежнюю формулировку
     *    («пусть будет, вдруг пригодится») даёт модели противоречивую историю: правленый
     *    вопрос и ответ на старый. Единственный честный ход — как у ChatGPT: переспросить
     *    с этого места, а прежняя ветка остаётся в памяти экрана до перезагрузки.
     *
     * Фильтрация идёт по позиции, а не по id: id ответов в истории могут не совпадать с
     * id строк ленты (пустой ответ удаляется из ленты, но не всегда из истории), поэтому
     * индекс в [history] — единственный надёжный якорь. Если реплики в истории нет, метод
     * ничего не делает и возвращает `false`: экран в этом случае не должен обещать отправку.
     *
     * @return `true` — история урезана до позиции реплики (сама реплика исключена),
     *   `false` — такой реплики в истории нет.
     */
    fun truncateFrom(messageId: String): Boolean {
        // Ход идёт — резать нечего: лента в этот момент описывает незавершённую пару.
        if (_state.value.streaming) return false
        val index = history.indexOfFirst { it.id == messageId }
        if (index < 0) return false
        history = history.subList(0, index).toList()
        // Лента пересобирается из новой истории целиком: иначе на экране остались бы
        // строки, которых в истории уже нет, и следующая правка искала бы их зря.
        _state.value = _state.value.copy(
            items = buildItems(history),
            error = null,
            notice = null,
        )
        runCatching { onPersist(history) }
        return true
    }

    /**
     * Результат обработки кадра.
     *
     * [produced] — кадр принёс хоть байт ответа (текст или reasoning): по этому признаку
     * решается, возвращать ли ввод при сбое до первого токена.
     * [failed] — кадр сообщил об ошибке потока.
     */
    private data class Handled(
        val text: String,
        val committed: Boolean,
        val produced: Boolean = false,
        val failed: Boolean = false,
    )

    private fun handle(event: LocalChatEvent, assistantId: String, partial: String): Handled {
        // Фаза 1 отмены: новые дельты уже не принимаем. Проверка ДО обработки, а не
        // после: то, что прилетело в гонке с «Стоп», человек видеть не должен — иначе
        // текст продолжал бы расти после остановки.
        if (cancelRequested &&
            (event is LocalChatEvent.Delta || event is LocalChatEvent.ReasoningDelta)
        ) {
            return Handled(partial, false)
        }
        when (event) {
            is LocalChatEvent.Delta -> {
                val next = partial + event.text
                updateItem(assistantId) { it.copy(text = next, streaming = true) }
                return Handled(next, false, produced = event.text.isNotEmpty())
            }

            // Размышления провайдера: у reasoning-моделей (deepseek-v4.1-flash-1017,
            // sonar-reasoning-pro) `content` может остаться пустым, а весь бюджет
            // токенов уйти сюда — тогда человек видел пустой пузырь, который к тому же
            // молча удалялся в finalizeStreaming. Показываем как отдельную пометку,
            // чтобы было видно: модель работает, просто ещё думает.
            is LocalChatEvent.ReasoningDelta -> {
                val thought = event.text.trim()
                if (thought.isNotEmpty()) {
                    updateItem(assistantId) {
                        it.copy(thinking = (it.thinking + event.text).take(THINKING_LIMIT))
                    }
                }
                return Handled(partial, false, produced = thought.isNotEmpty())
            }

            is LocalChatEvent.ToolRequested -> {
                addToolCard(event.call)
                return Handled(partial, false)
            }

            is LocalChatEvent.ToolFinished -> {
                updateTool(event.callId) { card ->
                    card.copy(
                        phase = if (event.ok) LocalToolPhase.DONE else LocalToolPhase.FAILED,
                        preview = event.preview.ifBlank { null },
                    )
                }
                return Handled(partial, false)
            }

            // Приходит ПЕРЕД AssistantFinished: пилюля обновляется тем же кадром, которым
            // закрывается шаг, иначе между «ответ пришёл» и «процент обновился» виден скачок.
            is LocalChatEvent.UsageReported -> {
                recordUsage(event.usage)
                return Handled(partial, false)
            }

            is LocalChatEvent.AssistantFinished -> {
                val full = event.fullText.ifBlank { partial }
                // `length` — единственный достоверный признак упора в лимит выходных
                // токенов. `stop`/`tool_calls`/`content_filter` — модель закончила сама
                // (или её остановили), и «обрезан» на них был бы ложью. `null` —
                // провайдер причину не прислал: тоже не обрезка, а «неизвестно».
                val truncated = event.finishReason == LocalTurnMetrics.REASON_LENGTH
                turnFinishReason = event.finishReason
                if (full.isNotBlank()) {
                    updateItem(assistantId) {
                        it.copy(text = full, streaming = false, truncated = truncated)
                    }
                } else if (truncated) {
                    // Текста нет, а шаг закрыт по лимиту: флаг остаётся на строке —
                    // судьбу пустого пузыря решает finalizeStreaming, не мы.
                    updateItem(assistantId) { it.copy(truncated = true) }
                }
                history = history + LocalMessage(
                    id = newId(),
                    role = LocalRole.ASSISTANT,
                    text = event.fullText,
                    toolCalls = event.toolCalls,
                    createdAt = now(),
                    // Метрики едут в историю вместе с текстом: без них после
                    // перезапуска футер пуст, хотя цифры были получены (A3).
                    metrics = turnMetrics(interrupted = false),
                )
                // TOOL-сообщения обязаны следовать за вызовом и нести контент:
                // провайдеры отвергают историю с висящим tool_call.
                event.toolCalls.forEach { call ->
                    val result = toolResults[call.id]
                    history = history + LocalMessage(
                        id = newId(),
                        role = LocalRole.TOOL,
                        // Копия картинки могла попасть в результат этого же хода (её дописывает
                        // экран при сохранении), а в запрос модели base64 уходить не должен.
                        text = GeneratedImageStorage.strip(result?.content.orEmpty()),
                        toolCallId = call.id,
                        createdAt = now(),
                    )
                    if (result != null) {
                        updateTool(call.id) { card ->
                            card.copy(
                                phase = if (result.ok) LocalToolPhase.DONE else LocalToolPhase.FAILED,
                                preview = card.preview ?: result.preview.ifBlank { null },
                            )
                        }
                    }
                }
                return Handled(full, true, produced = full.isNotBlank())
            }

            is LocalChatEvent.Error -> {
                // Текст ошибки НЕ заменяет уже показанный ответ: плашка живёт отдельно,
                // а накопленный текст остаётся в строке (обрыв помечается в finally).
                _state.value = _state.value.copy(error = event.message)
                return Handled(partial, false, failed = true)
            }
        }
    }

    /**
     * Учесть расход шага: [usageTotal] копится суммой, заполнение окна — по последнему шагу.
     * Нулевой замер (провайдер не прислал `usage`) игнорируется: иначе пилюля показывала бы
     * «0%» после каждого хода у провайдера без usage.
     */
    private fun recordUsage(step: LocalUsage) {
        if (!step.isMeasured) return
        usageTotal = LocalUsage(
            promptTokens = usageTotal.promptTokens + step.promptTokens,
            completionTokens = usageTotal.completionTokens + step.completionTokens,
            totalTokens = usageTotal.totalTokens + step.totalTokens,
        )
        lastStepPromptTokens = step.promptTokens
        // Расход хода — сумма по его шагам: он относится к строке ответа, а не к диалогу.
        // Ноль сюда не попадает вовсе (isMeasured выше), поэтому футер не покажет «0 ток».
        // `promptTokens` берём из ПОСЛЕДНЕГО шага, а не суммой: каждый шаг включает всю
        // предыдущую историю, и сумма дала бы «запрос вдвое больше окна».
        turnUsage = LocalUsage(
            promptTokens = step.promptTokens,
            completionTokens = turnUsage.completionTokens + step.completionTokens,
            totalTokens = turnUsage.totalTokens + step.totalTokens,
        )
        val rowId = turnAssistantId
        if (rowId.isNotEmpty()) {
            updateItem(rowId) {
                it.copy(
                    promptTokens = turnUsage.promptTokens,
                    completionTokens = turnUsage.completionTokens,
                    totalTokens = turnUsage.totalTokens,
                )
            }
        }
        publishUsage()
    }

    /** Пересчитать пилюлю контекста по текущему замеру и окну активного профиля. */
    private fun publishUsage() {
        val view = LocalContextPolicy.view(lastStepPromptTokens, windowFor(activeProfile))
        _state.value = _state.value.copy(
            usage = usageTotal,
            // Невидимая пилюля хранится как null: экран не должен различать «скрыта
            // политикой» и «нет данных» — оба случая значат «показывать нечего».
            context = view.takeIf { it.visible },
        )
    }

    /** Окно контекста: из профиля (сохранено при выборе модели) либо из каталога. */
    private fun windowFor(profile: LocalProviderProfile?): Int? {
        if (profile == null) return null
        val known = profile.contextWindow
        if (known != null && known > 0) return known
        return modelInfo(profile.model)?.contextWindow?.takeIf { it > 0 }
    }

    /** Поддерживает ли модель профиля `reasoning_effort` (по каталогу, а не по имени). */
    private fun supportsReasoning(profile: LocalProviderProfile?): Boolean {
        if (profile == null) return false
        return modelInfo(profile.model)?.supportsReasoningEffort == true
    }

    /**
     * Применить изменённый профиль: сохранить через колбэк, обновить состояние и сбросить
     * замер заполнения — он относился к прежней модели и её окну.
     */
    private fun applyProfile(updated: LocalProviderProfile) {
        val withWindow = if (updated.contextWindow == null) {
            updated.copy(contextWindow = modelInfo(updated.model)?.contextWindow)
        } else {
            updated
        }
        val saved = onProfileChange(withWindow) ?: withWindow
        activeProfile = saved
        lastStepPromptTokens = 0
        _state.value = _state.value.copy(
            model = saved.model,
            reasoningEffort = saved.reasoningEffort,
            supportsReasoning = supportsReasoning(saved),
            context = null,
        )
    }

    /** Лента из сообщений истории: системный промпт не показываем, TOOL — карточками. */
    private fun buildItems(messages: List<LocalMessage>): List<LocalChatItem> {
        val items = ArrayList<LocalChatItem>(messages.size)
        messages.forEach { message ->
            when (message.role) {
                LocalRole.USER -> items.add(LocalChatItem(message.id, LocalRole.USER, message.text))
                LocalRole.ASSISTANT -> {
                    if (message.text.isNotBlank() || message.toolCalls.isEmpty()) {
                        // Метрики хода поднимаются из истории в элемент ленты: без этого
                        // после перезапуска футер был бы пуст, хотя цифры сохранены (A3).
                        // У сообщений без метрик (старый файл) поля остаются нулевыми —
                        // ровно как раньше.
                        val metrics = message.metrics
                        items.add(
                            LocalChatItem(
                                id = message.id,
                                role = LocalRole.ASSISTANT,
                                text = message.text,
                                promptTokens = metrics?.promptTokens ?: 0,
                                completionTokens = metrics?.completionTokens ?: 0,
                                totalTokens = metrics?.totalTokens ?: 0,
                                ttftMs = metrics?.ttftMs ?: 0L,
                                interrupted = metrics?.interrupted ?: false,
                                truncated = metrics?.truncated ?: false,
                            )
                        )
                    }
                    message.toolCalls.forEach { call ->
                        items.add(
                            LocalChatItem(
                                id = toolItemId(call.id),
                                role = LocalRole.TOOL,
                                tool = LocalToolCard(
                                    callId = call.id,
                                    name = call.name,
                                    argumentsJson = call.argumentsJson,
                                    phase = LocalToolPhase.DONE,
                                ),
                            )
                        )
                    }
                }
                LocalRole.TOOL -> {
                    // Результат крепится к карточке своего вызова. `ok` в файле не
                    // хранится (TOOL-сообщение несёт только текст), поэтому после
                    // перезапуска карточка нейтральна: DONE + превью.
                    //
                    // Текст копии картинки в превью НЕ идёт: base64 в карточке — это
                    // мегабайт мусора; превью строится по тексту до маркера, а сама
                    // картинка берётся лентой из копии (`GeneratedImageStorage`).
                    val index = items.indexOfFirst { it.tool?.callId == message.toolCallId }
                    if (index >= 0) {
                        val card = items[index].tool
                        if (card != null) {
                            items[index] = items[index].copy(
                                tool = card.copy(
                                    phase = LocalToolPhase.DONE,
                                    preview = GeneratedImageStorage.strip(message.text)
                                        .take(PREVIEW_LIMIT).ifBlank { null },
                                    // Файл из cacheDir мог не дожить до перезапуска —
                                    // тогда лента возьмёт байты из копии в этом сообщении.
                                    imagePath = card.imagePath
                                        ?: message.id.takeIf {
                                            GeneratedImageStorage.imageBytes(message.text) != null
                                        },
                                )
                            )
                        }
                    }
                }
                LocalRole.SYSTEM -> Unit // системный промпт в ленте не показываем
            }
        }
        return items
    }

    private fun addToolCard(call: LocalToolCall) {
        val id = toolItemId(call.id)
        if (_state.value.items.any { it.id == id }) {
            updateTool(call.id) { it.copy(phase = LocalToolPhase.RUNNING) }
            return
        }
        _state.value = _state.value.copy(
            items = _state.value.items + LocalChatItem(
                id = id,
                role = LocalRole.TOOL,
                tool = LocalToolCard(
                    callId = call.id,
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    phase = LocalToolPhase.RUNNING,
                ),
            )
        )
    }

    private fun updateItem(id: String, transform: (LocalChatItem) -> LocalChatItem) {
        val items = _state.value.items
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = ArrayList(items)
        updated[index] = transform(items[index])
        _state.value = _state.value.copy(items = updated)
    }

    private fun updateTool(callId: String, transform: (LocalToolCard) -> LocalToolCard) {
        val items = _state.value.items
        val index = items.indexOfFirst { it.tool?.callId == callId }
        if (index < 0) return
        val card = items[index].tool ?: return
        val updated = ArrayList(items)
        updated[index] = items[index].copy(tool = transform(card))
        _state.value = _state.value.copy(items = updated)
    }

    /**
     * Вставить системное сообщение с памятью в начало истории — ровно одно.
     *
     * Провайдеры отвергают историю с двумя `system` подряд, а память меняется от хода
     * к ходу (человек дописал факт), поэтому старое системное сообщение заменяется
     * новым, а не накапливается. Если памяти нет — история остаётся как была.
     */
    private fun withMemory(source: List<LocalMessage>): List<LocalMessage> {
        val block = runCatching { systemPrompt().trim() }.getOrDefault("")
        val rest = source.filterNot { it.role == LocalRole.SYSTEM }
        if (block.isEmpty()) return rest
        val system = LocalMessage(
            id = SYSTEM_MESSAGE_ID,
            role = LocalRole.SYSTEM,
            text = block,
            createdAt = 0L,
        )
        return listOf(system) + rest
    }

    /** Снять флаг стрима и убрать пустой пузырь (ошибка до первого токена). */
    private fun finalizeStreaming(assistantId: String) {
        val items = _state.value.items
        val cleaned = items.map { item ->
            if (assistantId.isNotEmpty() && item.id == assistantId && item.streaming) {
                // Пустой ответ reasoning-модели — не «ничего не произошло»: весь бюджет
                // ушёл в размышления, и человек обязан видеть причину, а не пустоту.
                // Раньше пузырь просто удалялся, и это выглядело как «Марс не ответил».
                if (item.text.isBlank() && item.thinking.isNotBlank()) {
                    item.copy(
                        text = EMPTY_REASONING_ANSWER,
                        streaming = false,
                    )
                } else {
                    item.copy(streaming = false)
                }
            } else {
                item
            }
        }.filterNot { item ->
            item.streaming && item.text.isBlank() && (assistantId.isEmpty() || item.id == assistantId)
        }
        _state.value = _state.value.copy(items = cleaned, streaming = false)
    }

    private fun toolItemId(callId: String): String = "tool-" + callId

    companion object {
        /** Идентификатор системного сообщения: постоянный, чтобы память не плодила копии. */
        private const val SYSTEM_MESSAGE_ID = "memory-system"

        /**
         * Признак «экран не подключил [onUndoInput]».
         *
         * Сравнивается по ссылке: значение по умолчанию получает ровно этот объект.
         * Нужен, чтобы при неподключённом возврате ввода не удалять сообщение человека
         * (см. `finishTurn`): пустая лямбда неотличима от намеренной заглушки, а
         * потерянный текст вопроса — это потеря работы человека.
         */
        private val NO_UNDO: (text: String, attachments: List<LocalAttachment>) -> Unit = { _, _ -> }

        /** Сколько символов размышлений держим в элементе: они не ответ, длину не копим. */
        private const val THINKING_LIMIT = 600

        /**
         * Текст вместо пустого ответа reasoning-модели: причина и что делать, а не пустота.
         */
        private const val EMPTY_REASONING_ANSWER =
            "Модель израсходовала весь лимит на размышления и не успела ответить. " +
                "Скажи вопрос короче или выбери другую модель."

        /** Длина превью результата инструмента в карточке. */
        const val PREVIEW_LIMIT = 200

        /**
         * Допустимые уровни `reasoning_effort` (проверено живым запросом, §2 контракта).
         * «Авто» — это `null`, а не строка: поле в запрос не отправляется.
         */
        val REASONING_EFFORTS: List<String> = listOf("low", "medium", "high")
    }
}

/**
 * Заголовок диалога из первого сообщения: одна строка, без переводов строк,
 * с усечением. Чистая функция — её же проверяет W4.
 */
internal fun localSessionTitle(text: String, limit: Int = 48): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    if (flat.isEmpty()) return LocalSessionStore.DEFAULT_TITLE
    if (flat.length <= limit) return flat
    return flat.take(limit - 1).trimEnd() + "…"
}
