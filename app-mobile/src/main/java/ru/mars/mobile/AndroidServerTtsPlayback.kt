package ru.mars.mobile

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Android-воспроизведение СЕРВЕРНОГО TTS-аудио: отдаёт байты плееру и изолирует здесь
 * все Android-зависимости серверного пути (движок [ServerTtsSpeechEngine] остаётся
 * чистым JVM-кодом и тестируется фейками).
 *
 * Движок — Media3 ExoPlayer, как и у остального аудио в приложении: пин
 * [MarsMusicPlayerNoMediaPlayerTest] запрещает android.media.MediaPlayer (на части
 * устройств он уводит AAC через compressed-offload ветку AudioFlinger и глохнет).
 * Серверный ответ уже лежит в памяти, поэтому играем локальный файл: байты пишутся во
 * временный файл cacheDir, ExoPlayer получает `file://`-источник.
 *
 * Потоки:
 * - запись файла — на отдельном однопоточном исполнителе, чтобы главный поток не
 *   блокировался на копировании байтов;
 * - все вызовы ExoPlayer — через главный Looper (контракт движка: один Looper на плеер).
 *
 * Отказы: синхронная невозможность принять реплику → `play` возвращает `false`;
 * асинхронный отказ (не открылся источник, нет кодека, битый поток) → однократный вызов
 * `onFailed`. Оба случая означают для движка одно: тот же текст уходит в системный TTS.
 * Устаревшие колбэки (после [stop]/[release] или новой реплики) не доставляются —
 * поэтому отменённая реплика не «догоняет» пользователя чужим голосом.
 *
 * Вывод помечен как речь ассистента (`USAGE_ASSISTANT` + `AUDIO_CONTENT_TYPE_SPEECH`): платформа
 * должна трактовать озвучку как голос самого приложения, а не как медиа — иначе микрофонный AEC
 * не гасит её, и перебивание слышит собственный динамик.
 */
@OptIn(UnstableApi::class)
class AndroidServerTtsPlayback(
    context: Context,
    /** Серверное аудио доиграло до конца: хост переводит hands-free-цикл в слушание. */
    private val onFinished: () -> Unit = {},
) : ServerTtsPlayback {

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Однопоточный писатель временных файлов (daemon: не держит процесс). */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    private var player: ExoPlayer? = null
    private var tempFile: File? = null

    /** Поколение реплики: любой поздний колбэк чужого поколения отбрасывается. */
    private var generation: Int = 0
    private var released: Boolean = false

    override fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean {
        if (released) return false
        val token = ++generation
        releasePlayer()
        val file = File(appContext.cacheDir, "$FILE_PREFIX${System.nanoTime()}$FILE_SUFFIX")
        try {
            writer.execute {
                val written = runCatching {
                    file.outputStream().use { it.write(bytes) }
                }.isSuccess
                main.post {
                    if (token != generation || released) {
                        file.delete()
                        return@post
                    }
                    if (!written) {
                        file.delete()
                        onFailed()
                        return@post
                    }
                    tempFile = file
                    startPlayer(file, token, onFailed)
                }
            }
        } catch (rejected: RejectedExecutionException) {
            // Хост уже уничтожен — играть нечем.
            file.delete()
            return false
        }
        return true
    }

    override fun stop() {
        // Новое поколение: колбэки текущей реплики станут устаревшими и молчат.
        generation++
        main.post { releasePlayer() }
        deleteTempFile()
    }

    override fun release() {
        released = true
        generation++
        runCatching { writer.shutdownNow() }
        main.post { releasePlayer() }
        deleteTempFile()
    }

    /** Строит и запускает плеер на главном потоке. Любой отказ уходит в [onFailed]. */
    private fun startPlayer(file: File, token: Int, onFailed: () -> Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (token != generation || released) return
                releasePlayer()
                file.delete()
                onFailed()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && token == generation) {
                    releasePlayer()
                    file.delete()
                    // Доиграло: только теперь цикл может снова слушать. Ошибки/стоп сюда не
                    // приходят — их обрабатывает движок (фолбэк или стоп контроллера).
                    runCatching { onFinished() }
                }
            }
        }
        val built = runCatching {
            ExoPlayer.Builder(appContext, DefaultMediaSourceFactory(appContext)).build().also {
                // USAGE_ASSISTANT, а не USAGE_MEDIA: платформа обязана видеть в этом выводе речь
                // ассистента. На медиа-маршруте она не считает звук «своим» для микрофона, и
                // эхоподавление захвата (VOICE_COMMUNICATION + AcousticEchoCanceler) не гасит
                // озвучку — при включённом перебивании цикл слышит сам себя. Content type —
                // речь, чтобы не включался музыкальный профиль обработки.
                it.setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_ASSISTANT)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    /* handleAudioFocus= */ false,
                )
                it.addListener(listener)
                it.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                it.playWhenReady = true
                it.prepare()
            }
        }.getOrNull()
        if (built == null) {
            file.delete()
            onFailed()
            return
        }
        player = built
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    private fun deleteTempFile() {
        runCatching { tempFile?.delete() }
        tempFile = null
    }

    companion object {
        private const val THREAD_NAME = "mars-server-tts-write"
        private const val FILE_PREFIX = "mars-tts-"
        private const val FILE_SUFFIX = ".audio"
    }
}
