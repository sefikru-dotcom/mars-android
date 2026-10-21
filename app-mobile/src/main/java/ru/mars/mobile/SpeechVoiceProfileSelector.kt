package ru.mars.mobile

import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsVoiceProfile
import ru.mars.network.repository.ServerTtsVoices

/**
 * Выбор голосового профиля для голосового диалога: естественный голос веба — основной,
 * локальный piper — только запасной.
 *
 * Зачем: в вебе Марс говорит `edge` + `ru-RU-SvetlanaNeural`, а Android v1 слал запрос без
 * `engine`/`voice` и получал `piper` + `ru_RU-dmitri-medium` — другой движок, другое
 * произношение («Флэш» вместо «Слэш»). Порядок профилей задаётся явно:
 *
 * 1. [ServerTtsVoices.NATURAL_SVETLANA] — тот самый голос веба. Он ПЕРВЫЙ всегда, пока
 *    доступен: подменять его «более быстрым» локальным нельзя, это выбор пользователя.
 * 2. [ServerTtsVoices.LOCAL_IRINA] — piper, если установлен. Запасной путь на случай,
 *    когда сеть/edge недоступны: irina произносит «Слэш» верно без словаря, TTFB ~0.22 с.
 * 3. Если недоступны оба — [candidates] пуст, и вызывающий слой честно уходит на системный
 *    TTS (а не молчит и не выдумывает третий голос).
 *
 * Понижение профиля — по НАБЛЮДЕНИЮ, а не по догадке: серверного капабилити-эндпоинта
 * пока нет (контракт в работе), поэтому единственный достоверный источник — фактический
 * ответ `api/tts`. Виды отказа различаются:
 * - `429 rate_limited` — это лимитер (2 с на клиента), а НЕ отсутствие голоса: профиль НЕ
 *   понижается, повтор делает очередь ([SpeechQueue]). Иначе первая же очередь фраз
 *   навсегда переключила бы диалог на irina вопреки желанию пользователя;
 * - `401 unauthorized` — проблема токена, голос ни при чём: профиль тоже не понижается;
 * - `400/404/501` (голоса нет в `voices_dir`, движок не установлен) — понижение надолго;
 * - транспорт/`5xx`/битый ответ — понижение ненадолго, чтобы вернуться к естественному
 *   голосу, как только сеть оживёт.
 *
 * @param profiles порядок предпочтения (первый — желаемый).
 * @param nowMs монотонные часы в мс (в тестах подменяются).
 * @param transientCooldownMs пауза перед повторной попыткой после сетевого сбоя.
 * @param permanentCooldownMs пауза после «голоса нет на сервере».
 * @param localVoiceInstalled установлен ли локальный piper-голос. Пока серверного
 *   капабилити нет, значение оптимистичное (`true`): если irina не установлена, первый же
 *   ответ `400/404` понизит её надолго, и диалог продолжит системным TTS. Хост сможет
 *   уточнить флаг, когда появится контракт возможностей.
 */
class SpeechVoiceProfileSelector(
    private val profiles: List<ServerTtsVoiceProfile> = ServerTtsVoices.VOICE_DIALOG,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val transientCooldownMs: Long = DEFAULT_TRANSIENT_COOLDOWN_MS,
    private val permanentCooldownMs: Long = DEFAULT_PERMANENT_COOLDOWN_MS,
    localVoiceInstalled: Boolean = true,
) {

    private val lock = Any()

    /** До какого момента профиль считается недоступным (id → время). */
    private val cooldownUntil = HashMap<String, Long>()

    /** Счётчик отказов по профилю — диагностика и тесты, решений не принимает. */
    private val failures = HashMap<String, Int>()

    private var localInstalled: Boolean = localVoiceInstalled

    /**
     * Профили, которые можно пробовать СЕЙЧАС, в порядке предпочтения.
     *
     * Пустой список — пробовать некого: вызывающий уходит на системный TTS.
     */
    fun candidates(): List<ServerTtsVoiceProfile> = synchronized(lock) {
        val now = nowMs()
        profiles.filter { profile ->
            isInstalledLocked(profile) && (cooldownUntil[profile.id] ?: 0L) <= now
        }
    }

    /** Первый доступный профиль или null. */
    fun current(): ServerTtsVoiceProfile? = candidates().firstOrNull()

    /** Уточнение установленности локального голоса (когда появится капабилити сервера). */
    fun setLocalVoiceInstalled(installed: Boolean) {
        synchronized(lock) { localInstalled = installed }
    }

    /** Сколько отказов накопил профиль (диагностика). */
    fun failureCount(profile: ServerTtsVoiceProfile): Int = synchronized(lock) {
        failures[profile.id] ?: 0
    }

    /** Профиль зазвучал: снимаем понижение и обнуляем счётчик отказов. */
    fun onAudio(profile: ServerTtsVoiceProfile) {
        synchronized(lock) {
            cooldownUntil.remove(profile.id)
            failures.remove(profile.id)
        }
    }

    /**
     * Синтез этим профилем не дал аудио.
     *
     * @return `true`, если профиль понижен (следующий запрос попробует другой голос);
     *   `false` — отказ не связан с голосом (`429`, `401`), порядок не меняется.
     */
    fun onUnavailable(profile: ServerTtsVoiceProfile, result: ServerTtsResult.Unavailable): Boolean {
        val now = nowMs()
        synchronized(lock) {
            failures[profile.id] = (failures[profile.id] ?: 0) + 1
            if (!demotesProfile(result)) return false
            val cooldown = if (isPermanent(result)) permanentCooldownMs else transientCooldownMs
            cooldownUntil[profile.id] = now + cooldown.coerceAtLeast(0L)
            return true
        }
    }

    /** Сбросить все понижения (новый диалог, смена сети, ручной выбор голоса). */
    fun reset() {
        synchronized(lock) {
            cooldownUntil.clear()
            failures.clear()
        }
    }

    private fun isInstalledLocked(profile: ServerTtsVoiceProfile): Boolean =
        profile.needsNetwork || localInstalled

    /**
     * Понижает ли такой отказ профиль.
     *
     * `rate_limited` и `unauthorized` — нет: лимитер и токен не говорят о наличии голоса,
     * и понижение здесь подменило бы желаемый голос из-за чужой проблемы.
     */
    private fun demotesProfile(result: ServerTtsResult.Unavailable): Boolean =
        result.reason != "rate_limited" && result.reason != "unauthorized"

    /** «Голоса/движка нет» — это надолго; сетевые и серверные сбои — ненадолго. */
    private fun isPermanent(result: ServerTtsResult.Unavailable): Boolean {
        val code = result.httpCode
        if (code != null && (code == 400 || code == 404 || code == 501)) return true
        return result.reason == "not_found" || result.reason == "invalid_voice"
    }

    companion object {
        /** Сетевой сбой: через 45 с снова пробуем естественный голос. */
        const val DEFAULT_TRANSIENT_COOLDOWN_MS: Long = 45_000L

        /** Голоса нет на сервере: не дёргаем его 10 минут (модель могут доустановить). */
        const val DEFAULT_PERMANENT_COOLDOWN_MS: Long = 600_000L
    }
}
