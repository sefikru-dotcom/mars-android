package ru.mars.tv.profiles

import ru.mars.network.model.MarsProfile
import ru.mars.network.model.ProfilesResponse

/**
 * Чистая презентационная политика TV-экрана «Профили» (без Android и I/O —
 * JVM-тестируемая, паритет [ru.mars.tv.spaces.TvSpacesPolicy]).
 *
 * Экран СТРОГО READ-ONLY: здесь нет ни одного решения о создании, переименовании,
 * удалении или переключении профиля. Серверный switch меняет `HERMES_HOME` живого
 * процесса WebUI — это состояние чужих сессий, и TV-клиент его не трогает: он
 * показывает каталог `GET /api/profiles` и открывает чат выбранного профиля
 * ([chatExtra] + [TvProfilesRouting], сама навигация — дело Activity).
 *
 * Правила отображения повторяют мобильный `ProfilesPolicy` (паритет WebUI):
 * - подпись строки — `display_name`, иначе техническое `name` ([MarsProfile.label]);
 * - модель показывается хвостом без провайдерского префикса ([MarsProfile.shortModel]);
 * - активный профиль — поле `active` ответа, иначе строка с `is_active = true`,
 *   иначе fallback из `GET /api/profile/active`, и только если он называет профиль
 *   из этого же ответа;
 * - скрытый профиль (`visible = false`) остаётся в списке, но помечен бейджем
 *   «Скрыт» и в чат не ведёт: сервер исключает его из чата.
 *
 * Все решения — здесь; [TvProfilesActivity] только рисует.
 */
object TvProfilesPolicy {

    /** [restoreSelection] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /** Сервер вернул пустой каталог (валидное состояние, не ошибка). */
    const val EMPTY_LIST = "На сервере пока нет профилей"

    /** Бейдж профиля, который сервер считает активным. */
    const val ACTIVE_BADGE = "Активный"

    /** Бейдж профиля по умолчанию (`is_default`). */
    const val DEFAULT_BADGE = "По умолчанию"

    /** Бейдж скрытого профиля (`visible = false`): строка есть, чата нет. */
    const val HIDDEN_BADGE = "Скрыт"

    /** Заголовок-префикс каталога. */
    const val CATALOG_PREFIX = "Профилей: "

    /** Разделитель подписей (принятая грамматика: `a · b`, не декоративный глиф). */
    const val DETAIL_SEPARATOR = " · "

    /** Мета профиля без модели, провайдера и навыков (паритет WebUI). */
    const val NO_CONFIGURATION = "Нет конфигурации"

    /** Максимальный возраст кэшированного каталога — профили меняются редко (24 ч). */
    const val CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Одна отрисованная карточка каталога. */
    data class ProfileRow(
        /**
         * Стабильный ключ фокуса между перерисовками: имя профиля уникально на
         * сервере и не меняется при обновлении каталога.
         */
        val key: String,
        val name: String,
        /** Подпись строки: `display_name`, иначе техническое имя. */
        val title: String,
        /** Модель без провайдерского префикса; null — не задана. */
        val model: String? = null,
        val provider: String? = null,
        val enabledSkills: Int = 0,
        val totalSkills: Int = 0,
        val active: Boolean = false,
        val default: Boolean = false,
        val hidden: Boolean = false,
        val path: String? = null,
    ) {
        /** Есть что показать в строке меты (иначе [NO_CONFIGURATION]). */
        val hasConfiguration: Boolean get() = model != null || provider != null || totalSkills > 0
    }

    /**
     * Активный профиль: поле `active` ответа, иначе строка с `is_active = true`,
     * иначе [fallback] — имя из `GET /api/profile/active`.
     *
     * Фолбэк принимается, только если он называет профиль из этого же ответа:
     * чужое или устаревшее имя не должно помечать строку активной.
     */
    fun activeName(response: ProfilesResponse?, fallback: String? = null): String? {
        val direct = response?.active?.takeIf(String::isNotBlank)
            ?: response?.profiles?.firstOrNull { it.isActive }?.name
        if (direct != null) return direct
        val candidate = fallback?.takeIf(String::isNotBlank) ?: return null
        return candidate.takeIf { name -> response?.profiles?.any { it.name == name } == true }
    }

    /**
     * Карточки каталога в серверном порядке.
     *
     * Записи без имени отбрасываются (профиль без имени нельзя ни показать, ни
     * сопоставить с активным), дубликаты имён схлопываются первым вхождением —
     * ключ фокуса обязан быть уникальным, иначе D-pad прыгает между карточками.
     */
    fun rows(response: ProfilesResponse?, fallbackActive: String? = null): List<ProfileRow> {
        val active = activeName(response, fallbackActive)
        return response?.profiles.orEmpty()
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }
            .map { profile -> row(profile, isActive = active != null && profile.name == active) }
    }

    /** Карточка одного профиля. */
    fun row(profile: MarsProfile, isActive: Boolean): ProfileRow = ProfileRow(
        key = profile.name,
        name = profile.name,
        title = profile.label,
        model = profile.shortModel,
        provider = profile.provider?.takeIf(String::isNotBlank),
        enabledSkills = profile.enabledSkills.coerceAtLeast(0),
        totalSkills = profile.totalSkills.coerceAtLeast(0),
        active = isActive,
        default = profile.isDefault,
        hidden = !profile.visible,
        path = profile.path?.takeIf(String::isNotBlank),
    )

    /** Live-регион: «Профилей: N» (+ плашку источника собирает Activity). */
    fun summaryLine(response: ProfilesResponse?): String = "$CATALOG_PREFIX${rows(response).size}"

    /**
     * Строка статуса: плашка источника данных (LIVE/CACHED) перед счётчиком
     * каталога; без плашки — только счётчик.
     */
    fun statusLine(summary: String, badge: String?): String =
        if (badge.isNullOrBlank()) summary else "$badge$DETAIL_SEPARATOR$summary"

    /**
     * Пустое состояние: `null` когда карточки есть; [EMPTY_LIST] для пустого
     * каталога. Отличить «пусто» от «сервер недоступен» нельзя: репозиторий
     * fail-soft (`null` при сбое) и его озвучивает контроллер ошибкой, а пустой
     * список — валидный ответ.
     */
    fun emptyLine(response: ProfilesResponse?): String? =
        if (rows(response).isEmpty()) EMPTY_LIST else null

    /** Бейджи карточки в порядке важности: активный, по умолчанию, скрытый. */
    fun badges(row: ProfileRow): List<String> = buildList {
        if (row.active) add(ACTIVE_BADGE)
        if (row.default) add(DEFAULT_BADGE)
        if (row.hidden) add(HIDDEN_BADGE)
    }

    /**
     * Строка меты в порядке WebUI: `модель · провайдер · N из M навыков`.
     * Пустая конфигурация — [NO_CONFIGURATION], а не пустая строка: карточка без
     * меты читается как сломанная.
     */
    fun metaLine(row: ProfileRow): String {
        val parts = buildList {
            row.model?.let(::add)
            row.provider?.let(::add)
            if (row.totalSkills > 0) add(skillsLabel(row.enabledSkills, row.totalSkills))
        }
        return if (parts.isEmpty()) NO_CONFIGURATION else parts.joinToString(DETAIL_SEPARATOR)
    }

    /** Счётчик навыков профиля: «3 из 12 навыков» (существительное согласуется с общим числом). */
    fun skillsLabel(enabled: Int, total: Int): String = "$enabled из $total ${skillsWord(total)}"

    /**
     * Родительный падеж слова «навык» для оборота «N из M»: существительное
     * согласуется с M — «из 21 навыка», «из 12 навыков», «из 1 навыка».
     */
    fun skillsWord(count: Int): String {
        val abs = if (count < 0) -count else count
        return if (abs % 100 !in 11..14 && abs % 10 == 1) "навыка" else "навыков"
    }

    /**
     * TalkBack-подпись карточки: подпись, бейджи и мета одним перечислением.
     * Карточка — единственная цель пульта, поэтому её описание обязано нести всё,
     * что видно на экране (иначе озвучка теряет состояние профиля).
     */
    fun cardDescription(row: ProfileRow): String =
        (listOf(row.title) + badges(row) + metaLine(row)).joinToString(", ")

    /**
     * Карточка ведёт в чат профиля. Скрытый профиль сервер исключает из чата,
     * поэтому его карточка остаётся только для просмотра.
     */
    fun canOpenChat(row: ProfileRow): Boolean = !row.hidden

    /**
     * Имя профиля для extra интента чата; `null` — карточка в чат не ведёт
     * (скрытый профиль). Навигацию выполняет Activity, политика только решает.
     */
    fun chatExtra(row: ProfileRow): String? = row.name.takeIf { canOpenChat(row) }

    /**
     * Восстановление фокуса после перерисовки (автообновление): прошлый индекс,
     * зажатый в новый список — края ДЕРЖАТ, переноса нет; [NO_SELECTION] для
     * пустого списка.
     */
    fun restoreSelection(previous: Int, size: Int): Int =
        if (size <= 0) NO_SELECTION else previous.coerceIn(0, size - 1)
}
