# RNS — PROJECT_AUDIT.md (Phase 0)

> Generated 2026-09-24 — audit only, no code changes yet. This is the Phase 0 deliverable required by the master rebuild plan.

## 1. Current Project Structure

```
RNS/
├── app/
│   ├── build.gradle (shared keystore hotspot-billing.p12, Room, libsu, NanoHTTPD, WorkManager)
│   └── src/main/
│       ├── AndroidManifest.xml (INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED, etc)
│       ├── java/com/hotspot/billing/
│       │   ├── MainActivity.kt — 4-tab UI (Dashboard, Vouchers, Users, Settings) + poller every 1.5s
│       │   ├── HotspotService.kt — ForegroundService, lifecycle owner of gateway, watchdog, portal, stats
│       │   ├── BootReceiver.kt — auto-start after reboot
│       │   ├── RnsApp.kt — Application, starts AppLog, CrashGuard, LogcatWatcher before anything else
│       │   ├── db/ — Room DB: Voucher, UserSession, DeviceProfile; DAO for each; Converters for enum
│       │   ├── debug/ — AppLog (6000-line ring + file), CrashGuard, LogcatWatcher (system log mirror), GatewayHealth (H1-H10), Diagnostics (11-section full report), ReportBuilder, HttpProbe, DebugExport, LogFormat
│       │   ├── net/ — ApMode (enum AUTO/SYSTEM/NETSHARE/LOCAL_ONLY/ROOT_AP/MANUAL), ApLauncher (tries adopt→cmd wifi start-softap→LOHS→WiFi Direct group→root hostapd→wait), WifiShareAp (LOHS + P2P group owner creation), SoftApController (legacy cmd wifi start-softap attempts + apInterface detection), LanPlan (parses hotspot.runtime), IpPool (in-memory only), LeaseParser (dhcp.leases + /proc/net/arp), ArpResolver, VoucherCodes, VoucherManager (generate, redeem, sweep, tc classId allocation)
│       │   ├── portal/ — CaptivePortalServer (NanoHTTPD on :8080, returns 200 for probes, handles /redeem), PortalPages (login/success/error html)
│       │   ├── ui/ — RecyclerView adapters: VoucherAdapter, ClientAdapter, ProfileAdapter, SessionAdapter, UiFmt
│       │   └── util/ — RootShell (thin wrapper around libsu, logs every command with exit code/stdout/stderr/duration)
│       └── res/ — layouts for activity_main, activity_debug, items; mipmap icons; strings
│       └── assets/ — GENERATED from scripts/ by :app:syncShellScripts (git-ignored)
├── scripts/ — SOURCE OF TRUTH for root layer
│   ├── setup_network.sh — NAT, captive-portal redirect (port 80→:8080, 443→REJECT), DNS redirect, DHCP (dnsmasq with --dhcp-hostsdir for reservations), policy routing (ip rule iif <lan> lookup main), MAC auth (HS_FWD chain), reserve/unreserve, keepalive, foreign-dhcp detection, diag, procs
│   ├── bandwidth_control.sh — tc HTB for download + ingress police for upload, state file /data/local/tmp/hotspot_tc.state, idempotent rebuild
│   └── netshare_ap.sh — root fallback AP: iw dev ... interface add + hostapd, writes netshare.runtime with CREATED flag
├── tools/run-script-selftest.sh — runs scripts against stub binaries, 40+ assertions
├── keystore/hotspot-billing.p12 — shared signing key
├── hotspot-billing.zip — frozen original drop
├── Hotspot .txt — terminal capture from Infinix Hot 8 (the design base)
├── AUDIT.md — full review of that drop: 6 build blockers fixed, 5 silent-fail bugs, 1 auth bypass fixed
└── README.md — deployment, modes, debugger docs
```

### Permissions (AndroidManifest)
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (required for LOHS/P2P on Android 9/10)
- `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`
- `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`
- `WRITE_EXTERNAL_STORAGE` legacy, `BLUETOOTH` not yet present (needed for thermal printer)

### Services
- `HotspotService` — foreground, notification with Debugger action, holds ApLauncher, portal, watchdog loop (monitorOnce every 8s), deep health check every N ticks, voucher sweep, stats refresh
- `BootReceiver` — starts service after boot
- No separate `WatchdogService` or `UsageMonitorService` yet (watchdog is a method inside HotspotService)

### Database (Room v2)
- `vouchers`: code PK, planName, durationMinutes, rateKbit, ceilKbit, status (UNUSED/ACTIVE/EXPIRED), boundMac, assignedIp, activatedAt, expiresAt, classId, createdAt
- `sessions`: id auto, mac, ip, voucherCode, connectedAt, disconnectedAt, bytesUp/Down (never populated — O4), deviceLabel
- `device_profiles`: mac PK, label, phone, note, hostname, firstSeen, lastSeen
- Missing per master plan: `dhcp_leases`, `hotspot_sessions`, `payments`, `settings` tables; also `clients` is currently split into `sessions` + `device_profiles` + live lease parsing

### UI Flow
- App start → request notification permission + wifi-share permissions → startForegroundService(ACTION_START) → bind → poller
- Dashboard: root status, AP kind/mode/SSID/pass, WAN/LAN, portal, clients count, vouchers count, hint, findings H1-H10, log tail, buttons Start/Stop/Open tether/Debugger/Copy log
- Vouchers tab: generate batch (count, plan, minutes, rate, ceil), filter UNUSED/ACTIVE/EXPIRED, copy/share code, expire/delete
- Users tab: 3 lists — online clients (lease+ARP merged), saved profiles (name/phone/note), session history
- Settings: SSID/pass, WAN/LAN pin, Detect button, AP mode RadioGroup (AUTO/SYSTEM/NETSHARE/LOCAL_ONLY/ROOT_AP/MANUAL), Permissions/Debugger shortcuts, env file view
- DebugActivity: level filters All/Info+/Warn+, Live log, Copy all/Share/Save .txt, Full report, Check now, Clear

### Root Commands (via RootShell.run)
- Every command logged: `$ cmd -> exit code, ms, out, err`
- `sh /data/local/tmp/setup_network.sh start|stop|status|keepalive|authorize|deauthorize|reserve|unreserve|route|foreign-dhcp|procs|diag`
- `sh /data/local/tmp/bandwidth_control.sh init|stop|add|remove|remove-class`
- `cmd wifi start-softap ...`, `svc wifi enable-softap`, `ndc softap ...`, `am start -n com.android.settings/.TetherSettings`
- `ip -o link show`, `ip route show default`, `ip -o -4 addr show dev <lan>`, `ip rule show`, `iptables -t nat|filter -S`, `cat /proc/net/arp`, `cat .../dnsmasq.leases`, `getprop wifi.tethering.interface`

### Android Versions
- minSdk 26, targetSdk 34, compileSdk 34, JDK 17
- Target device from logs: Infinix HOT 8, Android 9/10, MediaTek ccmni* uplink, no `iw`, no `hostapd` binary, `wlan0` never appears in `ip link` when WiFi off
- Modes that need Location ON on Android 9/10: LOCAL_ONLY, NETSHARE (WiFi Direct) — Android refuses with generic failure otherwise
- On Android 12+ `cmd wifi start-softap` with SSID/pass works; on 9/10 it doesn't exist, so manual toggle or LOHS/P2P is required

---

## 2. Existing Features (Working)

- Voucher generation (RNS-XXXX style via VoucherCodes), batch create, status filter, copy/share, expire/delete
- Voucher redemption via captive portal POST /redeem, single-device MAC binding, static IP reservation via dhcp-hostsdir + SIGHUP dnsmasq, MAC-based firewall ACCEPT above DROP, portal bypass RETURN, HTB download + ingress police upload shaping
- Captive portal: NanoHTTPD on 0.0.0.0:8080, returns HTTP 200 for OS probes (generate_204, hotspot-detect, ncsi, etc), 443 REJECT to force HTTP probe fallback, DNS from clients that ignore DHCP DNS redirected to phone, real DNS answers (not private IP spoof)
- Foreground service survives swipe, auto-restart after reboot, expiry sweep via WorkManager (sweepExpired), notification with Debugger action
- AP bring-up: AUTO tries adopt→softap→LOHS→WiFi Direct→root hostapd→wait manual; every refusal decoded (ERROR_INCOMPATIBLE_MODE etc) and logged; retries every 60s; waitForAddress avoids forcing 10.66.0.1 over Android's 192.168.43.1 (previous cause of "Obtaining IP")
- Policy routing: ip rule add iif <lan> lookup main (pref 15500/15501) so WiFi Direct / LOHS / root hostapd interfaces actually get internet (Android's trailing unreachable rule otherwise drops forwarded packets)
- Watchdog: monitorOnce checks LAN interface existence, address drift (adopts, not forces), keepalive (repairs forwarding, iptables jumps, policy routing, DHCP), refresh stats, deep health H1-H10 with fix text
- Debugger: 6000-line ring + file, every root command with timing, logcat mirror (hostapd, wpa_supplicant, Tethering, IpServer, netd, dnsmasq, etc) restarted up to 5x, crash guard with 120 preceding records, Full report 11 sections, Copy/Share/Save, rate-limited with drop count recorded
- Device profiles auto-recorded per MAC from DHCP hostname, manual label/phone/note
- Self-test: tools/run-script-selftest.sh runs real scripts against stub kernel commands, asserts default-deny ordering, portal order, idempotent auth, scoped teardown, DHCP reservations, tc tree
- Shared signing key: every build signed with same p12, updates in-place

## 3. Broken Features (Root Cause of User's Log)

**Symptom from pasted log (2026-09-24 07:32:xx, Infinix HOT 8):**
```
Tethering ap0
dnsmasq: failed to create listening socket: Address already in use
dnsmasq: FAILED to start up
tether dns set ... failed with '400 ... Remote I/O error'
sendTetherStateChangedBroadcast error=[ap0]
IP mode config error - need to clean up, stop softap
hostapd: Remove interface 'ap0'
hotspot.runtime missing (cat exit 1)
```
Loops 6 times, never completes.

**Root causes:**
1. **DHCP race / double dnsmasq:** `setup_network.sh` starts its own dnsmasq on ap0, then Android's Tethering service tries to start *its* dnsmasq on same interface → bind fails → IpServer aborts → hotspot torn down. `kill_foreign_dnsmasq` exists and saves foreign cmdline for respawn fallback, but timing is racy and Android restarts its dnsmasq after we kill.
2. **SYSTEM mode fragile on MTK 9/10:** Android's IpServer expects to own ap0 addressing/DHCP; our iptables + dnsmasq fight it. Result is `Obtaining IP address` forever on clients, as seen in master plan description.
3. **No dedicated NetworkEngine:** DHCP/NAT/DNS lifecycle split across HotspotService, ApLauncher, RootShell, setup_network.sh — hard to reason, hard to auto-heal granularly. Master plan asks for NetworkController/HotspotManager/DhcpManager/NatManager/DnsManager/FirewallManager/WanDetector/DeviceManager.
4. **IpPool in-memory only:** `IpPool.inUse` resets on process restart → duplicate IP allocation (F18 in AUDIT.md). Needs DB persistence.
5. **WAN detection hardcoded fallback:** `WAN_IF=auto` works, but `wlan0` never appears in logs when WiFi off; still some code assumes wlan0. Need proper WanDetector that checks default route, ccmni*, rmnet*, wlan0, eth0, usb0, and supports repeater mode (WiFi Internet → WiFi clients).
6. **DNS upstream:** hardcoded 8.8.8.8/1.1.1.1, no fallback to WAN DNS from `getprop net.dns1`, no caching stats, no hijack port handling for clients ignoring DHCP DNS (partially done in iptables but not in DnsManager).
7. **No usage tracking:** `bytesUp/Down` never populated (O4), so billing reports are empty. Need UsageMonitorService reading /proc/net/dev or iptables counters.
8. **No auto-heal granularity:** keepalive repairs forwarding/jumps/policy-routing/DHCP, but on DHCP failure it still restarts whole gateway in some paths. Need Watchdog that restarts only failed component (DHCP only, NAT only, etc).
9. **No printer, no payments, no hotspot_sessions table:** master plan requires thermal printer (Bluetooth/USB) and voucher printing format, payments table, hotspot_sessions.

## 4. Duplicate Code

- `scripts/*.sh` vs `app/src/main/assets/*.sh` — fixed by `syncShellScripts` task (assets generated, git-ignored, but still appears as duplicate in zip history)
- `ApLauncher` + `WifiShareAp` + `SoftApController` — three places that create APs, overlapping interface discovery logic (`interfaceNames()`, `apInterface()`, `pickApInterface()` in ApConfigText)
- `VoucherManager` + `VoucherCodes` — code generation vs business logic split but both touch code format
- `LeaseParser` + `ArpResolver` — both parse MAC/IP, merge logic in RootShell.connectedClients
- `RootShell.run` logging + `AppLog` + `LogcatWatcher` — three logging paths that eventually all go to AppLog, but with different quiet flags and levels; can be unified
- `LanPlan` parsing of hotspot.runtime + `ApConfigText.parseKeyValue` — both parse KEY=value runtime files

## 5. Missing Components (vs Master Plan)

| Master Plan Module | Exists? | Notes |
|---|---|---|
| NetworkEngine.kt / NetworkController | ❌ | Logic in HotspotService.runGateway + setup_network.sh |
| WanDetector.kt | ❌ | `resolve_wan()` in shell, `defaultRouteInterface()` in RootShell, but no Kotlin class detecting Mobile/WiFi/USB/Ethernet and supporting repeater mode |
| DhcpServer.kt / DhcpManager | ❌ | dnsmasq started in shell, no Kotlin manager with IP pool 192.168.49.x, lease DB, conflict prevention |
| NatManager.kt | ❌ | iptables masquerade in shell only |
| DnsManager.kt | ❌ | dnsmasq args in shell only, no local DNS + forward + captive portal support class |
| FirewallManager.kt | ❌ | HS_FWD/HS_NAT chains in shell only |
| DeviceManager | ❌ | connectedClients via RootShell, but no Kotlin manager with first_seen/last_seen/download/upload |
| BillingManager / VoucherManager exists partially | ⚠️ | VoucherManager exists but no data_limit, price, payments, thermal printing |
| HotspotService | ✅ | exists but should be split |
| WatchdogService | ❌ | monitorOnce inside HotspotService, not independent Service, not 10s granular heal |
| UsageMonitorService | ❌ | bytes never counted |
| PrinterManager.kt | ❌ | no BT/USB printer |
| Database tables: dhcp_leases, hotspot_sessions, payments, settings | ❌ | only vouchers, sessions, device_profiles |
| UI Redesign (Dashboard with ONLINE status, Client screen with Block/Limit) | ⚠️ | Dashboard exists but not matching master plan mock |
| Logging to files network.log/error.log/billing.log | ⚠️ | AppLog to applog.txt + logcat, but not separate files per master plan |

## 6. Recommended Architecture (Aligned to Master Plan + Existing Code)

Keep existing debugger and shell scripts as source of truth for root commands, but wrap them in Kotlin managers so auto-heal can be granular.

```
UI Layer
  Dashboard (STATUS ONLINE, Internet: WiFi/Mobile, Clients: N, Data: X GB, START/STOP)
  Clients (IP, usage, Block, Limit)
  Vouchers (generate RNS-1001, time/data rules, Activate/Expire/Disable/Print)
  Reports (hotspot_sessions, payments)
  Settings (hotspot mode, SSID/pass, WAN/LAN pin, printer)

Core Engine (new package com.hotspot.billing.core)
  NetworkController — orchestrates START: detect WAN → create LAN → start DHCP → start DNS → enable NAT → verify internet → SUCCESS; STOP: clean shutdown DHCP/DNS/NAT/hotspot
  WanDetector — detects Mobile (ccmni, rmnet), WiFi (wlan0), USB (rndis0, usb0), Ethernet (eth0); supports repeater mode (WiFi Internet → WiFi clients via WiFi Direct/LOHS/root_ap, not ap0)
  HotspotManager — wraps ApLauncher + SoftApController + WifiShareAp, returns ApHandle with interfaceName/ssid/pass, handles retry
  DhcpManager — ensures client gets 192.168.49.x, gateway 192.168.49.1, DNS 192.168.49.1; IP pool with Room persistence (dhcp_leases table), lease management, conflict prevention, SIGHUP dnsmasq via reserve/unreserve
  NatManager — enable ip_forward, iptables masquerade, forward rules, policy routing (ip rule iif <lan> lookup main)
  DnsManager — local DNS, forward to upstream (WAN DNS or 8.8.8.8/1.1.1.1), hijack port 53 for clients ignoring DHCP DNS, captive portal support
  FirewallManager — HS_FWD chain default DROP, per-client ACCEPT above DROP, RETURN for portal bypass, REJECT 443
  DeviceManager — merges dhcp_leases + ARP + sessions, tracks first_seen/last_seen/download/upload via iptables counters or /proc/net/dev
  BillingManager — wraps VoucherManager, adds data_limit, price, payments, time rules (1h/5h/1d, 500MB/1GB/5GB)

Background Services
  HotspotService — foreground, holds NetworkController, starts portal, owns notification
  WatchdogService (or Watchdog inside HotspotService but granular) — every 10s checks AP alive, DHCP alive, DNS alive, Internet alive, Clients alive; repairs only failed component
  UsageMonitorService — every 5s reads counters, updates clients table, checks data_limit

Storage (Room)
  hotspot_sessions: id, start, stop, mode, status, data_used
  clients: id, mac, ip, name, first_seen, last_seen, download, upload (or reuse device_profiles + sessions)
  dhcp_leases: id, mac, ip, hostname, start_time, last_seen (new)
  vouchers: existing + add data_limit, price
  payments: id, voucher_id, amount, date, method (new)
  settings: key, value (new, or use SharedPreferences for now)

Printing
  PrinterManager — Bluetooth thermal + USB, prints voucher format from master plan

Logging
  Keep AppLog ring + file, but also add network.log/error.log/billing.log per master plan, or at least tag-based filtering that exports to those names
```

**Phase 1 priority (per master plan): Fix hotspot engine so clients stop stuck at "Obtaining IP"**
- Implement WanDetector first (detect ccmni0 vs wlan0)
- Implement DhcpManager that guarantees IP/gateway/DNS, with Room-backed IP pool, and that kills foreign dnsmasq *before* starting ours, and adopts Android's address instead of forcing 10.66.0.1
- Implement NatManager + DnsManager + FirewallManager as thin Kotlin wrappers around setup_network.sh commands, but with granular start/stop/check
- NetworkController.START flow: detect WAN → create LAN (via HotspotManager) → waitForAddress → start DHCP (our dnsmasq) → start DNS (same dnsmasq) → enable NAT → test internet (ping 8.8.8.8 or HttpProbe) → SUCCESS
- STOP flow: stop DHCP/DNS/NAT/hotspot cleanly
- Test: Mobile data → client gets 192.168.49.x and internet works; WiFi sharing (repeater) → same

**No random rewrite:** Preserve MainActivity, HotspotService notification, debugger, voucher UI, portal HTML, scripts/ as source of truth. Only add new core/ package and refactor HotspotService to use it.

---

## 7. Test Plan (From Master Plan)

- Test 1: Mobile data: Phone data → RNS → Laptop, expect internet works
- Test 2: WiFi sharing: Router WiFi → Phone → RNS hotspot → Client, expect internet works (repeater mode via WiFi Direct / LOHS)
- Test 3: 5 clients simultaneously
- Test 4: Restart phone, expect auto recovery via BootReceiver + Watchdog
- Test 5: Internet disappears (airplane), expect auto recovery when back

Before release: run `bash tools/run-script-selftest.sh` and `./gradlew test` (unit tests).

## 8. Immediate Action (Phase 1)

1. Create `core/` package with WanDetector, DhcpManager, NatManager, DnsManager, FirewallManager, NetworkController, DeviceManager
2. Add Room entities for dhcp_leases, hotspot_sessions, payments, settings
3. Fix DHCP race: ensure foreign dnsmasq killed *before* our dnsmasq start, and fallback to saved cmdline if bind fails
4. Adopt LAN address instead of forcing, wait for address, relax rp_filter (already in script)
5. Implement granular Watchdog: check AP alive, DHCP alive, DNS alive, Internet alive, Clients alive; restart only failed component

This audit satisfies Phase 0. Next step is Phase 1 implementation.

---

# Phase 1 — Log forensics and fixes (2026-09-24)

Evidence: `Debuggerfitst semi success.txt` — 939-line event log, Infinix X650C, Android 9 (API 28),
MediaTek, rooted, app `1.0.27+f35d949`, phase RUNNING when exported.

## 9. What the log proves works (must not break while fixing the rest)

- AP: WiFi Direct group owner, `p2p0`, SSID `DIRECT-5O-Infinix HOT 8`, address adopted `192.168.49.1/24`.
- DHCP: client `a4:4e:31:83:ec:3c` got `192.168.49.10` (`DHCPACK` at 12:39:25), gateway/DNS `192.168.49.1`.
- NAT/policy routing: `HS_NAT`/`HS_FWD` first, `ip_forward=1`, `ip rule 15500/15501` present, `ping 8.8.8.8 -> true`.
- Voucher: portal served the page, `redeem` reserved the IP, authorized the MAC and added the `tc` class.

## 10. Problems, with the line that shows each one

| # | Problem | Evidence in the log | Measured cost |
| --- | --- | --- | --- |
| 1 | START took **74 s** (target < 10 s) | `+6s START requested` → `+80s START SUCCESS`; DHCP step 25.7 s, DNS step 15.3 s, NAT step 31.0 s | 3 of the 6 steps are 90 % of the time |
| 2 | Random SSID/password | requested `ssid=RNS-Hotspot`; `setNetworkName`/`setPassphrase` → `NoSuchFieldException`, `createGroup(config)` → `NoSuchMethodException`; group came up as `DIRECT-5O-Infinix HOT 8` / `kamm4fFC` | Android 9 blocks the pinned-method path |
| 3 | DHCP bind race | `dnsmasq: failed to bind DHCP server socket: Address already in use` / `FAILED to start up` at 12:37:11 — previous session's dnsmasq (pid 9557) still held UDP/67 and was then "adopted" | the new server never binds until the old one dies |
| 4 | Root shell is the bottleneck | 142 commands, **170.7 s** of shell time in 7 min; `setup_network.sh keepalive` 5× avg 18.1 s (Σ 90.6 s), `cat dnsmasq.leases` 104×, `cat /proc/net/arp` 6× avg 3.07 s (max 15.4 s), `authorize` avg 3.86 s (max 20.9 s) | every slow command starves every other caller |
| 5 | Duplicate work on one voucher | 3× `POST /redeem` for `HCSQ-KEBE` in 40 s, 6 `authorize` calls, 9 `bandwidth_control.sh` calls | same contention, plus repeated firewall writes |
| 6 | Stop/exit untested and heavy | no `stop`/`STOP` line in the log at all; `stopSequence` had no fast path and never ran `cleanup` | cannot be judged from this log — reviewed in code instead |
| 7 | Watchdog was a second hog | each tick ran `defaultRouteInterface`, `interfaces`, `isDnsmasqRunning`, `isForeignDnsmasqRunning`, `policyRoutingOk` ×2, `checkFirewallHealth` (2× `iptables -S`), `readLanPlan`, `lanAddresses`, then the 18 s `keepalive` | ~15 shell calls per 8 s tick |

Ambient noise (not ours, do not chase): `MtkDataShaping.openLteGateByDataShaping` NPE every ~10 s,
`WifiVendorHal getWifiLinkLayerStats ERROR_NOT_SUPPORTED`, `WifiP2pService Unhandled message`.

Root cause of 1/4/5/7 is one and the same: **libsu runs the jobs of one shell strictly one after
another**, and the code asked that shell dozens of times per poll from the UI thread pool, the
watchdog, the portal and the voucher path at once.

## 11. What was changed for this phase (file by file)

| File | Change | Why |
| --- | --- | --- |
| `util/RootShell.kt` | fair lock with wait logging, `tryRun()` (returns null when the shell is busy), `Probe` (one command → 19 fields, 2 s cache), lease/arp caches, `repairNat()`, `cleanupAll()`, `invalidateCaches()` | one choke point; hot paths can skip instead of queueing |
| `scripts/setup_network.sh` | new `probe`, `nat <lan> <wan> <subnet>`, `cleanup [lan]` subcommands; `cleanup` also removes the port-67 OUTPUT guard for every known interface; dnsmasq killed by ownership, not only by pidfile | lets the app read state in one command and repair NAT without restarting DHCP |
| `core/DhcpManager.kt` | start verified from one fresh probe; `owner()` → `ours` / `ours-orphan` / `android` / `none` | detect the pid-9557 case instead of adopting a stranger |
| `core/DnsManager.kt` | probe-based health, no duplicate checks | watchdog cost |
| `core/NatManager.kt` | `enableNat` = `repairNat` + verify; `forwardingHealthy()` | fixes the NAT step and the `masquerade present=false` false negative |
| `core/FirewallManager.kt` | redirect-aware health check, authorised list from one file | portal must not be reported broken while the redirect is in place |
| `core/WatchdogManager.kt` | `check()` = one probe; `heal()` repairs NAT first and rate-limits DHCP restarts to 20 s | off-tick heartbeats; never restart the whole hotspot to fix one part |
| `core/NetworkController.kt` | WAN detected once and pinned to `hotspot.env`; AP → 250 ms/4 s address poll; DHCP ∥ NAT in parallel; one probe; internet test; `stop()` = one script call + leftover report | the 74 s start |
| `core/EmergencyCleaner.kt` (new) | portal first, then rules ∥ AP release, verify with a fresh probe, retry once | leaks survive a crash; the master plan's `cleanupEverything()` |
| `HotspotService.kt` | `Phase.STOPPING`, `exitAndClean()`, busy-skip monitor tick, deep check every 8 ticks, teardown through the cleaner, `clientsSnapshot()`/`envSnapshot()` | instant STOP feedback, no root work from the UI |
| `net/VoucherManager.kt` | 20 s re-apply cooldown per code | 3× redeem in 40 s |
| `net/ArpResolver.kt` | reads the shared arp/lease cache | 15.4 s `cat /proc/net/arp` |
| `core/UsageMonitor.kt` | one counter dump + one lease read per tick (was one process per matched rule) | 104 lease reads |
| `MainActivity.kt`, `res/layout/activity_main.xml`, `res/values/strings.xml` | EXIT button calling `exitAndClean()`, `STOPPING...` shown immediately, poller uses the cached snapshots | problems 6 and 7 from the UI side |

## 12. Verification status (honest)

- `bash tools/run-script-selftest.sh` → **ALL CHECKS PASSED** (~12 s). It asserts the shell layer
  against stub `iptables`/`ip`/`tc`/`dnsmasq`: probe keys, cleanup of an untracked dnsmasq and of
  the port-67 guard, `nat` re-install without a DHCP restart and without duplicate redirects,
  idempotent `keepalive`, orphan handling.
- The sandbox this branch was written in has no JDK, no Gradle, no `kotlinc` and no network, so the
  first compile of this code happened in **CI, on the pull request** (`.github/workflows/apk.yml`),
  and it was not green at first. It found, in order:
  1. `RootShell.kt` — `IFACE_REGEX`, `IP_REGEX`, `MAC_REGEX`, `SUBNET_REGEX` and `POLL_WAIT_MS`
     were used but never declared in this rewrite. Fixed.
  2. `WifiShareAp.kt:328,391` — `interfaceNames()` returns `Set<String>` while `handleFromGroup()`
     and `discoverInterface()` declared `List<String>`. **This one was pre-existing: the same two
     errors are what failed the APK build on `main` (commit `dfe5a8e`, check-run annotations), so
     `main` did not compile at all before this branch.** Fixed by matching the parameter type to the
     caller and to `ApConfigText.pickApInterface()`; no behaviour change.
- Static cross-referencing was used where a compiler was not available (every `Class.member` /
  `manager.method()` in the touched files against its declaration, and a whole-tree scan for
  `UPPER_CASE` identifiers used but never declared). That found one further real break:
  `NetworkController.start()` called a `waitForLanInterface` that only existed in `HotspotService`
  — now a private 250 ms-poll helper of `NetworkController`, and the dead copy in the service was
  deleted.
- **CI result on this branch (run `35978368544`): green** — shell self-test, `testDebugUnitTest`
  (all unit tests), `lintDebug`, and the debug + release APK build. Artifact `hotspot-billing-apk-31`
  (6.7 MB) is attached to the run. This is a build/test result, **not** a device test: master-plan
  device tests 1–7 are still outstanding and are not claimed here.

## 13. Still open after this phase

1. Honest SSID/password path: keep `RNS` / `RNSRNSRNS` as the requested values, show the real
   active pair in the UI, and never present a random credential as the configured one (the
   WiFi Direct route cannot be forced on Android 9 — the fallbacks that can, must be preferred).
2. `ClientAuthorizationManager`: one class owning MAC → portal → voucher → firewall, replacing the
   authorise calls scattered between `VoucherManager`, `FirewallManager` and the portal.
3. Room: `dhcp_leases`, `voucher_usage`, `payments`, `system_logs` (+ indexes) as listed in the
   master plan; `hotspot_sessions` and `clients` already exist.
4. `logs/` directory with `network.log`, `error.log`, `voucher.log`, `system.log` written by
   `AppLog`, in addition to the current single `applog.txt`.
5. Printer integration and the production test pass (master list 1–7).
