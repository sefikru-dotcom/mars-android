#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Юнит-тесты scripts/apk-contract-check.py — чистый stdlib, БЕЗ android-sdk.

Фейковые «APK» — обычные zip с подставными classes*.dex, где имена .kt
зашиты так же, как в реальной dex-таблице строк (имя + \\x00).

Запуск:  python3 tests/test_apk_contract_check.py
"""

import importlib.util
import io
import os
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(REPO, "scripts", "apk-contract-check.py")

_spec = importlib.util.spec_from_file_location("apk_contract_check", SCRIPT)
assert _spec is not None and _spec.loader is not None, "не нашёл %s" % SCRIPT
acc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(acc)


def make_dex(kt_count, prefix="F"):
    """Байты фейкового dex с kt_count исходными именами вида F0001.kt\\x00."""
    head = b"dex\n035\x00" + b"\x00" * 32  # псевдо-заголовок, парсер его не читает
    names = b"".join(
        ("%s%04d.kt" % (prefix, i)).encode() + b"\x00" for i in range(kt_count)
    )
    return head + names


def make_apk(path, dex_entries):
    """dex_entries: dict {имя в zip: байты} или None — zip без dex."""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", b"<manifest/>")
        if dex_entries:
            for name, data in dex_entries.items():
                z.writestr(name, data)


def run_main(*argv):
    """Вызывает main() скрипта, возвращает (rc, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        rc = acc.main(list(argv))
    return rc, out.getvalue(), err.getvalue()


class ApkContractTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = self._tmp.name
        self.addCleanup(self._tmp.cleanup)

    def apk(self, name="test.apk"):
        return os.path.join(self.tmp, name)


class TestMinification(ApkContractTestCase):
    def test_r8_version_passes(self):
        """14 имён .kt (эталон R8) — контракт выполнен, rc=0."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(14)})
        rc, out, _ = run_main(p)
        self.assertEqual(rc, 0)
        self.assertIn("R8 OK", out)

    def test_no_r8_fails(self):
        """1288 имён .kt (эталон без R8) — провал контракта, rc=1."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(1288)})
        rc, _, err = run_main(p)
        self.assertEqual(rc, 1)
        self.assertIn("R8 НЕ применён", err)
        self.assertIn("1288", err)

    def test_threshold_boundary_below_passes(self):
        """Граница: 99 имён (< порога 100) — проходит."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(99)})
        rc, _, _ = run_main(p)
        self.assertEqual(rc, 0)

    def test_threshold_boundary_at_fails(self):
        """Граница: ровно 100 имён (>= порога) — провал."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(100)})
        rc, _, err = run_main(p)
        self.assertEqual(rc, 1)
        self.assertIn("R8 НЕ применён", err)

    def test_custom_threshold(self):
        """--max-kt-names меняет порог."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(50)})
        self.assertEqual(run_main(p, "--max-kt-names", "100")[0], 0)
        self.assertEqual(run_main(p, "--max-kt-names", "50")[0], 1)
        self.assertEqual(run_main(p, "--max-kt-names", "51")[0], 0)

    def test_multidex_counted_together(self):
        """Считаются все classes*.dex вместе: 60+60=120 >= 100 — провал."""
        p = self.apk()
        make_apk(p, {
            "classes.dex": make_dex(60, prefix="A"),
            "classes2.dex": make_dex(60, prefix="B"),
        })
        rc, _, err = run_main(p)
        self.assertEqual(rc, 1)
        self.assertIn("120", err)


class TestBadInput(ApkContractTestCase):
    def test_missing_file(self):
        rc, _, err = run_main(self.apk("nope.apk"))
        self.assertEqual(rc, 2)
        self.assertIn("не найден", err)

    def test_no_dex_inside(self):
        """APK без dex — понятная ошибка входа, rc=2 (не контракт)."""
        p = self.apk()
        make_apk(p, None)
        rc, _, err = run_main(p)
        self.assertEqual(rc, 2)
        self.assertIn("нет ни одного .dex", err)

    def test_corrupt_zip(self):
        """Битый zip — понятная ошибка входа, rc=2."""
        p = self.apk()
        with open(p, "wb") as f:
            f.write(os.urandom(4096))
        rc, _, err = run_main(p)
        self.assertEqual(rc, 2)
        self.assertIn("zip", err.lower())

    def test_truncated_zip(self):
        """Обрезанный zip (валидное начало, рваный конец) — rc=2, не rc=1."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(5)})
        with open(p, "r+b") as f:
            f.truncate(os.path.getsize(p) // 2)
        rc, _, err = run_main(p)
        self.assertEqual(rc, 2)
        self.assertTrue(err.strip())


class TestSigningSchemeParsing(ApkContractTestCase):
    """Разбор вывода apksigner verify --verbose — без запуска apksigner."""

    V3_ONLY = """Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v4 scheme (APK Signature Scheme v4): false
Number of signers: 1
"""
    LEGACY = """Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
"""
    NO_V3 = """Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: 1
"""

    def test_parse_v3_only(self):
        s = acc.parse_signing_schemes(self.V3_ONLY)
        self.assertEqual(s, {"1": False, "2": False, "3": True, "4": False})

    def test_parse_empty_output(self):
        self.assertEqual(acc.parse_signing_schemes("Verifies\n"), {})

    def _check_with_output(self, verbose_output, rc=0):
        """Подменяет subprocess.run, чтобы проверить логику схемы без SDK."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(14)})

        class R:
            returncode = rc
            stdout = verbose_output
            stderr = ""

        import subprocess as sp
        orig_run = sp.run
        sp.run = lambda *a, **k: R()
        try:
            return acc.check_signing_scheme_v3_only(p, apksigner="/bin/true")
        finally:
            sp.run = orig_run

    def test_v3_only_passes(self):
        report = self._check_with_output(self.V3_ONLY)
        self.assertIn("v3-only", report)

    def test_legacy_v1_v2_rejected(self):
        with self.assertRaises(acc.CheckError) as ctx:
            self._check_with_output(self.LEGACY)
        self.assertEqual(ctx.exception.code, 1)
        self.assertIn("устаревшие схемы", ctx.exception.message)

    def test_missing_v3_rejected(self):
        with self.assertRaises(acc.CheckError) as ctx:
            self._check_with_output(self.NO_V3)
        self.assertEqual(ctx.exception.code, 1)
        self.assertIn("НЕ подписан по схеме v3", ctx.exception.message)

    def test_unparsable_output_rejected(self):
        with self.assertRaises(acc.CheckError) as ctx:
            self._check_with_output("Verifies\n")
        self.assertEqual(ctx.exception.code, 1)
        self.assertIn("не удалось разобрать", ctx.exception.message)

    def test_apksigner_verify_failure_is_contract_fail(self):
        with self.assertRaises(acc.CheckError) as ctx:
            self._check_with_output("", rc=1)
        self.assertEqual(ctx.exception.code, 1)
        self.assertIn("apksigner verify не прошёл", ctx.exception.message)


class TestCliDefaults(ApkContractTestCase):
    def test_signing_skip_by_default(self):
        """Без --signing-scheme подпись не проверяется (unsigned-ветка CI)."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(14)})
        rc, out, _ = run_main(p)
        self.assertEqual(rc, 0)
        self.assertIn("пропущена", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
