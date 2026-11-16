package ru.mars.mobile

import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Политика живого космического фона «Глубокий космос с красной планетой Марс».
 *
 * Класс намеренно не зависит от `android.*`: это чистый JVM-объект, поэтому вся математика
 * кадров, параллакса и сама GLSL-программа проверяются обычными unit-тестами
 * ([MarsSpaceShaderPolicyTest]), без эмулятора и без GPU.
 *
 * Что здесь живёт:
 *  - исходники GLSL: `#version 300 es` ([VERTEX_SHADER_300], [FRAGMENT_SHADER_300]) и
 *    совместимый `#version 100` для ES 2.0 ([VERTEX_SHADER_100], [FRAGMENT_SHADER_100]);
 *  - чистые функции кадрового ритма ([targetFps], [frameIntervalMs], [shouldDrawFrame]);
 *  - инерционный параллакс скролла ([scrollTarget], [smoothScroll], [isScrolling]);
 *  - решение «анимировать ли вообще» ([shouldAnimate]) — reduce motion и энергосбережение;
 *  - палитра Марса и разрешение рендера, общие для GPU-шейдера и Canvas-фоллбэка.
 *
 * Картинка (паритет с эталоном Квартала, `public/chat-bg.js`, `_CS_FRAG_300`):
 *  - глубокий обсидиановый космос с медленным вращением небосвода вокруг полярной оси;
 *  - объёмная туманность с domain warping (`fbm3`/`fbm5`), тёмные пылевые прожилки
 *    (Bok globules), поглощение (`transmittance`) и подсвеченная кромка (forward scattering);
 *  - четыре слоя звёзд разной глубины с мерцанием и крестовыми вспышками ярких звёзд;
 *  - красная планета Марс на горизонте в контровом свете: тёплое рэлеевское гало пылевой
 *    атмосферы, терракотово-багровый рельеф с кратерами и пылевыми бурями, тёмная ночная
 *    сторона и рубиново-огненный серп на лимбе;
 *  - процедурные падающие кометы с плазменным хвостом — прямо в шейдере;
 *  - кинематографическая виньетка и ACES Filmic тональная компрессия.
 *
 * Палитра умышленно тёплая и приглушённая: поверх космоса лежит полупрозрачное стекло
 * (`MarsTheme.GLASS_*`), и белый текст должен читаться без затемняющих вуалей.
 */
object MarsSpaceShaderPolicy {

    // ── Кадровый ритм ────────────────────────────────────────────────────────────────────

    /** Покой: 24 кадра в секунду — газ мягкий, чаще незачем, GPU и батарея отдыхают. */
    const val IDLE_FPS = 24

    /** Активный скролл: 60 кадров в секунду, чтобы параллакс шёл без рывков. */
    const val SCROLL_FPS = 60

    /** 1000 / 24 = 41.67 мс; 41 мс — тот же порог, что `>= 40` в эталоне Квартала. */
    const val IDLE_FRAME_INTERVAL_MS = 41L

    /** 1000 / 60 = 16.67 мс; 16 мс — «каждый кадр Choreographer» без потери vsync. */
    const val SCROLL_FRAME_INTERVAL_MS = 16L

    /** Нижняя граница шага симуляции: после паузы/фона не телепортируем анимацию. */
    const val MIN_FRAME_DT_SEC = 0.001f

    /** Верхняя граница шага симуляции (как `Math.min(0.05, ...)` в эталоне). */
    const val MAX_FRAME_DT_SEC = 0.05f

    /** Скорость догоняния параллакса: `k = min(1, dt * 10)` из эталона. */
    const val SMOOTH_SCROLL_RESPONSE = 10f

    /** Нормировочный масштаб скролла: `tanh(raw / 850) * 1.4` — 850 px на «полный» сдвиг. */
    const val SCROLL_NORM_PX = 850f

    /** Предел параллакса в единицах uv: дальше сцена не уезжает (tanh насыщается). */
    const val SCROLL_LIMIT = 1.4f

    /** Порог «ещё едем»: меньше — считаем, что параллакс уже успокоился. */
    const val SCROLL_EPSILON = 0.0008f

    /** Время статичного кадра для reduce motion / энергосбережения — выразительный момент. */
    const val STATIC_TIME_SEC = 14.0f

    // ── Разрешение рендера ──────────────────────────────────────────────────────────────

    /** Эталон рисует газ в 0.85 от размера вью: газ мягкий, лишние пиксели не видны. */
    const val RENDER_SCALE = 0.85f

    /** Ниже 0.5 уже заметны пиксели на лимбе планеты. */
    const val MIN_RENDER_SCALE = 0.5f

    /** Бюджет пикселей буфера (~2.4 Мп): больше на телефоне не даёт ничего, кроме нагрева. */
    const val MAX_RENDER_PIXELS = 2_400_000

    // ── Планета Марс ────────────────────────────────────────────────────────────────────

    /** Радиус диска Марса в единицах uv (доля короткой стороны экрана). */
    const val PLANET_RADIUS = 0.24f

    /** Центр диска по X: чуть правее середины, как «планета на горизонте». */
    const val PLANET_CENTER_X = 0.41f

    /** Центр диска по Y (uv, ось вверх): выше середины, чтобы низ экрана был свободен. */
    const val PLANET_CENTER_Y = 0.24f

    /** Параллакс планеты от скролла: `uScroll * 0.036` в шейдере. */
    const val PLANET_SCROLL_FACTOR = 0.036f

    // ── Палитра (ARGB для Canvas-фоллбэка; те же числа зашиты в GLSL) ───────────────────

    /** Верх космоса: 0.004 / 0.006 / 0.014 — обсидиан с холодным уклоном. */
    const val COLOR_VOID_TOP = 0xFF010204.toInt()

    /** Низ космоса: 0.024 / 0.009 / 0.012 — ржавая дымка у горизонта Марса. */
    const val COLOR_VOID_BOTTOM = 0xFF060203.toInt()

    /** Рубиновый газ туманности: vec3(0.46, 0.10, 0.12). */
    const val COLOR_NEBULA_RUBY = 0xFF751A1F.toInt()

    /** Янтарный газ туманности: vec3(0.52, 0.26, 0.08). */
    const val COLOR_NEBULA_AMBER = 0xFF854214.toInt()

    /** Сливовый газ туманности: vec3(0.24, 0.08, 0.22). */
    const val COLOR_NEBULA_PLUM = 0xFF3D1438.toInt()

    /** Внешний ореол пылевой атмосферы: vec3(0.92, 0.28, 0.14). */
    const val COLOR_ATMOSPHERE = 0xFFEB4724.toInt()

    /** Ядро ореола (ближе к лимбу): vec3(1.00, 0.45, 0.22). */
    const val COLOR_ATMOSPHERE_CORE = 0xFFFF7338.toInt()

    /** Тёмный рельеф: vec3(0.32, 0.08, 0.04). */
    const val COLOR_SURFACE_DARK = 0xFF52140A.toInt()

    /** Освещённый рельеф: vec3(0.78, 0.24, 0.10). */
    const val COLOR_SURFACE_LIT = 0xFFC73D1A.toInt()

    /** Ночная сторона: vec3(0.015, 0.005, 0.006) — почти чёрный с багровым подтоном. */
    const val COLOR_NIGHT_SIDE = 0xFF040102.toInt()

    /** Рубиново-огненный серп на лимбе: vec3(0.96, 0.36, 0.18). */
    const val COLOR_LIMB_CRESCENT = 0xFFF55C2E.toInt()

    /** Тёплый белый звёзд: vec3(1.00, 0.86, 0.74). */
    const val COLOR_STAR_WARM = 0xFFFFDBBD.toInt()

    // ── Чистые функции кадрового ритма ──────────────────────────────────────────────────

    /** Целевая частота кадров: 24 в покое, 60 при активном скролле. */
    fun targetFps(scrolling: Boolean): Int = if (scrolling) SCROLL_FPS else IDLE_FPS

    /** Минимальный интервал между кадрами в миллисекундах. */
    fun frameIntervalMs(scrolling: Boolean): Long =
        if (scrolling) SCROLL_FRAME_INTERVAL_MS else IDLE_FRAME_INTERVAL_MS

    /** Сколько кадров в секунду даёт интервал (для логов и тестов). */
    fun framesPerSecond(intervalMs: Long): Double =
        if (intervalMs <= 0L) 0.0 else 1000.0 / intervalMs.toDouble()

    /**
     * Пора ли рисовать кадр. `lastDrawMs <= 0` — кадра ещё не было (первый кадр рисуем сразу),
     * `nowMs < lastDrawMs` — часы сбросились (перезапуск Choreographer, смена монотонности),
     * тоже рисуем немедленно, чтобы не залипнуть в чёрном экране.
     */
    fun shouldDrawFrame(lastDrawMs: Long, nowMs: Long, scrolling: Boolean): Boolean {
        if (lastDrawMs <= 0L) return true
        if (nowMs < lastDrawMs) return true
        return nowMs - lastDrawMs >= frameIntervalMs(scrolling)
    }

    /** Шаг симуляции, ограниченный сверху и снизу (после фона не «прыгаем» вперёд). */
    fun clampFrameDt(dtSec: Float): Float {
        if (dtSec.isNaN()) return MIN_FRAME_DT_SEC
        return dtSec.coerceIn(MIN_FRAME_DT_SEC, MAX_FRAME_DT_SEC)
    }

    // ── Инерционный параллакс скролла ───────────────────────────────────────────────────

    /**
     * Целевой сдвиг сцены от «сырого» скролла ленты: `tanh(raw / 850) * 1.4`.
     * Гиперболический тангенс насыщается, поэтому длинная лента не уводит небо за экран,
     * а первые экраны дают почти линейный, «дорогой» отклик.
     */
    fun scrollTarget(rawScrollPx: Int): Float {
        val raw = rawScrollPx.coerceAtLeast(0).toDouble()
        return (tanh(raw / SCROLL_NORM_PX.toDouble()) * SCROLL_LIMIT.toDouble()).toFloat()
    }

    /**
     * Один шаг инерционного догоняния: `current + (target - current) * min(1, dt * 10)`.
     * [dtSec] ограничен снизу нулём (отрицательный шаг не откатывает сцену) и сверху
     * [MAX_FRAME_DT_SEC] (после паузы сцена не проскакивает цель).
     */
    fun smoothScroll(rawScrollPx: Int, currentSmooth: Float, dtSec: Float): Float {
        val target = scrollTarget(rawScrollPx)
        val step = (dtSec.coerceIn(0f, MAX_FRAME_DT_SEC) * SMOOTH_SCROLL_RESPONSE).coerceIn(0f, 1f)
        return currentSmooth + (target - currentSmooth) * step
    }

    /** Параллакс ещё движется — значит кадры нужны на 60 fps, а не на 24. */
    fun isScrolling(rawScrollPx: Int, currentSmooth: Float): Boolean =
        kotlin.math.abs(scrollTarget(rawScrollPx) - currentSmooth) > SCROLL_EPSILON

    // ── Живость фона ────────────────────────────────────────────────────────────────────

    /**
     * Можно ли анимировать фон. Отключаем при:
     *  - `animatorsEnabled == false` — вызывающий код запретил анимацию;
     *  - `powerSave == true` — энергосбережение, батарея важнее красоты;
     *  - `animatorScale <= 0` — в системе включено «удалить анимации» (reduce motion).
     * В этих случаях вью рисует ровно один статичный кадр ([STATIC_TIME_SEC]) и глушит тикер.
     */
    fun shouldAnimate(animatorsEnabled: Boolean, powerSave: Boolean, animatorScale: Float): Boolean {
        if (!animatorsEnabled) return false
        if (powerSave) return false
        if (animatorScale.isNaN()) return false
        return animatorScale > 0f
    }

    /**
     * Масштаб буфера рендера: не больше [RENDER_SCALE] и не больше бюджета
     * [MAX_RENDER_PIXELS], но не ниже [MIN_RENDER_SCALE].
     */
    fun renderScale(
        widthPx: Int,
        heightPx: Int,
        preferred: Float = RENDER_SCALE,
    ): Float {
        if (widthPx <= 0 || heightPx <= 0) return MIN_RENDER_SCALE
        var scale = preferred.coerceIn(MIN_RENDER_SCALE, 1f)
        val pixels = widthPx.toLong() * heightPx.toLong()
        if (pixels <= 0L) return MIN_RENDER_SCALE
        val budget = sqrt(MAX_RENDER_PIXELS.toDouble() / pixels.toDouble()).toFloat()
        if (budget < scale) scale = budget
        return scale.coerceIn(MIN_RENDER_SCALE, 1f)
    }

    // ── GLSL: вершинные шейдеры ────────────────────────────────────────────────────────

    /**
     * ES 3.0: полноэкранный треугольник без буферов вершин — три вершины выводятся из
     * `gl_VertexID`, поэтому на кадр нет ни одного VBO-биндинга.
     */
    const val VERTEX_SHADER_300: String = """#version 300 es
void main(){
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** ES 2.0: тот же полноэкранный квад, но через обычный атрибут `aPos`. */
    const val VERTEX_SHADER_100: String = """#version 100
attribute vec2 aPos;
void main(){
  gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    // ── GLSL: общее тело фрагментного шейдера ──────────────────────────────────────────

    /**
     * Тело фрагментного шейдера, общее для ES 3.0 и ES 2.0: отличается только объявление
     * выходной переменной (`out vec4 fragColor` против `gl_FragColor`) и преамбула точности.
     * Держим один источник правды, чтобы марсианская картинка на старых GPU не разъезжалась
     * с новой. Комментарии — только ASCII: строгие драйверы GLSL ES не любят не-ASCII в коде.
     */
    private const val GLSL_FRAGMENT_BODY: String = """
// ---------- hashing and value noise ----------
float h1(vec3 p){
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}
vec3 h3(vec3 p){
  p = fract(p * vec3(0.1031, 0.1030, 0.0973));
  p += dot(p, p.yxz + 33.33);
  return fract((p.xxy + p.yxx) * p.zyx);
}
float vnoise(vec3 x){
  vec3 i = floor(x);
  vec3 f = fract(x);
  f = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(mix(h1(i), h1(i + vec3(1.0, 0.0, 0.0)), f.x),
        mix(h1(i + vec3(0.0, 1.0, 0.0)), h1(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
    mix(mix(h1(i + vec3(0.0, 0.0, 1.0)), h1(i + vec3(1.0, 0.0, 1.0)), f.x),
        mix(h1(i + vec3(0.0, 1.0, 1.0)), h1(i + vec3(1.0, 1.0, 1.0)), f.x), f.y),
    f.z);
}
mat2 rot2(float a){
  float s = sin(a);
  float c = cos(a);
  return mat2(c, s, -s, c);
}

// ---------- fractal brownian motion (5 and 3 octaves) ----------
float fbm5(vec3 p){
  float a = 0.52;
  float s = 0.0;
  for(int i = 0; i < 5; i++){
    s += a * vnoise(p);
    p.xy = rot2(1.09) * p.xy;
    p = p * 2.05 + vec3(1.7, 9.2, 4.3);
    a *= 0.51;
  }
  return s;
}
float fbm3(vec3 p){
  float s = 0.52 * vnoise(p);
  p.xy = rot2(1.09) * p.xy;
  p = p * 2.04 + vec3(1.7, 9.2, 4.3);
  s += 0.28 * vnoise(p);
  p.xy = rot2(1.09) * p.xy;
  p = p * 2.04 + vec3(1.7, 9.2, 4.3);
  s += 0.15 * vnoise(p);
  return s;
}

// ---------- one depth layer of the star field ----------
float starLayer(vec2 uv, float scale, float seed, float density, float t){
  vec2 p = uv * scale + vec2(seed * 17.3, seed * 31.7);
  vec2 i = floor(p);
  vec2 f = fract(p) - 0.5;
  vec3 r = h3(vec3(i, seed));
  vec2 d = f - (r.xy - 0.5) * 0.72;
  float dist = length(d);
  float br = smoothstep(1.0 - density, 1.0, r.z);
  float tw = 0.68 + 0.32 * sin(t * (1.4 + r.x * 2.6) + r.y * 6.283);
  float core = exp(-dist * dist * 210.0) * br * tw;
  float flare = 0.0;
  if (r.z > 0.986) {
    float sx = exp(-abs(d.x) * 42.0) * exp(-abs(d.y) * 6.5);
    float sy = exp(-abs(d.y) * 42.0) * exp(-abs(d.x) * 6.5);
    flare = (sx + sy) * 0.85 * tw;
  }
  return core + flare;
}

// ---------- ACES Filmic tone compression ----------
vec3 acesTone(vec3 x){
  x = max(x, vec3(0.0));
  x = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
  return pow(clamp(x, 0.0, 1.0), vec3(0.92));
}

// ---------- one falling comet with a plasma tail ----------
vec3 cometStreak(vec2 uv, float seed, float t){
  float period = 9.0 + seed * 5.0;
  float ph = fract(t / period + seed * 0.37);
  float life = smoothstep(0.0, 0.08, ph) * (1.0 - smoothstep(0.72, 0.95, ph));
  if (life <= 0.001) return vec3(0.0);
  vec2 a = vec2(-0.95 + seed * 1.55, 0.92 - seed * 0.35);
  vec2 b = a + vec2(0.72, -1.05);
  vec2 head = mix(a, b, ph);
  vec2 dir = normalize(b - a);
  vec2 d = uv - head;
  float along = dot(d, dir);
  float perp = dot(d, vec2(-dir.y, dir.x));
  float headR = 0.010 + 0.006 * seed;
  float coma = exp(-dot(d, d) / (headR * headR)) * 1.6;
  float back = clamp(-along / 0.55, 0.0, 1.0);
  float tail = exp(-perp * perp / (0.00035 + back * 0.0045)) * pow(1.0 - back, 2.2) * step(along, 0.0);
  float filaments = 0.65 + 0.35 * sin(perp * 90.0 + along * 26.0 + t * 3.0);
  vec3 cHead = vec3(1.0, 0.93, 0.86);
  vec3 cTail = mix(vec3(0.98, 0.42, 0.20), vec3(0.62, 0.80, 1.0), 0.35 + 0.35 * sin(seed * 9.0));
  return (cHead * coma + cTail * tail * filaments * 1.25) * life;
}

void main(){
  vec2 frag = gl_FragCoord.xy;
  float minDim = min(uRes.x, uRes.y);
  vec2 uv = (frag - 0.5 * uRes) / minDim;
  float t = uTime * 0.18;

  // Deep space void: obsidian with a faint rust glow toward the horizon.
  float horiz = clamp(0.55 - uv.y, 0.0, 1.0);
  vec3 col = mix(vec3(0.004, 0.006, 0.014), vec3(0.024, 0.009, 0.012), horiz * horiz);

  // Slow sky rotation around a distant polar axis + soft scroll parallax.
  vec2 pole = vec2(-0.18, 0.42);
  vec2 skyFar  = rot2(uTime * 0.0042) * (uv - pole) + pole + vec2(0.0, uScroll * 0.014);
  vec2 skyMid1 = rot2(uTime * 0.0056) * (uv - pole) + pole + vec2(0.0, uScroll * 0.028);
  vec2 skyMid2 = rot2(uTime * 0.0070) * (uv - pole) + pole + vec2(0.0, uScroll * 0.044);
  vec2 skyHero = rot2(uTime * 0.0084) * (uv - pole) + pole + vec2(0.0, uScroll * 0.062);

  // Diagonal nebula arm (ruby / amber / plum) with domain warping.
  vec2 ruv = rot2(-0.58 + uTime * 0.0032) * (uv + vec2(0.0, uScroll * 0.024));
  vec3 np = vec3(ruv * 1.65, t * 0.16);
  vec3 q = vec3(
    fbm3(np),
    fbm3(np + vec3(5.2, 1.3, 2.8)),
    fbm3(np + vec3(1.8, 7.1, 4.4))
  );
  vec3 r = vec3(
    fbm5(np + 2.45 * q + vec3(1.7, 9.2, 0.5) + t * 0.10),
    fbm5(np + 2.45 * q + vec3(8.3, 2.8, 1.9) - t * 0.08),
    fbm3(np + 2.10 * q + vec3(3.4, 4.9, 6.1))
  );
  float f = fbm5(np + 2.2 * r);

  // Galactic arm envelope + two local gas pockets.
  float band = exp(-pow((ruv.y + 0.14 * sin(ruv.x * 2.4)) * 2.15, 2.0));
  float pocket1 = exp(-dot(uv - vec2(-0.22, 0.34), uv - vec2(-0.22, 0.34)) * 3.8);
  float pocket2 = exp(-dot(uv - vec2(0.28, -0.26), uv - vec2(0.28, -0.26)) * 3.4);
  float env = clamp(band * 0.92 + pocket1 * 0.65 + pocket2 * 0.60, 0.0, 1.35);

  // Dark dust lanes (Bok globules): they swallow the light of the gas behind them.
  float ridge = 1.0 - abs(f * 2.0 - 1.0);
  float dustLane = smoothstep(0.42, 0.88, pow(ridge, 2.4) * fbm3(np * 2.35 - vec3(t * 0.12, 0.0, 0.0)));
  float transmittance = exp(-dustLane * env * 2.65);

  // Martian nebula palette: ruby, amber, plum, ember over a cold anchor.
  vec3 cRuby  = vec3(0.46, 0.10, 0.12);
  vec3 cAmber = vec3(0.52, 0.26, 0.08);
  vec3 cPlum  = vec3(0.24, 0.08, 0.22);
  vec3 cEmber = vec3(0.60, 0.20, 0.10);
  vec3 cCold  = vec3(0.06, 0.08, 0.16);

  float gasA = smoothstep(0.55, 0.98, f) * env * 0.35;
  float gasB = smoothstep(0.60, 0.95, r.x) * env * 0.30;
  float gasC = smoothstep(0.65, 0.99, r.y) * (band + pocket1 * 0.4) * 0.25;
  float hotCore = pow(clamp(f * r.x * 1.65, 0.0, 1.0), 3.0) * env * 0.2;

  vec3 neb = vec3(0.0);
  neb += cRuby * gasA * 0.72;
  neb += cPlum * gasB * 0.58;
  neb += cCold * pow(gasA, 1.45) * (0.85 + 0.5 * pocket2) * 0.55;
  neb += cEmber * pow(gasC, 1.35) * 0.86;
  neb += cAmber * hotCore * 1.45;

  // Dust absorption plus the forward-scattered rim of the gas.
  float rimLight = pow(clamp(f - dustLane * 0.45, 0.0, 1.0), 2.2) * env * 0.35;
  neb = neb * (0.32 + 0.68 * transmittance) + mix(cRuby, cAmber, r.y) * rimLight * 0.35;
  col += neb;

  // Four depth layers of rotating stars with twinkle and cross flares.
  float sFar  = starLayer(skyFar,  38.0, 1.1, 0.16, uTime);
  float sMid1 = starLayer(skyMid1, 21.0, 3.7, 0.11, uTime);
  float sMid2 = starLayer(skyMid2, 13.5, 7.3, 0.075, uTime);
  float sHero = starLayer(skyHero,  6.2, 13.9, 0.042, uTime);

  // Stars behind thick dust dim and redden (interstellar extinction).
  vec3 dustRedden = mix(vec3(1.0, 0.62, 0.42), vec3(1.0), transmittance);
  col += vec3(1.00, 0.92, 0.86) * sFar * 0.62 * dustRedden;
  col += vec3(1.00, 0.86, 0.74) * sMid1 * 1.10 * dustRedden;
  col += vec3(1.00, 0.80, 0.62) * sMid2 * 1.22;
  col += vec3(0.94, 0.96, 1.00) * sHero * 1.75;

  // Falling comets: plasma tail lives entirely inside this shader.
  vec2 cuv = uv + vec2(0.0, uScroll * 0.05);
  col += cometStreak(cuv, 0.17, uTime) * 1.15;
  col += cometStreak(cuv, 0.63, uTime + 4.0) * 0.85;
  col += cometStreak(cuv, 0.41, uTime * 0.85 + 11.0) * 0.70;

  // Mars on the horizon, back-lit: dark night side, ruby crescent on the limb.
  vec2 pCenter = vec2(0.41, 0.24 + uScroll * 0.036);
  float pRad = 0.24;
  vec2 dp = uv - pCenter;
  float pDist = length(dp);
  vec2 sunDir = normalize(vec2(-0.76, -0.65));
  float sunAlign = clamp(dot(dp / max(pDist, 0.0001), sunDir), 0.0, 1.0);
  // Warm Rayleigh halo of the dusty Martian atmosphere, only on the lit side.
  float atmoGlow = exp(-max(pDist - pRad, 0.0) * 26.0) * smoothstep(pRad * 0.72, pRad * 1.30, pDist);
  float crescent = pow(sunAlign, 2.5);
  col += vec3(0.92, 0.28, 0.14) * atmoGlow * crescent * 0.40;
  col += vec3(1.00, 0.45, 0.22) * atmoGlow * pow(crescent, 3.0) * 0.30;

  // The planet disk eclipses the sky behind it.
  if (pDist < pRad) {
    float z = sqrt(max(0.0, pRad * pRad - pDist * pDist)) / pRad;
    vec3 nrm = normalize(vec3(dp / pRad, z));
    vec3 lightDir = normalize(vec3(-0.72, -0.58, 0.26));
    float ndl = max(0.0, dot(nrm, lightDir));
    float rim = pow(1.0 - z, 3.8) * pow(sunAlign, 1.9);
    // Terracotta relief: macro regions, crater ring noise, drifting dust storms.
    float macro = fbm5(nrm * 2.60 + vec3(0.0, uTime * 0.006, 0.0));
    float detail = fbm3(nrm * 7.40 + vec3(11.0, 3.0, 7.0));
    float craterRidge = 1.0 - abs(detail * 2.0 - 1.0);
    float crater = smoothstep(0.62, 0.98, craterRidge) * (0.55 + 0.45 * macro);
    float pTex = clamp(macro * 0.72 + detail * 0.38 - crater * 0.24, 0.0, 1.0);
    vec3 pDark = vec3(0.015, 0.005, 0.006);
    vec3 pLit = mix(vec3(0.32, 0.08, 0.04), vec3(0.78, 0.24, 0.10), pTex) * pow(ndl, 1.45) * 0.92;
    float storm = smoothstep(0.55, 0.92, fbm3(nrm * 3.10 + vec3(uTime * 0.020, 0.0, uTime * 0.011))) * pow(ndl, 1.20);
    pLit += vec3(0.86, 0.42, 0.18) * storm * 0.30;
    vec3 pCol = pDark + pLit + vec3(0.96, 0.36, 0.18) * rim * 0.72;
    float edgeAa = smoothstep(pRad, pRad - 0.004, pDist);
    col = mix(col, pCol, edgeAa);
  }

  // Cinematic vignette and ACES Filmic tone compression.
  float vig = 1.0 - 0.28 * dot(uv, uv);
  col = acesTone(col * vig * 1.22);
"""

    /**
     * ES 3.0 фрагментный шейдер: `out vec4 fragColor`.
     * Собирается как заголовок + общее тело: картинка у 3.0 и 2.0 обязана быть одна.
     */
    val FRAGMENT_SHADER_300: String = """#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform float uScroll;
out vec4 fragColor;
""" + GLSL_FRAGMENT_BODY + """
  fragColor = vec4(col, 1.0);
}
"""

    /**
     * ES 2.0 фрагментный шейдер: `gl_FragColor`, а `highp` берётся только если GPU его
     * действительно умеет (иначе `mediump` — иначе часть старых драйверов не соберёт программу).
     */
    val FRAGMENT_SHADER_100: String = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uRes;
uniform float uTime;
uniform float uScroll;
""" + GLSL_FRAGMENT_BODY + """
  gl_FragColor = vec4(col, 1.0);
}
"""
}
