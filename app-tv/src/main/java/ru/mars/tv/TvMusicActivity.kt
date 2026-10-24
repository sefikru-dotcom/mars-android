package ru.mars.tv

import android.app.Dialog
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * TV music screen (P1 vertical slice): the dashboard's «Музыка» button lands here,
 * [TvMusicScreenController] loads the real music through [RepositoryTvMusicGateway]
 * (core-network MusicRepository over `https://marscore.ru/music`) and renders one of the
 * two branches. «Для тебя» (the startup branch) shows the personal feed `GET /api/home` as
 * shelves — a heading plus track rows for each shelf — with loading / error (D-pad
 * «Повторить») states of its own; a genuinely empty feed falls back to the charts, so the
 * screen never dead-ends blank. «Чарты» shows the catalog in the four states it always had
 * — loading / error (D-pad «Повторить») / empty / content. The two D-pad tabs sit next to
 * the search row and always restyle to show the open one ([TvMusicHomePolicy] decides what
 * is renderable, the fallback and what «Повторить» repeats).
 * The search row queries the SAME repository seam (`/api/search`): the field's IME
 * «search» action and the explicit «Найти» button submit (a result lives in the «Чарты»
 * branch), «Чарты» returns to the charts without stopping playback, and «Повторить»
 * retries exactly the last operation of the active branch — so the remote user never
 * depends on the soft keyboard being reachable or dismissable.
 * Selecting a track row binds the shown list as the process-global [TvMusicPlayer]
 * transport's playlist and starts it on that row; the transport keeps playing after this
 * screen closes, auto-advances to the next track when one completes ([TvPlaylistPolicy])
 * and goes idle at the end of the list. The header transport pair keeps non-destructive
 * and destructive control apart: «Пауза/Играть» ([TvMusicPlayer.togglePlayPause],
 * captions/icons delegated to [TvMiniPlayerSpec] — one voice with the chat mini player)
 * quiets the audio while KEEPING the playlist binding, «Остановить» ends playback
 * explicitly; both are shown only while a track is bound
 * ([TvMusicNowPlayingSpec.transportControls]). The header
 * «Повтор» toggle cycles the repeat preference OFF -> ALL -> ONE
 * ([TvMusicPlayer.cycleRepeatMode], label per [TvMusicNowPlayingSpec.repeatButtonLabel]) —
 * ALL loops the queue, ONE replays the current track.
 * The screen observes the transport ([TvMusicPlayer.onTransportChanged]) so the header
 * line and the current track's row highlight follow asynchronous state transitions
 * (prepared / completion / auto-advance / stream error) without user interaction; row
 * restyling never rebuilds the list, so D-pad focus survives. Presentation rules are pure
 * ([TvMusicNowPlayingSpec], [TvMusicSearchSpec]). Every interactive element is focusable
 * for the D-pad, and disappearing affordances never leave the window unfocused
 * ([TvMusicFocusPolicy]); theme colors only, no emojis.
 *
 * Обложки: серверные thumbnail треков (относительные `/music/api/thumb?v=ID`) грузит
 * [TvImageLoader] — без внешних библиотек, с byte-based LruCache и даунсэмплингом
 * (числовые инварианты — в чистой [TvImageLoaderPolicy]); URL резолвится от origin сайта,
 * а не от baseUrl музыкального API ([TvThumbnailUrlResolver]) — иначе двойной `/music/music/…`.
 * Обложка видна в строке каждого трека и рядом с «Сейчас играет» в шапке.
 *
 * Тексты песен: кнопка «Текст песни» на текущем треке открывает диалог
 * ([R.layout.dialog_tv_lyrics]); запрос идёт через [TvLyricsPanelFlow] (чистый конечный
 * автомат: Loading -> Text/Missing/Error, защита от двойного тапа, generation-guard)
 * и [RepositoryTvMusicGateway.fetchLyrics] (title/artist обязательны — живой сервер без них
 * отдаёт заглушку «Текст не найден.», которую [TvLyricsViewPolicy] распознаёт как Missing).
 * Переключатель «Перевод»/«Оригинал» — паритет с mobile: кнопка появляется только при
 * настоящем тексте, запрос `POST /api/lyrics/translate` — чистый автомат
 * [TvLyricsTranslationFlow] (своя очередь, Job-guard на двойное нажатие, generation-guard
 * на сброс), подписи/тело/заметку выбирает [TvLyricsTranslationPolicy] (строки из
 * ресурсов). Ошибка или пустой ответ панель не роняют — на экране остаётся оригинал,
 * понятная строка уходит в мету; сброс при смене трека и закрытии панели.
 *
 * Альбом: у трека с открываемым `albumBrowseId` ([TvAlbumPolicy.openable] — тот же префикс
 * `MPRE`, что проверяет `MusicRepository.getAlbum`) строка получает кнопку «Альбом»;
 * она открывает диалог `dialog_tv_album` со списком треков. Запрос идёт через
 * [TvAlbumFlow] (Loading -> Loaded/NotFound/Error, generation-guard), данные — тем же
 * `MusicRepository.getAlbum`, что у мобильного диалога. Выбор трека ставит очередь из
 * ВСЕГО альбома ([TvAlbumPolicy.playRequest]) — паритет с mobile. Имя альбома входит в
 * подпись строки ([TvAlbumPolicy.trackLabel]) и служит подсказкой перехода.
 *
 * Раскладка (декомпозиция W3): хост держит состояние и жизненный цикл, поведение живёт в
 * секциях `TvMusic*Section.kt` — оболочка и единая точка рендера, ветки и списки, строки
 * треков и транспорт, диалог альбома, диалог текста. Форма переноса — extension-функции
 * на Activity: тела дословные, порядок вызовов в `onCreate` и есть контракт проводки.
 * Бюджет хоста закреплён [TvMusicDecompositionTest].
 */
class TvMusicActivity : AppCompatActivity() {

    /** Один прод-гейтвей на экран: чарты/поиск контроллеру + тексты панели [lyricsFlow]. */
    private val musicGateway = RepositoryTvMusicGateway.create()

    // Поля ниже подняты `private` -> `internal` для секций (декомпозиция W3): extension-
    // функции не видят private-члены хоста. Владелец состояния по-прежнему хост, секции
    // только рисуют и зовут методы автоматов.
    internal val controller = TvMusicScreenController(
        gateway = musicGateway,
        transport = TvMusicPlayer,
        uiDispatcher = MainThreadTvDispatcher(),
    )

    /** Конечный автомат панели «Текст песни» (чистый; Android здесь — только рендер). */
    internal val lyricsFlow = TvLyricsPanelFlow(gateway = musicGateway)

    /** Переключатель «Перевод»/«Оригинал» — надстройка над [lyricsFlow] (чистый автомат). */
    internal val lyricsTranslationFlow = TvLyricsTranslationFlow(gateway = musicGateway)

    /**
     * Конечный автомат диалога альбома: тот же [musicGateway] (он же [TvAlbumGateway] —
     * `MusicRepository.getAlbum`, новый клиент не пишем). Рендер — [renderAlbumPanel].
     */
    internal val albumFlow = TvAlbumFlow(gateway = musicGateway)

    /**
     * Контроллер библиотеки — вкладки «Плейлисты» и «История» (паритет mobile
     * MusicActivity): `GET /api/playlists`, `GET /api/playlist/{id}`, `GET /api/history`
     * и запись прослушивания `POST /api/history/record`. Состояния чистой политики
     * [TvMusicLibraryPolicy], Android здесь — только рендер.
     */
    internal val libraryController = TvMusicLibraryController(
        gateway = musicGateway,
        transport = TvMusicPlayer,
        uiDispatcher = MainThreadTvDispatcher(),
    )

    internal lateinit var stateText: TextView
    internal lateinit var retryButton: Button
    internal lateinit var stopButton: Button
    internal lateinit var playPauseButton: Button
    internal lateinit var repeatButton: Button
    internal lateinit var backButton: Button
    internal lateinit var nowText: TextView
    internal lateinit var nowArt: ImageView
    internal lateinit var lyricsButton: Button
    internal lateinit var trackList: LinearLayout
    internal lateinit var searchInput: EditText
    internal lateinit var searchButton: Button
    internal lateinit var chartsButton: Button
    internal lateinit var forYouButton: Button
    internal lateinit var playlistsButton: Button
    internal lateinit var historyButton: Button

    /** Открытый диалог текста песни (один на экран; пересоздаётся на каждый показ). */
    internal var lyricsDialog: Dialog? = null

    /**
     * Видимость экранной клавиатуры: её высота съедает низ окна, [globalLayoutListener]
     * считает разницу по [View.getWindowVisibleDisplayFrame]. Нужна для BACK: сначала
     * закрывается IME ([TvMusicFocusPolicy.backAction]), и только потом экран.
     */
    private var imeVisible = false

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val root = window.decorView
        val visible = Rect()
        root.getWindowVisibleDisplayFrame(visible)
        // 80dp порога: системные бары ТВ не дают такой разницы, а панель IME — даёт.
        imeVisible = root.height - visible.bottom > dp(80)
    }

    /**
     * Rendered track rows — restyled in place on transport changes.
     * [label] красится акцентом у текущего трека, [marker] (ic_music) у него же виден.
     */
    internal class TrackRow(val trackId: String, val label: TextView, val marker: ImageView)

    internal val trackRows = mutableListOf<TrackRow>()

    /**
     * Системная настройка «уменьшить движение», прочитанная ОДИН раз на экран и переданная
     * в политику движения ([TvMotionPolicy]): подъём под фокусом и кроссфейд фона
     * схлопываются в мгновенную смену состояния, когда она включена
     * (Settings.Global.ANIMATOR_DURATION_SCALE).
     */
    internal val reducedMotion: Boolean by lazy {
        TvMotionPolicy.isReducedMotion(
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        )
    }

    /** Открытый диалог альбома (один на экран; пересоздаётся на каждый показ). */
    internal var albumDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_music)
        // Global music transport: audio focus + network wake mode need an application
        // context; playback started from this screen is a good audio citizen.
        TvMusicPlayer.attachContext(this)
        // Дисковый кэш обложек: отдельного Application-класса в app-tv нет, а обложки
        // грузит только этот экран — привязка здесь, до первого load().
        TvImageLoader.attachContext(this)
        stateText = findViewById(R.id.stateText)
        retryButton = findViewById(R.id.retryButton)
        stopButton = findViewById(R.id.stopButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        repeatButton = findViewById(R.id.repeatButton)
        backButton = findViewById(R.id.backButton)
        nowText = findViewById(R.id.nowText)
        nowArt = findViewById(R.id.nowArt)
        lyricsButton = findViewById(R.id.lyricsButton)
        trackList = findViewById(R.id.trackList)
        searchInput = findViewById(R.id.searchInput)
        searchButton = findViewById(R.id.searchButton)
        chartsButton = findViewById(R.id.chartsButton)
        forYouButton = findViewById(R.id.forYouButton)
        playlistsButton = findViewById(R.id.playlistsButton)
        historyButton = findViewById(R.id.historyButton)
        backButton.setOnClickListener { finish() }
        // Retry repeats the LAST operation of the ACTIVE branch: charts/search and the feed
        // are the screen controller's, playlists and history belong to the library
        // controller — «Повторить» under «Плейлисты» must never reload the charts.
        retryButton.setOnClickListener { retryActiveBranch() }
        stopButton.setOnClickListener { controller.stopPlayback() }
        // D-pad play/pause toggle: the non-destructive way to quiet the audio — the
        // playlist binding survives, «Играть» resumes the same queue. Same transport
        // action as the chat mini player's toggle ([TvMusicPlayer.togglePlayPause]).
        playPauseButton.setOnClickListener {
            TvMusicPlayer.togglePlayPause()
            renderTransportState()
        }
        // D-pad repeat toggle: cycles the transport preference OFF -> ALL -> ONE
        // ([TvMusicPlayer.cycleRepeatMode]); the label mirrors the new mode immediately.
        repeatButton.setOnClickListener {
            TvMusicPlayer.cycleRepeatMode()
            renderRepeatMode()
        }
        // «Текст песни»: диалог открывается сразу (тело — загрузка), [TvLyricsPanelFlow]
        // заполняет его по ответу сервера; двойной тап глушит сам flow (Job-guard parity).
        lyricsButton.setOnClickListener {
            val track = TvMusicPlayer.currentTrack ?: return@setOnClickListener
            if (lyricsDialog?.isShowing != true) showLyricsDialog(track)
            // Новый запрос текста: перевод прошлого трека не переносится (сброс при смене трека).
            lyricsTranslationFlow.reset()
            lyricsFlow.open(track)
        }
        // The IME «search» action and the explicit «Найти» button are the same submit;
        // «Чарты» empties the field and returns to the charts WITHOUT touching the
        // transport — music that is playing keeps playing across the list swap. Both tab
        // buttons reload their branch ([TvMusicScreenController.selectTab]).
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch()
                true
            } else {
                false
            }
        }
        searchButton.setOnClickListener { submitSearch() }
        chartsButton.setOnClickListener {
            searchInput.setText("")
            controller.selectTab(TvMusicTab.CHARTS)
        }
        forYouButton.setOnClickListener { controller.selectTab(TvMusicTab.FOR_YOU) }
        // Библиотечные вкладки (mobile parity): переключение ветки + первая загрузка.
        // Повторный вход на уже загруженную вкладку ничего не перезапрашивает — D-pad
        // не должен каждый раз дёргать сеть.
        playlistsButton.setOnClickListener { openLibraryTab(TvMusicTab.PLAYLISTS) }
        historyButton.setOnClickListener { openLibraryTab(TvMusicTab.HISTORY) }
        // Клавиатура НЕ всплывает на фокус сама (setShowSoftInputOnFocus(false), у XML-
        // атрибута нет публичного статуса): D-pad проходит через поле поиска вниз к
        // трекам без залипания. Открываем её только явным нажатием (DPAD_CENTER/клик).
        searchInput.setShowSoftInputOnFocus(false)
        searchInput.setOnClickListener { showIme() }
        // Android EditText ПОТРЕБЛЯЕТ стрелки D-pad сам (UP/DOWN — переход по строкам,
        // LEFT/RIGHT — курсор), поэтому nextFocusDown поля не срабатывает никогда:
        // DOWN залипал в поиске и до списка треков пультом было не добраться. Явный
        // перехват: DOWN — на цель из rewireSearchDownFocus() (первый трек или
        // «Повторить»), UP — обратно в шапку. При открытой клавиатуре не вмешиваемся:
        // стрелки принадлежат IME.
        searchInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (imeVisible) {
                        false
                    } else {
                        val target = searchInput.nextFocusDownId
                        val view = if (target != View.NO_ID) findViewById<View>(target) else null
                        view?.requestFocus() ?: false
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (imeVisible) false else backButton.requestFocus()
                }
                else -> false
            }
        }
        // BACK: сначала закрываем экранную клавиатуру (фокус остаётся на searchInput),
        // и только без IME — штатно закрываем экран. Решение — чистая
        // [TvMusicFocusPolicy.backAction], видимость IME считает [globalLayoutListener].
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Открытый плейлист — это ещё не экран, а его drill-down: BACK сначала
                // возвращает к списку плейлистов (как «Все плейлисты»), и только следующий
                // BACK закрывает экран. Правило живёт здесь, чистая политика не меняется.
                if (!imeVisible &&
                    controller.tab == TvMusicTab.PLAYLISTS &&
                    libraryController.openPlaylist != null
                ) {
                    libraryController.closePlaylist()
                    return
                }
                when (TvMusicFocusPolicy.backAction(imeVisible)) {
                    TvMusicFocusPolicy.BackAction.HIDE_IME -> {
                        hideIme()
                        searchInput.requestFocus()
                    }
                    TvMusicFocusPolicy.BackAction.CLOSE_SCREEN -> {
                        // Штатный путь: на мгновение снимаем callback и пропускаем BACK дальше.
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        TvFocusLift.attach(backButton, reducedMotion)
        TvFocusLift.attach(retryButton, reducedMotion)
        TvFocusLift.attach(stopButton, reducedMotion)
        TvFocusLift.attach(playPauseButton, reducedMotion)
        TvFocusLift.attach(repeatButton, reducedMotion)
        TvFocusLift.attach(lyricsButton, reducedMotion)
        TvFocusLift.attach(searchInput, reducedMotion)
        TvFocusLift.attach(searchButton, reducedMotion)
        TvFocusLift.attach(chartsButton, reducedMotion)
        TvFocusLift.attach(forYouButton, reducedMotion)
        TvFocusLift.attach(playlistsButton, reducedMotion)
        TvFocusLift.attach(historyButton, reducedMotion)
        // Одна точка рендера на все три наблюдателя: ветку выбирает активная вкладка,
        // поэтому переключение вкладки, лента и чартовый список рисуются одинаково.
        controller.onStateChanged = { renderCurrent() }
        controller.onTabChanged = { renderCurrent() }
        controller.onHomeStateChanged = { renderCurrent() }
        lyricsFlow.onStateChanged = { state ->
            // Фокус на скролл — только когда пришёл НОВЫЙ текст: перерисовки перевода
            // не должны вырывать D-pad у кнопки/скролла (требование панели).
            renderLyricsPanel(state, focusText = state is TvLyricsPanelState.Text)
        }
        lyricsTranslationFlow.onStateChanged = { renderLyricsPanel(lyricsFlow.state, focusText = false) }
        // Диалог альбома: каждое состояние автомата сразу становится кадром окна.
        albumFlow.onStateChanged = { renderAlbumPanel(it) }
        // Библиотека (плейлисты/история): свои хуки, но рендер тот же — ветку выбирает
        // активная вкладка, поэтому поздний ответ закрытой ветки экран не перерисует.
        libraryController.onPlaylistsChanged = { renderCurrent() }
        libraryController.onPlaylistTracksChanged = { renderCurrent() }
        libraryController.onHistoryChanged = { renderCurrent() }
        // Кадр транспорта: шапка/строки + запись прослушивания в серверную историю
        // (паритет с mobile). Дедупликацию по треку держит контроллер библиотеки, поэтому
        // пауза/возобновление не плодят записи.
        TvMusicPlayer.onTransportChanged = {
            renderTransportState()
            libraryController.onTrackStarted(TvMusicPlayer.currentTrack)
        }
        // Стартовая ветка — персональная лента «Для тебя»; пустая лента сама откатит
        // экран на чарты, поэтому старт без ленты не оставляет пустое окно.
        controller.loadHome()
        renderCurrent()
        backButton.requestFocus()
    }

    override fun onDestroy() {
        // The transport is global: teardown silences the controller but music keeps playing.
        controller.destroy()
        controller.onStateChanged = null
        controller.onTabChanged = null
        controller.onHomeStateChanged = null
        // Панель текста: глушим запрос в пути и снимаем хук рендера (окно умирает).
        lyricsFlow.destroy()
        lyricsFlow.onStateChanged = null
        lyricsTranslationFlow.destroy()
        lyricsTranslationFlow.onStateChanged = null
        // Диалог альбома: гасим запрос в пути и снимаем хук рендера.
        albumFlow.destroy()
        albumFlow.onStateChanged = null
        // Библиотека: гасим запросы в пути и снимаем хуки рендера.
        libraryController.destroy()
        libraryController.onPlaylistsChanged = null
        libraryController.onPlaylistTracksChanged = null
        libraryController.onHistoryChanged = null
        albumDialog?.dismiss()
        albumDialog = null
        lyricsDialog?.dismiss()
        lyricsDialog = null
        TvMusicPlayer.onTransportChanged = null
        window.decorView.viewTreeObserver.takeIf { it.isAlive }
            ?.removeOnGlobalLayoutListener(globalLayoutListener)
        super.onDestroy()
    }

    /** dp-шкала экрана; `internal` — её зовут секции (тела строк и диалогов). */
    internal fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
