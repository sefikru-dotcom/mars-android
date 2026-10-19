#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Юнит-тесты scripts/theme-parity-audit.py — чистый stdlib, БЕЗ android-sdk.

Фиксируют бан-листы дизайн-системы: хардкод-цвета, именованные цвета Android
(`Color.WHITE`/`Color.BLACK`), запрещённые глифы и декоративную позицию
разделителя `·`. Легальный инлайн-разделитель («42% · 84k/200k») не должен
считаться находкой — иначе гейт превращается в шум и его отключают.

Запуск:  python3 tests/test_theme_parity_audit.py
"""

import importlib.util
import os
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(REPO, "scripts", "theme-parity-audit.py")

_spec = importlib.util.spec_from_file_location("theme_parity_audit", SCRIPT)
assert _spec is not None and _spec.loader is not None, "не нашёл %s" % SCRIPT
tpa = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tpa)


class GateTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="theme-parity-")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def write(self, name, body):
        path = os.path.join(self.root, name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(body)

    def run_gate(self):
        out = StringIO()
        with redirect_stdout(out):
            code = tpa.main(["theme-parity-audit.py", self.root])
        return code, out.getvalue()

    # --- цвета -------------------------------------------------------------

    def test_hex_hardcode_outside_palette_fails(self):
        self.write("Screen.kt", 'val c = 0xff10131b\n')
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("[цвет]", out)

    def test_named_white_is_banned_outside_palette(self):
        self.write("Screen.kt", "view.setTextColor(Color.WHITE)\n")
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("Color.WHITE", out)

    def test_palette_file_may_declare_colors(self):
        self.write("MarsTheme.kt", "const val TEXT_STRONG = 0xffffffff\nval m = Color.WHITE\n")
        code, out = self.run_gate()
        self.assertEqual(0, code, out)

    def test_transparent_is_not_a_hardcoded_shade(self):
        self.write("Screen.kt", "val bg = Color.TRANSPARENT\n")
        code, _ = self.run_gate()
        self.assertEqual(0, code)

    # --- глифы -------------------------------------------------------------

    def test_bullet_glyph_is_banned(self):
        self.write("Screen.kt", 'ssb.append("•  ")\n')
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("[глиф", out)

    def test_leading_separator_is_decoration(self):
        self.write("Screen.kt", 'btn.text = "· Авто"\n')
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("[глиф-декорация]", out)

    def test_trailing_separator_is_decoration(self):
        self.write("Screen.kt", 'label.text = "Авто ·"\n')
        code, out = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("[глиф-декорация]", out)

    def test_inline_separator_stays_legal(self):
        self.write("Screen.kt", 'val s = "42% · 84k/200k"\nval d = " · поток остановлен"\n')
        code, out = self.run_gate()
        self.assertEqual(0, code, out)

    def test_pure_separator_literal_stays_legal(self):
        self.write("Screen.kt", 'val sep = " · "\nval j = list.joinToString("  ·  ")\n')
        code, out = self.run_gate()
        self.assertEqual(0, code, out)

    def test_comment_explaining_the_glyph_is_not_a_finding(self):
        self.write("Screen.kt", '// было: "· Авто" — потерянная иконка\nval x = 1\n')
        code, out = self.run_gate()
        self.assertEqual(0, code, out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
