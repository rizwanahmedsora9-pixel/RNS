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
| `scripts/setup_network.sh` | **Source of truth** for NAT, captive-portal redirect, DHCP, policy routing and MAC authorisation. |
| `scripts/bandwidth_control.sh` | **Source of truth** for per-client `tc`/HTB shaping (both directions). |
| `scripts/netshare_ap.sh` | Root fallback AP: asks the driver for a second interface and runs `hostapd` on it — a hotspot with no toggle and no framework API. |
| `app/…/debug/` | The recorder behind the [debugger](#debugger): ring buffer + file log, crash guard, logcat mirror, health findings (`H1`–`H10`), the full diagnostic report. |
| `app/…/net/ApLauncher.kt`, `WifiShareAp.kt` | How the customer-facing WiFi network gets created: one method only — the phone becomes a WiFi Direct **group owner** (the NetShare technique), with every attempt and framework callback logged. |
| `app/…/net/JoinConfig.kt`, `app/…/util/Qr.kt` | The one-tap join: a fixed, deliberately non-secret passphrase (`rns-open-2026`) and the `WIFI:` QR payload / rendering that makes joining a single scan. The voucher is the real gate. |
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
2. Launch the app. The gateway service comes up **IDLE**: it deploys its scripts, checks
   root and shows the status — it does not create the network. The **Dashboard** shows
   live state: root, AP, WAN/LAN interfaces, portal, a status badge and an event log that
   names every command it runs — if something fails, the reason is on that screen.
3. **Start / Stop is one button.** The dashboard's single button is the whole control
   surface, with a badge next to it that says exactly where things stand:

   | State | Button | Badge |
   | --- | --- | --- |
   | Idle / Stopped | **Start** (enabled) | `OFF` (grey) |
   | Starting | "Running" (disabled) | `STARTING… N/6` (yellow) — the six steps: WAN → AP → address → DHCP → DNS → NAT |
   | Running | **Running** (enabled, tap = stop) | `ONLINE — <SSID>, N clients` (green) |
   | Stopping | "Stopped" (disabled) | `OFF` (grey) — the button reverts to *Start* the instant teardown confirms complete |
   | Failed | **Start** (enabled — the retry) | `FAILED — <reason>` (red) |

   A stopped gateway **stays stopped across a reboot**: the user's last Start/Stop
   decision is persisted, and the boot receiver only auto-starts when the last action
   was a Start. If a start fails only because the Location permission is not granted
   yet, the app asks for it automatically and retries the moment the grant confirms —
   a dispatched request never starts the gateway on its own.
4. **The WiFi network customers join.** One method, no mode picker: the phone becomes a
   WiFi Direct **group owner** (the NetShare technique) — a real AP legacy clients can
   join — while the phone's own WiFi stays connected the whole time, so it keeps
   **receiving** internet on `wlan0`/`ccmni` while **sending** it out on the group's
   interface. Before every `createGroup` attempt the P2P state is confirmed clean
   (any leftover group is removed and its absence verified — a leftover group is what
   answered `BUSY` on *every* attempt on the Hot 8), and the app turns WiFi and Location
   services on first: while either is off the framework refuses with a generic error
   that looks like a conflict but isn't one.
   The SSID is requested as `DIRECT-<your name>`; the **passphrase is fixed by the app
   (`rns-open-2026`) and is deliberately not secret** — the network can never be
   literally open on WiFi Direct (WPA is mandatory), but a known passphrase makes
   joining a single tap. The dashboard shows a **join card** with SSID, passphrase and
   a scannable QR, and the portal's login page carries the same card, so the customer
   never types a password: the **voucher is the real gate**.
5. When the hotspot comes up the app **keeps the address Android already assigned** (usually `192.168.43.1`). Replacing that with `10.66.0.1` is what left phones spinning on "Obtaining IP address". DHCP offers are sent as broadcasts, because MediaTek radios drop the unicast offer and the client never finishes DHCP. If a foreign DHCP server (e.g. Android's own tether dnsmasq) is holding the ports, the app **kills it and retries with a growing pause** (1 s, 2 s, then falls back to a DNS-only server so the portal keeps resolving) instead of silently ending up with no DHCP. After installing this update, tell users to **forget the Wi-Fi network and join again once**.
6. **Vouchers** tab: pick a preset (1 Hour / 3 Hours / 1 Day / 7 Days) or fill in plan name, duration and speeds, then *Generate*. Codes are copyable/shareable straight from the dialog; the list filters by status and each row can be expired or deleted.
7. **Users** tab: everyone currently on the LAN (online *with* a voucher vs *waiting at the portal*), saved user profiles - a name/phone/note per device MAC, recorded automatically the first time a device is seen - and session history.
8. **Settings** tab: the SSID (it becomes the `DIRECT-…` network name), WAN/LAN interface pins (blank = automatic), a *Detect* button that fills in what the phone currently has, and *Permissions* / *Debugger* shortcuts.
9. Check the raw state any time with `su -c 'sh /data/local/tmp/setup_network.sh status'` — or `… diag` for the full dump the debugger's **Full report** is built from.

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

**Dashboard → Debugger** (also Settings → *Debugger*, and the *Debugger* action on the
ongoing notification) opens a screen whose only job is to get the text out of the phone:
tap **Copy all** or **Share** and paste it into a chat. Nothing has to be selected by hand.

What it records, continuously and in order:

| Source | What you see |
| --- | --- |
| Every root command | `$ iptables -t nat -S …` → `exit 0, 43ms`, plus stdout/stderr. A rule that was *not* applied is visible instead of silent. |
| AP bring-up | The one method (WiFi Direct group owner), every `createGroup` attempt, what the framework answered (`onFailed reason=2` = BUSY decoded into words, including the leftover-group diagnosis), the removeGroup + clean-state confirmations, which interface appeared, which address was adopted, the SSID/password customers join. |
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
app being swiped away, an **IDLE launch** (the service deploys and checks, never
auto-starts), a **single Start/Stop button with a live status badge**, a gateway that
**stays stopped across a reboot** when the user stopped it, a permission gate that
only reacts to a *confirmed* grant, a watchdog that reports and repairs drift (findings
`H1`–`H10`), and a [debugger](#debugger) that records every command, callback and
finding as copyable text.

Hotspot bring-up: one method, no toggle — the phone becomes a WiFi Direct **group
owner** (the NetShare technique), created only on the user's Start tap, with the P2P
state confirmed clean before every attempt. The phone's own WiFi stays connected, so it
keeps receiving internet while sending it out. Joining is one tap: a fixed, openly
displayed passphrase (`rns-open-2026`) plus the QR on the dashboard and the portal login
page — the voucher is the real gate. What a given radio supports is a hardware question:
the debugger names the exact framework error when the group is refused
(see [AUDIT.md O6](AUDIT.md#7-still-open)).

Not built yet — see [AUDIT.md §7](AUDIT.md#7-still-open). The important remaining ones:
no per-session byte accounting, portal traffic is plaintext HTTP on the LAN, and no
rate limit on `/redeem`.

<!-- BEGIN GENERATED: pr-log -->
### Pull requests

_Regenerated automatically by `.github/workflows/readme.yml` — edit anything outside the markers instead._

**0** open · **8** merged · **0** closed without merging · updated 2026-09-24 05:24 UTC

| PR | Title | Author | Branch | State | Updated |
| --- | --- | --- | --- | --- | --- |
[#8](https://github.com/rizwanahmedsora9-pixel/RNS/pull/8) | Start the Hot 8 hotspot as root so the gateway leaves WAITING_AP | @arena-ai-coding-agent[bot] | `arena/01a0d1b5-rns` | 🟣 merged | 2026-09-24
[#7](https://github.com/rizwanahmedsora9-pixel/RNS/pull/7) | fix(build): repair Kotlin nullability errors blocking APK build | @arena-ai-coding-agent[bot] | `arena/01a0d192-rns` | 🟣 merged | 2026-09-24
[#6](https://github.com/rizwanahmedsora9-pixel/RNS/pull/6) | CI: preserve Android toolchain and build diagnostics | @arena-ai-coding-agent[bot] | `arena/01a0d189-rns` | 🟣 merged | 2026-09-24
[#5](https://github.com/rizwanahmedsora9-pixel/RNS/pull/5) | Master rebuild: stable NetShare engine (Phases 0-9) — fix Obtaining IP | @arena-ai-coding-agent[bot] | `arena/01a0d142-rns` | 🟣 merged | 2026-09-24
[#4](https://github.com/rizwanahmedsora9-pixel/RNS/pull/4) | Debugger that records everything (copyable text) + NetShare-style hotspot with no toggle | @arena-ai-coding-agent[bot] | `arena/01a0d011-rns` | 🟣 merged | 2026-09-24
[#3](https://github.com/rizwanahmedsora9-pixel/RNS/pull/3) | Fix clients stuck obtaining an IP with no captive portal | @arena-ai-coding-agent[bot] | `arena/01a0cfe2-rns` | 🟣 merged | 2026-09-23
[#2](https://github.com/rizwanahmedsora9-pixel/RNS/pull/2) | Admin UI (vouchers, user profiles), real hotspot control, foreground service, in-place updates | @arena-ai-coding-agent[bot] | `arena/01a0cfac-rns` | 🟣 merged | 2026-09-23
[#1](https://github.com/rizwanahmedsora9-pixel/RNS/pull/1) | Audit the AI-generated hotspot-billing drop, make it build, add APK/README/branch CI | @arena-ai-coding-agent[bot] | `arena/01a0cf69-rns` | 🟣 merged | 2026-09-23
<!-- END GENERATED: pr-log -->
