package ru.mars.mobile.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Реализация [AsrBackend] на sherpa-onnx (JitPack, `v1.13.8`).
 *
 * Модель — streaming zipformer small RU (vosk, int8): 4 файла ONNX + tokens.txt, замеренный
 * на сервере RTF 0.040-0.050 и первый partial за 24-29 мс на двух потоках.
 *
 * ГРАБЛИ, заложенные в код:
 * 1. `acceptWaveform` принимает только float32 -1..1 (конвертация — [Pcm16ToFloat]);
 *    на int16 native молча возвращает пустую строку.
 * 2. hotwords в sherpa-onnx живут ТОЛЬКО в `modified_beam_search`: в `greedy_search`
 *    ветке C++-инициализация не создаёт ни `bpe_encoder`, ни `hotwords_graph`, поэтому
 *    `hotwords_file` игнорируется. Хуже: если передать hotwords в stream при
 *    `modeling_unit` не из {bpe, cjkchar, bbpe}, `EncodeHotwords` вызывает
 *    `SHERPA_ONNX_EXIT(-1)` — процесс умирает без исключения. Поэтому hotwords, `bpeVocab`
 *    и `modeling_unit = "bpe"` включаются строго вместе и только под modified_beam_search.
 * 3. Для BPE-модели слова кодируются в токены через `bpe.model`, который лежит рядом
 *    с моделью; без него контекстный биасинг не собрать.
 */
class SherpaAsrBackend : AsrBackend {

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    /** Hotwords реально включены (а не просто запрошены в конфиге). */
    var isHotwordsActive: Boolean = false
        private set

    override fun initialize(modelDir: File, config: AsrConfig): Boolean {
        release()
        if (!modelDir.isDirectory) {
            Log.w(TAG, "model dir missing: ${modelDir.absolutePath}")
            return false
        }

        val useHotwords = config.hotwords.isNotEmpty()
        val decodingMethod = resolveDecodingMethod(config, useHotwords)

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, ENCODER).absolutePath,
                decoder = File(modelDir, DECODER).absolutePath,
                joiner = File(modelDir, JOINER).absolutePath,
            ),
            tokens = File(modelDir, TOKENS).absolutePath,
            numThreads = config.numThreads,
            debug = false,
            provider = "cpu",
            modelType = "zipformer",
            // Пустые строки, когда hotwords выключены: native-слой тогда не пытается
            // читать bpe.model и не валидирует modeling_unit.
            modelingUnit = if (useHotwords) "bpe" else "",
            bpeVocab = if (useHotwords) File(modelDir, BPE).absolutePath else "",
        )

        val recognizerConfig = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, config.endpointRule1MinTrailingSilence, 0.0f),
                rule2 = EndpointRule(true, config.endpointRule2MinTrailingSilence, 0.0f),
                rule3 = EndpointRule(false, 0.0f, config.endpointRule3MinUtteranceLength),
            ),
            enableEndpoint = true,
            decodingMethod = decodingMethod,
            maxActivePaths = MAX_ACTIVE_PATHS,
            hotwordsScore = config.hotwordsScore,
        )

        return try {
            val created = OnlineRecognizer(config = recognizerConfig)
            recognizer = created
            isHotwordsActive = useHotwords && decodingMethod == AsrConfig.MODIFIED_BEAM_SEARCH
            true
        } catch (t: Throwable) {
            // native падает не исключением, а abort-ом, но ошибки конфига/файлов приходят сюда.
            Log.e(TAG, "failed to create OnlineRecognizer", t)
            recognizer = null
            isHotwordsActive = false
            false
        }
    }

    override fun newStream(hotwords: List<String>) {
        val rec = recognizer ?: return
        stream?.release()
        stream = try {
            if (hotwords.isEmpty()) {
                rec.createStream()
            } else {
                // sherpa-onnx режет строку по '/', поэтому слова клеим именно так.
                rec.createStream(hotwords.joinToString("/"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to create stream", t)
            null
        }
    }

    override fun acceptSamples(samples: FloatArray) {
        if (samples.isEmpty()) return
        stream?.acceptWaveform(samples, SAMPLE_RATE)
    }

    override fun isReady(): Boolean {
        val rec = recognizer ?: return false
        val s = stream ?: return false
        return rec.isReady(s)
    }

    override fun decode() {
        val rec = recognizer ?: return
        val s = stream ?: return
        rec.decode(s)
    }

    override fun isEndpoint(): Boolean {
        val rec = recognizer ?: return false
        val s = stream ?: return false
        return rec.isEndpoint(s)
    }

    override fun result(): String {
        val rec = recognizer ?: return ""
        val s = stream ?: return ""
        return rec.getResult(s).text
    }

    override fun finishStream() {
        // inputFinished говорит native, что аудио больше не будет: он дочитывает хвост.
        // Stream НЕ освобождаем — движок ещё вычитает из него результат; освободит
        // его release() или следующий newStream.
        stream?.inputFinished()
    }

    override fun release() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
        isHotwordsActive = false
    }

    private fun resolveDecodingMethod(config: AsrConfig, useHotwords: Boolean): String = when {
        // Явный greedy_search при hotwords: подсказки молча не заработают, поэтому
        // поднимаем режим до modified_beam_search — иначе пользователь ждёт биасинг,
        // которого нет.
        useHotwords -> AsrConfig.MODIFIED_BEAM_SEARCH
        config.decodingMethod == AsrConfig.AUTO -> AsrConfig.GREEDY_SEARCH
        else -> config.decodingMethod
    }

    companion object {
        private const val TAG = "MarsAsrEngine"
        private const val SAMPLE_RATE = 16_000
        private const val FEATURE_DIM = 80
        private const val MAX_ACTIVE_PATHS = 4

        private const val ENCODER = "encoder.int8.onnx"
        private const val DECODER = "decoder.onnx"
        private const val JOINER = "joiner.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val BPE = "bpe.model"
    }
}
