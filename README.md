# RNS — Hotspot Billing Gateway

A rooted-Android voucher hotspot: the phone sits between the ISP router and a second
router, hands out DHCP, pops a captive portal, and sells bandwidth by voucher with a
per-device MAC/IP binding and a per-plan `tc` cap.

```
Router1 (ISP) ──> rooted Android phone  ──> Router2 ──> users
                  · NAT + firewall gate
                  · dnsmasq DHCP + static reservations
                  · captive portal (NanoHTTPD)
                  · voucher → MAC binding, tc shaping
```

> `hotspot-billing.zip` is the frozen AI-generated drop this project started from. The live
> source is `app/` and `scripts/`. [`AUDIT.md`](AUDIT.md) is the full review of that drop:
> 6 build blockers, 5 "the feature silently did nothing" bugs and one authentication bypass,
> each with its status.

---

## Layout

| Path | What it is |
| --- | --- |
| `app/` | Android app (Kotlin, Room, NanoHTTPD, libsu). Open this folder's parent in Android Studio. |
| `app/…/SplashActivity.kt`, `RootCheckActivity.kt`, `SetupHotspotActivity.kt`, `SetupVoucherActivity.kt`, `SetupStartActivity.kt` | The [onboarding flow](#deploy): splash → root check → hotspot mode + password → voucher system → start, one screen per step. |
| `scripts/setup_network.sh` | **Source of truth** for NAT, captive-portal redirect, DHCP, policy routing and MAC authorisation. |
| `scripts/bandwidth_control.sh` | **Source of truth** for per-client `tc`/HTB shaping (both directions). |
| `scripts/netshare_ap.sh` | Root fallback AP: asks the driver for a second interface and runs `hostapd` on it — a hotspot with no toggle and no framework API. |
| `app/…/debug/` | The recorder behind the [debugger](#debugger): ring buffer + file log, crash guard, logcat mirror, health findings (`H1`–`H10`), the full diagnostic report. |
| `app/…/net/ApMode.kt`, `ApLauncher.kt`, `WifiShareAp.kt` | How the customer-facing WiFi network gets created: system hotspot, local-only hotspot, WiFi Direct group (NetShare-style), root `hostapd`, or manual. |
| `tools/run-script-selftest.sh` | Runs those scripts against stub kernel commands and asserts the resulting ruleset. |
| `.github/workflows/` | APK build, README refresh, branch cleanup. |
| `app/src/main/assets/*.sh` | **Generated** from `scripts/` by `:app:syncShellScripts`; git-ignored, never edited. |
| `Hotspot .txt` | Terminal capture from the device that the design is based on. |

## Build

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # unit tests
bash tools/run-script-selftest.sh  # shell layer, no phone needed
```

Requires JDK 17 and Android SDK platform 34. Every pull request also gets a built APK as a
workflow artifact — see [CI](#continuous-integration).

## Deploy

1. `adb install app-release.apk` (or just open the APK on the phone), then grant the app root when Magisk prompts.
2. **Launch the app.** It always opens on the **splash** — and if the *previous* run
   ended with a crash, the splash shows the saved crash on screen (tap it to open the
   error log), so "it died before it opened" is no longer an untraceable failure.
   The crash guard is installed in `Application.onCreate` *before* anything else
   runs, so every later failure is written to `files/logs/last_crash.txt` with the
   120 log records before it.
3. **First start is a four-step wizard**, one screen per step, each with its own
   *Debugger* shortcut:
   1. *Root check* — runs on **every** launch. The **Continue** button stays
      disabled until a uid-0 shell is confirmed (`Shell.getShell().isRoot`); no
      root → the screen says exactly where to fix it (Magisk → Superuser) and
      offers a re-check.
   2. *Hotspot* — network name + password (validated: WPA2 lengths, and never
      `" `` $ \` because they are root-shell input — the F-01 finding) and how
      the network is created (the six AP modes below). *Detect* fills the
      advanced WAN/LAN fields from the phone's current state.
   3. *Voucher system* — plan duration (1 Hour / 3 Hours / 1 Day / 7 Days),
      down/up speed in Mbps and how many codes to generate.
   4. *Ready* — a summary of what will run, then **START GATEWAY**: mints the
      codes, shows them in a dialog, marks setup complete and starts the
      gateway foreground service, then opens the dashboard.
   Once setup is complete, later launches skip straight from the root check to
   the dashboard; the wizard stays reachable from Settings → *Run setup wizard*.
   The gateway is started by **that Start button** (and by the boot receiver on
   later reboots) — never by a half-configured app on its own.
4. The **Dashboard** shows live state: root, hotspot, WAN/LAN interfaces, portal, and an event log that names every command it runs - if something fails, the reason is on that screen.
5. **The WiFi network customers join.** Settings → *Hotspot mode* picks how it is created; the
   phone's own WiFi stays connected the whole time, so it keeps **receiving** internet on
   `wlan0`/`ccmni` while **sending** it out on the second interface:

   | Mode | What it does | Needs the Android hotspot toggle? |
   | --- | --- | --- |
   | **Automatic** (default) | Turns WiFi on, then tries the method this radio can actually start. Mobile uplink (the Hot 8): system hotspot first, then local-only, WiFi Direct, root hostapd. WiFi uplink: system hotspot last, so it does not disconnect the internet. Whichever method actually **beacons** is remembered and tried first next time. | no |
   | **NetShare (WiFi Direct)** | The phone becomes a WiFi Direct **group owner**. Needs WiFi and Location on — while either is off, `createGroup` returns BUSY, which means Direct is disabled, not that another group exists. If Direct still refuses, the system hotspot is tried. | no |
   | **Local-only hotspot** | `WifiManager.startLocalOnlyHotspot()` — an AP the app may create on its own (Android picks the SSID/password; the app reads them back and shows them). | no |
   | **Root hostapd** | `scripts/netshare_ap.sh`: ask the driver for a second interface and run a CLI `hostapd`. The Hot 8 only has the WiFi HAL binary, which is not that program, so this mode falls through to the system hotspot. | no |
   | **System hotspot** | The real Android hotspot (`ap0`). Started as root via `startSoftAp` — a normal app is not allowed to call it. Android's own dnsmasq is left running; killing it makes the Hot 8 tear the AP down. | no |
   | **Manual** | Create nothing; wait for an interface to appear and take it over. | yes |

   On Android 9 (the Infinix Hot 8, X650C) Automatic does **not** wait for the toggle.
   `cmd wifi start-softap` does not exist on that build; the app calls the same
   `startSoftAp` the Settings app calls, as root. It also turns WiFi and Location
   services on first — WiFi Direct returns BUSY and local-only hotspot is refused
   while either is off, and a missing Location *permission* throws
   `SecurityException` instead of a callback. Granting the permission retries
   immediately. The gateway retries every 20 s and takes over the instant an
   interface appears, so flipping the system toggle later still works.

   **Adoption requires evidence, not a link.** An interface only counts as "already
   the hotspot" when the framework says the softap is `ENABLED`, a WiFi Direct group
   we own is running (or it carries Android's group-owner address `192.168.49.1`),
   our own `netshare` hostapd is, or a wired LAN has link. `p2p0` - the WiFi Direct
   interface - is created and brought **UP as soon as WiFi is on** and stays UP after
   the group is removed, so a build that adopted it because `ip link` said so went on
   to assign `10.66.0.1/24` to a dead interface, start dnsmasq on it and report a
   running hotspot that no phone could see (device log, 2026-09-24 14:42). Every
   start now logs the proof that was accepted **and** the reason each other candidate
   was rejected, and the address this app writes itself is removed again by cleanup
   so it can never be mistaken for evidence twice.
6. When the hotspot comes up the app **keeps the address Android already assigned** (usually `192.168.43.1`). Replacing that with `10.66.0.1` is what left phones spinning on "Obtaining IP address". DHCP offers are sent as broadcasts, because MediaTek radios drop the unicast offer and the client never finishes DHCP. If Android's own DHCP server comes back and the two would fight, the app steps aside and lets the phone hand out addresses — the sign-in page still appears either way. After installing this update, tell users to **forget the Wi-Fi network and join again once**.
7. **Vouchers** tab: pick a preset (1 Hour / 3 Hours / 1 Day / 7 Days) or fill in plan name, duration and speeds, then *Generate*. Codes are copyable/shareable straight from the dialog; the list filters by status and each row can be expired or deleted.
8. **Users** tab: everyone currently on the LAN (online *with* a voucher vs *waiting at the portal*), saved user profiles - a name/phone/note per device MAC, recorded automatically the first time a device is seen - and session history.
9. **Settings** tab: hotspot mode (see above), SSID/password, WAN/LAN interface pins (blank = automatic), a *Detect* button that fills in what the phone currently has, and *Permissions* / *Debugger* shortcuts.
10. Check the raw state any time with `su -c 'sh /data/local/tmp/setup_network.sh status'` — or `… diag` for the full dump the debugger's **Full report** is built from.

### Updating the app

Every build - local or CI, debug or release - is signed with the one committed
key `keystore/hotspot-billing.p12`, so a new APK installs as a normal update
over any earlier one (Android only ever refused before because CI-signed debug
APKs get a fresh random key on every runner).

**One exception, one time only:** builds made before this key existed are signed
differently, so the very next install needs a single uninstall first. After that,
updates replace in place forever - no uninstall/reinstall, and vouchers and
profiles survive because the update keeps the app's data.

### How the gate works

Unauthenticated clients are dropped at `FORWARD`. Port 80 is redirected on the phone to the
local login page, and that page is returned **directly** (HTTP 200) for the OS probe URLs
(`generate_204`, `hotspot-detect`, `ncsi`). A redirect to `:8080`, or a probe that times out,
does not pop the sign-in sheet — the client just sits there with an IP and no internet.
Port 443 is reset so the client falls back to its plain-HTTP probe (DNATing TLS to a
plaintext server just yields certificate errors and no login sheet). DNS from clients that
ignore the DHCP DNS server is redirected to the phone so the probe can resolve; answers are
real, not forged to a private address (several Android builds treat that as "no internet"
and never show the sheet). On redemption:

1. a `mac,ip` reservation is written and dnsmasq is `SIGHUP`ed, so the client
   actually receives the assigned address when this app owns DHCP;
2. an `ACCEPT` for that **MAC** (not MAC+IP — the client is often still on its
   old lease) is inserted **above** the default-deny rule;
3. a `RETURN` exempts the MAC from the portal redirect;
4. an HTB class plus an ingress `police` filter cap download *and* upload.
   The address the client still holds is capped too, until DHCP moves it.

Leases are 10 minutes so a client migrates to its reserved IP on the next renew.
Until then the MAC rule already lets it through, so it does not go dark mid-switch.

## Debugger

**Dashboard → Debugger** (also the *Debugger* link on the splash and on **every wizard
screen**, Settings → *Debugger*, and the *Debugger* action on the ongoing notification)
opens a screen whose only job is to get the text out of the phone:
tap **Copy all** or **Share** and paste it into a chat. Nothing has to be selected by hand.

What it records, continuously and in order:

| Source | What you see |
| --- | --- |
| Every root command | `$ iptables -t nat -S …` → `exit 0, 43ms`, plus stdout/stderr. A rule that was *not* applied is visible instead of silent. |
| AP bring-up | Which mode, which method was tried, what the framework answered (`onFailed reason=3` decoded into words), which interface appeared, which address was adopted, the SSID/password customers must join. |
| Watchdog | Findings with stable codes **H1**–**H10**: `H1` AP interface gone, `H2` address moved, `H3` no DHCP server ("Obtaining IP address"), `H4` IP forwarding off, `H5` portal dead, `H6` our iptables jump no longer first, `H7` phone has no internet side, `H8` policy-routing rule missing, `H9` clients but no voucher yet, `H10` portal probe not HTTP 200. Each one is logged when it appears, again when it recovers, and carries the fix. |
| The system's own log | A logcat mirror of `wpa_supplicant`, `hostapd`, `Tethering`, `IpServer`, `WifiP2pService`, `netd`, `dnsmasq` — off / WiFi tags / everything. With root it is the full log; without, this app's lines. |
| Crashes | Any uncaught exception or failed coroutine is written to `files/logs/last_crash.txt` **together with the 120 records before it**, and reported on the next start. |
| Vouchers & portal | Redeem attempts and outcomes, activations, expiries, sweeps, and every portal request. |

Buttons: **Copy all** / **Share** / **Save .txt** (shares the file for reports too large to
paste), **Full report** (an 11-section dump: device, permissions, WiFi/P2P/AP state,
interfaces, routing policy, iptables *with packet counters*, both DHCP servers, leases, ARP,
shaper, live portal probes, logcat and the app log), **Check now** (run the health check on
demand), **Live log** / level filters **All · Info+ · Warn+**, **Clear**.

The log is a 6 000-line ring buffer mirrored to `files/logs/applog.txt` (survives the process
dying), rate-limited at 300 lines/s with the dropped count *recorded* rather than hidden.

## Continuous integration

| Workflow | Trigger | What it does |
| --- | --- | --- |
| [`apk.yml`](.github/workflows/apk.yml) | every PR, every push to `main`, manual | Shell self-test → unit tests → lint → `assembleDebug` + `assembleRelease`, uploads the APK as an artifact and posts the download link back on the PR. |
| [`readme.yml`](.github/workflows/readme.yml) | PR opened / updated / closed, push to `main` | Rewrites the *Pull requests* section below from the live PR list. Only touches the generated markers; ignores `README.md` changes and commits with `[skip ci]`, so it cannot loop. |
| [`branch-cleanup.yml`](.github/workflows/branch-cleanup.yml) | every push to `main` (i.e. after a merge), every 6 h, manual | Deletes every remote branch except `main` / the default branch / anything in the `KEEP_BRANCHES` repo variable / the branch that triggered a manual run, enables GitHub's own *delete head branch on merge*, then reconciles the checkout with `origin` and reports what is left. |

`branch-cleanup.yml` never pushes to, resets, or deletes `main`. By default it also skips
branches that still have an **open** PR — re-run it with `include_open_pr_branches: true` to
force, or `dry_run: true` to preview.

Optional: add a `KEEP_BRANCHES` repository variable (comma-separated branch names) to spare
extra branches from the sweep, and a `REPO_ADMIN_TOKEN` secret (fine-grained PAT with
*Administration: write*) so the workflow can also flip GitHub's own *Automatically delete
head branches* setting — without it, tick that box once under Settings → General → Pull
Requests, or just let the prune job do the work.

## Status

Working: voucher generation and management from the admin UI, voucher redemption,
single-device binding, static IP assignment, per-plan shaping in both directions,
captive-portal probes for Android/iOS/Windows, foreground service that survives the
app being swiped away, auto-restart after reboot, expiry sweep, user profiles
auto-recorded per device MAC, a watchdog that reports and repairs drift (findings
`H1`–`H10`), evidence-based hotspot adoption (an interface is never adopted unless
something is really beaconing on it), and a [debugger](#debugger) that records every
command, callback and finding as copyable text.

The admin screen is a single dark console: a status hero with a live dot and the
Start/Stop actions, cards for gateway / join / interfaces, a 30-line event log (the
debugger keeps the rest), voucher presets and plan builder, users and sessions, and a
bottom navigation bar. The theme is forced dark so dialogs, inputs and radios match
the cards instead of coming out light on a dark layout.

Hotspot bring-up: no toggle needed — local-only hotspot, WiFi Direct group owner
(NetShare-style) or root `hostapd` create the network while the phone's own WiFi stays
connected; on Android 12+ the real system hotspot is tried first, and on Android 9/10 the
system toggle still works and is adopted within ~2 seconds whenever it appears. What a
given radio supports is a hardware question: the debugger names the exact framework error
when a method is refused (see [AUDIT.md O6](AUDIT.md#7-still-open)).

Not built yet — see [AUDIT.md §7](AUDIT.md#7-still-open). The important remaining ones:
no per-session byte accounting, portal traffic is plaintext HTTP on the LAN, and no
rate limit on `/redeem`.

<!-- BEGIN GENERATED: pr-log -->
### Pull requests

_Regenerated automatically by `.github/workflows/readme.yml` — edit anything outside the markers instead._

**2** open · **11** merged · **0** closed without merging · updated 2026-09-25 05:05 UTC

| PR | Title | Author | Branch | State | Updated |
| --- | --- | --- | --- | --- | --- |
[#13](https://github.com/rizwanahmedsora9-pixel/RNS/pull/13) | App that actually opens: splash → root check → settings → start, crash-proof | @arena-ai-coding-agent[bot] | `arena/01a0d6cd-rns` | 🟢 open | 2026-09-25
[#12](https://github.com/rizwanahmedsora9-pixel/RNS/pull/12) | Audit of all 11 phases + Linux e2e test environment (+ fixes for F-20, F-21) | @arena-ai-coding-agent[bot] | `arena/01a0d3da-rns` | 🟣 merged | 2026-09-25
[#11](https://github.com/rizwanahmedsora9-pixel/RNS/pull/11) | Hotspot: only adopt interfaces that are really beaconing + one dark console | @arena-ai-coding-agent[bot] | `arena/01a0d2cc-rns` | 🟣 merged | 2026-09-24
[#10](https://github.com/rizwanahmedsora9-pixel/RNS/pull/10) | Hotspot: kill root-shell contention — fast start, clean stop, verified leftovers | @arena-ai-coding-agent[bot] | `arena/01a0d27b-rns` | 🟣 merged | 2026-09-24
[#9](https://github.com/rizwanahmedsora9-pixel/RNS/pull/9) | One control surface, one method: idle launch, clean P2P, one-tap join | @arena-ai-coding-agent[bot] | `arena/01a0d1e2-rns` | 🟢 open | 2026-09-24
[#8](https://github.com/rizwanahmedsora9-pixel/RNS/pull/8) | Start the Hot 8 hotspot as root so the gateway leaves WAITING_AP | @arena-ai-coding-agent[bot] | `arena/01a0d1b5-rns` | 🟣 merged | 2026-09-24
[#7](https://github.com/rizwanahmedsora9-pixel/RNS/pull/7) | fix(build): repair Kotlin nullability errors blocking APK build | @arena-ai-coding-agent[bot] | `arena/01a0d192-rns` | 🟣 merged | 2026-09-24
[#6](https://github.com/rizwanahmedsora9-pixel/RNS/pull/6) | CI: preserve Android toolchain and build diagnostics | @arena-ai-coding-agent[bot] | `arena/01a0d189-rns` | 🟣 merged | 2026-09-24
[#5](https://github.com/rizwanahmedsora9-pixel/RNS/pull/5) | Master rebuild: stable NetShare engine (Phases 0-9) — fix Obtaining IP | @arena-ai-coding-agent[bot] | `arena/01a0d142-rns` | 🟣 merged | 2026-09-24
[#4](https://github.com/rizwanahmedsora9-pixel/RNS/pull/4) | Debugger that records everything (copyable text) + NetShare-style hotspot with no toggle | @arena-ai-coding-agent[bot] | `arena/01a0d011-rns` | 🟣 merged | 2026-09-24
[#3](https://github.com/rizwanahmedsora9-pixel/RNS/pull/3) | Fix clients stuck obtaining an IP with no captive portal | @arena-ai-coding-agent[bot] | `arena/01a0cfe2-rns` | 🟣 merged | 2026-09-23
[#2](https://github.com/rizwanahmedsora9-pixel/RNS/pull/2) | Admin UI (vouchers, user profiles), real hotspot control, foreground service, in-place updates | @arena-ai-coding-agent[bot] | `arena/01a0cfac-rns` | 🟣 merged | 2026-09-23
[#1](https://github.com/rizwanahmedsora9-pixel/RNS/pull/1) | Audit the AI-generated hotspot-billing drop, make it build, add APK/README/branch CI | @arena-ai-coding-agent[bot] | `arena/01a0cf69-rns` | 🟣 merged | 2026-09-23
<!-- END GENERATED: pr-log -->
