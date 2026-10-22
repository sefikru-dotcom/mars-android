#!/usr/bin/env python3
"""Тесты scripts/verify-google-services.py — CI-гейта FCM-конфига.

Проверяем ровно то, ради чего гейт существует: пустой/битый файл из
незаполненного секрета обязан валить шаг ДО Gradle, а валидный Firebase-конфиг
проходит и печатает project_id без утечки api_key.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "verify-google-services.py"


def _run(path: Path, module: str = "app-mobile") -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPT), str(path), module],
        capture_output=True,
        text=True,
    )


def _valid_config() -> dict:
    return {
        "project_info": {
            "project_id": "mars-test",
            "project_number": "1",
            "storage_bucket": "mars-test.appspot.com",
        },
        "client": [
            {
                "client_info": {
                    "mobilesdk_app_id": "1:1:android:abc",
                    "android_client_info": {"package_name": "ru.mars.mobile"},
                },
                "api_key": [{"current_key": "SECRET-API-KEY"}],
            }
        ],
        "configuration_version": "1",
    }


def test_valid_config_passes_and_hides_the_key() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "google-services.json"
        path.write_text(json.dumps(_valid_config()), encoding="utf-8")
        result = _run(path)
        assert result.returncode == 0, result.stderr
        assert "mars-test" in result.stdout
        assert "ru.mars.mobile" in result.stdout
        assert "SECRET-API-KEY" not in result.stdout


def test_empty_file_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "google-services.json"
        path.write_text("", encoding="utf-8")
        result = _run(path)
        assert result.returncode == 1
        assert "empty" in result.stderr


def test_missing_file_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        result = _run(Path(tmp) / "google-services.json")
        assert result.returncode == 1
        assert "not materialized" in result.stderr


def test_invalid_json_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "google-services.json"
        path.write_text("{not json", encoding="utf-8")
        result = _run(path)
        assert result.returncode == 1
        assert "not valid JSON" in result.stderr


def test_config_without_clients_fails() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "google-services.json"
        path.write_text(json.dumps({"project_info": {"project_id": "mars-test"}, "client": []}), encoding="utf-8")
        result = _run(path)
        assert result.returncode == 1
        assert "lacks" in result.stderr


def test_usage_error_returns_2() -> None:
    result = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True)
    assert result.returncode == 2


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except AssertionError as exc:  # pragma: no cover - failure path
                failures += 1
                print(f"FAIL {name}: {exc}")
    raise SystemExit(1 if failures else 0)
