#!/usr/bin/env python3
"""Аудит дизайн-системы Android-модуля Марса (mobile/TV).

Использование:
    python3 theme-parity-audit.py <корень-исходников> [--palette ИМЯ ...]
    python3 theme-parity-audit.py app-mobile/src/main
    python3 theme-parity-audit.py app-tv/src/main

Проверяет бан-листы визуального паритета:
  1. хардкод-цвета (`0xAARRGGBB` / `0xRRGGBB`, `Color.parseColor("#…")`) вне файла палитры;
  2. именованные цвета Android (`Color.WHITE`/`Color.BLACK`) вне файла палитры — у них есть
     токены `MarsTheme.TEXT_STRONG`/`MarsTheme.BG`, а `Color.WHITE` проходил мимо цветового
     бан-листа и жил в 21 месте экранов (O11);
  3. запрещённые глифы (эмодзи и типографские символы: chevron, точки, звезда, `x`-счётчик,
     маркер списка `•`) в строках;
  4. декоративная позиция разделителя `·` (U+00B7): в начале или в конце строки он изображает
     потерянную иконку, а не разделяет два фрагмента (O12). Инлайн `a · b` — легален: это
     принятая грамматика подписей (`DETAIL_SEPARATOR`, `42% · 84k/200k`).

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

# Именованные цвета Android, у которых есть токен палитры. `Color.TRANSPARENT` не банится:
# это «отсутствие цвета» (прозрачный фон кнопки), а не оттенок дизайн-системы.
COLOR_NAME_RE = re.compile(r"\bColor\.(WHITE|BLACK)\b")

# Типографские символы, которые эмодзи не считаются, но так же зависят от шрифта OEM.
# `•` (U+2022) — маркер списка: он рисуется canvas'ом (BulletSpan), а не глифом строки.
BANNED_SYMBOLS = set(
    "✓✔✗✕×✖⚡⚙★☆●◉○◦▲▼◀▶⌃⌄▴▾▸☰➤■▢▪⌛⏳⚠✂✎✏🗑•"
)

# `·` (U+00B7) — принятый разделитель подписей, поэтому банится не символ, а его
# ДЕКОРАТИВНАЯ ПОЗИЦИЯ: на краю строки он изображает потерянную иконку («· Авто»).
# Инлайн-разделитель («42% · 84k/200k», `DETAIL_SEPARATOR = " · "`) остаётся легальным.
BANNED_DECORATION_GLYPHS = "·"

# Строковые литералы Kotlin/XML-атрибуты: содержимое нужно, чтобы отличить разделитель
# внутри фразы от глифа на краю строки.
STRING_RE = re.compile(r"\"((?:[^\"\\]|\\.)*)\"")
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


def decoration_hits(line: str) -> list[str]:
    """Глифы-разделители в ДЕКОРАТИВНОЙ позиции строкового литерала.

    Декорация — глиф ПЕРВЫМ символом литерала (`"· Авто"`) или последним (`"Авто ·"`):
    в начале подписи он изображает потерянную иконку, в конце — висящий хвост.
    Не декорация:
      - литерал из одного разделителя (`joinToString("  ·  ")`, `DETAIL_SEPARATOR = " · "`);
      - суффиксный join с ведущим пробелом (`" · поток остановлен"`) — он приклеивается
        к уже набранному тексту, то есть разделяет два фрагмента;
      - инлайн `a · b` внутри фразы.
    """
    hits: list[str] = []
    for match in STRING_RE.finditer(line):
        content = match.group(1)
        if len(content.strip()) < 2:
            continue
        if content[0] in BANNED_DECORATION_GLYPHS or content[-1] in BANNED_DECORATION_GLYPHS:
            hits.append(content)
    return hits


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
                for m in COLOR_NAME_RE.finditer(line):
                    print(f"{path}:{idx + 1}: [цвет-имя] {m.group(0)}   | {original.strip()[:90]}")
                    colors += 1
            for ch in line:
                if is_banned_glyph(ch):
                    print(f"{path}:{idx + 1}: [глиф {ch!r}] {original.strip()[:90]}")
                    glyphs += 1
            for content in decoration_hits(line):
                print(f"{path}:{idx + 1}: [глиф-декорация] {content[:40]!r}   | {original.strip()[:90]}")
                glyphs += 1
    print(f"\nИтого: хардкод-цветов {colors}, глифов {glyphs} (палитра: {', '.join(sorted(palette))})")
    if colors or glyphs:
        print("Вне файла палитры цветов быть не должно; глифы менять на монохромные векторные иконки;")
        print("«·» не ставить на край подписи — это потерянная иконка, а не разделитель.")
        return 1
    print("Чисто.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
