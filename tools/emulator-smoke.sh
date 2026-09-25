#!/usr/bin/env bash
#
# emulator-smoke.sh - does the built APK actually RUN on a device?
#
# This is the test for the failure the app kept having: "the APK crashed before
# the hotspot ever appeared". A build that installs but dies on launch passes
# every unit test and still fails the user, so the APK is installed on a real
# (virtual) device here, launched, and watched.
#
#   1. install the APK as a normal update (every build is signed with the one
#      committed key, so -r always works),
#   2. launch it on the splash - the screen that shows the previous run's
#      crash if there was one,
#   3. wait, then check the process is still alive,
#   4. if it died (or the app's own crash guard wrote a report), pull
#      files/logs/last_crash.txt + applog.txt + logcat and fail with the
#      reason - so CI says WHERE it crashed, not just "it crashed".
#
# Usage:
#   tools/emulator-smoke.sh [path-to-apk]
#
# Environment:
#   SMOKE_WAIT_SECONDS   how long to watch the app after launch (default 25)
#   ANDROID_SERIAL       device to use (set by the emulator runner)
#
# Exit codes: 0 = the app launched and stayed up, 1 = it crashed / never ran.

set -Eeuo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="com.hotspot.billing"
LAUNCHER="${PKG}/.SplashActivity"
WAIT_SECONDS="${SMOKE_WAIT_SECONDS:-25}"
OUT_DIR="${SMOKE_OUT_DIR:-emulator-smoke}"

log()  { printf '[smoke] %s\n' "$*"; }
warn() { printf '[smoke] WARNING: %s\n' "$*" >&2; }
# The summary file is what CI reports on the pull request, so it is written on
# the way out too - never a bare "failed".
die() {
    printf '[smoke] FAILED: %s\n' "$*" >&2
    {
        echo "RESULT=FAIL"
        echo "REASON=$*"
        echo "PID=${PID:-none}"
        echo "ROOT=${ROOT_AVAILABLE:-unknown}"
        echo "APK=$APK"
    } >"$OUT_DIR/summary.txt" 2>/dev/null || true
    exit 1
}

PID=""
ROOT_AVAILABLE="unknown"

mkdir -p "$OUT_DIR"

[ -f "$APK" ] || die "APK not found: $APK (run ./gradlew assembleDebug first)"

# ------------------------------------------------------------------ device

adb wait-for-device
BOOT=""
for _ in $(seq 1 60); do
    BOOT="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [ "$BOOT" = "1" ] && break
    sleep 2
done
[ "$BOOT" = "1" ] || die "the device never finished booting"
log "device ready: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r') " \
    "Android $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r') " \
    "(API $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'))"

# The app is root-only by design. The default emulator images are userdebug and
# provide `su`; report it either way so a wrong image is obvious.
if adb shell su 0 id >/dev/null 2>&1; then
    ROOT_AVAILABLE=yes
    log "root shell available (the app will pass its root check)"
else
    ROOT_AVAILABLE=no
    warn "no root shell on this device - the app will stop on the root check screen by design"
fi

# ------------------------------------------------------------------ install

log "installing $(basename "$APK")"
adb install -r "$APK" >/dev/null 2>&1 || die "adb install failed for $APK"
log "installed"

# ------------------------------------------------------------------ launch

log "launching $LAUNCHER"
adb shell am start -n "$LAUNCHER" >/dev/null 2>&1 || die "could not start the launcher activity"

PID=""
for _ in $(seq 1 "$WAIT_SECONDS"); do
    PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
    [ -n "$PID" ] && break
    sleep 1
done

# Pull whatever the app's recorder wrote. run-as works for the debug build; the
# root shell is the fallback (and works for the release build too). A pull only
# counts when the content really looks like that file's content.
pull_app_file() {
    local remote="$1" local_name="$2" marker="$3"
    rm -f "$OUT_DIR/$local_name"
    if adb shell run-as "$PKG" cat "files/$remote" >"$OUT_DIR/$local_name" 2>/dev/null \
        && grep -q "$marker" "$OUT_DIR/$local_name"; then
        return 0
    fi
    if adb shell su -c "cat /data/data/$PKG/files/$remote" >"$OUT_DIR/$local_name" 2>/dev/null \
        && grep -q "$marker" "$OUT_DIR/$local_name"; then
        return 0
    fi
    rm -f "$OUT_DIR/$local_name"
    return 1
}

# The crash guard's own header line - only that makes the file a crash report.
has_crash_report() {
    [ -s "$OUT_DIR/last_crash.txt" ] && grep -q "CRASH" "$OUT_DIR/last_crash.txt"
}

if [ -z "$PID" ]; then
    log "the app process is GONE - collecting the reason"
    adb logcat -d >"$OUT_DIR/logcat.txt" 2>/dev/null || true
    pull_app_file "logs/last_crash.txt" "last_crash.txt" "CRASH" || true
    pull_app_file "logs/applog.txt" "applog.txt" "AppLog started" || true
    echo
    if has_crash_report; then
        echo "=== last_crash.txt (the app's own crash guard) ============================"
        sed -n '1,40p' "$OUT_DIR/last_crash.txt"
        echo "==========================================================================="
        die "the APK crashed on launch - the reason is above (saved in $OUT_DIR/)"
    fi
    echo "=== logcat: lines from $PKG ==============================================="
    grep -E "$PKG|AndroidRuntime|FATAL" "$OUT_DIR/logcat.txt" 2>/dev/null | tail -40 || true
    echo "==========================================================================="
    die "the app is not running and wrote no crash report - see $OUT_DIR/logcat.txt"
fi

log "app is running (pid $PID) after launch"

# The process is alive: make sure it did not ALSO write a crash report (a
# caught crash that the user would still see on the next launch).
pull_app_file "logs/last_crash.txt" "last_crash.txt" "CRASH" || true
if has_crash_report; then
    echo "=== last_crash.txt ========================================================="
    sed -n '1,40p' "$OUT_DIR/last_crash.txt"
    echo "==========================================================================="
    die "the app survived, but its crash guard recorded a crash - the reason is above"
fi

# And it got as far as recording that it started.
pull_app_file "logs/applog.txt" "applog.txt" "AppLog started" || true
if [ -s "$OUT_DIR/applog.txt" ]; then
    if grep -q "process started" "$OUT_DIR/applog.txt"; then
        log "the app's own log confirms it started"
    else
        warn "the app log exists but has no 'process started' line yet"
    fi
else
    warn "could not read the app log (run-as needs the debug build; the root shell is the fallback)"
fi

{
    echo "RESULT=PASS"
    echo "PID=$PID"
    echo "ROOT=$ROOT_AVAILABLE"
    echo "APK=$APK"
} >"$OUT_DIR/summary.txt"
log "SMOKE TEST PASSED - the APK installs, launches and stays running"
exit 0
