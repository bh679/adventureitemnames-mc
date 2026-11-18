#!/usr/bin/env python3
"""Local name preview: serves index.html plus /data.json, which bundles every
shipped naming chain and pool from src/main/resources/data/adventureitemnames/naming.

    python3 scripts/name-preview/serve.py [--port 8752]

Then open http://localhost:8752/. Reads the files on every request, so edits to
a pool show up on the next Regenerate after a page reload.
"""
import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
NAMING = HERE.parents[1] / "src/main/resources/data/adventureitemnames/naming"


def load_dir(sub: str) -> dict:
    out = {}
    for path in sorted((NAMING / sub).glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"skipping {path.name}: {exc}", file=sys.stderr)
            continue
        if isinstance(data, dict) and "id" in data:
            out[data["id"]] = data
    return out


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self._send(200, "text/html; charset=utf-8", (HERE / "index.html").read_bytes())
        elif self.path == "/data.json":
            body = json.dumps({"chains": load_dir("chains"), "pools": load_dir("pools")})
            self._send(200, "application/json", body.encode("utf-8"))
        else:
            self._send(404, "text/plain", b"not found")

    def _send(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=8752)
    args = parser.parse_args()
    if not NAMING.is_dir():
        sys.exit(f"naming data not found at {NAMING}")
    print(f"Name preview on http://localhost:{args.port}/")
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
