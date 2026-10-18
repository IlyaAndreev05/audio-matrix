#!/usr/bin/env python3
"""Reject Java and GJS classes whose source body exceeds the agreed LOC limit."""

from __future__ import annotations

import ast
import io
import re
import sys
import tokenize
from pathlib import Path

MAX_LINES = 200
JAVA_DECLARATION = re.compile(r"\b(?:class|interface|enum|record)\s+[\w$]+\b")
JS_DECLARATION = re.compile(r"\bclass\s+[\w$]+\b")


def mask_non_code(source: str, javascript: bool) -> str:
    """Blank comments and literals while keeping offsets and line breaks stable."""
    chars = list(source)
    i = 0
    state = "code"
    while i < len(chars):
        current = chars[i]
        following = chars[i + 1] if i + 1 < len(chars) else ""
        if state == "code":
            if not javascript and source.startswith('"""', i):
                for pos in range(i, min(i + 3, len(chars))):
                    if chars[pos] != "\n":
                        chars[pos] = " "
                i += 3
                state = "text"
                continue
            if current == "/" and following in {"/", "*"}:
                chars[i] = chars[i + 1] = " "
                i += 2
                state = "line" if following == "/" else "block"
                continue
            if current == '"':
                chars[i] = " "
                state = "string"
            elif current == "'":
                chars[i] = " "
                state = "char"
            elif javascript and current == "`":
                chars[i] = " "
                state = "template"
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
        if state == "text" and source.startswith('"""', i):
            for pos in range(i, min(i + 3, len(chars))):
                if chars[pos] != "\n":
                    chars[pos] = " "
            i += 3
            state = "code"
            continue
        delimiter = {"string": '"', "char": "'", "text": "", "template": "`"}[state]
        if current == "\\":
            chars[i] = " "
            if i + 1 < len(chars):
                if chars[i + 1] != "\n":
                    chars[i + 1] = " "
                i += 2
            else:
                i += 1
        elif delimiter and current == delimiter:
            chars[i] = " "
            state = "code"
            i += 1
        else:
            if current != "\n":
                chars[i] = " "
            i += 1
    return "".join(chars)


def class_ranges(masked: str, declaration: re.Pattern[str]) -> list[tuple[int, int, str]]:
    ranges = []
    for match in declaration.finditer(masked):
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


def mask_python_non_code(source: str) -> str:
    """Blank comments and string literals while preserving line boundaries."""
    chars = list(source)
    lines = source.splitlines(keepends=True)
    starts = [0]
    starts.extend(index + 1 for index, char in enumerate(source) if char == "\n")
    for token in tokenize.generate_tokens(io.StringIO(source).readline):
        if token.type not in {tokenize.COMMENT, tokenize.STRING}:
            continue
        start_line, start_column = token.start
        end_line, end_column = token.end
        for line in range(start_line, end_line + 1):
            first = starts[line - 1] + (start_column if line == start_line else 0)
            last = starts[line - 1] + (end_column if line == end_line else len(lines[line - 1]))
            for offset in range(first, min(last, len(chars))):
                if chars[offset] != "\n":
                    chars[offset] = " "
    return "".join(chars)


def python_class_ranges(source: str) -> list[tuple[int, int, str]]:
    tree = ast.parse(source)
    line_starts = [0]
    line_starts.extend(index + 1 for index, char in enumerate(source) if char == "\n")
    ranges = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.ClassDef) or node.end_lineno is None:
            continue
        start = line_starts[node.lineno - 1]
        end = line_starts[node.end_lineno] if node.end_lineno < len(line_starts) else len(source)
        ranges.append((start, end, f"class {node.name}"))
    return ranges


def count_code_lines(masked: str, start: int, end: int) -> int:
    return sum(bool(line.strip()) for line in masked[start:end].splitlines())


def violations(paths: list[Path]) -> list[str]:
    failures = []
    for path in paths:
        source = path.read_text(encoding="utf-8")
        if path.suffix == ".py":
            try:
                ranges = python_class_ranges(source)
                masked = mask_python_non_code(source)
            except (IndentationError, SyntaxError, tokenize.TokenError) as invalid:
                failures.append(f"{path}: could not inspect Python classes: {invalid}")
                continue
        else:
            javascript = path.suffix == ".js"
            masked = mask_non_code(source, javascript)
            declaration = JS_DECLARATION if javascript else JAVA_DECLARATION
            ranges = class_ranges(masked, declaration)
        for start, end, name in ranges:
            lines = count_code_lines(masked, start, end)
            if lines > MAX_LINES:
                line = masked.count("\n", 0, start) + 1
                failures.append(f"{path}:{line}: {name} has {lines} code lines (max {MAX_LINES})")
    return failures


def main(arguments: list[str]) -> int:
    roots = [Path(arg) for arg in arguments] or [Path("src"), Path("gnome"), Path("chrome"), Path("scripts")]
    files = sorted({
        file for root in roots if root.exists()
        for suffix in ("*.java", "*.js", "*.py")
        for file in root.rglob(suffix)
    })
    failures = violations(files)
    if failures:
        print("Class size limit exceeded:", *failures, sep="\n", file=sys.stderr)
        return 1
    print(f"Checked {len(files)} Java/GJS/Python source files; no class exceeds {MAX_LINES} code lines.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
