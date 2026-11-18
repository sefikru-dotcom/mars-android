package ru.mars.mobile.tts

import kotlinx.coroutines.CancellationException
import ru.mars.mobile.ServerTtsClient
import ru.mars.network.repository.ServerTtsResult

/**
 * Резерв Piper для ПОТОКОВОЙ озвучки серверного чата: облако основное, локальная модель —
 * второй канал, тишина — только когда не смогли ни там, ни там.
 *
 * ## Что здесь чинится
 * Потоковая озвучка (`StreamingSpeechController` → `SpeechQueue`) берёт у клиента ГОТОВЫЕ
 * байты и сама держит порядок фраз и плеер. Пока клиент был только серверным, любой отказ
 * `api/tts` (`401`, `429` от лимитера «2 с на клиента», таймаут, битый поток) означал
 * пропущенную фразу: очередь шла дальше, и посреди ответа появлялась дырка. При этом
 * единственная офлайн-модель лежала на диске и в этот момент не использовалась.
 *
 * ## Почему декоратор, а не правка очереди
 * Очередь не должна знать, ОТКУДА звук: её контракт — «получил байты, отдал плееру в
 * порядке постановки». Резерв, встроенный в очередь, пришлось бы проверять вместе с
 * порядком, отменой и повтором `429`; декоратор же подменяет ровно один шаг (`synthesize`)
 * и оставляет все инварианты очереди нетронутыми — включая поколение: отменённая фраза
 * уходит в отмену корутины, а не в «поздний звук».
 *
 * ## Почему WAV, а не `port.speak`
 * `OfflineTtsPort.speak` играет сам (fire-and-forget). Если позвать его из клиента очереди,
 * Piper зазвучит В ОБХОД плеера очереди: следующая фраза начнёт играть по сигналу
 * «доиграло» от ExoPlayer, которого не было, — две реплики наложатся, порядок потеряется, а
 * `onPhraseStarted` (эталон эха для перебивания) не узнает, что именно звучит. Поэтому
 * резерв синтезирует WAV ([OfflineTtsPort.synthesizeToWav]) и отдаёт его очереди как
 * обычное аудио с `Content-Type: audio/wav`; играет его тот же плеер, что и облако.
 *
 * ## Гарантии
 * - ПОРЯДОК: клиент ничего не играет и не переупорядочивает — только возвращает байты;
 * - ОТМЕНА: отмена корутины синтеза пробрасывается ([CancellationException]) и прекращает
 *   локальную генерацию, а не «доигрывает в пустоту»;
 * - ИСКЛЮЧЕНИЯ: ни серверный, ни локальный путь не бросают наружу — отказ превращается в
 *   [ServerTtsResult.Unavailable], иначе одна битая фраза уронила бы весь ход;
 * - ИНДИКАЦИЯ: каждый исход, о котором человеку нужно знать, уходит колбэком
 *   ([onLocalFallback], [onLocalFailure]). Молчаливая подмена голоса запрещена так же, как
 *   и молчаливая тишина.
 *
 * @param server основной канал (облако).
 * @param piper офлайн-резерв; `null` — модели на устройстве нет, тогда резерва не существует
 *   и фраза честно уходит в отказ (хост объявит его человеку).
 * @param onLocalFallback облако отказало, фразу прочитает Piper. Аргумент — причина отказа
 *   облака (для лога/диагностики, человеку она не показывается кодом).
 * @param onLocalFailure ни облако, ни Piper не дали звука — фраза пропущена.
 */
class PiperFallbackTtsClient(
    private val server: ServerTtsClient,
    private val piper: OfflineTtsPort?,
    private val onLocalFallback: (reason: String) -> Unit = {},
    private val onLocalFailure: (reason: String) -> Unit = {},
) : ServerTtsClient {

    /**
     * Синтез одной фразы: облако → локальная модель → отказ.
     *
     * Порядок жёсткий и без «повторной попытки облака»: повтор `429` живёт в очереди
     * ([ru.mars.mobile.SpeechRetryPolicy]) и срабатывает только если резерв не дал звука.
     * Иначе лимитер сервера складывался бы с паузой повтора в дырку посреди ответа, хотя
     * модель на устройстве может прочитать фразу прямо сейчас.
     */
    override suspend fun synthesize(text: String): ServerTtsResult {
        val primary = try {
            server.synthesize(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Транспортный порт обязан не бросать; страховка — тот же резерв, а не падение.
            ServerTtsResult.Unavailable(REASON_TRANSPORT)
        }
        val audio = primary as? ServerTtsResult.Audio
        if (audio != null && audio.bytes.isNotEmpty()) return audio

        val failure = primary as? ServerTtsResult.Unavailable
            ?: ServerTtsResult.Unavailable(REASON_EMPTY_AUDIO)
        val port = piper
        if (port == null || !port.isReady()) {
            // Резерва физически нет (модель не скачана / движок не поднялся): врать
            // «сейчас зазвучит» нельзя, поэтому отказ идёт наверх, а хост объявляет его.
            notify { onLocalFailure(failure.reason) }
            return failure
        }

        val wav = try {
            port.synthesizeToWav(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            null
        }
        if (wav != null && wav.isNotEmpty()) {
            notify { onLocalFallback(failure.reason) }
            return ServerTtsResult.Audio(wav, CONTENT_TYPE_WAV)
        }
        notify { onLocalFailure(failure.reason) }
        // Причина облака сохраняется: если она повторяемая (`429`/`5xx`), очередь имеет право
        // на один повтор — к тому моменту сервер может ожить.
        return failure
    }

    /** Колбэк хоста не должен ломать синтез: его падение — это не отказ звука. */
    private fun notify(block: () -> Unit) {
        runCatching(block)
    }

    companion object {
        /** Content-Type резерва: плеер выбирает по нему расширение временного файла. */
        const val CONTENT_TYPE_WAV: String = "audio/wav"

        /** Причина отказа, когда серверный порт бросил исключение вместо ответа. */
        const val REASON_TRANSPORT: String = "transport"

        /** Сервер ответил «аудио», но тело пустое — для очереди это отказ, а не звук. */
        const val REASON_EMPTY_AUDIO: String = "empty_audio"
    }
}
