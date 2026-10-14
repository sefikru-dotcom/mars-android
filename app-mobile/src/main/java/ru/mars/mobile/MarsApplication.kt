package ru.mars.mobile

import android.app.Application

/**
 * Application-класс процесса (в манифесте: android:name=".MarsApplication").
 *
 * Единственная задача — реакция на системное давление памяти: onTrimMemory/
 * onLowMemory. Решение «чистить или нет» принимает чистая [MemoryTrimPolicy]
 * (JVM-тесты: MemoryTrimPolicyTest); здесь только dispatch.
 *
 * Что чистим:
 * - битмап-кэш [ImageLoader] ([ImageLoader.trimMemoryCache]): LruCache ограничен
 *   бюджетом [ImageLoaderPolicy.cacheBudgetBytes] (≤ 24 МБ), под давлением памяти
 *   освобождается целиком, битмапы перезагружаются по URL лениво;
 * - кэш разбора Markdown ([MarkdownFormatter.trimCache]): восстанавливается лениво
 *   при следующем рендере.
 *
 * Транскрипт «подрезать» здесь не нужно: рост состояния уже ограничен
 * [TranscriptCapPolicy] (cap на число сообщений и текст пузыря), а view-дерево
 * транскрипта принадлежит ChatActivity и освобождается системой вместе с ним.
 */
class MarsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // ExoPlayer (Media3) не собирается без Context: процессный плеер получает
        // application-контекст здесь — раньше любого экрана/сервиса, который может
        // запустить воспроизведение (MusicActivity, ChatActivity, MarsMusicService).
        MarsMusicPlayer.attachContext(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (MemoryTrimPolicy.shouldClearCaches(level)) clearProcessCaches()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        clearProcessCaches()
    }

    private fun clearProcessCaches() {
        ImageLoader.trimMemoryCache()
        MarkdownFormatter.trimCache()
    }
}
