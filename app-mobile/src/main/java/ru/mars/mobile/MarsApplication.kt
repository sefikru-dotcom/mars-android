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
 * - битмап-кэш [ImageLoader]: публичного метода очистки на этой ветке нет —
 *   зона render-primitives (параллельный воркер) добавляет кэши/чистку внутри
 *   ImageLoader обратно совместимо. Вызов идёт best-effort по отражению
 *   (`trimMemoryCache`): после слияния веток заработает автоматически, а пока
 *   метода нет — runCatching молча не делает ничего. Сам ImageLoader здесь не
 *   меняется; его LruCache в любом случае ограничен бюджетом
 *   [ImageLoaderPolicy.cacheBudgetBytes] (≤ 24 МБ) и вытесняет сам.
 * - кэши парсинга Markdown — аналогично: кэш живёт внутри MarkdownFormatter (зона B),
 *   вызов также best-effort (`trimCache`).
 *
 * Транскрипт «подрезать» здесь не нужно: рост состояния уже ограничен
 * [TranscriptCapPolicy] (cap на число сообщений и текст пузыря), а view-дерево
 * транскрипта принадлежит ChatActivity и освобождается системой вместе с ним.
 */
class MarsApplication : Application() {

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (MemoryTrimPolicy.shouldClearCaches(level)) clearProcessCaches()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        clearProcessCaches()
    }

    private fun clearProcessCaches() {
        invokeIfExists(ImageLoader::class.java, ImageLoader, "trimMemoryCache")
        invokeIfExists(MarkdownFormatter::class.java, MarkdownFormatter, "trimCache")
    }

    /**
     * Best-effort вызов необязательного public-метода без аргументов: метод может
     * отсутствовать (контракт веток — сигнатуры существующих входов неизменны,
     * новые добавляются независимо), поэтому любой отказ молча игнорируется.
     */
    private fun invokeIfExists(owner: Class<*>, instance: Any, methodName: String) {
        runCatching { owner.getMethod(methodName).invoke(instance) }
    }
}
