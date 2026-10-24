package ru.mars.mobile

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.mars.mobile.UiTestHarness.waitUntilDisplayed

/**
 * UI-тесты уровня «Диалоги» ([ChatSessionsActivity]) на seeded офлайн-кэше:
 * запуск, шторка разделов, поиск, parent/subagent-дерево, кнопка раскрытия,
 * клик карточки и системный Back ([NavBackPolicy]).
 */
@RunWith(AndroidJUnit4::class)
class ChatSessionsUiTest {

    @Before
    fun setUp() {
        UiTestHarness.resetState()
        UiTestHarness.radioOff()
        UiTestHarness.grantRuntimePermissions()
        UiTestHarness.seedCredential()
        UiTestHarness.seedSessionCache()
    }

    @After
    fun tearDown() {
        UiTestHarness.radioOn()
    }

    /** Запускает экран и ждёт, пока офлайн-снимок списка отрисуется. */
    private fun launchAndWaitForList(): ActivityScenario<ChatSessionsActivity> {
        val scenario = ActivityScenario.launch(ChatSessionsActivity::class.java)
        waitUntilDisplayed(withText(UiTestHarness.PARENT_TITLE))
        return scenario
    }

    @Test
    fun launch_showsHeaderAndCachedSessionsWithoutCollapsedChildren() {
        launchAndWaitForList().use {
            onView(withText("Диалоги")).check(matches(isDisplayed()))
            onView(withText(UiTestHarness.OTHER_TITLE)).check(matches(isDisplayed()))
            // Subagent-ребёнок скрыт под свёрнутым родителем.
            onView(withText(UiTestHarness.CHILD_TITLE)).check(doesNotExist())
            // Кнопка раскрытия воркеров у родителя.
            onView(withContentDescription("Развернуть воркеры")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun drawer_opensViaMenuButton_andClosesViaCloseButton() {
        launchAndWaitForList().use {
            onView(withContentDescription("Разделы")).perform(click())
            // Панель разделов: заголовок + строки AppSectionsBar (contentDescription = title).
            waitUntilDisplayed(withText("Разделы"))
            onView(withContentDescription("Музыка")).check(matches(isDisplayed()))
            onView(withContentDescription("Настройки")).check(matches(isDisplayed()))

            onView(withContentDescription("Закрыть панель")).perform(click())
            onView(withContentDescription("Музыка")).check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun back_fromSessionList_opensDrawer_thenSecondBackExits() {
        launchAndWaitForList().use { scenario ->
            // NavBackPolicy: SESSION_LIST без оверлеев → OPEN_DRAWER.
            pressBack()
            waitUntilDisplayed(withContentDescription("Музыка"))
            // Back из показанных разделов на корневом уровне → SYSTEM_EXIT.
            pressBack()
            val deadline = System.currentTimeMillis() + 10_000
            var destroyed = false
            while (System.currentTimeMillis() < deadline && !destroyed) {
                destroyed = scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED
                if (!destroyed) SystemClock.sleep(200)
            }
            org.junit.Assert.assertTrue("Экран не завершился после Back из разделов", destroyed)
        }
    }

    @Test
    fun search_filtersList_andShowsEmptyStateOnNoMatch() {
        launchAndWaitForList().use {
            onView(withHint("Поиск диалогов…")).perform(
                typeText(UiTestHarness.OTHER_TITLE), closeSoftKeyboard()
            )
            waitUntilDisplayed(withText(UiTestHarness.OTHER_TITLE))
            onView(withText(UiTestHarness.PARENT_TITLE)).check(doesNotExist())

            onView(withHint("Поиск диалогов…")).perform(
                replaceText("zzz-такого-диалога-нет"), closeSoftKeyboard()
            )
            waitUntilDisplayed(withText("Ничего не найдено"))
        }
    }

    @Test
    fun expandWorkersButton_revealsSubagentChild_andHidesItAgain() {
        launchAndWaitForList().use {
            onView(withContentDescription("Развернуть воркеры")).perform(click())
            waitUntilDisplayed(withText(UiTestHarness.CHILD_TITLE))
            // Строка ребёнка помечена источником (sourceLabel + read-only подпись).
            onView(withText(org.hamcrest.Matchers.containsString("Subagent")))
                .check(matches(isDisplayed()))
            // Тумблер переключился на «Свернуть воркеры».
            onView(withContentDescription("Свернуть воркеры")).check(matches(isDisplayed()))

            onView(withContentDescription("Свернуть воркеры")).perform(click())
            // Рендер списка пересоздаёт строки — свёрнутый ребёнок исчезает из иерархии.
            onView(withText(UiTestHarness.CHILD_TITLE)).check(doesNotExist())
        }
    }

    @Test
    fun cardClick_opensChatWithOfflineTranscriptCopy() {
        launchAndWaitForList().use {
            // Реальный переход: клик по заголовку карточки → ChatActivity c EXTRA_OPEN_SESSION_ID;
            // радио выключено → детали не загрузились → показана сохранённая копия транскрипта.
            onView(withText(UiTestHarness.PARENT_TITLE)).perform(click())
            waitUntilDisplayed(withText("МАРС"))
            waitUntilDisplayed(withText(UiTestHarness.CACHED_MESSAGE_TEXT))
            onView(withText(org.hamcrest.Matchers.containsString("сохранённая копия диалога")))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun expandWorkersButton_doesNotOpenSession() {
        launchAndWaitForList().use {
            // Intents включены ПОСЛЕ запуска экрана, чтобы стартовый intent не учитывался.
            Intents.init()
            try {
                onView(withContentDescription("Развернуть воркеры")).perform(click())
                waitUntilDisplayed(withText(UiTestHarness.CHILD_TITLE))
                Intents.assertNoUnverifiedIntents()
            } finally {
                Intents.release()
            }
        }
    }
}
