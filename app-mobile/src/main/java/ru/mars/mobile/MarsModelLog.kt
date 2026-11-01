package ru.mars.mobile

import android.util.Log

/**
 * Единственная точка для строк `MarsModel` в logcat.
 *
 * Зачем отдельный объект, а не `Log.d` по месту: `LocalChatPresenter` проверяется
 * JVM-тестом, а `android.util.Log` в юнит-тестах — заглушка (`unitTests.isReturnDefaultValues`).
 * Тест подменяет [sink] и читает те же строки, что человек видит в `adb logcat -s MarsModel`,
 * поэтому «что уходит в запрос» проверяется без устройства.
 *
 * Секретов здесь нет и быть не может: пишется только id профиля, модель и адрес провайдера.
 */
internal object MarsModelLog {

    const val TAG = "MarsModel"

    /** Куда писать; `null` — в logcat. Ставится тестом, в приложении всегда `null`. */
    var sink: ((String) -> Unit)? = null

    fun log(message: String) {
        val hook = sink
        if (hook != null) {
            hook(message)
            return
        }
        Log.d(TAG, message)
    }
}
