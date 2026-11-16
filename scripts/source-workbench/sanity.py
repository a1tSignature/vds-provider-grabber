"""Usage: sanity.py <validate.out>

Flags tariffs whose values parsed but look wrong -- the usual sign of a selector reading the wrong
number (a unit the parser does not know, a digit inside a label such as "IPv4", a spec line shared
by several fields).
"""
import re
import sys

source = None
for line in open(sys.argv[1], encoding="utf-8"):
    if line.startswith("##"):
        source = line.split(".json")[0][3:]
        continue
    match = re.search(r"\| (.*) \| cpu=(\d+) ram=(\d+) disk=(\d+) \w+ .*?stock=\w+ (.*)$", line)
    if not match:
        continue
    name, cpu, ram, disk, prices = match.groups()
    cpu, ram, disk = int(cpu), int(ram), int(disk)
    problems = []
    if not 1 <= cpu <= 64:
        problems.append("cpu")
    if not 256 <= ram <= 262144:
        problems.append("ram")
    if not 5 <= disk <= 20000:
        problems.append("disk")
    if cpu > 8 and ram < 2048:
        problems.append("cpu/ram")
    if not prices.strip():
        problems.append("no price")
    if problems:
        print(source, "|", name, problems)
