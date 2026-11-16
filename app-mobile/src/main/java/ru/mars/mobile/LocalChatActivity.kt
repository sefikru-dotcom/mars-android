package ru.mars.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.mars.mobile.localtools.LocalDeviceTool
import ru.mars.mobile.service.MarsVoiceService
import ru.mars.mobile.tts.OfflineTtsBackend
import ru.mars.mobile.tts.OfflineTtsPort
import ru.mars.network.local.AttachmentKind
import ru.mars.network.local.LocalAttachment
import ru.mars.network.local.MemoryStore
import ru.mars.network.local.OwnerProfileStore
import ru.mars.network.local.LocalCapabilityCache
import ru.mars.network.local.LocalChatClient
import ru.mars.network.local.LocalMessage
import ru.mars.network.local.LocalModelInfo
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.LocalTurnSettings
import ru.mars.network.local.LocalProviderStore
import ru.mars.network.local.LocalRole
import ru.mars.network.local.LocalSession
import ru.mars.network.local.LocalToolCall
import ru.mars.network.local.LocalUsage
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.ServerTtsVoices
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран локального диалога: лента, композер, отправка/стоп, карточки инструментов.
 *
 * Чат ходит к провайдеру **напрямую** (`LocalChatClient`), без сервера Марса: ни
 * авторизации устройства, ни сессий сервера здесь нет. Всё, что знает экран о ядре, —
 * три интерфейса ([LocalChatClient], `LocalToolExecutor`, `LocalAgentLoop`), поэтому
 * транспорт и набор инструментов заменяемы без правки разметки.
 *
 * Слои: состояние и стрим — [LocalChatPresenter]; разметка ленты — [LocalChatTranscript];
 * ввод и пилюля контекста — [LocalChatComposerBar]; выбор модели и reasoning —
 * [LocalChatModelPicker]; действия над сообщением — [LocalChatActionsSheet]; хранилище —
 * [LocalSessionStore]; подтверждение деструктивных вызовов — [LocalChatConfirmDialog]
 * (таймаут 120 с = отказ). Здесь — только проводка и ввод.
 *
 * Навигация не моя: экран открывается через [newIntent], а раздел «Локально» в левом
 * баре ведёт на [LocalSessionsActivity].
 */
class LocalChatActivity : ComponentActivity() {

    companion object {
        /** Идентификатор диалога в Intent; отсутствует — новый диалог. */
        const val EXTRA_SESSION_ID = "local_session_id"

        /** Сколько ждать полный ответ на голосовую реплику, прежде чем сдаться. */
        private const val ANSWER_TIMEOUT_MS = 120_000L

        /** Запрос микрофона для фонового прослушивания (отличать от запроса голосовой кнопки). */
        private const val REQUEST_WAKE_MIC = 4750

        fun newIntent(context: Context, sessionId: String? = null): Intent =
            Intent(context, LocalChatActivity::class.java).apply {
                if (!sessionId.isNullOrBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
            }

        /** Быстрый бабл «Ок»: согласие с предложенным планом. */
        private const val QUICK_OK = "Ок, принимаю предложенный план, реализуй"

        /** Быстрый бабл «Продолжай»: модель оборвалась на полуслове. */
        private const val QUICK_CONTINUE = "Продолжай"

        private const val NOT_CONFIGURED =
            "Провайдер не настроен — нажмите, чтобы открыть настройки"
        private const val NO_KEY = "Ключ API не сохранён — нажмите, чтобы открыть настройки"

        /** Метка элемента буфера обмена: видна в истории буфера Android 13+. */
        private const val CLIP_LABEL = "Сообщение Марса"

        /**
         * Предел картинки — 8 МБ байт (≈10.9 МБ в base64).
         *
         * Не произвольное число: провайдеры режут тело запроса, и на 20-мегабайтном фото
         * человек получил бы отказ сети уже после «отправки». Лучше отказать до неё.
         */
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        /** Предел текстового файла — 1 МБ: это уже сотни тысяч токенов, окно не примет. */
        private const val MAX_TEXT_BYTES = 1024 * 1024

        /** Документы больше этого не читаем в память: 20 МБ PDF — уже перебор для телефона. */
        private const val MAX_DOCUMENT_BYTES = 20 * 1024 * 1024

        /** Честный отказ: провайдер понимает только текст и картинки. */
        private const val REJECT_UNSUPPORTED_TYPE =
            "Такой тип файла провайдер не принимает — приложите картинку или текстовый файл"

        /** Расширения, по которым узнаём текст, если провайдер не сообщил MIME. */
        private val TEXT_EXTENSIONS = listOf(
            ".txt", ".md", ".markdown", ".csv", ".json", ".log", ".kt", ".java", ".py",
            ".js", ".ts", ".xml", ".html", ".yml", ".yaml", ".ini", ".conf",
        )
    }

    private lateinit var providerStore: LocalProviderStore
    private lateinit var sessionStore: LocalSessionStore
    private lateinit var credentialStore: AndroidKeyStoreCredentialStore

    private lateinit var scaffold: SectionScaffold.Parts

    /**
     * Полоска «Запомнил: …» под лентой.
     *
     * Создаётся лениво, в [buildView], и живёт до конца экрана: подсказка появляется
     * считанные разы за диалог, поэтому её место в памяти дешевле, чем пересоздание
     * вьюхи посреди ленты на каждое сохранение факта.
     */
    private var memoryNoticeView: MemoryNoticeView? = null

    /** Снятие подсказки по сроку: перезаписывается новым фактом, снимается в onDestroy. */
    private var memoryNoticeHide: Runnable? = null

    /** Что показано сейчас: по нему отличаем новую запись от повторного рендера. */
    private var lastMemoryNotice: MemoryNotice? = null

    /**
     * Включённый перевод: с какого языка на какой. null — обычный разговор.
     *
     * Живёт в экране, а не в слое: направление подставляется в системный промпт каждого
     * хода, то есть это свойство разговора, а не оформления. Слой только показывает его.
     *
     * Хранится в памяти сессии, а не в настройках: перевод — это режим на «сейчас»
     * (перевести один разговор), а не постоянный выбор. Иначе человек, включив однажды
     * перевод для поездки, получил бы его же в обычном разговоре через неделю.
     */
    private var translateDirection: TranslateDirection? = null
    private lateinit var transcript: LocalChatTranscript
    private lateinit var composer: LocalChatComposerBar
    private lateinit var subtitle: TextView
    private lateinit var modelButton: TextView
    private lateinit var reasoningButton: TextView

    /** Чип «Параметры»: сэмпсинг текущего диалога. */
    /**
     * Чип «Голос» рядом с подписью диалога: выбор голоса и темпа речи.
     *
     * Отдельный чип от «Параметров»: там КАК отвечает модель (температура, лимиты),
     * здесь — как это звучит. Смешать их в одной шторке значило бы каждый раз
     * вспоминать, в каком из двух списков темп.
     */
    private lateinit var voiceProsodyButton: TextView

    /** Чип входа в полноэкранный разговор. */
    private lateinit var voiceModeButton: TextView

    /** Кнопка «Новый» в верхней строке: чистый диалог без захода в список. */
    private lateinit var newChatButton: TextView

    private lateinit var paramsButton: TextView

    /** Голосовой ввод (микрофон → ASR → композер). Инициализируется в [buildView]. */
    private lateinit var voice: LocalVoiceButton

    /** Чип фонового прослушивания в шапке. */
    private lateinit var wakeChip: android.widget.TextView

    /** Чип входа в память: профиль владельца и запомненные факты. */
    private lateinit var memoryChip: android.widget.TextView

    /** Включено ли прослушивание «Марс, слушай» (сервис в foreground). */
    private var wakeArmed = false

    /** Прошлое состояние стрима: нужен переход «шёл → закончился», а не сам флаг. */
    private var wasStreaming = false
    /**
     * Ответ озвучивается потоком (ранний старт речи).
     *
     * Когда `true`, куски уходят в синтез по мере поступления, и в конце ответа НЕЛЬЗЯ
     * звать `onAnswerCompleted` — иначе ответ прозвучит второй раз целиком.
     */
    private var voiceStreaming = false
    /** Сколько символов ответа уже отдано синтезу: дельта = хвост сверх этого. */
    private var voiceSpokenChars = 0

    /**
     * Область хода, которая НЕ отменяется при уходе экрана.
     *
     * `lifecycleScope` живёт до `onDestroy` экрана и отменяется вместе с ним, а ход должен
     * досчитываться и сохраняться в сессию, даже если человек свернул чат или погасил экран
     * (в том числе когда вопрос пришёл по «Марс, слушай» из фонового сервиса). Поэтому у хода
     * своя область: `SupervisorJob` (ошибка одного хода не убивает область) на главном
     * диспетчере (состояние презентера — `StateFlow` для UI, его надо менять на main).
     *
     * Отменяется только явно: кнопкой «Стоп» ([stopTurn]) и в [onDestroy] — и то лишь когда
     * фоновый разговор не идёт (иначе уход с экрана оборвал бы ответ, который читает сервис).
     */
    private val turnScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Фоновый разговор: реплика, распознанная по «Марс, слушай», уходит в тот же
     * презентер, что и обычный ход, а ответ возвращается сервису на озвучку.
     */
    private val voiceCallback = object : ru.mars.mobile.service.VoiceCallback {
        override fun onUtterance(text: String) {
            if (text.isBlank()) return
            if (presenter.state.value.streaming) return
            // Ход из фонового разговора живёт в [turnScope]: экран может быть свёрнут или
            // вовсе уничтожен, а ответ обязан досчитаться и озвучиться.
            turnScope.launch {
                val answer = sendAndAwaitAnswer(text)
                // Пустой ответ — тоже ответ: сервис по нему сразу возвращается к «Марс, слушай»,
                // а не висит минуту в «Думаю» до таймаута.
                MarsVoiceService.deliverAnswer(this@LocalChatActivity, answer.orEmpty())
            }
        }

        override fun onError(reason: String) {
            runOnUiThread { presenter.setNotice(reason) }
        }
    }

    private lateinit var client: LocalChatClient
    /** Темп и голос озвучки: живут в настройках приложения, а не в диалоге. */
    /**
     * Полноэкранный голосовой режим. Слой, а не Activity: отдельный экран отправил бы
     * этот в `onPause`, а там микрофон закрывается (см. KDoc [VoiceModeOverlay]).
     */
    private lateinit var voiceMode: VoiceModeOverlay

    private lateinit var voiceProsodyStore: VoiceProsodyPrefs

    /**
     * Текущий темп — одна копия на экран.
     *
     * Хранится здесь, а не только в настройках: шторка меняет значение чаще, чем пишет
     * на диск, а применить его надо и к движку, и к подписи чипа. Одно поле — один
     * источник правды, иначе чип показывал бы одно, а звучало другое.
     */
    private var voiceProsody: VoiceProsody = VoiceProsody()

    /** Подпись чипа «Голос», когда темп обычный и голос не выбран. */
    private val DEFAULT_VOICE_CHIP: String = "Голос"

    /** Подпись чипа входа в полноэкранный разговор. */
    private val VOICE_MODE_CHIP: String = "Разговор"

    private lateinit var presenter: LocalChatPresenter
    private var deviceTools: List<LocalDeviceTool> = emptyList()

    /**
     * Метаданные моделей из последнего удачного `GET /models`: окно контекста и поддержка
     * reasoning. Нужны презентеру ([LocalChatPresenter.modelInfo]) — без них пилюля
     * контекста не знает окно, а переключатель reasoning не знает о поддержке.
     */
    private val catalog = HashMap<String, LocalModelInfo>()

    /**
     * Кэш возможностей модели по адресу провайдера.
     *
     * Отличие от [catalog]: тот живёт до пересоздания экрана и ничего не знает о смене
     * сервера, поэтому после переключения адреса ещё показывал бы окно и поддержку
     * reasoning ЧУЖОГО провайдера. Здесь ключ включает нормализованный адрес, а смена
     * профиля сбрасывает кэш целиком ([LocalCapabilityCache.invalidate]).
     */
    private val capabilityCache = LocalCapabilityCache()

    /**
     * Метки запросов каталога: применяется только последний.
     *
     * `prefetchCatalog` запускается на каждый `onResume` и после смены профиля. Два
     * наложившихся запроса завершаются в любом порядке: медленный ответ по ПРЕЖНЕМУ
     * адресу мог прийти после быстрого по новому и переписать каталог обратно, то есть
     * вернуть окно и поддержку reasoning чужого сервера. Гейт отбрасывает такие ответы.
     */
    private val catalogGate = LocalModelPickGate()

    /** Текущий диалог; `null` — ещё не создан (создаётся первым сообщением). */
    @Volatile
    private var session: LocalSession? = null

    /** Текущий ход. «Стоп» — это его отмена. */
    private var turn: Job? = null

    /**
     * Выбранные, но ещё не отправленные вложения. Владелец списка — экран: композер
     * только рисует чипы, а презентер получает копию при отправке.
     */
    private var attachments: List<LocalAttachment> = emptyList()

    /**
     * Системный пикер файла.
     *
     * `OpenDocument` (а не `GetContent`) даёт стабильный `content://` uri и работает без
     * разрешений на хранилище: доступ выдаётся на один документ. Регистрируется
     * инициализатором поля — то есть ДО `onCreate`/`setContentView`: лончер, созданный
     * после старта Activity, теряет результат (ActivityResultRegistry уже поднят).
     */
    private val attachmentPicker: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadAttachment(uri)
        }

    /**
     * Озвучка ответа. Создаётся лениво и только если на устройстве вообще есть движок TTS:
     * поднимать TextToSpeech ради мёртвого пункта меню незачем.
     */
    private lateinit var speechEngine: AndroidSpeechEngine
    private lateinit var voiceOutput: VoiceOutputController

    /** Результат инициализации движка, пришедший раньше создания контроллера. */
    private var speechInitResult: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Темп и голос — из настроек, до первого звука: иначе первая фраза после
        // запуска зазвучала бы дефолтом, а человек уже слышал свой выбор вчера.
        voiceProsodyStore = VoiceProsodyPrefs(this)
        providerStore = LocalProviderPrefsStore(this)
        sessionStore = LocalSessionStore.forContext(this)
        credentialStore = AndroidKeyStoreCredentialStore(this)
        client = LocalChatTransport.clientOrUnavailable()
        deviceTools = LocalChatToolSource.tools(this)
        session = intent.getStringExtra(EXTRA_SESSION_ID)?.let { id ->
            sessionStore.session(id)
                ?: LocalSession(id, LocalSessionStore.DEFAULT_TITLE, System.currentTimeMillis())
        }

        presenter = LocalChatPresenter(
            client = client,
            baseExecutor = LocalChatToolSource.executor(deviceTools, ::confirmTool),
            // Ход сохраняется с копиями сгенерированных картинок: файл живёт в cacheDir
            // и переживает перезапуск не всегда, а показать картинку в старом диалоге — надо.
            onPersist = ::persistWithImages,
            onUsagePersist = ::persistUsage,
            onProfileChange = ::persistProfile,
            modelInfo = ::catalogInfo,
            // Память читается на каждый ход: человек мог только что дописать факт
            // на экране «Память», и следующий же вопрос обязан его учитывать.
            systemPrompt = ::memoryPrompt,
            // Сбой до первого токена возвращает вопрос в поле ввода вместе с вложениями:
            // набирать заново сообщение, которое уже было, — худший вид потери работы.
            onUndoInput = { text, files ->
                composer.input.setText(text)
                composer.input.setSelection(text.length)
                attachments = files
                composer.setAttachments(attachments, ::removeAttachment)
            },
        )
        // Прогрев офлайн-голоса — в фоне, пока человек читает экран.
        prepareOfflineVoiceInBackground()
        // Профиль обязан дойти до презентера до первого кадра: иначе чип модели пуст, а
        // переключатель reasoning не знает, поддерживает ли модель `reasoning_effort`.
        presenter.bindProfile(providerStore.state().active)

        setContentView(buildView())

        // Голосовой движок для фонового разговора (заблокированный экран). Фабрика —
        // единственная живая ссылка на LocalVoiceEngine/OfflineTts*: без неё R8 вырезает
        // их из release как мёртвый код (проверено по mapping — классов не было вовсе).
        // Ставится здесь, на активном экране: Android 14+ запрещает старт микрофонного
        // foreground-сервиса из фона.
        MarsVoiceService.installEngineFactory(
            LocalVoiceEngine.factory { offlineTts() },
        )

        // Колбэк фонового разговора: «Марс, слушай» → локальный чат → ответ вслух.
        // Без этой строки сервис поднимал бы микрофон, но распознанную реплику некому
        // было отправить провайдеру — wake word выглядел бы «мёртвым».
        MarsVoiceService.setCallback(voiceCallback)

        presenter.restore(
            messages = session?.let { sessionStore.messages(it.id) }.orEmpty(),
            usage = session?.let { sessionStore.meta(it.id) }?.toLocalUsage(),
        )
        prefetchCatalog()
        lifecycleScope.launch {
            presenter.state.collect { state -> render(state) }
        }
        updateNotice()
    }

    /**
     * «Назад» при открытом голосовом режиме сворачивает ЕГО, а не диалог.
     *
     * Через `OnBackPressedDispatcher`, а не переопределением `onBackPressed`: активность
     * на `ComponentActivity`, и старый способ здесь не вызывается вовсе — экран просто
     * закрылся бы вместе с режимом. Колбэк снимается в `onDestroy`, чтобы не держать
     * ссылку на активность после её смерти.
     */
    private var voiceModeBack: OnBackPressedCallback? = null

    private fun installVoiceModeBack() {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeVoiceModeIfShown()
            }
        }
        voiceModeBack = callback
        onBackPressedDispatcher.addCallback(this, callback)
    }
    override fun onResume() {
        super.onResume()
        // Провайдера и модель могли поменять в соседнем экране — чипы обязаны обновиться.
        presenter.bindProfile(providerStore.state().active)
        updateNotice()
        if (::voice.isInitialized) voice.onResume()
        // Возврат на экран: слой перерисовывается из текущего состояния канала,
        // чтобы после паузы орб снова показывал реальную фазу, а не покой.
        if (::voiceMode.isInitialized) voiceMode.onResume()
    }

    override fun onPause() {
        // Экран ушёл в фон — озвучка не должна продолжать говорить из кармана.
        if (::voiceOutput.isInitialized) voiceOutput.onPause()
        // Микрофон закрываем сразу: запись из кармана — это расход батареи и утечка звука.
        if (::voice.isInitialized) voice.onPause()
        // Слой перестаёт крутить кадры: невидимая анимация в фоне — расход батареи.
        if (::voiceMode.isInitialized) voiceMode.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // Отменяем ход только когда экран реально закрывают (человек ушёл кнопкой «назад») или
        // когда экран уничтожает система, а фоновый разговор не идёт. Свёртывание и
        // `onStop`/`onPause` ход не трогают вовсе: он живёт в [turnScope], досчитывается и
        // сохраняется в сессию — при следующем открытии ответ уже в ленте.
        val keepTurn = !isFinishing && !isChangingConfigurations ||
            MarsVoiceService.conversationActive
        if (!keepTurn) {
            turn?.cancel()
            turnScope.cancel()
        }
        turn = null
        // Отложенное снятие подсказки памяти: вьюха умирает вместе с экраном, и Runnable,
        // оставшийся в очереди корня, держал бы её в памяти до конца процесса.
        memoryNoticeHide?.let { scaffold.root.removeCallbacks(it) }
        memoryNoticeHide = null
        if (::voice.isInitialized) voice.release()
        // Офлайн-синтез держит нативный движок и AudioTrack: без release() они
        // переживут экран и продолжат занимать память с аудио-фокусом. Но пока идёт
        // фоновый разговор, этот же порт нужен сервису: release() убил бы его озвучку
        // (и именно так «уход из приложения» ломал разговор). Тогда порт отпускает сервис.
        if (!MarsVoiceService.conversationActive) runCatching { ttsWarmup.release() }
        if (::voiceOutput.isInitialized) voiceOutput.onDestroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Кнопке нужно знать исход: отказ ведёт в настройки приложения, а не молчит.
        if (requestCode == LocalVoiceButton.REQUEST_RECORD_AUDIO && ::voice.isInitialized) {
            voice.onPermissionResult(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
        // Фоновое прослушивание: разрешение дали — включаем сразу, без повторного тапа.
        if (requestCode == REQUEST_WAKE_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                toggleWakeService()
            } else {
                presenter.setNotice("Без доступа к микрофону «Марс, слушай» не работает")
            }
        }
    }

    /**
     * Офлайн-синтез для фонового голоса.
     *
     * Подготовка (скачивание 21 МиБ) стартует при открытии чата в фоне — не в момент
     * «слушай», иначе человек ждёт вслух. `OfflineTtsWarmup` зовёт `prepare()` ровно один
     * раз за процесс и защищает от двойного скачивания. Прогресс — в статусную строку.
     *
     * Собирается здесь, а не через `forApp`, ради одного параметра: `onSpeakingChanged`
     * бэкенда. Это единственный сигнал «реплика дочитана», и без него фоновый сервис
     * навсегда оставался в фазе SPEAKING (движок его не получал). Сигнал идёт в
     * [OfflineVoiceSignal], откуда его слушает [LocalVoiceEngine].
     */
    private val ttsWarmup by lazy {
        val relay = ru.mars.mobile.tts.ProgressRelay()
        val model = ru.mars.mobile.tts.OfflineTtsModel.forApp(this) { downloaded, total ->
            relay.emit(downloaded, total)
        }
        val port = OfflineTtsBackend(
            this,
            model = model,
            onSpeakingChanged = { speaking -> OfflineVoiceSignal.publish(speaking) },
        )
        ru.mars.mobile.tts.OfflineTtsWarmup(
            port = port,
            model = model,
            diagnostics = ru.mars.mobile.tts.AndroidWarmUpDiagnostics(this),
            relay = relay,
        )
    }

    private fun offlineTts(): OfflineTtsPort? = runCatching { ttsWarmup.port }.getOrNull()

    /**
     * Прогрев, запущенный с экрана. Один на экран: повторное «Озвучить» не должно ставить
     * второй корутин, который просто ждал бы тот же мьютекс.
     */
    private var warmUpJob: Job? = null

    private fun prepareOfflineVoiceInBackground() {
        if (warmUpJob?.isActive == true) return
        warmUpJob = lifecycleScope.launch {
            val ok = ttsWarmup.warmUp { done, total ->
                runOnUiThread {
                    // Прогресс синтеза не должен спорить с живым транскриптом: пока
                    // человек диктует, строкой владеет голосовой ввод.
                    if (total > 0 && !voiceStatusOwned) {
                        voiceStatusLine?.visibility = android.view.View.VISIBLE
                        voiceStatusLine?.text = "Готовлю голос… ${done * 100 / total}%"
                    }
                }
            }
            if (!ok && !voiceStatusOwned) {
                voiceStatusLine?.visibility = android.view.View.VISIBLE
                voiceStatusLine?.text = ttsWarmup.lastFailure ?: "синтез недоступен"
            } else if (ok && !voiceStatusOwned) {
                voiceStatusLine?.visibility = android.view.View.GONE
            }
        }
    }

    /** Строка статуса голоса; задаётся в buildView, если экран её показывает. */
    private var voiceStatusLine: android.widget.TextView? = null

    /**
     * Строкой статуса владеет живой голосовой ввод.
     *
     * Тот же TextView занят прогрессом прогрева синтеза («Готовлю голос… 40%»), и без
     * этого флага две записи перетирали бы друг друга: фаза «слушаю» гасла бы от
     * случайного кадра скачивания, а хвост «Готовлю голос…» висел бы поверх транскрипта.
     */
    private var voiceStatusOwned = false

    /**
     * Живой статус голосового ввода над композером.
     *
     * Пока человек диктует, над полем видно каждое распознанное слово: без этого
     * микрофон выглядит «нажал и тишина», и человек говорит в пустоту. Фаза «думаю» —
     * честное «распознаю», а не пустой экран: ответа ещё нет, но работа идёт.
     */
    private fun renderVoiceStatusLine(state: LocalVoiceState) {
        val line = voiceStatusLine ?: return
        val live = when (state.phase) {
            LocalVoicePhase.LISTENING -> if (state.partial.isNotBlank()) {
                "🎙 Слушаю: «${state.partial}» — нажмите микрофон для отправки"
            } else {
                "🎙 Слушаю вас — говорите… (нажмите микрофон для отправки)"
            }
            LocalVoicePhase.THINKING -> "⏳ Распознаю и обдумываю ответ…"
            else -> null
        }
        if (live != null) {
            voiceStatusOwned = true
            line.text = live
            line.setTextColor(MarsTheme.TEXT_EM)
            line.visibility = View.VISIBLE
            return
        }
        // Свою плашку снимаем сами: чужие тексты (прогресс синтеза) не трогаем.
        if (voiceStatusOwned) {
            voiceStatusOwned = false
            line.text = ""
            line.setTextColor(0xFF9AA0A6.toInt())
            line.visibility = View.GONE
        }
    }

    /**
     * Отправить реплику и дождаться готового ответа (для озвучки в фоне).
     *
     * Возвращает текст ответа на ЭТУ реплику или `null`, если ход не начался.
     *
     * ## Почему не «последнее сообщение ленты»
     * Прежняя реализация ждала `state.first { !streaming && error == null }` — а такое состояние
     * истинно ещё ДО старта хода (пока он не начался, стрима нет). `first` на `StateFlow`
     * возвращает текущее значение сразу, поэтому в озвучку уходил последний готовый ответ, то
     * есть ПРЕДЫДУЩЕЕ сообщение — ровно жалоба владельца. Теперь:
     * 1. запоминаем id всех элементов ленты до хода;
     * 2. дожидаемся завершения именно этого хода ([Job.join] корутины `presenter.send`);
     * 3. берём ответ, который появился ПОСЛЕ старта — по id, а не «последний в списке».
     */
    private suspend fun sendAndAwaitAnswer(text: String): String? = withContext(Dispatchers.Main) {
        val profile = providerStore.state().active ?: return@withContext null
        val apiKey = providerStore.apiKey(profile.id) ?: return@withContext null
        if (apiKey.isBlank()) return@withContext null

        // Лента до хода: по ней видно, какой ответ появился именно сейчас.
        val before = presenter.state.value.items.mapTo(HashSet()) { it.id }

        // Реплика должна попасть в ленту: человек увидит, что именно услышал Марс.
        composer.input.setText(text)
        val job = send() ?: return@withContext null

        // Ждём конец ИМЕННО этого хода. Таймаут — страховка от зависшей сети; по нему отдаём
        // то, что уже пришло, а не пустоту.
        withTimeoutOrNull(ANSWER_TIMEOUT_MS) { job.join() }

        val items = presenter.state.value.items
        val fresh = items.lastOrNull { it.role == LocalRole.ASSISTANT && it.id !in before }
        // Пустая строка вместо null: сервис по пустому ответу сразу возвращается к «Марс,
        // слушай», а не висит две минуты в «Думаю».
        fresh?.text.orEmpty()
    }

    /**
     * Системный промпт для модели: кто владелец и что она уже запомнила.
     *
     * Читается на каждый ход, а не кэшируется: память меняется прямо во время сессии
     * (инструмент `remember` или экран «Память»). Ошибка чтения не должна ломать чат —
     * пустая строка означает «памяти нет», и запрос уходит как раньше.
     */
    private fun memoryPrompt(): String {
        val dir = File(filesDir, "memory")
        val profile = runCatching { OwnerProfileStore(File(dir, "profile.json")).load() }
            .getOrNull()
        val memory = runCatching { MemoryStore(File(dir, "memory.md")) }.getOrNull()
        // Первый запуск может прийти сюда напрямую, минуя экран «Память»: сажаем
        // стартовые факты и здесь, иначе модель начнёт разговор, ничего не зная
        // о владельце, хотя файл с фактами лежит в APK.
        memory?.let { OwnerMemorySeed.applyOnce(this, it) }
        val parts = listOfNotNull(
            profile?.toPromptSection()?.takeIf { it.isNotBlank() },
            memory?.systemPromptBlock()?.takeIf { it.isNotBlank() },
        )
        // Модель, на которой идёт этот ход. Берётся из активного профиля на каждый вызов,
        // а не из константы: модель меняют в пикере, и следующий же ответ обязан называть
        // уже новую. Профиля может не быть (первый запуск) — тогда честное «неизвестно».
        val model = runCatching { providerStore.state().active?.model }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Правила ниже — не украшение: без них модель либо пересказывает профиль вслух,
        // либо отвечает на запомненные факты как на новый вопрос.
        val rules = """
            Ты — Марс, голосовой и текстовый ассистент владельца этого телефона.
            Отвечай по-русски, коротко и по делу, без вступлений и повторения вопроса.
            Факты ниже — твоя долгая память: не пересказывай её без просьбы и не
            спрашивай заново то, что уже знаешь. Если человек сообщает новый факт о
            себе надолго — вызови инструмент remember.
            Если спрашивают, какая ты модель, — отвечай прямо и коротко: назови себя и
            модель, на которой работаешь (${model ?: "точное имя сейчас неизвестно — так и скажи"}).
            Не отказывайся называть модель: владелец имеет право знать, что отвечает на
            его вопросы. Не рассуждай вслух перед ответом — сразу пиши ответ.
        """.trimIndent()
        // Правила уходят всегда, даже когда памяти и профиля ещё нет: иначе на вопрос
        // «какая ты модель» модель осталась бы без разрешения называть себя.
        val base = if (parts.isEmpty()) rules else rules + "\n\n" + parts.joinToString("\n\n")
        // Перевод — первым блоком, ДО правил Марса: инструкция «переведи и верни только
        // перевод» обязана перебивать «отвечай по-русски, коротко и по делу», иначе
        // модель ответит по-русски о переводе, а не переводом.
        val translation = translateDirection
            ?.let { TranslatePolicy.promptOrNull(it) }
            ?.takeIf { it.isNotBlank() }
        return if (translation == null) base else translation + "\n\n" + base
    }

    private fun buildView(): View {
        scaffold = SectionScaffold.create(this, "Локальный чат", ::refresh)
        // Тап по подсказке ведёт в настройки: иначе «провайдер не настроен» — тупик.
        scaffold.statusLine.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { openSettings() }
        }

        scaffold.content.addView(
            headerRow(),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
        )

        // «Запомнил: …» — над лентой, сразу под шапкой: факт относится к диалогу в целом,
        // а не к последнему сообщению, и в ленте он занял бы место ответа.
        memoryNoticeView = MemoryNoticeView(this)
        scaffold.content.addView(
            memoryNoticeView!!.root,
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) },
        )

        transcript = LocalChatTranscript(
            context = this,
            onMessageLongPress = ::onMessageLongPress,
            onCopyText = ::copyToClipboard,
            onOpenUrl = ::openUrl,
            onSaveImage = ::saveGeneratedImage,
            onStoredImage = ::storedImage,
            onReply = ::replyToItem,
            onSpeakText = ::speak,
        )
        scaffold.content.addView(transcript.view, LinearLayout.LayoutParams(-1, 0, 1f))

        // Композер сам зовёт окно «Контекст и параметры» (пилюля слева внизу) и отдаёт
        // нажатия быстрых баблов: экран остаётся владельцем действий и текстов реплик.
        composer = LocalChatComposerBar(
            this,
            ::onPrimaryAction,
            ::pickAttachment,
            onOpenContextHub = ::showLocalContextAndSettingsSheet,
            onQuickAction = ::handleQuickAction,
        )
        voiceStatusLine = android.widget.TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFF9AA0A6.toInt())
            // Плашка, а не голая строка: живой транскрипт («🎙 Слушаю: „…“») должен
            // бросаться в глаза над композером, а не теряться между лентой и полем ввода.
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = MarsTheme.pill(
                this@LocalChatActivity,
                MarsTheme.SURFACE,
                strokeColor = MarsTheme.BORDER_SUBTLE,
            )
            visibility = android.view.View.GONE
        }
        scaffold.content.addView(
            voiceStatusLine,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
        )
        scaffold.content.addView(
            composer.root,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )

        // Голосовой ввод: микрофон → существующий ASR → текст прямо в композер.
        // Кнопка сама рисует себя по состоянию и скрывается, когда распознавания на
        // устройстве нет (модель не скачана) — мёртвой кнопки не бывает.
        voice = LocalVoiceButton(
            activity = this,
            // Голос — это вопрос, а не черновик: после распознавания сразу отправляем.
            // Иначе контроллер уходит в «Думаю» и ждёт ответ на неотправленный запрос
            // (проверено на POCO 29.10: текст в поле, кнопка висит в «Думаю»).
            onTranscript = { text ->
                if (text.isNotBlank()) {
                    composer.input.setText(text)
                    composer.input.setSelection(text.length)
                    send()
                }
            },
            onState = { state ->
                state.notice?.let(scaffold.statusLine::setText)
                // Живой транскрипт над композером: партиал меняется на каждом слове, и
                // человек обязан видеть, что микрофон действительно его слышит.
                renderVoiceStatusLine(state)
            },
            onOpenX = { openAppSettings() },
            serverTts = RepositoryServerTtsClient(
                baseUrl = ChatActivity.BASE_URL,
                credentialProvider = { runCatching { credentialStore.read() }.getOrNull() },
                selector = SpeechVoiceProfileSelector(),
                prosodyProvider = { voiceProsody },
            ),
            // Офлайн-Piper для автоозвучки: без него ответ читает системный робот.
            offlineTts = { offlineTts() },
        )
        // Голос живёт внутри капсулы композера, слева от поля ввода — как в серверном чате.
        // Отдельной строкой под композером кнопка отнимала место у ленты и была неудобна.
        composer.attachVoice(voice.root)
        // Темп и голос — из настроек, до первого звука: движки держат их глобально и
        // берут при следующей реплике, поэтому первая же фраза зазвучит как настроено.
        // Без этого человек слышал бы дефолт после каждого запуска, а свой выбор — только
        // внутри текущей сессии.
        runCatching {
            val saved = voiceProsodyStore.load()
            voiceProsody = saved
            voice.setProsody(saved)
            updateVoiceProsodyChip(saved)
        }
        return scaffold.root
    }

    /**
     * Включить/выключить прослушивание «Марс, слушай» в фоне.
     *
     * Сервис держит микрофон и озвучку при погашенном экране. Стартуем ТОЛЬКО отсюда,
     * с активного экрана: Android 12+ запрещает запуск микрофонного foreground-сервиса
     * из фона. Если разрешение отозвано — просим его, а не молчим.
     */
    private fun toggleWakeService() {
        if (wakeArmed) {
            MarsVoiceService.stop(this)
            wakeArmed = false
            updateWakeChip()
            presenter.setNotice("Прослушивание выключено")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_WAKE_MIC)
            return
        }
        if (MarsVoiceService.start(this)) {
            wakeArmed = true
            updateWakeChip()
            presenter.setNotice("Скажите «Марс, слушай» — экран можно погасить")
        } else {
            presenter.setNotice("Не удалось включить прослушивание: движок недоступен")
        }
    }

    private fun updateWakeChip() {
        if (!::wakeChip.isInitialized) return
        wakeChip.text = if (wakeArmed) "Слушаю фон" else "Фон"
        wakeChip.alpha = if (wakeArmed) 1f else 0.6f
        wakeChip.contentDescription =
            if (wakeArmed) "Прослушивание включено. Выключить" else "Включить «Марс, слушай»"
    }

    /**
     * Строка под заголовком: название диалога слева, две компактные кнопки справа.
     *
     * Раньше здесь в одну строку без скролла стояли восемь элементов (заголовок и семь
     * чипов) — на 360dp они сплющивали друг друга и обрезались по буквам. На экране
     * осталось только то, что нужно каждый раз: имя диалога, вход в полноэкранный
     * разговор и новый чистый чат.
     *
     * Остальные чипы (`modelButton`, `reasoningButton`, `paramsButton`,
     * `voiceProsodyButton`, `wakeChip`, `memoryChip`) по-прежнему инициализируются — их
     * подписи обновляет [render], — но живут в скрытом контейнере: их настройки переехали
     * в единое окно «Контекст и параметры» ([showLocalContextAndSettingsSheet]).
     * Сносить поля нельзя: `render()` и обработчики шторок обращаются к ним напрямую.
     */
    private fun headerRow(): View {
        wakeChip = chip(::toggleWakeService).apply {
            text = if (wakeArmed) "Слушаю фон" else "Фон"
        }
        updateWakeChip()
        // Память рядом с «Фон»: вход в профиль и запомненные факты.
        memoryChip = chip { startActivity(LocalMemoryActivity.newIntent(this)) }.apply {
            text = "Память"
            contentDescription = "Профиль и память модели"
        }
        subtitle = TextView(this).apply {
            text = session?.title.orEmpty()
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // Заголовок — взвешенный элемент: без min-width 0 длинное имя диалога
            // выдавливает чипы за правый край.
            minimumWidth = 0
        }

        modelButton = chip(::chooseModel).apply {
            contentDescription = "Выбрать модель"
            // Текущая модель + шеврон: тап открывает каталог провайдера.
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                null,
                null,
                ContextCompat.getDrawable(this@LocalChatActivity, R.drawable.ic_chevron_down)
                    ?.mutate()
                    ?.apply {
                        setTint(MarsTheme.TEXT_MUTED)
                        val size = dp(12)
                        setBounds(0, 0, size, size)
                    },
                null,
            )
            compoundDrawablePadding = dp(4)
        }

        reasoningButton = chip(::chooseReasoning).apply {
            contentDescription = "Уровень размышлений"
            // Видимость решает render(): у модели без reasoning_controls кнопки нет.
            visibility = View.GONE
        }

        // «Параметры» — сэмпсинг этого диалога. Отдельный чип, а не пункт меню модели:
        // там выбирают ЧЕМ отвечать, а здесь — КАК, и смешивать эти два вопроса в одном
        // списке значит каждый раз вспоминать, в каком из них температура.
        paramsButton = chip(::openTurnSettings).apply {
            contentDescription = "Параметры хода: температура, top_p, лимиты"
        }

        // «Голос» — темп и голос озвучки. Стоит рядом с параметрами модели, потому что
        // человек ищет «как звучит» там же, где «как отвечает».
        // Слой создаётся здесь, а не лениво в поле: ему нужен уже собранный корень
        // экрана, и он же кладёт себя в него при первом показе.
        voiceMode = VoiceModeOverlay(this)
        // Перехват «назад» ставится сразу: он выключен, пока слой скрыт, и
        // включается в renderVoiceMode при показе.
        installVoiceModeBack()

        voiceProsodyButton = chip(::openVoiceProsody).apply {
            contentDescription = "Голос и темп речи"
        }

        // «Разговор» — тот же микрофон, но на весь экран: орб, фаза и одна кнопка.
        // Отдельным чипом, а не вторым режимом кнопки-микрофона: это ДРУГОЙ способ
        // говорить (смотрю на экран, а не на клавиатуру), и путать его с обычным
        // голосовым вводом значило бы менять поведение микрофона молча.
        voiceModeButton = chip(::openVoiceMode).apply {
            text = VOICE_MODE_CHIP
            contentDescription = "Полноэкранный голосовой режим"
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                ContextCompat.getDrawable(this@LocalChatActivity, R.drawable.ic_mic)
                    ?.mutate()
                    ?.apply {
                        setTint(MarsTheme.TEXT_EM)
                        val size = dp(12)
                        setBounds(0, 0, size, size)
                    },
                null,
                null,
                null,
            )
            compoundDrawablePadding = dp(4)
        }

        // «Новый» — единственный способ начать чистый диалог прямо с экрана чата:
        // очистка ленты и сброс сессии живут в [startNewLocalChat].
        newChatButton = chip(::startNewLocalChat).apply {
            text = "Новый"
            contentDescription = "Новый чистый диалог"
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                ContextCompat.getDrawable(this@LocalChatActivity, R.drawable.ic_add)
                    ?.mutate()
                    ?.apply {
                        setTint(MarsTheme.TEXT_EM)
                        val size = dp(12)
                        setBounds(0, 0, size, size)
                    },
                null,
                null,
                null,
            )
            compoundDrawablePadding = dp(4)
        }

        // Видимая строка: имя диалога забирает весь остаток ширины, кнопки — свои
        // собственные. Никаких восьми элементов в ряд: два чипа по краю.
        val visibleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(subtitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(
                voiceModeButton,
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
            )
            addView(
                newChatButton,
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
            )
        }

        // Скрытый контейнер: чипы живы (render() пишет в них подписи и видимость), но
        // с экрана убраны — их настройки открываются единым окном из композера.
        val hiddenControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(modelButton, LinearLayout.LayoutParams(-2, -2))
            addView(reasoningButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(paramsButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(voiceProsodyButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(wakeChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            addView(memoryChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(visibleRow, LinearLayout.LayoutParams(-1, -2))
            addView(hiddenControls, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * Открыть шторку параметров хода.
     *
     * Дефолты для «Сбросить» считаются от текущей модели: у неё может быть известный из
     * каталога потолок ответа, и после сброса он обязан вернуться — иначе человек получил
     * бы «провайдер сам» там, где модель честно сообщила предел.
     */
    /**
     * Открыть шторку «Голос и темп».
     *
     * Темп сохраняется в настройках приложения ([VoiceProsodyPrefs]) и применяется к
     * движку сразу, ещё до закрытия шторки: человек должен услышать правку на кнопке
     * «Проверить», а не узнавать о ней в следующем ответе.
     *
     * «Проверить» синтезирует короткую фразу тем же путём, что обычная озвучка: через
     * [voice], то есть с учётом того, какой движок сейчас в работе. Отдельного движка
     * для проверки нет — иначе проверялся бы не тот голос, который звучит в ответах.
     */
    /**
     * Войти в полноэкранный разговор.
     *
     * Слой кладётся поверх корня экрана и получает колбэки, которые дёргают ТОТ ЖЕ
     * голосовой канал, что и кнопка в композере: [LocalVoiceButton] остаётся
     * единственным владельцем микрофона, а слой — только его лицом на весь экран.
     *
     * Первый снимок берётся сразу: без него человек увидел бы пустой экран до первого
     * изменения фазы, то есть до того, как сам начнёт говорить.
     */
    private fun openVoiceMode() {
        voiceMode.callbacks = object : VoiceModeOverlay.Callbacks {
            override fun onMicTap() = voice.controller.toggle()
            override fun onStopSpeech() = voice.stopSpeaking()
            override fun onRetry() = retryLastTurn()
            override fun onPickDirection() = pickTranslateDirection()
            override fun onClose() {
                // Уход из режима не должен оставлять открытый микрофон: иначе человек
                // вернулся в чат, а его продолжают слушать.
                if (voice.controller.state().phase == LocalVoicePhase.LISTENING) voice.controller.toggle()
            }
        }
        voiceMode.show(scaffold.root as android.view.ViewGroup)
        voiceMode.renderDirection(translateDirection)
        voiceMode.render(voiceModeSnapshot())
    }

    /**
     * Снимок для полноэкранного орба: фаза канала + обе строки диалога.
     *
     * [VoiceModeState.from] несёт только партиал реплики, и на орбе после ответа
     * оставался один вопрос — ответа Марса не было видно вовсе. Поэтому строки
     * дополняются из ленты: партиал важнее (человек говорит прямо сейчас), а без него —
     * последняя реплика человека и последний ответ ассистента. Считается на каждый
     * кадр, потому что лента дописывается дельтами.
     */
    private fun voiceModeSnapshot(): VoiceModeState {
        val base = if (::voice.isInitialized) {
            VoiceModeState.from(voice.controller.state())
        } else {
            VoiceModeState()
        }
        val items = presenter.state.value.items
        val lastUser = items.lastOrNull { it.role == LocalRole.USER }?.text.orEmpty()
        val lastAssistant = items.lastOrNull { it.role == LocalRole.ASSISTANT }?.text.orEmpty()
        return base.copy(
            userLine = base.userLine.ifBlank { lastUser },
            assistantLine = lastAssistant,
        )
    }

    /**
     * Выбор направления перевода из голосового режима.
     *
     * Исходное значение — текущее направление или «с русского на английский»: лист не
     * открывается с пустым выбором. Выбранное направление сразу уходит в слой — человек
     * видит включённый режим, ещё не сказав ни слова.
     *
     * «Выключить» — отдельное нажатие по подписи уже включённого перевода: шторка не
     * нужна для того, чтобы вернуться к обычному разговору.
     */
    private fun pickTranslateDirection() {
        val current = translateDirection
        if (current != null) {
            // Второй тап по включённому переводу выключает его: это быстрее, чем искать
            // «выкл» в шторке, и не требует лишнего экрана на ходу.
            translateDirection = null
            if (::voiceMode.isInitialized) voiceMode.renderDirection(null)
            return
        }
        TranslateSheet.show(
            activity = this,
            current = TranslatePolicy.defaultDirection(),
            onApply = { direction ->
                translateDirection = direction
                if (::voiceMode.isInitialized) voiceMode.renderDirection(direction)
            },
        )
    }

    /**
     * Перерисовать слой под текущую фазу канала.
     *
     * Зовётся из [render] на каждое изменение состояния: дельты стрима идут потоком, а
     * лишний `render` у орба дешёвый (кадры перезапускаются только при смене фазы).
     */
    private fun renderVoiceMode() {
        // Перехват «назад» включается ровно на время показа слоя: иначе он глотал бы
        // системный жест в обычном чате.
        voiceModeBack?.isEnabled = ::voiceMode.isInitialized && voiceMode.isShowing
        if (!::voiceMode.isInitialized || !voiceMode.isShowing) return
        if (!::voice.isInitialized) return
        voiceMode.render(voiceModeSnapshot())
    }

    /** Системный «назад» в голосовом режиме: сначала свернуть его, потом выходить. */
    private fun closeVoiceModeIfShown(): Boolean {
        if (!::voiceMode.isInitialized || !voiceMode.isShowing) return false
        voiceMode.hide()
        if (voice.controller.state().phase == LocalVoicePhase.LISTENING) voice.controller.toggle()
        return true
    }

    private fun openVoiceProsody() {
        VoiceProsodySheet.show(
            activity = this,
            current = voiceProsodyStore.load(),
            onApply = { next ->
                voiceProsody = next
                voiceProsodyStore.save(next)
                if (::voice.isInitialized) voice.setProsody(next)
                updateVoiceProsodyChip(next)
            },
            onPreview = { draft ->
                if (::voice.isInitialized) {
                    voice.setProsody(draft)
                    voice.speakNow(VoiceProsodyPolicy.PREVIEW_TEXT)
                }
            },
        )
    }

    /**
     * Подпись чипа «Голос»: текущий темп или «Голос», если настройка обычная.
     *
     * Показывается только темп: имя голоса заняло бы всю строку, а человек меняет его
     * редко и помнит, что выбрал. Темп же правится чаще и сразу слышен.
     */
    private fun updateVoiceProsodyChip(prosody: VoiceProsody) {
        val label = if (prosody.isDefault) DEFAULT_VOICE_CHIP else prosody.speedLabel()
        if (voiceProsodyButton.text.toString() != label) voiceProsodyButton.text = label
    }

    private fun openTurnSettings() {
        val state = presenter.state.value
        // Модель может быть не выбрана (первый запуск): тогда потолок неизвестен и его
        // подставит профиль, а не «пустая строка вместо имени модели».
        val model = state.model?.takeIf { it.isNotBlank() }?.let { catalogInfo(it) }
        LocalTurnSettingsSheet.show(
            this,
            state.turnSettings,
            model?.maxOutputTokens,
            onSave = { next ->
                presenter.setTurnSettings(next)
                persistTurnSettings(next)
            },
            onReset = {
                val defaults = LocalTurnSettings.defaultsFor(
                    model,
                    activeProfileForDefaults(state),
                )
                presenter.setTurnSettings(defaults)
                persistTurnSettings(defaults)
            },
        )
    }

    /**
     * Профиль для расчёта дефолтов параметров хода.
     *
     * Берём активный профиль хранилища, а не состояние кнопки: после смены модели
     * презентер уже несёт новую модель, а `maxTokens` профиля задаёт верхнюю границу
     * «лимита ответа». Нет профиля — пустая заготовка: [LocalTurnSettings.defaultsFor]
     * переживает `null`-поля и вернёт «провайдер сам».
     */
    private fun activeProfileForDefaults(state: LocalChatUiState): LocalProviderProfile? =
        providerStore.state().active?.let { profile ->
            if (state.model.isNullOrBlank()) profile else profile.copy(model = state.model)
        }

    /** Снимок параметров в мету сессии: перезапуск приложения вернёт те же значения. */
    private fun persistTurnSettings(settings: LocalTurnSettings) {
        val id = session?.id ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { sessionStore.updateTurnSettings(id, settings.toJson()) }
        }
    }

    /** Компактный чип-кнопка: капсула с текстом, максимум 44% ширины экрана. */
    private fun chip(onClick: () -> Unit): TextView = TextView(this).apply {
        textSize = 11.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(MarsTheme.TEXT_EM)
        background = MarsTheme.pill(this@LocalChatActivity, MarsTheme.SURFACE, MarsTheme.BORDER)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = (resources.displayMetrics.widthPixels * 0.44f).toInt()
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * Единое окно «Контекст и параметры» локального чата.
     *
     * Раньше все настройки висели семью мелкими чипами в одной строке под шапкой и
     * сплющивали друг друга до нечитаемости. Теперь вход один — пилюля контекста в
     * композере, — а внутри стеклянного [MarsDialog] всё разложено по трём полочкам, как
     * в серверном чате (`ChatActivity.showContextUsageDetails`):
     *
     * 1. окно контекста и расход диалога (полоса заполнения + точные цифры токенов);
     * 2. модель, мышление и параметры генерации (строки-карточки, тап открывает пикер);
     * 3. голос, память и фоновый слух.
     *
     * Ничего не пересчитывается здесь заново: проценты и текст разбивки берутся из
     * [LocalContextPolicy], действия — прежние методы экрана. Окно только раскладывает.
     */
    private fun showLocalContextAndSettingsSheet() {
        val state = presenter.state.value
        val windowTokens = state.context?.windowTokens
        val percent = state.context?.takeIf { it.visible }?.percent
        val level = state.context?.takeIf { it.visible }?.level
        var dialog: android.app.Dialog? = null

        fun closeWindow() {
            dialog?.dismiss()
        }

        /** Заголовок полочки: маленькие капс-буквы акцентом, как в шторках серверного чата. */
        fun sectionTitle(title: String): View = TextView(this).apply {
            text = title
            textSize = 10f
            setTextColor(MarsTheme.ACCENT_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            includeFontPadding = false
            setPadding(0, dp(16), 0, dp(8))
        }

        /** Строка «подпись слева — значение справа» внутри карточки. */
        fun statRow(title: String, value: String): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@LocalChatActivity).apply {
                    text = title
                    textSize = 12.5f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(
                TextView(this@LocalChatActivity).apply {
                    text = value
                    textSize = 12.5f
                    setTextColor(MarsTheme.TEXT_STRONG)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.END
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) },
            )
        }

        /**
         * Кликабельная стеклянная строка-карточка параметра: слева название, справа
         * текущее значение, на краю шеврон. Тап закрывает окно и открывает пикер —
         * второй слой поверх шторки читался бы как «ничего не произошло».
         */
        fun controlRow(title: String, value: String, onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = MarsTheme.card(
                    this@LocalChatActivity,
                    MarsTheme.SURFACE_SUBTLE,
                    radiusDp = 12,
                    strokeColor = MarsTheme.BORDER_SUBTLE,
                )
                setPadding(dp(14), dp(11), dp(12), dp(11))
                isClickable = true
                isFocusable = true
                contentDescription = "$title: $value"
                setOnClickListener { onClick() }
                addView(
                    TextView(this@LocalChatActivity).apply {
                        text = title
                        textSize = 12.5f
                        setTextColor(MarsTheme.TEXT)
                        maxLines = 2
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                addView(
                    TextView(this@LocalChatActivity).apply {
                        text = value
                        textSize = 12f
                        setTextColor(MarsTheme.ACCENT_TEXT)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.END
                        maxWidth = dp(176)
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) },
                )
                addView(
                    android.widget.ImageView(this@LocalChatActivity).apply {
                        setImageDrawable(
                            ContextCompat.getDrawable(this@LocalChatActivity, R.drawable.ic_arrow_forward),
                        )
                        imageTintList =
                            android.content.res.ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
                    },
                    LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8) },
                )
            }

        // --- Полочка 1: окно контекста и расход ---------------------------------------
        // Цвет полосы — тот же порог, что у пилюли в композере: одна цифра не может
        // быть зелёной внизу и красной в окне.
        val tone = when (level) {
            LocalContextLevel.CRITICAL -> MarsTheme.STATUS_ERROR
            LocalContextLevel.WARNING -> MarsTheme.STATUS_WARNING
            else -> MarsTheme.STATUS_ONLINE
        }
        val fillBar = ContextFillBarView(this).apply { render(percent, tone) }
        val contextCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.glassSurface(this@LocalChatActivity, radiusDp = 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(statRow("Заполнение контекста", percent?.let { "$it%" } ?: "не измерено"))
            addView(
                fillBar,
                LinearLayout.LayoutParams(-1, dp(ContextFillBarView.BAR_HEIGHT_DP))
                    .apply { topMargin = dp(8) },
            )
            addView(
                TextView(this@LocalChatActivity).apply {
                    // Текст политики: сколько занято из окна, сколько ушло на запрос и
                    // ответ и где пороги. Экран не считает это второй раз.
                    text = LocalContextPolicy.detailsText(state.usage, windowTokens)
                    textSize = 12f
                    setTextColor(MarsTheme.TEXT_EM)
                    setLineSpacing(dp(2).toFloat(), 1f)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
            )
        }

        // --- Полочка 2: модель, мышление и генерация ----------------------------------
        val modelRow = controlRow(
            "Модель провайдера",
            state.model?.takeIf { it.isNotBlank() } ?: "Не выбрана",
        ) {
            closeWindow()
            chooseModel()
        }
        val reasoningRow = controlRow(
            "Уровень размышлений (Reasoning)",
            if (state.supportsReasoning) {
                LocalChatModelPicker.label(state.reasoningEffort)
            } else {
                "Не поддерживается моделью"
            },
        ) {
            // У модели без reasoning_controls строки-пикера нет: мёртвый тап хуже
            // честной подписи «не поддерживается».
            if (state.supportsReasoning) {
                closeWindow()
                chooseReasoning()
            }
        }
        val samplingRow = controlRow(
            "Параметры сэмплинга (Temp, Top-P, Лимит)",
            if (state.turnSettingsCustom) "Настроены вручную *" else "По умолчанию",
        ) {
            closeWindow()
            openTurnSettings()
        }

        // --- Полочка 3: голос, память и фоновый слух -----------------------------------
        val voiceModeRow = controlRow(
            "Полноэкранный голосовой разговор",
            "Живой диалог с орбом и перебиванием",
        ) {
            closeWindow()
            openVoiceMode()
        }
        val prosodyValue = voiceProsody.speedLabel() + " · " +
            if (hasOfflineVoice()) "Голос Ирины готов (22 кГц)" else "Подготовка / системный резерв"
        val prosodyRow = controlRow("Голос и темп речи (Piper офлайн)", prosodyValue) {
            closeWindow()
            openVoiceProsody()
        }
        val wakeRow = controlRow(
            "Фоновый слух („Марс, слушай“)",
            if (wakeArmed) "Включено — слушает в фоне" else "Выключено",
        ) {
            // Окно закрываем до переключения: включение может запросить разрешение на
            // микрофон, и системный диалог поверх шторки выглядел бы зависанием.
            closeWindow()
            toggleWakeService()
        }
        val memoryRow = controlRow(
            "Память владельца и факты",
            "Профиль и долгосрочная память на телефоне",
        ) {
            closeWindow()
            startActivity(LocalMemoryActivity.newIntent(this@LocalChatActivity))
        }

        dialog = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Контекст и параметры", null))

            root.addView(sectionTitle("ОКНО КОНТЕКСТА И РАСХОД"))
            root.addView(contextCard, LinearLayout.LayoutParams(-1, -2))
            root.addView(
                MarsDialog.primaryButton(this, "Новый диалог") {
                    closeWindow()
                    startNewLocalChat()
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
            )
            root.addView(
                MarsDialog.secondaryButton(this, "Настройки провайдера / API-ключ") {
                    closeWindow()
                    openSettings()
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
            )

            root.addView(sectionTitle("МОДЕЛЬ, МЫШЛЕНИЕ И ГЕНЕРАЦИЯ"))
            listOf(modelRow, reasoningRow, samplingRow).forEachIndexed { index, row ->
                root.addView(
                    row,
                    LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) },
                )
            }

            root.addView(sectionTitle("ГОЛОС, ПАМЯТЬ И ФОНОВЫЙ СЛУХ"))
            listOf(voiceModeRow, prosodyRow, wakeRow, memoryRow).forEachIndexed { index, row ->
                root.addView(
                    row,
                    LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) },
                )
            }

            root.addView(
                MarsDialog.quietButton(this, "Закрыть") { closeWindow() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
            )
            emptyList()
        }
    }

    /**
     * Быстрые баблы композера.
     *
     * «Ок» и «Продолжай» — готовые реплики на типовой ответ модели («вот план»,
     * «оборвался на полуслове»): набирать их вручную на ходу незачем. Реплика уходит
     * обычным сообщением (видна в ленте), а не служебной строкой. «Новый» — чистый диалог.
     */
    private fun handleQuickAction(action: String) {
        when (action.lowercase(Locale.ROOT)) {
            "ok", "ок" -> quickSend(QUICK_OK)
            "continue", "продолжай" -> quickSend(QUICK_CONTINUE)
            "new", "новый" -> startNewLocalChat()
            else -> Unit
        }
    }

    /** Отправить готовую реплику так, будто человек набрал её сам. */
    private fun quickSend(text: String) {
        if (presenter.state.value.streaming) return
        composer.input.setText(text)
        composer.input.setSelection(text.length)
        send()
    }

    /**
     * Новый чистый диалог, не уходя в список.
     *
     * Экран пересоздаётся без id сессии: так сбрасываются и лента, и мета расхода, и
     * параметры хода — то же, что даёт «Новый чат» в списке. Если диалог и так пуст,
     * перезапускать нечего: чистим поле ввода и вложения.
     */
    private fun startNewLocalChat() {
        if (presenter.state.value.streaming) stopTurn()
        val empty = session == null && presenter.state.value.items.isEmpty()
        if (empty) {
            composer.input.setText("")
            clearAttachments()
            presenter.setNotice("Новый диалог")
            return
        }
        startActivity(LocalChatActivity.newIntent(this, null))
        finish()
    }

    private fun render(state: LocalChatUiState) {
        // Слой голосового режима живёт на том же состоянии: фаза канала меняется от
        // действий на нём же, поэтому обновляется на каждый render, а не только при входе.
        renderVoiceMode()
        transcript.render(state)
        composer.render(state)
        // Пилюля контекста в композере показывает модель, а быстрые баблы — только когда
        // есть что продолжать и ответ не идёт: во время стрима они уходили бы в пустоту.
        composer.setModelSummary(state.model?.takeIf { it.isNotBlank() } ?: "Контекст")
        composer.setQuickChipsVisible(state.items.isNotEmpty() && !state.streaming)

        // Подписи чипов меняются редко, а render() зовётся на каждой дельте стрима:
        // лишний setText каждый кадр — лишний layout.
        val modelLabel = state.model ?: "Модель"
        if (modelButton.text.toString() != modelLabel) modelButton.text = modelLabel
        // Reasoning показывается ТОЛЬКО при поддержке моделью: иначе это мёртвая кнопка,
        // которая молча ничего не меняет (§2 контракта: поле есть у 14 моделей из 85).
        reasoningButton.visibility = if (state.supportsReasoning) View.VISIBLE else View.GONE
        if (state.supportsReasoning) {
            val effortLabel = LocalChatModelPicker.label(state.reasoningEffort)
            if (reasoningButton.text.toString() != effortLabel) reasoningButton.text = effortLabel
        }

        // «Параметры»: звёздочка, только когда человек реально что-то задал. Подпись
        // статичная — чип про состояние, а не про значения (их видно в шторке).
        val paramsLabel = if (state.turnSettingsCustom) "Параметры *" else "Параметры"
        if (paramsButton.text.toString() != paramsLabel) paramsButton.text = paramsLabel

        scaffold.progress.visibility = if (state.streaming) View.VISIBLE else View.GONE

        // Голос: ранний старт и подача по дельтам.
        //
        // Раньше ответ отдавался синтезу целиком в конце — человек ждал тишины, пока
        // модель домолчит. Теперь первый кусок уходит синтезу сразу, а остальное — по
        // мере поступления. `voiceSpokenChars` считает отданное, чтобы дельта не
        // повторяла уже сказанное: текст в ленте дописывается, а не заменяется.
        if (::voice.isInitialized) {
            val assistant = state.items.lastOrNull { it.role == LocalRole.ASSISTANT }
            val assistantText = assistant?.text.orEmpty()
            if (state.streaming && !voiceStreaming) {
                voiceStreaming = voice.onAnswerStarted()
                voiceSpokenChars = 0
            }
            if (state.streaming && voiceStreaming) {
                if (assistantText.length > voiceSpokenChars) {
                    voice.onAnswerDelta(assistantText.substring(voiceSpokenChars))
                    voiceSpokenChars = assistantText.length
                }
                // Размышления без текста: вместо тишины короткая подсказка один раз за ход.
                if (assistantText.isEmpty() && assistant?.thinking?.isNotEmpty() == true) {
                    voice.onReasoningStarted()
                }
            }
        }

        // Ход закончился: отдать ответ голосу. Без этого кнопка навсегда остаётся в
        // «Думаю» — проверено на POCO 29.10: ответ пришёл, кнопка висела.
        if (wasStreaming && !state.streaming) {
            val answer = state.items.lastOrNull { it.role == LocalRole.ASSISTANT }?.text
            if (::voice.isInitialized) {
                // Если ответ уже звучал потоком — только закрыть поток, не повторяя его.
                if (voiceStreaming) voice.onAnswerEnded() else voice.onAnswerCompleted(answer)
                voice.render(voice.controller.state())
            }
            voiceStreaming = false
            voiceSpokenChars = 0
        }
        wasStreaming = state.streaming

        renderMemoryNotice(state.memoryNotice)

        val notice = state.error ?: state.notice
        scaffold.statusLine.apply {
            text = notice.orEmpty()
            visibility = if (notice == null) View.GONE else View.VISIBLE
        }

        // Живой статус микрофона: фаза меняется не только кнопкой, но и ходом (реплика
        // ушла → «думаю»), поэтому строка обновляется на каждом кадре состояния.
        if (::voice.isInitialized) renderVoiceStatusLine(voice.controller.state())
    }

    /**
     * Показать «Запомнил: …» и убрать его по сроку.
     *
     * Срок держит экран, а не вьюха: время снимается одним [Runnable] на экран, поэтому
     * новая запись отменяет снятие предыдущей. Иначе таймер старого факта погасил бы
     * новый — человек не увидел бы второго «Запомнил» вовсе.
     *
     * Повтор того же факта не перезапускает анимацию и не мигает: сравниваем ссылку —
     * состояние публикует новый объект только на новую запись.
     */
    private fun renderMemoryNotice(notice: MemoryNotice?) {
        val view = memoryNoticeView ?: return
        if (notice == null) {
            memoryNoticeHide?.let { scaffold.root.removeCallbacks(it) }
            memoryNoticeHide = null
            view.hide()
            return
        }
        if (notice === lastMemoryNotice) return
        lastMemoryNotice = notice
        // Просроченный факт показывать нечего: он пришёл из восстановленного состояния.
        if (MemoryNoticePolicy.isExpired(notice, System.currentTimeMillis())) {
            view.hide()
            return
        }
        memoryNoticeHide?.let { scaffold.root.removeCallbacks(it) }
        view.show(notice)
        val hide = Runnable {
            view.hide()
            memoryNoticeHide = null
        }
        memoryNoticeHide = hide
        scaffold.root.postDelayed(hide, MemoryNoticePolicy.LIFETIME_MS)
    }

    /** Клик единственной кнопки композера: во время хода — стоп, иначе отправка. */
    private fun onPrimaryAction() {
        if (presenter.state.value.streaming) stopTurn() else send()
    }

    private fun send(internal: String? = null): Job? {
        // [internal] — служебная реплика (сейчас это «Продолжить»): она уходит модели,
        // но не рисуется в ленте и не подменяет содержимое поля ввода.
        val text = internal?.trim() ?: composer.input.text.toString().trim()
        // Вложение без текста — нормальный запрос («что на фото?» человек не писал).
        val pending = attachments
        if (text.isEmpty() && pending.isEmpty()) return null
        // Презентер молча игнорирует send во время хода: если бы мы очистили чипы
        // раньше него, вложения исчезли бы, так и не уехав.
        if (presenter.state.value.streaming) return null

        val profile = providerStore.state().active
        if (profile == null) {
            presenter.setNotice(NOT_CONFIGURED)
            return null
        }
        val apiKey = providerStore.apiKey(profile.id)
        if (apiKey.isNullOrBlank()) {
            presenter.setNotice(NO_KEY)
            return null
        }
        // Что именно уйдёт в запрос: id профиля, модель и адрес. Без этой строки разбор
        // «отвечает не та модель» упирается в догадки — см. `adb logcat -s MarsModel`.
        MarsModelLog.log("send id=${profile.id} model=${profile.model} base=${profile.baseUrl}")

        // Новый ход: заглушить прошлую озвучку, иначе она говорит поверх ответа на новый вопрос.
        if (::voice.isInitialized) voice.onTurnStarted()

        // Диалог создаётся первым сообщением: пустые «Новые диалоги» в списке не нужны.
        if (session == null) {
            val created = sessionStore.create()
            session = created
            subtitle.text = created.title
        }

        val body = if (internal != null) internal.trim() else bodyWithAttachmentMarks(text, pending)
        if (internal == null) composer.input.setText("")
        // Вложения ушли с этим ходом — чипы снимаем сразу: иначе они висят всю длину
        // ответа и читаются как «не отправлено».
        clearAttachments()
        transcript.scrollToBottom()
        // Ход идёт в собственной области ([turnScope]), а не в `lifecycleScope`: свернутый или
        // погашенный экран не должен отменять ни ответ, ни его сохранение в сессию.
        val started = turnScope.launch {
            presenter.send(
                profile = profile,
                apiKey = apiKey,
                tools = LocalChatToolSource.schemas(deviceTools),
                text = body,
                // Вложения едут в исходящем LocalMessage: презентер кладёт их в поле
                // attachments, а тело запроса собирает транспорт ядра.
                attachments = pending,
                // Служебная реплика не попадает в ленту: человек нажал действие,
                // а не написал сообщение.
                showInFeed = internal == null,
            )
        }
        turn = started
        return started
    }

    private fun stopTurn() {
        turn?.cancel()
        turn = null
        presenter.cancelLocally()
    }

    /**
     * Открыть системный выбор файла. MIME-фильтр — «любой тип» намеренно: провайдер файлов
     * часто не знает тип и отдаёт `application/octet-stream`, а фильтр только по картинкам
     * и тексту спрятал бы половину документов. Что файл не годится, решает [loadAttachment]
     * — уже после выбора и с честной фразой, а не молчаливым пустым списком.
     */
    private fun pickAttachment() {
        runCatching { attachmentPicker.launch(arrayOf("*/*")) }
            .onFailure {
                Toast.makeText(this, "Нечем открыть выбор файла", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Прочитать выбранный документ и прикрепить его к следующему сообщению.
     *
     * Чтение — в IO-потоке: картинка на 8 МБ с флешки читается и кодируется в base64
     * секундами, и на главном потоке это был бы ANR. UI-часть — только по возвращении.
     */
    private fun loadAttachment(uri: Uri) {
        val mime = resolveMime(uri)
        val name = displayName(uri)
        // Тип определяем с именем: PDF/DOCX/XLSX/PPTX провайдер не сообщает как mime,
        // а расширение говорит о формате прямо.
        val kind = kindOf(mime, name)
        if (kind == null) {
            // Молчание здесь — худший исход: человек уверен, что файл приложен.
            Toast.makeText(this, REJECT_UNSUPPORTED_TYPE, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readAttachment(uri, kind, mime, name) }
            when (result) {
                is AttachmentLoad.Ok -> {
                    attachments = attachments + result.attachment
                    renderAttachments()
                }
                is AttachmentLoad.Failed ->
                    Toast.makeText(this@LocalChatActivity, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Байты документа → [LocalAttachment]. Выполняется на IO-потоке.
     *
     * Сначала спрашиваем объявленный размер (`OpenableColumns.SIZE`), чтобы не тянуть в
     * память 200-мегабайтное видео ради фразы «слишком большой»; затем читаем не больше
     * лимита плюс байт — так превышение видно, даже если провайдер соврал о размере.
     */
    private fun readAttachment(
        uri: Uri,
        kind: AttachmentKind,
        mime: String,
        name: String?,
    ): AttachmentLoad {
        val limit = when (kind) {
            AttachmentKind.IMAGE -> MAX_IMAGE_BYTES
            // PDF/DOCX на 1 МБ — обычное дело, и текста в них немного: лимит на файл
            // держим выше, а объём извлечённого текста ограничивает сам экстрактор.
            AttachmentKind.DOCUMENT -> MAX_DOCUMENT_BYTES
            AttachmentKind.TEXT_FILE -> MAX_TEXT_BYTES
        }
        val declared = declaredSize(uri)
        if (declared != null && declared > limit) return AttachmentLoad.Failed(tooLarge(kind))

        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { readBounded(it, limit) }
        }.getOrNull() ?: return AttachmentLoad.Failed("Файл недоступен для чтения")
        if (bytes.isEmpty()) return AttachmentLoad.Failed("Файл пустой — прикреплять нечего")
        if (bytes.size > limit) return AttachmentLoad.Failed(tooLarge(kind))

        val attachment = when (kind) {
            // Картинка уходит провайдеру как data-url: base64 без переводов строки —
            // иначе URL рвётся на первом же переносе.
            AttachmentKind.IMAGE -> LocalAttachment(
                kind = kind,
                mimeType = mime,
                fileName = name,
                dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
            // Текстовый файл уходит текстом с пометкой имени — так его видит модель.
            AttachmentKind.TEXT_FILE -> LocalAttachment(
                kind = kind,
                mimeType = mime,
                fileName = name,
                textContent = String(bytes, Charsets.UTF_8),
            )
            // Документ распаковывается в текст здесь же, на телефоне: PDF/DOCX/XLSX/PPTX
            // провайдер как бинарник не принимает, а извлечённый текст принимает.
            AttachmentKind.DOCUMENT -> {
                val text = DocumentTextExtractor.extract(bytes, name.orEmpty(), mime)
                    .getOrElse { failure ->
                        return AttachmentLoad.Failed(
                            failure.message ?: "не удалось прочитать документ",
                        )
                    }
                if (text.isBlank()) {
                    return AttachmentLoad.Failed(
                        "В документе «${name.orEmpty()}» нет текста — вероятно, это скан или картинки",
                    )
                }
                LocalAttachment(
                    kind = kind,
                    mimeType = mime,
                    fileName = name,
                    textContent = text,
                )
            }
        }
        return AttachmentLoad.Ok(attachment)
    }

    /** Прочитать не больше [limit] + 1 байта: лишний байт — это и есть признак превышения. */
    private fun readBounded(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() <= limit) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Объявленный провайдером размер в байтах; `null` — провайдер его не сообщил. */
    private fun declaredSize(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    /** Имя документа для чипа и пометки в ленте. */
    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment

    /**
     * MIME документа. Провайдер вправе не сообщить тип — тогда узнаём по расширению и
     * только среди текстовых: угадывать «это точно картинка» по имени файла нельзя,
     * провайдер отвергнет запрос целиком.
     */
    private fun resolveMime(uri: Uri): String {
        val fromProvider = runCatching { contentResolver.getType(uri) }.getOrNull()
            .orEmpty()
            .lowercase()
        if (fromProvider.isNotBlank()) return fromProvider
        val name = displayName(uri).orEmpty().lowercase()
        return if (TEXT_EXTENSIONS.any { name.endsWith(it) }) "text/plain" else ""
    }

    /** Вид вложения по MIME; `null` — такой тип провайдер не принимает. */
    private fun kindOf(mime: String, name: String? = null): AttachmentKind? = when {
        mime.startsWith("image/") -> AttachmentKind.IMAGE
        // Документы читаются локально и уходят провайдеру текстом: PDF/DOCX/XLSX/PPTX
        // он как бинарник не принимает, а текст из них извлекается на телефоне.
        DocumentTextExtractor.isSupported(name.orEmpty(), mime) -> AttachmentKind.DOCUMENT
        mime.startsWith("text/") -> AttachmentKind.TEXT_FILE
        else -> null
    }

    /** Фраза о превышении лимита: конкретная, с размером и пределом. */
    private fun tooLarge(kind: AttachmentKind): String = when (kind) {
        AttachmentKind.IMAGE ->
            "Картинка больше 8 МБ — провайдер не примет такой запрос. Сожмите фото."
        AttachmentKind.DOCUMENT ->
            "Документ больше 20 МБ — читать такой на телефоне слишком дорого. Пришлите фрагмент."
        AttachmentKind.TEXT_FILE ->
            "Текстовый файл больше 1 МБ — приложите фрагмент поменьше."
    }

    /** Перерисовать чипы вложений в композере. */
    private fun renderAttachments() {
        composer.setAttachments(attachments, ::removeAttachment)
    }

    /** Убрать вложение по индексу чипа. */
    private fun removeAttachment(index: Int) {
        if (index !in attachments.indices) return
        attachments = attachments.filterIndexed { i, _ -> i != index }
        renderAttachments()
    }

    /** Снять все вложения (после отправки). */
    private fun clearAttachments() {
        if (attachments.isEmpty()) return
        attachments = emptyList()
        renderAttachments()
    }

    /**
     * Текст сообщения с пометками о вложениях.
     *
     * Лента рисует только `text`, отдельного рендера картинок у локального чата пока нет,
     * поэтому имя файла едет в тексте — иначе в ленте осталось бы пустое сообщение с
     * невидимым вложением. Пометка попадает и в запрос к провайдеру: модель видит, как
     * называется файл, чей текст лежит рядом.
     */
    private fun bodyWithAttachmentMarks(text: String, items: List<LocalAttachment>): String {
        if (items.isEmpty()) return text
        val marks = items.joinToString("\n") { item ->
            val name = item.fileName?.takeIf { it.isNotBlank() } ?: "без имени"
            when (item.kind) {
                AttachmentKind.IMAGE -> "[картинка: $name]"
                AttachmentKind.TEXT_FILE -> "[файл: $name]"
                AttachmentKind.DOCUMENT -> "[документ: $name]"
            }
        }
        return if (text.isBlank()) marks else "$text\n\n$marks"
    }

    /** Итог чтения документа: либо готовое вложение, либо фраза для человека. */
    private sealed class AttachmentLoad {
        class Ok(val attachment: LocalAttachment) : AttachmentLoad()
        class Failed(val message: String) : AttachmentLoad()
    }

    /**
     * «Сохранить в галерею» под сгенерированной картинкой.
     *
     * Запись идёт мимо состояния экрана: кнопка нажата в ленте, а не в форме, и
     * результат — тост, как у остальных побочных действий (копирование, файл). Ошибка
     * тоже тостом: молчать нельзя, человек думает, что картинка уже в галерее.
     */
    private fun saveGeneratedImage(file: File) {
        if (!GallerySaver.canSave()) {
            Toast.makeText(this, GallerySaver.LEGACY_REFUSAL, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            runCatching { GallerySaver.save(this@LocalChatActivity, file) }
                // IS_PENDING = 0 в GallerySaver уже делает запись видимой галерее: отдельный
                // media-scan не нужен, а устаревший broadcast MEDIA_SCANNER_SCAN_FILE на
                // Android 7+ бросает FileUriExposedException на file://.
                .onSuccess {
                    Toast.makeText(this@LocalChatActivity, "Сохранено в галерею", Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                    Toast.makeText(
                        this@LocalChatActivity,
                        "Не сохранилось: ${error.message ?: "ошибка"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    /**
     * Сохранение хода с картинками генерации.
     *
     * `generate_image` кладёт JPEG в `cacheDir` — систему вправе его вычистить, а диалог
     * живёт долго. Поэтому перед записью каждая карточка `generate_image` из текущего
     * состояния получает байты, склеенные с TOOL-сообщением своего вызова: путь берётся
     * из [LocalToolCard.imagePath], а текст результата остаётся прежним.
     *
     * Почему копия лежит в `text`, а не в `attachments`: вложения в истории принимаются
     * только у сообщения пользователя (`LocalMessage.attachmentRefusal`), а `attachments`
     * ещё и уходят провайдеру. Копия байтов проходит через хранилище как обычная строка
     * и в запрос модели не попадает — картинку в истории рисует лента, а не провайдер.
     */
    private fun persistWithImages(messages: List<LocalMessage>) {
        val images = presenter.state.value.items
            .mapNotNull { item -> item.tool?.takeIf { it.imagePath != null }?.let { it.callId to it.imagePath!! } }
            .toMap()
        if (images.isEmpty()) {
            persist(messages)
            return
        }
        val enriched = messages.map { message ->
            if (message.role != LocalRole.TOOL) return@map message
            val handle = message.toolCallId?.let { images[it] } ?: return@map message
            // Путь на диске есть только у свежей карточки: у восстановленной это id
            // сообщения, и байты для копии уже лежат в самом сообщении.
            val text = File(handle).takeIf { it.isFile }
                ?.let { file -> persistableImageText(message.text, file) }
                ?: message.text
            LocalMessage(
                id = message.id,
                role = message.role,
                text = text,
                toolCallId = message.toolCallId,
                createdAt = message.createdAt,
                attachments = message.attachments,
            )
        }
        persist(enriched)
    }

    /**
     * Текст TOOL-сообщения с копией картинки.
     *
     * Файл ещё на месте — копируем из него; файл уже вычищен, а в результате осталась
     * копия с прошлого сохранения — оставляем её как есть. Обе копии не плодим: маркер
     * проверяется один раз.
     */
    private fun persistableImageText(text: String, file: File): String {
        if (GeneratedImageStorage.imageBytes(text) != null) return text
        return GeneratedImageStorage.withImage(text, file)
    }

    /**
     * Копия картинки из истории по id TOOL-сообщения.
     *
     * Презентер отдаёт сюда id сообщения вместо пути файла, когда файла в cacheDir уже
     * нет (`buildItems`). Байты читаются из текущей истории презентера — лента копию
     * сообщений у себя не держит.
     */
    private fun storedImage(id: String): ByteArray? = runCatching {
        presenter.historySnapshot()
            .firstOrNull { it.id == id }
            ?.let { GeneratedImageStorage.imageBytes(it.text) }
    }.getOrNull()

    /** Сохранение хода. Зовётся презентером с IO-потоком. */
    private fun persist(messages: List<LocalMessage>) {
        val current = session ?: return
        val title = if (current.title == LocalSessionStore.DEFAULT_TITLE) {
            messages.firstOrNull { it.role == LocalRole.USER }
                ?.let { localSessionTitle(it.text) }
                ?: current.title
        } else {
            current.title
        }
        val saved = sessionStore.save(current.copy(title = title), messages)
        session = saved
        runOnUiThread {
            if (!isFinishing && !isDestroyed) subtitle.text = saved.title
        }
    }

    private fun refresh() {
        if (!presenter.state.value.streaming) {
            session?.let { loaded ->
                presenter.restore(sessionStore.messages(loaded.id))
                // Снимок параметров из меты: перезапуск возвращает те же настройки.
                presenter.restoreTurnSettings(
                    LocalTurnSettings.fromJson(sessionStore.meta(loaded.id)?.turnSettings),
                )
            }
        }
        updateNotice()
        transcript.scrollToBottom()
    }

    // ---------------------------------------------------------------------------------
    // Колбэки презентера: профиль, расход, каталог
    // ---------------------------------------------------------------------------------

    /**
     * Запись изменённого профиля (модель, `reasoning_effort`) из презентера.
     *
     * Возвращается профиль С окном контекста: [LocalProviderPrefsStore] пока не хранит
     * `contextWindow`, и прочитать его обратно из prefs значило бы потерять окно до
     * следующего запроса каталога. Поэтому окно подставляется из [catalog].
     */
    private fun persistProfile(updated: LocalProviderProfile): LocalProviderProfile? {
        val withWindow = updated.copy(
            contextWindow = catalog[updated.model]?.contextWindow ?: updated.contextWindow,
        )
        providerStore.saveProfile(withWindow)
        // Смена модели/адреса посреди хода обязана замолчать старый поток: иначе ответ
        // прежней модели ляжет в диалог, который человек уже ведёт с новой.
        LocalChatTransport.noteConfigurationChanged()
        // Возможности прежнего адреса больше не факт: окно и поддержка reasoning
        // относятся к серверу, а он сменился. Fail-closed: неизвестно — значит неизвестно,
        // а не «как у прошлой модели».
        capabilityCache.invalidate()
        return withWindow
    }

    /** Сохранение накопленного расхода в мету сессии. Зовётся презентером с IO-потока. */
    private fun persistUsage(usage: LocalUsage) {
        val current = session ?: return
        if (!usage.isMeasured) return
        runCatching {
            sessionStore.updateUsage(
                sessionId = current.id,
                promptTokens = usage.promptTokens.toLong(),
                totalTokens = usage.totalTokens.toLong(),
            )
        }
    }

    /** Метаданные модели из кэша каталога: окно контекста и поддержка reasoning. */
    private fun catalogInfo(id: String): LocalModelInfo? {
        // Сначала — кэш по адресу: он переживает перезагрузку каталога и сбрасывается
        // при смене провайдера. Затем — каталог этого экрана, собранный prefetchCatalog.
        val profile = providerStore.state().active
        if (profile != null) {
            capabilityCache.get(LocalCapabilityCache.keyFor(profile), id)?.let { return it }
        }
        return catalog[id]
    }

    /**
     * Каталог моделей в фоне: без него пилюля контекста не знает окно, а переключатель
     * reasoning — поддержку моделью. Один `GET /models` на экран; ошибка молча
     * игнорируется — чат работает и без каталога, просто без пилюли и чипа reasoning.
     */
    private fun prefetchCatalog() {
        val profile = providerStore.state().active ?: return
        val apiKey = providerStore.apiKey(profile.id) ?: return
        val requestSeq = catalogGate.begin()
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) {
                client.catalog(profile, apiKey).getOrNull()
            } ?: return@launch
            // Ответ по устаревшему запросу отбрасывается: за время ожидания человек мог
            // сменить адрес, и возможности прежнего сервера больше не факт.
            if (!catalogGate.shouldApply(requestSeq)) return@launch
            models.forEach { info -> catalog[info.id] = info }
            // Каталог разобран — возможности этих моделей дальше берутся по адресу
            // провайдера. Пустой ответ сюда не доходит (`?: return@launch` выше), поэтому
            // «неизвестно» не превращается в «модель ничего не умеет».
            capabilityCache.put(LocalCapabilityCache.keyFor(profile), models)
            if (isFinishing || isDestroyed) return@launch
            // Каталог уточнил окно и поддержку reasoning — сообщаем презентеру.
            presenter.bindProfile(providerStore.state().active)
        }
    }

    /**
     * Расход из меты сессии в форму ядра. `promptTokens` в мете — накопленная сумма по
     * шагам, поэтому она годится для подписи «сколько потрачено», но не для процента окна:
     * процент считает презентер по замеру последнего шага, и после восстановления пилюля
     * честно остаётся скрытой до первого хода.
     */
    private fun StoredSession.toLocalUsage(): LocalUsage? {
        if (totalTokens <= 0L && totalPromptTokens <= 0L) return null
        return LocalUsage(
            promptTokens = totalPromptTokens.toInt(),
            completionTokens = (totalTokens - totalPromptTokens).coerceAtLeast(0L).toInt(),
            totalTokens = totalTokens.toInt(),
        )
    }

    private fun updateNotice() {
        val profile = providerStore.state().active
        presenter.setNotice(
            when {
                profile == null -> NOT_CONFIGURED
                providerStore.apiKey(profile.id).isNullOrBlank() -> NO_KEY
                else -> null
            }
        )
    }

    private fun openSettings() {
        startActivity(LocalProviderSettingsActivity.newIntent(this))
    }

    /**
     * Настройки самого приложения.
     *
     * Нужны, когда микрофон отклонён навсегда: системный диалог разрешения больше не
     * показывается, и единственный путь — выдать RECORD_AUDIO руками. Без этого перехода
     * кнопка голоса упиралась бы в тупик.
     */
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        runCatching { startActivity(intent) }
    }

    // ---------------------------------------------------------------------------------
    // Модель и reasoning
    // ---------------------------------------------------------------------------------

    /** Каталог провайдера → выбор модели. Профиль меняется презентером, не листом. */
    private fun chooseModel() {
        val profile = providerStore.state().active
        if (profile == null) {
            presenter.setNotice(NOT_CONFIGURED)
            return
        }
        val apiKey = providerStore.apiKey(profile.id)
        if (apiKey.isNullOrBlank()) {
            presenter.setNotice(NO_KEY)
            return
        }
        LocalChatModelPicker.show(
            activity = this,
            scope = lifecycleScope,
            client = client,
            profile = profile,
            apiKey = apiKey,
            current = presenter.state.value.model ?: profile.model,
            onPick = { info -> applyModel(profile, info) },
        )
    }

    /**
     * Применить выбор модели: окно контекста берётся из каталога (единственный честный
     * источник), reasoning сбрасывается в «Авто», если новая модель его не принимает —
     * иначе в запрос уходил бы уровень, которого модель не понимает.
     *
     * Запись идёт через [LocalModelPick]: профиль-цель читается из хранилища в момент
     * выбора, а не берётся снимком при открытии списка. Снимок опасен тем, что за время
     * показа списка активный профиль могли пересоздать («Провайдер» → «Сохранить» даёт
     * новый `id` и делает его активным) — тогда выбор ложился в профиль, которого
     * `state().active` уже не видит, и следующий запрос уходил со старой моделью.
     */
    private fun applyModel(profile: LocalProviderProfile, info: LocalModelInfo) {
        catalog[info.id] = info
        val updated = LocalModelPick.apply(providerStore, profile, info)
        val active = providerStore.state().active
        MarsModelLog.log(
            "pick id=${updated.id} model=${info.id} active=${active?.model} activeId=${active?.id}",
        )
        // Презентер переключается тем же путём, что и раньше (сброс замера контекста),
        // а последним получает профиль ИЗ ХРАНИЛИЩА: чип и подпись обязаны показывать
        // то, что реально уйдёт в запрос, а не то, что мы думали записать.
        presenter.setModel(info.id)
        presenter.setReasoning(updated.reasoningEffort)
        presenter.bindProfile(active ?: updated)
        Toast.makeText(this, "Модель: ${info.id}", Toast.LENGTH_SHORT).show()
    }

    private fun chooseReasoning() {
        // Профиль читается в момент выбора, а не в момент открытия листа: см. [applyModel].
        val profile = providerStore.state().active ?: return
        LocalChatModelPicker.showReasoning(
            activity = this,
            current = presenter.state.value.reasoningEffort,
        ) { effort ->
            val target = providerStore.state().active ?: profile
            providerStore.saveProfile(target.copy(reasoningEffort = effort))
            presenter.setReasoning(effort)
        }
    }

    // ---------------------------------------------------------------------------------
    // Действия над сообщением
    // ---------------------------------------------------------------------------------

    private fun onMessageLongPress(item: LocalChatItem, isLastAssistant: Boolean) {
        LocalChatActionsSheet.show(
            activity = this,
            text = item.text,
            // «Повторить» — только последний ответ ассистента и только когда ход не идёт.
            canRetry = isLastAssistant && !presenter.state.value.streaming,
            canSpeak = speechAvailable(),
            onCopy = { copyToClipboard(item.text) },
            onRetry = ::retryLastTurn,
            onSelectText = { LocalChatActionsSheet.showSelectableText(this, item.text) },
            onSpeak = { speak(item.text) },
            // Правка своей реплики: инлайн-диалог + перезапрос с этого места.
            // Правило «можно ли» живёт в политике, а не здесь — иначе оно разъедется
            // с листом и с тестами (см. LocalChatEditPolicy).
            canEdit = LocalChatEditPolicy.canEdit(item, presenter.state.value.streaming),
            onEdit = { startEditMessage(item) },
            // Лист отдаёт действие без аргумента: текст сообщения захватываем здесь,
            // иначе `::share` не подходит по типу (функция с параметром).
            onShare = { share(item.text) },
            // Пересборка в файл: провайдер умеет только текст, поэтому DOCX/XLSX/TXT
            // собираются здесь, на телефоне, и отдаются в системное «Поделиться».
            onSaveFile = { chooseFileFormat(item.text) },
            // «Открыть» — панель для ответа, который не помещается в ленту целиком.
            // Решает политика: она же задаёт формат сохранения по содержимому
            // (таблица → XLSX, листинг → TXT, длинный текст → DOCX).
            canOpenAsArtifact = canOpenAsArtifact(item),
            onOpenAsArtifact = { openArtifact(item.text) },
            // «Продолжить» — только честная обрезка по лимиту токенов у последнего ответа.
            // Правило живёт в LocalContinuePolicy, здесь только вопрос «предлагать ли».
            canContinue = LocalChatActions.canContinue(item, isLastAssistant) &&
                !presenter.state.value.streaming,
            onContinue = { continueTruncated(item) },
            // Альтернативы для «повторить другой моделью»: кэш каталога, который уже
            // прогрелся на экране, плюс текущая — иначе на холодном старте список пуст.
            models = catalog.keys.sorted(),
            currentModel = presenter.state.value.model ?: providerStore.state().active?.model,
            onRetryWith = { model ->
                val profile = providerStore.state().active
                if (profile == null) {
                    Toast.makeText(this, NOT_CONFIGURED, Toast.LENGTH_SHORT).show()
                } else {
                    val info = catalog[model] ?: LocalModelInfo(id = model)
                    applyModel(profile, info)
                    retryLastTurn()
                }
            },
        )
    }

    /**
     * Выбрать формат и отдать файл в «Поделиться».
     *
     * Модель возвращает текст — в файл его превращает [DocumentBuilder] на самом телефоне:
     * сервер Марса в этом не участвует. Для таблицы текст разбирается на строки и ячейки.
     */
    /**
     * Открывать ли панель артефакта для этой строки.
     *
     * Только ответ ассистента и только цельный текст: карточка инструмента и своя реплика
     * в панели не нужны — там нечего сохранять в файл, а открывать их отдельно значило бы
     * предложить пункт, который ничего не добавляет к «Копировать».
     */
    private fun canOpenAsArtifact(item: LocalChatItem): Boolean {
        if (item.role != LocalRole.ASSISTANT || item.tool != null) return false
        if (item.streaming) return false
        return ArtifactPolicy.decide(item.text).isArtifact
    }

    /**
     * Панель артефакта: текст целиком, подпись «что это», сохранение в предложенном формате.
     *
     * Формат предлагает политика по содержимому, но кнопка одна — «Сохранить XLSX».
     * Три кнопки форматов заставляли бы выбирать между DOCX и XLSX там, где выбор уже
     * сделан по содержимому; кому нужен другой формат, у того есть лист действий.
     */
    private fun openArtifact(text: String) {
        val decision = ArtifactPolicy.decide(text)
        if (!decision.isArtifact) return
        ArtifactSheet.show(
            activity = this,
            decision = decision,
            text = text,
            onSave = { format -> writeResponseFile(text, format.name) },
            onCopy = { copyToClipboard(text) },
            onShare = { share(text) },
        )
    }

    private fun chooseFileFormat(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val options = listOf(
            "DOCX — документ Word",
            "XLSX — таблица Excel",
            "TXT — простой текст",
        )
        // Диалог нужно закрыть после выбора: MarsDialog.pill сам этого не делает,
        // а ссылка появляется только после возврата из show() — держим её в держателе.
        var holder: android.app.Dialog? = null
        val dialog = MarsDialog.show(this) { root ->
            root.addView(MarsDialog.header(this, "Сохранить как файл", null))
            root.addView(
                MarsDialog.paragraph(
                    this,
                    "Файл собирается на телефоне. Оформление, формулы и картинки " +
                        "исходного документа не сохраняются — только текст и структура.",
                ),
            )
            val buttons = options.map { label ->
                MarsDialog.pill(
                    context = this,
                    label = label,
                    bg = MarsTheme.SURFACE_SUBTLE,
                    fg = MarsTheme.TEXT_EM,
                    onClick = {
                        holder?.dismiss()
                        writeResponseFile(body, label.substringBefore(' '))
                    },
                )
            }
            listOf(MarsDialog.buttonRow(this, *buttons.toTypedArray()))
        }
        holder = dialog
    }

    /**
     * Собрать файл выбранного формата и открыть системный диалог сохранения.
     *
     * Сборка идёт на IO-потоке: XLSX на сотни строк — это заметная работа, а главный
     * поток занят лентой. Имя файла с датой, чтобы не затирать прошлые сохранения.
     */
    private fun writeResponseFile(text: String, format: String) {
        val base = "mars-ответ-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        val (suffix, bytes) = when (format) {
            // Строки берём ТОЛЬКО из таблицы: rowsFromText тянет в Excel весь ответ —
            // прозу строками из одной ячейки и заборы ``` как обычный текст. Если таблицы
            // в ответе нет, весь текст кладём одной ячейкой: пустой книги быть не должно.
            "XLSX" -> "xlsx" to DocumentBuilder.buildXlsx(
                ArtifactPolicy.tableRows(text).ifEmpty { listOf(listOf(text.trim())) },
            )
            "DOCX" -> "docx" to DocumentBuilder.buildDocx(text)
            else -> "txt" to DocumentBuilder.buildTxt(text)
        }
        // Кэш-каталог приложения: системный диалог сам скопирует файл туда, куда выбрал человек.
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "$base.$suffix")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { file.writeBytes(bytes) }.isSuccess
            }
            if (!ok) {
                Toast.makeText(this@LocalChatActivity, "Не удалось собрать файл", Toast.LENGTH_SHORT).show()
                return@launch
            }
            shareFile(file, if (suffix == "xlsx") {
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            } else if (suffix == "docx") {
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            } else {
                "text/plain"
            })
        }
    }

    /** Отдать готовый файл в системное «Поделиться»: оттуда — в любой мессенджер или на диск. */
    private fun shareFile(file: File, mime: String) {
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.sessionexports", file)
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, "Не удалось открыть файл для отправки", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Куда сохранить")) }
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Toast.makeText(this, "Буфер обмена недоступен", Toast.LENGTH_SHORT).show()
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        Toast.makeText(this, MessageActionPolicy.COPY_TOAST, Toast.LENGTH_SHORT).show()
    }

    /**
     * Быстрая кнопка «Ответить» на карточке сообщения: вставляет markdown-цитату (`> …`)
     * в начало композера и переводит фокус на поле ввода.
     */
    private fun replyToItem(item: LocalChatItem) {
        if (item.text.isBlank()) return
        val current = composer.input.text.toString()
        val quoted = MessageActionPolicy.composeQuote(current, item.text)
        composer.input.setText(quoted.text)
        composer.input.setSelection(quoted.cursor.coerceIn(0, quoted.text.length))
        composer.input.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(composer.input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        Toast.makeText(this, "Цитата добавлена в поле ввода", Toast.LENGTH_SHORT).show()
    }

    /**
     * Повтор последнего запроса: текст последнего сообщения пользователя снова уходит
     * провайдеру как новый ход. Перегенерации «поверх» ответа у локального презентера
     * нет — он только дописывает историю, поэтому повтор виден как новая пара сообщений.
     */
    private fun retryLastTurn() {
        if (presenter.state.value.streaming) return
        val lastUser = presenter.state.value.items
            .lastOrNull { it.role == LocalRole.USER && it.tool == null }
            ?.text
        if (lastUser.isNullOrBlank()) {
            Toast.makeText(this, "Нет сообщения для повтора", Toast.LENGTH_SHORT).show()
            return
        }
        composer.input.setText(lastUser)
        composer.input.setSelection(lastUser.length)
        send()
    }

    /**
     * Правка своей реплики: диалог → обрезка истории → перезапрос.
     *
     * Три шага именно в этом порядке, и каждый важен:
     * 1. [LocalChatEditPolicy.plan] решает, что будет удалено, и даёт человеку честную
     *    подсказку («будет удалено N сообщений») ДО нажатия. Молча срезать половину
     *    диалога нельзя — это выглядело бы как потеря истории.
     * 2. [LocalChatPresenter.truncateFrom] убирает реплику и всё после неё из истории и с
     *    экрана. Возврат `false` означает, что реплики в истории уже нет — тогда отправка
     *    не начинается, иначе вопрос ушёл бы дважды.
     * 3. Только теперь текст уходит провайдеру тем же путём, что и обычная отправка:
     *    отдельного транспорта для правки нет, иначе правленый ход терял бы вложения,
     *    инструменты и метрики.
     *
     * Своё сообщение НЕ остаётся в ленте до ответа: [send] добавит его заново с новым
     * текстом. Иначе на экране на миг было бы две версии одного вопроса.
     */
    private fun startEditMessage(item: LocalChatItem) {
        if (presenter.state.value.streaming) return
        val plan = LocalChatEditPolicy.plan(
            items = presenter.state.value.items,
            messageId = item.id,
            newText = item.text,
        ) ?: return
        LocalChatEditSheet.show(
            activity = this,
            initialText = item.text,
            hint = LocalChatEditPolicy.hint(plan),
        ) { newText ->
            val fresh = LocalChatEditPolicy.plan(
                items = presenter.state.value.items,
                messageId = item.id,
                newText = newText,
            )
            if (fresh == null || fresh.isNoop) {
                Toast.makeText(this, LocalChatEditPolicy.EMPTY_HINT, Toast.LENGTH_SHORT).show()
                return@show
            }
            // Сколько строк исчезнет, человеку уже сказала подсказка в диалоге.
            // Второй тост после нажатия — шум поверх того же самого.
            if (!presenter.truncateFrom(item.id)) return@show
            // Тот же путь отправки, но служебным параметром: он читает текст из аргумента,
            // а не из поля ввода. Отправка через композер потребовала бы печатать текст
            // в поле и очищать его — лишний видимый мигающий шаг.
            send(internal = newText)
        }
    }

    /**
     * «Продолжить» обрезанный ответ.
     *
     * Почему служебной репликой, а не повторной отправкой последнего вопроса: при
     * `finish_reason = length` модель уже сказала всё, что успела, и повтор вопроса дал
     * бы другой ответ с начала, а не продолжение. В историю уходит [LocalContinuePolicy.CONTINUE_PROMPT],
     * экран при этом не показывает лишнюю строку — человек видит только, как дописывается
     * прежний ответ.
     */
    private fun continueTruncated(item: LocalChatItem) {
        if (presenter.state.value.streaming) return
        if (!LocalContinuePolicy.shouldOffer(item.truncated, item.interrupted, item.text)) return
        send(internal = LocalContinuePolicy.CONTINUE_PROMPT)
    }

    private fun share(text: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, MessageActionPolicy.SHARE_SUBJECT)
            putExtra(Intent.EXTRA_TEXT, MessageActionPolicy.shareBody(text))
        }
        runCatching { startActivity(Intent.createChooser(intent, "Поделиться сообщением")) }
            .onFailure {
                Toast.makeText(this, "Некому открыть сообщение для шаринга", Toast.LENGTH_SHORT).show()
            }
    }

    /** Ссылка из markdown: наружу, в браузер. Своего лайтбокса у локального чата нет. */
    private fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure {
                Toast.makeText(this, "Некому открыть ссылку", Toast.LENGTH_SHORT).show()
            }
    }

    // ---------------------------------------------------------------------------------
    // Озвучка
    // ---------------------------------------------------------------------------------

    /**
     * Доступна ли озвучка. Пункт «Озвучить» показывается, если есть хоть один путь:
     * офлайн-синтез (модель скачана и движок поднят) либо системный TTS. Если движок
     * системного TTS отказал при инициализации, он больше не считается — мёртвая кнопка
     * хуже отсутствующей.
     */
    private fun speechAvailable(): Boolean {
        if (hasOfflineVoice()) return true
        if (::voiceOutput.isInitialized && !voiceOutput.engineAvailable) return false
        return hasTtsService()
    }

    /** Офлайн-синтез поднят и может говорить прямо сейчас. Ничего не скачивает. */
    private fun hasOfflineVoice(): Boolean =
        runCatching { ttsWarmup.isReady() }.getOrDefault(false)

    private fun hasTtsService(): Boolean = runCatching {
        packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .isNotEmpty()
    }.getOrDefault(false)

    /**
     * Ленивая сборка СИСТЕМНОЙ озвучки: [VoiceOutputController] (политика, чистый JVM)
     * поверх [AndroidSpeechEngine] (платформа).
     *
     * Зовётся только из запасного пути [speakWithSystemVoice]: ручная озвучка идёт через
     * офлайн-синтез, а этот контроллер поднимается, лишь когда офлайн-голоса нет.
     * Экран ничего не синтезирует сам — фильтрация markdown и запрет озвучки в фоне
     * уже внутри контроллера.
     */
    private fun ensureVoiceOutput() {
        if (::voiceOutput.isInitialized) return
        speechEngine = AndroidSpeechEngine(
            context = this,
            onInitFinished = { available ->
                // Колбэк может прийти синхронно из конструктора — до присваивания поля.
                if (::voiceOutput.isInitialized) {
                    voiceOutput.onEngineReady(available)
                } else {
                    speechInitResult = available
                }
            },
        )
        voiceOutput = VoiceOutputController(speechEngine)
        speechInitResult?.let { available ->
            speechInitResult = null
            voiceOutput.onEngineReady(available)
        }
    }

    /**
     * «Озвучить» сообщение.
     *
     * Голос выбирается по приоритету, а не «что первым под рукой»:
     * 1. офлайн-синтез Piper (`ru_RU-irina-medium-int8`) — он звучит по-человечески;
     * 2. если модель ещё готовится (качается или поднимается движок) — прогрев в фоне и
     *    честное «Готовлю голос, попробуйте ещё раз» вместо молчания и вместо робота;
     * 3. системный TTS Google — ТОЛЬКО запасной путь, когда офлайн-модели нет и её
     *    подготовка уже отказала ([ru.mars.mobile.tts.OfflineTtsWarmup.lastFailure]).
     *
     * Почему не «сразу системный»: именно он на приёмке 29.10 прозвучал «до ужаса
     * роботизированно». Системный голос допустим лишь тогда, когда другого нет.
     */
    private fun speak(text: String) {
        val body = text.trim()
        if (body.isEmpty()) {
            Toast.makeText(this, "Нечего озвучивать", Toast.LENGTH_SHORT).show()
            return
        }

        // Если голосовой контроллер инициализирован, озвучиваем через него: там уже
        // подключены и серверные голоса (Светлана, Дмитрий), и офлайн-Piper (Ирина),
        // и выбранный пользователем темп/профиль в VoiceProsody!
        if (::voice.isInitialized) {
            voice.setProsody(voiceProsody)
            voice.speakNow(body)
            Toast.makeText(this, "Озвучиваю", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Офлайн-движок уже поднят — говорим им, системный TTS не трогаем вовсе.
        if (hasOfflineVoice()) {
            // `OfflineTtsPort.speak` НЕ блокирующий: он ставит реплику в свой поток
            // синтеза и сразу возвращает «принято/отказ» (см. KDoc порта), поэтому
            // главный поток здесь не висит и прятать вызов в Dispatchers.Default не нужно.
            val accepted = runCatching { ttsWarmup.port.speak(body) }.getOrDefault(false)
            if (accepted) {
                // Системный голос мог звучать с прошлого нажатия — иначе два голоса разом.
                if (::voiceOutput.isInitialized) runCatching { voiceOutput.stopSpeaking() }
                Toast.makeText(this, "Озвучиваю", Toast.LENGTH_SHORT).show()
                return
            }
            // Порт отказал (реплика пустая после trim, движок освобождён) — это не «не
            // готов»: честно уходим на запасной путь ниже.
        } else if (ttsWarmup.lastFailure == null) {
            // 2. Готовности нет, но и отказа ещё не было: модель качается/движок поднимается.
            Toast.makeText(this, "Готовлю голос, попробуйте ещё раз", Toast.LENGTH_SHORT).show()
            prepareOfflineVoiceInBackground()
            return
        }

        // 3. Запасной путь: офлайн-голоса нет и он не встал.
        speakWithSystemVoice(body)
    }

    /**
     * Запасной путь озвучки — системный TTS Google.
     *
     * Зовётся только из [speak], когда офлайн-синтез недоступен: модель не скачалась или
     * движок не поднялся. Голос будет роботизированным — это осознанная плата за «хоть
     * что-то вместо тишины», а не основной путь.
     */
    private fun speakWithSystemVoice(body: String) {
        // Офлайн мог уже начать фразу — иначе два голоса наложатся друг на друга.
        runCatching { ttsWarmup.port.stop() }
        if (!hasTtsService()) {
            Toast.makeText(this, "Озвучка недоступна на этом устройстве", Toast.LENGTH_SHORT).show()
            return
        }
        ensureVoiceOutput()
        val message = when (voiceOutput.speakNow(body)) {
            VoiceOutputController.SpeakOutcome.SPOKEN -> "Озвучиваю"
            VoiceOutputController.SpeakOutcome.NOTHING_TO_SPEAK -> "Нечего озвучивать"
            VoiceOutputController.SpeakOutcome.ENGINE_UNAVAILABLE ->
                "Озвучка недоступна на этом устройстве"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Подтверждение деструктивного инструмента. `false` — отказ, BACK, таймаут 120 с
     * или уход с экрана; инструмент в этих случаях не исполняется.
     */
    private suspend fun confirmTool(tool: LocalDeviceTool, call: LocalToolCall): Boolean =
        LocalChatConfirmDialog.ask(
            activity = this,
            title = "Разрешить ${tool.schema.name}?",
            body = tool.schema.description.ifBlank {
                "Инструмент изменит данные на телефоне."
            },
            confirmLabel = "Разрешить",
            denyLabel = "Отклонить",
            code = call.argumentsJson.ifBlank { "{}" },
            codeLabel = "Аргументы вызова",
        )

    private fun dp(value: Int): Int = MarsTheme.dp(this, value)
}
