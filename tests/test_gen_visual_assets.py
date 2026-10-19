#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Юнит-тесты scripts/gen-visual-assets.py — ассеты заставки (splash).

Проверяется контракт, на который опирается SplashActivity, а не «красивость»
картинки:
  * mars_splash_planet.png — квадрат в НАТИВНОМ разрешении кропа (никакого
    апскейла выше источника), RGBA, углы прозрачны, центр непрозрачен,
    ореол полупрозрачный, заметно крупнее логотипа шапки;
  * mars_splash_bg.jpg — байт-в-байт копия источника (приглушение до alpha
    0.40 живёт в layer-list окна, а не в файле), пропорции сохранены;
  * оба ресурса есть в ОБОИХ модулях (app-mobile и app-tv) — паритет целей;
  * --write идемпотентен (повторные байты те же), --verify ловит подмену.

Часть тестов герметична: источник рисуется тут же (диск на обсидиане), потому
что assets-src/ лежит вне git. Тесты на уже сгенерированные файлы читают
ресурсы репозитория и в сети/источниках не нуждаются.

Запуск:  python3 tests/test_gen_visual_assets.py
"""

import hashlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(REPO, "scripts", "gen-visual-assets.py")

_spec = importlib.util.spec_from_file_location("gen_visual_assets", SCRIPT)
assert _spec is not None and _spec.loader is not None, "не нашёл %s" % SCRIPT
gen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen)

MODULES = ("app-mobile", "app-tv")
NODPI = os.path.join("src", "main", "res", "drawable-nodpi")
SPLASH_PLANET = "mars_splash_planet.png"
SPLASH_BG = "mars_splash_bg.jpg"


def module_path(root, module, name):
    return os.path.join(root, module, NODPI, name)


def synthetic_src(dirpath, *, size=256, radius=90):
    """Источник-заглушка: яркий диск на обсидиане, как рендер планеты.

    Радиус 90 при кадре 256 держит кроп 2*1.22*r = 220px внутри источника —
    ровно та же геометрия, что у favicon-512.png (r=179, кроп 436 при 512).
    """
    img = Image.new("RGB", (size, size), (7, 8, 11))
    cx = cy = size // 2
    draw = ImageDraw.Draw(img)
    draw.ellipse(
        (cx - radius, cy - radius, cx + radius, cy + radius), fill=(181, 50, 28)
    )
    # Ореол снаружи лимба: без него альфа-рампа keyed_planet не проверится —
    # в реальном рендере вокруг диска есть затухающее свечение.
    for rr in range(radius + 1, int(radius * gen.HALO_SPAN) + 1):
        v = 30.0 * (1.0 - (rr - radius) / (radius * (gen.HALO_SPAN - 1.0)))
        v = max(v, 9.0)
        draw.ellipse((cx - rr, cy - rr, cx + rr, cy + rr),
                     outline=(int(v), int(v * 0.45), int(v * 0.30)))
    os.makedirs(dirpath, exist_ok=True)
    png = os.path.join(dirpath, gen.PLANET_SRC)
    img.save(png, format="PNG")
    bg = Image.new("RGB", (180, 400), (24, 10, 14))
    ImageDraw.Draw(bg).rectangle((0, 300, 179, 399), fill=(120, 40, 30))
    bg.save(os.path.join(dirpath, gen.BG_SRC), format="JPEG", quality=90)
    return dirpath


def plan_item(plan, module, name):
    """Элемент плана по модулю и имени файла; падает, если его нет."""
    for item in plan:
        if item["module"] == module and item["path"].name == name:
            return item
    raise AssertionError("в плане нет %s/%s" % (module, name))


def file_hashes(root):
    out = {}
    for base, _dirs, files in os.walk(root):
        for f in files:
            p = os.path.join(base, f)
            with open(p, "rb") as fh:
                out[os.path.relpath(p, root)] = hashlib.sha256(fh.read()).hexdigest()
    return out


class TestSplashPlanHermetic(unittest.TestCase):
    """План генерации на синтетическом источнике: геометрия и байты."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.src = synthetic_src(cls.tmp.name)
        cls.plan = gen.build_plan(Path(cls.src), Path(cls.tmp.name) / "repo")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_splash_assets_in_both_modules(self):
        """Заставка генерируется в app-mobile И app-tv — паритет целей."""
        for module in MODULES:
            for name in (SPLASH_PLANET, SPLASH_BG):
                self.assertEqual(plan_item(self.plan, module, name)["module"], module)

    def test_planet_is_native_crop_not_upscaled(self):
        """Планета заставки = 2*HALO_SPAN*r (нативный кроп), не больше источника."""
        with Image.open(os.path.join(self.src, gen.PLANET_SRC)) as src:
            r = gen.detect_geometry(src)[2]
            src_size = src.size
        item = plan_item(self.plan, "app-mobile", SPLASH_PLANET)
        w, h = gen.ihdr_size(item["data"])
        self.assertEqual(w, h, "планета заставки обязана быть квадратной")
        self.assertEqual(w, 2 * round(gen.HALO_SPAN * r),
                         "размер должен быть нативным кропом, а не выдуманным числом")
        self.assertLessEqual(w, min(src_size), "апскейл выше источника запрещён")

    def test_planet_rgba_transparent_corners_opaque_center(self):
        """PNG с альфой: углы прозрачны, центр непрозрачен, ореол полупрозрачный."""
        item = plan_item(self.plan, "app-mobile", SPLASH_PLANET)
        with Image.open(io.BytesIO(item["data"])) as im:
            self.assertEqual(im.mode, "RGBA")
            w, h = im.size
            for xy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
                self.assertEqual(im.getpixel(xy)[3], 0, "угол %s не прозрачен" % (xy,))
            self.assertEqual(im.getpixel((w // 2, h // 2))[3], 255,
                             "центр планеты должен быть непрозрачным")
            alphas = im.getchannel("A").histogram()
        halo = sum(alphas[1:255])
        self.assertGreater(halo, 0, "нет полупрозрачного ореола вокруг лимба")
        self.assertGreater(alphas[0], 0, "нет прозрачной зоны вокруг лимба")

    def test_bg_is_source_copy_full_brightness(self):
        """Фон заставки — копия источника байт-в-байт: без перекодирования и альфы."""
        with open(os.path.join(self.src, gen.BG_SRC), "rb") as fh:
            src_bytes = fh.read()
        item = plan_item(self.plan, "app-mobile", SPLASH_BG)
        self.assertEqual(item["data"], src_bytes, "фон заставки изменён (приглушён?)")
        with Image.open(io.BytesIO(item["data"])) as im:
            self.assertEqual(im.format, "JPEG")
            self.assertEqual(im.mode, "RGB", "в фоне заставки не должно быть альфы")
            with Image.open(os.path.join(self.src, gen.BG_SRC)) as src:
                self.assertEqual(im.size, src.size, "пропорции/размер источника потеряны")


class TestCommittedSplashAssets(unittest.TestCase):
    """Уже сгенерированные и закоммиченные ассеты заставки в обоих модулях."""

    def test_files_exist_in_both_modules(self):
        for module in MODULES:
            for name in (SPLASH_PLANET, SPLASH_BG):
                p = module_path(REPO, module, name)
                self.assertTrue(os.path.isfile(p), "нет файла %s" % p)

    def test_planet_larger_than_header_logo(self):
        """Планета заставки заметно крупнее логотипа шапки (192px)."""
        with Image.open(module_path(REPO, "app-mobile", SPLASH_PLANET)) as splash:
            with Image.open(module_path(REPO, "app-mobile", "mars_planet.png")) as logo:
                self.assertGreater(splash.size[0], logo.size[0] * 2)
                self.assertEqual(splash.size[0], splash.size[1])
                self.assertEqual(splash.mode, "RGBA")
                w, h = splash.size
                for xy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
                    self.assertEqual(splash.getpixel(xy)[3], 0, "угол %s не прозрачен" % (xy,))
                self.assertEqual(splash.getpixel((w // 2, h // 2))[3], 255)

    def test_bg_full_resolution_no_alpha(self):
        """Фон заставки: JPEG без альфы, размеры как у источника 1080x2400."""
        with Image.open(module_path(REPO, "app-mobile", SPLASH_BG)) as im:
            self.assertEqual(im.format, "JPEG")
            self.assertEqual(im.mode, "RGB")
            self.assertEqual(im.size, (1080, 2400))

    def test_bg_not_dimmed_relative_to_window_wallpaper(self):
        """Байты фона заставки совпадают с обоями окна: приглушение — в layer-list."""
        with open(module_path(REPO, "app-mobile", SPLASH_BG), "rb") as fh:
            splash = fh.read()
        with open(module_path(REPO, "app-mobile", "mars_bg.jpg"), "rb") as fh:
            wall = fh.read()
        self.assertEqual(splash, wall, "фон заставки отличается от источника яркости")


class TestWriteVerifyIdempotency(unittest.TestCase):
    """--write дважды даёт те же байты, --verify пересчитывает и ловит подмену."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = os.path.join(self.tmp.name, "repo")
        self.src = synthetic_src(os.path.join(self.tmp.name, "assets"))

    def run_main(self, *args):
        argv = ["gen-visual-assets.py", "--src", self.src, "--root", self.root]
        argv.extend(args)
        return gen.main(argv)

    def test_write_is_idempotent(self):
        self.assertEqual(self.run_main("--write"), 0)
        first = file_hashes(self.root)
        self.assertIn(os.path.join("app-mobile", NODPI, SPLASH_PLANET), first)
        self.assertIn(os.path.join("app-tv", NODPI, SPLASH_BG), first)
        self.assertEqual(self.run_main("--write"), 0)
        self.assertEqual(first, file_hashes(self.root), "повторный --write изменил байты")
        self.assertEqual(self.run_main("--verify"), 0)

    def test_verify_detects_tampered_splash_asset(self):
        self.assertEqual(self.run_main("--write"), 0)
        target = module_path(self.root, "app-mobile", SPLASH_PLANET)
        with open(target, "ab") as fh:
            fh.write(b"\x00")
        self.assertEqual(self.run_main("--verify"), 1, "--verify не заметил подмену")


if __name__ == "__main__":
    unittest.main(verbosity=2)
