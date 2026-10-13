package ru.mars.mobile

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import ru.mars.network.cache.SessionOfflineCache
import ru.mars.network.cache.SharedPreferencesKeyValueStore
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage

/**
 * Детерминированный harness для instrumented UI-тестов (Espresso).
 *
 * Задача: проверить настоящую ручную View-вёрстку на эмуляторе БЕЗ боевого
 * сервера и без симуляции результата JVM-contract-тестом. Поэтому:
 *
 * 0. **Жёсткий guard (GAP-2)**: любое изменение состояния устройства возможно
 *    только на эмуляторе — каждый изменяющий метод этого harness начинается с
 *    [DeviceSafetyGuard.requireEmulator], а [EmulatorOnlyTestRunner] вообще не
 *    запускает тесты на физическом устройстве (fail-closed: неизвестное
 *    устройство считается физическим). Тесты собираются против изолированного
 *    варианта `uiTest` (applicationIdSuffix `.uitest`, см. build.gradle.kts):
 *    собственный пакет `ru.mars.mobile.uitest` со своим data-каталогом и своим
 *    UID-пространством AndroidKeyStore — боевой `ru.mars.mobile` на любом
 *    устройстве не заменяется и не затрагивается. Радио — глобальный эффект,
 *    поэтому guard обязателен даже при изоляции пакетом. Регрессионные
 *    проверки самого guard: [DeviceSafetyGuardTest].
 * 1. **Радио выключается** shell-командами эмулятора (`svc wifi/data disable`) —
 *    сетевые запросы падают быстро и одинаково, боевой `marscore.ru` не
 *    затрагивается вообще.
 * 2. **Авторизация — настоящий [AndroidKeyStoreCredentialStore]** с фейковым
 *    токеном: экраны проходят свой guard `store.read() != null` ровно тем же
 *    кодом, что и в проде; сервер фейковый токен отклонил бы, но на офлайн-UI
 *    это не влияет (репозитории на сбое возвращают пустой результат).
 * 3. **Данные — seeded [SessionOfflineCache]** (тот же класс и тот же
 *    SharedPreferences-файл, что использует прод-код): при пустом сетевом
 *    ответе `reconcile` показывает сохранённый снимок, поэтому список
 *    диалогов, parent/subagent-дерево и офлайн-копия транскрипта полностью
 *    детерминированы.
 *
 * Тесты НЕ отправляют сообщения: 401 на send-пути вызывает forced re-auth
 * ([AuthRejectionPolicy]) — это единственная ветка, требующая живого сервера.
 */
object UiTestHarness {

    val targetContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    // --- Фикстура списка диалогов: parent + subagent-ребёнок + независимая сессия ---
    const val PARENT_SESSION_ID = "s-parent"
    const val CHILD_SESSION_ID = "s-child"
    const val OTHER_SESSION_ID = "s-other"
    const val PARENT_TITLE = "Родительский диалог"
    const val CHILD_TITLE = "Дочерний воркер"
    const val OTHER_TITLE = "Отдельный чат"
    const val CACHED_MESSAGE_TEXT = "Привет из офлайн-кэша"
    const val OFFLINE_HINT = "Нет связи — показана сохранённая копия диалога"

    /**
     * Выполняет shell-команду от имени instrumentation (права shell) и дожидается вывода.
     * Guard: только эмулятор — shell-права позволяют изменить что угодно, поэтому
     * команда даже не отправляется на физическом устройстве.
     */
    fun shell(command: String) {
        DeviceSafetyGuard.requireEmulator("UiTestHarness.shell(\"$command\")")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand(command).use { pfd ->
            // Команда реально исполняется только при полном чтении потока вывода.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    fun radioOff() {
        DeviceSafetyGuard.requireEmulator("UiTestHarness.radioOff")
        shell("svc wifi disable")
        shell("svc data disable")
    }

    fun radioOn() {
        DeviceSafetyGuard.requireEmulator("UiTestHarness.radioOn")
        shell("svc wifi enable")
        shell("svc data enable")
    }

    /**
     * Полный сброс локального состояния приложения между тестами: credential,
     * офлайн-кэш, черновики, очередь офлайн-отправки и все персистентные выборы
     * (модель/reasoning/режим активности), чтобы каждый тест стартовал с
     * известного состояния.
     *
     * Guard: на физическом устройстве сброс отклоняется ДО первого обращения к
     * Keystore/prefs (изолированный пакет `ru.mars.mobile.uitest` — второй
     * уровень защиты: боевой пакет тесты не видят вообще).
     */
    fun resetState() {
        DeviceSafetyGuard.requireEmulator("UiTestHarness.resetState")
        AndroidKeyStoreCredentialStore(targetContext).clear()
        listOf(
            "mars-offline-cache",
            "mars-session-drafts",
            "mars-pending-messages",
            "mars-model-choice",
            "mars-reasoning-choice",
            "mars-activity-mode",
            "mars-workspace-choice",
            "mars-auto-compression",
            "mars-music-search-history",
        ).forEach { name ->
            targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    /** Сохраняет фейковый native-credential настоящим прод-хранилищем (только эмулятор). */
    fun seedCredential() {
        DeviceSafetyGuard.requireEmulator("UiTestHarness.seedCredential")
        AndroidKeyStoreCredentialStore(targetContext).save(
            ConsumedDeviceCredential(
                accessToken = "ui-test-token",
                tokenType = "Bearer",
                deviceId = "ui-test-device",
                scopes = listOf("chat"),
                expiresIn = 3600,
            )
        )
    }

    /**
     * Кладёт в прод-кэш ([SessionOfflineCache] поверх файла `mars-offline-cache`)
     * снимок списка диалогов и транскрипт родительской сессии. При выключенном
     * радио список диалогов и открытый диалог показывают ровно эти данные.
     */
    fun seedSessionCache() {
        DeviceSafetyGuard.requireEmulator("UiTestHarness.seedSessionCache")
        val cache = SessionOfflineCache(
            SharedPreferencesKeyValueStore(
                targetContext.getSharedPreferences("mars-offline-cache", Context.MODE_PRIVATE)
            )
        )
        val saved = cache.saveSessions(
            listOf(
                SessionItem(
                    id = PARENT_SESSION_ID,
                    title = PARENT_TITLE,
                    preview = "сводка родительского диалога",
                    createdAt = "2026-10-01T08:00:00",
                    updatedAt = "2026-10-01T10:00:00",
                    messageCount = 2,
                ),
                SessionItem(
                    id = CHILD_SESSION_ID,
                    title = CHILD_TITLE,
                    preview = "делегированная подзадача",
                    createdAt = "2026-10-01T08:30:00",
                    updatedAt = "2026-10-01T09:00:00",
                    messageCount = 1,
                    sourceTag = "subagent",
                    rawSource = "subagent",
                    sourceLabel = "Subagent",
                    readOnly = true,
                    parentSessionId = PARENT_SESSION_ID,
                ),
                SessionItem(
                    id = OTHER_SESSION_ID,
                    title = OTHER_TITLE,
                    preview = "независимая сессия",
                    createdAt = "2026-09-30T08:00:00",
                    updatedAt = "2026-09-30T10:00:00",
                    messageCount = 0,
                ),
            )
        )
        check(saved) { "SessionOfflineCache отклонил запись фикстуры" }
        val savedMessages = cache.saveMessages(
            PARENT_SESSION_ID,
            listOf(
                SessionMessage(id = "m1", role = "user", content = CACHED_MESSAGE_TEXT),
                SessionMessage(id = "m2", role = "assistant", content = "Ответ из сохранённой копии"),
            )
        )
        check(savedMessages) { "SessionOfflineCache отклонил запись транскрипта" }
    }

    /**
     * Ждёт (поллинг на main-idle между попытками), пока [matcher] не станет
     * отображаемым: асинхронная загрузка из кэша/сети не отслеживается
     * IdlingResource приложения.
     */
    fun waitUntilDisplayed(matcher: Matcher<View>, timeoutMs: Long = 20_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (t: Throwable) {
                lastError = t
                SystemClock.sleep(250)
            }
        }
        throw AssertionError("View $matcher не появилась за ${timeoutMs}ms", lastError)
    }
}
