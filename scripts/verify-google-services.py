#!/usr/bin/env python3
"""Проверка материализованного google-services.json (CI-гейт FCM).

Файл Firebase-конфига в git НЕ хранится: он расшифровывается из секрета
(MARS_GOOGLE_SERVICES_{MOBILE,TV}_B64) на каждом прогоне CI. Незаполненный
секрет даёт ПУСТОЙ файл, и плагин com.google.gms.google-services падает
невнятным «File google-services.json is missing» уже внутри Gradle — этот
скрипт валит шаг раньше и объясняет причину.

Использование:
    python3 scripts/verify-google-services.py app-mobile/google-services.json app-mobile

Код возврата 1 при любой проблеме; содержимое конфига (api_key) не печатается.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path


def verify(path: Path, module: str) -> str:
    """Возвращает короткое описание конфига; бросает SystemExit с причиной."""
    if not path.is_file():
        raise SystemExit(f"FAIL: {module}: {path} not materialized (secret missing?)")
    raw = path.read_text(encoding="utf-8", errors="replace").strip()
    if not raw:
        raise SystemExit(
            f"FAIL: {module}: {path} is empty — the CI secret with google-services.json is not set"
        )
    try:
        cfg = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"FAIL: {module}: {path} is not valid JSON: {exc}") from exc
    if not isinstance(cfg, dict):
        raise SystemExit(f"FAIL: {module}: {path} is not a Firebase config object")
    project_id = str((cfg.get("project_info") or {}).get("project_id") or "").strip()
    clients = cfg.get("client") or []
    if not project_id or not isinstance(clients, list) or not clients:
        raise SystemExit(
            f"FAIL: {module}: {path} lacks project_info.project_id/client — wrong secret?"
        )
    package_names = sorted(
        str((client.get("client_info") or {}).get("android_client_info", {}).get("package_name") or "")
        for client in clients
        if isinstance(client, dict)
    )
    return f"{module}: Firebase project {project_id}, clients={len(clients)} {package_names}"


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    print(verify(Path(argv[1]), argv[2]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
