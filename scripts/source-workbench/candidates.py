"""Usage: candidates.py <catalog-dump.sql> <sources dir> <work dir>

Writes into <work dir>:
  providers.tsv  -- the `provider` table rows (id, slug, name, website_url, description, ...)
  rest.tsv       -- `slug<TAB>discovery page URL` for every CANDIDATE whose enrichment succeeded and
                    that has no source file yet; blog/news/rating pages are left out.
  all.tsv        -- `slug, discovery URL, website, enrichment outcome` for every reachable CANDIDATE
                    without a source file, blogs included: the input of discover.py.
"""
import os
import re
import sys

dump, sources_dir, work = sys.argv[1:4]
os.makedirs(work, exist_ok=True)


def table(name):
    rows, inside = [], False
    for line in open(dump, encoding="utf-8"):
        if line.startswith(f"COPY public.{name} "):
            inside = True
            continue
        if inside:
            if line.startswith("\\."):
                break
            rows.append(line.rstrip("\n").split("\t"))
    return rows


providers = table("provider")
with open(os.path.join(work, "providers.tsv"), "w", encoding="utf-8") as out:
    for row in providers:
        out.write("\t".join(row) + "\n")

outcome = {row[0]: row[2] for row in table("provider_enrichment")}
existing = {f[:-5] for f in os.listdir(sources_dir) if f.endswith(".json")}
skip = re.compile(r"(?i)/blog|/news|glossary|viewtopic|/learn/|tin-tuc|best-|/about/")
with open(os.path.join(work, "rest.tsv"), "w", encoding="utf-8") as rest, \
        open(os.path.join(work, "all.tsv"), "w", encoding="utf-8") as everything:
    for row in providers:
        pid, slug, website, description, system_status = row[0], row[1], row[3], row[4], row[8]
        if system_status != "CANDIDATE" or slug in existing:
            continue
        if outcome.get(pid) in ("UNREACHABLE", "ROBOTS_DISALLOWED"):
            continue
        found = re.search(r" at (https?://[^: ]+)", description)
        discovery = found.group(1) if found else website
        everything.write(f"{slug}\t{discovery}\t{website}\t{outcome.get(pid)}\n")
        if outcome.get(pid) == "SUCCESS" and found and not skip.search(discovery):
            rest.write(f"{slug}\t{discovery}\n")
