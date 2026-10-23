package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт доступности экрана «Музыка» (MUS-M1/M2/M3): TalkBack-подписи главного
 * Play/Pause — ДЕЙСТВИЕ («Пауза» на ходу, «Воспроизвести» в паузе), статусная
 * строка — live region POLITE, подписи shuffle/repeat/heart/строк/вкладок
 * обновляются вместе с состоянием и берутся из единой чистой политики
 * [MusicA11yPolicy] (значения не дублируются в слое вью).
 *
 * Instrumented-слоя для телефона в проекте нет, поэтому проводка закреплена как
 * контракт по РЕАЛЬНОМУ production-исходнику (паттерн [ProjectsShellContractTest]):
 * удаление live region, возврат статической подписи «Воспроизведение» или обход
 * политики ломает тест. Сами строки проверяются на чистой политике в
 * [MusicA11yPolicyTest].
 */
class MusicA11yContractTest {

    private fun source(relative: String): String {
        // Unit-тесты Gradle работают из каталога модуля app-mobile; подстраховка —
        // подъём вверх до корня репозитория (worktree/CI могут ставить другой cwd).
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    private val sourceDir: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app-mobile/src/main/java/ru/mars/mobile")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile
        }
        throw AssertionError("Не найден каталог исходников app-mobile")
    }

    /**
     * Все исходники экрана «Музыка» одной строкой: хост (lifecycle, поля, диспетчер
     * вкладок) плюс каждая `Music*.kt` — секции-расширения и вынесенные политики.
     *
     * После декомпозиции строки разъехались по файлам, поэтому контракт проверяет
     * ПОВЕДЕНИЕ, а не место хранения строки. Glob, а не перечисление: следующий
     * вынос подхватится сам, тест править не придётся.
     */
    private val musicSources: String by lazy {
        val parts = mutableListOf(source("src/main/java/ru/mars/mobile/MusicActivity.kt"))
        sourceDir.listFiles { f -> f.isFile && f.name.startsWith("Music") && f.name.endsWith(".kt") }
            ?.sortedBy { it.name }
            ?.forEach { parts += it.readText() }
        parts.joinToString("\n")
    }

    // --- MUS-M2: статусная строка озвучивается без переноса фокуса ---


    /**
     * Тело функции по сигнатуре: от объявления до её сбалансированной закрывающей скобки.
     * Нужен потому, что после декомпозиции «следующая функция» живёт в другом файле,
     * и грубый substringBefore захватывает чужие строки — контракт становится ложно-зелёным.
     */
    private fun funBody(signature: String): String {
        val start = musicSources.indexOf(signature)
        require(start >= 0) { "не найдена сигнатура: $signature" }
        var i = musicSources.indexOf('{', start + signature.length)
        require(i >= 0) { "нет тела у: $signature" }
        var depth = 0
        val from = i
        while (i < musicSources.length) {
            when (musicSources[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return musicSources.substring(from, i + 1)
                }
            }
            i++
        }
        throw AssertionError("не закрыто тело: $signature")
    }

    @Test fun statusTextIsPoliteLiveRegion() {
        val block = musicSources
            .substringAfter("statusText = TextView(this).apply {")
            .substringBefore("root.addView(statusText")
        assertTrue(
            "statusText обязан быть live region POLITE — обратная связь (избранное/очередь/ошибки) озвучивается сама",
            block.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"),
        )
    }

    // --- MUS-M1: главная кнопка Play/Pause читает действие, а не состояние ---

    @Test fun mainPlayButtonStartsWithPausedActionLabel() {
        val block = musicSources
            .substringAfter("playButton = MarsTheme.circleIconButton(")
            .substringBefore("controls.addView(playButton")
        assertTrue(
            "Стартовая подпись главной кнопки — действие paused-состояния из общей политики",
            block.contains("MusicA11yPolicy.playPauseAction(isPlaying = false)"),
        )
    }

    @Test fun mainPlayButtonLabelFollowsEveryTransportStateChange() {
        val block = musicSources
            .substringAfter("fun MusicActivity.renderPlayerState(")
            .substringBefore("fun MusicActivity.renderProgress(")
        assertTrue(
            "override в хосте обязан делегировать в renderPlayerState, иначе подпись не обновится",
            musicSources.contains(
                "override fun onStateChanged(track: MusicTrack?, isPlaying: Boolean) = renderPlayerState(track, isPlaying)",
            ),
        )
        assertTrue(
            "onStateChanged обязан обновлять contentDescription главной кнопки из политики",
            block.contains("playButton.contentDescription = MusicA11yPolicy.playPauseAction(isPlaying)"),
        )
    }

    @Test fun noStaleStateBasedMainLabelRemains() {
        assertFalse(
            "Статическая state-подпись «Воспроизведение» на Play/Pause запрещена",
            musicSources.contains("\"Воспроизведение\""),
        )
    }

    // --- MUS-M3: shuffle/repeat/heart/tabs озвучивают текущее состояние действием ---

    @Test fun shuffleAndRepeatLabelsUpdateWithQueueState() {
        val block = funBody("fun MusicActivity.updateQueueIcons()")
        assertTrue(
            "Подпись shuffle обязана обновляться из политики вместе с тонировкой",
            block.contains("shuffleBtn.contentDescription = MusicA11yPolicy.shuffleAction("),
        )
        assertTrue(
            "Подпись repeat обязана обновляться из политики вместе с иконкой",
            block.contains("repeatBtn.contentDescription = MusicA11yPolicy.repeatAction("),
        )
    }

    @Test fun shuffleAndRepeatButtonsAreBornWithPolicyLabels() {
        val shuffle = musicSources
            .substringAfter("shuffleBtn = MarsTheme.circleIconButton(")
            .substringBefore("controls.addView(shuffleBtn")
        assertTrue(shuffle.contains("MusicA11yPolicy.shuffleAction(shuffleOn = false)"))
        val repeat = musicSources
            .substringAfter("repeatBtn = MarsTheme.circleIconButton(")
            .substringBefore("controls.addView(repeatBtn")
        assertTrue(repeat.contains("MusicA11yPolicy.repeatAction(MusicQueueManager.RepeatMode.OFF)"))
    }

    @Test fun heartLabelFollowsFavoriteState() {
        val creation = musicSources
            .substringAfter("// Favorite heart")
            .substringBefore("heartButtons.getOrPut")
        assertTrue(
            "Кнопка избранного в строке создаётся с подписью из политики",
            creation.contains("MusicA11yPolicy.heartAction(isFav)"),
        )
        val refresh = musicSources
            .substringAfter("fun MusicActivity.refreshHeart(id: String)")
            .substringBefore("fun MusicActivity.onHeartClicked(")
        assertTrue(
            "refreshHeart обязан обновлять contentDescription вместе с иконкой",
            refresh.contains("button.contentDescription = MusicA11yPolicy.heartAction(filled)"),
        )
    }

    @Test fun tabsAnnounceTheActiveTab() {
        val block = musicSources
            .substringAfter("fun MusicActivity.updateTabStyles()")
            .substringBefore("fun MusicActivity.showStatus(")
        assertTrue(
            "Активная вкладка обязана озвучиваться («… — активная вкладка») через политику",
            block.contains("MusicA11yPolicy.tabDescription("),
        )
    }

    // --- строки списка: те же подписи из того же источника ---

    @Test fun trackRowButtonsUseTheSharedPolicyLabels() {
        assertTrue(
            "Кнопка строки обязана брать подпись из общей политики, а не из своих литералов",
            musicSources.contains(
                "holder.playButton.contentDescription = MusicA11yPolicy.playPauseAction(state.showPauseIcon)",
            ),
        )
        val rowCreation = musicSources
            .substringAfter("val playTrackBtn = MarsTheme.circleIconButton(")
            .substringBefore("row.addView(playTrackBtn")
        assertTrue(rowCreation.contains("MusicA11yPolicy.ACTION_PLAY"))
    }

    // --- мини-плеер чата говорит тем же голосом (единый источник строк) ---

    @Test fun miniPlayerDelegatesItsStatusLabelToTheSamePolicy() {
        val presenter = source("src/main/java/ru/mars/mobile/MiniPlayerPresenter.kt")
        assertTrue(
            "MiniPlayerPresenter обязан брать action-подпись из MusicA11yPolicy",
            presenter.contains("MusicA11yPolicy.playPauseAction(isPlaying)"),
        )
        assertFalse(
            "Дубликаты строк play/pause в презентере запрещены",
            presenter.contains("\"Пауза\"") || presenter.contains("\"Воспроизвести\""),
        )
    }
}
