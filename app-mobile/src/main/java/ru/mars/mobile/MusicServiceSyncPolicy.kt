package ru.mars.mobile

/**
 * Чистое решение, когда экран музыки обязан (пере)поднять foreground-сервис плеера.
 * Без Android-типов — проверяется JVM-тестом [MusicServiceSyncPolicyTest].
 *
 * Зачем: флаг `musicServiceStarted` живёт в Activity, а сервис умеет останавливаться
 * сам (`onTaskRemoved` в паузе, `stopSelf` при опустевшей очереди) и может быть убит
 * системой. Флаг при этом остаётся `true`, и повторное нажатие «играть» не поднимало
 * сервис: музыка шла без уведомления и без foreground-режима, поэтому Android мог её
 * тихо убить при уходе приложения в фон.
 *
 * Правило: трека нет — флаг сбрасывается; трек есть — сервис поднимается при первом
 * появлении трека и перепроверяется на каждом кадре воспроизведения (это дешёвый
 * `startForegroundService` для уже работающего сервиса, зато пропущенный FGS всегда
 * восстановится). Пауза с уже поднятым сервисом ничего не дёргает.
 */
object MusicServiceSyncPolicy {

    data class Decision(
        /** Нужно ли вызвать MarsMusicService.start(). */
        val startService: Boolean,
        /** Какое значение запомнить в флаге экрана. */
        val rememberStarted: Boolean,
    )

    fun resolve(hasTrack: Boolean, isPlaying: Boolean, previouslyStarted: Boolean): Decision =
        when {
            !hasTrack -> Decision(startService = false, rememberStarted = false)
            !previouslyStarted || isPlaying -> Decision(startService = true, rememberStarted = true)
            else -> Decision(startService = false, rememberStarted = true)
        }
}
