"""Usage: gen.py <out dir> <providers.tsv> <rest.tsv> <spec.py> [<spec.py> ...]

Turns compact Python specs into source config files. A spec calls `site(...)` once per provider:

    site("slug", "Display name", "<rootSelector>", "<rowSelector>", {
      "name": T("h3"),                                  # text of the first match
      "externalId": A("a.buy", "href"),                 # attribute of the first match
      "vcpuCount": T("li:contains(vCPU)"),
      "ramMb": T("li:contains(RAM)", unit="GB"),        # unit for pages writing ГБ, Go, ...
      "diskGb": T("li:contains(NVMe)"), "diskType": DT("li:contains(NVMe)"),
      "ipv4Count": V(1), "virtualization": V("KVM"),    # constants
      "prices": [P("MONTHLY", "span.price", "$", grouping=","),     # currency by symbol (see SYM)
                 P("ANNUAL", "div.plan", "USD", attr="data-year")]  # or a fixed ISO code
    }, complete=True, ack=False, url=None)

`url` defaults to the discovery page from rest.tsv; `complete` is `tariffListComplete`; `ack` sets
`acknowledgeSlugMismatch` for a page on another host of the provider. See examples/example-spec.py for a real spec.
"""
import json
import os
import sys
from urllib.parse import urlsplit

out_dir, providers_tsv, rest_tsv, *specs = sys.argv[1:]
os.makedirs(out_dir, exist_ok=True)
website = {}
for line in open(providers_tsv, encoding="utf-8"):
    fields = line.rstrip("\n").split("\t")
    if len(fields) > 3:
        website[fields[1]] = fields[3]
urls = dict(line.rstrip("\n").split("\t")[:2] for line in open(rest_tsv, encoding="utf-8") if "\t" in line)

SYM = {"$": "USD", "€": "EUR", "£": "GBP", "₽": "RUB", "₺": "TRY", "₹": "INR", "R$": "BRL", "zł": "PLN",
       "₴": "UAH", "₪": "ILS", "Kč": "CZK", "RM": "MYR", "Rp": "IDR"}
DISK_TYPES = {"NVMe": "NVME", "NVME": "NVME", "SSD": "SSD", "HDD": "HDD"}


def T(selector, **extra):
    return {"selector": selector, "text": True, **extra}


def A(selector, attr, **extra):
    return {"selector": selector, "attr": attr, **extra}


def V(value):
    return {"value": str(value)}


def DT(selector, attr=None):
    spec = A(selector, attr) if attr else T(selector)
    spec["values"] = dict(DISK_TYPES)
    return spec


def P(period, selector, currency, attr=None, **extra):
    spec = {"billingPeriod": period, "selector": selector}
    if attr:
        spec["attr"] = attr
    else:
        spec["text"] = True
    spec["currency"] = {"fixed": currency} if len(currency) == 3 and currency.isupper() else {"bySymbol": {currency: SYM[currency]}}
    spec.update(extra)
    return spec


def site(slug, name, root, row, tariffs, url=None, complete=True, ack=False):
    parts = urlsplit(url or urls[slug])
    config = {
        "schemaVersion": 1, "id": slug, "providerSlug": slug,
        "baseUrl": f"{parts.scheme}://{parts.netloc}", "pagePath": (parts.path or "/") + ("?" + parts.query if parts.query else ""),
        "enabled": True, "tariffListComplete": complete,
        "provider": {"name": V(name), "websiteUrl": V(website[slug])},
        "tariffs": {"rootSelector": root, "rowSelector": row, **tariffs},
    }
    if ack:
        config["acknowledgeSlugMismatch"] = True
    with open(os.path.join(out_dir, slug + ".json"), "w", encoding="utf-8") as out:
        json.dump(config, out, ensure_ascii=False, indent=2)


for spec in specs:
    exec(open(spec, encoding="utf-8").read())
