#!/system/bin/sh
# =============================================================================
# bandwidth_control.sh - per-client tc/htb shaping on the LAN interface
#
# USAGE (as root):
#   sh /data/local/tmp/bandwidth_control.sh init
#   sh /data/local/tmp/bandwidth_control.sh stop
#   sh /data/local/tmp/bandwidth_control.sh add    <ip> <class_id> <rate_kbit> <ceil_kbit>
#   sh /data/local/tmp/bandwidth_control.sh remove <ip> <class_id>
#   sh /data/local/tmp/bandwidth_control.sh list
#
# WHY IT REBUILDS
#   `tc filter del ... u32 match ip dst ...` is not a valid delete - tc deletes
#   filters by handle, not by match expression. Filters therefore accumulated
#   forever and, once their class was deleted, blackholed the traffic they
#   matched. This script keeps the authoritative set of classes in a state file
#   and rebuilds the tree from it, which is both idempotent and recoverable
#   after the interface goes down (the pool is <= 40 clients, so a full rebuild
#   is cheap).
#
# DIRECTIONS
#   egress on the LAN interface = download to the client  (matched on dst IP, HTB)
#   ingress on the LAN interface = upload from the client  (matched on src IP, police)
#   Shaping only egress, as before, left uploads completely uncapped.
# =============================================================================

# HOTSPOT_STATE_DIR exists so the scripts can be exercised off-device; on the
# phone it stays /data/local/tmp.
STATE_DIR="${HOTSPOT_STATE_DIR:-/data/local/tmp}"
CONF="${HOTSPOT_CONF:-$STATE_DIR/hotspot.env}"
# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"

LAN_IF="${LAN_IF:-ap0}"
ROOT_RATE="${ROOT_RATE:-1000mbit}"
FALLBACK_CLASS="${FALLBACK_CLASS:-999}"
FALLBACK_RATE="${FALLBACK_RATE:-64kbit}"
FALLBACK_CEIL="${FALLBACK_CEIL:-128kbit}"
STATE_FILE="$STATE_DIR/hotspot_tc.state"   # one "<ip> <class_id> <rate> <ceil>" per line

log() { echo "[bandwidth] $*"; }

# burst for `tc police`: roughly two seconds of data at the shaped rate, in KB.
burst_for() {
    RATE_KBIT="$1"
    B=$(( RATE_KBIT / 4 ))
    [ "$B" -lt 16 ] && B=16
    echo "$B"
}

init() {
    : > "$STATE_FILE"
    rebuild
    log "initialised on $LAN_IF"
}

stop() {
    tc qdisc del dev "$LAN_IF" root 2>/dev/null
    tc qdisc del dev "$LAN_IF" ingress 2>/dev/null
    : > "$STATE_FILE"
    log "shaping removed from $LAN_IF"
}

rebuild() {
    tc qdisc del dev "$LAN_IF" root 2>/dev/null
    tc qdisc del dev "$LAN_IF" ingress 2>/dev/null

    if ! tc qdisc add dev "$LAN_IF" root handle 1: htb default "$FALLBACK_CLASS" 2>/dev/null; then
        log "ERROR: this tc build has no HTB scheduler - shaping unavailable"
        return 1
    fi
    tc class add dev "$LAN_IF" parent 1: classid "1:1" htb rate "$ROOT_RATE" ceil "$ROOT_RATE"
    tc class add dev "$LAN_IF" parent 1:1 classid "1:$FALLBACK_CLASS" htb \
        rate "$FALLBACK_RATE" ceil "$FALLBACK_CEIL"
    tc qdisc add dev "$LAN_IF" handle ffff: ingress

    [ -s "$STATE_FILE" ] || return 0

    while read -r IP CID RATE CEIL; do
        [ -n "$IP" ] || continue
        tc class add dev "$LAN_IF" parent 1:1 classid "1:$CID" htb \
            rate "${RATE}kbit" ceil "${CEIL}kbit"
        # download
        tc filter add dev "$LAN_IF" protocol ip parent 1:0 prio 1 u32 \
            match ip dst "${IP}/32" flowid "1:$CID"
        # upload
        tc filter add dev "$LAN_IF" parent ffff: protocol ip prio 1 u32 \
            match ip src "${IP}/32" police rate "${RATE}kbit" burst "$(burst_for "$RATE")k" drop flowid :1
    done < "$STATE_FILE"
    return 0
}

add() {
    IP="$1"; CID="$2"; RATE="$3"; CEIL="$4"
    [ -n "$IP" ] && [ -n "$CID" ] && [ -n "$RATE" ] && [ -n "$CEIL" ] \
        || { echo "usage: $0 add <ip> <class_id> <rate_kbit> <ceil_kbit>"; return 1; }
    mkdir -p "$STATE_DIR"
    TMP="${STATE_FILE}.tmp.$$"
    : > "$TMP"
    [ -f "$STATE_FILE" ] && grep -v "^$IP " "$STATE_FILE" >> "$TMP" 2>/dev/null
    echo "$IP $CID $RATE $CEIL" >> "$TMP"
    mv "$TMP" "$STATE_FILE"
    rebuild
    log "added $IP class 1:$CID rate ${RATE}kbit ceil ${CEIL}kbit"
}

remove() {
    IP="$1"
    [ -n "$IP" ] || { echo "usage: $0 remove <ip> <class_id>"; return 1; }
    TMP="${STATE_FILE}.tmp.$$"
    : > "$TMP"
    [ -f "$STATE_FILE" ] && grep -v "^$IP " "$STATE_FILE" >> "$TMP" 2>/dev/null
    mv "$TMP" "$STATE_FILE"
    rebuild
    log "removed $IP"
}

# Drop every class with this id, whichever IP it was attached to. Used to clear
# the transitional cap on the address a client still holds after a voucher ends.
remove_class() {
    CID="$1"
    [ -n "$CID" ] || { echo "usage: $0 remove-class <class_id>"; return 1; }
    TMP="${STATE_FILE}.tmp.$$"
    : > "$TMP"
    if [ -f "$STATE_FILE" ]; then
        while read -r IP C RATE CEIL; do
            [ -n "$IP" ] || continue
            [ "$C" = "$CID" ] && continue
            echo "$IP $C $RATE $CEIL" >> "$TMP"
        done < "$STATE_FILE"
    fi
    mv "$TMP" "$STATE_FILE"
    rebuild
    log "removed class 1:$CID"
}

list() {
    echo "interface: $LAN_IF"
    echo "-- state --"
    [ -f "$STATE_FILE" ] && cat "$STATE_FILE"
    echo "-- qdiscs --"
    tc qdisc show dev "$LAN_IF" 2>/dev/null
    echo "-- classes --"
    tc class show dev "$LAN_IF" 2>/dev/null
}

case "${1:-}" in
    init)   init ;;
    stop)   stop ;;
    add)          add "${2:-}" "${3:-}" "${4:-}" "${5:-}" ;;
    remove)       remove "${2:-}" "${3:-}" ;;
    remove-class) remove_class "${2:-}" ;;
    list)         list ;;
    *) echo "usage: $0 {init|stop|add <ip> <class_id> <rate_kbit> <ceil_kbit>|remove <ip> <class_id>|remove-class <class_id>|list}" ;;
esac
