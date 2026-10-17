#!/usr/bin/env python3
"""Reject Java classes whose source body exceeds the agreed physical LOC limit."""

from __future__ import annotations

import re
import sys
from pathlib import Path

MAX_LINES = 200
DECLARATION = re.compile(r"\b(?:class|interface|enum|record)\s+[\w$]+\b")


def mask_non_code(source: str) -> str:
    """Blank comments and literals while retaining offsets and line breaks."""
    chars = list(source)
    i = 0
    state = "code"
    while i < len(chars):
        current = chars[i]
        following = chars[i + 1] if i + 1 < len(chars) else ""
        if state == "code":
            if source.startswith("\"\"\"", i):
                for pos in range(i, min(i + 3, len(chars))):
                    if chars[pos] != "\n":
                        chars[pos] = " "
                i += 3
                state = "text"
                continue
            if current == "/" and following == "/":
                chars[i] = chars[i + 1] = " "
                i += 2
                state = "line"
                continue
            if current == "/" and following == "*":
                chars[i] = chars[i + 1] = " "
                i += 2
                state = "block"
                continue
            if current == '"':
                chars[i] = " "
                state = "string"
            elif current == "'":
                chars[i] = " "
                state = "char"
            i += 1
            continue
        if state == "line":
            if current == "\n":
                state = "code"
            else:
                chars[i] = " "
            i += 1
            continue
        if state == "block":
            if current == "*" and following == "/":
                chars[i] = chars[i + 1] = " "
                i += 2
                state = "code"
            else:
                if current != "\n":
                    chars[i] = " "
                i += 1
            continue
        if state == "text":
            if source.startswith("\"\"\"", i):
                for pos in range(i, min(i + 3, len(chars))):
                    if chars[pos] != "\n":
                        chars[pos] = " "
                i += 3
                state = "code"
            else:
                if current != "\n":
                    chars[i] = " "
                i += 1
            continue
        if current == "\\":
            chars[i] = " "
            if i + 1 < len(chars):
                if chars[i + 1] != "\n":
                    chars[i + 1] = " "
                i += 2
            else:
                i += 1
        elif (state == "string" and current == '"') or (state == "char" and current == "'"):
            chars[i] = " "
            state = "code"
            i += 1
        else:
            if current != "\n":
                chars[i] = " "
            i += 1
    return "".join(chars)


def class_ranges(masked: str) -> list[tuple[int, int, str]]:
    ranges = []
    for match in DECLARATION.finditer(masked):
        opening = masked.find("{", match.end())
        if opening < 0:
            continue
        depth = 0
        for index in range(opening, len(masked)):
            if masked[index] == "{":
                depth += 1
            elif masked[index] == "}":
                depth -= 1
                if depth == 0:
                    ranges.append((match.start(), index + 1, match.group(0)))
                    break
    return ranges


def count_code_lines(masked: str, start: int, end: int) -> int:
    return sum(bool(line.strip()) for line in masked[start:end].splitlines())


def violations(paths: list[Path]) -> list[str]:
    failures = []
    for path in paths:
        source = path.read_text(encoding="utf-8")
        masked = mask_non_code(source)
        for start, end, declaration in class_ranges(masked):
            line_count = count_code_lines(masked, start, end)
            if line_count > MAX_LINES:
                line = masked.count("\n", 0, start) + 1
                failures.append(f"{path}:{line}: {declaration} has {line_count} code lines (max {MAX_LINES})")
    return failures


def main(arguments: list[str]) -> int:
    roots = [Path(arg) for arg in arguments] or [Path("src/main/java"), Path("src/test/java")]
    files = sorted({file for root in roots for file in root.rglob("*.java") if root.exists()})
    failures = violations(files)
    if failures:
        print("Class size limit exceeded:", *failures, sep="\n", file=sys.stderr)
        return 1
    print(f"Checked {len(files)} Java files; no class exceeds {MAX_LINES} code lines.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
