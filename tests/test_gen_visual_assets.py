#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Юнит-тесты scripts/gen-visual-assets.py — ассеты заставки (splash).

Проверяется контракт, на который опирается SplashActivity, а не «красивость»
картинки:
  * mars_splash_planet.webp — квадрат в НАТИВНОМ разрешении кропа (никакого
    апскейла выше источника), RGBA, углы прозрачны, центр непрозрачен,
    ореол полупрозрачный, заметно крупнее логотипа шапки;
  * mars_bg.webp — WebP q80 из источника: приглушение до alpha 0.40 живёт в
    layer-list окна, а не в файле, поэтому яркость полная, альфы нет, размеры
    источника сохранены;
  * фон ОДИН: оба layer-list (окно и заставка) ссылаются на @drawable/mars_bg,
    отдельного mars_splash_bg нет — раньше это были две байт-в-байт копии;
  * ассеты есть в ОБОИХ модулях (app-mobile и app-tv) — паритет целей;
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

from PIL import Image, ImageDraw, ImageStat

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(REPO, "scripts", "gen-visual-assets.py")

_spec = importlib.util.spec_from_file_location("gen_visual_assets", SCRIPT)
assert _spec is not None and _spec.loader is not None, "не нашёл %s" % SCRIPT
gen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen)

MODULES = ("app-mobile", "app-tv")
NODPI = os.path.join("src", "main", "res", "drawable-nodpi")
SPLASH_PLANET = "mars_splash_planet.webp"
BG = "mars_bg.webp"


def asset_size(data):
    """Размер растра из байтов: PNG читаем по IHDR, остальное — через PIL."""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return gen.ihdr_size(data)
    with Image.open(io.BytesIO(data)) as im:
        return im.size


def mean_lum(img):
    """Средняя яркость кадра — проверка «фон не приглушён в файле»."""
    return sum(ImageStat.Stat(img.convert("L")).mean) / 1.0


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

    def test_splash_assets_where_they_are_used(self):
        """Все растры шапки/заставки — только в app-mobile: в TV их никто не рисует.

        В TV нет SplashActivity и шапки с планетой, а shrinkResources срезал
        mars_planet до 0-байтовой заглушки — то есть файл был мёртвым.
        """
        for name in (SPLASH_PLANET, BG, "mars_planet.webp"):
            self.assertEqual(plan_item(self.plan, "app-mobile", name)["module"],
                             "app-mobile")
            self.assertNotIn(os.path.join("app-tv", NODPI, name),
                             [os.path.relpath(i["path"], Path(self.src) / "repo")
                              for i in self.plan])

    def test_single_bg_asset_in_plan(self):
        """Дубль фона слит: в плане ровно один файл обоев, splash-копии нет."""
        names = [i["path"].name for i in self.plan if i["module"] == "app-mobile"]
        self.assertEqual(sum(1 for n in names if n.startswith("mars_bg")), 1,
                         "обоев должно быть ровно один файл: %s" % names)
        self.assertNotIn("mars_splash_bg.jpg", names)
        self.assertNotIn("mars_bg.jpg", names)

    def test_planet_is_native_crop_not_upscaled(self):
        """Планета заставки = 2*HALO_SPAN*r (нативный кроп), не больше источника."""
        with Image.open(os.path.join(self.src, gen.PLANET_SRC)) as src:
            r = gen.detect_geometry(src)[2]
            src_size = src.size
        item = plan_item(self.plan, "app-mobile", SPLASH_PLANET)
        w, h = asset_size(item["data"])
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

    def test_bg_is_webp_q80_of_source(self):
        """Фон — WebP q80 из источника: без альфы, размеры и яркость источника.

        Раньше здесь проверялась байт-в-байт копия источника. Контракт сменён
        намеренно (волна 20.10, слив веса APK): копия стоила 184 452 B, а WebP
        q80 того же кадра — ~36.7 КБ при PSNR 46 dB, то есть без видимой
        разницы на градиенте. Яркость обязана остаться полной: приглушение до
        alpha 0.40 живёт в layer-list окна, поэтому сравниваем среднюю яркость
        файла с источником, а не байты.
        """
        with open(os.path.join(self.src, gen.BG_SRC), "rb") as fh:
            src_bytes = fh.read()
        item = plan_item(self.plan, "app-mobile", BG)
        self.assertLess(len(item["data"]), len(src_bytes) // 3,
                        "WebP q80 обязан быть втрое легче исходного JPEG")
        with Image.open(io.BytesIO(item["data"])) as im:
            self.assertEqual(im.format, "WEBP")
            self.assertEqual(im.mode, "RGB", "в фоне заставки не должно быть альфы")
            with Image.open(os.path.join(self.src, gen.BG_SRC)) as src:
                self.assertEqual(im.size, src.size, "пропорции/размер источника потеряны")
                self.assertAlmostEqual(mean_lum(im), mean_lum(src), delta=1.5,
                                       msg="фон приглушён в файле, а не в layer-list")


class TestCommittedSplashAssets(unittest.TestCase):
    """Уже сгенерированные и закоммиченные ассеты заставки в обоих модулях."""

    def test_files_exist_where_used(self):
        for name in ("mars_planet.webp", SPLASH_PLANET, BG):
            p = module_path(REPO, "app-mobile", name)
            self.assertTrue(os.path.isfile(p), "нет файла %s" % p)
            self.assertFalse(os.path.isfile(module_path(REPO, "app-tv", name)),
                             "TV не рисует %s — файл там мёртвый" % name)

    def test_planet_larger_than_header_logo(self):
        """Планета заставки заметно крупнее логотипа шапки (192px)."""
        with Image.open(module_path(REPO, "app-mobile", SPLASH_PLANET)) as splash:
            with Image.open(module_path(REPO, "app-mobile", "mars_planet.webp")) as logo:
                self.assertGreater(splash.size[0], logo.size[0] * 2)
                self.assertEqual(splash.size[0], splash.size[1])
                self.assertEqual(splash.mode, "RGBA")
                w, h = splash.size
                for xy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
                    self.assertEqual(splash.getpixel(xy)[3], 0, "угол %s не прозрачен" % (xy,))
                self.assertEqual(splash.getpixel((w // 2, h // 2))[3], 255)

    def test_bg_full_resolution_no_alpha(self):
        """Фон: WebP без альфы, размеры как у источника 1080x2400."""
        with Image.open(module_path(REPO, "app-mobile", BG)) as im:
            self.assertEqual(im.format, "WEBP")
            self.assertEqual(im.mode, "RGB")
            self.assertEqual(im.size, (1080, 2400))

    def test_single_bg_resource_shared_by_both_layers(self):
        """Оба layer-list ссылаются на @drawable/mars_bg; второго файла нет.

        Это и есть проверка «дубль слит»: раньше mars_bg.jpg и mars_splash_bg.jpg
        были байт-в-байт копиями (md5 a7015cdd…), то есть APK вёз один кадр
        дважды. Яркость/приглушение задаются в XML, а не вторым файлом.
        """
        res = os.path.join(REPO, "app-mobile", "src", "main", "res")
        for layer in ("mars_window_bg.xml", "splash_window_bg.xml"):
            path = os.path.join(res, "drawable", layer)
            with open(path, encoding="utf-8") as fh:
                body = fh.read()
            self.assertIn("@drawable/mars_bg", body,
                          "%s не ссылается на общий фон" % layer)
            self.assertNotIn("@drawable/mars_splash_bg", body,
                             "%s всё ещё ссылается на удалённый дубль" % layer)
        nodpi = os.path.join(res, "drawable-nodpi")
        bgs = sorted(f for f in os.listdir(nodpi) if f.startswith("mars_bg"))
        self.assertEqual(bgs, [gen.BG_ASSET], "дубль фона вернулся: %s" % bgs)

    def test_nodpi_holds_only_webp(self):
        """В drawable-nodpi не осталось .jpg/.png: крупные растры уехали в WebP.

        .9.png (NinePatch) сюда не попадает — они лежат в drawable/, где
        конвертация запрещена: чанк npTc есть только в PNG.
        """
        nodpi = os.path.join(REPO, "app-mobile", "src", "main", "res", "drawable-nodpi")
        for name in sorted(os.listdir(nodpi)):
            self.assertTrue(name.endswith(".webp"),
                            "не WebP в drawable-nodpi: %s" % name)


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
        self.assertIn(os.path.join("app-mobile", NODPI, BG), first)
        self.assertNotIn(os.path.join("app-tv", NODPI, BG), first)
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
