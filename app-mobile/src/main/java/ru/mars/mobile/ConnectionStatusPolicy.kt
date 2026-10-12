package ru.mars.mobile

import ru.mars.network.model.HealthLevel

/**
 * Картирование вердикта health-пробы сервера в тексты строки состояния
 * на экране «Настройки».
 *
 * Зачем политика вынесена отдельно: раньше строка статуса собиралась прямо в
 * `SettingsActivity` вокруг пробы `http://127.0.0.1:8787/api/health`, то есть
 * проверялся localhost САМОГО ТЕЛЕФОНА, а не хост Марса (`MarsApi.BASE_URL`).
 * Порт 8787 на телефоне никем не слушается, поэтому вердикт был всегда
 * отрицательным независимо от реального состояния сервера. Теперь проба идёт
 * на боевой хост (см. [ConnectionProbePath]), а решение «что показать» —
 * чистая функция от [HealthLevel] и измеренной задержки, без Android и сети.
 *
 * Тексты намеренно не содержат запрещённых дизайн-системой глифов
 * (эмодзи, chevron, звезда, «x»-счётчик) — см. `theme-parity-audit.py`.
 */
object ConnectionStatusPolicy {

    /** Строка до получения первого вердикта. */
    const val PROBING = "Проверка соединения…"

    /** Относительный путь публичной пробы на хосте Марса (доступ без токена). */
    const val PROBE_PATH = "health"

    /**
     * Собирает текст строки состояния для [level].
     *
     * @param latencyMs измеренная задержка; для [HealthLevel.OFFLINE] не
     *   показывается, так как число мс отказа ничего не сообщает пользователю.
     */
    fun statusText(level: HealthLevel, latencyMs: Long): String = when (level) {
        HealthLevel.HEALTHY -> "Онлайн · Пинг $latencyMs мс"
        HealthLevel.DEGRADED -> "Сервер отвечает медленно · Пинг $latencyMs мс"
        HealthLevel.OFFLINE -> "Сервер недоступен"
    }

    /**
     * Нужен ли зелёный индикатор: только полностью здоровая проба. Медленный
     * ответ и отказ дают нейтрально-серую точку, чтобы не обещать стабильность,
     * которой нет.
     */
    fun isOnline(level: HealthLevel): Boolean = level == HealthLevel.HEALTHY
}
