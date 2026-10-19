#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen-visual-assets.py — воспроизводимый генератор растровых ассетов Марса.

Источник (`assets-src/`, вне git): фотореалистичный 3D-рендер планеты
(`favicon-512.png`, тот же кадр что в WebUI) и обои (`mars-bg-mobile.jpg`).
Скрипт раскладывает их в ресурсы клиента — Android-бинарники в git не правятся
руками, любая правка ассета делается повторным запуском.

Что генерируется (обе цели — app-mobile и app-tv):

  res/drawable-nodpi/mars_bg.webp         обои окна И фон заставки (один файл, WebP q80)
  res/drawable-nodpi/mars_planet.webp     планета для шапки, прозрачный фон (WebP q90)
  res/drawable-nodpi/mars_splash_planet.webp  планета заставки, прозрачный фон (WebP q90)
  res/mipmap-<d>/ic_launcher.png          квадратная legacy-иконка
  res/mipmap-<d>/ic_launcher_round.png    круглая legacy-иконка
  res/mipmap-<d>/ic_launcher_foreground.png  foreground adaptive-иконки (108dp)
  (только app-tv) res/drawable-xhdpi/tv_banner_render.png  TV-баннер 320x180

Заставка (splash) использует ТОТ ЖЕ фон, что и окно, — один файл на два
layer-list: `mars_window_bg.xml` приглушает обои слоем alpha 0.40, а
`splash_window_bg.xml` рисует их на полной яркости. Раньше это были две
байт-в-байт копии источника (md5 совпадал), то есть APK вёз один кадр дважды;
разница яркости живёт в атрибутах слоя, а не в отдельном файле. Планета
заставки — нативный кроп рендера: 436px при источнике 512px.
Потолок разрешения задаёт сам источник: апскейл выше нативного кропа
(favicon-512.png — это 512px, лимб 358px) не добавил бы деталей, только
размыл лимб, поэтому 1080px из него не рисуется. Пропорции фона сохраняются
1:1, поверх ничего не дорисовывается — неоновый ореол накладывает код.

Геометрия источника не зашита числами: лимб планеты (край диска) ищется как
медиана перепадов яркости по 360-градусным лучам, центр — сдвигом с наименьшим
разбросом найденных радиусов. Край ищется строго по градиенту, а не по
абсолютной яркости: рендер непрозрачный, и тёмное полушарие почти так же черно,
как вакуум. Альфа строится по этой геометрии — внутри лимба непрозрачно (тёмная
сторона обязана остаться в силуэте), снаружи — рампа по яркости с радиальным
затуханием к рамке кадра (атмосферный ореол без кольца-шва на границе).

Использование:
    gen-visual-assets.py --write [--src DIR] [--root DIR]
    gen-visual-assets.py --verify [--src DIR] [--root DIR]

`--write` пишет ресурсы в модули, `--verify` пересчитывает всё в памяти и
сравнивает с файлами на диске (код 1 при расхождении) — это же и проверка
идемпотентности: повторный `--write` обязан дать те же байты.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import math
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- константы

PLANET_SRC = "favicon-512.png"
BG_SRC = "mars-bg-mobile.jpg"

# Целевые метрики (dp x плотность). Значения обязательные: их проверяют тесты.
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND_DP = 108          # viewport adaptive-иконки
SAFE_DP = 66                 # диаметр safe zone: дальше него маска может срезать
LEGACY_CONTENT_FRAC = 0.74   # доля кадра legacy-иконки под весь рендер (с ореолом)
HALO_SPAN = 1.22             # ореол тянется до 1.22 радиуса лимба

# Лимб adaptive-иконки выводится из safe zone, а не назначается на глаз: кадр
# ассета обрезан по 1.22 радиуса диска, поэтому рамка кадра обязана уложиться в
# радиус safe zone (33dp) — иначе маска-круг срежет ореол жёстким краем.
# 52dp даёт рамку 1.22 * 26 = 31.7dp при радиусе safe zone 33dp.
FOREGROUND_LIMB_DP = 52
FOREGROUND_LIMB_FRAC = FOREGROUND_LIMB_DP / FOREGROUND_DP
assert FOREGROUND_LIMB_DP / 2 * HALO_SPAN <= SAFE_DP / 2, (
    "лимб foreground не влезает в safe zone: ореол срежется маской"
)
PLANET_LOGO_PX = 192         # логотип шапки; НЕ 96: MainActivity рисует его в 72dp
                             # (288 px на xxxhdpi), 96 px дал бы апскейл 3×. Экономия
                             # от 96 px — 8 КБ, цена — мыло на экране входа.
TV_BANNER_PX = (320, 180)

# ── форматы растровых ресурсов (волна 20.10: слив веса APK) ───────────────────
# Все крупные растры уезжают в WebP: платформа декодирует его нативно с API 14,
# а minSdk здесь 28, поэтому поддержка не обсуждается. PNG/JPEG остаются только
# там, где WebP запрещён форматом: .9.png (NinePatch) не конвертируется вообще —
# чанк npTc живёт только в PNG, WebP сломает растяжение фона.
#
# mars_bg.webp — ЕДИНСТВЕННЫЙ файл обоев: в репозитории лежали две байт-в-байт
# копии источника (mars_bg.jpg для окна и mars_splash_bg.jpg для заставки,
# md5 совпадал), то есть APK вёз один и тот же кадр дважды. Оба layer-list
# (mars_window_bg.xml и splash_window_bg.xml) ссылаются на @drawable/mars_bg;
# разница «полная яркость vs alpha 0.40» живёт в XML, а не в файле.
BG_ASSET = "mars_bg.webp"
BG_WEBP_QUALITY = 80         # 1080x2400: 184 452 B JPEG → ~36.7 КБ WebP, PSNR 46 dB
# 90, а не 80: планета — мелкий контрастный объект с резким лимбом, на q80 по
# краю диска проступает «звон». Альфа в lossy-WebP кодируется БЕЗ потерь, поэтому
# прозрачный ореол заставки от качества не страдает вовсе.
PLANET_LOGO_WEBP_QUALITY = 90
SPLASH_PLANET_WEBP_QUALITY = 90

# ── заставка (splash) ────────────────────────────────────────────────────────
# Планета заставки рендерится в НАТИВНОМ разрешении кропа keyed_planet
# (2*HALO_SPAN*r), без ресемплинга: множитель 1.0. Почему не 1080px, хотя
# экран POCO F5 — 2712x1220: источник favicon-512.png всего 512px, лимб в нём
# 179px, кроп 436px. Апскейл до 1080 размыл бы лимб и ореол (мыло вместо
# деталей), поэтому честный потолок — 436px, а масштабирование под экран
# делает View на устройстве. Значение > 1.0 запрещено assert'ом ниже.
SPLASH_PLANET_UPSCALE = 1.0

# Порог яркости фона источника (lum пикселя (7,7,9) ≈ 7.2) и яркость ореола,
# на которой альфа выходит в максимум (измерено на источнике вдоль лимба).
LUM_BG = 9.5
LUM_HALO = 52.0

FONT_CANDIDATES = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
)


def log(msg: str) -> None:
    print(msg, flush=True)


# ------------------------------------------------------------ работа с PNG

def png_bytes(img: Image.Image) -> bytes:
    """PNG детерминированно: без метаданных, фиксированная оптимизация."""
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def webp_bytes(img: Image.Image, *, quality: int) -> bytes:
    """WebP детерминированно: фиксированные quality/method, без метаданных.

    method=6 — самый медленный и самый плотный режим libwebp: генератор гоняется
    раз в жизни ассета, экономить на нём незачем. Альфа (если есть) остаётся
    lossless — это свойство формата, а не настройка.
    """
    buf = io.BytesIO()
    img.save(buf, format="WEBP", quality=quality, method=6)
    return buf.getvalue()


def ihdr_size(data: bytes) -> tuple[int, int]:
    """Размеры из IHDR (для отчёта/проверок без PIL)."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("не PNG")
    w = int.from_bytes(data[16:20], "big")
    h = int.from_bytes(data[20:24], "big")
    return w, h


# ------------------------------------------------------- анализ источника

def _lum(img: Image.Image) -> list[list[float]]:
    gray = img.convert("L")
    w, h = gray.size
    px = gray.load()
    return [[float(px[x, y]) for x in range(w)] for y in range(h)]


def _pixel_lum(img: Image.Image, x: int, y: int) -> float:
    """Яркость одного пикселя источника (опорный фон obsidian по углам)."""
    r, g, b = img.convert("RGB").getpixel((x, y))[:3]
    return 0.299 * r + 0.587 * g + 0.114 * b


def _ray_edge(lum, cx: float, cy: float, deg: int, r_min: int, r_max: int):
    """Радиус и сила максимального перепада яркости вдоль луча.

    Луч идёт из центра наружу; край диска — единственный крупный перепад на
    пути (атмосферный ореол снаружи, поверхность внутри). Возвращает
    (радиус, сила перепада) или None, если перепадов нет.

    Источник — НЕПРОЗРАЧНЫЙ рендер: тёмная сторона планеты почти так же
    черна, как вакуум, поэтому порог по яркости край не находит. Край ищется
    строго по градиенту вдоль луча, а не по абсолютной яркости.
    """
    cos_t, sin_t = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    radii = list(range(r_min, r_max))
    vals = []
    for r in radii:
        y = int(round(cy + r * sin_t))
        x = int(round(cx + r * cos_t))
        if y < 0 or x < 0 or y >= len(lum) or x >= len(lum[0]):
            return None
        vals.append(lum[y][x])
    if len(vals) < 2:
        return None
    diffs = [abs(vals[i + 1] - vals[i]) for i in range(len(vals) - 1)]
    i = max(range(len(diffs)), key=lambda k: diffs[k])
    return radii[i + 1], diffs[i]


def detect_geometry(img: Image.Image) -> tuple[float, float, float]:
    """Центр планеты и радиус лимба (px источника): медиана краёв по лучам.

    Лимб у сферического рендера равноудалён от центра, поэтому радиус ищется
    как медиана краёв по всем направлениям (медиана устойчива к лучам, где
    рельеф дал локальный перепад сильнее лимба), а центр — уточняется сдвигом:
    берётся смещение с наименьшим разбросом найденных радиусов. Так центр не
    зависит от яркости полушарий (у освещённой стороны центроид яркости уезжает).
    """
    lum = _lum(img)
    w, h = img.size
    r_min, r_max = int(min(w, h) * 0.12), int(min(w, h) * 0.47)
    degrees = list(range(0, 360, 5))
    best = None
    span = int(min(w, h) * 0.04)
    for oy in range(-span, span + 1, 2):
        for ox in range(-span, span + 1, 2):
            cx, cy = w / 2 + ox, h / 2 + oy
            rs = []
            for deg in degrees:
                found = _ray_edge(lum, cx, cy, deg, r_min, r_max)
                if found:
                    rs.append(found[0])
            if len(rs) < len(degrees) // 2:
                continue
            rs.sort()
            median = rs[len(rs) // 2]
            spread = rs[int(len(rs) * 0.75)] - rs[int(len(rs) * 0.25)]
            if best is None or spread < best[0]:
                best = (spread, median, cx, cy, len(rs))
    if best is None:
        raise SystemExit("не удалось найти лимб планеты в источнике")
    spread, r, cx, cy, n = best
    log(f"лимб: r={r}px центр=({cx:.0f},{cy:.0f}) по {n} лучам, разброс={spread}px")
    return cx, cy, float(r)


def keyed_planet(path: Path) -> tuple[Image.Image, dict]:
    """Планета с прозрачным фоном, вырезанная СИММЕТРИЧНО вокруг центра.

    Источник — непрозрачный рендер на фирменном фоне (obsidian), поэтому альфа
    строится по геометрии лимба, а не по цвету:

      * внутри лимба            — 255 (тёмное полушарие обязано остаться в
                                  силуэте: порог по яркости его бы вырезал);
      * от лимба до HALO_SPAN*R — рампа по яркости относительно фона: это
                                  атмосферный ореол и свечение лимба;
      * дальше                  — 0.

    Кроп симметричный (одинаковый отступ со всех сторон от центра лимба):
    `getbbox()` дал бы разные поля слева и справа — ореол ярче на освещённой
    стороне, и планета уехала бы из центра канвы иконки.
    """
    src = Image.open(path).convert("RGBA")
    cx, cy, r = detect_geometry(src)
    half = int(round(r * HALO_SPAN))
    left, top = int(round(cx - half)), int(round(cy - half))
    box = (left, top, left + 2 * half, top + 2 * half)
    frame = src.crop(box)
    # диагонали исходного кадра дают фон obsidian; берём его как опорную яркость
    bg_lum = min(
        _pixel_lum(src, 2, 2), _pixel_lum(src, src.size[0] - 3, 2),
        _pixel_lum(src, 2, src.size[1] - 3),
        _pixel_lum(src, src.size[0] - 3, src.size[1] - 3),
    )
    out = frame.copy()
    gray = frame.convert("L").load()
    px = out.load()
    w, h = frame.size
    fcx, fcy = cx - left, cy - top
    for y in range(h):
        for x in range(w):
            dx, dy = x - fcx, y - fcy
            d = (dx * dx + dy * dy) ** 0.5
            if d <= r:
                a = 255
            elif d <= half:
                t = (gray[x, y] - bg_lum) / (LUM_HALO - bg_lum)
                t = max(0.0, min(1.0, t))
                # Радиальное затухание: рампа по яркости не доходит до нуля на
                # рамке кадра (там ещё видно свечение), и без множителя альфа
                # обрывалась бы ступенькой — на канве оставалось бы жёсткое
                # кольцо-шов. Smoothstep гасит ореол ровно к границе кадра.
                u = (half - d) / (half - r)
                a = int(255 * t * (u * u * (3 - 2 * u)))
            else:
                a = 0
            if a == 0:
                px[x, y] = (0, 0, 0, 0)
            else:
                px[x, y] = (px[x, y][0], px[x, y][1], px[x, y][2], a)
    meta = {
        "cx": cx, "cy": cy, "r_limb": r,
        "crop": frame.size,
        "limb_frac": (2 * r) / frame.size[0],
    }
    return out, meta


# ------------------------------------------------------------- композиция

OBSIDIAN = (7, 8, 11, 255)   # #07080B — фон палитры Марса


def render_planet(art: Image.Image, meta: dict, *, canvas: tuple[int, int],
                  limb_px: float, bg: tuple[int, int, int, int] | None = None,
                  circle: bool = False) -> Image.Image:
    """Планета в масштабе, где лимб = limb_px, по центру канвы.

    bg — непрозрачная подложка (legacy-иконка); circle=True вырезает круг
    (legacy-иконка для круглых масок).
    """
    scale = limb_px / (meta["limb_frac"] * art.size[0])
    size = (max(1, round(art.size[0] * scale)), max(1, round(art.size[1] * scale)))
    planet = art.resize(size, Image.LANCZOS)
    if bg is None:
        out = Image.new("RGBA", canvas, (0, 0, 0, 0))
    else:
        out = Image.new("RGBA", canvas, bg)
        if circle:
            mask = Image.new("L", canvas, 0)
            ImageDraw.Draw(mask).ellipse((0, 0, canvas[0] - 1, canvas[1] - 1), fill=255)
            disc = Image.new("RGBA", canvas, bg)
            out = Image.composite(disc, Image.new("RGBA", canvas, (0, 0, 0, 0)), mask)
    out.alpha_composite(planet, ((canvas[0] - size[0]) // 2, (canvas[1] - size[1]) // 2))
    return out


def _font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if os.path.isfile(path):
            return ImageFont.truetype(path, size)
    raise SystemExit(
        "нет кириллического шрифта для баннера; ожидался один из: "
        + ", ".join(FONT_CANDIDATES)
    )


def russian_text(draw: ImageDraw.ImageDraw, text: str, *, font, x: int, y: int,
                 spacing: int, fill) -> None:
    cursor = x
    for ch in text:
        draw.text((cursor, y), ch, font=font, fill=fill)
        cursor += draw.textlength(ch, font=font) + spacing


def render_banner(art: Image.Image, meta: dict) -> Image.Image:
    """TV-баннер 320x180: рендер планеты + подпись «МАРС» (правило Play: без альфы)."""
    w, h = TV_BANNER_PX
    banner = Image.new("RGBA", (w, h), OBSIDIAN)
    # рендер в квадрате высоты баннера, прижат к левому краю
    planet = render_planet(art, meta, canvas=(h, h), limb_px=h * 0.62)
    banner.alpha_composite(planet, (6, 0))
    draw = ImageDraw.Draw(banner)
    # Шрифт подбирается по свободной ширине — подпись обязана влезть целиком.
    text_box = w - (h + 6) - 10
    size = 48
    while size > 16:
        font = _font(size)
        width = sum(draw.textlength(c, font=font) for c in "МАРС") + 3 * 6
        if width <= text_box:
            break
        size -= 2
    font = _font(size)
    russian_text(draw, "МАРС", font=font, x=h + 6 + 8, y=(h - size) // 2 - 2,
                 spacing=6, fill=(255, 255, 255, 255))
    return banner.convert("RGB")


# ------------------------------------------------------------------ план

def build_plan(src_dir: Path, root: Path) -> list[dict]:
    """Единый план генерации: [(relative path, bytes)] — общий для write/verify."""
    modules = [root / "app-mobile", root / "app-tv"]
    art, meta = keyed_planet(src_dir / PLANET_SRC)
    log(
        "источник: лимб r={r_limb:.1f}px центр=({cx:.0f},{cy:.0f}) "
        "кроп={crop} (лимб {limb_frac:.1%} кадра)".format(**meta)
    )

    bg_src = src_dir / BG_SRC
    # Источник — JPEG 1080x2400. Декодируем и кодируем в WebP один раз: файл
    # один на оба layer-list (см. BG_ASSET), альфа в фоне не нужна и не
    # допускается (RGB), иначе приглушение в mars_window_bg.xml считалось бы
    # дважды.
    with Image.open(bg_src) as im:
        bg_img = im.convert("RGB")
    bg_data = webp_bytes(bg_img, quality=BG_WEBP_QUALITY)
    logo = render_planet(art, meta, canvas=(PLANET_LOGO_PX, PLANET_LOGO_PX),
                         limb_px=PLANET_LOGO_PX * meta["limb_frac"])

    # Планета заставки: нативный кроп, ресемплинг не нужен (множитель 1.0).
    # art уже квадратный и отцентрован по лимбу, поэтому берём его как есть —
    # ореол и прозрачность вокруг лимба посчитаны в keyed_planet.
    assert SPLASH_PLANET_UPSCALE <= 1.0, "планета заставки не апскейлится выше источника"
    splash_px = int(round(art.size[0] * SPLASH_PLANET_UPSCALE))
    splash_planet = art.copy() if splash_px == art.size[0] else art.resize(
        (splash_px, splash_px), Image.LANCZOS)

    out: list[dict] = []
    for module in modules:
        nodpi = module / "src/main/res/drawable-nodpi"
        # Растровые ассеты шапки и заставки нужны только телефону. В TV нет ни
        # SplashActivity, ни шапки с планетой (фон — обсидиановая виньетка
        # bg_tv_root), поэтому nodpi там пуст: раньше mars_planet лежал и в TV,
        # где shrinkResources срезал его до 0-байтовой заглушки в APK.
        if module.name == "app-mobile":
            out.append({"module": module.name, "path": nodpi / BG_ASSET,
                        "data": bg_data, "what": "обои окна и фон заставки"})
            out.append({"module": module.name, "path": nodpi / "mars_planet.webp",
                        "data": webp_bytes(logo, quality=PLANET_LOGO_WEBP_QUALITY),
                        "what": "логотип-рендер"})
            out.append({"module": module.name,
                        "path": nodpi / "mars_splash_planet.webp",
                        "data": webp_bytes(splash_planet, quality=SPLASH_PLANET_WEBP_QUALITY),
                        "what": "планета заставки"})
        for density, px in DENSITIES.items():
            mip = module / "src/main/res" / f"mipmap-{density}"
            # legacy-иконка: весь рендер (с ореолом) занимает LEGACY_CONTENT_FRAC
            # кадра, значит лимб = LEGACY_CONTENT_FRAC * limb_frac * размер кадра.
            limb = px * LEGACY_CONTENT_FRAC * meta["limb_frac"]
            out.append({"module": module.name, "path": mip / "ic_launcher.png",
                        "data": png_bytes(render_planet(
                            art, meta, canvas=(px, px), limb_px=limb,
                            bg=OBSIDIAN)),
                        "what": f"legacy-иконка {density}"})
            out.append({"module": module.name, "path": mip / "ic_launcher_round.png",
                        "data": png_bytes(render_planet(
                            art, meta, canvas=(px, px), limb_px=limb,
                            bg=OBSIDIAN, circle=True)),
                        "what": f"legacy-иконка круглая {density}"})
            fg_px = round(px * FOREGROUND_DP / 48)
            out.append({"module": module.name,
                        "path": mip / "ic_launcher_foreground.png",
                        "data": png_bytes(render_planet(
                            art, meta, canvas=(fg_px, fg_px),
                            limb_px=fg_px * FOREGROUND_LIMB_FRAC)),
                        "what": f"foreground {density}"})
    tv = root / "app-tv/src/main/res/drawable-xhdpi/tv_banner_render.png"
    out.append({"module": "app-tv", "path": tv,
                "data": png_bytes(render_banner(art, meta)),
                "what": "TV-баннер 320x180"})
    return out


# ----------------------------------------------------------------- запуск

def find_src(explicit: str | None, root: Path) -> Path:
    if explicit:
        p = Path(explicit)
        if not (p / PLANET_SRC).is_file():
            raise SystemExit(f"в {p} нет {PLANET_SRC}")
        return p
    candidates = [
        Path(os.environ["MARS_ASSETS_SRC"]) if os.environ.get("MARS_ASSETS_SRC") else None,
        root.parent / "orchestrator/assets-src",
        # Рабочие деревья воркеров лежат в /root/workspace/<wave>, а источники —
        # в скрытом /root/workspace/.mars/orchestrator/assets-src. Без этого
        # кандидата `--verify` из worktree падал «не найден assets-src».
        root.parent / ".mars/orchestrator/assets-src",
        root.parent.parent / "orchestrator/assets-src",
        Path.home() / ".mars/orchestrator/assets-src",
    ]
    for cand in candidates:
        if cand and (cand / PLANET_SRC).is_file():
            return cand
    raise SystemExit(
        "не найден каталог assets-src (favicon-512.png). Укажи --src или MARS_ASSETS_SRC"
    )


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="генератор растровых ассетов Марса")
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="записать ресурсы в модули")
    mode.add_argument("--verify", action="store_true",
                      help="пересчитать и сверить с диском (идемпотентность)")
    ap.add_argument("--src", default=None, help="каталог assets-src")
    ap.add_argument("--root", default=None, help="корень репозитория (по умолчанию — родитель scripts/)")
    args = ap.parse_args(argv[1:])

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    src = find_src(args.src, root)
    plan = build_plan(src, root)

    drift = 0
    written = 0
    for item in plan:
        path: Path = item["path"]
        data: bytes = item["data"]
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.is_file() or path.read_bytes() != data:
                path.write_bytes(data)
                written += 1
        else:
            on_disk = path.read_bytes() if path.is_file() else None
            if on_disk != data:
                drift += 1
                log(f"РАСХОЖДЕНИЕ {path.relative_to(root)}: "
                    f"{'нет файла' if on_disk is None else 'другие байты'}")

    for item in plan:
        rel = item["path"].relative_to(root)
        digest = hashlib.sha256(item["data"]).hexdigest()[:12]
        if item["path"].suffix == ".png":
            w, h = ihdr_size(item["data"])
            dims = f"{w}x{h}"
        else:
            with Image.open(io.BytesIO(item["data"])) as im:
                dims = f"{im.size[0]}x{im.size[1]}"
        log(f"  {rel}  {dims}  {len(item['data'])}B  sha256:{digest}  ({item['what']})")

    if args.verify:
        log(f"проверено {len(plan)} файлов, расхождений {drift}")
        return 1 if drift else 0
    log(f"записано {written} из {len(plan)} файлов")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
