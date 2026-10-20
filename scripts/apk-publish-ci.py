#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
apk-publish-ci.py — публикация release-APK Марса ИЗ CI.

Обёртка над логикой /root/tools/mars-apk-publish.py, но без локальных путей
хоста: в CI нет ни /opt/hermes-webui, ни /root/backups, ни aapt2 из кеша
Gradle. Поэтому здесь только то, что имеет смысл в раннере:

  1. входной APK: существует, размер не абсурдно мал;
  2. sha256 артефакта (он же станет <name>.sha256 на раздаче);
  3. копирование на хост раздачи по `scp` (сначала в `<name>.part`, затем
     атомарный `mv` через ssh — чтобы по публичной ссылке никогда не отдавался
     недописанный файл);
  4. верификация: скачивание по HTTPS и сверка размера и sha256.

Проверку «это не debug-сборка» и «подпись нашим ключом» скрипт НЕ дублирует:
в CI её уже сделали `scripts/apk-contract-check.py` (R8 + v3-only) и
`scripts/apk-cert-pin-check.py` (пин отпечатка сертификата) на том же файле.

Использование:
  apk-publish-ci.py --apk <путь> --name mars-mobile.apk \
      [--remote-user root] [--remote-host marscore.ru] \
      [--remote-dir /opt/hermes-webui/static/apk] [--dry-run]

  --dry-run печатает план и НЕ делает ни одного сетевого вызова: ни scp, ни
  HTTPS-сверки. Код возврата 0 — план валиден.

Аутентификация: SSH-ключ из секрета CI, положенный в файл (--ssh-key или
$MARS_PUBLISH_SSH_KEY_FILE). Содержимое ключа никогда не печатается.

Только stdlib + системные `scp`/`ssh`.
Коды возврата: 0 — успех (или чистый dry-run); 1 — отказ/провал.
"""

import argparse
import hashlib
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

DEFAULT_REMOTE_USER = "root"
DEFAULT_REMOTE_HOST = "marscore.ru"
DEFAULT_REMOTE_DIR = "/opt/hermes-webui/static/apk"
DEFAULT_PUBLIC_BASE = "https://marscore.ru/static/apk"
MIN_APK_BYTES = 100 * 1024
ENV_KEY_FILE = "MARS_PUBLISH_SSH_KEY_FILE"


def log(msg):
    print(msg, flush=True)


def fail(msg):
    print("\n=== ОТКАЗ: %s ===" % msg, file=sys.stderr, flush=True)
    return 1


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def remote_target(args, remote_path):
    return "%s@%s:%s" % (args.remote_user, args.remote_host, remote_path)


def run_cmd(cmd, what):
    """Запускает команду, возвращает (ok, stdout, stderr). Секретов в argv нет."""
    log("    $ %s" % " ".join(shlex.quote(c) for c in cmd))
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    except FileNotFoundError:
        return False, "", "%s не найден в PATH" % cmd[0]
    except subprocess.TimeoutExpired:
        return False, "", "%s: таймаут 600 с" % what
    if r.returncode != 0:
        return False, r.stdout, (r.stderr or "").strip()
    return True, r.stdout, r.stderr


def scp_args(args):
    """scp использует -P для порта, ssh — -p. Разделяем явно."""
    opts = ["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=20"]
    if args.ssh_key:
        opts += ["-i", args.ssh_key]
    if args.ssh_port:
        opts += ["-P", str(args.ssh_port)]
    return opts


def ssh_args(args):
    opts = ["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=20"]
    if args.ssh_key:
        opts += ["-i", args.ssh_key]
    if args.ssh_port:
        opts += ["-p", str(args.ssh_port)]
    return opts


def fetch_remote_sha256(url, timeout):
    """sha256 текущего опубликованного файла (для журнала/откатов). None, если нет."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "mars-apk-publish-ci/1.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                return None
            return sha256_bytes(resp.read())
    except Exception:
        return None


def verify_public(url, size, local_sha, retries, delay, timeout):
    """Скачивает опубликованный файл и сверяет размер + sha256. Возвращает (ok, msg)."""
    last = "нет попыток"
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(
                url, headers={"User-Agent": "mars-apk-publish-ci/1.0"}
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                status = resp.status
                body = resp.read()
            if status != 200:
                last = "HTTP %s (ожидался 200)" % status
            elif len(body) != size:
                last = "размер скачанного %d != локального %d" % (len(body), size)
            else:
                remote_sha = sha256_bytes(body)
                if remote_sha == local_sha:
                    return True, "HTTP 200, размер %d и sha256 совпадают" % size
                last = "sha256 скачанного %s != локального %s" % (remote_sha, local_sha)
        except urllib.error.HTTPError as e:
            last = "HTTP %s" % e.code
        except Exception as e:
            last = str(e)
        if attempt < retries:
            log("    попытка %d/%d не прошла (%s), повтор через %d с"
                % (attempt, retries, last, delay))
            time.sleep(delay)
    return False, last


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Публикация release-APK Марса из CI на хост раздачи"
    )
    ap.add_argument("--apk", required=True, help="путь к публикуемому APK")
    ap.add_argument("--name", default="mars-mobile.apk",
                    help="имя файла в каталоге раздачи (default: %(default)s)")
    ap.add_argument("--remote-user", default=DEFAULT_REMOTE_USER)
    ap.add_argument("--remote-host", default=DEFAULT_REMOTE_HOST)
    ap.add_argument("--remote-dir", default=DEFAULT_REMOTE_DIR)
    ap.add_argument("--public-base-url", default=DEFAULT_PUBLIC_BASE,
                    help="база публичного URL раздачи (default: %(default)s)")
    ap.add_argument("--ssh-key", default=os.environ.get(ENV_KEY_FILE),
                    help="файл SSH-ключа (default: env %s)" % ENV_KEY_FILE)
    ap.add_argument("--ssh-port", type=int, default=None)
    ap.add_argument("--retries", type=int, default=6,
                    help="попыток HTTPS-сверки (default: %(default)s)")
    ap.add_argument("--retry-delay", type=float, default=10.0,
                    help="пауза между попытками, с (default: %(default)s)")
    ap.add_argument("--timeout", type=float, default=120.0,
                    help="таймаут HTTP, с (default: %(default)s)")
    ap.add_argument("--dry-run", action="store_true",
                    help="только валидация и план, без сети и без изменений")
    args = ap.parse_args(argv)

    src = os.path.abspath(args.apk)
    name = os.path.basename(args.name)
    if not name.endswith(".apk"):
        return fail("--name должен оканчиваться на .apk: %r" % name)
    remote_path = args.remote_dir.rstrip("/") + "/" + name
    url = "%s/%s" % (args.public_base_url.rstrip("/"), name)

    # ---------- ШАГ 0: входной файл ----------
    if not os.path.isfile(src):
        return fail("входной APK не существует: %s" % src)
    size = os.path.getsize(src)
    log("[0] Входной APK: %s (%d байт, %.2f МБ)" % (src, size, size / 1048576.0))
    if size < MIN_APK_BYTES:
        return fail("подозрительно маленький APK (%d байт < %d)" % (size, MIN_APK_BYTES))

    # ---------- ШАГ 1: хеш ----------
    local_sha = sha256_file(src)
    log("[1] sha256: %s" % local_sha)

    if args.dry_run:
        log("\n[2] DRY-RUN: план публикации (сеть не используется)")
        log("    цель:      %s@%s:%s" % (args.remote_user, args.remote_host, remote_path))
        log("    публичный: %s" % url)
        if args.ssh_key:
            log("    ключ:      %s (содержимое не печатается)" % args.ssh_key)
        else:
            log("    ключ:      не задан — scp пойдёт по умолчанию ssh (в CI это ошибка)")
        log("    scp:       %s -> %s.part" % (name, remote_path))
        log("    ssh:       mv -f %s.part %s && chmod 644 %s"
            % (remote_path, remote_path, remote_path))
        log("    затем:     <name>.sha256 рядом с APK")
        log("    сверка:    GET %s -> размер + sha256" % url)
        log("\n=== DRY-RUN завершён: план валиден, изменения НЕ вносились ===")
        return 0

    # ---------- ШАГ 2: предпосылки публикации (до любой сети) ----------
    if not args.ssh_key:
        return fail(
            "не задан SSH-ключ публикации (--ssh-key или env %s); "
            "в CI это секрет MARS_PUBLISH_SSH_KEY" % ENV_KEY_FILE
        )
    if not os.path.isfile(args.ssh_key):
        return fail("файл SSH-ключа не найден: %s" % args.ssh_key)
    if not shutil.which("scp") or not shutil.which("ssh"):
        return fail("в PATH нет scp/ssh — публикация невозможна")

    # ---------- ШАГ 3: журнал текущей раздачи ----------
    log("\n[3] Текущая раздача (для журнала/откатов)...")
    prev_sha = fetch_remote_sha256(url, args.timeout)
    if prev_sha:
        log("    опубликовано сейчас: sha256 %s" % prev_sha)
        if prev_sha == local_sha:
            log("    ВНИМАНИЕ: на раздаче уже ровно этот файл — публикация идемпотентна")
    else:
        log("    опубликованного файла нет либо он недоступен — первая публикация")

    # ---------- ШАГ 4: копирование ----------
    log("\n[4] Копирование на хост раздачи...")
    part_path = remote_path + ".part"
    ok, _out, err = run_cmd(
        ["scp"] + scp_args(args) + [src, remote_target(args, part_path)],
        "scp APK",
    )
    if not ok:
        return fail("scp APK не удался: %s" % err)

    tmpdir = tempfile.mkdtemp(prefix="mars-apk-publish-")
    try:
        sha_local = os.path.join(tmpdir, name + ".sha256")
        with open(sha_local, "w", encoding="ascii") as f:
            f.write(local_sha + "\n")
        ok, _out, err = run_cmd(
            ["scp"] + scp_args(args) + [sha_local, remote_target(args, part_path + ".sha256")],
            "scp .sha256",
        )
        if not ok:
            return fail("scp файла .sha256 не удался: %s" % err)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    # ---------- ШАГ 5: атомарная подмена ----------
    log("\n[5] Атомарная подмена на хосте...")
    remote_cmd = (
        "set -eu; mv -f {p} {t} && chmod 644 {t}; "
        "mv -f {ps} {ts} && chmod 644 {ts}"
    ).format(
        p=shlex.quote(part_path), t=shlex.quote(remote_path),
        ps=shlex.quote(part_path + ".sha256"), ts=shlex.quote(remote_path + ".sha256"),
    )
    ok, _out, err = run_cmd(
        ["ssh"] + ssh_args(args) + ["%s@%s" % (args.remote_user, args.remote_host), remote_cmd],
        "ssh mv",
    )
    if not ok:
        return fail("не удалось переименовать %s на хосте: %s" % (part_path, err))

    # ---------- ШАГ 6: верификация раздачи ----------
    log("\n[6] Верификация раздачи: %s" % url)
    ok, msg = verify_public(url, size, local_sha, args.retries, args.retry_delay, args.timeout)
    if not ok:
        return fail("опубликованный файл не совпадает с локальным: %s" % msg)
    log("    %s" % msg)

    log("\n=== ПУБЛИКАЦИЯ УСПЕШНА ===")
    log("Файл:    %s" % url)
    log("Размер:  %d байт" % size)
    log("SHA-256: %s" % local_sha)
    if prev_sha and prev_sha != local_sha:
        log("Было:    %s (для отката)" % prev_sha)
    return 0


if __name__ == "__main__":
    sys.exit(main())
