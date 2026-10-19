package ru.mars.mobile

import ru.mars.network.model.SessionItem

/** Решение [SessionListRenderPolicy]: перерисовывать список диалогов или переиспользовать текущий. */
sealed interface SessionListRenderDecision {
    /** Входное состояние совпало с отрисованным — снимать и заново строить карточки незачем. */
    data object Reuse : SessionListRenderDecision

    /** Состояние изменилось — список строится заново. */
    data object Rebuild : SessionListRenderDecision
}

/**
 * Всё, от чего зависит картинка списка диалогов ([ChatSessionsActivity.renderSessionList]):
 * строка фильтра, множество раскрытых родителей, плоский список сессий и серверные
 * совпадения отдельной секцией.
 *
 * Дерево ([SessionTreePolicy]) и секция серверного поиска ([SessionSearchPolicy]) —
 * ПРОИЗВОДНЫЕ от этих четырёх полей, поэтому в состояние они не входят: считать
 * отпечаток по уже построенному дереву значило бы сначала построить дерево, то есть
 * выполнить половину той работы, которую политика призвана отменить.
 */
data class SessionListRenderState(
    val filterQuery: String,
    val expandedParents: Set<String>,
    val sessions: List<SessionItem>,
    val remoteMatches: List<SessionItem>,
)

/**
 * Политика «перерисовывать или нет» для списка диалогов.
 *
 * Зачем: экран держит список как `LinearLayout` и на каждый рендер снимает ВСЕ
 * карточки (`removeAllViews`) и строит их заново — inflate-подобная сборка строк,
 * `GradientDrawable` на карточку, кнопки, обработчики. Обновлений у экрана много
 * (возврат на корень раздела, pin/archive/move/rename/delete, серверный поиск), и
 * большинство из них НЕ меняет то, что видно: сервер вернул тот же список, поиск
 * вернул те же совпадения, повторный триггер пришёл с тем же состоянием. Строить в
 * этом случае нечего — отпечаток входного состояния совпадает с отрисованным.
 *
 * Контракт:
 * - **Отпечаток** — строка, собранная ОДНИМ проходом по списку (без вложенных
 *   `filter`/`map`-цепочек): сравнивать её дешевле на порядки, чем пересобирать
 *   карточки, поэтому даже «дорогой» по меркам CPU проход окупается.
 * - **Порядок строк значим.** Список сортируется сервером (свежесть, закреплённые
 *   выше), поэтому перестановка тех же строк — это другая картинка, а не та же.
 * - **Порядок `expandedParents` НЕ значим.** Раскрытие/сворачивание в разном порядке
 *   даёт одинаковое множество и одинаковую картинку, поэтому id сортируются перед
 *   записью в отпечаток.
 * - **В отпечаток входит всё, что печатается на карточке**, а не только
 *   `id`+`title`+родитель: иначе строка с изменившимся числом сообщений, меткой
 *   времени, флагом pin/archive, статусом исполнителя или ожидающим attention
 *   осталась бы визуально устаревшей до следующего «настоящего» изменения. Время
 *   (`nowMillis`) в отпечаток НЕ входит: относительная метка «5 мин назад» — не
 *   событие состояния, и включать её значило бы отменять переиспользование всегда.
 */
class SessionListRenderPolicy {

    private var lastFingerprint: String? = null

    /**
     * Можно ли переиспользовать уже построенный список. При [SessionListRenderDecision.Rebuild]
     * отпечаток запоминается сразу: следующий вызов с тем же состоянием даст `Reuse`.
     */
    fun decide(state: SessionListRenderState): SessionListRenderDecision {
        val fingerprint = fingerprint(state)
        if (fingerprint == lastFingerprint) return SessionListRenderDecision.Reuse
        lastFingerprint = fingerprint
        return SessionListRenderDecision.Rebuild
    }

    /**
     * Забыть отрисованное состояние. Нужен, когда view-дерево списка заменено
     * (пересоздание экрана): старые карточки больше не существуют, и первый же
     * рендер обязан построить список, даже если входное состояние не изменилось.
     */
    fun reset() {
        lastFingerprint = null
    }

    companion object {
        private const val FIELD = '\u0001'
        private const val ROW = '\u0002'
        private const val SECTION = '\u0003'
        private const val ABSENT = '~'

        /**
         * Отпечаток входного состояния — один проход, один `StringBuilder`.
         *
         * Поля пишутся с длиной (`len:value`), а не через разделитель: заголовок
         * диалога — произвольный пользовательский текст, и разделитель внутри него
         * склеил бы два разных состояния в один отпечаток (пропущенная перерисовка).
         */
        fun fingerprint(state: SessionListRenderState): String {
            val sb = StringBuilder(64 + state.sessions.size * 48)
            appendField(sb, state.filterQuery)
            // Множество раскрытых: порядок вставки не влияет на картинку.
            state.expandedParents.sorted().forEach { appendField(sb, it) }
            sb.append(SECTION)
            for (i in state.sessions.indices) appendRow(sb, state.sessions[i])
            sb.append(SECTION)
            for (i in state.remoteMatches.indices) appendRow(sb, state.remoteMatches[i])
            return sb.toString()
        }

        /**
         * Строка списка: то, что реально попадает в карточку. Проход по полям без
         * промежуточных коллекций — этот код греется на каждом обновлении.
         */
        private fun appendRow(sb: StringBuilder, session: SessionItem) {
            appendField(sb, session.id)
            appendField(sb, session.title)
            appendField(sb, session.parentSessionId)
            appendField(sb, session.preview)
            appendField(sb, session.updatedAt)
            appendField(sb, session.createdAt)
            appendNumber(sb, session.messageCount)
            sb.append(if (session.pinned) '1' else '0')
            sb.append(if (session.archived) '1' else '0')
            // isSubagent считается из этих двух полей — их и пишем (без геттера).
            appendField(sb, session.sourceTag)
            appendField(sb, session.rawSource)
            val work = session.workerStatus
            if (work == null) {
                sb.append(ABSENT)
            } else {
                appendField(sb, work.status)
                appendField(sb, work.goal)
                appendNumber(sb, work.durationSeconds)
                appendField(sb, work.lastAction)
            }
            val turn = session.activeTurn
            appendField(sb, turn.activeStreamId)
            sb.append(if (turn.isStreaming) '1' else '0')
            sb.append(if (turn.hasPendingUserMessage) '1' else '0')
            val attention = turn.attention
            if (attention == null) {
                sb.append(ABSENT)
            } else {
                appendField(sb, attention.kind)
                appendNumber(sb, attention.count)
                appendField(sb, attention.severity)
            }
            sb.append(ROW)
        }

        /** `len:value` — null и пустая строка различимы (это разные карточки). */
        private fun appendField(sb: StringBuilder, value: String?) {
            if (value == null) {
                sb.append(ABSENT)
            } else {
                sb.append(value.length).append(':').append(value)
            }
            sb.append(FIELD)
        }

        private fun appendNumber(sb: StringBuilder, value: Int?) {
            if (value == null) sb.append(ABSENT) else sb.append(value)
            sb.append(FIELD)
        }

        private fun appendNumber(sb: StringBuilder, value: Double?) {
            if (value == null) sb.append(ABSENT) else sb.append(value)
            sb.append(FIELD)
        }
    }
}
