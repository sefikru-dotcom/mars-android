package ru.mars.mobile

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Экран авторизации ([MainActivity]) без сохранённого credential:
 * форма отображается, пустой пароль отклоняется ЛОКАЛЬНО (без сети).
 */
@RunWith(AndroidJUnit4::class)
class LoginScreenUiTest {

    @Before
    fun setUp() {
        UiTestHarness.resetState()
        UiTestHarness.radioOff()
    }

    @After
    fun tearDown() {
        UiTestHarness.radioOn()
    }

    @Test
    fun loginFormShown_andEmptyPasswordRejectedLocally() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("МАРС")).check(matches(isDisplayed()))
            onView(withText("Авторизация устройства")).check(matches(isDisplayed()))
            onView(withHint("Пароль Марса")).check(matches(isDisplayed()))
            onView(withText("Войти")).check(matches(isDisplayed()))

            // Пустой пароль — локальная валидация, сеть не участвует.
            onView(withText("Войти")).perform(click())
            onView(withText("Введите пароль")).check(matches(isDisplayed()))
            scenario.onActivity { /* activity жива, guard не сработал */ }
        }
    }
}
