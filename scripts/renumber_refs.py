"""Renumber inline citations [N] in paper/*.md according to the mapping
old → new below. Runs a two-pass replacement to avoid collisions
(e.g., [6] → [5] would otherwise clash with an existing [5] → ...).
"""

import re
from pathlib import Path

# Old number -> new number. Missing keys are unsupported (should not occur).
MAPPING = {
    1: 1, 2: 2, 3: 3, 4: 4,
    6: 5, 7: 6, 9: 7, 10: 8,
    12: 9, 13: 10, 15: 11, 17: 12,
    19: 13, 22: 14, 23: 15, 25: 16, 26: 17,
}

# Sources in `paper/` that contain inline citations.
# SECTION_1.md was processed in the first run; resume from SECTION_2 to
# avoid double-mapping.
PAPER = Path(__file__).resolve().parent.parent / "paper"
FILES = [
    "ABSTRACT.md",
    "SECTION_2.md",
    "SECTION_3.md",
    "SECTION_4.md",
    "SECTION_5.md",
    "SECTION_6.md",
    "SECTION_7.md",
]

CITE_PATTERN = re.compile(r"\[(\d+(?:\s*,\s*\d+)*)\]")


def remap_list(match: re.Match[str]) -> str:
    raw = match.group(1)
    parts = [int(x.strip()) for x in raw.split(",")]
    new_parts = []
    for n in parts:
        if n not in MAPPING:
            # Any surviving "deleted" reference should be flagged, not silently kept.
            raise ValueError(f"Reference [{n}] is in the deletion list — text "
                             f"still cites it: {match.group(0)}")
        new_parts.append(MAPPING[n])
    return "[" + ", ".join(str(x) for x in new_parts) + "]"


def process(path: Path) -> int:
    text = path.read_text()
    # Author notes (## Примітки для автора) are stripped by assemble.sh
    # before the docx is built. Skip them here to avoid touching stale
    # references that only appear in those notes.
    marker = "\n## Примітки для автора"
    idx = text.find(marker)
    head, tail = (text[:idx], text[idx:]) if idx != -1 else (text, "")
    new_head, n = CITE_PATTERN.subn(remap_list, head)
    if n > 0:
        path.write_text(new_head + tail)
    return n


def main() -> None:
    total = 0
    for name in FILES:
        p = PAPER / name
        try:
            count = process(p)
        except ValueError as e:
            print(f"[!] {name}: {e}")
            raise
        print(f"  {name}: {count} citation groups")
        total += count
    print(f"Total: {total}")


if __name__ == "__main__":
    main()
