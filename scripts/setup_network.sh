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
#   sh /data/local/tmp/setup_network.sh authorize   <mac> <static_ip> [current_ip]
#   sh /data/local/tmp/setup_network.sh deauthorize <mac>
#   sh /data/local/tmp/setup_network.sh reserve     <mac> <static_ip>
#   sh /data/local/tmp/setup_network.sh unreserve   <mac>
#
# CONFIG
#   Every setting below can be overridden without editing this file by writing
#   them to /data/local/tmp/hotspot.env, e.g.:
#       WAN_IF=ccmni0
#       LAN_IF=ap0
#       UPSTREAM_DNS=8.8.8.8
# =============================================================================

# HOTSPOT_STATE_DIR exists so the scripts can be exercised off-device; on the
# phone it stays /data/local/tmp.
STATE_DIR="${HOTSPOT_STATE_DIR:-/data/local/tmp}"
CONF="${HOTSPOT_CONF:-$STATE_DIR/hotspot.env}"
# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"

# --- interfaces --------------------------------------------------------------
# WAN_IF=auto picks the interface holding the default route. On the Infinix Hot 8
# (MediaTek) the uplink is a ccmni* PPP interface, NOT wlan0 - `ip link` on the
# device does not even list wlan0 while WiFi is off, so auto-detect is the safe
# default. Pin it in hotspot.env once you have confirmed your topology.
WAN_IF="${WAN_IF:-auto}"
LAN_IF="${LAN_IF:-ap0}"

# --- addressing --------------------------------------------------------------
LAN_IP="${LAN_IP:-10.66.0.1}"
LAN_PREFIX="${LAN_PREFIX:-24}"
LAN_SUBNET="${LAN_SUBNET:-10.66.0.0/24}"
# Range spans the whole pool: 10.66.0.10-49 are handed out as voucher statics via
# --dhcp-host reservations, the rest is dynamic. dnsmasq never allocates a
# reserved address to anybody else, so one range covering both is correct.
DHCP_START="${DHCP_START:-10.66.0.10}"
DHCP_END="${DHCP_END:-10.66.0.250}"
DHCP_LEASE="${DHCP_LEASE:-10m}"   # short on purpose: clients migrate to their
                                  # reserved IP within one lease, not 12 hours
UPSTREAM_DNS="${UPSTREAM_DNS:-8.8.8.8}"
UPSTREAM_DNS2="${UPSTREAM_DNS2:-1.1.1.1}"

# --- captive portal ----------------------------------------------------------
PORTAL_PORT="${PORTAL_PORT:-8080}"

# --- runtime files -----------------------------------------------------------
PIDFILE="$STATE_DIR/dnsmasq_hotspot.pid"
LEASEFILE="$STATE_DIR/dnsmasq.leases"
LOGFILE="$STATE_DIR/dnsmasq_hotspot.log"
HOSTS_DIR="$STATE_DIR/dhcp_hosts.d"
AUTHORIZED_FILE="$STATE_DIR/authorized_macs.txt"

log() { echo "[setup_network] $*"; }
die() { echo "[setup_network] ERROR: $*" >&2; exit 1; }

# --- helpers -----------------------------------------------------------------

# resolve WAN_IF once per invocation
resolve_wan() {
    if [ "$WAN_IF" != "auto" ]; then
        echo "$WAN_IF"
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
# Prevents the duplicate-rule pile-up that repeated authorize/deauthorize caused.
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

enable_forwarding() {
    if command -v sysctl >/dev/null 2>&1; then
        sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 && return 0
    fi
    echo 1 > /proc/sys/net/ipv4/ip_forward
}

dnsmasq_pid() {
    [ -f "$PIDFILE" ] || return 1
    P=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$P" ] && kill -0 "$P" 2>/dev/null && echo "$P" && return 0
    return 1
}

# --- subcommands -------------------------------------------------------------

start() {
    WAN=$(resolve_wan)
    [ -n "$WAN" ] || die "could not determine the WAN interface; set WAN_IF in $CONF"
    log "WAN=$WAN LAN=$LAN_IF"

    ip link show "$LAN_IF" >/dev/null 2>&1 \
        || die "LAN interface '$LAN_IF' does not exist (see README: STA+AP / USB-OTG)"

    log "enabling IP forwarding"
    echo 1 > /proc/sys/net/ipv4/ip_forward

    # Only touch the LAN address if it is not already right - if this is Android's
    # own hotspot interface the tethering service owns it and a blind `addr flush`
    # makes the two fight each other.
    CURRENT=$(ip -o -4 addr show dev "$LAN_IF" 2>/dev/null | awk '{print $4}' | head -n 1)
    if [ "$CURRENT" != "${LAN_IP}/${LAN_PREFIX}" ]; then
        log "configuring $LAN_IF as ${LAN_IP}/${LAN_PREFIX} (was ${CURRENT:-none})"
        ip addr flush dev "$LAN_IF" 2>/dev/null
        ip addr add "${LAN_IP}/${LAN_PREFIX}" dev "$LAN_IF"
    fi
    ip link set "$LAN_IF" up

    log "NAT: $LAN_SUBNET -> $WAN"
    ensure_top nat POSTROUTING -o "$WAN" -s "$LAN_SUBNET" -j MASQUERADE

    # --- filter/FORWARD ------------------------------------------------------
    # Built bottom-up with -I ... 1 so the final order is:
    #   1. per-client ACCEPTs (added by `authorize`, always above the deny)
    #   2. return traffic for the LAN
    #   3. deny everything else from the LAN  <-- this is what makes the portal
    #      meaningful. The old script appended an unconditional ACCEPT for
    #      LAN->WAN after this, which let unauthenticated clients straight out.
    ensure_top filter FORWARD -i "$LAN_IF" -j DROP
    # Fail fast on HTTPS for unauthenticated clients instead of silently dropping:
    # a TCP reset makes the browser give up and retry over plain HTTP, which the
    # DNAT rule above then turns into the portal. (DNATing 443 to a plaintext
    # server, as before, only produced certificate errors and no login sheet.)
    ensure_top filter FORWARD -i "$LAN_IF" -p tcp --dport 443 -j REJECT --reject-with tcp-reset
    ensure_top filter FORWARD -o "$LAN_IF" -d "$LAN_SUBNET" -m state --state RELATED,ESTABLISHED -j ACCEPT

    # --- nat/PREROUTING ------------------------------------------------------
    # Only port 80 is redirected. Port 443 is reset by the deny rule above so
    # clients fall back to their plain-HTTP probe; DNATing TLS to a plaintext
    # server just produces certificate errors and no login sheet.
    ensure_top nat PREROUTING -i "$LAN_IF" -p tcp --dport 80 -j DNAT \
        --to-destination "${LAN_IP}:${PORTAL_PORT}"

    # IPv6 would otherwise be a free bypass around all of the above.
    ip6tables -C FORWARD -i "$LAN_IF" -j DROP 2>/dev/null \
        || ip6tables -I FORWARD 1 -i "$LAN_IF" -j DROP 2>/dev/null

    # Re-apply anything that survived a previous run.
    if [ -f "$AUTHORIZED_FILE" ]; then
        while read -r mac ip extra; do
            [ -n "$mac" ] || continue
            authorize "$mac" "$ip" "$extra"
        done < "$AUTHORIZED_FILE"
    fi

    start_dnsmasq "$LAN_IF"
    log "start complete"
}

start_dnsmasq() {
    IF="$1"
    if P=$(dnsmasq_pid); then
        log "dnsmasq already running (pid $P)"
        return 0
    fi
    rm -f "$PIDFILE"
    mkdir -p "$HOSTS_DIR"

    log "starting dnsmasq on $IF"
    # No --no-daemon: dnsmasq must detach, otherwise it dies with the shell that
    # started it (the old script backgrounded it with & and lost it).
    dnsmasq \
        --interface="$IF" \
        --bind-interfaces \
        --except-interface=lo \
        --dhcp-range="${DHCP_START},${DHCP_END},${DHCP_LEASE}" \
        --dhcp-authoritative \
        --dhcp-lease-max=250 \
        --dhcp-option="3,${LAN_IP}" \
        --dhcp-option="6,${LAN_IP}" \
        --dhcp-hostsdir="$HOSTS_DIR" \
        --dhcp-leasefile="$LEASEFILE" \
        --no-resolv \
        --server="$UPSTREAM_DNS" \
        --server="$UPSTREAM_DNS2" \
        --domain=lan \
        --local=/lan/ \
        --user=root \
        --pid-file="$PIDFILE" \
        --log-facility="$LOGFILE" \
        --conf-file= \
        || die "dnsmasq failed to start (see $LOGFILE)"
    log "dnsmasq started"
}

stop() {
    if P=$(dnsmasq_pid); then
        log "stopping dnsmasq (pid $P)"
        kill "$P" 2>/dev/null
    fi
    rm -f "$PIDFILE"

    WAN=$(resolve_wan)

    log "removing gateway rules"
    # Delete only our own rules - a global `iptables -F` also wipes Android's
    # tethering and per-UID accounting chains and breaks the phone itself.
    [ -n "$WAN" ] && delete_all nat POSTROUTING -o "$WAN" -s "$LAN_SUBNET" -j MASQUERADE
    delete_all nat PREROUTING -i "$LAN_IF" -p tcp --dport 80 -j DNAT \
        --to-destination "${LAN_IP}:${PORTAL_PORT}"
    delete_all filter FORWARD -o "$LAN_IF" -d "$LAN_SUBNET" -m state --state RELATED,ESTABLISHED -j ACCEPT
    delete_all filter FORWARD -i "$LAN_IF" -p tcp --dport 443 -j REJECT --reject-with tcp-reset
    delete_all filter FORWARD -i "$LAN_IF" -j DROP
    ip6_delete_all FORWARD -i "$LAN_IF" -j DROP 2>/dev/null

    # Per-client rules
    if [ -f "$AUTHORIZED_FILE" ]; then
        while read -r mac ip extra; do
            [ -n "$mac" ] || continue
            delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$mac" -s "$ip" -j ACCEPT
            [ -n "$extra" ] && [ "$extra" != "$ip" ] && \
                delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$mac" -s "$extra" -j ACCEPT
        done < "$AUTHORIZED_FILE"
    fi

    # Tear the shaper down too - leaving it in place would keep throttling at the
    # default 64kbit bucket after "stop".
    sh "$(dirname "$0")/bandwidth_control.sh" stop 2>/dev/null

    log "stop complete"
}

authorize() {
    MAC=$(echo "$1" | tr 'A-F' 'a-f')
    IP="$2"
    EXTRA="${3:-}"
    [ -n "$MAC" ] && [ -n "$IP" ] || die "usage: authorize <mac> <static_ip> [current_ip]"

    ensure_top filter FORWARD -i "$LAN_IF" -m mac --mac-source "$MAC" -s "$IP" -j ACCEPT
    # The client is still holding its old dynamic lease until DHCP renews, so let
    # that address through as well - otherwise it goes dark in the meantime.
    if [ -n "$EXTRA" ] && [ "$EXTRA" != "$IP" ]; then
        ensure_top filter FORWARD -i "$LAN_IF" -m mac --mac-source "$MAC" -s "$EXTRA" -j ACCEPT
    fi
    # Authorised clients must skip the portal redirect.
    ensure_top nat PREROUTING -i "$LAN_IF" -m mac --mac-source "$MAC" -j RETURN

    # Rewrite the state file without duplicates instead of appending forever.
    TMP="${AUTHORIZED_FILE}.tmp.$$"
    : > "$TMP"
    [ -f "$AUTHORIZED_FILE" ] && grep -v "^$MAC " "$AUTHORIZED_FILE" >> "$TMP" 2>/dev/null
    echo "$MAC $IP $EXTRA" >> "$TMP"
    mv "$TMP" "$AUTHORIZED_FILE"
    log "authorized $MAC ($IP${EXTRA:+, transitionally $EXTRA})"
}

deauthorize() {
    MAC=$(echo "$1" | tr 'A-F' 'a-f')
    [ -n "$MAC" ] || die "usage: deauthorize <mac>"

    delete_all nat PREROUTING -i "$LAN_IF" -m mac --mac-source "$MAC" -j RETURN

    if [ -f "$AUTHORIZED_FILE" ]; then
        grep "^$MAC " "$AUTHORIZED_FILE" 2>/dev/null | while read -r m ip extra; do
            delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$m" -s "$ip" -j ACCEPT
            [ -n "$extra" ] && delete_all filter FORWARD -i "$LAN_IF" -m mac --mac-source "$m" -s "$extra" -j ACCEPT
        done
    fi

    TMP="${AUTHORIZED_FILE}.tmp.$$"
    grep -v "^$MAC " "$AUTHORIZED_FILE" > "$TMP" 2>/dev/null
    mv "$TMP" "$AUTHORIZED_FILE"
    log "deauthorized $MAC"
}

# Pin <mac> -> <ip> in DHCP so the address the app picked is the address the
# client actually uses. Without this the static IP existed only inside iptables
# and tc rules and never reached the client.
reserve() {
    MAC=$(echo "$1" | tr 'A-F' 'a-f')
    IP="$2"
    [ -n "$MAC" ] && [ -n "$IP" ] || die "usage: reserve <mac> <static_ip>"
    mkdir -p "$HOSTS_DIR"
    # dnsmasq ignores hostsdir files whose names contain a dot, so use dashes.
    echo "dhcp-host=$MAC,$IP" > "$HOSTS_DIR/$(echo "$MAC" | tr ':' '-')"
    if P=$(dnsmasq_pid); then
        kill -HUP "$P" 2>/dev/null   # SIGHUP re-reads --dhcp-hostsdir
        log "reserved $MAC -> $IP (dnsmasq reloaded)"
    else
        log "reserved $MAC -> $IP (dnsmasq not running yet)"
    fi
}

unreserve() {
    MAC=$(echo "$1" | tr 'A-F' 'a-f')
    [ -n "$MAC" ] || die "usage: unreserve <mac>"
    rm -f "$HOSTS_DIR/$(echo "$MAC" | tr ':' '-')"
    if P=$(dnsmasq_pid); then kill -HUP "$P" 2>/dev/null; fi
    log "unreserved $MAC"
}

status() {
    WAN=$(resolve_wan)
    echo "WAN interface      : ${WAN:-<unknown>}"
    echo "LAN interface      : $LAN_IF"
    echo "LAN address        : $(ip -o -4 addr show dev "$LAN_IF" 2>/dev/null | awk '{print $4}' | head -n 1)"
    echo "ip_forward         : $(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null)"
    if P=$(dnsmasq_pid); then echo "dnsmasq            : running (pid $P)"; else echo "dnsmasq            : stopped"; fi
    echo "-- nat PREROUTING (ours) --"
    iptables -t nat -S PREROUTING 2>/dev/null | grep -- "-i $LAN_IF"
    echo "-- filter FORWARD (ours) --"
    iptables -S FORWARD 2>/dev/null | grep -- "-i $LAN_IF"
    echo "-- authorized --"
    [ -f "$AUTHORIZED_FILE" ] && cat "$AUTHORIZED_FILE"
    echo "-- dhcp reservations --"
    [ -d "$HOSTS_DIR" ] && cat "$HOSTS_DIR"/* 2>/dev/null
}

case "${1:-}" in
    start)       start ;;
    stop)        stop ;;
    status)      status ;;
    authorize)   authorize "${2:-}" "${3:-}" "${4:-}" ;;
    deauthorize) deauthorize "${2:-}" ;;
    reserve)     reserve "${2:-}" "${3:-}" ;;
    unreserve)   unreserve "${2:-}" ;;
    *) echo "usage: $0 {start|stop|status|authorize <mac> <ip> [cur_ip]|deauthorize <mac>|reserve <mac> <ip>|unreserve <mac>}" ;;
esac
