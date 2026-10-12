#!/usr/bin/env python3
"""Аудит дизайн-системы Android-модуля Марса (mobile/TV).

Использование:
    python3 theme-parity-audit.py <корень-исходников> [--palette ИМЯ ...]
    python3 theme-parity-audit.py app-mobile/src/main
    python3 theme-parity-audit.py app-tv/src/main

Проверяет два бан-листа визуального паритета:
  1. хардкод-цвета (`0xAARRGGBB` / `0xRRGGBB`, `Color.parseColor("#…")`) вне файла палитры;
  2. запрещённые глифы (эмодзи и типографские символы: chevron, точки, звезда, `x`-счётчик) в строках.

Комментарии и KDoc снимаются перед разбором: строка, ОБЪЯСНЯЮЩАЯ запрещённый глиф, — не дефект.
Каталоги `*/build/` пропускаются (сгенерированные исходники).

Выход: `путь:строка: [цвет|глиф] фрагмент`; код возврата 1 при любой находке, иначе 0.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# Файлы, где палитра ЖИВЁТ легально (mobile — дизайн-система, TV — ресурсы темы).
# MarsGradients.kt — pure-data половина дизайн-системы mobile: цветовые константы,
# градиентные и статус-бейдж спеки без Android-классов (шапка файла: «Holds every
# color constant»). Гейт без него давал ложные срабатывания на всю палитру и
# обесценивал счёт: 34 «находки» скрывали 4 настоящих хардкода в экранах.
DEFAULT_PALETTE_FILES = {"MarsTheme.kt", "MarsGradients.kt", "colors.xml"}

COLOR_RE = re.compile(r"0x[0-9a-fA-F]{6,8}\b|parseColor\(\s*\"#[0-9a-fA-F]{6,8}\"")

# Типографские символы, которые эмодзи не считаются, но так же зависят от шрифта OEM.
BANNED_SYMBOLS = set(
    "✓✔✗✕×✖⚡⚙★☆●◉○◦▲▼◀▶⌃⌄▴▾▸☰➤■▢▪⌛⏳⚠✂✎✏🗑"
)
EMOJI_RANGES = (
    (0x1F000, 0x1F2FF),
    (0x1F300, 0x1FAFF),
    (0x1F900, 0x1F9FF),
    (0x2600, 0x27BF),
    (0x2B00, 0x2BFF),
    (0xFE0F, 0xFE0F),
    (0x200D, 0x200D),
)


def is_banned_glyph(ch: str) -> bool:
    if ch in BANNED_SYMBOLS:
        return True
    code = ord(ch)
    return any(lo <= code <= hi for lo, hi in EMOJI_RANGES)


def strip_comments(text: str) -> str:
    """Снимает //, /* */ и <!-- -->, СБЕРЕГАЯ переводы строк (нумерация не съезжает)."""
    text = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    out: list[str] = []
    i, n = 0, len(text)
    in_line = in_block = in_str = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\" and nxt:
                out.append(nxt)
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if c == '"':
            in_str = True
        out.append(c)
        i += 1
    return "".join(out)


def sources(root: Path):
    for pat in ("*.kt", "*.xml"):
        for path in sorted(root.rglob(pat)):
            if any(part == "build" for part in path.parts):
                continue
            yield path


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    root = Path(argv[1])
    if not root.is_dir():
        print(f"Нет каталога: {root}", file=sys.stderr)
        return 2
    palette = set(DEFAULT_PALETTE_FILES)
    rest = argv[2:]
    if rest and rest[0] == "--palette":
        palette |= set(rest[1:])

    colors = glyphs = 0
    for path in sources(root):
        raw = path.read_text(encoding="utf-8", errors="replace").splitlines()
        clean = strip_comments("\n".join(raw)).splitlines()
        is_palette = path.name in palette
        for idx, line in enumerate(clean):
            original = raw[idx] if idx < len(raw) else ""
            if not is_palette:
                for m in COLOR_RE.finditer(line):
                    print(f"{path}:{idx + 1}: [цвет] {m.group(0)}   | {original.strip()[:90]}")
                    colors += 1
            for ch in line:
                if is_banned_glyph(ch):
                    print(f"{path}:{idx + 1}: [глиф {ch!r}] {original.strip()[:90]}")
                    glyphs += 1
    print(f"\nИтого: хардкод-цветов {colors}, глифов {glyphs} (палитра: {', '.join(sorted(palette))})")
    if colors or glyphs:
        print("Вне файла палитры цветов быть не должно; глифы менять на монохромные векторные иконки.")
        return 1
    print("Чисто.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
