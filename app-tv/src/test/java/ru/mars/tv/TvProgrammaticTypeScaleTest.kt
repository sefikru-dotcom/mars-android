package ru.mars.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Программный рендер ТВ-клиента держит ту же типографическую шкалу, что и layout-ы.
 *
 * Причина существования: [TvTypeScaleGateTest] проверяет `android:textSize` в XML и
 * литеральные `NNf` в Kotlin, но вьюхи, собранные кодом (карточки решений, секции чата,
 * канбан, музыка, аналитика, скиллы), задавали кегль подбором на глаз — 15/17/18/22/24sp.
 * Из-за этого шкала из `dimens.xml` действовала только на половину экрана.
 *
 * Контракт:
 *  1) литеральный кегль допустим только из шкалы [scale];
 *  2) программный кегль берётся токеном `R.dimen.tv_text_*` через `TextView.tvTextSize`;
 *  3) каждый такой токен существует в `dimens.xml` и сам лежит на шкале — иначе
 *     «правильный» вызов тянул бы «неправильный» размер.
 *
 * Тест читает исходники с диска: юнит-тест JVM не видит ни `R`, ни ресурсов, а
 * `user.dir` у тестовой задачи — каталог модуля `app-tv`.
 */
class TvProgrammaticTypeScaleTest {

    private val moduleDir = File(System.getProperty("user.dir"))
    private val javaDir = File(moduleDir, "src/main/java")
    private val dimensFile = File(moduleDir, "src/main/res/values/dimens.xml")

    /** Шкала styles.xml: micro, label, body, title, headline, display (+ 11sp минимум). */
    private val scale = setOf(11, 12, 14, 16, 20, 28, 40)

    /** `textSize = 18f` — присваивание свойства TextView. */
    private val rawAssign = Regex("""textSize\s*=\s*(\d+(?:\.\d+)?)f""")

    /** `setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)` — явный вызов сеттера. */
    private val rawSetter = Regex(
        """setTextSize\(\s*TypedValue\.COMPLEX_UNIT_(?:SP|PX)\s*,\s*(\d+(?:\.\d+)?)f""",
    )

    /** `tvTextSize(R.dimen.tv_text_body)` — единственная разрешённая форма. */
    private val tokenCall = Regex("""tvTextSize\(\s*R\.dimen\.(tv_text_\w+)\s*\)""")

    private fun kotlinFiles(): List<File> =
        javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Все `tv_text_*` из dimens.xml вместе со значением в sp. */
    private fun declaredTokens(): Map<String, Int> =
        Regex("""<dimen name="(tv_text_\w+)">\s*(\d+)sp\s*</dimen>""")
            .findAll(dimensFile.readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }

    @Test
    fun `no literal text size outside the scale`() {
        val offenders = mutableListOf<String>()
        kotlinFiles().forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                listOf(rawAssign, rawSetter).forEach { re ->
                    re.findAll(line).forEach { m ->
                        val sp = m.groupValues[1].toFloat()
                        if (sp != sp.toInt().toFloat() || sp.toInt() !in scale) {
                            offenders += "${file.name}:${index + 1} ${m.value}"
                        }
                    }
                }
            }
        }
        assertTrue(
            "Кегли вне шкалы $scale в программном рендере — бери R.dimen.tv_text_* " +
                "через tvTextSize(): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `programmatic text sizes reference declared scale tokens`() {
        val tokens = declaredTokens()
        val offenders = mutableListOf<String>()
        kotlinFiles().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                tokenCall.findAll(line).forEach { m ->
                    val token = m.groupValues[1]
                    val value = tokens[token]
                    when {
                        value == null -> offenders += "${file.name}:${index + 1} нет токена $token"
                        value !in scale -> offenders += "${file.name}:${index + 1} $token=$value вне шкалы"
                    }
                }
            }
        }
        assertTrue("Программный кегль вне токенов шкалы: $offenders", offenders.isEmpty())
    }

    @Test
    fun `scale tokens themselves stay on the scale`() {
        val tokens = declaredTokens()
        assertTrue("dimens.xml не объявляет ни одного tv_text_*", tokens.isNotEmpty())
        val drifted = tokens.filterValues { it !in scale }
        assertTrue("Токены кеглей вне шкалы $scale: $drifted", drifted.isEmpty())
        listOf("tv_text_display", "tv_text_headline", "tv_text_title",
               "tv_text_body", "tv_text_label", "tv_text_micro")
            .forEach { assertTrue("нет токена кегля $it", tokens.containsKey(it)) }
    }
}
