package ru.mars.mobile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.mars.network.model.ConsumedDeviceCredential

/**
 * Единственная точка, через которую экран чата создаёт первую сессию.
 *
 * Зачем гейт поверх [FirstSessionFlow]: мятку запускают ДВА независимых жеста — первый
 * ход и «скрепка» в новом чате (файл грузится на `POST /api/upload`, которому нужен
 * `session_id`). Если оба увидят `currentSessionId == null`, каждый создаст свою сессию,
 * и вложение уедет в диалог, который первый ход уже не продолжит — сирота на сервере.
 * Поэтому здесь:
 *  - одна критическая секция на экран ([Mutex]) — вторая мятка ждёт первую;
 *  - id перечитывается ВНУТРИ секции ([boundSessionId]), а не до неё: ожидающий видит
 *    результат уже состоявшейся мятки и просто переиспользует её;
 *  - привязка ([bind]) выполняется тоже внутри секции — иначе окно между «сессия создана»
 *    и «currentSessionId выставлен» снова пропустило бы второго создателя.
 *
 * Отмена и исключения безопасны: `withLock` отпускает мьютекс, поэтому уход с экрана или
 * упавшая мятка не оставляют залипший лок — следующий жест просто попробует снова.
 *
 * Чистый Kotlin без Android: хостовое состояние приходит лямбдами, поэтому сериализация
 * и переиспользование проверяются unit-тестами без устройства.
 */
class FirstSessionGate(private val flow: FirstSessionFlow) {
    private val mutex = Mutex()

    /**
     * Гарантирует сессию: возвращает [EnsureSessionResult.Ready] с привязанным id или
     * [EnsureSessionResult.Failed] с причиной (её показывает хост, а не глотает).
     *
     * [requestedSessionId] — явный адресат хода (сообщение очереди своего диалога): он
     * главнее привязки, иначе повтор уехал бы не в тот диалог. `null` означает «решает
     * экран»: подхватывается уже привязанная сессия, и только при её отсутствии идёт мятка.
     */
    suspend fun ensure(
        requestedSessionId: String?,
        credential: ConsumedDeviceCredential,
        boundSessionId: () -> String?,
        bind: (String) -> Unit,
    ): EnsureSessionResult = mutex.withLock {
        val target = requestedSessionId?.takeIf { it.isNotBlank() } ?: boundSessionId()
        when (val result = flow.ensureSession(target, credential)) {
            is EnsureSessionResult.Ready -> {
                // Привязка под тем же локом, что и мятка: следующий ожидающий уже увидит id.
                bind(result.sessionId)
                result
            }
            // Провал ничего не привязывает: id не существует, повтор должен быть возможен.
            is EnsureSessionResult.Failed -> result
        }
    }
}
