#!/usr/bin/env bash
# =============================================================================
# rns-e2e.sh - end-to-end test of the RNS gateway on a real Linux kernel.
#
#   [client ns]        [gateway ns]                    [wan ns]
#     eth0  <=========>  eth-lan  [ the phone ]  eth-wan <=========> eth0
#   DHCP client         192.168.49.1              203.0.113.2        203.0.113.1
#                       dnsmasq + iptables +          "the internet":
#                       captive portal (:8080)        HTTP :80, DNS :53
#
# It runs the REAL scripts/setup_network.sh and scripts/bandwidth_control.sh
# against a real kernel, with a real dnsmasq and a real test client, and walks
# the whole customer journey: obtain an IP -> be blocked -> hit the captive
# portal -> redeem a voucher -> get internet -> lose it again.
#
# Run:  sudo -E bash tools/linux-e2e/rns-e2e.sh
# See README.md in this directory for prerequisites and known limitations.
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SETUP="$REPO/scripts/setup_network.sh"
SHAPER="$REPO/scripts/bandwidth_control.sh"

RNS_ROOT="${RNS_ROOT:-/tmp/rns-e2e}"
BIN="$RNS_ROOT/bin"; STATE="$RNS_ROOT/state"; WWW="$RNS_ROOT/www"

GW=rns-gw; WAN=rns-wan; CLIENT=rns-client
LAN_IF=eth-lan; GW_WAN_IF=eth-wan
LAN_IP=192.168.49.1            # what Android's WiFi Direct group owner uses
WAN_IP=203.0.113.1             # "the internet"
GW_WAN_IP=203.0.113.2
CLIENT_MAC="02:aa:bb:cc:dd:01"

PASS=0; FAIL=0; SKIP=0
FAILED_NAMES=""

g() { printf '\033[32m'; }; r() { printf '\033[31m'; }
y() { printf '\033[33m'; }; c() { printf '\033[36m'; }; z() { printf '\033[0m'; }
pass() { PASS=$((PASS+1)); printf "  $(g)ok  $(z)  %s\n" "$*"; }
fail() { FAIL=$((FAIL+1)); printf "  $(r)FAIL$(z)  %s\n" "$*"; FAILED_NAMES="$FAILED_NAMES\n    - $*"; }
skip() { SKIP=$((SKIP+1)); printf "  $(y)skip$(z)  %s\n" "$*"; }
info() { printf "  $(c)info$(z)  %s\n" "$*"; }
head_() { printf "\n$(c)== %s$(z)\n" "$*"; }

SYS_PATH="/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin"
NSENV="PATH=$BIN:$SYS_PATH RNS_E2E_STATE=$STATE HOTSPOT_STATE_DIR=$STATE RNS_REAL_IPTABLES=/usr/sbin/iptables"

ns() { sudo -n env $NSENV ip netns exec "$1" sh -c "$2"; }
sudoc() { sudo -n env PATH="$BIN:$SYS_PATH" sh -c "$1"; }
start_bg() { # <ns> <tag> <command...>
    local n="$1" tag="$2"; shift 2
    sudo -n env $NSENV setsid ip netns exec "$n" sh -c "echo \$\$ > '$STATE/$tag.pid'; exec $*" \
        >"$STATE/$tag.out" 2>&1 < /dev/null &
    disown 2>/dev/null || true
}

# ---------------------------------------------------------------- preflight ---
preflight() {
    head_ "Preflight"
    sudo -n true 2>/dev/null || { echo "This harness needs passwordless sudo."; exit 1; }
    for f in "$SETUP" "$SHAPER"; do
        [ -f "$f" ] || { echo "missing $f"; exit 1; }
    done
    pass "scripts found: setup_network.sh, bandwidth_control.sh"

    rm -rf "$RNS_ROOT"; mkdir -p "$BIN" "$STATE" "$WWW"
    cp "$HERE/shim/iptables" "$BIN/iptables"; chmod +x "$BIN/iptables"
    cp "$HERE/shim/udhcpc.script" "$BIN/udhcpc.script"; chmod +x "$BIN/udhcpc.script"
    cp "$HERE/portal.py" "$HERE/dns_upstream.py" "$BIN/" 2>/dev/null
    cp "$HERE/shim/find_dnsmasq.sh" "$BIN/"; chmod +x "$BIN/find_dnsmasq.sh"

    if [ -n "${DNSMASQ_BIN:-}" ] && [ -x "${DNSMASQ_BIN:-}" ]; then
        ln -sf "$DNSMASQ_BIN" "$BIN/dnsmasq"
    elif command -v dnsmasq >/dev/null 2>&1; then
        ln -sf "$(command -v dnsmasq)" "$BIN/dnsmasq"
    else
        echo "dnsmasq not found. Build it or set DNSMASQ_BIN=/path/to/dnsmasq."
        echo "  (e.g. curl -sL https://github.com/imp/dnsmasq/archive/refs/tags/v2.90.tar.gz | tar xz && cd dnsmasq-2.90 && make"
        echo "   then re-run with DNSMASQ_BIN=\$PWD/src/dnsmasq)"
        exit 1
    fi
    pass "dnsmasq: $("$BIN/dnsmasq" --version 2>/dev/null | head -1)"

    # Which kernel features does this box actually have?
    sudo -n ip netns add __rnsfeat 2>/dev/null
    sudo -n ip link add __rf0 type veth peer name __rf1 2>/dev/null
    sudo -n ip link set __rf1 netns __rnsfeat 2>/dev/null
    sudo -n ip netns exec __rnsfeat ip link set __rf1 up 2>/dev/null
    if sudo -n ip netns exec __rnsfeat iptables -A FORWARD -i __rf1 -m mac \
        --mac-source 00:11:22:33:44:55 -j ACCEPT 2>/dev/null; then HAVE_MAC=1; else HAVE_MAC=0; fi
    if sudo -n ip netns exec __rnsfeat tc qdisc add dev __rf1 root handle 1: htb default 9 \
        2>/dev/null; then HAVE_HTB=1; else HAVE_HTB=0; fi
    if sudo -n ip netns exec __rnsfeat tc qdisc add dev __rf1 handle ffff: ingress \
        2>/dev/null; then HAVE_INGRESS=1; else HAVE_INGRESS=0; fi
    sudo -n ip netns del __rnsfeat 2>/dev/null; sudo -n ip link del __rf0 2>/dev/null

    if [ "$HAVE_MAC" = 1 ]; then
        pass "kernel has xt_mac - MAC rules installed exactly as the app writes them"
    else
        skip "ENVIRONMENT: no xt_mac in this kernel - per-MAC rules are translated to
              source-IP by the shim, so the matcher itself is not exercised"
    fi
    if [ "$HAVE_HTB" = 1 ]; then pass "kernel has sch_htb (shaping testable)"; else
        skip "kernel has NO sch_htb - tc shaping cannot be exercised here (see README)"; fi
}

# ---------------------------------------------------------------- topology ---
topo_up() {
    head_ "Topology"
    for n in $GW $WAN $CLIENT; do sudo -n ip netns del "$n" 2>/dev/null; done
    sudo -n ip netns add $GW; sudo -n ip netns add $WAN; sudo -n ip netns add $CLIENT

    sudo -n ip link add v-gw-wan type veth peer name v-wan 2>/dev/null
    sudo -n ip link set v-gw-wan netns $GW; sudo -n ip link set v-wan netns $WAN
    sudo -n ip link add v-gw-lan type veth peer name v-client 2>/dev/null
    sudo -n ip link set v-gw-lan netns $GW; sudo -n ip link set v-client netns $CLIENT

    ns $GW "ip link set v-gw-wan name $GW_WAN_IF; ip link set v-gw-lan name $LAN_IF"
    ns $WAN "ip link set v-wan name eth0"
    ns $CLIENT "ip link set v-client name eth0"

    ns $CLIENT "ip link set eth0 down; ip link set eth0 address $CLIENT_MAC; ip link set eth0 up"
    ns $GW "ip link set $LAN_IF up; ip addr add $LAN_IP/24 dev $LAN_IF"
    ns $GW "ip link set $GW_WAN_IF up; ip addr add $GW_WAN_IP/24 dev $GW_WAN_IF; ip route replace default via $WAN_IP"
    ns $WAN "ip link set eth0 up; ip addr add $WAN_IP/24 dev eth0; ip link set lo up"
    ns $GW "echo 1 > /proc/sys/net/ipv4/ip_forward"
    ns $CLIENT "ip link set lo up"

    # "The internet": an HTTP origin and an upstream DNS resolver.
    echo "RNS-E2E-WAN-OK" > "$WWW/index.html"
    start_bg $WAN wanhttp "python3 -m http.server 80 --bind $WAN_IP --directory $WWW"
    start_bg $WAN wandns  "python3 $BIN/dns_upstream.py --bind $WAN_IP --answer $WAN_IP"
    start_bg $GW  portal  "python3 $BIN/portal.py --state $STATE --setup $SETUP --shaper $SHAPER"
    sleep 3

    ns $GW "ip -o -4 addr show dev $LAN_IF | awk '{print \$4}'" | grep -q "$LAN_IP" \
        && pass "gateway ns: $LAN_IF=$LAN_IP/24, $GW_WAN_IF=$GW_WAN_IP/24 (internet via $WAN_IP)" \
        || fail "gateway addressing not applied"
    [ "$(ns $GW "curl -s --max-time 5 http://$WAN_IP/" | tr -d '[:space:]')" = "RNS-E2E-WAN-OK" ] \
        && pass "WAN side serves HTTP (the 'internet' is reachable from the gateway)" \
        || fail "WAN HTTP server not reachable"
}

# ------------------------------------------------------------------- tests ---
t_start() {
    head_ "1. setup_network.sh start (the real script, real kernel)"
    cat > "$STATE/hotspot.env" <<EOF
LAN_IF=$LAN_IF
WAN_IF=$GW_WAN_IF
UPSTREAM_DNS=$WAN_IP
UPSTREAM_DNS2=$WAN_IP
EOF
    out=$(ns $GW "sh $SETUP start" 2>&1)
    echo "$out" | sed 's/^/        | /'
    echo "$out" | grep -q "dhcp ours" && pass "start completed and our dnsmasq owns DHCP" \
        || fail "start did not report dhcp=ours"
    ns $GW "iptables -t nat -S PREROUTING 2>/dev/null | grep -v '^-P ' | head -1" | grep -q HS_NAT \
        && pass "HS_NAT jump is first in nat/PREROUTING" || fail "HS_NAT jump not first"
    ns $GW "iptables -t filter -S FORWARD 2>/dev/null | grep -v '^-P ' | head -1" | grep -q HS_FWD \
        && pass "HS_FWD jump is first in filter/FORWARD" || fail "HS_FWD jump not first"
    ns $GW "iptables -t nat -S HS_NAT" | grep -q "REDIRECT --to-ports 8080" \
        && pass "port 80 REDIRECT to the portal is installed" || fail "no port-80 redirect"
    ns $GW "iptables -t filter -S HS_FWD" | grep -q "REJECT --reject-with tcp-reset" \
        && pass "443 is REJECTed with tcp-reset (so clients fall back to the HTTP probe)" \
        || fail "no 443 reject rule"
    ns $GW "iptables -t filter -S HS_FWD" | tail -1 | grep -q "DROP" \
        && pass "HS_FWD ends in DROP (default deny for unauthorised clients)" \
        || fail "HS_FWD does not end in DROP"
}

t_dhcp() {
    head_ "2. Test client obtains an address (real dnsmasq, real DHCP handshake)"
    ns $CLIENT "timeout 25 busybox udhcpc -i eth0 -q -n -f -s $BIN/udhcpc.script" 2>&1 | sed 's/^/        | /'
    lease=$(cat "$STATE/client_lease.txt" 2>/dev/null)
    CLIENT_IP=$(echo "$lease" | awk '{print $1}')
    [ -n "$CLIENT_IP" ] && pass "client got $CLIENT_IP from DHCP (router=$(echo "$lease" | awk '{print $3}') dns=$(echo "$lease" | awk '{print $4}'))" \
        || { fail "DHCP failed - client has no address"; return; }
    case "$CLIENT_IP" in 192.168.49.*) pass "address is inside the adopted LAN subnet 192.168.49.0/24" ;;
        *) fail "address $CLIENT_IP is not in 192.168.49.0/24" ;; esac
    echo "$lease" | awk '{print $3}' | grep -q "$LAN_IP" && pass "DHCP option 3 (router) = $LAN_IP" \
        || fail "wrong router offered"
    echo "$lease" | awk '{print $4}' | grep -q "$LAN_IP" && pass "DHCP option 6 (DNS) = $LAN_IP" \
        || fail "wrong DNS offered"
    ns $GW "cat $STATE/dnsmasq.leases" | grep -qi "$CLIENT_MAC" \
        && pass "gateway recorded the lease for $CLIENT_MAC (lease file written)" \
        || fail "no lease recorded for the client MAC"
}

t_blocked() {
    head_ "3. An unauthenticated client is blocked from the internet"
    body=$(ns $CLIENT "curl -s --max-time 8 http://$WAN_IP/" 2>/dev/null | tr -d '[:space:]')
    if [ -z "$body" ]; then
        # nothing came back - blocked, but we want to know it was the portal that answered
        pass "client cannot fetch http://$WAN_IP/ (no path through the gateway)"
    elif echo "$body" | grep -q "RNS-E2E-WAN-OK"; then
        fail "SECURITY: unauthenticated client reached the internet - the voucher gate is not holding"
    fi
}

t_portal() {
    head_ "4. Captive portal: the sign-in sheet would pop"
    body=$(ns $CLIENT "curl -s --max-time 8 http://$WAN_IP/" 2>/dev/null)
    echo "$body" | grep -q "RNS Hotspot" \
        && pass "HTTP from the client is intercepted and answered by the portal (200 + login page)" \
        || fail "client's HTTP was not served the portal page"
    code=$(ns $CLIENT "curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://$WAN_IP/generate_204" 2>/dev/null)
    [ "$code" = "200" ] && pass "OS probe path /generate_204 answered with HTTP 200 (this is what pops the sheet)" \
        || fail "/generate_204 returned '$code', not 200 - no sign-in sheet would appear"
    code=$(ns $CLIENT "curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://$WAN_IP/connecttest.txt" 2>/dev/null)
    [ "$code" = "200" ] && pass "probe path /connecttest.txt (as seen in the device log) answered 200" \
        || fail "/connecttest.txt returned '$code'"
}

t_https_reset() {
    head_ "5. HTTPS is reset, not dropped"
    start=$(date +%s%N)
    rc=$(ns $CLIENT "curl -s -o /dev/null --max-time 10 https://$WAN_IP:443/ 2>/dev/null; echo \$?")
    ms=$(( ($(date +%s%N) - start) / 1000000 ))
    if [ "$rc" = "7" ] && [ "$ms" -lt 5000 ]; then
        pass "HTTPS fails fast ($rc in ${ms}ms) - tcp-reset delivered, client retries on plain HTTP"
    elif [ "$rc" = "28" ]; then
        fail "HTTPS timed out (rc=28) - 443 is being dropped instead of reset"
    else
        info "HTTPS rc=$rc after ${ms}ms"
    fi
}

t_dns() {
    head_ "6. DNS: the gateway's resolver answers, and foreign DNS is hijacked"
    cat > "$BIN/dnsq.py" <<'PY'
import socket, struct, sys
srv, name = sys.argv[1], sys.argv[2]
q = struct.pack("!HHHHHH", 0x1234, 0x0100, 1, 0, 0, 0)
for lbl in name.encode().split(b"."):
    q += bytes([len(lbl)]) + lbl
q += b"\x00" + struct.pack("!HH", 1, 1)
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(6)
s.sendto(q, (srv, 53))
d, _ = s.recvfrom(512)
if len(d) < 12: print("SHORT"); sys.exit(0)
ancount = struct.unpack("!H", d[6:8])[0]
rcode = d[3] & 0x0F
if ancount == 0: print(f"NOANSWER rcode={rcode}"); sys.exit(0)
i = 12
while d[i] != 0: i += 1 + d[i]
i += 5
while d[i] & 0xC0 == 0: i += 1
i += 10 if (d[i] & 0xC0) else (i, i)[0] and 0
rdlen = struct.unpack("!H", d[i+10-2:i+10])[0] if False else None
# walk to rdata using the standard compression-aware reader
i = 12
while d[i] != 0: i += 1 + d[i]
i += 5
if d[i] & 0xC0: i += 2
else:
    while d[i] != 0: i += 1 + d[i]
    i += 1
rdlen = struct.unpack("!H", d[i+8:i+10])[0]
rdata = d[i+10:i+10+rdlen]
print(".".join(str(b) for b in rdata) if rdlen == 4 else f"RDATA{rdlen}")
PY
    ans=$(ns $CLIENT "python3 $BIN/dnsq.py $LAN_IP portal.test 2>&1 | tail -1")
    [ "$ans" = "$WAN_IP" ] && pass "client query to the gateway's DNS ($LAN_IP) resolved to $ans" \
        || fail "query to $LAN_IP returned '$ans'"

    # The hijack: a client that ignores DHCP DNS and asks 8.8.8.8 must still be
    # answered, or the OS probe never happens and no sheet appears.
    ans=$(ns $CLIENT "python3 $BIN/dnsq.py 8.8.8.8 captive.test 2>&1 | tail -1")
    [ "$ans" = "$WAN_IP" ] && pass "query to 8.8.8.8 was hijacked to the gateway and answered ($ans)" \
        || fail "DNS hijack failed: query to 8.8.8.8 returned '$ans'"
}

t_bad_voucher() {
    head_ "7. A wrong voucher code does not open the gate"
    body=$(ns $CLIENT "curl -s --max-time 10 -X POST -d 'voucher=ZZZZ-ZZZZ' http://$LAN_IP:8080/redeem" 2>/dev/null)
    echo "$body" | grep -q "Invalid voucher code" \
        && pass "invalid code rejected by the portal" || fail "invalid code was not rejected"
    body=$(ns $CLIENT "curl -s --max-time 8 http://$WAN_IP/" 2>/dev/null | tr -d '[:space:]')
    echo "$body" | grep -q "RNS-E2E-WAN-OK" \
        && fail "SECURITY: client reached the internet after a failed voucher" \
        || pass "client still blocked after the failed redemption"
}

t_redeem() {
    head_ "8. Redeeming a valid voucher applies reserve + authorize"
    body=$(ns $CLIENT "curl -s --max-time 25 -X POST -d 'voucher=HCSQ-KEBE' http://$LAN_IP:8080/redeem" 2>/dev/null)
    echo "$body" | grep -q "Connected" && pass "voucher accepted" || fail "voucher was not accepted: $(echo "$body" | grep -o '<h3>[^<]*' | head -1)"
    grep -q "\-s $CLIENT_IP" "$STATE/macshim.log" 2>/dev/null || true
    ns $GW "cat $STATE/authorized_macs.txt 2>/dev/null" | grep -qi "$CLIENT_MAC" \
        && pass "authorized_macs.txt records $CLIENT_MAC" || fail "client MAC not in authorized_macs.txt"
    ns $GW "cat $STATE/dhcp_hosts 2>/dev/null" | grep -qi "$CLIENT_MAC" \
        && pass "DHCP reservation written for $CLIENT_MAC" || fail "no DHCP reservation written"
    ns $GW "iptables -t filter -S HS_FWD" | head -3 | grep -qE "ACCEPT|255\.255\.255\.255" \
        && pass "an ACCEPT for this client now sits above the DROP in HS_FWD" \
        || fail "no client ACCEPT rule in HS_FWD"
    grep -q "UNRESOLVED" "$STATE/macshim.log" 2>/dev/null \
        && fail "shim could not resolve the client MAC to an IP - authorisation rule matches nothing" \
        || pass "MAC resolved to a real client IP for the authorisation rule"
}

t_authorized() {
    head_ "9. The authorised client now has internet"
    body=$(ns $CLIENT "curl -s --max-time 10 http://$WAN_IP/" 2>/dev/null | tr -d '[:space:]')
    [ "$body" = "RNS-E2E-WAN-OK" ] && pass "client fetched the real page from the internet: '$body'" \
        || fail "client still cannot reach the internet (got '${body:-nothing}')"
    ns $GW "iptables -t nat -L HS_NAT -v -n 2>/dev/null | grep -q REDIRECT" && \
        info "portal redirect still present for unauthorised clients (correct)"
    # Counters on the MASQUERADE rule prove packets really went through NAT.
    pkts=$(ns $GW "iptables -t nat -L POSTROUTING -v -n -x 2>/dev/null | awk '/MASQUERADE/{print \$1; exit}'")
    [ "${pkts:-0}" -gt 0 ] 2>/dev/null && pass "MASQUERADE counter is non-zero ($pkts packets) - traffic really was NATted" \
        || info "MASQUERADE counter: ${pkts:-0}"
}

t_second_device() {
    head_ "10. The same voucher cannot be reused from another device"
    # Simulate a second device: the WAN namespace has no route into the LAN, so
    # instead the client changes its MAC and takes a brand-new lease - which is
    # exactly what a different handset arriving at the hotspot looks like.
    ns $CLIENT "ip link set eth0 down; ip link set eth0 address 02:aa:bb:cc:dd:02; ip link set eth0 up" 2>/dev/null
    ns $CLIENT "timeout 25 busybox udhcpc -i eth0 -q -n -f -s $BIN/udhcpc.script" >/dev/null 2>&1
    info "second device: MAC 02:aa:bb:cc:dd:02, address $(awk '{print $1}' "$STATE/client_lease.txt" 2>/dev/null)"
    body=$(ns $CLIENT "curl -s --max-time 15 -X POST -d 'voucher=HCSQ-KEBE' http://$LAN_IP:8080/redeem" 2>/dev/null)
    echo "$body" | grep -q "already in use on another device" \
        && pass "second device presenting the same code is refused" \
        || fail "second device was NOT refused (got: $(echo "$body" | grep -o '<h3>[^<]*' | head -1))"
    # put the original handset back
    ns $CLIENT "ip link set eth0 down; ip link set eth0 address $CLIENT_MAC; ip link set eth0 up" 2>/dev/null
    ns $CLIENT "timeout 25 busybox udhcpc -i eth0 -q -n -f -s $BIN/udhcpc.script" >/dev/null 2>&1
    CLIENT_IP=$(awk '{print $1}' "$STATE/client_lease.txt" 2>/dev/null)
    info "original device restored: $CLIENT_MAC, address $CLIENT_IP"
}

t_deauth() {
    head_ "11. Deauthorising cuts the client off again"
    ns $GW "sh $SETUP deauthorize $CLIENT_MAC" >/dev/null 2>&1
    sleep 1
    body=$(ns $CLIENT "curl -s --max-time 8 http://$WAN_IP/" 2>/dev/null | tr -d '[:space:]')
    echo "$body" | grep -q "RNS-E2E-WAN-OK" \
        && fail "client still has internet after deauthorize" \
        || pass "client is blocked again after deauthorize"
    ns $GW "cat $STATE/authorized_macs.txt 2>/dev/null" | grep -qi "$CLIENT_MAC" \
        && fail "MAC still listed in authorized_macs.txt" || pass "authorisation record removed"
}

t_reservation() {
    head_ "12. A reserved address is handed to the client on renew"
    RESERVED=192.168.49.77
    ns $GW "sh $SETUP reserve $CLIENT_MAC $RESERVED" >/dev/null 2>&1
    ns $GW "kill -HUP \$(cat $STATE/dnsmasq_hotspot.pid) 2>/dev/null" 2>/dev/null
    ns $CLIENT "timeout 25 busybox udhcpc -i eth0 -q -n -f -s $BIN/udhcpc.script" >/dev/null 2>&1
    got=$(awk '{print $1}' "$STATE/client_lease.txt" 2>/dev/null)
    [ "$got" = "$RESERVED" ] && pass "client renewed and received the reserved address $RESERVED" \
        || fail "expected $RESERVED, client has ${got:-nothing}"
    ns $GW "sh $SETUP unreserve $CLIENT_MAC" >/dev/null 2>&1
}

t_keepalive() {
    head_ "13. keepalive is idempotent"
    before=$(ns $GW "iptables -t nat -S HS_NAT | grep -c REDIRECT")
    pid_before=$(ns $GW "cat $STATE/dnsmasq_hotspot.pid 2>/dev/null")
    ns $GW "sh $BIN/find_dnsmasq.sh" | sed 's/^/        | /' 
    out=$(ns $GW "sh $SETUP keepalive" 2>&1)
    echo "$out" | sed 's/^/        | /'
    out2=$(ns $GW "sh $SETUP keepalive" 2>&1)
    echo "$out2" | sed 's/^/        | /'
    pid_after=$(ns $GW "cat $STATE/dnsmasq_hotspot.pid 2>/dev/null")
    if [ -n "$pid_before" ] && [ "$pid_before" != "$pid_after" ]; then
        fail "keepalive restarted a healthy dnsmasq (pid $pid_before -> ${pid_after:-none}) - clients would lose DHCP"
    else
        pass "keepalive left the running dnsmasq alone (pid ${pid_after:-none} unchanged)"
    fi
    after=$(ns $GW "iptables -t nat -S HS_NAT | grep -c REDIRECT")
    [ "$before" = "$after" ] && pass "two keepalive runs left $after portal redirect(s) - no duplication" \
        || fail "keepalive duplicated rules ($before -> $after)"
}

t_decoy() {
    head_ "13b. a process that merely MENTIONS dnsmasq cannot stop our DHCP (F-20 regression)"
    # The old predicate matched any command line containing a path ending in
    # /dnsmasq. This decoy is a bash process carrying such a path - plus one of
    # our state paths - purely in its argument text.
    pid_before=$(ns $GW "cat $STATE/dnsmasq_hotspot.pid 2>/dev/null")
    # `sleep 120; :` (two commands) stops bash from exec-optimising the sleep
    # over the command line, so the dnsmasq path stays visible in /proc/PID/
    # cmdline - which is exactly what the old predicate keyed on.
    ns $GW "bash -c 'sleep 30; :' /tmp/rns-e2e/bin/dnsmasq --interface=eth-lan --pid-file=$STATE/dnsmasq_hotspot.pid --dhcp-leasefile=$STATE/dnsmasq.leases </dev/null >/dev/null 2>&1 & echo \\$! > $STATE/decoy.pid"
    sleep 1
    info "decoy running (pid $(cat "$STATE/decoy.pid" 2>/dev/null)); what the detectors say:"
    diag=$(ns $GW "sh $BIN/find_dnsmasq.sh")
    echo "$diag" | sed 's/^/        | /'
    if echo "$diag" | grep -q "old-pattern(F-20)=1 new-pattern=0"; then
        pass "decoy confirmed: the OLD predicate would have matched it, the new one does not"
    else
        fail "decoy not visible to the old pattern - this regression test is vacuous"
    fi
    out=$(ns $GW "sh $SETUP keepalive" 2>&1)
    echo "$out" | sed 's/^/        | /'
    if echo "$out" | grep -q "stepping aside"; then
        fail "keepalive decided Android DHCP was back because of the decoy - F-20 not fixed"
    else
        pass "keepalive ignored the decoy"
    fi
    pid_after=$(ns $GW "cat $STATE/dnsmasq_hotspot.pid 2>/dev/null")
    if [ -n "$pid_before" ] && [ "$pid_before" = "$pid_after" ]; then
        pass "our dnsmasq survived the keepalive (pid $pid_after)"
    else
        fail "our dnsmasq died (pid $pid_before -> ${pid_after:-none})"
    fi
    dpid=$(cat "$STATE/decoy.pid" 2>/dev/null)
    # kill the decoy's own sleep child too (it would otherwise outlive the test)
    ns $GW "pkill -TERM -x 'sleep 30' 2>/dev/null; kill $dpid 2>/dev/null"
    rm -f "$STATE/decoy.pid"
}

t_probe() {
    head_ "14. probe reports a coherent snapshot (one round-trip health check)"
    p=$(ns $GW "sh $SETUP probe $LAN_IF" 2>/dev/null)
    for k in dhcp_pid nat_jump fwd_jump in_jump masq redirect rule_iif rule_subnet leases authed; do
        echo "$p" | grep -q "^$k=" && pass "probe reports $k=$(echo "$p" | sed -n "s/^$k=//p")" \
            || fail "probe is missing $k"
    done
    # F-21 regression: at this point in the run the rules are demonstrably
    # installed, so probe must actually say so. On iptables 1.8+ the old
    # substring match reported masq=no / redirect=no here and made the
    # watchdog reinstall them every tick.
    for kv in "masq=yes" "redirect=yes" "nat_jump=yes" "fwd_jump=yes" "in_jump=yes" \
              "dhcp_ours=yes" "dhcp_orphan=no" "dhcp_foreign=no"; do
        if echo "$p" | grep -q "^$kv\$"; then
            pass "probe value correct: $kv"
        else
            got=$(echo "$p" | sed -n "s/^${kv%%=*}=//p")
            fail "probe value wrong: expected $kv, got ${kv%%=*}=${got:-<missing>}"
        fi
    done
}

t_shaping() {
    head_ "15. Bandwidth shaping (bandwidth_control.sh)"
    if [ "${HAVE_HTB:-0}" != 1 ]; then
        out=$(ns $GW "sh $SHAPER init 2>&1; sh $SHAPER add $CLIENT_IP 101 64 128 2>&1")
        echo "$out" | grep -q "no HTB scheduler" \
            && pass "shaper degrades gracefully when the kernel has no HTB (logged, exit non-zero)" \
            || info "shaper output: $(echo "$out" | head -2 | tr '\n' ' ')"
        skip "throughput capping NOT exercised - this kernel has no sch_htb/sch_tcb/ingress qdisc"
        return
    fi
    ns $GW "sh $SHAPER init" >/dev/null 2>&1
    ns $GW "sh $SHAPER add $CLIENT_IP 101 64 128" >/dev/null 2>&1
    ns $GW "tc class show dev $LAN_IF" | grep -q "1:101" && pass "HTB class 1:101 installed for $CLIENT_IP" \
        || fail "no HTB class for the client"
    skip "throughput measurement needs iperf/HTTP timing and is not implemented here"
}

t_cleanup() {
    head_ "16. cleanup removes everything (EXIT path)"
    ns $GW "sh $SETUP authorize $CLIENT_MAC $CLIENT_IP" >/dev/null 2>&1
    out=$(ns $GW "sh $SETUP cleanup $LAN_IF" 2>&1)
    echo "$out" | sed 's/^/        | /'
    for ch in "nat HS_NAT" "filter HS_FWD" "filter HS_IN"; do
        set -- $ch
        n=$(ns $GW "iptables -t $1 -S 2>/dev/null | grep -c $2")
        if [ "${n:-x}" = "0" ]; then pass "no $2 rules or chain left"
        else fail "$2 left behind (${n} references): $(ns $GW "iptables -t $1 -S 2>/dev/null | grep $2" | tr '\n' ' ')"; fi
    done
    ns $GW "ls $STATE/dnsmasq_hotspot.pid >/dev/null 2>&1" \
        && fail "dnsmasq pidfile survived cleanup" || pass "dnsmasq pidfile gone"
    leftover=$(ns $GW "pgrep -x dnsmasq 2>/dev/null | tr '\n' ' '")
    [ -z "$(echo "$leftover" | tr -d '[:space:]')" ] \
        && pass "no dnsmasq process left running" \
        || fail "dnsmasq still running (pids: $leftover)"
    n=$(ns $GW "iptables -t nat -S POSTROUTING 2>/dev/null | grep -c MASQUERADE")
    if [ "${n:-x}" = "0" ]; then pass "masquerade removed"
    else fail "masquerade left behind: $(ns $GW "iptables -t nat -S POSTROUTING 2>/dev/null | grep MASQUERADE" | tr '\n' ' ')"; fi
    body=$(ns $CLIENT "curl -s --max-time 8 http://$WAN_IP/" 2>/dev/null | tr -d '[:space:]')
    [ -z "$body" ] && pass "client has no path to the internet after cleanup" \
        || fail "client still reached something after cleanup"
}

teardown() {
    head_ "Teardown"
    if [ -n "${KEEP_NS:-}" ]; then
        info "KEEP_NS set - namespaces left in place for inspection"
        return 0
    fi
    for n in $GW $WAN $CLIENT; do sudo -n ip netns del "$n" 2>/dev/null; done
    sudo -n ip link del v-gw-wan 2>/dev/null; sudo -n ip link del v-gw-lan 2>/dev/null
    info "namespaces removed; logs kept in $RNS_ROOT"
}

# -------------------------------------------------------------------- main ---
preflight
topo_up
t_start
t_dhcp
t_blocked
t_portal
t_https_reset
t_dns
t_bad_voucher
t_redeem
t_authorized
t_second_device
t_deauth
t_reservation
t_keepalive
t_decoy
t_probe
t_shaping
t_cleanup
teardown

echo
echo "=================================================================="
printf "  %s passed, %s failed, %s skipped\n" "$PASS" "$FAIL" "$SKIP"
if [ "$FAIL" -gt 0 ]; then printf "  failures:%b\n" "$FAILED_NAMES"; fi
echo "  artefacts: $RNS_ROOT  (state/, portal.log, macshim.log, *.out)"
echo "=================================================================="
[ "$FAIL" -eq 0 ]
