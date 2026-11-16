"""Usage: robots.py <robots.txt file> <page path> <label>

Prints `ok` or `BLOCKED by <rule>` for the crawler's own user agent (falling back to `*`),
longest matching rule wins, `*` and `$` wildcards honoured.
"""
import re
import sys

lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
page, label = sys.argv[2], sys.argv[3]
groups, agents, last = {}, [], None
for line in lines:
    line = line.split("#")[0].strip()
    if ":" not in line:
        continue
    key, value = [x.strip() for x in line.split(":", 1)]
    key = key.lower()
    if key == "user-agent":
        if last == "rules":
            agents = []
        agents.append(value.lower())
        last = "ua"
        for agent in agents:
            groups.setdefault(agent, [])
    elif key in ("allow", "disallow"):
        last = "rules"
        for agent in agents:
            groups[agent].append((key, value))

rules = groups.get("vds-provider-grabber") or groups.get("*") or []
best = None
for kind, rule in rules:
    if not rule:
        continue
    pattern = "^" + ".*".join(re.escape(part) for part in rule.rstrip("$").split("*")) + ("$" if rule.endswith("$") else "")
    if re.match(pattern, page) and (best is None or len(rule) > len(best[1])):
        best = (kind, rule)
print(f"{label:24} {'BLOCKED by ' + best[1] if best and best[0] == 'disallow' else 'ok'}")
