package ru.mars.mobile

import ru.mars.network.local.LocalModelInfo
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.LocalProviderStore

/**
 * Запись выбранной модели в активный профиль — один путь для чата и настроек.
 *
 * Почему отдельным объектом, а не двумя строками в экране: выбор модели писали ДВА
 * места, и оба брали профиль, снятый при открытии списка. Пока список открыт, активный
 * профиль мог быть пересоздан (экран «Провайдер» сохраняет профиль с новым `id` и тут же
 * делает его активным) — и запись уходила в профиль, которого `state().active` уже не
 * видит. Снаружи это выглядит ровно как «выбрал модель — отвечает прежняя»: пикер
 * показывает новую, а чат читает старую из другого профиля.
 *
 * Поэтому профиль-цель берётся из хранилища В МОМЕНТ выбора, а после записи
 * проверяется, что активный профиль — именно записанный. Логика здесь, а не в
 * Activity, чтобы её проверял JVM-тест без Android.
 */
internal object LocalModelPick {

    /**
     * Применить выбор модели к активному профилю.
     *
     * [snapshot] — профиль, снятый при открытии пикера: используется только если
     * активного профиля нет вовсе (первый запуск, хранилище пустое).
     *
     * Возвращает записанный профиль. `reasoningEffort` сбрасывается в `null`, если новая
     * модель `reasoning_effort` не принимает: иначе в запрос уходил бы уровень, которого
     * модель не понимает.
     */
    fun apply(
        store: LocalProviderStore,
        snapshot: LocalProviderProfile,
        info: LocalModelInfo,
    ): LocalProviderProfile {
        val target = store.state().active ?: snapshot
        val effort = target.reasoningEffort.takeIf { info.supportsReasoningEffort }
        val updated = target.copy(
            model = info.id,
            contextWindow = info.contextWindow,
            reasoningEffort = effort,
        )
        store.saveProfile(updated)
        // Активный профиль обязан указывать на записанное: если `activeId` пуст или
        // смотрит в другой профиль, чат читал бы оттуда — то есть старую модель.
        if (store.state().activeId != updated.id) store.setActive(updated.id)
        return updated
    }
}
