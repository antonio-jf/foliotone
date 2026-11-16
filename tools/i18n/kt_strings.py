"""Find Kotlin string literals (with template parts) in a source file."""
import re, json, sys

class Lit:
    def __init__(self, start, end, parts, depth):
        self.start, self.end, self.parts, self.depth = start, end, parts, depth
    # parts: list of ("text", str) | ("var", name) | ("expr", (start,end) source span of expression inside ${})

def scan(src):
    lits = []
    i, n = 0, len(src)
    def skip_comment(i):
        if src.startswith("//", i):
            j = src.find("\n", i); return n if j < 0 else j
        if src.startswith("/*", i):
            j = src.find("*/", i + 2); return n if j < 0 else j + 2
        return None
    def parse_string(i, depth):
        # src[i] == '"' (not raw)
        assert src[i] == '"'
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            while j + 3 < n and src[j + 3] == '"': j += 1
            lits.append(Lit(i, j + 3, [("raw", src[i+3:j])], depth)); return j + 3
        j = i + 1; parts = []; buf = []
        while j < n:
            c = src[j]
            if c == '\\':
                buf.append(src[j:j+2]); j += 2; continue
            if c == '"':
                if buf: parts.append(("text", "".join(buf)))
                lits.append(Lit(i, j + 1, parts, depth)); return j + 1
            if c == '$' and j + 1 < n and src[j+1] == '{':
                if buf: parts.append(("text", "".join(buf))); buf = []
                k = parse_code(j + 2, depth + 1, stop='}')
                parts.append(("expr", (j + 2, k))); j = k + 1; continue
            if c == '$' and j + 1 < n and (src[j+1].isalpha() or src[j+1] == '_'):
                m = re.match(r'[A-Za-z_][A-Za-z0-9_]*', src[j+1:])
                if buf: parts.append(("text", "".join(buf))); buf = []
                parts.append(("var", m.group(0))); j += 1 + len(m.group(0)); continue
            buf.append(c); j += 1
        raise ValueError("unterminated string at %d" % i)
    def parse_code(i, depth, stop=None):
        level = 0
        while i < n:
            sc = skip_comment(i)
            if sc is not None: i = sc; continue
            c = src[i]
            if c == '"': i = parse_string(i, depth); continue
            if c == "'":
                m = re.match(r"'(\\.|[^'\\])[^']*'", src[i:])
                i += len(m.group(0)) if m else 1; continue
            if c == '{': level += 1
            elif c == '}':
                if level == 0 and stop == '}': return i
                level -= 1
            i += 1
        return i
    parse_code(0, 0)
    return lits

def line_of(src, pos): return src.count("\n", 0, pos) + 1

if __name__ == "__main__":
    path = sys.argv[1]
    src = open(path, encoding="utf-8").read()
    for l in scan(src):
        if l.parts and l.parts[0][0] == "raw": continue
        text = "".join(p[1] if p[0] == "text" else ("{$%s}" % p[1] if p[0] == "var" else "{expr}") for p in l.parts)
        if not re.search(r"[A-Za-z]{2}", text): continue
        ctx = src[max(0, l.start - 45):l.start].replace("\n", " ")
        print(f"{line_of(src, l.start)}\t{l.depth}\t{ctx[-45:]!s:>45} ⟨{text}⟩")
