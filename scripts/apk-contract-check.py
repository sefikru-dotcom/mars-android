#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apk-contract-check.py — контракт release-APK Марса (гейт CI).

Проверяет то же, что боевой публикатор mars-apk-publish.py, но ДО публикации:

  1. R8-минификация: считает исходные имена `*.kt` в таблице строк всех
     classes*.dex. R8 переименовывает классы, поэтому эталон: ~14 имён
     с R8 против ~1288 без R8. Порог по умолчанию: >= 100 — провал
     (release собран без минификации).
  2. Схема подписи (--signing-scheme v3-only): разбирает вывод
     `apksigner verify --verbose` и требует v3=true, v1=false, v2=false —
     норма раздачи Марса при minSdk 28.

Коды возврата:
  0 — контракт выполнен;
  1 — нарушение контракта (нет R8 / неверная схема подписи / APK не верифицируется);
  2 — ошибка входных данных (файл не найден, битый zip, нет dex, нет apksigner).

Только stdlib, python3. Юнит-тесты: tests/test_apk_contract_check.py.
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import zipfile

EXIT_OK = 0
EXIT_CONTRACT = 1
EXIT_INPUT = 2

# Порог откалиброван по публикатору: R8 = 14 имён, без R8 = 1288.
DEFAULT_MAX_KT_NAMES = 100
KT_NAME_RE = re.compile(rb"[A-Za-z_]\w*\.kt\x00")
# Строки вида "Verified using v3 scheme (APK Signature Scheme v3): true"
SCHEME_RE = re.compile(
    r"Verified using v(\d+(?:\.\d+)?) scheme[^:]*:\s*(true|false)", re.IGNORECASE
)


class CheckError(Exception):
    """Ошибка проверки с кодом возврата."""

    def __init__(self, message, code):
        super().__init__(message)
        self.message = message
        self.code = code


def contract_fail(message):
    return CheckError(message, EXIT_CONTRACT)


def input_fail(message):
    return CheckError(message, EXIT_INPUT)


# ---------- R8 / минификация ----------

def read_dex_bytes(apk_path):
    """Склеивает байты всех *.dex из APK.

    Битый zip и отсутствие dex — понятные ошибки входа (код 2),
    чтобы CI отличал «сломан артефакт» от «нарушен контракт».
    """
    if not os.path.isfile(apk_path):
        raise input_fail("APK не найден: %s" % apk_path)
    if not zipfile.is_zipfile(apk_path):
        raise input_fail("APK не является корректным zip-архивом: %s" % apk_path)
    try:
        with zipfile.ZipFile(apk_path) as z:
            dex_names = [n for n in z.namelist() if n.endswith(".dex")]
            if not dex_names:
                raise input_fail(
                    "внутри APK нет ни одного .dex — артефакт повреждён "
                    "или это не APK: %s" % apk_path
                )
            return b"".join(z.read(n) for n in sorted(dex_names)), dex_names
    except zipfile.BadZipFile as e:
        raise input_fail("битый zip внутри APK %s: %s" % (apk_path, e))


def count_kt_names(apk_path):
    """Возвращает (число исходных имён *.kt в dex, список dex-файлов)."""
    dex, dex_names = read_dex_bytes(apk_path)
    return len(KT_NAME_RE.findall(dex)), dex_names


def check_minified(apk_path, max_kt_names=DEFAULT_MAX_KT_NAMES):
    """Проверяет, что release-APK прошёл R8. Возвращает текст отчёта."""
    kt_names, dex_names = count_kt_names(apk_path)
    if kt_names >= max_kt_names:
        raise contract_fail(
            "R8 НЕ применён: в dex %d исходных имён .kt при пороге <%d "
            "(dex: %s). Release без минификации публикатор отвергнет — "
            "пересобери assembleRelease с isMinifyEnabled=true."
            % (kt_names, max_kt_names, ", ".join(dex_names))
        )
    return "R8 OK: исходных имён .kt в dex = %d (< %d), dex: %s" % (
        kt_names, max_kt_names, ", ".join(dex_names)
    )


# ---------- Схема подписи ----------

def parse_signing_schemes(verbose_output):
    """Разбирает вывод `apksigner verify --verbose` -> {'1': bool, '3': bool, ...}."""
    schemes = {}
    for m in SCHEME_RE.finditer(verbose_output):
        schemes[m.group(1)] = m.group(2).lower() == "true"
    return schemes


def find_apksigner(cli_path=None):
    """apksigner из --apksigner / $APKSIGNER / build-tools в ANDROID_HOME / PATH."""
    candidates = []
    if cli_path:
        candidates.append(cli_path)
    if os.environ.get("APKSIGNER"):
        candidates.append(os.environ["APKSIGNER"])
    for c in candidates:
        if os.path.isfile(c) and os.access(c, os.X_OK):
            return c
        raise input_fail("apksigner не найден по указанному пути: %s" % c)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        found = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "apksigner")))
        if found:
            return found[-1]
    which = shutil.which("apksigner")
    if which:
        return which
    raise input_fail(
        "apksigner не найден (ANDROID_HOME=%r, PATH) — схему подписи проверить нельзя"
        % sdk
    )


def check_signing_scheme_v3_only(apk_path, apksigner=None):
    """Требует v3-only: v3=true, v1=false, v2=false. Возвращает текст отчёта."""
    if not os.path.isfile(apk_path):
        raise input_fail("APK не найден: %s" % apk_path)
    signer = find_apksigner(apksigner)
    r = subprocess.run(
        [signer, "verify", "--verbose", apk_path],
        capture_output=True, text=True, timeout=300,
    )
    if r.returncode != 0:
        raise contract_fail(
            "apksigner verify не прошёл для %s:\n%s"
            % (apk_path, (r.stderr or r.stdout).strip())
        )
    schemes = parse_signing_schemes(r.stdout)
    if not schemes:
        raise contract_fail(
            "не удалось разобрать схемы подписи в выводе apksigner для %s:\n%s"
            % (apk_path, r.stdout.strip())
        )
    v3 = any(v is True for k, v in schemes.items() if k.startswith("3"))
    if not v3:
        raise contract_fail(
            "APK %s НЕ подписан по схеме v3 (норма раздачи — v3-only): %s"
            % (apk_path, schemes)
        )
    legacy_on = sorted(k for k, v in schemes.items()
                       if v and (k == "1" or k.startswith("1.")
                                 or k == "2" or k.startswith("2.")))
    if legacy_on:
        raise contract_fail(
            "APK %s включает устаревшие схемы подписи v%s — норма раздачи "
            "v3-only (minSdk 28), v1/v2 должны быть выключены"
            % (apk_path, "/v".join(legacy_on))
        )
    return "Схема подписи OK: v3-only (%s), apksigner=%s" % (schemes, signer)


# ---------- CLI ----------

def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Контракт release-APK Марса: R8-минификация и схема подписи"
    )
    ap.add_argument("apk", help="путь к release-APK")
    ap.add_argument(
        "--max-kt-names", type=int, default=DEFAULT_MAX_KT_NAMES,
        help="порог исходных имён .kt в dex: >= этого числа — R8 не применён "
             "(default: %(default)s)"
    )
    ap.add_argument(
        "--signing-scheme", choices=("skip", "v3-only"), default="skip",
        help="skip — не проверять подпись (unsigned-ветка CI); "
             "v3-only — требовать схему v3 и отвергать v1/v2"
    )
    ap.add_argument("--apksigner", default=None, help="путь к apksigner (опционально)")
    args = ap.parse_args(argv)

    try:
        print("[apk-contract] %s" % args.apk)
        print("  " + check_minified(args.apk, args.max_kt_names))
        if args.signing_scheme == "v3-only":
            print("  " + check_signing_scheme_v3_only(args.apk, args.apksigner))
        else:
            print("  схема подписи: пропущена (--signing-scheme skip)")
    except CheckError as e:
        print("FAIL: %s" % e.message, file=sys.stderr)
        return e.code
    print("[apk-contract] PASS")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
