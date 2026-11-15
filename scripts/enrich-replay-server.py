#!/usr/bin/env python3
"""Serves one site saved by the enrichment prototype run on a local port, so enrichment can be
replayed deterministically against real pages without touching the live site.

Usage: python3 -I scripts/enrich-replay-server.py --run-dir ~/.vds-real-run/enrich-prototype-run6 --slug aeza-net [--port 8765]

The served origin is `http://<original host>.localhost:<port>` (e.g. `http://aeza.net.localhost:8765`),
which resolves to loopback and keeps the brand the heuristics read from the domain. Only a ccTLD
signal is lost (the TLD becomes `localhost`).

`pages/<slug>/NN.html` (or `.js`) is the NN-th entry (1-based) of that site's `pages` list in the
run's `results.json`; an entry without a file (a redirect, an error, a robots skip) is not served.
Absolute URLs of the original host (with or without `www.`) are rewritten to this server's origin
while serving, in memory only. robots.txt allows everything; anything not saved is a 404.
Paths match with or without a trailing slash. Stdlib only.
"""
import argparse
import json
import os
import re
import socket
import socketserver
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONTENT_TYPES = {".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8"}


def load_site(run_dir, slug):
    with open(os.path.join(run_dir, "results.json"), encoding="utf-8") as fh:
        site = json.load(fh)[slug]
    page_dir = os.path.join(run_dir, "pages", slug)
    routes, hosts = {}, []
    for index, entry in enumerate(site.get("pages", []), start=1):
        url = urllib.parse.urlsplit(entry["url"])
        host = url.hostname.lower()
        if host not in hosts:
            hosts.append(host)
        for ext in CONTENT_TYPES:
            path = os.path.join(page_dir, f"{index:02d}{ext}")
            if os.path.isfile(path):
                key = (url.path.rstrip("/") or "/") + ("?" + url.query if url.query else "")
                routes.setdefault(key, path)
    return routes, hosts


def make_handler(routes, hosts, origin):
    host_rx = re.compile(
        r"(?:https?:)?//(?:www\.)?(?:%s)(?=[/\"'\s?#:)]|$)" % "|".join(re.escape(h.removeprefix("www.")) for h in hosts), re.I
    )

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802 - http.server naming
            url = urllib.parse.urlsplit(self.path)
            if url.path == "/robots.txt":
                return self.reply(200, "text/plain; charset=utf-8", b"User-agent: *\nAllow: /\n")
            key = (url.path.rstrip("/") or "/") + ("?" + url.query if url.query else "")
            path = routes.get(key)
            if path is None:
                return self.reply(404, "text/plain; charset=utf-8", b"not saved\n")
            with open(path, encoding="utf-8", errors="replace") as fh:
                body = host_rx.sub(origin, fh.read())
            self.reply(200, CONTENT_TYPES[os.path.splitext(path)[1]], body.encode("utf-8"))

        def reply(self, status, content_type, body):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            sys.stderr.write("replay %s\n" % (fmt % args))

    return Handler


class LoopbackServer(ThreadingHTTPServer):
    def server_bind(self):
        # HTTPServer.server_bind resolves the bind address with getfqdn(), a reverse DNS lookup
        # that can stall for tens of seconds; the name is not needed here.
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = "localhost", self.server_address[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--run-dir", required=True, help="the saved prototype run (results.json + pages/)")
    parser.add_argument("--slug", required=True, help="the site to serve, e.g. aeza-net")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    run_dir = os.path.expanduser(args.run_dir)
    routes, hosts = load_site(run_dir, args.slug)
    origin = f"http://{hosts[0]}.localhost:{args.port}"
    handler = make_handler(routes, hosts, origin)
    # Loopback only: the pages are third-party content. `*.localhost` may resolve to ::1 or
    # 127.0.0.1, so both get a listener.
    ipv4 = LoopbackServer(("127.0.0.1", args.port), handler)
    ipv6_class = type("LoopbackServer6", (LoopbackServer,), {"address_family": socket.AF_INET6})
    ipv6 = ipv6_class(("::1", args.port), handler)
    threading.Thread(target=ipv6.serve_forever, daemon=True).start()
    print(f"replaying {args.slug} ({len(routes)} pages) at {origin}", flush=True)
    try:
        ipv4.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
