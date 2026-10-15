package ru.mars.tv

import android.content.Context
import android.provider.Settings
import java.util.WeakHashMap

/**
 * Тонкий Android-адаптер системной настройки «уменьшить движение» для TV: сам решает
 * только «откуда взять масштаб и сколько раз его читать». Вся логика — в чистом
 * [TvMotionPolicy]; здесь ровно одно действие — прочитать
 * Settings.Global.ANIMATOR_DURATION_SCALE и скормить его политике.
 *
 * Правило чтения: один раз на экран. Экраны, которым важна явность ([TvChatActivity],
 * [TvMusicActivity], [DashboardActivity]), держат флаг в поле и передают его в политику
 * сами; этот объект обслуживает остальных потребителей ([TvFocusLift] по умолчанию) —
 * фокусный листенер создаётся на каждой фокусируемой View, и обращение к провайдеру
 * настроек на каждую из них было бы лишней работой в главном потоке (подстановка истории
 * в чате создаёт сотни View за кадр).
 *
 * Кеш слабый по ключу (Activity может умереть), значение — обычный Boolean, ссылки на
 * Activity он не удерживает. Доступ всегда с главного потока.
 */
object TvMotion {

    private val reducedMotionByContext = WeakHashMap<Context, Boolean>()

    /** Значение Settings.Global.ANIMATOR_DURATION_SCALE; 1f, если настройки нет. */
    fun animatorDurationScale(context: Context): Float = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )

    /** «Уменьшить движение» по системной настройке [context]; читается один раз на экран. */
    fun reducedMotionFor(context: Context): Boolean = synchronized(reducedMotionByContext) {
        reducedMotionByContext.getOrPut(context) {
            TvMotionPolicy.isReducedMotion(animatorDurationScale(context))
        }
    }
}
