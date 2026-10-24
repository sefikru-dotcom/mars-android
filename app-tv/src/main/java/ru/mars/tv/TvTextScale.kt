package ru.mars.tv

import android.util.TypedValue
import android.widget.TextView

/**
 * Кегль для программного рендера — только из шкалы `dimens.xml` (`tv_text_*`).
 *
 * Зачем: до волны tv-scale код-рендер подбирал размеры на глаз (14/15/17/18/22/24),
 * и шкала из layout-ов не совпадала со шкалой вьюх, собранных в Kotlin. Теперь оба
 * пути берут одни токены, поэтому «подобранный» кегль нельзя вписать незаметно.
 *
 * `getDimension` возвращает уже масштабированные пиксели (sp * fontScale * density),
 * а [TextView.setTextSize] с `COMPLEX_UNIT_PX` их не масштабирует повторно —
 * системный множитель шрифта учитывается ровно один раз.
 */
internal fun TextView.tvTextSize(dimenRes: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(dimenRes))
}
