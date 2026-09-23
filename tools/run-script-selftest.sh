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
export PATH="$STUBS:$PATH"

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
case "$*" in
  "route show default") echo "default via 192.168.1.1 dev ccmni0 metric 1" ;;
  "-o link show up")    printf '1: lo: <LOOPBACK,UP> mtu 65536\n3: ccmni0: <NOARP,UP,LOWER_UP> mtu 1500\n10: ap0: <BROADCAST,MULTICAST,UP> mtu 1500\n' ;;
  "link show ap0")      echo "10: ap0: <BROADCAST,MULTICAST,UP> mtu 1500" ;;
  "-o -4 addr show dev ap0") echo "10: ap0    inet 192.168.43.1/24 brd 192.168.43.255 scope global ap0" ;;
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
for a in "$@"; do case "$a" in --pid-file=*) pidfile="${a#--pid-file=}";; esac; done
[ -n "$pidfile" ] || { echo "stub: no --pid-file" >&2; exit 1; }
# Ignore SIGHUP (like real dnsmasq, which reloads on it) so the reserve test works.
sh -c 'trap "" HUP; exec sleep 300' &
echo $! > "$pidfile"
exit 0
EOF

chmod +x "$STUBS"/*
SETUP="sh $ROOT/scripts/setup_network.sh"
SHAPER="sh $ROOT/scripts/bandwidth_control.sh"

# ------------------------------------------------------------------- scenarios
section "syntax"
for f in "$ROOT"/scripts/*.sh; do
    if sh -n "$f" 2>"$WORK/syn"; then pass "$(basename "$f") parses with sh -n"; else fail "$(basename "$f"): $(cat "$WORK/syn")"; fi
done

section "setup_network.sh start"
$SETUP start > "$WORK/out.start" 2>&1 || { fail "start exited non-zero"; cat "$WORK/out.start"; }
check "WAN pinned via hotspot.env, no route probe needed" 0 "^ip route show default"
check "default-deny FORWARD rule present exactly once" 1 "iptables -t filter -I FORWARD 1 -i ap0 -j DROP"
check "NO blanket LAN->WAN accept (the old bypass)" 0 "iptables .*-i ap0 -o ccmni0 -j ACCEPT"
check "MASQUERADE scoped to the LAN subnet" 1 "iptables -t nat -I POSTROUTING 1 -o ccmni0 -s 10.66.0.0/24 -j MASQUERADE"
check "return traffic allowed" 1 "iptables -t filter -I FORWARD 1 -o ap0 -d 10.66.0.0/24 -m state --state RELATED,ESTABLISHED -j ACCEPT"
check "port 80 DNAT to the portal" 1 "iptables -t nat -I PREROUTING 1 -i ap0 -p tcp --dport 80 -j DNAT"
check "port 443 is NOT DNATed to plaintext" 0 "dport 443 -j DNAT"
check "unauthorized HTTPS is reset, not silently dropped" 1 "iptables -t filter -I FORWARD 1 -i ap0 -p tcp --dport 443 -j REJECT --reject-with tcp-reset"
check "IPv6 forward bypass closed" 1 "^ip6tables -I FORWARD 1 -i ap0 -j DROP"
check "dnsmasq started" 1 "^dnsmasq "
check "dnsmasq has an upstream resolver" 1 "dnsmasq .*--no-resolv .*--server=8.8.8.8"
check "dnsmasq daemonises (no --no-daemon)" 0 "dnsmasq .*--no-daemon"
check "dnsmasq serves DHCP reservations" 1 "dnsmasq .*--dhcp-hostsdir="
check "dnsmasq lease is short so statics take effect" 1 "dhcp-range=10.66.0.10,10.66.0.250,10m"
check "no global iptables flush" 0 "iptables .* -F($| )"

section "authorize is idempotent"
$SETUP authorize AA:BB:CC:DD:EE:FF 10.66.0.10 10.66.0.137 >/dev/null 2>&1
$SETUP authorize AA:BB:CC:DD:EE:FF 10.66.0.10 10.66.0.137 >/dev/null 2>&1
$SETUP authorize AA:BB:CC:DD:EE:FF 10.66.0.10 10.66.0.137 >/dev/null 2>&1
check "one ACCEPT for the reserved IP after 3 calls" 1 "FORWARD 1 -i ap0 -m mac --mac-source aa:bb:cc:dd:ee:ff -s 10.66.0.10 -j ACCEPT"
check "one transitional ACCEPT for the old lease" 1 "FORWARD 1 -i ap0 -m mac --mac-source aa:bb:cc:dd:ee:ff -s 10.66.0.137 -j ACCEPT"
check "one portal RETURN for the MAC" 1 "PREROUTING 1 -i ap0 -m mac --mac-source aa:bb:cc:dd:ee:ff -j RETURN"
if [ "$(wc -l < "$STATE/authorized_macs.txt")" = "1" ]; then
    pass "authorized_macs.txt has no duplicate lines"
else
    fail "authorized_macs.txt grew to $(wc -l < "$STATE/authorized_macs.txt") lines"
fi

section "ordering: ACCEPTs sit above the deny"
python3 - "$STUB_RULES" <<'PY'
import json, sys
db = json.load(open(sys.argv[1]))
fwd = [" ".join(r) for r in db["filter"]["FORWARD"]]
deny = next(i for i, r in enumerate(fwd) if r == "-i ap0 -j DROP")
accepts = [i for i, r in enumerate(fwd) if r.startswith("-i ap0 -m mac")]
print("  FORWARD chain:", *fwd, sep="\n    ")
assert accepts, "no per-client ACCEPT rules"
assert max(accepts) < deny, "an ACCEPT sits below the DROP rule"
reset = next(i for i, r in enumerate(fwd) if "--dport 443" in r and r.endswith("-j REJECT --reject-with tcp-reset"))
assert max(accepts) < reset < deny, "the 443 reset must sit between the client ACCEPTs and the deny"
PY
[ $? -eq 0 ] && pass "every client ACCEPT precedes the default-deny DROP" || fail "rule ordering is wrong"

nat_order=$(python3 - "$STUB_RULES" <<'PY'
import json, sys
db = json.load(open(sys.argv[1]))
pre = [" ".join(r) for r in db["nat"]["PREROUTING"]]
ret = next(i for i, r in enumerate(pre) if r.endswith("-j RETURN"))
dnat = next(i for i, r in enumerate(pre) if "DNAT" in r)
print("ok" if ret < dnat else "bad")
PY
)
[ "$nat_order" = "ok" ] && pass "portal RETURN precedes the DNAT rule" || fail "authorized clients would still be redirected"

section "reserve / unreserve"
$SETUP reserve AA:BB:CC:DD:EE:FF 10.66.0.10 > "$WORK/out.reserve" 2>&1
if grep -q "dhcp-host=aa:bb:cc:dd:ee:ff,10.66.0.10" "$STATE/dhcp_hosts.d/aa-bb-cc-dd-ee-ff" 2>/dev/null; then
    pass "DHCP reservation written (client actually gets the assigned IP)"
else
    fail "no dhcp-host reservation file"
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
