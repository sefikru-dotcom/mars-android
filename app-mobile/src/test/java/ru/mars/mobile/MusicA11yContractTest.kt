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

    private val activity by lazy { source("src/main/java/ru/mars/mobile/MusicActivity.kt") }

    /**
     * Секции-расширения экрана после раскладки (шаги 0–6): вынесенный код — тот же
     * production-код экрана, поэтому проверки доступности читают его вместе с Activity.
     */
    private val sections by lazy {
        listOf(
            "MusicPlaybackGlyphSection.kt",
            "MusicAudioSettingsSection.kt",
            "MusicForYouSection.kt",
            "MusicChartSection.kt",
            "MusicFlowSection.kt",
            "MusicFavoritesSection.kt",
        ).joinToString("\n") { source("src/main/java/ru/mars/mobile/$it") }
    }

    // --- MUS-M2: статусная строка озвучивается без переноса фокуса ---

    @Test fun statusTextIsPoliteLiveRegion() {
        val block = activity
            .substringAfter("statusText = TextView(this).apply {")
            .substringBefore("root.addView(statusText")
        assertTrue(
            "statusText обязан быть live region POLITE — обратная связь (избранное/очередь/ошибки) озвучивается сама",
            block.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"),
        )
    }

    // --- MUS-M1: главная кнопка Play/Pause читает действие, а не состояние ---

    @Test fun mainPlayButtonStartsWithPausedActionLabel() {
        val block = activity
            .substringAfter("playButton = MarsTheme.circleIconButton(")
            .substringBefore("controls.addView(playButton")
        assertTrue(
            "Стартовая подпись главной кнопки — действие paused-состояния из общей политики",
            block.contains("MusicA11yPolicy.playPauseAction(isPlaying = false)"),
        )
    }

    @Test fun mainPlayButtonLabelFollowsEveryTransportStateChange() {
        val block = activity
            .substringAfter("override fun onStateChanged")
            .substringBefore("override fun onProgress")
        assertTrue(
            "onStateChanged обязан обновлять contentDescription главной кнопки из политики",
            block.contains("playButton.contentDescription = MusicA11yPolicy.playPauseAction(isPlaying)"),
        )
    }

    @Test fun noStaleStateBasedMainLabelRemains() {
        assertFalse(
            "Статическая state-подпись «Воспроизведение» на Play/Pause запрещена",
            activity.contains("\"Воспроизведение\""),
        )
    }

    // --- MUS-M3: shuffle/repeat/heart/tabs озвучивают текущее состояние действием ---

    @Test fun shuffleAndRepeatLabelsUpdateWithQueueState() {
        val block = activity
            .substringAfter("private fun updateQueueIcons()")
            .substringBefore("/** Raw body")
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
        val shuffle = activity
            .substringAfter("shuffleBtn = MarsTheme.circleIconButton(")
            .substringBefore("controls.addView(shuffleBtn")
        assertTrue(shuffle.contains("MusicA11yPolicy.shuffleAction(shuffleOn = false)"))
        val repeat = activity
            .substringAfter("repeatBtn = MarsTheme.circleIconButton(")
            .substringBefore("controls.addView(repeatBtn")
        assertTrue(repeat.contains("MusicA11yPolicy.repeatAction(MusicQueueManager.RepeatMode.OFF)"))
    }

    @Test fun heartLabelFollowsFavoriteState() {
        val creation = activity
            .substringAfter("// Favorite heart")
            .substringBefore("heartButtons.getOrPut")
        assertTrue(
            "Кнопка избранного в строке создаётся с подписью из политики",
            creation.contains("MusicA11yPolicy.heartAction(isFav)"),
        )
        val refresh = sections
            .substringAfter("fun MusicActivity.refreshHeart(id: String)")
            .substringBefore("fun MusicActivity.onHeartClicked(")
        assertTrue(
            "refreshHeart обязан обновлять contentDescription вместе с иконкой",
            refresh.contains("button.contentDescription = MusicA11yPolicy.heartAction(filled)"),
        )
    }

    @Test fun tabsAnnounceTheActiveTab() {
        val block = activity
            .substringAfter("private fun updateTabStyles()")
            .substringBefore("private fun selectTab(")
        assertTrue(
            "Активная вкладка обязана озвучиваться («… — активная вкладка») через политику",
            block.contains("MusicA11yPolicy.tabDescription("),
        )
    }

    // --- строки списка: те же подписи из того же источника ---

    @Test fun trackRowButtonsUseTheSharedPolicyLabels() {
        assertTrue(
            "Кнопка строки обязана брать подпись из общей политики, а не из своих литералов",
            activity.contains(
                "holder.playButton.contentDescription = MusicA11yPolicy.playPauseAction(state.showPauseIcon)",
            ),
        )
        val rowCreation = activity
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
