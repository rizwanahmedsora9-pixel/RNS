#!/bin/sh
# Diagnostic: list every process the script's dnsmasq detection would accept,
# under the OLD full-cmdline pattern (F-20) and the NEW argv/exe-based one, and
# exclude the instance our own pidfile names.
#
# setup_network.sh scans /proc/[0-9]* - every process in the PID namespace, not
# just the ones in this network namespace. On a phone there is only ever one
# relevant dnsmasq; in a test harness anything whose command line merely
# mentions dnsmasq used to be mistaken for Android's tether server, and
# keepalive() would then kill our DHCP server and "step aside".
STATE="${RNS_E2E_STATE:-/tmp/rns-e2e/state}"
OUR=$(cat "$STATE/dnsmasq_hotspot.pid" 2>/dev/null)
echo "  our dnsmasq pid: ${OUR:-none}"
for proc in /proc/[0-9]*; do
    pid=${proc#/proc/}
    [ -n "$OUR" ] && [ "$pid" = "$OUR" ] && continue
    cmdline=$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null) || continue
    [ -n "$cmdline" ] || continue
    old=0; new=0
    case "$cmdline" in
        */dnsmasq|*/dnsmasq\ *|*dnsmasq\ --*) old=1 ;;
    esac
    case "$cmdline" in
        *dnsmasq*)
            _exe=$(readlink "$proc/exe" 2>/dev/null)
            case "${_exe##*/}" in dnsmasq|dnsmasq-*) new=1 ;; esac
            if [ "$new" = 0 ]; then
                tr '\0' '\n' < "$proc/cmdline" 2>/dev/null | head -n 2 | awk '
                    { b = $0; sub(/.*\//, "", b); if (b == "dnsmasq" || index(b, "dnsmasq-") == 1) f = 1 }
                    END { exit f ? 0 : 1 }' && new=1
            fi
            ;;
    esac
    if [ "$old" = 1 ] || [ "$new" = 1 ]; then
        echo "  pid=$pid old-pattern(F-20)=$old new-pattern=$new: $(printf '%s' "$cmdline" | cut -c1-100)"
    fi
done
exit 0
