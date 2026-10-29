"""Lists the packages of app/src/main that import each other (two-way pairs) and counts the imports behind each."""
import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java/com/touringproject/v2")
BASE = "com.touringproject.v2"
edges = collections.defaultdict(lambda: collections.defaultdict(list))
for path in sorted(ROOT.rglob("*.kt")):
    rel = path.relative_to(ROOT)
    pkg = rel.parts[0] if len(rel.parts) > 1 else "(root)"
    for line in path.read_text().splitlines():
        m = re.match(rf"import {re.escape(BASE)}\.([a-z]+)\.", line)
        target = m.group(1) if m else ("(root)" if re.match(rf"import {re.escape(BASE)}\.[A-Z]", line) else None)
        if target and target != pkg:
            edges[pkg][target].append(f"{rel}: {line[7:]}")
pairs = sorted({tuple(sorted((a, b))) for a in edges for b in edges[a] if a in edges.get(b, {})})
for a, b in pairs:
    print(f"{a} <-> {b}  ({len(edges[a][b])} + {len(edges[b][a])} imports)")
print(f"{len(pairs)} two-way pairs")
