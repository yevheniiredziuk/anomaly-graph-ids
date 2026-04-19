#!/usr/bin/env bash
# Idempotent T20 assembly: paper/*.md  →  paper/build/Kolinets-AGIDS-v1.docx
# Usage:  cd paper && bash build/assemble.sh
#
# Depends on: python3, pandoc (brew install pandoc).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PAPER_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$PAPER_DIR")"

cd "$PAPER_DIR"
rm -rf build
mkdir -p build/figures

# 1. Copy figure PNGs
cp "$REPO_ROOT/results/figures/"*.png build/figures/

# 2. Assemble article_full.md (strip author notes, status blockquotes, top h1;
#    rewrite image paths from ../results/figures/ to figures/)
python3 - <<'PY'
import shutil
from pathlib import Path

PAPER = Path(".")
OUT = PAPER / "build" / "article_full.md"


def strip_author_notes(text: str) -> str:
    idx = text.find("\n## Примітки для автора")
    return text[:idx].rstrip() + "\n" if idx != -1 else text


def strip_status_blockquote(text: str) -> str:
    lines = text.splitlines(keepends=True)
    out = []
    state = "before_title"
    for ln in lines:
        if state == "before_title":
            if ln.startswith("# "):
                out.append(ln)
                state = "skipping_bq"
                continue
            out.append(ln)
            continue
        if state == "skipping_bq":
            if ln.startswith(">") or ln.strip() == "":
                continue
            if ln.strip() == "---":
                state = "skipping_sep"
                continue
            state = "body"
            out.append(ln)
            continue
        if state == "skipping_sep":
            if ln.strip() == "":
                continue
            state = "body"
            out.append(ln)
            continue
        out.append(ln)
    return "".join(out)


def strip_top_heading(text: str) -> str:
    lines = text.splitlines(keepends=True)
    return "".join(lines[1:]).lstrip() if lines and lines[0].startswith("# ") else text


def load(path: str, strip_top: bool) -> str:
    text = Path(path).read_text()
    text = strip_author_notes(text)
    text = strip_status_blockquote(text)
    if strip_top:
        text = strip_top_heading(text)
    text = text.replace("../results/figures/", "figures/")
    return text.strip() + "\n\n"


parts = [load("ABSTRACT.md", strip_top=False)]
for sec in ["SECTION_1.md", "SECTION_2.md", "SECTION_3.md",
            "SECTION_4.md", "SECTION_5.md", "SECTION_6.md", "SECTION_7.md"]:
    parts.append(load(sec, strip_top=True))
parts.append(load("REFERENCES.md", strip_top=True))
parts.append(load("REFERENCES_EN.md", strip_top=True))

OUT.write_text("".join(parts))
print(f"[py] Wrote {OUT}: {OUT.stat().st_size:,} bytes")
PY

# 3. pandoc markdown → docx
cd build
pandoc article_full.md \
    --from gfm+tex_math_dollars+yaml_metadata_block \
    --to docx \
    --output Kolinets-AGIDS-v1.docx \
    --standalone \
    --resource-path=. \
    2>&1 | tee pandoc.log

echo ""
echo "=== Build summary ==="
echo "Markdown: $(wc -l < article_full.md) lines, $(wc -c < article_full.md) bytes"
echo "Images:   $(ls figures/*.png | wc -l) PNGs"
echo "Docx:     $(ls -la Kolinets-AGIDS-v1.docx | awk '{print $5}') bytes"
echo "Words:    $(pandoc article_full.md --to plain 2>/dev/null | wc -w)"
