package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки виджета «Музыка Марса» на РЕАЛЬНЫХ production-файлах.
 *
 * Инструментального слоя в проекте нет (RemoteViews на чистой JVM не собрать), поэтому
 * проводка закреплена как source-контракт — тем же приёмом, что
 * [ChatAccessibilityContractTest] и [ChatClickPathContractTest]: тест читает исходник,
 * манифест и ресурсы и проверяет, что требование действительно выполнено, а не описано.
 *
 * Что здесь ловится при регрессии:
 * - манифест: ресивер объявлен, export закрыт, метаданные указывают на info-файл;
 * - кнопки: транспорт идёт `PendingIntent.getService` с существующими ACTION_* в
 *   MarsMusicService — не свой сервис и не свой плеер;
 * - тап: обложка и текст открывают MusicActivity через `PendingIntent.getActivity`;
 * - состояние: слушатель зарегистрирован в MarsMusicPlayer и снимается в onDisabled;
 * - обложка: HTTP + Bitmap в RemoteViews, ни Glide, ни Coil, ни setImageViewUri;
 * - дизайн-система: ни одного hex-цвета и ни одного глифа — вклад в гейт CI;
 * - доступность: contentDescription у каждой кнопки;
 * - размер и экономия батареи: 4x2, resize в обе стороны, updatePeriodMillis 0.
 */
class MusicWidgetContractTest {

    private fun source(relative: String): String {
        // Unit-тесты Gradle работают из каталога модуля app-mobile; подъём вверх —
        // подстраховка для worktree/CI с другим cwd (как в ChatAccessibilityContractTest).
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    private val provider by lazy { source("src/main/java/ru/mars/mobile/widget/MusicWidgetProvider.kt") }

    /**
     * Тот же провайдер БЕЗ комментариев. Негативные проверки («нет Glide», «нет
     * setImageViewUri», «нет литерала action-строки») обязаны смотреть на КОД: в KDoc
     * эти имена встречаются как объяснение, почему так НЕ сделано, и проверка по
     * сырому тексту ловила бы собственную документацию.
     */
    private val providerCode by lazy {
        provider
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { line -> line.substringBefore("//") }
    }
    private val manifest by lazy { source("src/main/AndroidManifest.xml") }
    private val layout by lazy { source("src/main/res/layout/music_widget.xml") }
    private val info by lazy { source("src/main/res/xml/music_widget_info.xml") }
    private val strings by lazy { source("src/main/res/values/strings.xml") }

    // ------------------------------------------------------------------ манифест

    @Test
    fun `ресивер виджета объявлен и принимает APPWIDGET_UPDATE`() {
        assertTrue(
            "в манифесте нет ресивера .widget.MusicWidgetProvider",
            manifest.contains("android:name=\".widget.MusicWidgetProvider\""),
        )
        assertTrue(
            "ресивер не слушает android.appwidget.action.APPWIDGET_UPDATE",
            manifest.contains("android.appwidget.action.APPWIDGET_UPDATE"),
        )
        assertTrue(
            "нет метаданных android.appwidget.provider",
            manifest.contains("android.appwidget.provider"),
        )
        assertTrue(
            "метаданные не указывают на music_widget_info",
            manifest.contains("@xml/music_widget_info"),
        )
    }

    /** Виджет не должен быть доступен сторонним приложениям: только системный broadcast. */
    @Test
    fun `ресивер виджета не экспортирован наружу`() {
        val block = manifest.substring(
            manifest.indexOf("<receiver"),
            manifest.indexOf("</receiver>"),
        )
        assertTrue("в блоке ресивера нет exported=false", block.contains("android:exported=\"false\""))
        assertFalse("ресивер экспортирован наружу", block.contains("android:exported=\"true\""))
    }

    // ------------------------------------------------------------------ транспорт

    @Test
    fun `кнопки шлют команду сервису через PendingIntent getService`() {
        assertTrue(
            "транспорт не идёт через PendingIntent.getService",
            provider.contains("PendingIntent.getService("),
        )
        assertTrue(
            "intent не адресован MarsMusicService",
            provider.contains("Intent(context, MarsMusicService::class.java)"),
        )
    }

    /** Дублировать action-строки нельзя: они уже объявлены в MusicNotificationPolicy. */
    @Test
    fun `action-строки не продублированы литералами`() {
        assertFalse(
            "в провайдере захардкожена action-строка сервиса",
            providerCode.contains("\"ru.mars.mobile.service.action."),
        )
        listOf("ACTION_PREV", "ACTION_NEXT", "ACTION_PLAY", "ACTION_PAUSE").forEach { name ->
            assertTrue(
                "провайдер не использует MusicNotificationPolicy.$name",
                providerCode.contains("MusicNotificationPolicy.$name"),
            )
        }
    }

    /** Свой сервис/плеер в процессе виджета — прямой запрет задачи. */
    @Test
    fun `виджет не заводит собственный сервис и плеер`() {
        assertFalse("провайдер наследует Service", providerCode.contains(": Service()"))
        assertFalse("провайдер строит ExoPlayer", providerCode.contains("ExoPlayer"))
        assertFalse("провайдер строит MediaPlayer", providerCode.contains("MediaPlayer"))
        // Плеер читается только как снимок состояния процессного синглтона.
        assertTrue(providerCode.contains("MarsMusicPlayer.currentTrack"))
        assertTrue(providerCode.contains("MarsMusicPlayer.isPlaying"))
    }

    // ---------------------------------------------------------------------- тап

    @Test
    fun `тап по обложке и тексту открывает MusicActivity`() {
        assertTrue(
            "нет PendingIntent.getActivity",
            provider.contains("PendingIntent.getActivity("),
        )
        assertTrue(
            "не открывается MusicActivity",
            provider.contains("Intent(context, MusicActivity::class.java)"),
        )
        assertTrue("обложка не кликабельна", provider.contains("R.id.widget_cover, openIntent"))
        assertTrue("текст не кликабелен", provider.contains("R.id.widget_text, openIntent"))
    }

    /** Тап по виджету не должен копировать экран плеера в стек, как это делает шторка. */
    @Test
    fun `тап по виджету переиспользует открытый плеер`() {
        val open = provider.substringAfter("private fun openPlayerIntent(")
            .substringBefore("private const val REQUEST_OPEN")
        assertTrue(
            "нет общих флагов открытия — тапы виджета плодили копии MusicActivity",
            open.contains("MusicNotificationPolicy.TAP_INTENT_FLAGS"),
        )
        assertTrue("флаги должны применяться к intent", open.contains(".addFlags("))
    }

    // ----------------------------------------------------------------- состояние

    @Test
    fun `состояние приходит подпиской на существующий MarsMusicListener`() {
        assertTrue(
            "нет регистрации слушателя в MarsMusicPlayer",
            provider.contains("MarsMusicPlayer.registerListener("),
        )
        assertTrue(
            "слушатель не снимается — утечка на весь процесс",
            provider.contains("MarsMusicPlayer.unregisterListener("),
        )
        assertTrue(
            "провайдер не реализует MarsMusicListener",
            provider.contains("MarsMusicPlayer.MarsMusicListener"),
        )
        assertTrue("onStateChanged не перерисовывает виджет", provider.contains("onStateChanged"))
        assertTrue(
            "подписка не снимается в onDisabled",
            provider.contains("override fun onDisabled") && provider.contains("MusicWidgetHost.detach()"),
        )
    }

    /** Тик прогресса не должен перерисовывать RemoteViews: виджету позиция не нужна. */
    @Test
    fun `прогресс не вызывает перерисовку`() {
        val progress = provider.substringAfter("override fun onProgress")
            .substringBefore("}")
        assertFalse(
            "onProgress перерисовывает виджет — лишний трафик через Binder каждые полсекунды",
            progress.contains("render"),
        )
    }

    // ------------------------------------------------------------------- обложка

    @Test
    fun `обложка грузится обычным HTTP и уезжает в RemoteViews как bitmap`() {
        assertTrue("нет HttpURLConnection", providerCode.contains("HttpURLConnection"))
        assertTrue("нет setImageViewBitmap", providerCode.contains("setImageViewBitmap"))
        assertFalse(
            "setImageViewUri заставил бы лаунчер качать картинку сам",
            providerCode.contains("setImageViewUri"),
        )
    }

    @Test
    fun `никаких библиотек загрузки изображений`() {
        listOf("Glide", "Coil", "Picasso", "Fresco").forEach { lib ->
            assertFalse("виджет тянет $lib", providerCode.contains(lib))
        }
    }

    /** Ошибка загрузки не должна всплывать: остаётся заглушка ic_music. */
    @Test
    fun `ошибка загрузки обложки глотается, остаётся заглушка`() {
        assertTrue("нет заглушки ic_music", providerCode.contains("R.drawable.ic_music"))
        assertTrue(
            "сетевой вызов не обёрнут в runCatching",
            providerCode.contains("runCatching { download(url) }"),
        )
        assertTrue("нет таймаутов соединения", providerCode.contains("connectTimeout"))
        assertTrue("нет таймаута чтения", providerCode.contains("readTimeout"))
        assertTrue("нет потолка размера", providerCode.contains("COVER_MAX_BYTES"))
    }

    // -------------------------------------------------------------- размер, батарея

    @Test
    fun `виджет 4x2 с ресайзом в обе стороны и без периодических будильников`() {
        assertTrue("minWidth не 250dp", info.contains("android:minWidth=\"250dp\""))
        assertTrue("minHeight не 110dp", info.contains("android:minHeight=\"110dp\""))
        assertTrue(
            "resizeMode не horizontal|vertical",
            info.contains("android:resizeMode=\"horizontal|vertical\""),
        )
        assertTrue("updatePeriodMillis не 0", info.contains("android:updatePeriodMillis=\"0\""))
        assertTrue("нет targetCellWidth=4", info.contains("android:targetCellWidth=\"4\""))
        assertTrue("нет targetCellHeight=2", info.contains("android:targetCellHeight=\"2\""))
        assertTrue("нет initialLayout", info.contains("android:initialLayout="))
    }

    // ------------------------------------------------------------- доступность

    @Test
    fun `у каждой кнопки и обложки есть contentDescription`() {
        listOf(
            "widget_prev" to "@string/music_widget_prev",
            "widget_play" to "@string/music_widget_play",
            "widget_next" to "@string/music_widget_next",
            "widget_cover" to "@string/music_widget_cover",
        ).forEach { (id, stringRes) ->
            val block = layout.substringAfter("@+id/$id").substringBefore("/>")
            assertTrue(
                "у $id нет contentDescription",
                block.contains("android:contentDescription="),
            )
            assertTrue(
                "$id не ссылается на $stringRes",
                block.contains("android:contentDescription=\"$stringRes\""),
            )
        }
    }

    @Test
    fun `строки виджета существуют и на русском`() {
        listOf(
            "music_widget_label",
            "music_widget_description",
            "music_widget_cover",
            "music_widget_prev",
            "music_widget_play",
            "music_widget_next",
        ).forEach { name ->
            assertTrue("нет строки $name", strings.contains("<string name=\"$name\">"))
        }
        assertTrue("нет названия виджета", strings.contains(">Музыка Марса<"))
    }

    // --------------------------------------------------------- дизайн-система

    /** Тот же запрет, что у гейта scripts/theme-parity-audit.py, но по нашему файлу. */
    @Test
    fun `в ресурсах виджета нет hex-цветов`() {
        listOf(layout, info, strings).forEach { text ->
            assertFalse(
                "хардкод цвета в ресурсах виджета: ${Regex("0x[0-9a-fA-F]{6,8}|#[0-9a-fA-F]{6,8}").find(text)?.value}",
                Regex("0x[0-9a-fA-F]{6,8}|#[0-9a-fA-F]{6,8}").containsMatchIn(text),
            )
        }
        assertFalse("хардкод цвета в провайдере", Regex("0x[0-9a-fA-F]{6,8}").containsMatchIn(provider))
    }

    /**
     * Глифы вместо иконок запрещены: только vector-drawable. Набор совпадает с
     * BANNED_SYMBOLS гейта, плюс запрет любых эмодзи.
     */
    @Test
    fun `в ресурсах виджета нет типографских символов и эмодзи`() {
        val banned = "✓✔✗✕×✖⚡⚙★☆●◉○◦▲▼◀▶⌃⌄▴▾▸☰➤■▢▪⌛⏳⚠✂✎✏🗑♫♪"
        listOf(layout, info, provider, strings).forEach { text ->
            banned.forEach { glyph ->
                assertFalse("глиф '$glyph' в ресурсах виджета", text.contains(glyph))
            }
            // Идём по code point'ам вручную: String.codePoints() — поток Java 8, а
            // суррогатные пары эмодзи иначе посчитались бы двумя символами.
            var i = 0
            while (i < text.length) {
                val code = text.codePointAt(i)
                val isEmoji = code in 0x1F300..0x1FAFF || code in 0x2600..0x27BF || code in 0x2B00..0x2BFF
                assertFalse("эмодзи U+${code.toString(16)} в ресурсах виджета", isEmoji)
                i += Character.charCount(code)
            }
        }
    }

    @Test
    fun `цвета берутся токенами палитры mars`() {
        val backgroundRes = source("src/main/res/drawable/music_widget_bg.xml")
        assertTrue(
            "подложка виджета не использует токен палитры",
            backgroundRes.contains("@color/mars_surface"),
        )
        assertFalse(
            "подложка виджета залита hex'ом",
            Regex("#[0-9a-fA-F]{6,8}").containsMatchIn(backgroundRes),
        )
    }

    /**
     * Чужие зоны не тронуты: виджет обязан жить без правок сервиса и экрана музыки.
     * Проверяется по факту — сервис по-прежнему не знает про виджет.
     */
    @Test
    fun `виджет не требует правок MarsMusicService`() {
        val service = source("src/main/java/ru/mars/mobile/service/MarsMusicService.kt")
        assertFalse(
            "MarsMusicService ссылается на виджет — чужая зона не должна меняться",
            service.contains("Widget"),
        )
        val manifestService = manifest.substringAfter("<service")
            .substringBefore("/>")
        assertTrue(
            "объявление MarsMusicService изменилось",
            manifestService.contains("android:name=\".service.MarsMusicService\""),
        )
    }
}
