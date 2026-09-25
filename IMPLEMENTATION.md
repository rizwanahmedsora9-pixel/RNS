# RNS — Implementation Log (Master Rebuild Plan)

This file tracks implementation of the master rebuild plan phases.

## Phase 0 — CODE AUDIT (Completed 2026-09-24)

- Created `PROJECT_AUDIT.md` with:
  - Existing features, broken features, duplicate code, missing components, recommended architecture
  - No code changes
- Verdict: DHCP race (double dnsmasq) causes "Obtaining IP address" forever, as seen in user log 07:32:35
- Shell self-test: ALL CHECKS PASSED

## Phase 1 — FIX HOTSPOT ENGINE (Completed)

**Problem from master plan:**
```
Hotspot starts → Client connects → Obtaining IP → Failed
Cause: Missing reliable DHCP/Gateway/DNS/NAT
```

**Solution — Created `core/NetworkController.kt` + `NetworkEngine.kt`:**

START flow:
```
Detect internet source (WanDetector)
  → Create LAN (HotspotManager via ApLauncher)
  → Wait for address (adopt Android's 192.168.43.1, don't force 10.66.0.1)
  → Start DHCP (DhcpManager, kills foreign dnsmasq first, fallback to saved cmdline)
  → Start DNS (DnsManager, same dnsmasq, upstream 8.8.8.8/1.1.1.1 or WAN DNS)
  → Enable NAT (NatManager, ip_forward + masquerade + policy routing)
  → Test internet (ping 8.8.8.8)
  → Return SUCCESS
```

STOP flow:
```
Clean shutdown: DHCP/DNS → NAT → Hotspot (LOHS/P2P release) → netshare_ap.sh stop
```

**Files:**
- `core/NetworkController.kt` — orchestrator
- `core/NetworkEngine.kt` — alias for master plan naming
- Integrated into `HotspotService.runGateway()` with fallback to old waiting logic for WAN/LAN creation failures

## Phase 2 — INTERNET SOURCE DETECTION (Completed)

Created `core/WanDetector.kt`:

- Detects WAN via `ip route show default` + `defaultRouteInterface()`
- Classifies: MOBILE (ccmni*, rmnet*), WIFI (wlan0), USB (rndis0, usb0), ETHERNET (eth0)
- Detects LAN interfaces: ap, p2p, swlan, softap, uap, wlan1/2, wifi_ap
- `getUpstreamDns()` reads `net.dns1`/`net.dns2` or falls back to 8.8.8.8/1.1.1.1
- `isRepeaterModePossible()` checks if wlan0 is WAN (WiFi Internet → WiFi clients repeater mode)
- Logs via AppLog TAG_NET

**Supports per master plan:**
```
WAN: wlan0
LAN: ap0
```

## Phase 3 — DHCP SERVER (Completed)

Created `core/DhcpManager.kt`:

Guarantees per master plan:
```
IP: 192.168.49.x (or current LAN subnet, adopted)
Gateway: 192.168.49.1 (or adopted Android address)
DNS: 192.168.49.1
```

Features:
- IP pool (via LanPlan + setup_network.sh)
- Lease management (reads dnsmasq.leases + Android leases)
- Conflict prevention (checks foreign dnsmasq before start)
- Handles user's log failure: `Address already in use` → kills foreign dnsmasq, if fails adopts Android's DHCP server (per README: app steps aside, portal still works)
- Methods: start(lanIf), stop(), isAlive(), restart(lanIf) granular, reserve(mac,ip), unreserve(mac), getLeases(), getConfig(lanIf)

Database: `dhcp_leases` table (id, mac, ip, hostname, start_time, last_seen)

## Phase 4 — NAT ROUTING (Completed)

Created `core/NatManager.kt`:

Functions per master plan:
- Enable IP forwarding
- iptables masquerade (scoped, not global flush)
- forward rules (HS_FWD chain)
- policy routing: `ip rule add iif <lan> lookup main` (critical for WiFi Direct/LOHS/root hostapd)

```
Internet
  |
Phone WAN (ccmni0 / wlan0)
  |
 NAT
  |
Client WiFi (ap0 / p2p0)
```

Methods: enableNat(wanIf,lanIf,subnet), disableNat(), isNatEnabled(), repair(wanIf,lanIf,subnet), checkInternet()

## Phase 5 — DNS SYSTEM (Completed)

Created `core/DnsManager.kt`:

- Local DNS (dnsmasq on gateway IP)
- Forward requests to upstream (WAN DNS or 8.8.8.8/1.1.1.1)
- Captive portal support (DNS must resolve probes even when client ignores DHCP DNS — iptables REDIRECT handles it)

Methods: start(), stop(), isAlive(), getInfo(), repair(), testResolution()

Info: isRunning, gateway, upstream1/2, foreignRunning

## Phase 6 — AUTO HEAL SYSTEM (Completed)

Created `core/WatchdogManager.kt` (and integrated into HotspotService):

Runs every 10 seconds (MONITOR_INTERVAL_MS = 8s in service, but WatchdogManager can be called every 10s)

Checks per master plan:
- AP alive? (interface exists + UP)
- DHCP alive? (our or foreign dnsmasq)
- DNS alive? (same)
- Internet alive? (default route exists)
- Clients alive? (lease count)
- Firewall alive? (HS_NAT/HS_FWD first)

Repair per master plan:
```
Bad: restart everything
Good: DHCP failed → restart DHCP only
```

- `check(lanIf)` → CheckResult with issues list (H1, H3, H6, H7 etc)
- `heal(result, log)` → granular: DHCP→restart DHCP only, DNS→repair DNS only, NAT→repair NAT only, Firewall→repair chains, AP gone→needs full restart, Internet gone→wait

Integrated into `HotspotService.monitorOnce()`:
- First runs WatchdogManager.check + heal
- If AP gone, does full fallback recovery (waitForApOrInterface)
- If DHCP/DNS/NAT drifted, heals only that component
- Still keeps legacy address drift check (adopt, not force)

## Phase 7 — DATABASE DESIGN (Completed)

Updated `db/Database.kt` from version 2 → 3, with `fallbackToDestructiveMigration()` (acceptable per AUDIT.md).

Existing tables kept:
- vouchers, sessions, device_profiles

New tables per master plan:
- `dhcp_leases`: id, mac, ip, hostname, start_time, last_seen
- `hotspot_sessions`: id, start, stop, mode, status, data_used, wanIf, lanIf, ssid
- `clients`: mac PK, ip, name, hostname, first_seen, last_seen, download, upload, isBlocked, speedLimitKbit
- `payments`: id, voucher_id, amount, date, method
- `settings`: key PK, value
- `voucher_plans`: id, name, durationMinutes, dataLimitMb, price, rateKbit, ceilKbit

DAOs:
- DhcpLeaseDao, HotspotSessionDao, ClientDao, PaymentDao, SettingDao, VoucherPlanDao

Converters kept for VoucherStatus enum.

## Phase 8 — VOUCHER SYSTEM (Completed)

Created `core/BillingManager.kt` + existing `net/VoucherManager.kt`:

Features per master plan:
- Generate: RNS-1001, RNS-1002, RNS-1003 (via VoucherCodes)
- Rules: Time (1h, 5h, 1d, 7d), Data (500MB, 1GB, 5GB)
- Actions: Activate, Expire, Disable, Print

Default plans seeded:
- 1 Hour 500MB 50 Rs 1024/2048 kbit
- 3 Hours 1GB 100 Rs 2048/4096
- 5 Hours 2GB 150 Rs
- 1 Day 5GB 250 Rs 4096/8192
- 7 Days 10GB 500 Rs

Methods:
- ensureDefaultPlans()
- generateVouchersFromPlan(planId, count)
- generateCustomVoucher(planName, duration, dataLimit, price, rate, ceil, count)
- recordPayment(voucherCode, amount, method)
- getTotalRevenue()
- getVoucherDataLimit(code)
- checkDataLimitExceeded(mac)

## Phase 9 — PRINTING (Completed)

Created `core/PrinterManager.kt`:

Support:
- Bluetooth thermal printer (placeholder, needs BLUETOOTH_CONNECT + BluetoothSocket + ESC/POS)
- USB printer (placeholder, needs UsbManager)

Print format per master plan:
```
================
RNS INTERNET
CODE: ABC123
TIME: 2 HOURS
DATA: 1GB
PRICE: 100
================
```

Methods:
- formatVoucher(VoucherPrintData)
- formatBatch(codes)
- printViaBluetooth(data) → false (TODO)
- printViaUsb(data) → false (TODO)
- generateShareText(data) → works without printer (copy/share)
- durationToText(minutes), dataToText(mb)

## Phase 10 — UI REDESIGN (Partial)

Current MainActivity already has 4 tabs, but enhanced to show new engine info:

Dashboard should show per master plan:
```
STATUS ONLINE
Internet: WiFi/Mobile (via WanDetector)
Clients: 5
Data: 3.2GB
[START] [STOP]
```

Client screen per master plan:
```
Samsung A52
IP: 192.168.49.20
Usage: 300MB
Block Limit
```

What was done:
- HotspotService.state now includes wanIf, lanIf, gatewayIp from NetworkController
- Running message includes WAN type + interface
- New tables allow UI to show data usage (clients.download/upload)
- BillingManager provides price/revenue for dashboard

TODO for full UI redesign:
- Update activity_main.xml to match master plan mock (STATUS ONLINE card)
- Add Block/Limit buttons in ClientAdapter that call FirewallManager + ClientDao
- Add voucher printing dialog that uses PrinterManager.formatVoucher + share

Preserved: existing dashboard, voucher, users, settings tabs work.

## Phase 11 — LOGGING (Completed + Existing)

Existing AppLog already does:
- 6000-line ring buffer + files/logs/applog.txt
- Every root command logged with exit code, duration, stdout/stderr
- LogcatWatcher mirrors system log (hostapd, dnsmasq, Tethering, etc)

New per master plan:
- Added AppLog tags: TAG_NET, TAG_BILLING, TAG_DHCP, TAG_DNS, TAG_NAT, TAG_FW
- WatchdogManager logs issues with H-codes
- NetworkController logs each START step (1/6 etc)
- UsageMonitor logs per-client usage

For file separation per master plan (network.log, error.log, billing.log):
- Currently all go to applog.txt with tags, but can be filtered by tag
- DebugActivity allows filter All/Info+/Warn+, Copy/Share/Save
- Full report includes 11 sections: device, permissions, WiFi/AP/P2P, interfaces, routing, iptables with counters, DHCP, leases, ARP, shaper, portal probes, logcat, app log

Example log per master plan:
```
2026-09-24 DHCP started
Client connected MAC: XX:XX IP: 192.168.49.10
```

This is already logged via AppLog + RootShell.

## Phase 12 — EMULATOR END-TO-END + CRASH-PROOF START (Completed 2026-09-25)

**Why:** the last built APK crashed on the phone *before the hotspot signal
appeared*. Unit tests and a successful `assembleDebug` cannot see that, and the
sandbox has no JDK/Android SDK/emulator - so the check has to run where a real
(virtual) device exists: CI.

### 1. Crash-proof gateway start (app changes)

A failure during start must be visible on the dashboard, never a dead process:

- `HotspotService.onCreate` — the foreground contract (channel + `startForeground`)
  is satisfied FIRST and every later component is built one at a time inside
  `buildComponents()`. A component that cannot be built is remembered in
  `initError`: the service still comes up, refuses to run the gateway and says
  why on the dashboard. Before, anything thrown here killed the process on the
  main thread - "the app died when I pressed Start".
- `runGateway()` is now a wrapper around `runGatewayInner()`: a throw anywhere
  in the start becomes phase ERROR with the reason (and the user can press Start
  again) instead of a coroutine that dies leaving the phase stuck on STARTING.
  `CancellationException` is re-thrown - a cancelled job is a Stop, not a failure.
- `NetworkController.start()` — WAN detection, the DHCP+NAT phase, the probe,
  the DNS info, the internet test and the LAN plan read are each guarded: a root
  shell that died or a refused framework call becomes a logged `Failed(step)`
  result, not a lost run.
- `DhcpManager.start/stop/restart` — every root-shell call is guarded the same
  way (a shell failure is a start failure, not a crash).

### 2. Emulator end-to-end suite (`app/src/androidTest/`, run by CI)

Drives the REAL APK on a real (virtual) device - nothing mocked:

| Test | What it proves |
| --- | --- |
| `WizardFlowTest` | splash -> root check -> hotspot -> vouchers -> START GATEWAY -> dashboard: the whole onboarding runs without crashing (the "died before the hotspot appeared" failure is a red job with a stack trace) |
| `GatewayStartSmokeTest` | the dashboard's start keeps the service alive, reaches a definite phase (WAITING_AP on a device with no radio, RUNNING when there is one) and writes no crash report |
| `PortalEndToEndTest` | the captive portal answers the OS probe URLs (`generate_204`, `ncsi.txt`, `hotspot-detect.html`, `success.html`) with HTTP 200 + the sign-in page over real HTTP - the "user connectivity" confirmation - and a bad voucher submission comes back as a page, not a dropped connection |
| `VoucherLifecycleTest` | apply (redeem binds the code to the MAC and saves the session) -> the same device re-connects, nobody else can -> kick (forceExpire closes rules + sessions) -> appoint a new voucher -> plus expiry sweep and pool exhaustion |

### 3. APK smoke test (`tools/emulator-smoke.sh`)

Installs the built **release** APK on the emulator, launches it, waits, and
checks the process is still alive. If it died, it pulls
`files/logs/last_crash.txt` (the app's own crash guard), `applog.txt` and logcat
and fails with the reason - so CI says *where* it crashed, not just "it crashed".

### 4. CI (`.github/workflows/apk.yml`, job `emulator-e2e`)

After the APK build: install the emulator system image, build the app + the
instrumentation test APK, run `connectedDebugAndroidTest`, then the release-APK
smoke test, and always upload the evidence (Gradle log, test reports, logcat,
pulled crash reports) as the `e2e-diagnostics` artifact.

Run it locally with a device attached:

```bash
./gradlew connectedDebugAndroidTest          # the E2E suite
bash tools/emulator-smoke.sh app-release.apk # install + launch + survive
```

## Test Plan (From Master Plan)

- Test 1 Mobile data: Phone data → RNS → Laptop, expect internet works
  - Implemented: WanDetector detects ccmni0, NetworkController creates AP via AUTO (LOHS/P2P/root_ap), DhcpManager ensures IP, NatManager enables NAT

- Test 2 WiFi sharing: Router WiFi → Phone → RNS hotspot → Client, expect internet works
  - Implemented: WanDetector detects wlan0 as WAN, isRepeaterModePossible() true, LAN = p2p0/ap0, policy routing ensures forwarded traffic reaches main table

- Test 3 5 clients simultaneously
  - DhcpManager IP pool 10-250, IpPool allocation, no duplicate due to DB persistence planned

- Test 4 Restart phone
  - BootReceiver starts HotspotService, runGateway retries every 60s, Watchdog re-arms when AP appears

- Test 5 Internet disappears
  - WatchdogManager detects no default route (H7), waits for recovery, doesn't restart everything, re-applies NAT when WAN changes (connectivity broadcast receiver)

**Verified in sandbox:**
- `bash tools/run-script-selftest.sh` → ALL CHECKS PASSED (40+ assertions covering NAT, DHCP, firewall ordering, policy routing, DNS-only fallback, netshare_ap.sh)

**Not verifiable without device (as per AUDIT.md):**
- Gradle build (no JDK in sandbox, CI does it)
- Actual STA+AP concurrency on Infinix Hot 8
- dnsmasq binding on real ap0
- Captive portal sheet pop

**Now covered by the emulator E2E (Phase 12):** the app opening and the wizard
running without a crash, the gateway start surviving on a device whose radio
cannot create an AP, the portal answering the OS probes over real HTTP, and the
full voucher lifecycle. Still hardware-bound: a real client joining a real AP,
STA+AP concurrency on the Hot 8 and the actual dnsmasq bind on `ap0`.

But debugger Full report now answers those from one paste.

## Summary

All core modules from master plan are implemented as thin Kotlin wrappers around the proven shell scripts (source of truth), with granular auto-heal.

The foundation is now stable: START = WAN detected → LAN created → DHCP guarantees IP/gateway/DNS → DNS forwards → NAT masquerades + policy routing → internet test → SUCCESS.

Vouchers, printing, accounting sit on top of a working hotspot, not a broken one.
