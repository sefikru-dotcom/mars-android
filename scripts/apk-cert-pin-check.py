#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apk-cert-pin-check.py — пин отпечатка сертификата подписи release-APK Марса.

Проверяет, что APK подписан ОЖИДАЕМЫМ ключом, сравнивая SHA-256 отпечаток
сертификата первого подписанта, а не текст DN.

Почему так: боевой ключ Марса — это debug-сертификат Android с DN
`C=US, O=Android, CN=Android Debug`. Текстовая проверка «в сертификате нет
androiddebugkey / CN=Android Debug» валила КАЖДЫЙ боевой релиз, и выпуск ушёл
на ручную публикацию с хоста. Отпечаток однозначно отличает наш ключ от любого
чужого debug-ключа (в том числе эфемерного ключа раннера), поэтому пин — верный
критерий, а DN — нет.

Ключ НЕ перегенерировать: смена ключа ломает обновление поверх установленного
приложения (Android отвергает APK, подписанный другим сертификатом).

Использование:
  apk-cert-pin-check.py <apk> [<apk> ...] [--expected SHA256] [--apksigner PATH]

Ожидаемый отпечаток: --expected, иначе env MARS_EXPECTED_CERT_SHA256.
Сравнение нормализованное: нижний регистр, без двоеточий и пробелов.

Если ожидаемый отпечаток НЕ задан — работает обратная совместимость:
запрет debug-ключа по тексту DN (старое поведение CI).

Проверка `^Verifies` выполняется ВСЕГДА, независимо от режима.

Коды возврата:
  0 — подпись валидна и совпадает с пином (или DN-режим пройден);
  1 — нарушение (не верифицируется / чужой отпечаток / debug-ключ в DN-режиме);
  2 — ошибка входа (нет файла, нет apksigner, отпечаток не разобран).

Только stdlib, python3.
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys

EXIT_OK = 0
EXIT_CONTRACT = 1
EXIT_INPUT = 2

ENV_EXPECTED = "MARS_EXPECTED_CERT_SHA256"

# "Signer #1 certificate SHA-256 digest: 15da3b02...d311" (в т.ч. с двоеточиями)
DIGEST_RE = re.compile(
    r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", re.MULTILINE
)
DN_RE = re.compile(r"Signer #1 certificate DN:\s*(.+)", re.MULTILINE)
SIGNERS_RE = re.compile(r"Number of signers:\s*(\d+)", re.MULTILINE)
# Строгое начало строки, как в apksigner: "Verifies" / "Does not verify: ..."
VERIFIES_RE = re.compile(r"^Verifies\b", re.MULTILINE)

DEBUG_MARKERS = ("androiddebugkey", "cn=android debug")


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


def normalize_fingerprint(value):
    """Приводит отпечаток к нижнему регистру без двоеточий/пробелов.

    Пустая строка/None -> None (означает «пин не задан»).
    Невалидная длина -> ошибка входа: молча «нормализовать» мусор в 64 символа
    нельзя, иначе опечатка в пине превратится в тихо неверную проверку.
    """
    if value is None:
        return None
    cleaned = re.sub(r"[^0-9a-f]", "", value.strip().lower())
    if not cleaned:
        return None
    if len(cleaned) != 64:
        raise input_fail(
            "ожидаемый отпечаток сертификата задан, но после нормализации "
            "получилось %d hex-символов вместо 64: %r" % (len(cleaned), value)
        )
    return cleaned


def find_apksigner(cli_path=None):
    """apksigner из --apksigner / $APKSIGNER / build-tools в ANDROID_HOME / PATH."""
    for c in (cli_path, os.environ.get("APKSIGNER")):
        if not c:
            continue
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
        "apksigner не найден (ANDROID_HOME=%r, PATH) — отпечаток проверить нельзя"
        % sdk
    )


def apksigner_output(apk_path, apksigner):
    """Возвращает stdout `apksigner verify --verbose --print-certs`."""
    if not os.path.isfile(apk_path):
        raise input_fail("APK не найден: %s" % apk_path)
    signer = find_apksigner(apksigner)
    r = subprocess.run(
        [signer, "verify", "--verbose", "--print-certs", apk_path],
        capture_output=True, text=True, timeout=300,
    )
    return signer, r


def check_apk(apk_path, expected=None, apksigner=None):
    """Проверяет один APK. Возвращает текст отчёта, бросает CheckError при провале."""
    expected = normalize_fingerprint(expected)
    signer, r = apksigner_output(apk_path, apksigner)
    out = r.stdout or ""
    if r.returncode != 0 or not VERIFIES_RE.search(out):
        raise contract_fail(
            "%s НЕ верифицируется apksigner (rc=%d):\n%s"
            % (apk_path, r.returncode, (r.stderr or out).strip())
        )

    m = DIGEST_RE.search(out)
    if not m:
        raise input_fail(
            "не нашёл 'Signer #1 certificate SHA-256 digest' в выводе apksigner "
            "для %s — проверить пин нельзя" % apk_path
        )
    actual = normalize_fingerprint(m.group(1))
    dn_m = DN_RE.search(out)
    dn = dn_m.group(1).strip() if dn_m else "(DN не найден)"
    signers_m = SIGNERS_RE.search(out)
    signers = int(signers_m.group(1)) if signers_m else 1
    warn = ""
    if signers > 1:
        warn = "  ВНИМАНИЕ: подписантов %d — пин проверен только для Signer #1\n" % signers

    if expected is not None:
        if actual != expected:
            raise contract_fail(
                "%s подписан ЧУЖИМ ключом: SHA-256 сертификата %s != ожидаемого "
                "пина %s (DN: %s). Публикация запрещена: обновление поверх "
                "установленного приложения не встанет."
                % (apk_path, actual, expected, dn)
            )
        return (
            "%s  пин сертификата OK: sha256=%s (DN: %s, apksigner=%s)\n%s"
            % ("  " + os.path.basename(apk_path), actual, dn, signer, warn)
        ).rstrip("\n")

    # Пин не задан — обратная совместимость: старое поведение CI (запрет debug-ключа).
    low = out.lower()
    hit = [mk for mk in DEBUG_MARKERS if mk in low]
    if hit:
        raise contract_fail(
            "%s подписан DEBUG-ключом по тексту DN (%s; DN: %s), а ожидаемый "
            "отпечаток MARS_EXPECTED_CERT_SHA256 не задан — в режиме обратной "
            "совместимости такой APK считается непроизводственным. Задай пин "
            "отпечатка боевого ключа." % (apk_path, ", ".join(hit), dn)
        )
    return (
        "  %s  пин не задан (MARS_EXPECTED_CERT_SHA256 пуст) — DN-проверка OK: "
        "debug-маркеров нет, sha256=%s (DN: %s, apksigner=%s)\n%s"
        % (os.path.basename(apk_path), actual, dn, signer, warn)
    ).rstrip("\n")


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Пин SHA-256 отпечатка сертификата подписи release-APK Марса"
    )
    ap.add_argument("apk", nargs="+", help="путь(и) к APK")
    ap.add_argument(
        "--expected", default=None,
        help="ожидаемый SHA-256 отпечаток сертификата (по умолчанию env %s)"
             % ENV_EXPECTED
    )
    ap.add_argument("--apksigner", default=None, help="путь к apksigner (опционально)")
    args = ap.parse_args(argv)

    expected = args.expected if args.expected is not None else os.environ.get(ENV_EXPECTED)
    try:
        expected_norm = normalize_fingerprint(expected)
        mode = ("пин %s" % expected_norm) if expected_norm else "режим DN (пин не задан)"
        print("[apk-cert-pin] %s; ожидание: %s" % (", ".join(args.apk), mode))
        for apk in args.apk:
            print(check_apk(apk, expected_norm, args.apksigner))
    except CheckError as e:
        print("FAIL: %s" % e.message, file=sys.stderr)
        return e.code
    print("[apk-cert-pin] PASS")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
