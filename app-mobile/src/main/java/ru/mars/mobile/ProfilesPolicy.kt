package ru.mars.mobile

import ru.mars.network.model.MarsProfile
import ru.mars.network.model.ProfilesResponse

/**
 * Строка списка профилей (данные без разметки): что показать в заголовке строки,
 * какие пометки видимы и что положить в строку меты.
 */
data class ProfilesRow(
    val name: String,
    val title: String,
    val model: String? = null,
    val provider: String? = null,
    val enabledSkills: Int = 0,
    val totalSkills: Int = 0,
    val showActiveBadge: Boolean = false,
    val showDefaultBadge: Boolean = false,
    val showHiddenBadge: Boolean = false,
    val gatewayRunning: Boolean = false,
) {
    /**
     * Есть что показать в строке меты. Пусто → «Нет конфигурации» (паритет WebUI:
     * у профиля без `model`/`provider`/скиллов иной меты нет).
     */
    val hasConfiguration: Boolean get() = model != null || provider != null || totalSkills > 0
}

/**
 * Состояние экрана «Профили»: чистые данные и решения, без разметки и текстов.
 *
 * Тексты (заголовок, подписи пометок, пояснения) живут ТОЛЬКО в
 * `res/values/strings.xml` — политика называет состояние и решение, экран берёт
 * соответствующий ресурс. Так строка не дублируется в двух местах, а решения
 * остаются проверяемыми на JVM.
 */
sealed interface ProfilesScreenState {
    /** Первая загрузка: показывать пока нечего. */
    data object Loading : ProfilesScreenState

    /** Нет device-credential: загрузка даже не начинается, нужна пара с сервером. */
    data object NoAuth : ProfilesScreenState

    /** Сеть/сервер недоступны или тело не разобралось, а прошлого списка нет. */
    data object Failure : ProfilesScreenState

    /** Ответ получен, список профилей пуст. */
    data object Empty : ProfilesScreenState

    /**
     * Список профилей.
     *
     * [activeName] — активный профиль по версии сервера (null, если сервер его
     * не назвал); [singleProfileMode] — режим одного профиля, в нём список
     * вырожден и переключение бессмысленно; [refreshing] — идёт обновление
     * поверх уже показанного списка; [stale] — обновление не удалось, на экране
     * остался прошлый список.
     */
    data class Body(
        val rows: List<ProfilesRow>,
        val activeName: String? = null,
        val singleProfileMode: Boolean = false,
        val refreshing: Boolean = false,
        val stale: Boolean = false,
    ) : ProfilesScreenState
}

/**
 * Чистая логика экрана «Профили» (JVM, без Android): разбор состояния из ответа
 * `GET /api/profiles`, состав строк, определение активного профиля и пометок.
 *
 * Экран ТОЛЬКО ЧТЕНИЕ. Политика не содержит ни одного решения о переключении
 * профиля: серверный `switch` меняет `HERMES_HOME` живого процесса WebUI, то
 * есть состояние чужих сессий, и с мобильного клиента такая мутация не
 * выполняется вовсе — выбор активного профиля остаётся за владельцем в WebUI.
 *
 * Правила:
 * - нет credential → [ProfilesScreenState.NoAuth];
 * - загрузка поверх пустого экрана → [ProfilesScreenState.Loading], поверх
 *   готового списка → тот же список с `refreshing = true` (список не мигает);
 * - ответ не пришёл → `stale = true` при прошлом списке, иначе
 *   [ProfilesScreenState.Failure];
 * - пустой список → [ProfilesScreenState.Empty];
 * - иначе [ProfilesScreenState.Body].
 */
object ProfilesPolicy {

    /**
     * Активный профиль: поле `active` ответа, иначе строка с `is_active = true`,
     * иначе [fallback] — имя из `GET /api/profile/active`.
     *
     * Фолбэк принимается, только если он называет профиль из этого же ответа:
     * чужое/устаревшее имя не должно помечать строку активной.
     */
    fun activeName(response: ProfilesResponse?, fallback: String? = null): String? {
        val direct = response?.active?.takeIf(String::isNotBlank)
            ?: response?.profiles?.firstOrNull { it.isActive }?.name
        if (direct != null) return direct
        val candidate = fallback?.takeIf(String::isNotBlank) ?: return null
        return candidate.takeIf { name -> response?.profiles?.any { it.name == name } == true }
    }

    /**
     * Состояние экрана из ответа репозитория.
     *
     * @param response ответ `GET /api/profiles`; null — сеть/HTTP/разбор не удались.
     * @param loading запрос в полёте прямо сейчас.
     * @param authenticated есть ли device-credential.
     * @param previous прошлое состояние — чтобы обновление не теряло показанный список.
     * @param fallbackActive имя активного профиля из `GET /api/profile/active`
     *   (используется, только когда список его не назвал).
     */
    fun resolve(
        response: ProfilesResponse?,
        loading: Boolean,
        authenticated: Boolean = true,
        previous: ProfilesScreenState? = null,
        fallbackActive: String? = null,
    ): ProfilesScreenState {
        if (!authenticated) return ProfilesScreenState.NoAuth
        val shown = previous as? ProfilesScreenState.Body
        if (loading) {
            return shown?.copy(refreshing = true, stale = false) ?: ProfilesScreenState.Loading
        }
        if (response == null) {
            return shown?.copy(refreshing = false, stale = true) ?: ProfilesScreenState.Failure
        }
        if (response.profiles.isEmpty()) return ProfilesScreenState.Empty
        return ProfilesScreenState.Body(
            rows = rows(response, fallbackActive),
            activeName = activeName(response, fallbackActive),
            singleProfileMode = response.singleProfileMode,
        )
    }

    /** Строки списка в серверном порядке; активный помечен по [ProfilesPolicy.activeName]. */
    fun rows(response: ProfilesResponse?, fallbackActive: String? = null): List<ProfilesRow> {
        val active = activeName(response, fallbackActive)
        return response?.profiles.orEmpty().map { profile -> row(profile, isActive = active != null && profile.name == active) }
    }

    /** Строка одного профиля. */
    fun row(profile: MarsProfile, isActive: Boolean): ProfilesRow = ProfilesRow(
        name = profile.name,
        title = profile.label,
        model = profile.shortModel,
        provider = profile.provider,
        enabledSkills = profile.enabledSkills,
        totalSkills = profile.totalSkills,
        showActiveBadge = isActive,
        showDefaultBadge = profile.isDefault,
        showHiddenBadge = !profile.visible,
        gatewayRunning = profile.gatewayRunning,
    )

    /**
     * Строка меты профиля в порядке WebUI: `модель · провайдер · N / M навыков`.
     * Подпись счётчика навыков форматирует экран (плюрализация — ресурс), но
     * РЕШЕНИЕ о её наличии и порядке принимается здесь.
     */
    fun metaParts(row: ProfilesRow, skillsLabel: (enabled: Int, total: Int) -> String): List<String> =
        buildList {
            row.model?.let(::add)
            row.provider?.let(::add)
            if (row.totalSkills > 0) add(skillsLabel(row.enabledSkills, row.totalSkills))
        }
}
