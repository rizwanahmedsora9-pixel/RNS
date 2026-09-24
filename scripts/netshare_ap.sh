#!/system/bin/sh
# =============================================================================
# netshare_ap.sh - create a second WiFi network as root, no hotspot toggle
#
# USAGE (as root):
#   sh /data/local/tmp/netshare_ap.sh start <ssid> <passphrase> [channel]
#   sh /data/local/tmp/netshare_ap.sh stop
#   sh /data/local/tmp/netshare_ap.sh status
#
# WHAT THIS IS
#   The "NetShare" idea: the phone stays associated to its own WiFi (that is the
#   internet side) and at the same time runs an access point for customers, so
#   nobody has to switch the Android hotspot on. This is the rooted version -
#   instead of asking the framework, it asks the WiFi driver directly:
#
#     iw dev wlan0 interface add rnsap0 type __ap     (a second virtual interface)
#     <vendor hostapd> -B /data/local/tmp/netshare_hostapd.conf
#
#   setup_network.sh then does NAT/DHCP/portal on that interface like any other
#   LAN, and adds the `ip rule` entries Android's routing would otherwise need.
#
# WHEN IT CANNOT WORK
#   Only if the driver supports STA+AP concurrency. When it does not, `iw` says
#   so explicitly and that message is written to the log - which is the point:
#   a definite answer instead of an AP that silently never appears.
#
# EVERY line this script prints is captured by the app's debugger.
# =============================================================================

STATE_DIR="${HOTSPOT_STATE_DIR:-/data/local/tmp}"
CONF_FILE="${HOTSPOT_NETSHARE_CONF:-$STATE_DIR/netshare_hostapd.conf}"
RUNTIME_FILE="${HOTSPOT_NETSHARE_RUNTIME:-$STATE_DIR/netshare.runtime}"
PIDFILE="$STATE_DIR/netshare_hostapd.pid"
HOSTAPD_LOG="$STATE_DIR/netshare_hostapd.log"
IFACE_NAME="${HOTSPOT_NETSHARE_IFACE:-rnsap0}"
AP_IP="${HOTSPOT_NETSHARE_IP:-192.168.50.1}"
AP_PREFIX="${HOTSPOT_NETSHARE_PREFIX:-24}"
MAX_STA="${HOTSPOT_NETSHARE_MAX_STA:-32}"

log() { echo "[netshare_ap] $*"; }
err() { echo "[netshare_ap] ERROR: $*"; echo "[netshare_ap] ERROR: $*" >&2; }
# stdout as well as stderr: the debugger on the Hot 8 showed "exit 1" and the
# discovery lines, and dropped the stderr-only reason.
die() { echo "[netshare_ap] ERROR: $*"; echo "[netshare_ap] ERROR: $*" >&2; exit 1; }

# ------------------------------------------------------------------ discovery

# ccmni0 / rmnet* are the mobile uplink. `iw dev ccmni0` cannot create an AP,
# and that is exactly what the Hot 8 log did (sta=ccmni0, then exit 1).
is_mobile_iface() {
    case "$1" in
        ccmni*|rmnet*|ccemni*|pdp*|ppp*) return 0 ;;
        *) return 1 ;;
    esac
}

# The WiFi STA radio, if one exists. Independent of the default route: on this
# phone the default route is ccmni0 while wlan0 is down or absent.
find_wifi_radio() {
    for candidate in wlan0 wlan1 swlan0; do
        if iface_exists "$candidate"; then echo "$candidate"; return 0; fi
    done
    for name in $(ip -o link show 2>/dev/null | sed -n 's/^[0-9]*: \([^:@ ]*\).*/\1/p'); do
        case "$name" in
            wlan*|swlan*) echo "$name"; return 0 ;;
        esac
    done
    echo ""
}

# The channel the STA is on. A concurrent AP must share it on single-radio chips.
find_channel() {
    STA="$1"
    CH=$(iw dev "$STA" info 2>/dev/null | sed -n 's/.*channel \([0-9]*\).*/\1/p' | head -n 1)
    [ -n "$CH" ] && echo "$CH" && return 0
    # Some vendors only expose it through wpa_cli.
    CH=$(wpa_cli -i "$STA" status 2>/dev/null | sed -n 's/^freq=//p' | head -n 1)
    if [ -n "$CH" ]; then freq_to_channel "$CH"; return 0; fi
    echo ""
}

freq_to_channel() {
    F="$1"
    case "$F" in
        24*) echo $(( (F - 2407) / 5 )) ;;
        5*)  echo $(( (F - 5000) / 5 )) ;;
        *)   echo "" ;;
    esac
}

find_hostapd() {
    for candidate in /vendor/bin/hw/hostapd /system/bin/hostapd /vendor/bin/hostapd \
                     /system/xbin/hostapd /data/local/tmp/hostapd; do
        [ -x "$candidate" ] && echo "$candidate" && return 0
    done
    # Last resort: whatever is on PATH.
    command -v hostapd 2>/dev/null
}

find_iw() {
    for candidate in /system/bin/iw /vendor/bin/iw /data/local/tmp/iw; do
        [ -x "$candidate" ] && echo "$candidate" && return 0
    done
    command -v iw 2>/dev/null
}

hostapd_running() {
    [ -f "$PIDFILE" ] || return 1
    P=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$P" ] && kill -0 "$P" 2>/dev/null && return 0
    return 1
}

iface_exists() {
    [ -d "/sys/class/net/$1" ] && return 0
    # `ip link` is what the app's own interface scan uses. sysfs is not always
    # visible to the shell that runs this script, and the self-test has no sysfs.
    ip link show "$1" 2>/dev/null | grep -q "$1"
}

write_runtime() {
    IFACE="$1"; SSID="$2"; PASS="$3"; CHANNEL="$4"; MODE="$5"
    cat > "$RUNTIME_FILE" <<EOF
IFACE=$IFACE
SSID=$SSID
PASS=$PASS
CHANNEL=$CHANNEL
MODE=$MODE
CREATED=$CREATED
AP_IP=$AP_IP/$AP_PREFIX
HOSTAPD=$HOSTAPD_BIN
EOF
    # stdout is what the app parses; keep these lines stable.
    echo "IFACE=$IFACE"
    echo "SSID=$SSID"
    echo "PASS=$PASS"
    echo "CHANNEL=$CHANNEL"
    echo "MODE=$MODE"
    echo "CREATED=$CREATED"
}

# ------------------------------------------------------------------ hostapd conf

write_conf() {
    IFACE="$1"; SSID="$2"; PASS="$3"; CHANNEL="$4"
    HW_MODE=g
    if [ -n "$CHANNEL" ] && [ "$CHANNEL" -gt 14 ] 2>/dev/null; then HW_MODE=a; fi
    cat > "$CONF_FILE" <<EOF
# Generated by netshare_ap.sh - do not edit while the gateway is running.
interface=$IFACE
driver=nl80211
ctrl_interface=$STATE_DIR/netshare_hostapd.sock
ssid=$SSID
hw_mode=$HW_MODE
EOF
    if [ -n "$CHANNEL" ]; then
        echo "channel=$CHANNEL" >> "$CONF_FILE"
    else
        log "no channel could be determined - letting hostapd pick"
    fi
    cat >> "$CONF_FILE" <<EOF
beacon_int=100
dtim_period=2
max_num_sta=$MAX_STA
supported_rates=60 90 120 180 240 360 480 540
wpa=2
wpa_passphrase=$PASS
wpa_key_mgmt=WPA-PSK
rsn_pairwise=CCMP
wpa_pairwise=TKIP CCMP
ignore_broadcast_ssid=0
wmm_enabled=1
EOF
    log "wrote $CONF_FILE (interface=$IFACE ssid=$SSID hw_mode=$HW_MODE channel=${CHANNEL:-auto})"
}

# ------------------------------------------------------------------ commands

# Bring the interface up with an address, so DHCP has something to serve from
# even if hostapd's own driver init does not.
address_interface() {
    IFACE="$1"
    ip link set "$IFACE" up 2>/dev/null || log "could not set $IFACE up"
    CURRENT=$(ip -o -4 addr show dev "$IFACE" 2>/dev/null | awk '{print $4}' | head -n 1)
    if [ -z "$CURRENT" ]; then
        ip addr add "$AP_IP/$AP_PREFIX" dev "$IFACE" 2>/dev/null \
            && log "assigned $AP_IP/$AP_PREFIX to $IFACE" \
            || log "could not assign $AP_IP/$AP_PREFIX to $IFACE (setup_network.sh will retry)"
    else
        # Adopt whatever is already there - same rule the main script follows.
        AP_IP="${CURRENT%/*}"
        AP_PREFIX="${CURRENT#*/}"
        log "adopting the existing address $CURRENT on $IFACE"
    fi
    echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null
}

start() {
    SSID="$1"
    PASS="$2"
    WANT_CHANNEL="$3"

    [ -n "$SSID" ] || die "usage: start <ssid> <passphrase> [channel]"
    if [ "${#PASS}" -lt 8 ]; then
        die "passphrase must be at least 8 characters (got ${#PASS})"
    fi

    # Unconditionally: a crashed session's rnsap0 must not survive a start.
    cleanup_stale_interfaces

    if hostapd_running; then
        log "hostapd from a previous run is still alive (pid $(cat "$PIDFILE"))"
        . "$RUNTIME_FILE" 2>/dev/null
        if [ -n "$IFACE" ] && iface_exists "$IFACE"; then
            write_runtime "$IFACE" "$SSID" "$PASS" "$CHANNEL" "already-running"
            return 0
        fi
        stop
    fi

    IW=$(find_iw)
    HOSTAPD_BIN=$(find_hostapd)
    RADIO=$(find_wifi_radio)
    UPLINK=$(ip route show default 2>/dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p' | head -n 1)
    log "discovered: radio=${RADIO:-none} uplink=${UPLINK:-none} iw=${IW:-none} hostapd=${HOSTAPD_BIN:-none}"
    if is_mobile_iface "${UPLINK:-}"; then
        log "uplink $UPLINK is mobile data - it is not a WiFi radio, and an AP is not created on it"
    fi

    case "$HOSTAPD_BIN" in
        ""|HAL:*)
            die "no CLI hostapd on this phone (${HOSTAPD_BIN:-none}). /vendor/bin/hw/hostapd is the WiFi HAL, not a program that accepts a config file. The system hotspot (ap0) is the path here."
            ;;
    esac

    CHANNEL="$WANT_CHANNEL"
    if [ -z "$CHANNEL" ] && [ -n "$RADIO" ]; then
        CHANNEL=$(find_channel "$RADIO")
        log "channel on $RADIO: ${CHANNEL:-unknown}"
    fi
    if [ -z "$CHANNEL" ]; then
        # A mobile uplink has no WiFi channel. Channel 6 is 2.4 GHz, which is
        # what client phones can join. Leaving it empty made hostapd exit.
        CHANNEL=6
        log "no WiFi channel to share - using 2.4GHz channel 6"
    fi

    # An interface that already exists, even if it is DOWN. The Hot 8 keeps
    # rnsap0 / p2p0 / ap0 around after a previous run; requiring them to be UP
    # meant we ignored them and then died because `iw` is not installed.
    NEW_IF=""
    CREATED=0
    for candidate in "$IFACE_NAME" p2p0 p2p-wlan0-0 ap0 ap1 wlan1 softap0 swlan0; do
        [ "$candidate" = "$RADIO" ] && continue
        is_mobile_iface "$candidate" && continue
        if iface_exists "$candidate"; then
            STATE=$(ip link show "$candidate" 2>/dev/null)
            case "$STATE" in
                *UP*) log "reusing the existing AP interface $candidate (already up)" ;;
                *)    log "reusing the existing AP interface $candidate (it was down)" ;;
            esac
            NEW_IF="$candidate"
            CREATED=0
            break
        fi
    done

    if [ -z "$NEW_IF" ]; then
        if [ -z "$IW" ]; then
            die "no usable AP interface ($IFACE_NAME/p2p0/ap0/wlan1) and no 'iw' binary to create one"
        fi
        if [ -z "$RADIO" ]; then
            die "no WiFi radio (wlan0) to add an interface to, and no AP interface already exists. The uplink (${UPLINK:-none}) is not a WiFi radio."
        fi
        log "asking the driver for a second interface: iw dev $RADIO interface add $IFACE_NAME type __ap"
        if $IW dev "$RADIO" interface add "$IFACE_NAME" type __ap 2>>"$HOSTAPD_LOG"; then
            NEW_IF="$IFACE_NAME"; CREATED=1
            log "driver accepted type __ap"
        else
            log "type __ap refused; trying type managed"
            if $IW dev "$RADIO" interface add "$IFACE_NAME" type managed 2>>"$HOSTAPD_LOG"; then
                NEW_IF="$IFACE_NAME"; CREATED=1
                log "driver accepted type managed (hostapd will try to switch it to AP mode)"
            else
                die "the WiFi driver refused to create a second interface on $RADIO - this chip cannot run an AP and a WiFi connection at the same time. Use the Android hotspot instead."
            fi
        fi
    fi

    address_interface "$NEW_IF"
    write_conf "$NEW_IF" "$SSID" "$PASS" "$CHANNEL"

    : > "$HOSTAPD_LOG"
    log "starting $HOSTAPD_BIN -B $CONF_FILE"
    "$HOSTAPD_BIN" -B -P "$PIDFILE" "$CONF_FILE" >>"$HOSTAPD_LOG" 2>&1
    sleep 3

    if ! hostapd_running; then
        TAIL=$(tail -n 8 "$HOSTAPD_LOG" 2>/dev/null)
        die "hostapd exited immediately. Last lines: ${TAIL:-none}"
    fi

    # Confirm it really is beaconing, not just alive.
    if [ -n "$IW" ]; then
        log "iw dev $NEW_IF info: $($IW dev "$NEW_IF" info 2>/dev/null | tr '\n' ';')"
    fi
    log "AP up: $SSID on $NEW_IF (pid $(cat "$PIDFILE"))"
    write_runtime "$NEW_IF" "$SSID" "$PASS" "$CHANNEL" "root-hostapd"
}

# A crashed session (or the app being force-killed) leaves rnsap0 behind: `stop`
# only removes an interface when the CURRENT run's ownership file says it
# created it, so after a crash nothing ever deletes it. Only this app creates an
# interface with this name, so start() may remove it unconditionally - first
# killing whatever hostapd still thinks it owns.
cleanup_stale_interfaces() {
    iface_exists "$IFACE_NAME" || return 0
    # A HEALTHY previous run (pidfile + live process) is the "already running"
    # fast path, not a stale interface - leave it for start() to notice.
    if hostapd_running; then
        return 0
    fi
    log "stale $IFACE_NAME from a crashed session found - removing it unconditionally"
    # The previous run's hostapd (pidfile survived, process died with the
    # session, or the driver still holds the interface): kill it if alive.
    if [ -f "$PIDFILE" ]; then
        P=$(cat "$PIDFILE" 2>/dev/null)
        if [ -n "$P" ] && kill -0 "$P" 2>/dev/null; then
            kill "$P" 2>/dev/null && log "killed the previous run's hostapd (pid $P)"
        fi
        rm -f "$PIDFILE"
    fi
    for proc in /proc/[0-9]*; do
        pid=${proc#/proc/}
        cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
        case "$cmdline" in
            *hostapd*"$IFACE_NAME"*)
                kill "$pid" 2>/dev/null && log "killed hostapd bound to $IFACE_NAME (pid $pid)"
                ;;
        esac
    done
    sleep 1
    ip link set "$IFACE_NAME" down 2>/dev/null
    IW=$(find_iw)
    if [ -n "$IW" ] && $IW dev "$IFACE_NAME" del 2>/dev/null; then
        log "removed stale $IFACE_NAME (iw dev del)"
    elif ip link del "$IFACE_NAME" 2>/dev/null; then
        log "removed stale $IFACE_NAME (ip link del)"
    else
        log "could not remove stale $IFACE_NAME - the driver refused"
    fi
}

stop() {
    IFACE=""
    CREATED=""
    # shellcheck disable=SC1090
    [ -f "$RUNTIME_FILE" ] && . "$RUNTIME_FILE"
    if hostapd_running; then
        P=$(cat "$PIDFILE")
        log "stopping hostapd (pid $P)"
        kill "$P" 2>/dev/null
        sleep 1
        kill -9 "$P" 2>/dev/null
    fi
    rm -f "$PIDFILE"
    TARGET="${IFACE:-$IFACE_NAME}"
    if [ "$CREATED" = "1" ]; then
        IW=$(find_iw)
        if [ -n "$IW" ]; then
            log "removing the interface this script created ($TARGET)"
            $IW dev "$TARGET" del 2>/dev/null || log "the driver refused to remove $TARGET"
        else
            log "no iw binary - cannot remove $TARGET"
        fi
    else
        log "leaving $TARGET in place (this script did not create it)"
    fi
    rm -f "$RUNTIME_FILE"
    log "stopped"
}

status() {
    echo "-- netshare_ap runtime --"
    [ -f "$RUNTIME_FILE" ] && cat "$RUNTIME_FILE" || echo "(not started by this script)"
    if hostapd_running; then
        echo "hostapd            : running (pid $(cat "$PIDFILE"))"
    else
        echo "hostapd            : stopped"
    fi
    echo "-- interfaces --"
    ip -o link show 2>/dev/null
    echo "-- addresses --"
    ip -o -4 addr show 2>/dev/null
    echo "-- hostapd log (tail) --"
    tail -n 20 "$HOSTAPD_LOG" 2>/dev/null
    echo "-- discovery --"
    echo "iw                 : $(find_iw)"
    echo "hostapd            : $(find_hostapd)"
    echo "wifi radio         : $(find_wifi_radio)"
    echo "uplink             : $(ip route show default 2>/dev/null | sed -n 's/.* dev \\([^ ]*\\).*/\\1/p' | head -n 1)"
}

case "${1:-}" in
    start)  start "${2:-}" "${3:-}" "${4:-}" ;;
    stop)   stop ;;
    status) status ;;
    *) echo "usage: $0 {start <ssid> <passphrase> [channel]|stop|status}" ;;
esac
