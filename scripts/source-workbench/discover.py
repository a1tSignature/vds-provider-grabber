"""Usage: discover.py <work dir> [cache dir]

For candidates whose discovery page is not a plans page. Reads <work dir>/all.tsv (written by
candidates.py), and for every provider saves up to five pages as <work dir>/pages/<slug>~<k>.html:
the discovery page (k=0, taken from <cache dir> when already downloaded), then the shortest
same-site links whose path looks like a VPS/VDS offer, found on the discovery page and on the home
page. <work dir>/variants.tsv maps each `<slug>~<k>` to its URL; feed its first column to the
workbench's `auto` step, then pick one page per provider with best.py.
"""
import html
import os
import re
import ssl
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin, urlsplit

work = sys.argv[1]
cache = sys.argv[2] if len(sys.argv) > 2 else None
os.makedirs(os.path.join(work, "pages"), exist_ok=True)
USER_AGENT = "vds-provider-grabber/0.1"
OFFER = re.compile(r"(?i)(vps|vds|virtual-server|virtual-private|cloud-server|cloud-vps|kvm|vserver|virtuel|servidor|sunucu|wirtual)")
NOT_OFFER = re.compile(r"(?i)(blog|news|docs?/|knowledge|/kb/|faq|login|cart|register|signup|account|contact|about|affiliate|reseller|tutorial|guide|help|support|wiki|terms|privacy|\.pdf|\.jpg|\.png)")
# Hosting sites with broken chains are common; this only reads public marketing pages.
TLS = ssl.create_default_context()
TLS.check_hostname = False
TLS.verify_mode = ssl.CERT_NONE


def get(url):
    try:
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/html"})
        with urllib.request.urlopen(request, timeout=20, context=TLS) as response:
            if response.status != 200:
                return None, url
            return response.read(3_000_000).decode("utf-8", "replace"), response.geturl()
    except Exception:
        return None, url


def site_of(host):
    parts = host.split(".")
    two_level = len(parts) > 2 and len(parts[-1]) == 2 and parts[-2] in ("co", "com", "net", "org")
    return ".".join(parts[-3:] if two_level else parts[-2:])


def visit(row):
    slug, discovery, website = row[0], row[1], row[2]
    cached = os.path.join(cache, slug + ".html") if cache else None
    if cached and os.path.exists(cached):
        page, final = open(cached, encoding="utf-8", errors="replace").read(), discovery
    else:
        page, final = get(discovery)
    sources = [(page, final)] if page else []
    home, home_final = get(website)
    if home:
        sources.append((home, home_final))
    site = site_of(urlsplit(website).hostname or "")
    links = []
    for body, base in sources:
        for href in re.findall(r'href=["\']([^"\'#]+)["\']', body):
            parts = urlsplit(urljoin(base, html.unescape(href)))
            if parts.scheme not in ("http", "https") or not parts.hostname or site_of(parts.hostname) != site:
                continue
            url = f"{parts.scheme}://{parts.hostname}{parts.path}"
            if OFFER.search(parts.path) and not NOT_OFFER.search(url) and url not in links:
                links.append(url)
    links.sort(key=lambda u: (len(urlsplit(u).path), u))
    saved = [(discovery, page)] if page else []
    for url in links[:4]:
        body, _ = get(url)
        if body:
            saved.append((url, body))
    for k, (url, body) in enumerate(saved):
        with open(os.path.join(work, "pages", f"{slug}~{k}.html"), "w", encoding="utf-8") as out:
            out.write(body)
    return [(f"{slug}~{k}", url) for k, (url, _) in enumerate(saved)]


rows = [line.rstrip("\n").split("\t") for line in open(os.path.join(work, "all.tsv"), encoding="utf-8")]
with ThreadPoolExecutor(24) as pool, open(os.path.join(work, "variants.tsv"), "w", encoding="utf-8") as out:
    for result in pool.map(visit, rows):
        for key, url in result:
            out.write(f"{key}\t{url}\n")
