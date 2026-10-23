package ru.mars.mobile

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Настройки звука экрана музыки: диалог «Звук» (вкл/выкл, пресеты эквалайзера,
 * Bass Boost) и выбор профиля аудиопотока.
 *
 * Состояние живёт в хосте ([AudioFxSettings], [StreamQualitySettings] — поля `audioFx`,
 * `streamQuality`), применение к плееру — через [MarsMusicPlayer]; здесь только диалоги,
 * их перерисовка на месте и подсветка иконок шапки.
 */

/**
 * «Звук»: тёмный обсидиановый [MarsDialog] с тумблером обработки звука, выбором
 * пресета эквалайзера (паритет с WebUI EQ_PRESETS) и ползунком Bass Boost.
 * Каждое изменение сразу уходит в живой плеер ([MarsMusicPlayer.setAudioFx]) и
 * сохраняется через [AudioFxSettings]; тело диалога перерисовывается на месте.
 */
internal fun MusicActivity.onEqClicked() {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var dialogRef: Dialog? = null
    dialogRef = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, "Звук", audioFx.state.preset.title))
        renderEqBody(body)
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(MarsDialog.quietButton(this, "Закрыть") { dialogRef?.dismiss() })
        emptyList()
    }
}

/** Fills the dialog body: on/off toggle, preset stack, Bass Boost slider. */
private fun MusicActivity.renderEqBody(body: LinearLayout) {
    body.removeAllViews()
    val state = audioFx.state

    body.addView(MarsDialog.caption(this, "Обработка звука"))
    val toggle = MarsDialog.pill(
        this,
        if (state.enabled) "Включена" else "Выключена",
        bg = if (state.enabled) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
        fg = if (state.enabled) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED,
        border = if (state.enabled) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
    ) {
        audioFx.setEnabled(!state.enabled)
        onAudioFxChanged()
        renderEqBody(body)
    }
    body.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    body.addView(MarsDialog.caption(this, "Пресет эквалайзера"))
    AudioFxPreset.values().forEach { preset ->
        val selected = state.preset == preset
        val pill = MarsDialog.pill(
            this,
            preset.title,
            bg = if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
            fg = if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_EM,
            border = if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
        ) {
            audioFx.setPreset(preset)
            onAudioFxChanged()
            renderEqBody(body)
        }
        body.addView(pill, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    val bassLabel = TextView(this).apply {
        text = bassBoostCaption(state.bassBoost)
        textSize = 10f
        setTextColor(MarsTheme.TEXT_MUTED)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }
    body.addView(bassLabel, LinearLayout.LayoutParams(-1, -2))
    val bassSeek = SeekBar(this).apply {
        max = AudioFxPolicy.MAX_BASS_BOOST
        progress = state.bassBoost
        progressTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        thumbTintList = ColorStateList.valueOf(MarsTheme.ACCENT)
        isEnabled = state.enabled
        alpha = if (state.enabled) 1f else 0.4f
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                audioFx.setBassBoost(value)
                bassLabel.text = bassBoostCaption(audioFx.state.bassBoost)
                onAudioFxChanged()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }
    body.addView(bassSeek, LinearLayout.LayoutParams(-1, -2))
}

/** Pushes the persisted state into the live player and refreshes the header icon. */
private fun MusicActivity.onAudioFxChanged() {
    MarsMusicPlayer.setAudioFx(audioFx.state)
    updateEqIcon()
}

/** Header «Звук» glows ruby while sound processing is enabled. */
internal fun MusicActivity.updateEqIcon() {
    if (!isEqReady()) return
    eqBtn.imageTintList = ColorStateList.valueOf(
        if (audioFx.state.enabled) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED
    )
}

/** Opens the explicit original/standard/economy stream profile picker. */
internal fun MusicActivity.onQualityClicked() {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var dialogRef: Dialog? = null
    dialogRef = MarsDialog.show(this) { root ->
        root.addView(MarsDialog.header(this, "Качество потока", streamQuality.value.label))
        renderQualityBody(body)
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(MarsDialog.quietButton(this, "Закрыть") { dialogRef?.dismiss() })
        emptyList()
    }
}

/** Renders selectable quality pills and applies a choice without rebuilding the screen. */
private fun MusicActivity.renderQualityBody(body: LinearLayout) {
    body.removeAllViews()
    val selected = streamQuality.value
    body.addView(MarsDialog.caption(this, "Профиль аудиопотока"))
    StreamQuality.selectable.forEach { quality ->
        val isSelected = quality == selected
        val pill = MarsDialog.pill(
            this,
            quality.label,
            bg = if (isSelected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE_SUBTLE,
            fg = if (isSelected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_EM,
            border = if (isSelected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_MUTED,
        ) {
            streamQuality.set(quality)
            MarsMusicPlayer.setStreamQuality(quality)
            updateQualityIcon()
            renderQualityBody(body)
        }
        body.addView(pill, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
}

/** Highlights the active explicit quality profile in the header. */
internal fun MusicActivity.updateQualityIcon() {
    if (!isQualityReady()) return
    qualityBtn.imageTintList = ColorStateList.valueOf(
        if (streamQuality.value.isExplicit) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED
    )
    qualityBtn.contentDescription = "Качество потока: ${streamQuality.value.label}"
}

/** «BASS BOOST · 65%»: hardware scale 0..1000 shown as percent, WebUI-style caption. */
private fun MusicActivity.bassBoostCaption(strength: Int): String =
    "BASS BOOST · ${AudioFxPolicy.sanitizeStrength(strength) / 10}%"
