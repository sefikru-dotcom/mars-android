package ru.mars.tv.profiles

/**
 * Контракт перехода «Профили» → чат (чистый объект, без Android — JVM-тесты).
 *
 * Экран профилей только читает каталог: единственное действие карточки —
 * открыть чат соответствующего профиля ([ru.mars.tv.TvChatActivity]). Имя
 * профиля уезжает в extra интента, а чат передаёт его в
 * `POST /api/session/new` как `profile` при создании НОВОЙ сессии — сервер уже
 * принимает этот аргумент (см. `SessionRepository.createSession`). Никакого
 * переключения профиля по дороге не происходит: уже открытый чат сохраняет
 * свою привязку, потому что смена профиля живого процесса WebUI — мутация
 * чужого состояния, а не операция TV-пульта.
 */
object TvProfilesRouting {

    /** Extra интента [ru.mars.tv.TvChatActivity]: имя профиля, чей чат открывается. */
    const val EXTRA_PROFILE = "ru.mars.tv.profiles.profile_name"

    /**
     * Имя профиля для extra: обрезанное, непустое; иначе `null` — extra не
     * кладётся вовсе (чат откроется в профиле по умолчанию, как обычно).
     */
    fun profileExtra(profile: String?): String? = profile?.trim()?.takeIf(String::isNotBlank)
}
