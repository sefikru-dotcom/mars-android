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
import hashlib
import os
import shutil
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


def make_dex(kt_count, prefix="F", reflective=True):
    """Байты фейкового dex с kt_count исходными именами вида F0001.kt\\x00.

    reflective=True добавляет в пул строк имена классов рефлексионного графа Moshi
    в dex-форме (`Lru/mars/network/model/MusicTrack;`), как в настоящем R8-выходе:
    без них проверка check_reflective_models справедливо провалит артефакт.
    """
    head = b"dex\n035\x00" + b"\x00" * 32  # псевдо-заголовок, парсер его не читает
    names = b"".join(
        ("%s%04d.kt" % (prefix, i)).encode() + b"\x00" for i in range(kt_count)
    )
    if reflective:
        names += b"".join(
            ("L" + c.replace(".", "/") + ";").encode() + b"\x00"
            for c in acc.MOSHI_REFLECTIVE_CLASSES
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


class TestReflectiveGraph(ApkContractTestCase):
    """Проверка сохранения имён рефлексионного графа Moshi в release-APK."""

    def test_renamed_reflective_class_fails(self):
        """R8 переименовал класс рефлексионного графа — контракт провален (rc=1)."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(14, reflective=False)})
        rc, _, err = run_main(p)
        self.assertEqual(rc, 1)
        self.assertIn("Рефлексионные классы Moshi", err)
        # Сообщение обязано подсказать готовое правило для proguard-rules.pro.
        self.assertIn("-keep class ru.mars.network.model.MusicTrack { *; }", err)

    def test_partial_rename_fails_and_names_only_missing(self):
        """Половина графа переименована — в ошибке только отсутствующие классы."""
        p = self.apk()
        head = b"dex\n035\x00" + b"\x00" * 32
        kept = acc.MOSHI_REFLECTIVE_CLASSES[:2]
        body = b"".join(("F%04d.kt" % i).encode() + b"\x00" for i in range(14))
        body += b"".join(
            ("L" + c.replace(".", "/") + ";").encode() + b"\x00" for c in kept
        )
        make_apk(p, {"classes.dex": head + body})
        rc, _, err = run_main(p)
        self.assertEqual(rc, 1)
        self.assertIn("ru.mars.network.model.LyricsResult", err)

    def test_skip_reflective_flag(self):
        """--skip-reflective отключает проверку (для не-mobile артефактов)."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(14, reflective=False)})
        rc, out, _ = run_main(p, "--skip-reflective")
        self.assertEqual(rc, 0)
        self.assertIn("пропущен", out)

    def test_check_function_direct(self):
        """check_reflective_models возвращает отчёт при полном графе."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(14)})
        self.assertIn("Moshi OK", acc.check_reflective_models(p))


class TestCliDefaults(ApkContractTestCase):
    def test_signing_skip_by_default(self):
        """Без --signing-scheme подпись не проверяется (unsigned-ветка CI)."""
        p = self.apk()
        make_apk(p, {"classes.dex": make_dex(14)})
        rc, out, _ = run_main(p)
        self.assertEqual(rc, 0)
        self.assertIn("пропущена", out)


# ---------------------------------------------------------------------------
# scripts/apk-cert-pin-check.py — пин отпечатка сертификата подписи
# ---------------------------------------------------------------------------
#
# Боевой ключ Марса — это debug-сертификат Android (DN «C=US, O=Android,
# CN=Android Debug»), поэтому единственный надёжный признак «наш ключ» —
# SHA-256 отпечаток сертификата. Тесты фиксируют: пин решает, текст DN — нет.

PIN_SCRIPT = os.path.join(REPO, "scripts", "apk-cert-pin-check.py")
_pin_spec = importlib.util.spec_from_file_location("apk_cert_pin_check", PIN_SCRIPT)
assert _pin_spec is not None and _pin_spec.loader is not None, "не нашёл %s" % PIN_SCRIPT
pin = importlib.util.module_from_spec(_pin_spec)
_pin_spec.loader.exec_module(pin)

MARS_PIN = "15da3b0219edc2b82a7ae20c73b18497d9034e4c745d842390451b0cb7f3d311"
MARS_DN = "C=US, O=Android, CN=Android Debug"
# Отпечаток другого ключа (эфемерный debug-ключ раннера / чужая сборка).
FOREIGN_PIN = "3f9a1c0d5b7e24680ace13579bdf02468ace13579bdf02468ace13579bdf0246"


class _FakeProc:
    def __init__(self, stdout="", stderr="", returncode=0):
        self.stdout = stdout
        self.stderr = stderr
        self.returncode = returncode


def apksigner_text(digest=MARS_PIN, dn=MARS_DN, verifies=True, signers=1):
    """Реалистичный stdout `apksigner verify --verbose --print-certs`."""
    lines = [
        "Verifies" if verifies else "DOES NOT VERIFY",
        "Verified using v1 scheme (JAR signing): false",
        "Verified using v2 scheme (APK Signature Scheme v2): false",
        "Verified using v3 scheme (APK Signature Scheme v3): true",
        "Verified using v4 scheme (APK Signature Scheme v4): false",
        "Number of signers: %d" % signers,
    ]
    for i in range(1, signers + 1):
        lines += [
            "Signer #%d certificate DN: %s" % (i, dn),
            "Signer #%d certificate SHA-256 digest: %s" % (i, digest),
            "Signer #%d certificate SHA-1 digest: 602a6561bb73eb8d21fa0216916069d7111eb2e4" % i,
        ]
    return "\n".join(lines) + "\n"


class CertPinTestCase(unittest.TestCase):
    """База: временный APK + подмена вывода apksigner."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mars-pin-test-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.apk = os.path.join(self.tmp, "mars-mobile.apk")
        with open(self.apk, "wb") as f:
            f.write(b"PK\x03\x04 fake apk payload")
        # Тесты не должны зависеть от окружения прогона.
        self._env = os.environ.pop(pin.ENV_EXPECTED, None)
        self.addCleanup(self._restore_env)

    def _restore_env(self):
        os.environ.pop(pin.ENV_EXPECTED, None)
        if self._env is not None:
            os.environ[pin.ENV_EXPECTED] = self._env

    def fake_apksigner(self, text):
        """Подменяет subprocess.run в модуле пина на фиксированный вывод."""
        real = pin.subprocess.run
        pin.subprocess.run = lambda *a, **k: _FakeProc(text)
        self.addCleanup(setattr, pin.subprocess, "run", real)

    def run_pin(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = pin.main(list(argv))
        return rc, out.getvalue(), err.getvalue()


class TestCertPinMatch(CertPinTestCase):
    def test_mars_pin_matches(self):
        """Наш отпечаток совпадает с пином -> PASS, хотя DN — Android Debug."""
        self.fake_apksigner(apksigner_text())
        rc, out, err = self.run_pin(self.apk, "--expected", MARS_PIN,
                                    "--apksigner", sys.executable)
        self.assertEqual(rc, 0, err)
        self.assertIn("PASS", out)
        self.assertIn(MARS_PIN, out)
        self.assertIn("CN=Android Debug", out)  # DN в отчёте, но он не решает

    def test_pin_from_env(self):
        """Пин читается из MARS_EXPECTED_CERT_SHA256 (workflow-level env)."""
        os.environ[pin.ENV_EXPECTED] = MARS_PIN
        self.fake_apksigner(apksigner_text())
        rc, out, _ = self.run_pin(self.apk, "--apksigner", sys.executable)
        self.assertEqual(rc, 0)
        self.assertIn("PASS", out)

    def test_normalization_colons_and_case(self):
        """Двоеточия и верхний регистр в пине не ломают сравнение."""
        pretty = ":".join(MARS_PIN[i:i + 2] for i in range(0, 64, 2)).upper()
        self.fake_apksigner(apksigner_text())
        rc, out, _ = self.run_pin(self.apk, "--expected", pretty,
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 0)
        self.assertIn("PASS", out)

    def test_multiple_apks_all_match(self):
        """Несколько APK (mobile+TV) с одним пином проходят разом."""
        tv = os.path.join(self.tmp, "mars-tv.apk")
        with open(tv, "wb") as f:
            f.write(b"PK\x03\x04 tv")
        self.fake_apksigner(apksigner_text())
        rc, out, _ = self.run_pin(self.apk, tv, "--expected", MARS_PIN,
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 0)
        self.assertIn("mars-tv.apk", out)


class TestCertPinMismatch(CertPinTestCase):
    def test_foreign_expected_fails(self):
        """Негативный тест: подмена ожидаемого отпечатка на чужой валит проверку."""
        self.fake_apksigner(apksigner_text())
        rc, out, err = self.run_pin(self.apk, "--expected", FOREIGN_PIN,
                                    "--apksigner", sys.executable)
        self.assertEqual(rc, 1)
        self.assertIn("ЧУЖИМ ключом", err)
        self.assertIn(FOREIGN_PIN, err)
        self.assertNotIn("PASS", out)

    def test_foreign_cert_with_same_debug_dn_fails(self):
        """Чужой debug-ключ: DN идентичен нашему, отпечаток другой -> FAIL.

        Именно этот случай текстовая DN-проверка не различала: APK, подписанный
        debug-ключом раннера, имел тот же «CN=Android Debug» и проходил бы.
        """
        self.fake_apksigner(apksigner_text(digest=FOREIGN_PIN, dn=MARS_DN))
        rc, _, err = self.run_pin(self.apk, "--expected", MARS_PIN,
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 1)
        self.assertIn("ЧУЖИМ ключом", err)

    def test_mismatch_reports_both_fingerprints(self):
        """В отказе видны оба отпечатка — иначе разбор инцидента невозможен."""
        self.fake_apksigner(apksigner_text(digest=FOREIGN_PIN))
        _, _, err = self.run_pin(self.apk, "--expected", MARS_PIN,
                                 "--apksigner", sys.executable)
        self.assertIn(FOREIGN_PIN, err)
        self.assertIn(MARS_PIN, err)

    def test_one_of_two_apks_foreign_fails(self):
        """Провал одного APK из пары валит весь шаг (не публикуем половину)."""
        tv = os.path.join(self.tmp, "mars-tv.apk")
        with open(tv, "wb") as f:
            f.write(b"PK\x03\x04 tv")
        texts = {self.apk: apksigner_text(), tv: apksigner_text(digest=FOREIGN_PIN)}
        real = pin.subprocess.run

        def fake(cmd, **kw):
            return _FakeProc(texts[cmd[-1]])

        pin.subprocess.run = fake
        self.addCleanup(setattr, pin.subprocess, "run", real)
        rc, _, err = self.run_pin(self.apk, tv, "--expected", MARS_PIN,
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 1)
        self.assertIn("mars-tv.apk", err)


class TestCertPinLegacyMode(CertPinTestCase):
    """Обратная совместимость: без пина работает старый запрет debug-ключа."""

    def test_unset_pin_blocks_debug_dn(self):
        self.fake_apksigner(apksigner_text())
        rc, _, err = self.run_pin(self.apk, "--apksigner", sys.executable)
        self.assertEqual(rc, 1)
        self.assertIn("cn=android debug", err)
        self.assertIn(pin.ENV_EXPECTED, err)

    def test_unset_pin_allows_release_dn(self):
        self.fake_apksigner(apksigner_text(dn="CN=Mars Release, O=Mars, C=RU"))
        rc, out, err = self.run_pin(self.apk, "--apksigner", sys.executable)
        self.assertEqual(rc, 0, err)
        self.assertIn("пин не задан", out)

    def test_empty_pin_means_unset(self):
        """Пустая строка в env = пин не задан (не «совпало с пустотой»)."""
        os.environ[pin.ENV_EXPECTED] = "   "
        self.fake_apksigner(apksigner_text())
        rc, out, _ = self.run_pin(self.apk, "--apksigner", sys.executable)
        self.assertEqual(rc, 1)
        self.assertIn("режим DN", out)


class TestCertPinBadInput(CertPinTestCase):
    def test_not_verifying_fails(self):
        """^Verifies обязателен: без него пин ничего не доказывает."""
        self.fake_apksigner(apksigner_text(verifies=False))
        rc, _, err = self.run_pin(self.apk, "--expected", MARS_PIN,
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 1)
        self.assertIn("НЕ верифицируется", err)

    def test_apksigner_nonzero_rc_fails(self):
        real = pin.subprocess.run
        pin.subprocess.run = lambda *a, **k: _FakeProc(
            apksigner_text(), stderr="ERROR: bad signature", returncode=1
        )
        self.addCleanup(setattr, pin.subprocess, "run", real)
        rc, _, err = self.run_pin(self.apk, "--expected", MARS_PIN,
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 1)
        self.assertIn("НЕ верифицируется", err)

    def test_missing_digest_is_input_error(self):
        self.fake_apksigner("Verifies\nNumber of signers: 1\n")
        rc, _, err = self.run_pin(self.apk, "--expected", MARS_PIN,
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 2)
        self.assertIn("SHA-256 digest", err)

    def test_short_pin_rejected(self):
        """Опечатка в пине не должна молча превращаться в «другой» отпечаток."""
        self.fake_apksigner(apksigner_text())
        rc, _, err = self.run_pin(self.apk, "--expected", "deadbeef",
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 2)
        self.assertIn("64", err)

    def test_missing_apk_is_input_error(self):
        rc, _, err = self.run_pin(os.path.join(self.tmp, "nope.apk"),
                                  "--expected", MARS_PIN,
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 2)
        self.assertIn("APK не найден", err)

    def test_multiple_signers_warned(self):
        """Больше одного подписанта — пин покрывает только Signer #1, и это видно."""
        self.fake_apksigner(apksigner_text(signers=2))
        rc, out, _ = self.run_pin(self.apk, "--expected", MARS_PIN,
                                  "--apksigner", sys.executable)
        self.assertEqual(rc, 0)
        self.assertIn("подписантов 2", out)


# ---------------------------------------------------------------------------
# scripts/apk-publish-ci.py — публикация из CI
# ---------------------------------------------------------------------------
PUB_SCRIPT = os.path.join(REPO, "scripts", "apk-publish-ci.py")
_pub_spec = importlib.util.spec_from_file_location("apk_publish_ci", PUB_SCRIPT)
assert _pub_spec is not None and _pub_spec.loader is not None, "не нашёл %s" % PUB_SCRIPT
pub = importlib.util.module_from_spec(_pub_spec)
_pub_spec.loader.exec_module(pub)


class PublishCiTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="pub-test-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.apk = os.path.join(self.tmp, "app-mobile-release.apk")
        with open(self.apk, "wb") as f:
            f.write(b"PK\x03\x04" + os.urandom(200 * 1024))
        os.environ.pop(pub.ENV_KEY_FILE, None)
        self.addCleanup(os.environ.pop, pub.ENV_KEY_FILE, None)
        # Сеть в юнит-тестах запрещена: считаем обращения к раздаче, чтобы
        # доказать, что dry-run не ходит в сеть ни разу.
        self.net_calls = []
        self.addCleanup(setattr, pub, "fetch_remote_sha256", pub.fetch_remote_sha256)

        def _fake_fetch(url, _timeout):
            self.net_calls.append(url)
            return None  # «на раздаче ничего нет» — первая публикация

        setattr(pub, "fetch_remote_sha256", _fake_fetch)

    def snapshot(self):
        st = os.stat(self.apk)
        with open(self.apk, "rb") as f:
            return (st.st_mtime_ns, st.st_size, hashlib.sha256(f.read()).hexdigest())

    def run_pub(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = pub.main(list(argv))
        return rc, out.getvalue(), err.getvalue()

    def test_dry_run_publishes_nothing(self):
        """--dry-run: rc=0, ни сети, ни изменений входного файла/каталога."""
        before = self.snapshot()
        listing = sorted(os.listdir(self.tmp))
        rc, out, _ = self.run_pub(
            "--apk", self.apk, "--name", "mars-mobile.apk", "--dry-run"
        )
        self.assertEqual(rc, 0, out)
        self.assertIn("DRY-RUN", out)
        self.assertIn("mars-mobile.apk", out)
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(sorted(os.listdir(self.tmp)), listing)
        self.assertEqual(self.net_calls, [], "dry-run не должен обращаться к раздаче")

    def test_dry_run_needs_no_key(self):
        """Плановый прогон не требует секрета — иначе PR-прогон был бы красным."""
        rc, out, _ = self.run_pub("--apk", self.apk, "--name", "mars-tv.apk", "--dry-run")
        self.assertEqual(rc, 0, out)
        self.assertIn("ключ:", out)

    def test_real_mode_without_key_refuses(self):
        """Без SSH-ключа реальная публикация запрещена (в CI это секрет)."""
        rc, _, err = self.run_pub("--apk", self.apk, "--name", "mars-mobile.apk")
        self.assertEqual(rc, 1)
        self.assertIn("SSH-ключ", err)
        self.assertEqual(self.net_calls, [], "без ключа публикация не должна трогать сеть")

    def test_name_must_be_apk(self):
        rc, _, err = self.run_pub("--apk", self.apk, "--name", "mars-mobile.txt", "--dry-run")
        self.assertEqual(rc, 1)
        self.assertIn(".apk", err)

    def test_missing_apk_refused(self):
        rc, _, err = self.run_pub(
            "--apk", os.path.join(self.tmp, "nope.apk"), "--name", "mars-mobile.apk", "--dry-run"
        )
        self.assertEqual(rc, 1)
        self.assertIn("не существует", err)

    def test_tiny_apk_refused(self):
        small = os.path.join(self.tmp, "small.apk")
        with open(small, "wb") as f:
            f.write(b"PK\x03\x04")
        rc, _, err = self.run_pub("--apk", small, "--name", "mars-mobile.apk", "--dry-run")
        self.assertEqual(rc, 1)
        self.assertIn("маленький", err)


if __name__ == "__main__":
    unittest.main(verbosity=2)
