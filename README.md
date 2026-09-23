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
| `scripts/setup_network.sh` | **Source of truth** for NAT, captive-portal redirect, DHCP and MAC authorisation. |
| `scripts/bandwidth_control.sh` | **Source of truth** for per-client `tc`/HTB shaping (both directions). |
| `tools/run-script-selftest.sh` | Runs those two scripts against stub kernel commands and asserts the resulting ruleset. |
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
2. Launch the app. The gateway starts as a foreground service and the **Dashboard** shows live state: root, hotspot, WAN/LAN interfaces, portal, and an event log that names every command it runs - if something fails, the reason is on that screen.
3. **Turning the hotspot on.** On Android 12+ the app starts the Wi-Fi hotspot itself via `cmd wifi start-softap`. On Android 9/10 (the Infinix Hot 8) no root command exists for that, so the app says so on the dashboard and shows **Open Android hotspot settings** - flip the toggle there and the app detects the AP interface within ~2 seconds and takes over automatically (IP, DHCP, NAT, portal). It also re-arms itself if the hotspot is toggled off and on again.
4. When the hotspot comes up the app **keeps the address Android already assigned** (usually `192.168.43.1`). Replacing that with `10.66.0.1` is what left phones spinning on "Obtaining IP address". DHCP offers are sent as broadcasts, because MediaTek radios drop the unicast offer and the client never finishes DHCP. If Android's own DHCP server comes back and the two would fight, the app steps aside and lets the phone hand out addresses — the sign-in page still appears either way. After installing this update, tell users to **forget the Wi-Fi network and join again once**.
5. **Vouchers** tab: pick a preset (1 Hour / 3 Hours / 1 Day / 7 Days) or fill in plan name, duration and speeds, then *Generate*. Codes are copyable/shareable straight from the dialog; the list filters by status and each row can be expired or deleted.
6. **Users** tab: everyone currently on the LAN (online *with* a voucher vs *waiting at the portal*), saved user profiles - a name/phone/note per device MAC, recorded automatically the first time a device is seen - and session history.
7. **Settings** tab: hotspot SSID/password, WAN/LAN interface pins (blank = automatic), with a *Detect* button that fills in what the phone currently has.
8. Check the raw state any time with `su -c 'sh /data/local/tmp/setup_network.sh status'`.

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
auto-recorded per device MAC, and an event log that surfaces every root command.

Hotspot bring-up: automatic on Android 12+; on Android 9/10 it waits for the OS
hotspot toggle and configures itself the moment the interface appears (see Deploy).

Not built yet — see [AUDIT.md §7](AUDIT.md#7-still-open). The important remaining ones:
no per-session byte accounting, portal traffic is plaintext HTTP on the LAN, and no
rate limit on `/redeem`.

<!-- BEGIN GENERATED: pr-log -->
### Pull requests

_Regenerated automatically by `.github/workflows/readme.yml` — edit anything outside the markers instead._

**1** open · **2** merged · **0** closed without merging · updated 2026-09-23 20:51 UTC

| PR | Title | Author | Branch | State | Updated |
| --- | --- | --- | --- | --- | --- |
[#3](https://github.com/rizwanahmedsora9-pixel/RNS/pull/3) | Fix clients stuck obtaining an IP with no captive portal | @arena-ai-coding-agent[bot] | `arena/01a0cfe2-rns` | 🟢 open | 2026-09-23
[#2](https://github.com/rizwanahmedsora9-pixel/RNS/pull/2) | Admin UI (vouchers, user profiles), real hotspot control, foreground service, in-place updates | @arena-ai-coding-agent[bot] | `arena/01a0cfac-rns` | 🟣 merged | 2026-09-23
[#1](https://github.com/rizwanahmedsora9-pixel/RNS/pull/1) | Audit the AI-generated hotspot-billing drop, make it build, add APK/README/branch CI | @arena-ai-coding-agent[bot] | `arena/01a0cf69-rns` | 🟣 merged | 2026-09-23
<!-- END GENERATED: pr-log -->
