"""List interface texts (tr("…") in the Kotlin sources) that have no translation yet.

Usage, from the project folder:
    python3 tools/i18n/check_translations.py            # report missing and unused keys
    python3 tools/i18n/check_translations.py --add      # also append missing keys to es.json (English
                                                        # copied as a placeholder to translate)
"""
import json, re, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from kt_strings import scan

ROOT = Path(__file__).resolve().parents[2] / "app/src/main"
# Texts passed to tr() at runtime rather than as literals (theme descriptions, voice-label words).
DYNAMIC = re.compile(r'theme\("[a-z]+", "[^"]+", "([^"]+)"')
EXTRA = ["English", "Italian", "French", "German", "Spanish", "Portuguese", "Dutch", "Arabic", "Hindi",
         "Casual", "Cheerful", "Neutral", "female", "male"]

def unescape(text):
    return re.sub(r'\\(.)', lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), text)

keys = {}
for path in sorted((ROOT / "java").rglob("*.kt")):
    src = path.read_text(encoding="utf-8")
    for lit in scan(src):
        before = src[max(0, lit.start - 4):lit.start]
        if not before.endswith("tr(") or (len(before) == 4 and (before[0].isalnum() or before[0] == "_")) \
                or not lit.parts or lit.parts[0][0] == "raw":
            continue
        if any(kind != "text" for kind, _ in lit.parts):
            print(f"{path.name}: tr() must get a plain text with {{0}} placeholders, not a template:",
                  src[lit.start:lit.end][:80])
            continue
        keys.setdefault(unescape("".join(value for _, value in lit.parts)), path.name)
    keys.update({mood: path.name for mood in DYNAMIC.findall(src) if mood not in keys})
for word in EXTRA:
    keys.setdefault(word, "NarrationServer.kt")

for table in sorted((ROOT / "assets/i18n").glob("*.json")):
    data = json.loads(table.read_text(encoding="utf-8"))
    missing = [k for k in keys if k not in data]
    unused = [k for k in data if k not in keys]
    broken = [k for k, v in data.items() if sorted(re.findall(r"\{\d+\}", k)) != sorted(re.findall(r"\{\d+\}", v))]
    print(f"{table.name}: {len(keys)} texts · {len(missing)} missing · {len(unused)} unused · {len(broken)} placeholder mismatches")
    for k in missing: print("  missing:", json.dumps(k, ensure_ascii=False), "←", keys[k])
    for k in unused: print("  unused:", json.dumps(k, ensure_ascii=False))
    for k in broken: print("  placeholders differ:", json.dumps(k, ensure_ascii=False))
    if "--add" in sys.argv and missing:
        data.update({k: k for k in missing})
        table.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"  added {len(missing)} English placeholders to {table.name}; translate them before release")
