"""Usage: sanity.py <validate.out>

Flags tariffs whose values parsed but look wrong -- the usual sign of a selector reading the wrong
number (a unit the parser does not know, a digit inside a label such as "IPv4", a spec line shared
by several fields).
"""
import re
import sys

source = None
tariff = None
prices_by_period = {}


def grouped_price_candidates(raw):
    """Return likely values when a displayed price contains a 3-digit group."""
    for match in re.finditer(r"(?<!\d)\d[\d.,\s]*\d(?!\d)", raw):
        token = match.group().strip()
        spaced_decimal = re.fullmatch(r"\s*(\d+)\s+[.,]\s+(\d+)\s*", token)
        if spaced_decimal:
            yield float(spaced_decimal.group(1) + "." + spaced_decimal.group(2))
        # 3,299.00 / 3.299,00: the rightmost separator is decimal, the other groups thousands.
        separators = [i for i, char in enumerate(token) if char in ".,"]
        if separators:
            decimal_at = separators[-1]
            fraction = re.sub(r"\D", "", token[decimal_at + 1:])
            integer = token[:decimal_at]
            groups = re.split(r"[.,\s]", integer)
            all_groups = re.split(r"[.,\s]", token)
            integer_grouping = len(all_groups) > 1 and all(len(group) == 3 for group in all_groups[1:])
            if integer_grouping and len(fraction) == 3:
                yield float(re.sub(r"\D", "", token))
            elif len(groups) > 1 and all(len(group) == 3 for group in groups[1:]):
                value = re.sub(r"\D", "", integer)
                yield float(value + ("." + fraction if fraction else ""))
        groups = re.split(r"\s+", token)
        if len(groups) > 1 and all(group.isdigit() and len(group) == 3 for group in groups[1:]):
            yield float("".join(groups))


for line in open(sys.argv[1], encoding="utf-8"):
    if line.startswith("##"):
        source = line.split(".json")[0][3:]
        tariff = None
        continue
    match = re.search(r"\| (.*) \| cpu=(\d+) ram=(\d+) disk=(\d+) \w+ .*?stock=\w+ (.*)$", line)
    if match:
        name, cpu, ram, disk, prices = match.groups()
        tariff = name
        prices_by_period = {
            period: float(amount)
            for period, amount in re.findall(r"(MONTHLY|QUARTERLY|YEARLY|BIENNIALLY|TRIENNIALLY):([\d.]+)", prices)
        }
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
        continue
    inputs = re.match(r"\s*PRICE INPUTS \| (.*)", line)
    if inputs and tariff:
        for period, raw in re.findall(r"(MONTHLY|QUARTERLY|YEARLY|BIENNIALLY|TRIENNIALLY) '[^']*' = '(.*?)'", inputs.group(1)):
            parsed = prices_by_period.get(period)
            if parsed is None:
                continue
            for candidate in grouped_price_candidates(raw):
                if candidate >= 10 and candidate >= parsed * 10 or parsed >= 10 and parsed >= candidate * 10:
                    print(source, "|", tariff, "| price", period, "parsed", parsed,
                          "but grouped display value may be", candidate, "from", repr(raw))
