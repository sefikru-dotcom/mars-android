package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import ru.mars.network.model.ProfilesResponse

/**
 * Презентер экрана «Профили»: оркестрирует чтение `GET /api/profiles` (порт
 * [fetch] — в app это `ProfilesRepository().getProfiles(baseUrl, credential)`) и
 * публикует чистое состояние [ProfilesScreenState].
 *
 * Дополнительный порт [fetchActiveName] — `GET /api/profile/active`: он
 * запрашивается ТОЛЬКО когда список профилей не назвал активного, и результат
 * принимается лишь при совпадении с именем из списка (решение — [ProfilesPolicy.activeName]).
 *
 * Контракт состояний и offline-safe поведение — в [ProfilesPolicy.resolve]:
 * обновление поверх готового списка не мигает, неудачное обновление оставляет
 * прошлый список (`stale`).
 *
 * Конкурентные загрузки (быстрые «Обновить»): каждый [load] увеличивает
 * [loadGeneration] и публикует результат, только если поколение осталось
 * последним — устаревший ответ не затирает свежий.
 *
 * Отмена корутины экрана ([CancellationException]) пробрасывается дальше, а не
 * превращается в ошибку: отмена — не сетевой сбой.
 *
 * Экран ТОЛЬКО ЧТЕНИЕ: презентер не порождает ни одной мутации — в том числе не
 * переключает активный профиль (это меняет состояние живого процесса WebUI и
 * остаётся за владельцем).
 */
class ProfilesPresenter(
    private val hasCredential: () -> Boolean,
    private val fetch: suspend () -> ProfilesResponse?,
    private val fetchActiveName: suspend () -> String? = { null },
) {

    var state: ProfilesScreenState = ProfilesPolicy.resolve(response = null, loading = true)
        private set

    private var onChanged: (ProfilesScreenState) -> Unit = {}

    /** Поколение загрузки: результат устаревшего конкурентного [load] отбрасывается. */
    private var loadGeneration = 0

    /** Подписка на состояния; сразу реплеит текущее. Одна подписка на экран. */
    fun subscribe(listener: (ProfilesScreenState) -> Unit) {
        onChanged = listener
        listener(state)
    }

    /** Загрузка/перезагрузка списка профилей. */
    suspend fun load() {
        if (!hasCredential()) {
            publish(ProfilesPolicy.resolve(response = null, loading = false, authenticated = false, previous = state))
            return
        }
        val generation = ++loadGeneration
        publish(ProfilesPolicy.resolve(response = null, loading = true, previous = state))

        val response = try {
            fetch()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        if (generation != loadGeneration) return

        val fallback = if (response != null && ProfilesPolicy.activeName(response) == null) {
            try {
                fetchActiveName()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (generation != loadGeneration) return

        publish(
            ProfilesPolicy.resolve(
                response = response,
                loading = false,
                previous = state,
                fallbackActive = fallback,
            )
        )
    }

    private fun publish(next: ProfilesScreenState) {
        state = next
        onChanged(next)
    }
}
