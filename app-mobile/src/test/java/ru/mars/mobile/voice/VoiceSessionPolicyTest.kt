package ru.mars.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ворота голосовой сессии: когда микрофон вообще можно открыть.
 *
 * Почему это тестируется отдельно от Android: ошибка здесь не видна на глаз — телефон
 * просто тихо садится за сутки, а владелец не понимает причины. Политика чистая,
 * поэтому все правила проверяются на JVM без устройства.
 *
 * Проверяется ПРИОРИТЕТ правил, а не только каждое по отдельности: если «звонок»
 * перестанет перебивать «низкий заряд», микрофон откроется во время разговора по
 * телефону — это заметно и раздражает, но тест на одно правило такого не поймает.
 */
class VoiceSessionPolicyTest {

    private fun policy() = VoiceSessionPolicy()

    private fun allow(env: VoiceSessionEnvironment, now: Long = 0L): VoiceSessionDecision.Allow? =
        policy().gate(env, now) as? VoiceSessionDecision.Allow

    private fun deny(env: VoiceSessionEnvironment, now: Long = 0L): VoiceSessionDecision.Deny? =
        policy().gate(env, now) as? VoiceSessionDecision.Deny

    @Test
    fun healthyEnvironmentAllowsSession() {
        val decision = allow(VoiceSessionEnvironment())
        assertTrue("при здоровом окружении микрофон разрешён", decision != null)
    }

    @Test
    fun missingMicPermissionBeatsBattery() {
        // Разрешение важнее заряда: без него микрофон не откроется физически,
        // и человеку нужно показать именно причину «нет разрешения», а не «заряд».
        val decision = deny(
            VoiceSessionEnvironment(
                micPermissionGranted = false,
                batteryPercent = 5,
                screenOn = false,
                keyguardLocked = true,
            ),
        )
        assertEquals(VoiceSessionRejection.MIC_PERMISSION_MISSING, decision?.reason)
    }

    @Test
    fun missingModelIsReportedSeparately() {
        // «Модель не скачана» и «нет разрешения» — разные действия для человека:
        // в одном случае качать 21 МБ, в другом идти в настройки.
        val decision = deny(VoiceSessionEnvironment(modelInstalled = false))
        assertEquals(VoiceSessionRejection.MODEL_MISSING, decision?.reason)
    }

    @Test
    fun activeCallBlocksMicrophone() {
        val decision = deny(VoiceSessionEnvironment(callActive = true))
        assertEquals(VoiceSessionRejection.CALL_ACTIVE, decision?.reason)
        assertTrue("после звонка имеет смысл спросить снова", decision!!.retryAfterMs > 0)
    }

    @Test
    fun otherCaptureBlocksMicrophone() {
        val decision = deny(VoiceSessionEnvironment(otherCaptureActive = true))
        assertEquals(VoiceSessionRejection.MIC_BUSY, decision?.reason)
    }

    @Test
    fun doNotDisturbBlocksMicrophone() {
        val decision = deny(VoiceSessionEnvironment(doNotDisturb = true))
        assertEquals(VoiceSessionRejection.DO_NOT_DISTURB, decision?.reason)
    }

    @Test
    fun lowBatteryBlocksWhenNotCharging() {
        val decision = deny(VoiceSessionEnvironment(batteryPercent = 10, charging = false))
        assertEquals(VoiceSessionRejection.BATTERY_TOO_LOW, decision?.reason)
        assertTrue("пауза должна быть длинной: это батарея", decision!!.retryAfterMs >= 30_000)
    }

    @Test
    fun lowBatteryIsFineWhenCharging() {
        // На зарядке заряд не аргумент: смысл правила — не посадить телефон,
        // а от сети он не садится.
        val decision = allow(VoiceSessionEnvironment(batteryPercent = 10, charging = true))
        assertTrue(decision != null)
    }

    @Test
    fun unknownBatteryIsTreatedAsEnough() {
        // Провайдер заряда может не ответить (-1). Блокировать из-за неизвестного
        // значения значит запретить фичу на части прошивок без причины.
        val decision = allow(VoiceSessionEnvironment(batteryPercent = -1))
        assertTrue(decision != null)
    }

    @Test
    fun screenOffWithoutForegroundServiceIsDenied() {
        val decision = deny(
            VoiceSessionEnvironment(
                screenOn = false,
                keyguardLocked = true,
                foregroundServiceRunning = false,
            ),
        )
        assertEquals(VoiceSessionRejection.NO_FOREGROUND_SERVICE, decision?.reason)
    }

    @Test
    fun screenOffWithForegroundServiceIsAllowed() {
        // Главный сценарий: экран погашен, сервис держит микрофон — это и есть
        // «разговор при заблокированном экране».
        val decision = allow(
            VoiceSessionEnvironment(
                screenOn = false,
                keyguardLocked = true,
                foregroundServiceRunning = true,
            ),
        )
        assertTrue("фоновый сервис снимает требование к экрану", decision != null)
    }

    @Test
    fun powerSaveInBackgroundBlocks() {
        val decision = deny(
            VoiceSessionEnvironment(
                powerSaveMode = true,
                screenOn = false,
                keyguardLocked = true,
                foregroundServiceRunning = true,
            ),
        )
        assertEquals(VoiceSessionRejection.POWER_SAVE, decision?.reason)
    }

    @Test
    fun gateIsPureAndRepeatable() {
        // Один и тот же вход обязан давать один и тот же выход: политика не хранит
        // скрытого состояния между вызовами, иначе «почему-то не работает» не отладить.
        val env = VoiceSessionEnvironment(batteryPercent = 80)
        val policy = policy()
        val first = policy.gate(env, 1_000L)
        val second = policy.gate(env, 2_000L)
        assertEquals(first::class, second::class)
    }

    @Test
    fun denialNeverClaimsOpenMic() {
        // Отказ не должен оставлять микрофон открытым: иначе кнопка «не работает»,
        // а запись идёт — худший из возможных исходов.
        val decision = deny(VoiceSessionEnvironment(callActive = true))
        assertFalse("при отказе микрофон обязан быть закрыт", decision!!.keepsMicOpen)
    }
}
