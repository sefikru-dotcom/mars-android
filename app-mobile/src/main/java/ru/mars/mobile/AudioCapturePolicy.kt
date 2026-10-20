package ru.mars.mobile

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Источник записи, который умеет пробовать политика захвата, в терминах домена (Android-free:
 * отображение на константы `MediaRecorder.AudioSource` живёт в [AndroidAudioSourceFactory]).
 *
 * - [VOICE_COMMUNICATION] — штатный путь для голосового диалога: платформа включает на этом
 *   маршруте эхоподавление и шумодав, поэтому голос ассистента из динамика не попадает в запись
 *   как «речь человека» (без этого перебивание слышит само себя);
 * - [MIC] — прежний путь и явный откат: обработки не даёт, зато открывается практически везде.
 *
 * [id] — короткое имя для логов и отчётов о замерах; оно стабильно, поэтому по logcat можно
 * сравнить поведение на устройстве, не читая код.
 */
enum class CaptureSource(val id: String) {
    VOICE_COMMUNICATION("voice_communication"),
    MIC("mic"),
}

/**
 * Аппаратный эффект записи, который политика умеет включать поверх открытого AudioRecord.
 * [id] — стабильное короткое имя для логов (и для сравнения замеров между сборками).
 */
enum class CaptureEffectKind(val id: String) {
    ACOUSTIC_ECHO_CANCELER("aec"),
    NOISE_SUPPRESSOR("ns"),
}

/**
 * Итог попытки включить эффект. Отдельные значения нужны, чтобы отчёт о замерах отличал
 * «платформа не умеет» ([UNAVAILABLE]) от «умеет, но отказала» ([FAILED]): первое — норма для
 * части устройств, второе — повод смотреть прошивку.
 */
enum class EffectDecision {
    /** Запрошен и реально включён: платформа подтвердила enabled. */
    ENABLED,

    /** Не запрашивался (флаг выключен) — эффекта в плане нет. */
    NOT_REQUESTED,

    /** Платформа сообщила, что эффекта нет (isAvailable == false / create вернул null). */
    UNAVAILABLE,

    /** Эффект есть, но включить не удалось (setEnabled вернул ошибку или бросил). */
    FAILED,
}

/**
 * Включённый аппаратный эффект записи. Порт намеренно узкий (только освобождение): включение
 * делает платформенный адаптер в [AndroidAudioSourceFactory], а правило «освободить ровно один
 * раз» проверяется на JVM фейком.
 */
interface CaptureEffect {
    val name: String

    /** Освободить эффект. Повторные вызовы допустимы и игнорируются вызывающей стороной. */
    fun release()
}

/** Результат попытки получить один эффект у платформы. */
sealed interface EffectAcquisition {
    /** Эффект создан и включён; владение переходит к [AudioCaptureEffects]. */
    class Enabled(val effect: CaptureEffect) : EffectAcquisition

    /** Платформа сообщила, что эффекта на этом устройстве нет. */
    object Unavailable : EffectAcquisition

    /** Эффект есть, но включить не удалось. */
    object Failed : EffectAcquisition
}

/**
 * Набор включённых эффектов одного AudioRecord. Освобождается ровно один раз: и [release] из
 * [VoiceRecorder] (поток чтения), и остановка контроллера могут прийти одновременно, а платформа
 * не любит повторный `release()` эффекта. Тот же инвариант, что у самого AudioRecord
 * (`AtomicBoolean`), поэтому «ровно один раз» проверяется юнит-тестом.
 *
 * Ошибка освобождения одного эффекта не мешает освободить остальные: залипший эффект не должен
 * удерживать микрофон.
 */
class AudioCaptureEffects(effects: List<CaptureEffect> = emptyList()) {

    private val held: List<CaptureEffect> = effects.toList()
    private val released = AtomicBoolean(false)

    /** Имена включённых эффектов в порядке плана — для лога и тестов. */
    val names: List<String> = held.map { it.name }

    val isEmpty: Boolean get() = held.isEmpty()

    val isReleased: Boolean get() = released.get()

    fun release() {
        if (!released.compareAndSet(false, true)) return
        for (effect in held) {
            runCatching { effect.release() }
        }
    }
}

/** Итог включения эффектов: что удерживается и чем закончилась каждая попытка. */
data class EffectActivation(
    val effects: AudioCaptureEffects = AudioCaptureEffects(),
    val decisions: Map<CaptureEffectKind, EffectDecision> = emptyMap(),
) {
    fun decision(kind: CaptureEffectKind): EffectDecision =
        decisions[kind] ?: EffectDecision.NOT_REQUESTED
}

/** Открытый захват: какой кандидат сработал и был ли это откат. */
data class OpenedCapture(
    val source: AudioSource,
    val captureSource: CaptureSource,
    val fallbackUsed: Boolean,
)

/**
 * Снимок решения о захвате для logcat и замеров на устройстве: один стабильный строковый формат,
 * по которому можно сравнивать сборки и устройства, не залезая в код.
 */
data class AudioCaptureDiagnostics(
    val captureSource: CaptureSource,
    val fallbackUsed: Boolean,
    val echoCancellationRequested: Boolean,
    val noiseSuppressionRequested: Boolean,
    val activation: EffectActivation = EffectActivation(),
) {
    /** Аппаратное эхоподавление реально работает на этой сессии. */
    val echoCanceled: Boolean
        get() = activation.decision(CaptureEffectKind.ACOUSTIC_ECHO_CANCELER) == EffectDecision.ENABLED

    /** Аппаратный шумодав реально работает на этой сессии. */
    val noiseSuppressed: Boolean
        get() = activation.decision(CaptureEffectKind.NOISE_SUPPRESSOR) == EffectDecision.ENABLED

    fun logLine(): String = buildString {
        append("capture source=").append(captureSource.id)
        append(" fallback=").append(fallbackUsed)
        append(" echo_cancellation=").append(if (echoCancellationRequested) "on" else "off")
        append(" noise_suppression=").append(if (noiseSuppressionRequested) "on" else "off")
        append(" aec=").append(activation.decision(CaptureEffectKind.ACOUSTIC_ECHO_CANCELER).name)
        append(" ns=").append(activation.decision(CaptureEffectKind.NOISE_SUPPRESSOR).name)
    }
}

/**
 * Чистая политика захвата (Android-free, для JVM-тестов) по образцу [AudioFxPolicy]:
 * порядок кандидатов источника, правила отката и решение «какой эффект включать».
 *
 * Порядок кандидатов зависит от флага [echoCancellation]:
 * - включён (по умолчанию) — [CaptureSource.VOICE_COMMUNICATION], затем [CaptureSource.MIC];
 * - выключен — только [CaptureSource.MIC], то есть ровно прежнее поведение. Это и есть ручка для
 *   сравнения на железе: A/B отличается одним флагом, а не правкой кода.
 *
 * Откат делается на любую ошибку инициализации, КРОМЕ отказа в разрешении: `SecurityException`
 * означает, что RECORD_AUDIO не выдан, а он не выдан для всех источников сразу — повторять
 * попытку на MIC значило бы подменить понятную ошибку «нет доступа» на «микрофон недоступен».
 */
object AudioCapturePolicy {

    /** По умолчанию эхоподавление включено: без него перебивание слышит собственный динамик. */
    const val DEFAULT_ECHO_CANCELLATION: Boolean = true

    /** Шумодав включается вместе с AEC: он не мешает распознаванию и чистит запись. */
    const val DEFAULT_NOISE_SUPPRESSION: Boolean = true

    private val ECHO_CANCELING: List<CaptureSource> =
        listOf(CaptureSource.VOICE_COMMUNICATION, CaptureSource.MIC)

    private val LEGACY: List<CaptureSource> = listOf(CaptureSource.MIC)

    /** Кандидаты в порядке попыток; первый — предпочтительный. */
    fun candidates(echoCancellation: Boolean): List<CaptureSource> =
        if (echoCancellation) ECHO_CANCELING else LEGACY

    /** Стоит ли пробовать следующего кандидата после [failure]. */
    fun isFallbackWorthy(failure: Throwable): Boolean = failure !is SecurityException

    /** Какие эффекты запрашивать для этой конфигурации. */
    fun effectPlan(echoCancellation: Boolean, noiseSuppression: Boolean): List<CaptureEffectKind> =
        buildList {
            if (echoCancellation) add(CaptureEffectKind.ACOUSTIC_ECHO_CANCELER)
            if (noiseSuppression) add(CaptureEffectKind.NOISE_SUPPRESSOR)
        }

    /** Перевод результата попытки в решение для отчёта. */
    fun effectDecision(requested: Boolean, acquisition: EffectAcquisition): EffectDecision = when {
        !requested -> EffectDecision.NOT_REQUESTED
        acquisition is EffectAcquisition.Enabled -> EffectDecision.ENABLED
        acquisition is EffectAcquisition.Unavailable -> EffectDecision.UNAVAILABLE
        else -> EffectDecision.FAILED
    }

    /**
     * Включает эффекты из [plan] через [acquire], не падая ни на одном из них: исключение или
     * отказ платформы — это просто [EffectDecision.FAILED], захват продолжается без эффекта.
     * Включённые эффекты собираются в один [AudioCaptureEffects], который освобождается вместе с
     * AudioRecord.
     */
    fun activateEffects(
        plan: List<CaptureEffectKind>,
        acquire: (CaptureEffectKind) -> EffectAcquisition,
    ): EffectActivation {
        val held = ArrayList<CaptureEffect>(plan.size)
        val decisions = LinkedHashMap<CaptureEffectKind, EffectDecision>(plan.size)
        for (kind in plan) {
            val acquisition = try {
                acquire(kind)
            } catch (error: Exception) {
                EffectAcquisition.Failed
            }
            decisions[kind] = effectDecision(requested = true, acquisition)
            if (acquisition is EffectAcquisition.Enabled) held.add(acquisition.effect)
        }
        return EffectActivation(AudioCaptureEffects(held), decisions)
    }
}

/**
 * Открывает лучший доступный захват: пробует кандидатов [AudioCapturePolicy.candidates] по
 * порядку и возвращает первый успешно открытый.
 *
 * Правила отката:
 * - `SecurityException` (включая [MicrophonePermissionDeniedException]) прерывает перебор:
 *   разрешение не выдано для всех источников сразу;
 * - любая другая ошибка (неинициализированный AudioRecord, отказ `startRecording`, отказ
 *   платформенного конструктора) означает «этот кандидат на устройстве не работает» и ведёт к
 *   следующему;
 * - если не открылся ни один, наружу уходит последняя ошибка, а предыдущие прикладываются к ней
 *   как suppressed — так в логе видно обе причины, а не только финальную.
 *
 * Класс ничего не знает про Android: [openSource] — точка подмены, поэтому откат проверяется на
 * JVM фейковой фабрикой, которая роняет первую попытку.
 */
class AudioCaptureSelector(
    private val openSource: (CaptureSource) -> AudioSource,
    private val onAttemptFailed: (CaptureSource, Exception) -> Unit = { _, _ -> },
) {

    fun open(echoCancellation: Boolean = AudioCapturePolicy.DEFAULT_ECHO_CANCELLATION): OpenedCapture {
        val candidates = AudioCapturePolicy.candidates(echoCancellation)
        var failure: Exception? = null
        for ((index, candidate) in candidates.withIndex()) {
            try {
                val source = openSource(candidate)
                return OpenedCapture(
                    source = source,
                    captureSource = candidate,
                    fallbackUsed = index > 0,
                )
            } catch (denied: SecurityException) {
                failure?.let { denied.addSuppressed(it) }
                throw denied
            } catch (error: Exception) {
                onAttemptFailed(candidate, error)
                failure?.let { error.addSuppressed(it) }
                failure = error
            }
        }
        throw failure ?: IllegalStateException("no capture source candidates")
    }
}
