package ru.mars.mobile

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.mars.mobile.UiTestHarness.waitUntilDisplayed

/**
 * UI-тесты уровня «диалог» ([ChatActivity]): запуск без сервера, шторка
 * разделов и переход в «Диалоги», системный Back ([NavBackPolicy]), поиск по
 * транскрипту, чипы модели/reasoning в деградации без каталога.
 */
@RunWith(AndroidJUnit4::class)
class ChatActivityUiTest {

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

    @Test
    fun launch_showsHeaderComposerAndModelChip_reasoningChipHiddenWithoutServer() {
        ActivityScenario.launch(ChatActivity::class.java).use {
            waitUntilDisplayed(withText("МАРС"))
            // Композер на месте (минималистичный: поле + отправка, без shortcuts-секций).
            onView(withHint(ComposerLayoutPresenter.HINT)).check(matches(isDisplayed()))
            // Чип модели виден всегда; reasoning-чип без серверной лестницы скрыт.
            onView(withContentDescription("Модель")).check(matches(isDisplayed()))
            onView(withContentDescription("Уровень размышлений")).check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun drawer_chatSection_navigatesToDialogsLevel() {
        ActivityScenario.launch(ChatActivity::class.java).use {
            waitUntilDisplayed(withText("МАРС"))
            onView(withContentDescription("Разделы")).perform(click())
            waitUntilDisplayed(withContentDescription("Музыка"))
            // Клик по разделу «Чат» на уровне диалога → корень раздела (список диалогов).
            onView(withContentDescription("Чат")).perform(click())
            waitUntilDisplayed(withText("Диалоги"))
        }
    }

    @Test
    fun back_fromChat_goesToSessionList() {
        ActivityScenario.launch(ChatActivity::class.java).use {
            waitUntilDisplayed(withText("МАРС"))
            // NavBackPolicy: CHAT без оверлеев → GO_TO_SESSION_LIST.
            pressBack()
            waitUntilDisplayed(withText("Диалоги"))
        }
    }

    @Test
    fun transcriptSearch_opensViaHeaderButton_andBackClosesItStayingInChat() {
        ActivityScenario.launch(ChatActivity::class.java).use {
            waitUntilDisplayed(withText("МАРС"))
            // Описание «Поиск по диалогу» носят ДВА элемента: кнопка в шапке и поле ввода
            // в раскрытой панели. Кликаем именно по кнопке (ImageButton), иначе Espresso
            // валится на AmbiguousViewMatcher. Поле проверяется ниже по подсказке.
            onView(allOf(
                withClassName(equalTo(android.widget.ImageButton::class.java.name)),
                withContentDescription("Поиск по диалогу"),
            )).perform(click())
            waitUntilDisplayed(withHint("Искать в диалоге…"))

            // Back закрывает поиск первым (CLOSE_SEARCH), пользователь остаётся в диалоге.
            // Клавиатуру закрываем ДО Back: пока поле в фокусе, первый Back съедает IME,
            // панель остаётся открытой, и проверка ниже падает не на поведении экрана.
            closeSoftKeyboard()
            pressBack()
            onView(withHint("Искать в диалоге…")).check(matches(not(isDisplayed())))
            onView(withText("МАРС")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun modelChip_withoutServerCatalog_degradesWithStatusMessage() {
        ActivityScenario.launch(ChatActivity::class.java).use {
            waitUntilDisplayed(withText("МАРС"))
            onView(withContentDescription("Модель")).perform(click())
            // Радио выключено: каталог не загружается, статус сообщает о деградации,
            // activity остаётся живой (никакого принудительного reauth на пассивных путях).
            waitUntilDisplayed(withText("Каталог моделей недоступен"), timeoutMs = 20_000)
        }
    }
}
