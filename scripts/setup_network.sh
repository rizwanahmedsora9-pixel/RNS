#!/system/bin/sh
# =============================================================================
# setup_network.sh - rooted Android hotspot gateway: NAT + captive portal + DHCP
#
#   Router1 (ISP) -> [ this phone: NAT + portal + vouchers ] -> Router2 -> users
#
# USAGE (as root):
#   sh /data/local/tmp/setup_network.sh start
#   sh /data/local/tmp/setup_network.sh stop
#   sh /data/local/tmp/setup_network.sh status
#   sh /data/local/tmp/setup_network.sh keepalive
#   sh /data/local/tmp/setup_network.sh authorize   <mac> <static_ip> [current_ip]
#   sh /data/local/tmp/setup_network.sh deauthorize <mac>
#   sh /data/local/tmp/setup_network.sh reserve     <mac> <static_ip>
#   sh /data/local/tmp/setup_network.sh unreserve   <mac>
#   sh /data/local/tmp/setup_network.sh route       <lan_if> <subnet>   # policy routing only
#   sh /data/local/tmp/setup_network.sh nat         <lan_if> <wan> <subnet>  # forwarding only, no DHCP restart
#   sh /data/local/tmp/setup_network.sh probe       [lan_if]            # key=value health snapshot (1 round-trip)
#   sh /data/local/tmp/setup_network.sh cleanup     [lan_if]            # EXIT: remove everything, including orphans
#   sh /data/local/tmp/setup_network.sh foreign-dhcp                    # yes/no
#   sh /data/local/tmp/setup_network.sh free-dns                        # drop every dnsmasq (port 53)
#   sh /data/local/tmp/setup_network.sh procs                           # dhcp/ap processes
#   sh /data/local/tmp/setup_network.sh diag                            # everything, for the debugger
#
# OWNERSHIP
#   Every dnsmasq this script starts carries --pid-file/--dhcp-leasefile/
#   --dhcp-hostsfile under $STATE_DIR. That is the ownership marker: start() and
#   cleanup() kill any dnsmasq that matches it, so an instance from a previous
#   session can never keep UDP/67 and make the next one die with "Address
#   already in use" (seen on 2026-09-24, device log). Android's own tether
#   dnsmasq has none of those paths and is never touched.
#
#   KEEP_ANDROID_DHCP=1 sh ... start
#       Do not kill Android's tether dnsmasq. On the Hot 8 the framework tears
#       ap0 down if its own dnsmasq cannot bind, or if it is killed afterwards.
#
# CONFIG
#   Every setting below can be overridden without editing this file by writing
#   them to /data/local/tmp/hotspot.env, e.g.:
#       WAN_IF=ccmni0
#       LAN_IF=ap0
#       UPSTREAM_DNS=8.8.8.8
#   Pinning LAN_IP there forces that address. Leaving it unset (the normal case)
#   adopts whatever address Android already put on the hotspot interface.
#
# WHY THE ADDRESS IS ADOPTED
#   Forcing 10.66.0.1 on top of Android's 192.168.43.1 made netd and this script
#   fight over the interface. Clients never finished DHCP ("Obtaining IP address"
#   forever) and therefore never sent the probe that pops the sign-in sheet.
# =============================================================================

# HOTSPOT_STATE_DIR exists so the scripts can be exercised off-device; on the
# phone it stays /data/local/tmp.
STATE_DIR="${HOTSPOT_STATE_DIR:-/data/local/tmp}"
CONF="${HOTSPOT_CONF:-$STATE_DIR/hotspot.env}"
# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"

# A LAN_IP set in hotspot.env is an explicit pin. A value learned from a
# previous run (hotspot.runtime) must NOT count as a pin, or the next start
# would force the old address back and restart the fight this script avoids.
LAN_IP_PINNED=0
[ -n "${LAN_IP+x}" ] && LAN_IP_PINNED=1

# --- interfaces --------------------------------------------------------------
# WAN_IF=auto picks the interface holding the default route. On the Infinix Hot 8
# (MediaTek) the uplink is a ccmni* PPP interface, NOT wlan0 - `ip link` on the
# device does not even list wlan0 while WiFi is off, so auto-detect is the safe
# default. Pin it in hotspot.env once you have confirmed your topology.
WAN_IF="${WAN_IF:-auto}"
LAN_IF="${LAN_IF:-ap0}"

# --- addressing --------------------------------------------------------------
# Defaults only. start() replaces these with the address already on the hotspot
# interface unless LAN_IP was pinned in hotspot.env.
LAN_IP="${LAN_IP:-10.66.0.1}"
LAN_PREFIX="${LAN_PREFIX:-24}"
LAN_SUBNET="${LAN_SUBNET:-10.66.0.0/24}"
DHCP_START="${DHCP_START:-10.66.0.10}"
DHCP_END="${DHCP_END:-10.66.0.250}"
# 10 minutes: long enough that a renew storm cannot look like "obtaining IP"
# again, short enough that a reserved address takes effect the same visit.
DHCP_LEASE="${DHCP_LEASE:-10m}"
UPSTREAM_DNS="${UPSTREAM_DNS:-8.8.8.8}"
UPSTREAM_DNS2="${UPSTREAM_DNS2:-1.1.1.1}"
DHCP_OWNER="${DHCP_OWNER:-ours}"

# --- captive portal ----------------------------------------------------------
PORTAL_PORT="${PORTAL_PORT:-8080}"
# Hijack DNS listens here. Unauthenticated queries are redirected to it so the
# OS probe resolves even when the client ignores the DHCP DNS server. It must
# NOT answer with the gateway's private address: several Android builds treat
# a private answer for connectivitycheck as "no internet" and never show the
# sign-in sheet. This listener forwards to the real resolvers; port 80 is what
# gets intercepted.
HIJACK_PORT="${HIJACK_PORT:-53}"

# --- policy routing ----------------------------------------------------------
# Priorities for the `ip rule` entries that let forwarded traffic reach the main
# routing table. Android routes per-network and ends with an "unreachable" rule,
# so a packet that is not from a local UID and carries no fwmark is dropped. The
# system hotspot survives because netd adds tethering rules for it; an interface
# this app brought up itself (WiFi Direct group, local-only hotspot, root
# hostapd) gets none - clients then have an IP and a sign-in page but no internet.
RULE_PREF_IIF="${RULE_PREF_IIF:-15500}"
RULE_PREF_SUBNET="${RULE_PREF_SUBNET:-15501}"

# --- runtime files -----------------------------------------------------------
PIDFILE="$STATE_DIR/dnsmasq_hotspot.pid"
LEASEFILE="$STATE_DIR/dnsmasq.leases"
LOGFILE="$STATE_DIR/dnsmasq_hotspot.log"
HOSTS_DIR="$STATE_DIR/dhcp_hosts.d"
HOSTS_FILE="$STATE_DIR/dhcp_hosts"
AUTHORIZED_FILE="$STATE_DIR/authorized_macs.txt"
RUNTIME_FILE="$STATE_DIR/hotspot.runtime"
# The interface the last start()/cleanup() acted on. cleanup() needs it because
# the rules can outlive the interface name they were written for.
LAST_IF_FILE="$STATE_DIR/hotspot.last_lan_if"

log() { echo "[setup_network] $*"; }
die() { echo "[setup_network] ERROR: $*" >&2; exit 1; }

# --- helpers -----------------------------------------------------------------

# The interface the app pinned in hotspot.env, if it did. The app resolves the
# uplink ONCE (WanDetector) and writes it here; the script must then use exactly
# that interface for NAT. Resolving it again here is how NAT ended up installed
# for ccmni1 while the uplink was actually ccmni0 (2026-09-24 debug log,
# 12:37:25 script `WAN=ccmni1` vs 12:37:52 app `WAN=ccmni0`): the client got an
# IP, redeemed a voucher and still had no internet, because MASQUERADE was on an
# interface no packet ever left through.
WAN_PINNED=0
if [ "$WAN_IF" != "auto" ]; then WAN_PINNED=1; fi

resolve_wan() {
    if [ "$WAN_PINNED" = 1 ]; then
        echo "$WAN_IF"
        return 0
    fi
    # hotspot.runtime records the interface the last start() actually used. Reuse
    # it so keepalive/stop/probe act on the same uplink as start().
    if [ -z "${WAN_IF_ACTIVE:-}" ] && [ -f "$RUNTIME_FILE" ]; then
        WAN_IF_ACTIVE=$(sed -n 's/^WAN_IF_ACTIVE=//p' "$RUNTIME_FILE" 2>/dev/null | head -n 1)
    fi
    if [ -n "${WAN_IF_ACTIVE:-}" ] && ip link show "$WAN_IF_ACTIVE" >/dev/null 2>&1; then
        echo "$WAN_IF_ACTIVE"
        return 0
    fi
    W=$(ip route show default 2>/dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p' | head -n 1)
    if [ -z "$W" ]; then
        W=$(ip -o link show up 2>/dev/null \
            | sed -n 's/^[0-9]*: \([^:@]*\).*/\1/p' \
            | grep -v -x -e lo -e "$LAN_IF" \
            | head -n 1)
    fi
    echo "$W"
}

# Insert a rule at the top of a chain unless an identical rule already exists.
ensure_top() {
    TBL="$1"; CHAIN="$2"; shift 2
    iptables -t "$TBL" -C "$CHAIN" "$@" 2>/dev/null \
        || iptables -t "$TBL" -I "$CHAIN" 1 "$@"
}

# Delete *every* copy of a rule (a single -D only removes one).
delete_all() {
    TBL="$1"; CHAIN="$2"; shift 2
    while iptables -t "$TBL" -C "$CHAIN" "$@" 2>/dev/null; do
        iptables -t "$TBL" -D "$CHAIN" "$@" 2>/dev/null || break
    done
}

ip6_delete_all() {
    CHAIN="$1"; shift
    while ip6tables -C "$CHAIN" "$@" 2>/dev/null; do
        ip6tables -D "$CHAIN" "$@" 2>/dev/null || break
    done
}

dnsmasq_pid() {
    [ -f "$PIDFILE" ] || return 1
    P=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$P" ] && kill -0 "$P" 2>/dev/null && echo "$P" && return 0
    return 1
}

# cmdline match for a dnsmasq binary, not for a script that merely mentions it.
is_dnsmasq_cmd() {
    case "$1" in
        */dnsmasq|*/dnsmasq\ *|*dnsmasq\ --*) return 0 ;;
    esac
    return 1
}

foreign_dnsmasq_running() {
    OUR_PID=$(dnsmasq_pid 2>/dev/null) || OUR_PID=""
    for proc in /proc/[0-9]*; do
        pid=${proc#/proc/}
        [ -n "$OUR_PID" ] && [ "$pid" = "$OUR_PID" ] && continue
        cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
        if is_dnsmasq_cmd "$cmdline"; then
            return 0
        fi
    done
    return 1
}

FOREIGN_CMD="$STATE_DIR/foreign_dnsmasq.cmdline"

# Keep the exact argv of Android's dnsmasq. If our binary cannot bind (port 53
# already taken, or an option this 2.51 build rejects), we re-exec that argv
# instead of leaving the client with no DHCP server at all.
save_foreign_cmdline() {
    OUR_PID=$(dnsmasq_pid 2>/dev/null) || OUR_PID=""
    for proc in /proc/[0-9]*; do
        pid=${proc#/proc/}
        [ -n "$OUR_PID" ] && [ "$pid" = "$OUR_PID" ] && continue
        cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
        if is_dnsmasq_cmd "$cmdline"; then
            cp "$proc/cmdline" "$FOREIGN_CMD" 2>/dev/null && return 0
        fi
    done
    return 1
}

respawn_saved_dnsmasq() {
    [ -s "$FOREIGN_CMD" ] || return 1
    # Ignore SIGHUP so the re-exec survives this script exiting.
    (
        trap '' HUP
        xargs -0 sh -c 'exec "$0" "$@"' < "$FOREIGN_CMD"
    ) >/dev/null 2>&1 &
    sleep 1
    foreign_dnsmasq_running
}

# Android's tethering stack also spawns a dnsmasq on the LAN interface. Two
# DHCP servers NAK each other and the client UI stays on "Obtaining IP address".
kill_foreign_dnsmasq() {
    # Off-device self-test must not signal a dnsmasq that happens to be running
    # on the build machine. On the phone STATE_DIR is /data/local/tmp.
    if [ "$STATE_DIR" != "/data/local/tmp" ]; then
        return 0
    fi
    save_foreign_cmdline || true
    OUR_PID=$(dnsmasq_pid 2>/dev/null) || OUR_PID=""
    killed=0
    for proc in /proc/[0-9]*; do
        pid=${proc#/proc/}
        [ -n "$OUR_PID" ] && [ "$pid" = "$OUR_PID" ] && continue
        cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
        if is_dnsmasq_cmd "$cmdline"; then
            if kill "$pid" 2>/dev/null; then
                log "stopped foreign dnsmasq (pid $pid)"
                killed=1
            fi
        fi
    done
    if [ "$killed" = 1 ]; then
        sleep 1
    fi
}

# Is this dnsmasq *ours*? Ours is identified by the state paths it was started
# with, not by the pidfile: an instance from a previous session whose pidfile was
# already removed is still ours and still holds UDP/67.
is_our_dnsmasq_cmd() {
    case "$1" in
        *"--pid-file=$PIDFILE"*|*"--dhcp-leasefile=$LEASEFILE"*|*"--dhcp-hostsfile=$HOSTS_FILE"*) return 0 ;;
    esac
    return 1
}

# Kill EVERY dnsmasq of ours, tracked or not, and drop the pidfile.
#
# The 2026-09-24 debug log is the reason this exists: a dnsmasq from an earlier
# session (pid 9557) survived a stop, so the next start's dnsmasq died with
# "failed to bind DHCP server socket: Address already in use", the script then
# "adopted" the stale instance, and Android's own tether dnsmasq could never
# bind either. Clients got an IP from a server whose config nobody could verify.
kill_our_dnsmasq() {
    OUR_PID=$(dnsmasq_pid 2>/dev/null) || OUR_PID=""
    killed=0
    for proc in /proc/[0-9]*; do
        pid=${proc#/proc/}
        [ -n "$OUR_PID" ] && [ "$pid" = "$OUR_PID" ] && continue
        cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
        if is_our_dnsmasq_cmd "$cmdline"; then
            if kill "$pid" 2>/dev/null; then
                log "stopped a dnsmasq left over from an earlier session (pid $pid)"
                killed=1
            fi
        fi
    done
    if [ -n "$OUR_PID" ]; then
        kill "$OUR_PID" 2>/dev/null && log "stopped our dnsmasq (pid $OUR_PID)"
        killed=1
    fi
    if [ "$killed" = 1 ]; then
        # Give the port back before anyone tries to bind it.
        sleep 1
        for proc in /proc/[0-9]*; do
            pid=${proc#/proc/}
            cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
            if is_our_dnsmasq_cmd "$cmdline"; then
                kill -9 "$pid" 2>/dev/null && log "force-killed dnsmasq pid $pid"
            fi
        done
    fi
    rm -f "$PIDFILE"
}

# Does any dnsmasq of ours exist that the pidfile does not know about? The app
# reports this as a finding: a second DHCP server on the LAN that we cannot
# control is exactly what "client connects but Obtaining IP fails" looks like.
our_dnsmasq_orphan_running() {
    OUR_PID=$(dnsmasq_pid 2>/dev/null) || OUR_PID=""
    for proc in /proc/[0-9]*; do
        pid=${proc#/proc/}
        [ -n "$OUR_PID" ] && [ "$pid" = "$OUR_PID" ] && continue
        cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
        if is_our_dnsmasq_cmd "$cmdline"; then
            return 0
        fi
    done
    return 1
}

# rp_filter drops DHCPDISCOVER (source 0.0.0.0) on some Android kernels, which
# looks exactly like a client stuck obtaining an IP. Only touch sysctls when
# the LAN interface actually exists in /proc, so an off-device self-test cannot
# rewrite the machine-wide rp_filter.
relax_iface() {
    IF_DIR="/proc/sys/net/ipv4/conf/$LAN_IF"
    if [ -d "$IF_DIR" ]; then
        echo 0 > "$IF_DIR/rp_filter" 2>/dev/null || true
        echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true
        echo 1 > "$IF_DIR/bc_forwarding" 2>/dev/null || true
    fi
    # Only when this interface exists in /proc. An off-device self-test has no
    # ap0 and must not flip the machine-wide forwarding sysctl.
    if [ -d "$IF_DIR" ]; then
        echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true
    fi
    # No IPv6 router advertisements. A client that gets an IPv6 address probes
    # over IPv6, that probe times out (we cannot serve it), and Android treats
    # a timeout as "not a captive portal" - so the sign-in sheet never appears.
    V6_DIR="/proc/sys/net/ipv6/conf/$LAN_IF"
    if [ -d "$V6_DIR" ]; then
        echo 1 > "$V6_DIR/disable_ipv6" 2>/dev/null || true
    fi
}

# Put LAN traffic into the main routing table (see RULE_PREF_* above). Idempotent:
# `ip rule add` of an identical rule fails with EEXIST, so check first.
install_policy_routing() {
    RULES=$(ip rule show 2>/dev/null)
    case "$RULES" in
        *"iif $LAN_IF lookup main"*)
            ;;
        *)
            if ip rule add pref "$RULE_PREF_IIF" iif "$LAN_IF" lookup main 2>/dev/null; then
                log "routing: $LAN_IF -> main table (pref $RULE_PREF_IIF)"
            else
                log "routing: FAILED to add the iif rule for $LAN_IF - forwarded packets may be dropped by Android's unreachable rule"
            fi
            ;;
    esac
    case "$RULES" in
        *"to $LAN_SUBNET lookup main"*)
            ;;
        *)
            if ip rule add pref "$RULE_PREF_SUBNET" to "$LAN_SUBNET" lookup main 2>/dev/null; then
                log "routing: return traffic to $LAN_SUBNET -> main table (pref $RULE_PREF_SUBNET)"
            else
                log "routing: FAILED to add the subnet rule for $LAN_SUBNET"
            fi
            ;;
    esac
}

remove_policy_routing() {
    # Bounded: a `del` that silently does nothing must not spin here, because
    # this script runs on the app's single root shell and would block it.
    N=0
    while [ "$N" -lt 5 ] && ip rule show 2>/dev/null | grep -q "iif $LAN_IF lookup main"; do
        ip rule del iif "$LAN_IF" lookup main 2>/dev/null || break
        N=$((N + 1))
    done
    N=0
    while [ "$N" -lt 5 ] && ip rule show 2>/dev/null | grep -q "to $LAN_SUBNET lookup main"; do
        ip rule del to "$LAN_SUBNET" lookup main 2>/dev/null || break
        N=$((N + 1))
    done
    # Belt and braces: drop anything left at our priorities, whichever subnet or
    # interface name it was written for (the interface can change between runs).
    ip rule del pref "$RULE_PREF_IIF" 2>/dev/null
    ip rule del pref "$RULE_PREF_SUBNET" 2>/dev/null
}

is_private_slash24() {
    case "$1" in
        10.*.*.*/24|192.168.*.*/24|172.1[6-9].*.*/24|172.2[0-9].*.*/24|172.3[0-1].*.*/24)
            return 0
            ;;
    esac
    return 1
}

derive_subnet() {
    # $1 = a.b.c.d  (gateway). Android hotspot addresses are /24 with .1 as the
    # gateway; the static voucher pool is .10-.49 and dynamic DHCP is .10-.250
    # (dnsmasq will not hand a dhcp-host reservation to anyone else).
    BASE=${1%.*}
    LAN_SUBNET="${BASE}.0/24"
    DHCP_START="${BASE}.10"
    DHCP_END="${BASE}.250"
}

write_runtime() {
    # LAN_IF_USED, not LAN_IF: keepalive() sources this file, and a stale LAN_IF
    # here would override the interface hotspot.env was just updated with.
    # WAN_IF_ACTIVE pins keepalive/stop/probe to the uplink start() really used.
    # A keepalive must not move STARTED_AT (the app shows session age).
    if [ -z "${STARTED_AT:-}" ] && [ -f "$RUNTIME_FILE" ]; then
        STARTED_AT=$(sed -n 's/^STARTED_AT=//p' "$RUNTIME_FILE" 2>/dev/null | head -n 1)
    fi
    cat > "$RUNTIME_FILE" <<EOF
LAN_IF_USED=$LAN_IF
WAN_IF_ACTIVE=${WAN_ACTIVE:-$WAN_IF_ACTIVE}
LAN_IP=$LAN_IP
LAN_PREFIX=$LAN_PREFIX
LAN_SUBNET=$LAN_SUBNET
DHCP_START=$DHCP_START
DHCP_END=$DHCP_END
PORTAL_PORT=$PORTAL_PORT
DHCP_OWNER=$DHCP_OWNER
STARTED_AT=${STARTED_AT:-$(date +%s 2>/dev/null || echo 0)}
EOF
}

# Take the address Android already configured. Flushing it and writing
# 10.66.0.1 is what made clients loop on "Obtaining IP address".
configure_lan_address() {
    CURRENT=$(ip -o -4 addr show dev "$LAN_IF" 2>/dev/null | awk '{print $4}' | head -n 1)
    if [ "$LAN_IP_PINNED" = 1 ]; then
        WANT="${LAN_IP}/${LAN_PREFIX}"
        if [ "$CURRENT" != "$WANT" ]; then
            log "pinned LAN address $WANT (interface had ${CURRENT:-none})"
            ip addr flush dev "$LAN_IF" 2>/dev/null
            ip addr add "$WANT" dev "$LAN_IF" || die "failed to set $WANT on $LAN_IF"
        fi
    elif is_private_slash24 "$CURRENT"; then
        LAN_IP=${CURRENT%/*}
        LAN_PREFIX=24
        log "adopting existing hotspot address $CURRENT (not replacing it)"
    else
        LAN_IP=10.66.0.1
        LAN_PREFIX=24
        log "no usable hotspot address (${CURRENT:-none}); assigning ${LAN_IP}/24"
        ip addr flush dev "$LAN_IF" 2>/dev/null
        ip addr add "${LAN_IP}/24" dev "$LAN_IF" || die "failed to assign ${LAN_IP}/24 on $LAN_IF"
    fi
    ip link set "$LAN_IF" up
    derive_subnet "$LAN_IP"
    log "LAN $LAN_IP/$LAN_PREFIX subnet $LAN_SUBNET dhcp $DHCP_START-$DHCP_END"
}

# Previous builds put the gate directly on the built-in chains. A leftover
# DNAT to :8080 sits behind our jump and recaptures a client whose RETURN only
# skips our chain, so the sign-in page comes back after a successful voucher.
remove_legacy_rules() {
    WAN_NOW="$1"
    delete_all nat PREROUTING -i "$LAN_IF" -p tcp --dport 80 -j DNAT \
        --to-destination "10.66.0.1:${PORTAL_PORT}"
    delete_all nat PREROUTING -i "$LAN_IF" -p tcp --dport 80 -j DNAT \
        --to-destination "${LAN_IP}:${PORTAL_PORT}"
    delete_all nat PREROUTING -i "$LAN_IF" -p tcp --dport 80 -j REDIRECT --to-ports "$PORTAL_PORT"
    delete_all filter FORWARD -i "$LAN_IF" -j DROP
    delete_all filter FORWARD -i "$LAN_IF" -p tcp --dport 443 -j REJECT --reject-with tcp-reset
    delete_all filter FORWARD -o "$LAN_IF" -d "10.66.0.0/24" -m state --state RELATED,ESTABLISHED -j ACCEPT
    delete_all filter FORWARD -o "$LAN_IF" -d "$LAN_SUBNET" -m state --state RELATED,ESTABLISHED -j ACCEPT
    if [ -n "$WAN_NOW" ]; then
        delete_all nat POSTROUTING -o "$WAN_NOW" -s "10.66.0.0/24" -j MASQUERADE
        delete_all nat POSTROUTING -o "$WAN_NOW" -s "$LAN_SUBNET" -j MASQUERADE
    fi
    if [ -f "$AUTHORIZED_FILE" ]; then
        while read -r mac ip extra; do
            [ -n "$mac" ] || continue
            delete_all nat PREROUTING -i "$LAN_IF" -m mac --mac-source "$mac" -j RETURN
            delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$mac" -j ACCEPT
            [ -n "$ip" ] && delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$mac" -s "$ip" -j ACCEPT
            [ -n "$extra" ] && [ "$extra" != "$ip" ] && \
                delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$mac" -s "$extra" -j ACCEPT
        done < "$AUTHORIZED_FILE"
    fi
}

install_chains() {
    WAN_NOW="$1"
    iptables -t nat -N HS_NAT 2>/dev/null || true
    iptables -t filter -N HS_FWD 2>/dev/null || true
    iptables -t filter -N HS_IN 2>/dev/null || true
    iptables -t nat -F HS_NAT
    iptables -t filter -F HS_FWD
    iptables -t filter -F HS_IN

    # Jump from the top of the built-in chains so Android's own tether rules
    # cannot accept the packet before we have decided.
    delete_all nat PREROUTING -j HS_NAT
    iptables -t nat -I PREROUTING 1 -j HS_NAT || log "FAILED to install nat jump"
    delete_all filter FORWARD -j HS_FWD
    iptables -t filter -I FORWARD 1 -j HS_FWD || log "FAILED to install forward jump"
    delete_all filter INPUT -j HS_IN
    iptables -t filter -I INPUT 1 -j HS_IN || log "FAILED to install input jump"

    # Port 80 is redirected onto the local portal. REDIRECT (not DNAT to a
    # hardcoded 10.66.0.1:8080) follows the interface address, so it still
    # works after we adopt 192.168.43.1. The portal answers the probe itself
    # with HTTP 200; a redirect to :8080 does not pop the sign-in sheet.
    iptables -t nat -A HS_NAT -i "$LAN_IF" -p tcp --dport 80 -j REDIRECT --to-ports "$PORTAL_PORT" \
        || log "FAILED to redirect port 80 to the portal"
    # Clients that ignore DHCP DNS (hardcoded 8.8.8.8) still need a resolver
    # or the probe never starts and no sheet appears. Forward, don't forge.
    iptables -t nat -A HS_NAT -i "$LAN_IF" -p udp --dport 53 -j REDIRECT --to-ports "$HIJACK_PORT"
    iptables -t nat -A HS_NAT -i "$LAN_IF" -p tcp --dport 53 -j REDIRECT --to-ports "$HIJACK_PORT"

    # FORWARD, bottom of the chain (client ACCEPTs are inserted above these):
    #   established return traffic, then reset HTTPS so the client falls back
    #   to its plain-HTTP probe, then deny everything else from the LAN.
    iptables -t filter -A HS_FWD -o "$LAN_IF" -d "$LAN_SUBNET" -m state --state RELATED,ESTABLISHED -j ACCEPT
    iptables -t filter -A HS_FWD -i "$LAN_IF" -p tcp --dport 443 -j REJECT --reject-with tcp-reset
    iptables -t filter -A HS_FWD -i "$LAN_IF" -j DROP

    # DNATed packets are delivered locally. Android's INPUT policy often drops
    # a new connection to a high port from the hotspot interface, which makes
    # the probe time out - and a timeout is NOT a captive portal, so no sheet.
    iptables -t filter -A HS_IN -i "$LAN_IF" -p tcp --dport "$PORTAL_PORT" -j ACCEPT
    iptables -t filter -A HS_IN -i "$LAN_IF" -p udp --dport 53 -j ACCEPT
    iptables -t filter -A HS_IN -i "$LAN_IF" -p tcp --dport 53 -j ACCEPT
    iptables -t filter -A HS_IN -i "$LAN_IF" -p udp --dport 67 -j ACCEPT
    iptables -t filter -A HS_IN -i "$LAN_IF" -p icmp -j ACCEPT

    ensure_top nat POSTROUTING -o "$WAN_NOW" -s "$LAN_SUBNET" -j MASQUERADE

    ip6tables -C FORWARD -i "$LAN_IF" -j DROP 2>/dev/null \
        || ip6tables -I FORWARD 1 -i "$LAN_IF" -j DROP 2>/dev/null
    ip6tables -C INPUT -i "$LAN_IF" -j DROP 2>/dev/null \
        || ip6tables -I INPUT 1 -i "$LAN_IF" -j DROP 2>/dev/null
    ip6tables -C OUTPUT -o "$LAN_IF" -p icmpv6 --icmpv6-type router-advertisement -j DROP 2>/dev/null \
        || ip6tables -I OUTPUT 1 -o "$LAN_IF" -p icmpv6 --icmpv6-type router-advertisement -j DROP 2>/dev/null
}

block_foreign_dhcp() {
    # If Android restarts its dnsmasq, drop its offers. Ours runs as root.
    iptables -C OUTPUT -o "$LAN_IF" -p udp --sport 67 -m owner '!' --uid-owner 0 -j DROP 2>/dev/null \
        || iptables -I OUTPUT 1 -o "$LAN_IF" -p udp --sport 67 -m owner '!' --uid-owner 0 -j DROP 2>/dev/null \
        || log "could not block Android DHCP replies (owner match unavailable)"
}

unblock_foreign_dhcp() {
    delete_all filter OUTPUT -o "$LAN_IF" -p udp --sport 67 -m owner '!' --uid-owner 0 -j DROP
}

# Ask netd to spawn its own dnsmasq again. Used only when ours failed to start,
# so the client is not left with no DHCP server at all.
restore_android_dhcp() {
    if respawn_saved_dnsmasq; then
        log "restarted the DHCP server Android was already running"
        return 0
    fi
    log "asking Android to serve DHCP again ($DHCP_START-$DHCP_END)"
    ndc tether interface add "$LAN_IF" >/dev/null 2>&1 || true
    ndc tether start "$DHCP_START" "$DHCP_END" >/dev/null 2>&1 || true
    if ! foreign_dnsmasq_running; then
        ndc tether start 192.168.43.2 192.168.43.254 >/dev/null 2>&1 || true
    fi
}

migrate_hosts() {
    : >> "$HOSTS_FILE"
    [ -d "$HOSTS_DIR" ] || return 0
    for f in "$HOSTS_DIR"/*; do
        [ -f "$f" ] || continue
        line=$(cat "$f" 2>/dev/null)
        line=${line#dhcp-host=}
        case "$line" in
            *,*) echo "$line" >> "$HOSTS_FILE" ;;
        esac
        rm -f "$f"
    done
}

# dnsmasq on the Hot 8 is the AOSP 2.51 build. --dhcp-hostsdir (2.73+) makes
# that binary exit immediately, which is how a previous version killed Android's
# DHCP server and then failed to replace it: clients stayed on "Obtaining IP".
run_dnsmasq() {
    MODE="$1"
    IF="$2"
    rm -f "$PIDFILE"
    OPT114=""
    case "$MODE" in
        full) OPT114="--dhcp-option=114,http://${LAN_IP}/" ;;
    esac
    # shellcheck disable=SC2086
    dnsmasq \
        --interface="$IF" \
        --except-interface=lo \
        --bind-interfaces \
        --listen-address="$LAN_IP" \
        --dhcp-range="${DHCP_START},${DHCP_END},${DHCP_LEASE}" \
        --dhcp-authoritative \
        --dhcp-lease-max=250 \
        --dhcp-option="3,${LAN_IP}" \
        --dhcp-option="6,${LAN_IP}" \
        $OPT114 \
        --dhcp-hostsfile="$HOSTS_FILE" \
        --dhcp-leasefile="$LEASEFILE" \
        --dhcp-broadcast \
        --no-resolv \
        --server="$UPSTREAM_DNS" \
        --server="$UPSTREAM_DNS2" \
        --user=root \
        --pid-file="$PIDFILE" \
        --log-facility="$LOGFILE" \
        --conf-file= \
        >>"$LOGFILE" 2>&1 || return 1
    if dnsmasq_pid >/dev/null; then
        return 0
    fi
    sleep 1
    dnsmasq_pid >/dev/null
}

run_dnsmasq_min() {
    IF="$1"
    rm -f "$PIDFILE"
    dnsmasq \
        --interface="$IF" \
        --bind-interfaces \
        --listen-address="$LAN_IP" \
        --dhcp-range="${DHCP_START},${DHCP_END},${DHCP_LEASE}" \
        --dhcp-option="3,${LAN_IP}" \
        --dhcp-option="6,${LAN_IP}" \
        --dhcp-leasefile="$LEASEFILE" \
        --dhcp-broadcast \
        --no-resolv \
        --server="$UPSTREAM_DNS" \
        --user=root \
        --pid-file="$PIDFILE" \
        --conf-file= \
        >>"$LOGFILE" 2>&1 || return 1
    dnsmasq_pid >/dev/null || { sleep 1; dnsmasq_pid >/dev/null; }
}

# A pid left over from a previous gateway address keeps answering on the wrong
# subnet. Clients then never finish DHCP on the address we just adopted.
# Identity is our own pidfile plus the address we are serving now: that also
# recognises the DNS-only instance, which has no --dhcp-range at all.
dnsmasq_matches() {
    P="$1"
    cmd=$(tr '\0' ' ' < "/proc/$P/cmdline" 2>/dev/null) || return 1
    case "$cmd" in
        *"--pid-file=$PIDFILE"*) ;;
        *) return 1 ;;
    esac
    case "$cmd" in
        *"--listen-address=$LAN_IP "*|*"--listen-address=$LAN_IP") return 0 ;;
    esac
    return 1
}

# DNS only: no --dhcp-range, so dnsmasq never touches port 67. Used when the
# system's own DHCP server holds that port (Android 10+ serves WiFi Direct and
# local-only hotspots from system_server).
run_dnsmasq_dns_only() {
    IF="$1"
    rm -f "$PIDFILE"
    dnsmasq \
        --interface="$IF" \
        --except-interface=lo \
        --bind-interfaces \
        --listen-address="$LAN_IP" \
        --no-resolv \
        --server="$UPSTREAM_DNS" \
        --server="$UPSTREAM_DNS2" \
        --user=root \
        --pid-file="$PIDFILE" \
        --log-facility="$LOGFILE" \
        --conf-file= \
        >>"$LOGFILE" 2>&1 || return 1
    dnsmasq_pid >/dev/null || { sleep 1; dnsmasq_pid >/dev/null; }
}

start_dnsmasq_dns_only() {
    IF="$1"
    if P=$(dnsmasq_pid); then
        log "a dnsmasq of ours is already running (pid $P) - leaving it alone"
        return 0
    fi
    mkdir -p "$STATE_DIR"
    kill_foreign_dnsmasq
    log "starting dnsmasq DNS-only on $IF ($LAN_IP:53, no DHCP)"
    if run_dnsmasq_dns_only "$IF"; then
        log "dnsmasq started (DNS only)"
        return 0
    fi
    log "ERROR: dnsmasq refused DNS-only mode too ($(tail -n 1 "$LOGFILE" 2>/dev/null))"
    return 1
}

start_dnsmasq() {
    IF="$1"
    if P=$(dnsmasq_pid); then
        if dnsmasq_matches "$P"; then
            log "dnsmasq already running (pid $P)"
            return 0
        fi
        log "dnsmasq pid $P is not serving $LAN_IP, restarting"
        kill "$P" 2>/dev/null
        sleep 1
        rm -f "$PIDFILE"
    fi
    mkdir -p "$STATE_DIR"
    migrate_hosts
    kill_foreign_dnsmasq
    log "starting dnsmasq on $IF ($DHCP_START-$DHCP_END, broadcast replies)"
    if run_dnsmasq full "$IF"; then
        log "dnsmasq started"
        return 0
    fi
    log "dnsmasq rejected the full option set ($(tail -n 1 "$LOGFILE" 2>/dev/null)); retrying without option 114"
    kill_foreign_dnsmasq
    if run_dnsmasq basic "$IF"; then
        log "dnsmasq started (without captive-portal DHCP option)"
        return 0
    fi
    log "dnsmasq rejected the basic option set ($(tail -n 1 "$LOGFILE" 2>/dev/null)); retrying minimal"
    kill_foreign_dnsmasq
    if run_dnsmasq_min "$IF"; then
        log "dnsmasq started (minimal options)"
        return 0
    fi
    log "ERROR: dnsmasq failed to start ($(tail -n 1 "$LOGFILE" 2>/dev/null))"
    return 1
}

reapply_authorized() {
    [ -f "$AUTHORIZED_FILE" ] || return 0
    # authorize() rewrites AUTHORIZED_FILE. Reading it directly would drop
    # every line after the first on a restart.
    SNAP="${AUTHORIZED_FILE}.snap.$$"
    cp "$AUTHORIZED_FILE" "$SNAP" 2>/dev/null || return 0
    while read -r mac ip extra; do
        [ -n "$mac" ] || continue
        authorize "$mac" "$ip" "$extra"
    done < "$SNAP"
    rm -f "$SNAP"
}

# --- subcommands -------------------------------------------------------------

start() {
    WAN=$(resolve_wan)
    [ -n "$WAN" ] || die "could not determine the WAN interface; set WAN_IF in $CONF"
    WAN_ACTIVE="$WAN"
    log "WAN=$WAN LAN=$LAN_IF"

    ip link show "$LAN_IF" >/dev/null 2>&1 \
        || die "LAN interface '$LAN_IF' does not exist (see README: STA+AP / USB-OTG)"

    log "enabling IP forwarding"
    # Only when this interface exists in /proc. An off-device self-test has no
    # ap0 and must not flip the machine-wide forwarding sysctl.
    if [ -d "/proc/sys/net/ipv4/conf/$LAN_IF" ]; then
        echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true
    fi

    configure_lan_address
    relax_iface
    install_policy_routing
    remove_legacy_rules "$WAN"
    log "NAT: $LAN_SUBNET -> $WAN"
    install_chains "$WAN"
    reapply_authorized
    echo "$LAN_IF" > "$LAST_IF_FILE" 2>/dev/null || true

    # A dnsmasq from an earlier session (app force-closed, phone rebooted, stop
    # interrupted) would hold UDP/67 and make ours die with "Address already in
    # use"; the old code then adopted it and served addresses from a config
    # nobody had verified. Clear ours first, always.
    kill_our_dnsmasq

    # The Hot 8 (and other Android 9 tether stacks) abort the softap if their
    # dnsmasq cannot bind, and again if we kill it after the AP is up. When the
    # AP was started by the framework, wait for its dnsmasq and leave it.
    if [ "${KEEP_ANDROID_DHCP:-0}" = "1" ]; then
        log "KEEP_ANDROID_DHCP=1 - waiting for Android's tether dnsmasq instead of replacing it"
        i=0
        while [ "$i" -lt 5 ]; do
            if foreign_dnsmasq_running; then
                break
            fi
            sleep 1
            i=$((i + 1))
        done
        if foreign_dnsmasq_running; then
            unblock_foreign_dhcp
            DHCP_OWNER=android
            log "Android's dnsmasq owns DHCP and DNS on $LAN_IF; portal rules stay in place"
            write_runtime
            log "start complete (gateway $LAN_IP, dhcp $DHCP_OWNER)"
            return 0
        fi
        log "Android dnsmasq did not appear within 5s - starting ours"
    fi

    if start_dnsmasq "$LAN_IF"; then
        DHCP_OWNER=ours
        block_foreign_dhcp
    elif foreign_dnsmasq_running; then
        unblock_foreign_dhcp
        DHCP_OWNER=android
        log "Android's dnsmasq owns DHCP and DNS on $LAN_IF; portal rules stay in place"
    elif start_dnsmasq_dns_only "$LAN_IF"; then
        # Android 10+ serves DHCP for a WiFi Direct / local-only-hotspot interface
        # from inside system_server - no dnsmasq process to detect or replace. We
        # must still own port 53, or the client's probe cannot resolve and the
        # sign-in sheet never appears.
        unblock_foreign_dhcp
        DHCP_OWNER=ours-dns
        log "port 67 is held by the system's own DHCP server; our dnsmasq serves DNS only"
    else
        unblock_foreign_dhcp
        restore_android_dhcp
        if foreign_dnsmasq_running; then
            DHCP_OWNER=android
            log "Android DHCP is serving addresses; portal rules stay in place"
        else
            DHCP_OWNER=failed
            log "ERROR: no DHCP server is running - clients will stay on Obtaining IP"
        fi
    fi
    write_runtime
    log "start complete (gateway $LAN_IP, dhcp $DHCP_OWNER)"
}

# Android's tether service inserts its own ACCEPT at the top of FORWARD after
# we start. If that sits above our jump, clients get internet with no sign-in
# sheet — or, if it drops them, the probe never reaches the portal.
ensure_jump_first() {
    TBL="$1"; CHAIN="$2"; TARGET="$3"
    first=$(iptables -t "$TBL" -S "$CHAIN" 2>/dev/null | grep -v '^-P ' | head -n 1)
    case "$first" in
        "-A $CHAIN -j $TARGET") return 0 ;;
    esac
    delete_all "$TBL" "$CHAIN" -j "$TARGET"
    iptables -t "$TBL" -I "$CHAIN" 1 -j "$TARGET" 2>/dev/null || true
}

# Called every few seconds by the app. Must not flush the interface address
# and must not restart a fight with Android's DHCP server.
keepalive() {
    [ -f "$RUNTIME_FILE" ] && . "$RUNTIME_FILE"
    relax_iface
    install_policy_routing
    ensure_jump_first nat PREROUTING HS_NAT
    ensure_jump_first filter FORWARD HS_FWD
    ensure_jump_first filter INPUT HS_IN
    # A leftover instance from an earlier session must never survive a keepalive:
    # two of ours would fight over UDP/67 exactly like ours against Android's.
    if our_dnsmasq_orphan_running; then
        log "an untracked dnsmasq of ours is running (leftover session) - clearing it"
        kill_our_dnsmasq
        DHCP_OWNER=""
    fi
    # Android rewrites iptables when tethering restarts and leaves the address
    # alone. Reinstall the redirect without flushing that address, or the
    # client has an IP and still never sees the sign-in page.
    if ! iptables -t nat -C HS_NAT -i "$LAN_IF" -p tcp --dport 80 \
            -j REDIRECT --to-ports "$PORTAL_PORT" 2>/dev/null; then
        WAN=$(resolve_wan)
        if [ -n "$WAN" ]; then
            log "portal redirect missing, reinstalling"
            install_chains "$WAN"
            reapply_authorized
        fi
    fi
    WAN_ACTIVE=$(resolve_wan)
    if [ -n "$WAN_ACTIVE" ]; then
        ensure_top nat POSTROUTING -o "$WAN_ACTIVE" -s "$LAN_SUBNET" -j MASQUERADE
    fi
    if dnsmasq_pid >/dev/null && foreign_dnsmasq_running; then
        log "Android DHCP came back alongside ours - stepping aside so clients are not stuck obtaining an IP"
        if P=$(dnsmasq_pid); then
            kill "$P" 2>/dev/null
        fi
        rm -f "$PIDFILE"
        unblock_foreign_dhcp
        DHCP_OWNER=android
        write_runtime
        return 0
    fi
    if dnsmasq_pid >/dev/null; then
        block_foreign_dhcp
        [ -n "$DHCP_OWNER" ] || DHCP_OWNER=ours
        write_runtime
        return 0
    fi
    if foreign_dnsmasq_running; then
        # Android is the only server. Leave it alone.
        DHCP_OWNER=android
        write_runtime
        return 0
    fi
    log "no DHCP server running, starting ours"
    if start_dnsmasq "$LAN_IF"; then
        DHCP_OWNER=ours
        block_foreign_dhcp
        write_runtime
    fi
}

stop() {
    [ -f "$RUNTIME_FILE" ] && . "$RUNTIME_FILE"
    # Every instance of ours, not just the one the pidfile names: an orphan is
    # what makes the next start fail to bind (2026-09-24 debug log, dnsmasq
    # "failed to bind DHCP server socket: Address already in use").
    kill_our_dnsmasq

    WAN=$(resolve_wan)
    unblock_foreign_dhcp
    remove_legacy_rules "$WAN"
    remove_policy_routing

    delete_all nat PREROUTING -j HS_NAT
    delete_all filter FORWARD -j HS_FWD
    delete_all filter INPUT -j HS_IN
    iptables -t nat -F HS_NAT 2>/dev/null
    iptables -t nat -X HS_NAT 2>/dev/null
    iptables -t filter -F HS_FWD 2>/dev/null
    iptables -t filter -X HS_FWD 2>/dev/null
    iptables -t filter -F HS_IN 2>/dev/null
    iptables -t filter -X HS_IN 2>/dev/null

    ip6_delete_all FORWARD -i "$LAN_IF" -j DROP
    ip6_delete_all INPUT -i "$LAN_IF" -j DROP
    ip6_delete_all OUTPUT -o "$LAN_IF" -p icmpv6 --icmpv6-type router-advertisement -j DROP

    if [ -f "$AUTHORIZED_FILE" ]; then
        while read -r mac ip extra; do
            [ -n "$mac" ] || continue
            delete_all filter HS_FWD -i "$LAN_IF" -m mac --mac-source "$mac" -j ACCEPT
            delete_all nat HS_NAT -i "$LAN_IF" -m mac --mac-source "$mac" -j RETURN
            [ -n "$ip" ] && delete_all filter HS_FWD -i "$LAN_IF" -m mac --mac-source "$mac" -s "$ip" -j ACCEPT
            [ -n "$extra" ] && delete_all filter HS_FWD -i "$LAN_IF" -m mac --mac-source "$mac" -s "$extra" -j ACCEPT
        done < "$AUTHORIZED_FILE"
    fi

    sh "$(dirname "$0")/bandwidth_control.sh" stop 2>/dev/null
    log "stop complete"
}

authorize() {
    MAC=$(echo "$1" | tr 'A-F' 'a-f')
    IP="$2"
    EXTRA="${3:-}"
    [ -n "$MAC" ] && [ -n "$IP" ] || die "usage: authorize <mac> <static_ip> [current_ip]"

    iptables -t filter -N HS_FWD 2>/dev/null || true
    iptables -t nat -N HS_NAT 2>/dev/null || true

    # MAC only, not MAC+IP. The client is still on whatever address Android (or
    # a not-yet-renewed lease) gave it. A rule that also required the reserved
    # IP never matched, and the MAC RETURN had already exempted it from the
    # portal, so a paying user had neither internet nor a sign-in page.
    ensure_top filter HS_FWD -i "$LAN_IF" -m mac --mac-source "$MAC" -j ACCEPT
    ensure_top nat HS_NAT -i "$LAN_IF" -m mac --mac-source "$MAC" -j RETURN

    TMP="${AUTHORIZED_FILE}.tmp.$$"
    : > "$TMP"
    [ -f "$AUTHORIZED_FILE" ] && grep -v "^$MAC " "$AUTHORIZED_FILE" >> "$TMP" 2>/dev/null
    echo "$MAC $IP $EXTRA" >> "$TMP"
    mv "$TMP" "$AUTHORIZED_FILE"
    log "authorized $MAC ($IP${EXTRA:+, currently $EXTRA})"
}

deauthorize() {
    MAC=$(echo "$1" | tr 'A-F' 'a-f')
    [ -n "$MAC" ] || die "usage: deauthorize <mac>"

    delete_all nat HS_NAT -i "$LAN_IF" -m mac --mac-source "$MAC" -j RETURN
    delete_all nat PREROUTING -i "$LAN_IF" -m mac --mac-source "$MAC" -j RETURN
    delete_all filter HS_FWD -i "$LAN_IF" -m mac --mac-source "$MAC" -j ACCEPT
    delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$MAC" -j ACCEPT

    if [ -f "$AUTHORIZED_FILE" ]; then
        grep "^$MAC " "$AUTHORIZED_FILE" 2>/dev/null | while read -r m ip extra; do
            delete_all filter HS_FWD -i "$LAN_IF" -m mac --mac-source "$m" -s "$ip" -j ACCEPT
            delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$m" -s "$ip" -j ACCEPT
            [ -n "$extra" ] && delete_all filter HS_FWD -i "$LAN_IF" -m mac --mac-source "$m" -s "$extra" -j ACCEPT
            [ -n "$extra" ] && delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$m" -s "$extra" -j ACCEPT
        done
    fi

    TMP="${AUTHORIZED_FILE}.tmp.$$"
    grep -v "^$MAC " "$AUTHORIZED_FILE" > "$TMP" 2>/dev/null
    mv "$TMP" "$AUTHORIZED_FILE"
    log "deauthorized $MAC"
}

# dhcp-hostsfile format is "mac,ip" (no dhcp-host= prefix). That option exists
# on dnsmasq 2.51; --dhcp-hostsdir does not, and using it prevented DHCP from
# starting at all.
reserve() {
    MAC=$(echo "$1" | tr 'A-F' 'a-f')
    IP="$2"
    [ -n "$MAC" ] && [ -n "$IP" ] || die "usage: reserve <mac> <static_ip>"
    mkdir -p "$STATE_DIR"
    TMP="${HOSTS_FILE}.tmp.$$"
    : > "$TMP"
    [ -f "$HOSTS_FILE" ] && grep -v "^$MAC," "$HOSTS_FILE" >> "$TMP" 2>/dev/null
    echo "$MAC,$IP" >> "$TMP"
    mv "$TMP" "$HOSTS_FILE"
    if P=$(dnsmasq_pid); then
        kill -HUP "$P" 2>/dev/null
        log "reserved $MAC -> $IP (dnsmasq reloaded)"
    else
        log "reserved $MAC -> $IP (dnsmasq not running yet)"
    fi
}

unreserve() {
    MAC=$(echo "$1" | tr 'A-F' 'a-f')
    [ -n "$MAC" ] || die "usage: unreserve <mac>"
    if [ -f "$HOSTS_FILE" ]; then
        TMP="${HOSTS_FILE}.tmp.$$"
        grep -v "^$MAC," "$HOSTS_FILE" > "$TMP" 2>/dev/null
        mv "$TMP" "$HOSTS_FILE"
    fi
    rm -f "$HOSTS_DIR/$(echo "$MAC" | tr ':' '-')"
    if P=$(dnsmasq_pid); then kill -HUP "$P" 2>/dev/null; fi
    log "unreserved $MAC"
}

status() {
    [ -f "$RUNTIME_FILE" ] && . "$RUNTIME_FILE"
    WAN=$(resolve_wan)
    echo "WAN interface      : ${WAN:-<unknown>}"
    echo "LAN interface      : $LAN_IF"
    echo "LAN address        : $(ip -o -4 addr show dev "$LAN_IF" 2>/dev/null | awk '{print $4}' | head -n 1)"
    echo "gateway            : $LAN_IP"
    echo "dhcp owner         : $DHCP_OWNER"
    echo "ip_forward         : $(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)"
    if P=$(dnsmasq_pid); then echo "dnsmasq            : running (pid $P)"; else echo "dnsmasq            : stopped"; fi
    if foreign_dnsmasq_running; then echo "android dnsmasq    : running"; else echo "android dnsmasq    : stopped"; fi
    echo "-- policy routing (ip rule) --"
    ip rule show 2>/dev/null | grep -E "iif $LAN_IF|to $LAN_SUBNET" \
        || echo "(none of ours - forwarded traffic may hit Android's unreachable rule)"
    echo "-- runtime --"
    [ -f "$RUNTIME_FILE" ] && cat "$RUNTIME_FILE"
    echo "-- dhcp / ap processes --"
    procs
    echo "-- nat HS_NAT --"
    iptables -t nat -S HS_NAT 2>/dev/null
    echo "-- filter HS_FWD --"
    iptables -S HS_FWD 2>/dev/null
    echo "-- filter HS_IN --"
    iptables -S HS_IN 2>/dev/null
    echo "-- authorized --"
    [ -f "$AUTHORIZED_FILE" ] && cat "$AUTHORIZED_FILE"
    echo "-- dhcp reservations --"
    [ -f "$HOSTS_FILE" ] && cat "$HOSTS_FILE"
}

# Just the policy-routing rules, so the app can (re)apply them on its own after an
# interface change without a full start.
route_only() {
    LAN_IF="${1:-$LAN_IF}"
    LAN_SUBNET="${2:-$LAN_SUBNET}"
    [ -n "$LAN_IF" ] || die "usage: route <lan_if> <subnet>"
    log "ensuring policy routing for $LAN_IF / $LAN_SUBNET"
    install_policy_routing
    echo "-- ip rule --"
    ip rule show 2>/dev/null
}

# Re-install only what makes packet forwarding work: ip_forward, the two jumps,
# the portal/DNS redirects, the masquerade and the policy-routing rules.
#
# The app calls this when the uplink changes (WiFi <-> mobile data) and when the
# watchdog finds the jumps or the masquerade gone. It exists so the app never has
# to run the full `start` (which restarts DHCP and can therefore drop clients)
# just to repair routing.
nat_only() {
    LAN_IF="${1:-$LAN_IF}"
    WAN_ACTIVE="${2:-}"
    LAN_SUBNET="${3:-}"
    if [ -z "$WAN_ACTIVE" ] || [ -z "$LAN_SUBNET" ]; then
        [ -f "$RUNTIME_FILE" ] && . "$RUNTIME_FILE"
        LAN_SUBNET="${LAN_SUBNET:-$3}"
        WAN_ACTIVE="${WAN_ACTIVE:-$2}"
    fi
    [ -n "$WAN_ACTIVE" ] || WAN_ACTIVE=$(resolve_wan)
    [ -n "$WAN_ACTIVE" ] || die "usage: nat <lan_if> <wan_if> <subnet>"
    if [ -d "/proc/sys/net/ipv4/conf/$LAN_IF" ]; then
        echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true
    fi
    relax_iface
    install_policy_routing
    iptables -t nat -N HS_NAT 2>/dev/null || true
    iptables -t filter -N HS_FWD 2>/dev/null || true
    iptables -t filter -N HS_IN 2>/dev/null || true
    ensure_jump_first nat PREROUTING HS_NAT
    ensure_jump_first filter FORWARD HS_FWD
    ensure_jump_first filter INPUT HS_IN
    # Re-add the two redirects only if they are actually missing: -C is cheap but
    # a blind install every keepalive tick piles up duplicate rules.
    if ! iptables -t nat -C HS_NAT -i "$LAN_IF" -p tcp --dport 80 \
            -j REDIRECT --to-ports "$PORTAL_PORT" 2>/dev/null; then
        iptables -t nat -A HS_NAT -i "$LAN_IF" -p tcp --dport 80 \
            -j REDIRECT --to-ports "$PORTAL_PORT" 2>/dev/null \
            || log "FAILED to redirect port 80 to the portal"
    fi
    for proto in udp tcp; do
        if ! iptables -t nat -C HS_NAT -i "$LAN_IF" -p "$proto" --dport 53 \
                -j REDIRECT --to-ports "$HIJACK_PORT" 2>/dev/null; then
            iptables -t nat -A HS_NAT -i "$LAN_IF" -p "$proto" --dport 53 \
                -j REDIRECT --to-ports "$HIJACK_PORT" 2>/dev/null
        fi
    done
    ensure_top nat POSTROUTING -o "$WAN_ACTIVE" -s "$LAN_SUBNET" -j MASQUERADE
    # The uplink changed: the masquerade for the previous interface is dead
    # weight, and an unauthenticated client must not slip out through it.
    log "nat: $LAN_SUBNET -> $WAN_ACTIVE (jumps first, masquerade present)"
}

# One-shot health snapshot: every fact the app's watchdog needs, in a single
# root round-trip, as key=value lines it can parse.
#
# Why: on the 2026-09-24 debug log the app spent 142 root commands / 170 s of
# root-shell time in 7 minutes - 12+ commands per watchdog tick, each queueing
# behind the previous ones (a `cat /proc/net/arp` "took" 15.4 s because it was
# waiting for the shell). One command per tick removes the queue entirely.
probe() {
    [ -f "$RUNTIME_FILE" ] && . "$RUNTIME_FILE"
    LAN_IF="${1:-${LAN_IF_USED:-$LAN_IF}}"
    LAN_SUBNET="${LAN_SUBNET:-${LAN_IP%.*}.0/24}"
    WAN=$(resolve_wan)
    echo "probe_version=1"
    echo "lan=$LAN_IF"
    echo "wan=${WAN:--}"
    echo "subnet=$LAN_SUBNET"
    echo "gateway=$LAN_IP"
    if ip link show "$LAN_IF" >/dev/null 2>&1; then
        echo "lan_up=$(ip -o link show "$LAN_IF" 2>/dev/null | grep -q 'UP' && echo yes || echo no)"
    else
        echo "lan_up=no"
    fi
    echo "lan_addr=$(ip -o -4 addr show dev "$LAN_IF" 2>/dev/null | awk '{print $4}' | head -n 1)"
    echo "ip_forward=$(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)"

    # DHCP ownership, in three states the app can act on:
    #   dhcp_ours    - the instance our pidfile knows about
    #   dhcp_orphan  - ours but untracked (a leftover from an earlier session)
    #   dhcp_foreign - Android's tether dnsmasq
    if dnsmasq_pid >/dev/null; then echo "dhcp_ours=yes"; else echo "dhcp_ours=no"; fi
    if our_dnsmasq_orphan_running; then echo "dhcp_orphan=yes"; else echo "dhcp_orphan=no"; fi
    if foreign_dnsmasq_running; then echo "dhcp_foreign=yes"; else echo "dhcp_foreign=no"; fi
    echo "dhcp_pid=$(dnsmasq_pid 2>/dev/null || echo -)"

    NATS=$(iptables -t nat -S 2>/dev/null)
    FILT=$(iptables -t filter -S 2>/dev/null)
    case "$NATS" in
        *"-A PREROUTING -j HS_NAT"*) echo "nat_jump=yes" ;;
        *) echo "nat_jump=no" ;;
    esac
    case "$FILT" in
        *"-A FORWARD -j HS_FWD"*) echo "fwd_jump=yes" ;;
        *) echo "fwd_jump=no" ;;
    esac
    case "$FILT" in
        *"-A INPUT -j HS_IN"*) echo "in_jump=yes" ;;
        *) echo "in_jump=no" ;;
    esac
    case "$NATS" in
        *"-A POSTROUTING -o ${WAN} -s ${LAN_SUBNET} -j MASQUERADE"*|*"-o ${WAN} -s ${LAN_SUBNET} -j MASQUERADE"*)
            echo "masq=yes" ;;
        *) echo "masq=no" ;;
    esac
    case "$NATS" in
        *"-A HS_NAT -i ${LAN_IF} -p tcp --dport 80 -j REDIRECT --to-ports ${PORTAL_PORT}"*)
            echo "redirect=yes" ;;
        *) echo "redirect=no" ;;
    esac
    RULES=$(ip rule show 2>/dev/null)
    case "$RULES" in
        *"iif $LAN_IF lookup main"*) echo "rule_iif=yes" ;;
        *) echo "rule_iif=no" ;;
    esac
    case "$RULES" in
        *"to $LAN_SUBNET lookup main"*) echo "rule_subnet=yes" ;;
        *) echo "rule_subnet=no" ;;
    esac

    echo "leases=$(grep -c . "$LEASEFILE" 2>/dev/null || echo 0)"
    echo "authed=$(grep -c . "$AUTHORIZED_FILE" 2>/dev/null || echo 0)"
    echo "dhcp_reservations=$(grep -c . "$HOSTS_FILE" 2>/dev/null || echo 0)"
}

# Emergency cleaner / EXIT: leave nothing of ours behind, whatever state the
# scripts were in when the app died.
#
# Unlike stop(), this does not trust hotspot.runtime (it may be stale or gone),
# does not need the interface to exist any more, and also drops rules that were
# written for a previous interface name. Read-mostly and idempotent: it is safe
# to run twice, and safe to run while a gateway is supposed to be up (that is
# what "EXIT" means).
cleanup() {
    ARG_IF="${1:-}"
    RUNTIME_IF=""
    RUNTIME_SUBNET=""
    RUNTIME_WAN=""
    if [ -f "$RUNTIME_FILE" ]; then
        RUNTIME_IF=$(sed -n 's/^LAN_IF_USED=//p' "$RUNTIME_FILE" 2>/dev/null | head -n 1)
        RUNTIME_SUBNET=$(sed -n 's/^LAN_SUBNET=//p' "$RUNTIME_FILE" 2>/dev/null | head -n 1)
        RUNTIME_WAN=$(sed -n 's/^WAN_IF_ACTIVE=//p' "$RUNTIME_FILE" 2>/dev/null | head -n 1)
        . "$RUNTIME_FILE"
    fi
    # The interface we cleaned up last time: rules for a name the hotspot no
    # longer uses are invisible otherwise.
    LAST_IF=""
    [ -f "$LAST_IF_FILE" ] && LAST_IF=$(cat "$LAST_IF_FILE" 2>/dev/null)

    log "cleanup: removing everything this app put on the system"

    # 1. Every dnsmasq of ours, tracked or orphaned. Android's is left alone.
    kill_our_dnsmasq

    # 2. Firewall: our chains, our jumps, our per-MAC rules - for the current
    #    interface and for the one the previous session used.
    for IFACE in "$ARG_IF" "$RUNTIME_IF" "$LAST_IF" "$LAN_IF"; do
        [ -n "$IFACE" ] || continue
        LAN_IF_SAVED="$LAN_IF"
        LAN_IF="$IFACE"
        SUBNET_SAVED="$LAN_SUBNET"
        [ -n "$RUNTIME_SUBNET" ] && LAN_SUBNET="$RUNTIME_SUBNET"
        remove_legacy_rules "$RUNTIME_WAN"
        remove_policy_routing
        # The "drop DHCP replies from anyone but root" guard: leaving it behind
        # would silently block the NEXT session's DHCP answers (including
        # Android's, which we step aside for).
        unblock_foreign_dhcp
        LAN_SUBNET="$SUBNET_SAVED"
        LAN_IF="$LAN_IF_SAVED"
    done

    # 3. The jumps and the chains themselves.
    delete_all nat PREROUTING -j HS_NAT
    delete_all filter FORWARD -j HS_FWD
    delete_all filter INPUT -j HS_IN
    iptables -t nat -F HS_NAT 2>/dev/null
    iptables -t nat -X HS_NAT 2>/dev/null
    iptables -t filter -F HS_FWD 2>/dev/null
    iptables -t filter -X HS_FWD 2>/dev/null
    iptables -t filter -F HS_IN 2>/dev/null
    iptables -t filter -X HS_IN 2>/dev/null

    # 4. Our masquerade on any uplink we ever pinned.
    for W in "$RUNTIME_WAN" "$WAN_ACTIVE" "$(resolve_wan)"; do
        [ -n "$W" ] || continue
        for S in "$RUNTIME_SUBNET" "$LAN_SUBNET" "10.66.0.0/24"; do
            [ -n "$S" ] || continue
            delete_all nat POSTROUTING -o "$W" -s "$S" -j MASQUERADE
        done
    done

    # 5. IPv6 guards.
    for IFACE in "$ARG_IF" "$RUNTIME_IF" "$LAST_IF"; do
        [ -n "$IFACE" ] || continue
        ip6_delete_all FORWARD -i "$IFACE" -j DROP
        ip6_delete_all INPUT -i "$IFACE" -j DROP
        ip6_delete_all OUTPUT -o "$IFACE" -p icmpv6 --icmpv6-type router-advertisement -j DROP
    done

    # 6. Shaping and the root hostapd (netshare fallback), if either is present.
    sh "$(dirname "$0")/bandwidth_control.sh" stop 2>/dev/null
    if [ -f "$(dirname "$0")/netshare_ap.sh" ]; then
        sh "$(dirname "$0")/netshare_ap.sh" stop >/dev/null 2>&1
    fi
    # Any hostapd that is managing *our* state file.
    if [ -f "$STATE_DIR/netshare_hostapd.pid" ]; then
        HP=$(cat "$STATE_DIR/netshare_hostapd.pid" 2>/dev/null)
        if [ -n "$HP" ] && kill -0 "$HP" 2>/dev/null; then
            kill "$HP" 2>/dev/null && log "cleanup: stopped the root-hostapd (pid $HP)"
        fi
        rm -f "$STATE_DIR/netshare_hostapd.pid"
    fi

    # 7. State files: a stale pidfile or runtime file is what makes the *next*
    #    start adopt a process that is no longer ours.
    rm -f "$PIDFILE" "$RUNTIME_FILE" "$STATE_DIR/netshare.runtime"
    rm -f "$STATE_DIR/dnsmasq_hotspot.log.old"
    if [ -f "$LAST_IF_FILE" ]; then
        rm -f "$LAST_IF_FILE"
    fi
    log "cleanup complete"
}

# "yes" when a DHCP server that is not ours is running. The app's watchdog uses
# this to explain a client stuck on "Obtaining IP address".
foreign_dhcp() {
    if foreign_dnsmasq_running; then echo "yes"; else echo "no"; fi
}

# Drop every dnsmasq, including ours. Called only when no AP is up and we are
# about to ask the framework to start one. A leftover listener on port 53 is
# why the Hot 8's tether dnsmasq logged "Address already in use" and the
# framework then ran stopSoftAp.
free_dns() {
    if [ "$STATE_DIR" != "/data/local/tmp" ]; then
        log "free-dns skipped (off-device)"
        return 0
    fi
    if P=$(dnsmasq_pid 2>/dev/null); then
        log "stopping our dnsmasq (pid $P) so the system tether can bind port 53"
        kill "$P" 2>/dev/null
        sleep 1
        kill -9 "$P" 2>/dev/null
        rm -f "$PIDFILE"
    fi
    for proc in /proc/[0-9]*; do
        pid=${proc#/proc/}
        cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
        if is_dnsmasq_cmd "$cmdline"; then
            if kill "$pid" 2>/dev/null; then
                log "stopped dnsmasq pid $pid"
            fi
        fi
    done
    log "free-dns complete"
}

# Every DHCP / AP daemon with its full command line: which one owns port 67, and
# with what arguments.
procs() {
    for proc in /proc/[0-9]*; do
        pid=${proc#/proc/}
        cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
        [ -n "$cmdline" ] || continue
        case "$cmdline" in
            *hostapd*|*wpa_supplicant*|*netshare_ap*) echo "$pid: $cmdline" ;;
            *) if is_dnsmasq_cmd "$cmdline"; then echo "$pid: $cmdline"; fi ;;
        esac
    done
}

# Everything the debugger asks for in one go. Read-only: it changes no state.
diag() {
    status
    echo "-- interfaces --"
    ip -o link show 2>/dev/null
    echo "-- addresses --"
    ip -o -4 addr show 2>/dev/null
    echo "-- routes (main) --"
    ip route show 2>/dev/null
    echo "-- all ip rules --"
    ip rule show 2>/dev/null
    echo "-- counters: HS_FWD --"
    iptables -t filter -L HS_FWD -v -n -x 2>/dev/null
    echo "-- counters: HS_NAT --"
    iptables -t nat -L HS_NAT -v -n -x 2>/dev/null
    echo "-- counters: HS_IN --"
    iptables -t filter -L HS_IN -v -n -x 2>/dev/null
    echo "-- nat POSTROUTING --"
    iptables -t nat -S POSTROUTING 2>/dev/null
    echo "-- leases (ours) --"
    cat "$LEASEFILE" 2>/dev/null
    echo "-- leases (android) --"
    cat /data/misc/dhcp/dnsmasq.leases /data/misc/dhcp/dnsmasq.tether.leases 2>/dev/null
    echo "-- arp --"
    cat /proc/net/arp 2>/dev/null
    echo "-- netshare ap runtime --"
    cat "$STATE_DIR/netshare.runtime" 2>/dev/null || echo "(netshare_ap.sh has not been used)"
    echo "-- dnsmasq log (tail) --"
    tail -n 25 "$LOGFILE" 2>/dev/null
    echo "-- shaper --"
    sh "$(dirname "$0")/bandwidth_control.sh" list 2>/dev/null
}

case "${1:-}" in
    start)        start ;;
    stop)         stop ;;
    status)       status ;;
    keepalive)    keepalive ;;
    authorize)    authorize "${2:-}" "${3:-}" "${4:-}" ;;
    deauthorize)  deauthorize "${2:-}" ;;
    reserve)      reserve "${2:-}" "${3:-}" ;;
    unreserve)    unreserve "${2:-}" ;;
    route)        route_only "${2:-}" "${3:-}" ;;
    nat)          nat_only "${2:-}" "${3:-}" "${4:-}" ;;
    probe)        probe "${2:-}" ;;
    cleanup)      cleanup "${2:-}" ;;
    foreign-dhcp) foreign_dhcp ;;
    free-dns)     free_dns ;;
    procs)        procs ;;
    diag)         diag ;;
    *) echo "usage: $0 {start|stop|status|keepalive|cleanup [lan_if]|probe [lan_if]|nat <lan_if> <wan_if> <subnet>|diag|route <lan_if> <subnet>|foreign-dhcp|free-dns|procs|authorize <mac> <ip> [cur_ip]|deauthorize <mac>|reserve <mac> <ip>|unreserve <mac>}" ;;
esac
