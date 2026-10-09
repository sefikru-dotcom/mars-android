package ru.mars.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat

/**
 * Mars WebUI Design System Parity.
 * Pure Obsidian Dark Theme (#07080B) with Ruby Mars (#EF4444) accents,
 * subtle micro-borders (#1E2433), floating glass capsules and zero emojis.
 */
object MarsTheme {
    // Primary palette
    const val BG = 0xff07080b.toInt()
    const val SIDEBAR = 0xff0c0e14.toInt()
    const val SURFACE = 0xff10131b.toInt()
    const val SURFACE_SUBTLE = 0xff141822.toInt()
    const val SURFACE_CARD = 0xff161b28.toInt()
    
    // Borders
    const val BORDER = 0xff212634.toInt()
    const val BORDER_SUBTLE = 0xff171c26.toInt()
    const val BORDER_MUTED = 0xff283042.toInt()
    /** Thin hairline border for floating modal surfaces (dialogs, sheets). */
    const val BORDER_DIALOG = 0xff1e2433.toInt()
    
    // Accent Ruby
    const val ACCENT = 0xffef4444.toInt()
    const val ACCENT_HOVER = 0xfff87171.toInt()
    const val ACCENT_BG = 0x26ef4444.toInt() // 15% alpha
    /** 20%-alpha accent border for accent-tinted pills/buttons (WebUI `rgba(239,68,68,.2)`). */
    const val ACCENT_BORDER = 0x33ef4444.toInt()
    const val ACCENT_TEXT = 0xfffca5a5.toInt()

    /** Standard 20% white ripple for every interactive surface (buttons, icon buttons). */
    const val RIPPLE = 0x33ffffff.toInt()
    
    // Text colors
    const val TEXT = 0xfff1f5f9.toInt()
    const val TEXT_STRONG = 0xffffffff.toInt()
    const val TEXT_MUTED = 0xff8b95a5.toInt()
    const val TEXT_EM = 0xffcbd5e1.toInt()

    // Thoughts (blue subtle tone for reasoning accordion)
    const val THOUGHT_BG = 0x143b82f6.toInt()
    const val THOUGHT_BORDER = 0x333b82f6.toInt()
    
    // Chat & Code
    const val USER_BUBBLE = 0xff1c2436.toInt()
    const val USER_BORDER = 0xff2b374e.toInt()
    const val BOT_CARD = 0xff0f121a.toInt()
    const val BOT_BORDER = 0xff1b2230.toInt()
    const val CODE_BG = 0xff090b10.toInt()
    const val CODE_BORDER = 0xff1c2331.toInt()
    
    // Status colors
    const val STATUS_ONLINE = 0xff22c55e.toInt()
    const val STATUS_ERROR = 0xffef4444.toInt()
    const val STATUS_WARNING = 0xfff59e0b.toInt()
    const val STATUS_INFO = 0xff38bdf8.toInt()

    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /** Floating rounded pill / capsule (for composer, model selector, quick chips) */
    fun pill(context: Context, bg: Int, strokeColor: Int = BORDER, strokeWidthDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(context, 999).toFloat()
            if (strokeWidthDp > 0) setStroke(dp(context, strokeWidthDp), strokeColor)
        }
    }

    /** Rounded card with subtle border */
    fun card(context: Context, bg: Int, radiusDp: Int = 14, strokeColor: Int = BORDER, strokeWidthDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeWidthDp > 0) setStroke(dp(context, strokeWidthDp), strokeColor)
        }
    }

    /** Interactive button with ripple effect and rounded corners */
    fun button(context: Context, bg: Int, radiusDp: Int = 10, strokeColor: Int = 0): RippleDrawable {
        val content = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(context, 1), strokeColor)
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(context, radiusDp).toFloat()
        }
        val rippleColor = ColorStateList.valueOf(RIPPLE)
        return RippleDrawable(rippleColor, content, mask)
    }

    /** Circular action icon button with ripple */
    fun circleIconButton(
        context: Context,
        drawableRes: Int,
        contentDesc: String,
        bg: Int = SURFACE_SUBTLE,
        iconTint: Int = TEXT,
        sizeDp: Int = 40,
        strokeColor: Int = BORDER
    ): ImageButton {
        return ImageButton(context).apply {
            contentDescription = contentDesc
            val normal = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
                if (strokeColor != 0) setStroke(dp(context, 1), strokeColor)
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), normal, mask)
            setImageDrawable(ContextCompat.getDrawable(context, drawableRes))
            imageTintList = ColorStateList.valueOf(iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(context, 10)
            setPadding(pad, pad, pad, pad)
        }
    }

    /**
     * Small circular status dot, tinted with a STATUS_* color — replaces the `●` text glyph
     * (font-dependent metrics/color across OEM skins, same anti-pattern as emojis). The caller
     * sets the layout size (8 dp matches the WebUI status bullets).
     */
    fun statusDot(context: Context, color: Int): View {
        return View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }
}
