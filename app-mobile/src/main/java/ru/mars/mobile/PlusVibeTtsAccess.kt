package ru.mars.mobile

import okhttp3.OkHttpClient
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.LocalProviderState
import ru.mars.network.local.LocalProviderStore
import ru.mars.network.repository.PaidTtsClient
import ru.mars.network.repository.PlusVibeMediaTtsClient
import ru.mars.network.repository.ServerTtsResult
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Какой профиль провайдера считать «настроенным PlusVibe» для платного синтеза.
 *
 * ## Зачем это отдельная политика
 * Платный голос (`plusvibe-alice`, `plusvibe-onyx`) уходит НЕ на сервер Марса, а прямо
 * провайдеру: `POST {root}/api/media/generate` с ключом в заголовке `Authorization`.
 * Значит адрес и ключ обязаны принадлежать одному и тому же профилю PlusVibe. Раньше оба
 * брались у АКТИВНОГО профиля (`state().active`), и этого хватало ровно до второго
 * провайдера: человек настраивал Yeta (или любой другой OpenAI-совместимый адрес), тот
 * становился активным — и платный голос либо терял ключ PlusVibe (молчаливый резерв),
 * либо отправлял запрос на чужой хост.
 *
 * ## Почему по хосту, а не по id или названию
 * `id` профиля — случайный UUID (`LocalProviderSettingsActivity.save`), а `title` человек
 * пишет руками. Единственный устойчивый признак — адрес: профиль PlusVibe заведён с
 * `https://plusvibeapi.ru/v1`. Поэтому узнаём провайдера по РЕГИСТРИРУЕМОМУ домену
 * (`plusvibeapi.ru`, `api.plusvibe.ai`), а не по подстроке во всём URL: `https://yeta.example/plusvibe`
 * и `https://plusvibe.yeta.example` — чужие хосты, и ключ туда не поедет.
 *
 * Класс чистый (никакого `android.*`), поэтому правила проверяются JVM-тестом.
 */
internal object PlusVibeTtsProfile {

    /**
     * Профиля PlusVibe в настройках нет.
     *
     * Отдельная причина, а не общий `no_api_key`: человеку и диагностике важно различать
     * «провайдер не заведён» и «ключ не сохранён» — лечатся они в разных местах экрана.
     */
    const val REASON_NO_PROFILE: String = "no_plusvibe_profile"

    /** Профиль PlusVibe есть, но ключ к нему не сохранён. */
    const val REASON_NO_KEY: String = "no_api_key"

    /** Метка домена провайдера: `plusvibeapi.ru`, `plusvibe.ai`, `api.plusvibe.ai`. */
    private const val PLUSVIBE_LABEL: String = "plusvibe"

    /**
     * Хост адреса провайдера: без схемы, порта, логина и пути.
     *
     * Схема не обязательна: человек вписывает `plusvibeapi.ru/v1`, а хранилище приводит
     * адрес к `https://…` уже после. Пустая строка и мусор дают `null`, а не «первый
     * попавшийся хост»: неизвестный адрес не должен случайно совпасть с PlusVibe.
     */
    fun hostOf(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val afterScheme = withScheme.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        // `user:pass@host:port` — логин и порт к домену отношения не имеют.
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) {
            // IPv6 в скобках: `[::1]:8080`.
            hostPort.substringBefore(']') + "]"
        } else {
            hostPort.substringBefore(':')
        }
        return host.trim().trimEnd('.').lowercase(Locale.ROOT).ifBlank { null }
    }

    /**
     * Домен принадлежит PlusVibe.
     *
     * Смотрится РЕГИСТРИРУЕМАЯ часть (предпоследняя метка), поэтому поддомены провайдера
     * (`api.plusvibe.ai`) узнаются, а поддомен чужого домена (`plusvibe.yeta.example`) и
     * чужой хост с путём `/plusvibe` — нет.
     */
    fun isPlusVibeHost(host: String): Boolean {
        val labels = host.trim().trimEnd('.').lowercase(Locale.ROOT)
            .split('.')
            .filter { it.isNotBlank() }
        if (labels.isEmpty()) return false
        val domain = if (labels.size >= 2) labels[labels.size - 2] else labels[0]
        return domain.startsWith(PLUSVIBE_LABEL)
    }

    /** Адрес провайдера принадлежит PlusVibe. */
    fun isPlusVibe(baseUrl: String): Boolean = hostOf(baseUrl)?.let(::isPlusVibeHost) == true

    /**
     * Настроенный профиль PlusVibe — НЕЗАВИСИМО от того, какой провайдер активен.
     *
     * Порядок выбора: активный профиль, если он сам PlusVibe (это текущий выбор человека,
     * и при двух профилях PlusVibe он важнее порядка в списке), иначе первый профиль
     * PlusVibe в порядке хранения (порядок создания — он же порядок на экране настроек).
     *
     * @return профиль либо `null` — PlusVibe не настроен вовсе. `null` здесь означает
     *   «платного голоса нет», а не «возьми первый попавшийся адрес».
     */
    fun select(state: LocalProviderState): LocalProviderProfile? {
        val active = state.active
        if (active != null && isPlusVibe(active.baseUrl)) return active
        return state.profiles.firstOrNull { isPlusVibe(it.baseUrl) }
    }

    /**
     * Человеческая причина отказа платного голоса — короткая фраза для плашки в интерфейсе.
     *
     * ## Зачем отдельная функция, а не код ошибки на экране
     * Причина подмены обязана доходить до человека, но `no_plusvibe_profile` — это язык
     * логов. Человеку нужно понять, ЧТО случилось и где это чинится: «провайдер не заведён»
     * лечится в настройках провайдера, «нет ключа» — вставкой ключа, «timeout/transport» —
     * ожиданием или проверкой сети. Три разных действия не должны выглядеть одной строкой.
     *
     * ## Почему чистая функция
     * Текст — тоже контракт: он должен быть одинаковым в обоих чатах, а проверяться — без
     * телефона. Ни `android.*`, ни состояния, поэтому правила покрыты JVM-тестом.
     *
     * @param reason код отказа платного клиента ([REASON_NO_PROFILE], [REASON_NO_KEY],
     *   `timeout`, `transport`, `deadline`, …) либо `null` — причина неизвестна.
     * @return короткая русская фраза; неизвестный код возвращается КАК ЕСТЬ — выдумывать
     *   объяснение опаснее, чем показать код: по нему хотя бы можно искать в логах.
     */
    fun humanReason(reason: String?): String {
        // Причина не пришла вовсе — общая фраза, но всё равно с указанием, что делать дальше:
        // молчание о подмене запрещено в любом случае.
        if (reason == null) return PAID_UNKNOWN
        return when (reason) {
            REASON_NO_PROFILE -> "Профиль PlusVibe не настроен — добавьте провайдера в настройках"
            REASON_NO_KEY -> "Ключ PlusVibe не сохранён — вставьте его в настройках провайдера"
            PlusVibeMediaTtsClient.REASON_TIMEOUT ->
                "Провайдер PlusVibe не ответил вовремя — читаю другим голосом"
            PlusVibeMediaTtsClient.REASON_TRANSPORT ->
                "Нет связи с провайдером PlusVibe — читаю другим голосом"
            RepositoryServerTtsClient.REASON_PAID_DEADLINE ->
                "Голос PlusVibe не уложился во время — читаю другим голосом"
            RepositoryServerTtsClient.REASON_NO_PAID_CLIENT ->
                "Платный синтез не подключён — читаю другим голосом"
            // Пустой код — та же неизвестность, что и отсутствие кода: пустая плашка хуже
            // общей фразы, потому что выглядит как «ничего не случилось».
            else -> reason.ifBlank { PAID_UNKNOWN }
        }
    }

    /** Причина неизвестна — общая фраза, но всё равно с указанием, что делать дальше. */
    private const val PAID_UNKNOWN: String =
        "Платный голос не прозвучал — читаю другим голосом"
}

/**
 * Доступ к платному синтезу: пара «адрес + ключ» ОДНОГО профиля PlusVibe.
 *
 * ## Почему одна пара, а не два независимых провайдера
 * `PlusVibeMediaTtsClient` спрашивает адрес и ключ двумя разными лямбдами. Если каждая
 * сама читает `state()`, между вызовами настройки могут измениться — и ключ одного
 * профиля уедет на адрес другого. Здесь пара собирается ОДНИМ чтением ([bind]) и
 * присваивается клиенту одним действием, поэтому «ключ на чужом хосте» невозможен
 * структурно, а не по договорённости.
 *
 * Ключ читается у профиля PlusVibe (`store.apiKey(profile.id)`), а не у активного: у
 * чужого профиля свой ключ, и он не имеет отношения к провайдеру PlusVibe.
 */
internal class PlusVibeTtsAccess(private val store: LocalProviderStore) {

    /**
     * Снимок одного профиля: адрес и ключ принадлежат ему оба.
     *
     * `toString` маскирует ключ: снимок может попасть в лог или в текст исключения, а
     * секрет не должен покидать хранилище ни при каких обстоятельствах.
     */
    data class Binding(
        val profileId: String,
        val baseUrl: String,
        val apiKey: String?,
    ) {
        override fun toString(): String =
            "Binding(profileId=$profileId, baseUrl=$baseUrl, apiKey=${if (apiKey == null) "null" else "***"})"
    }

    /** Профиль PlusVibe из настроек; `null` — провайдер не заведён. */
    fun profile(): LocalProviderProfile? =
        runCatching { PlusVibeTtsProfile.select(store.state()) }.getOrNull()

    /**
     * Адрес и ключ одного профиля PlusVibe.
     *
     * @return `null` — профиля нет. Профиль без ключа возвращается с `apiKey == null`:
     *   вызывающий обязан сказать «ключа нет», а не искать ключ в другом профиле.
     */
    fun bind(): Binding? {
        val profile = profile() ?: return null
        val key = runCatching { store.apiKey(profile.id) }.getOrNull()?.takeIf { it.isNotBlank() }
        return Binding(profileId = profile.id, baseUrl = profile.baseUrl, apiKey = key)
    }

    /** Платный клиент, привязанный к профилю PlusVibe (см. [PlusVibeTtsClient]). */
    fun client(httpClient: OkHttpClient = PlusVibeMediaTtsClient.defaultHttpClient()): PaidTtsClient =
        PlusVibeTtsClient(this, httpClient)
}

/**
 * Платный синтез PlusVibe, привязанный к настроенному профилю.
 *
 * Отличия от «голого» [PlusVibeMediaTtsClient] в проводке:
 * - адрес и ключ берутся у профиля PlusVibe, а не у активного провайдера;
 * - нет профиля или ключа — сразу [ServerTtsResult.Unavailable] с ЯВНОЙ причиной
 *   ([PlusVibeTtsProfile.REASON_NO_PROFILE] / [PlusVibeTtsProfile.REASON_NO_KEY]), без
 *   единого сетевого вызова: молчаливая отправка на чужой хост и «пустой» запрос
 *   одинаково недопустимы.
 *
 * @param newTransport точка подмены транспорта для тестов: получает провайдеры адреса и
 *   ключа, уже связанные с одним снимком профиля.
 */
internal class PlusVibeTtsClient(
    private val access: PlusVibeTtsAccess,
    httpClient: OkHttpClient = PlusVibeMediaTtsClient.defaultHttpClient(),
    private val newTransport: (baseUrl: () -> String?, apiKey: () -> String?) -> PaidTtsClient =
        { baseUrl, apiKey ->
            PlusVibeMediaTtsClient(
                baseUrlProvider = baseUrl,
                apiKeyProvider = apiKey,
                httpClient = httpClient,
            )
        },
) : PaidTtsClient {

    /**
     * Снимок профиля текущей реплики.
     *
     * `AtomicReference` — потому что синтез идёт из корутин IO, а предсинтез следующей
     * фразы может стартовать параллельно. Оба читателя (адрес и ключ) видят ОДИН снимок:
     * даже если настройки поменяются между фразами, ключ и хост останутся из одного профиля.
     */
    private val bound = AtomicReference<PlusVibeTtsAccess.Binding?>(null)

    private val transport: PaidTtsClient by lazy {
        newTransport({ bound.get()?.baseUrl }, { bound.get()?.apiKey })
    }

    override suspend fun synthesize(
        text: String,
        voice: String,
        model: String,
        speed: Float,
        instructions: String?,
        voiceId: String?,
    ): ServerTtsResult {
        val current = access.bind()
            ?: return ServerTtsResult.Unavailable(PlusVibeTtsProfile.REASON_NO_PROFILE)
        if (current.apiKey == null) {
            return ServerTtsResult.Unavailable(PlusVibeTtsProfile.REASON_NO_KEY)
        }
        bound.set(current)
        // `voiceId` (голос из библиотеки) обязан доехать до транспорта: именно он, а не имя
        // `voice`, попадает в `opts.voice_id` и выбирает русский тембр.
        return transport.synthesize(text, voice, model, speed, instructions, voiceId)
    }
}
