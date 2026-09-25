#!/usr/bin/env python3
"""Stand-in for the app's captive portal during the Linux end-to-end test.

It plays the two roles the Kotlin app plays on the phone:

  * CaptivePortalServer  - every GET, including the OS probe paths, is answered
    with the login page at HTTP 200 (a redirect or a 204 is what stops the
    sign-in sheet appearing).
  * VoucherManager       - POST /redeem validates the code, binds it to the
    requesting MAC, and then runs the SAME shell commands the app runs:
      setup_network.sh reserve   <mac> <ip>
      setup_network.sh authorize <mac> <ip> <current_ip>
      bandwidth_control.sh add   <ip> <class> <rate> <ceil>

It is deliberately a thin shell around the real scripts: the point of the test
is the scripts and the kernel, not this file.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROBE_PATHS = (
    "/generate_204", "/gen_204", "/connecttest.txt", "/redirect",
    "/hotspot-detect.html", "/library/test/success.html", "/ncsi.txt",
    "/canonical.html", "/success.txt",
)

LOGIN_PAGE = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Sign in</title>
<meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body style="font-family:sans-serif;text-align:center;padding-top:40px">
<h2>RNS Hotspot</h2>
<p>Enter your voucher code.</p>
<form method="post" action="/redeem">
  <input name="voucher" placeholder="XXXX-XXXX" autocomplete="off">
  <button type="submit">Connect</button>
</form>
</body></html>
"""


def page(title, body, colour="#333"):
    return (
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
        f"<title>{title}</title></head>"
        f"<body style=\"font-family:sans-serif;text-align:center;padding-top:40px;color:{colour}\">"
        f"{body}<p><a href=\"/\">Back</a></p></body></html>"
    )


class Portal:
    def __init__(self, state, setup, shaper, codes, rate, ceil, log_path):
        self.state = state
        self.setup = setup
        self.shaper = shaper
        self.codes = {c.upper(): None for c in codes if c}
        self.rate = rate
        self.ceil = ceil
        self.log_path = log_path
        self.store_path = os.path.join(state, "vouchers.json")
        self.store = self._load()

    # ---- helpers -------------------------------------------------------
    def log(self, msg):
        line = f"{time.strftime('%H:%M:%S')} {msg}"
        sys.stderr.write(f"[portal] {line}\n")
        sys.stderr.flush()
        try:
            with open(self.log_path, "a", encoding="utf-8") as fh:
                fh.write(line + "\n")
        except OSError:
            pass

    def _load(self):
        try:
            with open(self.store_path, encoding="utf-8") as fh:
                return json.load(fh)
        except (OSError, ValueError):
            return {}

    def _save(self):
        tmp = self.store_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(self.store, fh)
        os.replace(tmp, self.store_path)

    def mac_for_ip(self, ip):
        """Lease file first (the app's LeaseParser), then the neighbour table."""
        leases = os.path.join(self.state, "dnsmasq.leases")
        try:
            with open(leases, encoding="utf-8") as fh:
                for line in fh:
                    f = line.split()
                    if len(f) >= 3 and f[2] == ip:
                        return f[1].lower()
        except OSError:
            pass
        try:
            out = subprocess.run(["ip", "neigh", "show", ip], capture_output=True,
                                 text=True, timeout=5).stdout
            m = re.search(r"([0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:"
                          r"[0-9a-f]{2}:[0-9a-f]{2})", out, re.I)
            if m:
                return m.group(1).lower()
        except (OSError, subprocess.SubprocessError):
            pass
        return None

    def run(self, args):
        self.log("exec: " + " ".join(args))
        try:
            r = subprocess.run(args, capture_output=True, text=True, timeout=30)
            for line in (r.stdout or "").splitlines():
                if line.strip():
                    self.log("   out: " + line.strip())
            for line in (r.stderr or "").splitlines():
                if line.strip():
                    self.log("   err: " + line.strip())
            return r.returncode
        except (OSError, subprocess.SubprocessError) as exc:
            self.log(f"   FAILED to run: {exc}")
            return 1

    def grant(self, mac, ip):
        """reserve -> authorize -> shape, exactly like VoucherManager.applyAccess."""
        cls = 100 + (len(self.store) % 800)
        self.run(["sh", self.setup, "reserve", mac, ip])
        code = self.run(["sh", self.setup, "authorize", mac, ip, ip])
        self.run(["sh", self.shaper, "add", ip, str(cls), str(self.rate), str(self.ceil)])
        return code

    def redeem(self, code, ip):
        code = (code or "").strip().upper()
        if not code:
            return "Missing voucher code", "#c0392b"
        if code not in self.codes:
            self.log(f"reject: unknown code {code} from {ip}")
            return "Invalid voucher code.", "#c0392b"

        mac = self.mac_for_ip(ip)
        if not mac:
            return ("Could not identify your device. Reconnect to the WiFi and try again.",
                    "#c0392b")

        bound = self.store.get(code)
        if bound and bound.get("mac") and bound["mac"] != mac:
            self.log(f"reject: {code} already bound to {bound['mac']}, request from {mac}")
            return "This voucher is already in use on another device.", "#c0392b"

        rc = self.grant(mac, ip)
        self.store[code] = {"mac": mac, "ip": ip, "at": int(time.time())}
        self._save()
        self.log(f"GRANTED {code} -> mac {mac} ip {ip} (authorize exit {rc})")
        return "Connected. You can close this window.", "#27ae60"


def make_handler(portal: Portal):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # keep stderr clean-ish
            portal.log(f"{self.command} {self.path} from {self.client_address[0]}")

        def _send(self, body, status=200):
            raw = body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            # Every GET returns the login page with 200 - including the OS
            # probes. That is the behaviour that pops the sign-in sheet.
            self._send(LOGIN_PAGE, 200)

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length).decode("utf-8", "replace")
            params = dict(p.split("=", 1) for p in raw.split("&") if "=" in p)
            from urllib.parse import unquote_plus
            code = unquote_plus(params.get("voucher", ""))
            msg, colour = portal.redeem(code, self.client_address[0])
            self._send(page("RNS Hotspot", f"<h3>{msg}</h3>", colour), 200)

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--state", required=True)
    ap.add_argument("--setup", required=True)
    ap.add_argument("--shaper", required=True)
    ap.add_argument("--codes", default="HCSQ-KEBE,MMRT-4KQP")
    ap.add_argument("--rate", default="64")
    ap.add_argument("--ceil", default="128")
    args = ap.parse_args()

    portal = Portal(args.state, args.setup, args.shaper,
                    args.codes.split(","), args.rate, args.ceil,
                    os.path.join(args.state, "portal.log"))
    portal.log(f"listening on 0.0.0.0:{args.port}, codes={sorted(portal.codes)}")
    ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(portal)).serve_forever()


if __name__ == "__main__":
    main()
