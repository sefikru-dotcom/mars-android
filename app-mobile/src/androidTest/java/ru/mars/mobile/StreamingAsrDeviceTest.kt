package ru.mars.mobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.mars.mobile.asr.AsrConfig

/**
 * Приёмка нативного движка на живом устройстве.
 *
 * Зачем отдельно от JVM-тестов: политику диалога проверяет JVM с фейковым бэкендом, а здесь
 * проверяется то, что можно проверить только на железе — что нативная библиотека sherpa-onnx
 * грузится под arm64, модель скачивается по HTTPS и распознаватель инициализируется без
 * SIGABRT. Именно на этом шаге приложение умирало: native вызывает SHERPA_ONNX_EXIT(-1) на
 * несовместимом формате BPE-словаря, и процесс гибнет сигналом 6 без исключения.
 *
 * Тест намеренно НЕ помечен как emulator-only: он нужен на POCO F5.
 * Запуск:
 *   ./gradlew :app-mobile:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.mars.mobile.StreamingAsrDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class StreamingAsrDeviceTest {

    private companion object {
        const val TAG = "MarsAsrDeviceTest"
    }

    @Test
    fun modelDownloadsAndRecognizerInitializes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = StreamingAsrEngine(context = ctx, config = AsrConfig())

        assertTrue("движок не загрузился (модель недоступна или native упал)", engine.load())
        assertTrue("движок не готов после load()", engine.isReady)
    }

    @Test
    fun acceptsPcmAndProducesResultOrEmptyWithoutCrash() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val events = mutableListOf<AsrEvent>()
        val engine = StreamingAsrEngine(
            context = ctx,
            config = AsrConfig(),
            listener = { synchronized(events) { events += it } },
        )
        assertTrue("движок не загрузился", engine.load())

        engine.startUtterance()
        // Секунда тишины 16 кГц моно 16-bit: движок обязан проглотить поток без падения.
        // Пустой результат здесь — норма (речи нет), важен сам факт живой обработки.
        val silence = ByteArray(32_000)
        var offset = 0
        while (offset < silence.size) {
            val chunk = minOf(3_200, silence.size - offset)
            engine.acceptPcm(silence, chunk)
            offset += chunk
        }
        val final = engine.finishUtterance()
        Log.i(TAG, "engine alive after 1s of silence; final='$final'")
        assertTrue("движок перестал быть готовым", engine.isReady)
        engine.release()
    }
}
