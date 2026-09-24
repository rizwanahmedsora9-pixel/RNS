#!/usr/bin/env bash
# =============================================================================
# run-script-selftest.sh
#
# Executes scripts/setup_network.sh and scripts/bandwidth_control.sh against
# stub iptables / ip6tables / ip / tc / dnsmasq / sysctl binaries and asserts the
# exact firewall and shaper state they produce.
#
# The real scripts are what runs here - only the kernel-facing commands are
# faked - so this catches rule ordering bugs, duplicate-rule pile-up, stale tc
# filters and dnsmasq misconfiguration without needing a rooted phone.
#
# Usage: tools/run-script-selftest.sh
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
STUBS="$WORK/bin"
STATE="$WORK/state"
LOG="$WORK/commands.log"
mkdir -p "$STUBS" "$STATE"

export HOTSPOT_STATE_DIR="$STATE"
export HOTSPOT_CONF="$WORK/hotspot.env"
export STUB_LOG="$LOG"
export STUB_RULES="$WORK/rules.json"
export STUB_TC="$WORK/tc.json"
export STUB_IPRULES="$WORK/iprules.txt"
export PATH="$STUBS:$PATH"
: > "$STUB_IPRULES"

cat > "$WORK/hotspot.env" <<'ENV'
WAN_IF=ccmni0
LAN_IF=ap0
ENV

FAILURES=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
check() { # check <description> <expected-count> <grep-pattern>
    local desc="$1" want="$2" pat="$3"
    local got
    got=$(grep -c -E "$pat" "$LOG" 2>/dev/null || true)
    if [ "$got" = "$want" ]; then pass "$desc ($got)"; else fail "$desc: expected $want, got $got"; fi
}
section() { printf '\n== %s\n' "$1"; }

# ---------------------------------------------------------------- stub binaries
cat > "$STUBS/iptables.py" <<'PY'
import json, os, sys

PATH = os.environ["STUB_RULES"]

def load():
    if os.path.exists(PATH):
        with open(PATH) as f:
            return json.load(f)
    return {}

def save(db):
    with open(PATH, "w") as f:
        json.dump(db, f)

argv = sys.argv[1:]
table = "filter"
if argv and argv[0] == "-t":
    table, argv = argv[1], argv[2:]
if not argv:
    sys.exit(2)
op, rest = argv[0], argv[1:]

db = load()
chains = db.setdefault(table, {})

if op == "-N":
    chains.setdefault(rest[0], [])
    save(db); sys.exit(0)

if op == "-X":
    name = rest[0]
    if chains.get(name):
        sys.exit(1)
    chains.pop(name, None)
    save(db); sys.exit(0)

if op == "-F":
    if rest:
        chains[rest[0]] = []
    else:
        db[table] = {}
    save(db); sys.exit(0)

if op == "-S":
    for rule in chains.get(rest[0], []):
        print(f"-A {rest[0]} " + " ".join(rule))
    sys.exit(0)

chain, rule = rest[0], rest[1:]
rules = chains.setdefault(chain, [])
key = " ".join(rule)

if op == "-C":
    sys.exit(0 if key in [" ".join(r) for r in rules] else 1)
if op == "-D":
    for i, r in enumerate(rules):
        if " ".join(r) == key:
            del rules[i]; save(db); sys.exit(0)
    sys.exit(1)
if op == "-A":
    rules.append(rule); save(db); sys.exit(0)
if op == "-I":
    pos = 0
    if rule and rule[0].isdigit():
        pos = max(0, int(rule[0]) - 1); rule = rule[1:]
    if key not in [" ".join(r) for r in rules]:
        rules.insert(pos, rule)
    save(db); sys.exit(0)
sys.exit(2)
PY

# iptables and ip6tables get separate state, exactly like the real kernel tables.
cat > "$STUBS/iptables" <<EOF
#!/usr/bin/env bash
echo "iptables \$*" >> "\$STUB_LOG"
STUB_RULES="$STUB_RULES" exec python3 "$STUBS/iptables.py" "\$@"
EOF
cat > "$STUBS/ip6tables" <<EOF
#!/usr/bin/env bash
echo "ip6tables \$*" >> "\$STUB_LOG"
STUB_RULES="$STUB_RULES.6" exec python3 "$STUBS/iptables.py" "\$@"
EOF

cat > "$STUBS/ip" <<'EOF'
#!/usr/bin/env bash
echo "ip $*" >> "$STUB_LOG"
RULES="${STUB_IPRULES:-/dev/null}"
case "$*" in
  "route show default") echo "default via 192.168.1.1 dev ccmni0 metric 1" ;;
  "-o link show up")    printf '1: lo: <LOOPBACK,UP> mtu 65536\n3: ccmni0: <NOARP,UP,LOWER_UP> mtu 1500\n10: ap0: <BROADCAST,MULTICAST,UP> mtu 1500\n' ;;
  "link show ap0")      echo "10: ap0: <BROADCAST,MULTICAST,UP> mtu 1500" ;;
  "-o link show")       printf '1: lo: <LOOPBACK,UP> mtu 65536\n3: ccmni0: <NOARP,UP,LOWER_UP> mtu 1500\n10: ap0: <BROADCAST,MULTICAST,UP> mtu 1500\n' ;;
  "-o -4 addr show dev ap0")
        # Empty by default so start() assigns 10.66.0.1. The adoption scenario
        # exports STUB_LAN_ADDR with a full `ip -o -4 addr` line.
        if [ -n "${STUB_LAN_ADDR:-}" ]; then printf '%s\n' "$STUB_LAN_ADDR"; fi
        ;;
  "rule show")          cat "$RULES" 2>/dev/null ;;
  rule*)
        # NB: bash does not strip prefixes from "$*" directly (${*#x} is a no-op),
        # so copy into a variable first.
        ALL="$*"
        REST="${ALL#rule }"
        OP="${REST%% *}"
        SPEC="${REST#"$OP "}"
        case "$OP" in
            add) echo "$SPEC" >> "$RULES" ;;
            del)
                if [ -s "$RULES" ]; then
                    grep -v -F "$SPEC" "$RULES" > "$RULES.tmp" 2>/dev/null
                    mv "$RULES.tmp" "$RULES"
                fi
                ;;
        esac
        ;;
esac
exit 0
EOF

cat > "$STUBS/tc.py" <<'PY'
import json, os, sys

PATH = os.environ["STUB_TC"]

def load():
    if os.path.exists(PATH):
        with open(PATH) as f:
            return json.load(f)
    return {"qdiscs": [], "classes": [], "filters": []}

argv = sys.argv[1:]
kind, rest = argv[0], argv[1:]
db = load()

def field(name):
    return rest[rest.index(name) + 1] if name in rest else None

if kind == "qdisc":
    action = rest[0]
    if action == "del":
        db["qdiscs"] = []
        # Deleting the root qdisc takes its whole class/filter tree with it.
        if "root" in rest:
            db["classes"] = []
            db["filters"] = []
    elif action == "add":
        db["qdiscs"].append(" ".join(rest))
elif kind == "class":
    if rest[0] == "add":
        cid = field("classid")
        if cid in db["classes"]:
            print(f"RTNETLINK answers: File exists (duplicate classid {cid})", file=sys.stderr)
            json.dump(db, open(PATH, "w")); sys.exit(2)
        db["classes"].append(cid)
    elif rest[0] == "del":
        cid = field("classid")
        db["classes"] = [c for c in db["classes"] if c != cid]
elif kind == "filter":
    if rest[0] == "add":
        db["filters"].append(" ".join(rest))
    elif rest[0] == "del":
        print("filter del by match expression is not supported by tc", file=sys.stderr)
        sys.exit(2)
elif kind == "qdisc" :
    pass
json.dump(db, open(PATH, "w"))
print(" ".join(argv))
PY

cat > "$STUBS/tc" <<EOF
#!/usr/bin/env bash
echo "tc \$*" >> "\$STUB_LOG"
exec python3 "$STUBS/tc.py" "\$@"
EOF

cat > "$STUBS/sysctl" <<'EOF'
#!/usr/bin/env bash
echo "sysctl $*" >> "$STUB_LOG"
exit 0
EOF

cat > "$STUBS/dnsmasq" <<'EOF'
#!/usr/bin/env bash
echo "dnsmasq $*" >> "$STUB_LOG"
pidfile=""
has_range=""
for a in "$@"; do
    case "$a" in
        --pid-file=*) pidfile="${a#--pid-file=}" ;;
        --dhcp-range=*) has_range=1 ;;
    esac
done
[ -n "$pidfile" ] || { echo "stub: no --pid-file" >&2; exit 1; }
# Simulate the system's own DHCP server holding UDP/67 (Android 10+ serves WiFi
# Direct / local-only hotspots from system_server). Only DHCP mode fails; a
# DNS-only dnsmasq must still start.
if [ -n "${STUB_PORT67_TAKEN:-}" ] && [ -n "$has_range" ]; then
    echo "dnsmasq: failed to create listening socket for port 67" >&2
    exit 1
fi
# Ignore SIGHUP (like real dnsmasq, which reloads on it) so the reserve test works.
sh -c 'trap "" HUP; exec sleep 300' &
echo $! > "$pidfile"
exit 0
EOF

cat > "$STUBS/iw" <<'EOF'
#!/usr/bin/env bash
echo "iw $*" >> "$STUB_LOG"
case "$*" in
    "dev ccmni0 info")
        echo "Interface ccmni0"
        echo "	ifindex 3"
        echo "	type managed"
        echo "	channel 6 (2437 MHz), width: 20 MHz, center1: 2437 MHz"
        ;;
    "dev rnsap0 info")
        echo "Interface rnsap0"
        echo "	type AP"
        echo "	channel 6 (2437 MHz), width: 20 MHz"
        ;;
esac
exit 0
EOF

cat > "$STUBS/hostapd" <<'EOF'
#!/usr/bin/env bash
echo "hostapd $*" >> "$STUB_LOG"
pidfile=""
conf=""
prev=""
for a in "$@"; do
    case "$prev" in -P) pidfile="$a" ;; esac
    case "$a" in *.conf) conf="$a" ;; esac
    prev="$a"
done
[ -n "$conf" ] || { echo "stub hostapd: no config file given" >&2; exit 1; }
[ -f "$conf" ] || { echo "stub hostapd: $conf does not exist" >&2; exit 1; }
[ -n "$pidfile" ] || { echo "stub hostapd: no -P pidfile" >&2; exit 1; }
sh -c 'exec sleep 120' &
echo $! > "$pidfile"
exit 0
EOF

chmod +x "$STUBS"/*
SETUP="sh $ROOT/scripts/setup_network.sh"
SHAPER="sh $ROOT/scripts/bandwidth_control.sh"
NETSHARE="sh $ROOT/scripts/netshare_ap.sh"

# ------------------------------------------------------------------- scenarios
section "syntax"
for f in "$ROOT"/scripts/*.sh; do
    if sh -n "$f" 2>"$WORK/syn"; then pass "$(basename "$f") parses with sh -n"; else fail "$(basename "$f"): $(cat "$WORK/syn")"; fi
done

section "setup_network.sh start"
$SETUP start > "$WORK/out.start" 2>&1 || { fail "start exited non-zero"; cat "$WORK/out.start"; }
check "WAN pinned via hotspot.env, no route probe needed" 0 "^ip route show default"
check "forward jump installed once" 1 "iptables -t filter -I FORWARD 1 -j HS_FWD"
check "default-deny lives in HS_FWD exactly once" 1 "iptables -t filter -A HS_FWD -i ap0 -j DROP"
check "NO blanket LAN->WAN accept (the old bypass)" 0 "iptables .*-i ap0 -o ccmni0 -j ACCEPT"
check "MASQUERADE scoped to the LAN subnet" 1 "iptables -t nat -I POSTROUTING 1 -o ccmni0 -s 10.66.0.0/24 -j MASQUERADE"
check "return traffic allowed" 1 "iptables -t filter -A HS_FWD -o ap0 -d 10.66.0.0/24 -m state --state RELATED,ESTABLISHED -j ACCEPT"
check "port 80 redirected to the local portal" 1 "iptables -t nat -A HS_NAT -i ap0 -p tcp --dport 80 -j REDIRECT --to-ports 8080"
check "port 80 is NOT DNATed to a hardcoded :8080" 0 "iptables -t nat -[AI] .*dport 80 -j DNAT"
check "port 443 is NOT DNATed to plaintext" 0 "dport 443 -j DNAT"
check "unauthorized HTTPS is reset, not silently dropped" 1 "iptables -t filter -A HS_FWD -i ap0 -p tcp --dport 443 -j REJECT --reject-with tcp-reset"
check "portal port accepted on INPUT" 1 "iptables -t filter -A HS_IN -i ap0 -p tcp --dport 8080 -j ACCEPT"
check "DHCP accepted on INPUT" 1 "iptables -t filter -A HS_IN -i ap0 -p udp --dport 67 -j ACCEPT"
check "IPv6 forward bypass closed" 1 "^ip6tables -I FORWARD 1 -i ap0 -j DROP"
check "fallback address assigned when the AP has none" 1 "ip addr add 10.66.0.1/24 dev ap0"
check "dnsmasq started" 1 "^dnsmasq "
check "dnsmasq has an upstream resolver" 1 "dnsmasq .*--no-resolv .*--server=8.8.8.8"
check "dnsmasq daemonises (no --no-daemon)" 0 "dnsmasq .*--no-daemon"
check "dnsmasq serves DHCP reservations via hostsfile" 1 "dnsmasq .*--dhcp-hostsfile="
check "dnsmasq does not use dhcp-hostsdir (absent on Android 2.51)" 0 "dhcp-hostsdir"
check "DHCP offers are broadcast (unicast offers never reach MediaTek clients)" 1 "dnsmasq .*--dhcp-broadcast"
check "dnsmasq lease is short so statics take effect" 1 "dhcp-range=10.66.0.10,10.66.0.250,10m"
check "no global iptables flush" 0 "iptables( -t [^ ]+)? -F$"
if grep -q '^LAN_IP=10.66.0.1$' "$STATE/hotspot.runtime" && grep -q '^DHCP_OWNER=ours$' "$STATE/hotspot.runtime"; then
    pass "runtime file records the gateway and DHCP owner"
else
    fail "runtime file missing gateway/owner: $(cat "$STATE/hotspot.runtime" 2>/dev/null)"
fi

section "authorize is idempotent"
$SETUP authorize AA:BB:CC:DD:EE:FF 10.66.0.10 10.66.0.137 >/dev/null 2>&1
$SETUP authorize AA:BB:CC:DD:EE:FF 10.66.0.10 10.66.0.137 >/dev/null 2>&1
$SETUP authorize AA:BB:CC:DD:EE:FF 10.66.0.10 10.66.0.137 >/dev/null 2>&1
check "one MAC ACCEPT after 3 calls (not tied to the reserved IP)" 1 "HS_FWD 1 -i ap0 -m mac --mac-source aa:bb:cc:dd:ee:ff -j ACCEPT"
check "portal exemption is by MAC, once" 1 "HS_NAT 1 -i ap0 -m mac --mac-source aa:bb:cc:dd:ee:ff -j RETURN"
if [ "$(wc -l < "$STATE/authorized_macs.txt")" = "1" ]; then
    pass "authorized_macs.txt has no duplicate lines"
else
    fail "authorized_macs.txt grew to $(wc -l < "$STATE/authorized_macs.txt") lines"
fi

section "ordering: ACCEPTs sit above the deny"
python3 - "$STUB_RULES" <<'PY'
import json, sys
db = json.load(open(sys.argv[1]))
fwd = [" ".join(r) for r in db["filter"]["HS_FWD"]]
deny = next(i for i, r in enumerate(fwd) if r == "-i ap0 -j DROP")
accepts = [i for i, r in enumerate(fwd) if r.startswith("-i ap0 -m mac") and r.endswith("-j ACCEPT")]
print("  HS_FWD chain:", *fwd, sep="\n    ")
assert accepts, "no per-client ACCEPT rules"
assert max(accepts) < deny, "an ACCEPT sits below the DROP rule"
reset = next(i for i, r in enumerate(fwd) if "--dport 443" in r and r.endswith("-j REJECT --reject-with tcp-reset"))
assert max(accepts) < reset < deny, "the 443 reset must sit between the client ACCEPTs and the deny"
# A rule that also required the reserved source IP would miss the lease the
# client still holds, and the MAC RETURN would already have skipped the portal.
assert not any("-s 10.66.0.10" in r and r.endswith("-j ACCEPT") for r in fwd)
PY
[ $? -eq 0 ] && pass "every client ACCEPT precedes the default-deny DROP" || fail "rule ordering is wrong"

nat_order=$(python3 - "$STUB_RULES" <<'PY'
import json, sys
db = json.load(open(sys.argv[1]))
pre = [" ".join(r) for r in db["nat"]["HS_NAT"]]
ret = next(i for i, r in enumerate(pre) if r.endswith("-j RETURN"))
redir = next(i for i, r in enumerate(pre) if "REDIRECT" in r and "--dport 80" in r)
print("ok" if ret < redir else "bad")
PY
)
[ "$nat_order" = "ok" ] && pass "portal RETURN precedes the port-80 redirect" || fail "authorized clients would still be redirected"

section "reserve / unreserve"
$SETUP reserve AA:BB:CC:DD:EE:FF 10.66.0.10 > "$WORK/out.reserve" 2>&1
if grep -q '^aa:bb:cc:dd:ee:ff,10.66.0.10$' "$STATE/dhcp_hosts" 2>/dev/null; then
    pass "DHCP reservation written (client actually gets the assigned IP)"
else
    fail "no dhcp-host reservation in $STATE/dhcp_hosts"
fi
if grep -q "reloaded" "$WORK/out.reserve" 2>/dev/null; then pass "dnsmasq told to reload reservations"; else fail "reserve did not reload dnsmasq"; fi

section "deauthorize removes every copy"
$SETUP deauthorize AA:BB:CC:DD:EE:FF >/dev/null 2>&1
left=$(python3 - "$STUB_RULES" <<'PY'
import json, sys
db = json.load(open(sys.argv[1]))
n = sum(1 for t in db.values() for c in t.values() for r in c if "aa:bb:cc:dd:ee:ff" in " ".join(r))
print(n)
PY
)
[ "$left" = "0" ] && pass "no rules left for the deauthorized MAC" || fail "$left rules survived deauthorize"

section "stop only removes our own rules"
: > "$LOG"
$SETUP stop >/dev/null 2>&1
check "no global flush on stop" 0 "^iptables -F"
check "no FORWARD policy change on stop" 0 "iptables .* -P FORWARD"
remaining=$(python3 - "$STUB_RULES" <<'PY'
import json, sys
db = json.load(open(sys.argv[1]))
print(sum(1 for t in db.values() for c in t.values() for r in c if "ap0" in " ".join(r)))
PY
)
[ "$remaining" = "0" ] && pass "all gateway rules removed" || fail "$remaining rules still reference ap0"
check "shaper torn down by stop" 1 "^tc qdisc del dev ap0 root"

section "adopts Android's hotspot address instead of replacing it"
: > "$LOG"
export STUB_LAN_ADDR="10: ap0    inet 192.168.43.1/24 brd 192.168.43.255 scope global ap0"
$SETUP start > "$WORK/out.adopt" 2>&1 || { fail "adopt start exited non-zero"; cat "$WORK/out.adopt"; }
check "does not flush a working hotspot address" 0 "ip addr flush"
check "does not overwrite it with 10.66.0.1" 0 "ip addr add 10.66.0.1/24"
check "MASQUERADE follows the adopted subnet" 1 "iptables -t nat -I POSTROUTING 1 -o ccmni0 -s 192.168.43.0/24 -j MASQUERADE"
check "DHCP range follows the adopted subnet" 1 "dhcp-range=192.168.43.10,192.168.43.250,10m"
if grep -q '^LAN_IP=192.168.43.1$' "$STATE/hotspot.runtime"; then
    pass "runtime gateway is the address Android already assigned"
else
    fail "runtime did not adopt 192.168.43.1: $(cat "$STATE/hotspot.runtime" 2>/dev/null)"
fi
$SETUP stop >/dev/null 2>&1
unset STUB_LAN_ADDR

section "bandwidth_control.sh"
$SHAPER init >/dev/null 2>&1
$SHAPER add 10.66.0.10 101 2000 3000 >/dev/null 2>&1
$SHAPER add 10.66.0.11 102 1000 1500 >/dev/null 2>&1
$SHAPER remove 10.66.0.10 101 >/dev/null 2>&1
$SHAPER add 10.66.0.10 103 4000 6000 >/dev/null 2>&1
$SHAPER add 10.66.0.10 103 4000 6000 >/dev/null 2>&1
python3 - "$STUB_TC" <<'PY'
import json, sys
db = json.load(open(sys.argv[1]))
classes = db["classes"]
filters = db["filters"]
print("  classes:", classes)
assert len(classes) == len(set(classes)), f"duplicate class ids: {classes}"
assert "1:101" not in classes, "removed class still present"
assert classes == ["1:1", "1:999", "1:102", "1:103"], f"unexpected tree: {classes}"
assert classes.count("1:103") == 1, "duplicate add created two classes"
dl = [f for f in filters if "match ip dst 10.66.0.10/32" in f]
ul = [f for f in filters if "match ip src 10.66.0.10/32" in f and "police" in f]
assert dl, "no download filter for 10.66.0.10"
assert ul, "no upload police filter for 10.66.0.10 (uploads would be uncapped)"
assert any("handle ffff: ingress" in q for q in db["qdiscs"]), "no ingress qdisc: uploads cannot be policed"
assert not [f for f in filters if "10.66.0.11" not in f and "10.66.0.10" not in f]
PY
[ $? -eq 0 ] && pass "tc tree rebuilt cleanly: no duplicate or dangling classes, both directions shaped" \
             || fail "tc state is wrong"

$SHAPER add 192.168.43.50 20103 1000 1500 >/dev/null 2>&1
$SHAPER remove-class 20103 >/dev/null 2>&1
python3 - "$STUB_TC" <<'PY'
import json, sys
db = json.load(open(sys.argv[1]))
assert "1:20103" not in db["classes"], db["classes"]
assert "1:103" in db["classes"], "remove-class dropped an unrelated class"
assert not any("192.168.43.50" in f for f in db["filters"]), db["filters"]
PY
[ $? -eq 0 ] && pass "remove-class drops only that class id" || fail "remove-class left the transitional class"

section "policy routing: a self-managed AP interface reaches the internet"
# Android ends its ip rule list with an "unreachable" rule. Traffic forwarded
# from an interface WE brought up (WiFi Direct group, local-only hotspot, root
# hostapd) has no fwmark and no local UID, so without these rules the client has
# an IP, sees the sign-in page, and still gets nothing from the internet.
: > "$LOG"; : > "$STUB_IPRULES"
$SETUP start > "$WORK/out.route" 2>&1 || { fail "start (routing scenario) exited non-zero"; cat "$WORK/out.route"; }
check "LAN-in rule added to the main table" 1 "ip rule add pref 15500 iif ap0 lookup main"
check "return-traffic rule added for the subnet" 1 "ip rule add pref 15501 to 10.66.0.0/24 lookup main"
if grep -q "iif ap0 lookup main" "$STUB_IPRULES"; then
    pass "the rule is actually present in the routing table"
else
    fail "no iif rule recorded: $(cat "$STUB_IPRULES" 2>/dev/null)"
fi

: > "$LOG"
$SETUP keepalive > "$WORK/out.ka1" 2>&1
$SETUP keepalive > "$WORK/out.ka2" 2>&1
check "keepalive does not pile up duplicate rules" 0 "ip rule add pref 1550"

section "route subcommand (re-apply after an interface change)"
: > "$LOG"
$SETUP route p2p-wlan0-0 192.168.49.0/24 > "$WORK/out.route2" 2>&1
$SETUP route p2p-wlan0-0 192.168.49.0/24 > "$WORK/out.route3" 2>&1
check "rules follow the interface the app is using now" 1 "ip rule add pref 15500 iif p2p-wlan0-0 lookup main"
check "subnet rule follows too" 1 "ip rule add pref 15501 to 192.168.49.0/24 lookup main"

section "stop removes the routing rules"
: > "$LOG"
$SETUP stop > "$WORK/out.stoproute" 2>&1
check "our rule priorities are released on stop" 2 "ip rule del pref 1550"

section "DHCP port 67 held by the system: DNS-only fallback"
# Android 10+ serves DHCP for WiFi Direct / local-only hotspots from inside
# system_server, so no dnsmasq process exists to take over from. Port 53 must
# still be ours or the client's connectivity probe cannot resolve and the
# sign-in sheet never appears.
: > "$LOG"
STUB_PORT67_TAKEN=1 $SETUP start > "$WORK/out.dnsonly" 2>&1 || { fail "DNS-only start exited non-zero"; cat "$WORK/out.dnsonly"; }
if grep -q '^DHCP_OWNER=ours-dns$' "$STATE/hotspot.runtime"; then
    pass "runtime records DHCP_OWNER=ours-dns"
else
    fail "expected DHCP_OWNER=ours-dns, got: $(cat "$STATE/hotspot.runtime" 2>/dev/null)"
fi
if grep -E "^dnsmasq " "$LOG" | grep -v -- "--dhcp-range" | grep -q -- "--no-resolv"; then
    pass "a DNS-only dnsmasq was started (no --dhcp-range, so it cannot clash on port 67)"
else
    fail "no DNS-only dnsmasq invocation in the log"
fi
if grep -q "DNS-only" "$WORK/out.dnsonly"; then
    pass "the log says DNS-only mode was used"
else
    fail "no DNS-only message in the output: $(cat "$WORK/out.dnsonly")"
fi
if grep -E "^dnsmasq " "$LOG" | grep -v -- "--dhcp-range" | grep -q -- "--listen-address=10.66.0.1"; then
    pass "the DNS-only dnsmasq listens on the gateway address"
else
    fail "the DNS-only dnsmasq is not bound to the gateway address"
fi

section "netshare_ap.sh: root hostapd, no hotspot toggle"
: > "$LOG"
$NETSHARE start "RNS-Test" "password123" > "$WORK/out.netshare" 2>&1 \
    || { fail "netshare start exited non-zero"; cat "$WORK/out.netshare"; }
# The uplink in this stub is ccmni0 (mobile). That is not a WiFi radio — the
# Hot 8 log died here (`sta=ccmni0 iw=none`). ap0 already exists, so the script
# reuses it instead of asking `iw` to add an interface on the mobile uplink.
check "does not create an AP on the mobile uplink" 0 "iw dev ccmni0 interface add"
if [ -f "$STATE/netshare_hostapd.conf" ]; then
    conf_ok=1
    grep -q '^interface=ap0$'         "$STATE/netshare_hostapd.conf" || conf_ok=0
    grep -q '^ssid=RNS-Test$'          "$STATE/netshare_hostapd.conf" || conf_ok=0
    grep -q '^wpa_passphrase=password123$' "$STATE/netshare_hostapd.conf" || conf_ok=0
    grep -q '^channel=6$'              "$STATE/netshare_hostapd.conf" || conf_ok=0
    grep -q '^wpa=2$'                  "$STATE/netshare_hostapd.conf" || conf_ok=0
    [ "$conf_ok" = "1" ] && pass "hostapd.conf has the interface, SSID, passphrase, channel and WPA2" \
                         || { fail "hostapd.conf is wrong:"; cat "$STATE/netshare_hostapd.conf"; }
else
    fail "no hostapd.conf was written"
fi
if [ -s "$STATE/netshare_hostapd.pid" ] && kill -0 "$(cat "$STATE/netshare_hostapd.pid")" 2>/dev/null; then
    pass "hostapd was started in the background and is alive"
else
    fail "hostapd is not running (pidfile: $(cat "$STATE/netshare_hostapd.pid" 2>/dev/null))"
fi
if grep -q '^IFACE=ap0$' "$WORK/out.netshare" && grep -q '^SSID=RNS-Test$' "$WORK/out.netshare"; then
    pass "stdout reports the interface and SSID the app parses"
else
    fail "stdout is not parseable: $(cat "$WORK/out.netshare")"
fi
if grep -q "mobile data" "$WORK/out.netshare"; then
    pass "the log says the mobile uplink is not a WiFi radio"
else
    fail "did not explain that the uplink is mobile: $(cat "$WORK/out.netshare")"
fi
if [ -f "$STATE/netshare.runtime" ] && grep -q '^MODE=root-hostapd$' "$STATE/netshare.runtime"; then
    pass "netshare.runtime written"
else
    fail "netshare.runtime missing/wrong: $(cat "$STATE/netshare.runtime" 2>/dev/null)"
fi
$NETSHARE start "RNS-Test" "short" > "$WORK/out.short" 2>&1 \
    && fail "a 5-character passphrase was accepted" \
    || pass "a too-short passphrase is refused before touching the driver"

: > "$LOG"
$NETSHARE stop > "$WORK/out.netstop" 2>&1
check "stop does not delete an AP interface this script did not create" 0 "iw dev .* del"
if grep -q "leaving ap0" "$WORK/out.netstop"; then
    pass "stop leaves the adopted ap0 in place"
else
    fail "stop did not say it was leaving ap0: $(cat "$WORK/out.netstop")"
fi
if [ ! -f "$STATE/netshare_hostapd.pid" ] && [ ! -f "$STATE/netshare.runtime" ]; then
    pass "stop cleans up the pidfile and runtime file"
else
    fail "stop left state behind"
fi

section "foreign-dhcp / procs report cleanly"
: > "$LOG"
if [ "$($SETUP foreign-dhcp 2>/dev/null)" = "no" ]; then
    pass "foreign-dhcp says 'no' when no other DHCP server runs"
else
    fail "foreign-dhcp answered: $($SETUP foreign-dhcp 2>/dev/null)"
fi
$SETUP procs > "$WORK/out.procs" 2>&1 || fail "procs exited non-zero"
$SETUP diag > "$WORK/out.diag" 2>&1 || fail "diag exited non-zero"
for needed in "policy routing" "dhcp / ap processes" "arp" "netshare ap runtime"; do
    if grep -q "$needed" "$WORK/out.diag"; then pass "diag report includes: $needed"; else fail "diag report is missing: $needed"; fi
done

section "teardown"
$SHAPER stop >/dev/null 2>&1
[ -s "$STATE/hotspot_tc.state" ] && fail "state file not cleared" || pass "shaper state cleared"

printf '\n%s\n' "----------------------------------------"
if [ "$FAILURES" = "0" ]; then
    echo "ALL CHECKS PASSED"
    rm -rf "$WORK"
    exit 0
else
    echo "$FAILURES CHECK(S) FAILED (artifacts kept in $WORK)"
    exit 1
fi
