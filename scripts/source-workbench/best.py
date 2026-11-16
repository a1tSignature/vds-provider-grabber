"""Usage: best.py <work dir>

After discover.py and the workbench's `auto` step: for every provider keeps the page variant with
the largest group of tariff cards (menus and groups of more than 40 are ignored), copies it to
pages/<slug>.html, writes rest.tsv (`slug<TAB>url`, what gen.py reads), best.tsv (slug, variant,
card count, url, card class) and auto.txt (the chosen slugs, to rerun `auto` on them).
"""
import os
import re
import shutil
import sys

work = sys.argv[1]
best = {}
for line in open(os.path.join(work, "auto.out"), encoding="utf-8"):
    match = re.match(r"#### (\S+)~(\d+): (\d+) x (\S+) in (.*)", line)
    if not match:
        continue
    slug, variant, count, card, path = match.groups()
    count = int(count)
    if count > 40 or re.search(r"(?i)menu|nav|footer|header|mega", path + card):
        continue
    if slug not in best or count > best[slug][1]:
        best[slug] = (variant, count, card[:60])

urls = dict(line.rstrip("\n").split("\t") for line in open(os.path.join(work, "variants.tsv"), encoding="utf-8"))
with open(os.path.join(work, "best.tsv"), "w", encoding="utf-8") as best_out, \
        open(os.path.join(work, "rest.tsv"), "w", encoding="utf-8") as rest_out, \
        open(os.path.join(work, "auto.txt"), "w", encoding="utf-8") as auto_out:
    for slug, (variant, count, card) in sorted(best.items()):
        url = urls[f"{slug}~{variant}"]
        shutil.copy(os.path.join(work, "pages", f"{slug}~{variant}.html"), os.path.join(work, "pages", f"{slug}.html"))
        best_out.write(f"{slug}\t{variant}\t{count}\t{url}\t{card}\n")
        rest_out.write(f"{slug}\t{url}\n")
        auto_out.write(slug + "\n")
print(len(best))
