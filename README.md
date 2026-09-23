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

1. `adb install app-debug.apk`, then grant the app root when Magisk prompts.
2. Confirm the topology and pin it in `/data/local/tmp/hotspot.env` on the phone:
   ```sh
   su -c 'printf "WAN_IF=ccmni0\nLAN_IF=ap0\n" > /data/local/tmp/hotspot.env'
   ```
   `WAN_IF=auto` (the default) takes the interface that holds the default route. **Do not
   leave it on the old hardcoded `wlan0`** — `Hotspot .txt` shows `wlan0` never appears in
   `ip link`, while `ccmni0`/`ccmni1` (cellular PPP) carry the traffic.
3. Launch the app. It copies both scripts to `/data/local/tmp`, brings up NAT + DHCP +
   firewall, starts the shaper, and listens on `10.66.0.1:8080`.
4. Check it with `su -c 'sh /data/local/tmp/setup_network.sh status'`.

### How the gate works

Unauthenticated clients are dropped at `FORWARD`; only port 80 is DNATed to the portal, and
port 443 is reset so the client falls back to its plain-HTTP probe (DNATing TLS to a
plaintext server just yields certificate errors and no login sheet). On redemption:

1. a `dhcp-host=<mac>,<ip>` reservation is written and dnsmasq is `SIGHUP`ed, so the client
   actually receives the assigned address;
2. `ACCEPT` rules for that MAC+IP are inserted **above** the default-deny rule;
3. a `RETURN` in `nat/PREROUTING` exempts the MAC from the portal redirect;
4. an HTB class plus an ingress `police` filter cap download *and* upload.

Leases are 10 minutes so a client migrates to its reserved IP quickly; until then its old
address is also accepted so it does not go dark mid-switch.

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

Working: voucher redemption, single-device binding, static IP assignment, per-plan shaping
in both directions, captive-portal probes for Android/iOS/Windows.

Not built yet — see [AUDIT.md §7](AUDIT.md#7-still-open). The important ones: no foreground
service (the portal currently dies with the activity), no admin UI to mint vouchers, no
scheduled expiry sweep, no per-session byte accounting.

<!-- BEGIN GENERATED: pr-log -->
### Pull requests

_Regenerated automatically by `.github/workflows/readme.yml` — edit anything outside the markers instead._

**1** open · **0** merged · **0** closed without merging · updated 2026-09-23 18:38 UTC

| PR | Title | Author | Branch | State | Updated |
| --- | --- | --- | --- | --- | --- |
[#1](https://github.com/rizwanahmedsora9-pixel/RNS/pull/1) | Audit the AI-generated hotspot-billing drop, make it build, add APK/README/branch CI | @arena-ai-coding-agent[bot] | `arena/01a0cf69-rns` | 🟢 open | 2026-09-23
<!-- END GENERATED: pr-log -->
