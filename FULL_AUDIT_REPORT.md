# RNS — Full Development & Code Audit

**Repository:** `rizwanahmedsora9-pixel/RNS`
**Audited revision:** `bf7f3938a8998e529da436653219c4e626340ecc` (`main`, 2026-09-24 11:26:33 UTC)
**Audit date:** 2026-09-24
**Scope:** all 11 pull requests (development phases 1–11), the CI/CD setup, the repository
and branch process, and the current source tree (Kotlin, shell, resources, workflows).

---

## 0. Executive summary

RNS went from an unusable AI-generated zip drop to a coherent, root-driven Android hotspot
billing gateway in **17 hours across 11 pull requests**. The engineering that landed is
genuinely good in places — the shell-layer self-test, the root-command validation, the
captive-portal protocol handling and the debugger are above the standard for a project of
this size. CI is real and it caught real bugs.

But the audit found one thing that dominates everything else:

> **The only build of this project that has ever been proven to work on the target hardware
> is the one in pull request #9 — and PR #9 was never merged. It is now conflicted with
> `main`, and the conflict is architectural, not mechanical.**

PR #9's code is the good news: it is byte-identical to the commit that passed CI (`app/` and
`scripts/` trees hash equal) *and* it is the build the device log came from. Nothing about it
is untested. It is blocked purely because PRs #8, #10 and #11 landed underneath it.

Everything merged after PR #9's base (PRs #10 and #11 — the two largest refactors in the
project, ~4 300 changed lines) has **zero on-device verification**. The project's most
valuable asset is a 940-line device log that was produced by a build that is not on `main`.

Five findings are rated High or above:

| ID | Severity | Finding |
| --- | --- | --- |
| **F-01** | **High** | Root command injection through the hotspot SSID/passphrase field — proven with a working PoC |
| **F-02** | **High** | PR #9 (the only device-validated work) is stranded: conflicted with `main` on an architectural question, not on trivia |
| **F-03** | **High** | `main` has no on-device verification at all; PRs #10 and #11 are unvalidated on hardware |
| **F-04** | **Medium-High** | Private APK signing key committed to the repo with its password in plaintext in `app/build.gradle` |
| **F-05** | **Medium-High** | `main` did not compile for ~3 h 50 m; 22 of 37 APK CI runs failed; 3 PRs were merged red |
| **F-20** | **High** | A process that merely *mentions* the dnsmasq path on its command line is mistaken for Android's DHCP server; `keepalive()` then kills ours. Found + **fixed** in the Linux e2e run (§5.5) |

**Recommended immediate action:** resolve PR #9 (rebase onto `main` and re-run CI), install
the resulting APK on the Infinix Hot 8, and fix F-01 before the app is used on a live
network. Details in §8.

Since this report was first written, `tools/linux-e2e/` (a real-kernel test environment with a
real dnsmasq and a real test client) was built and the full customer journey was exercised:
**65 passed, 0 failed, 3 skipped**. It found and the project has since **fixed** two further
defects — F-20 (High) and F-21 (Medium) — both in `scripts/setup_network.sh`, each locked in by
a regression test. See §5.5 and `LINUX_E2E_RESULTS.md`.

---

## 1. What was audited, and how

| Artefact | Size at `bf7f393` |
| --- | --- |
| Kotlin, `app/src/main/java` | 10 804 lines (45 files) |
| Kotlin, `app/src/test/java` | 1 390 lines (13 test classes) |
| Shell, `scripts/` | 1 961 lines (`setup_network.sh` 1 443, `netshare_ap.sh` 363, `bandwidth_control.sh` 155) |
| Gradle / CI | `apk.yml`, `readme.yml`, `branch-cleanup.yml` |
| Docs | `README.md` 18 KB, `AUDIT.md` 23 KB, `PROJECT_AUDIT.md` 28 KB, `IMPLEMENTATION.md` 11 KB |
| Device evidence | `Debuggerfitst semi success.txt` (940 lines), `Hotspot .txt` (terminal capture) |

**Methods used**

- Full PR archaeology: `gh pr view` for all 11 PRs (metadata, bodies, diffs), commit
  archaeology on all 43 commits of `main` and all 35 commits of PR #9's branch
  (the sandbox clone was shallow at depth 1; I unshallowed it after noticing it produced a
  false "no merge base" result).
- CI forensics: 37 APK workflow runs, per-branch success/failure tallies, check-runs on
  individual `main` commits, and the diff of the commit that documents the compile errors.
- Static review of the Kotlin and shell sources, focused on the root-privileged path,
  the voucher/billing path and the captive portal.
- **Executed verification:** `bash tools/run-script-selftest.sh` — **ALL CHECKS PASSED**
  (~30 s, 40+ assertions against stub `iptables`/`ip`/`tc`/`dnsmasq`).
- **Executed PoC:** a shell-injection proof against the exact command string the app builds
  (§4.1).

**What I could not do:** this sandbox has no JDK, no Android SDK and no route to Maven
Central, `services.gradle.org` or `dl.google.com`, so **I could not compile the Kotlin or
run the unit tests**. Build/test status in this report is taken from the project's own CI,
not from my own compile. (I did install a JDK 25 via `jdk4py`; it is useless without the
Android SDK and the Maven artifacts.)

---

## 2. Development-phase review (PRs #1–#11)

Timeline: 2026-09-23 18:25 UTC → 2026-09-24 11:26 UTC. 10 merged, 1 open.

| # | Title | Merged (UTC) | +/− | Files | CI runs on branch (failed) | Post-merge `main` build | Device evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Audit the AI-generated drop, make it build, add APK/README/branch CI | 09-23 18:52 | +3121 −1 | 40 | 4 (3) | n/a | no |
| 2 | Admin UI, real hotspot control, foreground service, in-place updates | 09-23 19:45 | +2542 −101 | 30 | 2 (1) | green | no |
| 3 | Fix clients stuck obtaining an IP with no captive portal | 09-23 20:54 | +1269 −298 | 18 | 1 (0) | green | no |
| 4 | Debugger that records everything + NetShare-style hotspot, no toggle | 09-24 02:28 | +5727 −91 | 36 | 2 (1) | green | no |
| 5 | Master rebuild: NetShare engine (Phases 0–9) | 09-24 03:46 | +2432 −25 | 19 | 1 (1) | **red** | no |
| 6 | CI: preserve Android toolchain and build diagnostics | 09-24 03:59 | +47 −13 | 2 | 1 (1) | **red** | no |
| 7 | fix(build): repair Kotlin nullability errors blocking APK build | 09-24 04:17 | +8 −3 | 2 | 1 (0) | green | no |
| 8 | Start the Hot 8 hotspot as root so the gateway leaves WAITING_AP | 09-24 05:24 | +1241 −153 | 20 | 1 (1) | **red** | no |
| 9 | **One control surface, one method: idle launch, clean P2P, one-tap join** | **not merged** | +1515 −1730 | 26 | 6 (5) | — | **yes** ✅ |
| 10 | Kill root-shell contention — fast start, clean stop, verified leftovers | 09-24 09:14 | +1759 −383 | 20 | 4 (2) | green | no |
| 11 | Only adopt interfaces that are really beaconing + one dark console | 09-24 11:26 | +2587 −529 | 46 | 3 (2) | green | no |

### 2.1 Phase-by-phase assessment

**PR #1 — Bootstrap and first audit (merged).** Converted a chat transcript plus a zip into
a real Gradle project and produced `AUDIT.md`: 6 build blockers, 5 "silently does nothing"
bugs, 1 authentication bypass. The headline findings were real and serious — a default-deny
`FORWARD` policy immediately followed by a blanket `ACCEPT` (anyone on the LAN had free
internet), static IPs that were never handed to clients so every `tc`/`-s <ip>` rule matched
nothing, HTTPS DNATed to a plaintext server, and an invalid `tc filter del`. Also added the
three workflows. **Verdict: delivered as described.** The only caveat is that it took 4 CI
runs (3 failures) to get green.

**PR #2 — Admin UI and service lifecycle (merged).** Four-tab console (dashboard, vouchers,
users, settings), foreground service, boot restart, and the single most practically useful
change in the project: one committed signing key so APKs install as in-place updates.
**Verdict: delivered.**

**PR #3 — "Obtaining IP address" fix, first attempt (merged).** Stopped forcing `10.66.0.1`
over Android's `192.168.43.1`, answered captive-portal probes with HTTP 200 on the same host,
and taught the app to step aside when the phone's DHCP server wins. **Verdict: delivered.**
Note that this phase's diagnosis was later revised twice (PR #5, then PR #11) — the
"obtaining IP" symptom had more than one cause, and the team kept hunting. That is the right
behaviour, but it means this PR did not close the issue it names.

**PR #4 — Debugger and NetShare-style AP (merged, +5 727 lines).** The debugger
(`app/…/debug/`) is the best part of this codebase: a 6 000-line ring buffer plus file log,
every root command logged with exit code and duration, a logcat mirror that deliberately
uses `Runtime.exec` rather than the shared libsu shell (so a streaming command cannot queue
every other root command behind it), coded findings H1–H10, and an 11-section diagnostic
report with one-tap copy/share. `ApLauncher` gained five AP-creation methods.
**Verdict: delivered.** The `LogcatWatcher` design note in particular shows real care.

**PR #5 — "Master rebuild", Phases 0–9 (merged, but merged red).** Added the `core/`
package: `WanDetector`, `DhcpManager`, `NatManager`, `DnsManager`, `FirewallManager`,
`NetworkController`, `WatchdogManager`, `DeviceManager`, `UsageMonitor`, `BillingManager`,
`PrinterManager`; bumped Room to v3 with six new tables. **Verdict: structure delivered,
quality mixed.** Two problems:
- It was merged while its CI build was **failing**, and the post-merge `main` build was also
  red. PRs #6 and #7 were the cleanup.
- Several "phases" were declared *Completed* in `IMPLEMENTATION.md` on the strength of being
  written, not of working. Phase 9 (printing) is a 106-line stub that is never called
  (§5.2); Phase 8's voucher-code description is factually wrong (§6.2).

**PR #6 — CI diagnostics (merged, merged red).** Small, useful: captures Java/Gradle/SDK
versions, always uploads a diagnostics artifact. Merged while red because it was *about* CI.
**Verdict: delivered.**

**PR #7 — Nullability build fix (merged).** +8 −3 lines. Its entire PR body is the string
`test-pr`. **Verdict: delivered, but the project's record for this phase is two words.**
Low impact on its own; it matters because it is the commit that turned `main` green again.

**PR #8 — Root `startSoftAp` on Android 9 (merged, merged red).** Correct diagnosis: on the
Hot 8 nothing in the app's uid can start the AP, and killing Android's tether dnsmasq makes
the framework tear the AP down — so leave it running. **Verdict: diagnosis delivered,
process failed.** This PR left `main` non-compiling (see §3.2), and it was merged anyway.

**PR #9 — One control surface, one method (OPEN — the critical finding).**
This is the PR that implements the operator's 12-item problem list: boot IDLE, a single
Start/Stop button, an honest status badge, a persisted `manually_stopped` flag, P2P state
cleaned before every attempt, stale-interface cleanup, dnsmasq backoff, a confirmed-location
gate, and one-tap join.
**It is also the only build ever proven to work on the device.** See §3.1.
**Verdict: the right work, stranded.**

**PR #10 — Root-shell contention (merged, no device test).** The best analysis in the
project: it measured 142 root commands / 170.7 s of shell time in 7 minutes from the device
log and traced it to libsu serialising one shell's jobs. The fix is a single choke point
(`RootShell` with a gate, a batched 19-field `probe`, caches, `tryRun()`), parallel
DHCP ∥ NAT on start, `EmergencyCleaner` for verified teardown, and a cheaper watchdog tick.
**Verdict: delivered and well-reasoned — but the PR itself admits the Kotlin was never
compiled by its author, and CI found two compile errors in it (`RootShell` used
`IFACE_REGEX`/`IP_REGEX`/`MAC_REGEX`/`SUBNET_REGEX`/`POLL_WAIT_MS` before declaring them;
`WifiShareAp` passed a `List` where a `Set` was declared). No device test has been run
against it.**

**PR #11 — Evidence-based AP adoption and dark console (merged, no device test).** The
second-best analysis: `p2p0` on this MediaTek build is created and brought UP as soon as
WiFi is on and stays UP after the group is removed, so link-up is not evidence of an AP —
the gateway had been configuring DHCP/NAT/firewall on a dead interface and reporting
success. `ApEvidence` now requires framework state, a group we own, our own hostapd runtime,
or a wired link. Plus a full UI rewrite to a forced-dark console.
**Verdict: delivered, same caveats as #10.** Again required two `fix(ui):` commits after CI
rejected hard-coded AppCompat style parents.

---

## 3. Process and repository health

### 3.1 F-02 (High) — the only device-validated build is not on `main`

`Debuggerfitst semi success.txt` is the only end-to-end on-device record in the repository.
Its header says:

```
exported  : 2026-09-24 12:44:20.402  (+0500 → 07:44 UTC)
device    : INFINIX MOBILITY LIMITED Infinix X650C / Android 9 (API 28)
app 1.0.27+f35d949
phase     : RUNNING
ap        : WiFi Direct group (NetShare) ssid=DIRECT-5O-Infinix HOT 8
lan/wan   : p2p0 / ccmni0 gateway=192.168.49.1 dhcp=ours
findings  : none
```

That build is `1.0.27+f35d949`. `versionName` is produced by CI as
`1.0.${GITHUB_RUN_NUMBER}+${GITHUB_SHA::7}` (`.github/workflows/apk.yml`), so run number 27,
commit `f35d949`. I resolved `f35d949` through the API:

```
f35d94921f0573957d7c842f0cf9b599fc1039c8 | 2026-09-24T06:55:57Z
  | Merge 4414333b22462e9ad74c38fc3053710750e05711 into de0d96b3ac45304e81ae11d04da09aef7d94790c
```

That is **GitHub's test-merge commit for PR #9** (head `4414333`, base `de0d96b`). Confirmed
independently by the log contents: the strings

```
service IDLE - the gateway only starts when the user taps Start (ACTION_START)
service started without an action - staying IDLE (Start is the user's tap)
```

exist **only** in PR #9's branch (`HotspotService.kt:179` and `:202` at `51a91ff`) and do not
exist anywhere in `main` (verified by grep).

**What that log proves works** (on PR #9, not on `main`):

```
+80s  nat: internet check via ping 8.8.8.8 -> true
+80s  network: START SUCCESS WAN=ccmni0 LAN=p2p0 gateway=192.168.49.1
+81s  captive portal listening on 0.0.0.0:8080
+142s portal: NEW CLIENT 192.168.49.10 - first contact
+258s redeem "HCSQ-KEBE" (abc, UNUSED) from mac a4:4e:31:83:ec:3c ip 192.168.49.10
+260s redeem "HCSQ-KEBE" -> Success ... expires 2026-09-24 13:01:22
```

Start → DHCP → NAT → internet → client → portal → voucher redemption, end to end, on real
hardware, `findings: none`. It also shows the churn PR #10 later targeted: the *same*
voucher was redeemed four more times in the following 28 seconds.

**Current state of PR #9:** `mergeable_state: dirty` (conflicted). It branches from
`de0d96b`; since then `main` has landed PRs #8, #10 and #11. Critically, PR #9 **deletes**
`net/ApPlan.kt` and renames `net/ApMode.kt` → `net/ApHandle.kt`, while PRs #10 and #11
**depend on** `ApPlan.Step`, `ApPlan.AP_CANDIDATES` and the `ApMode` enum (PR #11's
"remembered method" feature is built on `ApPlan.Step`). The two designs are not
reconcilable by a text merge — a human has to decide which architecture wins.

**The code in PR #9 is fully tested — do not read "unmerged" as "unproven".** Its head
`51a91ff` differs from the CI-green commit `4414333` by exactly one line, and that line is in
`README.md` (the auto-generated PR log):

```
$ git diff --stat 4414333 51a91ff
 README.md | 2 +-
 1 file changed, 1 insertion(+), 1 deletion(-)

$ git rev-parse 4414333:app      → 104a1be3bc6d794fadbe070045c6643fb18380b4
$ git rev-parse 51a91ff:app      → 104a1be3bc6d794fadbe070045c6643fb18380b4   (identical)
$ git rev-parse 4414333:scripts  → f432a7a7264c99fe222f2ba8ab6b9b2443b40494
$ git rev-parse 51a91ff:scripts  → f432a7a7264c99fe222f2ba8ab6b9b2443b40494   (identical)
```

So the branch that ran green in CI (`35966927783`, 06:55 UTC) and the branch that ran on the
Infinix at 07:44 UTC are the same `app/` and `scripts/` trees. The PR is blocked solely by
merge conflicts, and the conflicts are the point: `main` has since committed to the
`ApPlan`/`ApMode` architecture, and PR #9 deletes both.

**One thing PR #9 does cover that is not on `main` either:** PR #9 branched from `de0d96b`,
the commit immediately after PR #8 merged, so the device log also validates PR #8's
root-`startSoftAp` change as part of that build. PR #8 is therefore the last change with any
device evidence behind it.

### 3.2 F-05 (Medium-High) — `main` was red, and PRs were merged red

APK workflow outcomes across the project: **37 runs, 22 failures, 15 successes (59 % red).**

Post-merge `main`-push build results, in order:

| `main` commit | What landed | Build APK |
| --- | --- | --- |
| `b71d5f5` | PR #2 | green |
| `7b81155` | PR #3 | green |
| `b47ecff` | PR #4 | green |
| `439ff92` | Merge PR #5 | **failure** |
| `8e8b242` | PR #6 (CI fix) | **failure** |
| `4cf1f91` | Merge PR #7 | green |
| `40c6c85` | Merge PR #8 | **failure** |
| `dfe5a8e` | *Add files via upload* (device log) | **failure** |
| `8273c1a` | Merge PR #10 | green |
| `4470f0d` | Merge PR #11 | green |

`main` therefore **did not compile from 05:24 UTC (PR #8) until 09:14 UTC (PR #10) — about
3 h 50 m** — and was also broken 03:46 → 04:17. The cause is documented in the project's own
commit `90feb39`:

> `WifiShareAp.kt:328,391` — `interfaceNames()` returns `Set<String>` while `handleFromGroup()`
> and `discoverInterface()` declared `List<String>`. **This one was pre-existing: the same two
> errors are what failed the APK build on `main` (commit `dfe5a8e`, check-run annotations), so
> `main` did not compile at all before this branch.**

**PRs #5, #6 and #8 were merged while their build was failing.** There is no branch
protection requiring a green build.

### 3.3 F-03 (High) — `main` has never been verified on hardware

Every merged PR lists its device tests as unchecked, and honestly so. PR #10's body says it
plainly: *"the sandbox this branch was written in has no JDK, Gradle or `kotlinc` and no
network, so the Kotlin was **not compiled or run** by the author"*, and *"device tests 1–7 of
the master plan are still outstanding and are not claimed"*.

Consequences:

- The two largest merged changes — PR #10 (root-shell contention; 20 files) and PR #11
  (evidence-based adoption + full UI rewrite; 46 files) — have **no device evidence
  whatsoever**.
- PR #10 claims START fell from 74 s; PR #11 claims the 70 s pre-start delay is gone. Both
  are reasoned from logs, neither is measured on a device running that build.
- PR #11 changed the UI wholesale (theme, layouts, navigation) and needed two follow-up
  `fix(ui):` commits for style parents that do not exist in AppCompat. Those were caught by
  the *build*, not by anyone looking at a screen. Nobody has seen this UI render.
- The single most important open question in the project — *which AP method does this
  MediaTek radio actually accept* — was answered empirically by PR #9's build
  (WiFi Direct group, `dhcp=ours`, `findings: none`) and that answer is not on `main`.

### 3.4 Lower-severity process findings

| ID | Sev | Finding |
| --- | --- | --- |
| F-12 | Low | Two commits were made directly to `main` through the GitHub web UI (`9484a50`, `dfe5a8e` — "Add files via upload"), bypassing PR review and CI gating. `dfe5a8e` is the device log, so it is harmless content-wise, but the path exists. |
| F-13 | Low | `branch-cleanup.yml` deletes **every** remote branch except `main`/default/`KEEP_BRANCHES`/trigger, on a 6-hourly cron. Open-PR branches are spared only by a default-off flag (`include_open_pr_branches`), which a manual run can flip. Combined with squash merges this leaves no recoverable record of merged work other than `main` and the PR descriptions. |
| F-14 | Low | `testOptions { unitTests.returnDefaultValues = true }` (`app/build.gradle`) makes unmocked Android calls return dummy values instead of throwing, so unit tests can pass against behaviour that would crash on a device. |
| F-16 | Info | `build.gradle` defaults `versionCode 3` / `versionName 1.2`; CI overrides both with the run number. A locally built APK therefore reports as an *older* version than any CI build and will not install over one. |
| F-18 | Info | PR #7's body is `test-pr`. The phase that restored a green `main` has a two-word record. |

---

## 4. Security audit

### 4.1 F-01 (High) — Root command injection via the hotspot SSID / passphrase

**The app interpolates operator-supplied SSID and passphrase text directly into commands that
run as uid 0, and the sanitisation applied is both incomplete and inconsistent between the two
call sites that do it.**

`app/…/net/ApLauncher.kt:325–330` (reachable from **Automatic** mode and from the
**Root hostapd** mode):

```kotlin
private fun tryRootHostapd(ssid: String, pass: String, log: (String) -> Unit): ApHandle? {
    val safePass = ApConfigText.sanitizePassphrase(pass)
    val safeSsid = ssid.replace(Regex("[\"'\\\\]"), "").ifBlank { "RNS-Hotspot" }
    val res = RootShell.run("sh $NETSHARE start \"$safeSsid\" \"$safePass\"")
```

- `safeSsid` removes `"`, `'` and `\` — **but not `$` and not `` ` ``**. Both survive into a
  double-quoted shell word, where the shell performs command substitution.
- `sanitizePassphrase` (`app/…/net/ApMode.kt:181–190`) removes only non-printable ASCII:

  ```kotlin
  val clean = (requested ?: "").replace(Regex("[^\\x20-\\x7E]"), "").trim()
  ```

  `"`, `$` and `` ` `` all survive. A single `"` in the passphrase breaks out of the quoting
  outright.
- `RootShell.run()` (`util/RootShell.kt:136`) performs **no** validation; it passes the string
  straight to `Shell.cmd(cmd).exec()`. Its `IFACE_REGEX` / `IP_REGEX` / `MAC_REGEX` /
  `SUBNET_REGEX` guards (declared at lines 101–110 and applied at 337, 385, 413–414, 482–484,
  490, 496–497, 502, 557, 569, 574, 662) cover interface names, addresses and MACs — **never
  SSID or passphrase**. The file's own comment at line 96 claims these patterns "are the only
  reason `setup_network.sh reserve "$mac" "$ip"` cannot carry a `;`", which is true for the
  arguments it guards and false for the ones it does not.
- **The inconsistency is the clearest evidence it is a bug, not a decision.** The other root
  caller, `SoftApController`, gets this right (`net/SoftApController.kt:582`):

  ```kotlin
  /** Drops every character that could terminate a double-quoted shell string. */
  private fun shellSafe(value: String): String = value.replace(Regex("[\"`$\\\\]"), "")
  ```

  Two callers build commands for the same root shell; one strips `` "`$\ `` and one does not,
  and the weak one is on the `ROOT_HOSTAPD` path.

**Proof.** Reproducing the exact command string the app builds, with exactly the sanitisation
that code applies, and evaluating it the way the root shell would:

```
built command: sh /data/local/tmp/netshare_ap.sh start "$(id > /tmp/pwned2.txt)EVIL" "hotspot123"
[stub netshare_ap.sh] argv: start EVIL hotspot123
>>> PAYLOAD EXECUTED (as user):
uid=1001(user) gid=1001(user) groups=1001(user),27(sudo),100(users)
```

The stub received `start EVIL hotspot123` **and** the payload ran. In the app the same
command is executed by libsu as **root**.

**Exploitability.** Not remote: the SSID/passphrase come from Settings (`MainActivity.kt:610–611`
calls `.putString(KEY_SSID, etSsid.text.toString().trim())` with no validation at all) and are
stored in SharedPreferences. So this is a local/self-inflicted footgun rather than an
attacker-controlled RCE — but it is a footgun wired to a **root** shell, reachable by anyone
who can type in the Settings tab, and a stray `$`, backtick or quote in an SSID is a plausible
accident. Rated High because of the privilege level, with exploitability stated precisely.

**Fix.** Route every SSID/passphrase through one `shellSafe()` (or better, stop building
command strings: pass arguments through the script's positional parameters with proper
quoting and validate against `^[A-Za-z0-9 _.-]{1,32}$` / printable-ASCII-without-shell-metacharacters).
Constrain the `EditText`s in Settings with `android:digits`/input filters. Add a unit test
asserting that `$(`, `` ` ``, `"`, `\`, `;` and newline cannot survive.

**Note, checked and cleared:** `scripts/netshare_ap.sh write_conf()` writes `ssid=$SSID` and
`wpa_passphrase=$PASS` inside an **unquoted** heredoc (`<<EOF`). I tested this specifically
(`write_conf "rnsap0" '$(id > /tmp/pwned.txt)EVIL' …`) and the payload did **not** execute:
shells do not re-scan the result of parameter expansion for command substitution, so the
value is written literally. It is a config-corruption hazard, not an injection sink. The
injection is entirely at the Kotlin command-construction site.

### 4.2 F-04 (Medium-High) — committed private signing key with a plaintext password

`keystore/hotspot-billing.p12` is committed (explicitly un-ignored in `.gitignore`), and
`app/build.gradle` contains:

```groovy
storeFile = sharedKeystore
storeType = 'PKCS12'
storePassword = 'rns-hotspot-billing'
keyAlias = 'rns-hotspot'
keyPassword = 'rns-hotspot-billing'
```

The trade-off is documented in-file and in the README, and the reason is sound: random debug
keys on CI runners are what caused "App not installed" on every update. But the consequence
is that **anyone who can read this repository can sign an APK that Android will accept as an
update to the installed app** — i.e. full code execution on every device running it, plus
access to the voucher database.

The comment already says *"if this is ever distributed publicly, move the key to CI secrets
instead."* This audit's recommendation: the app is heading for distribution (it is a billing
gateway for paying customers), so do it now. Move the keystore and both passwords to GitHub
Actions secrets, keep the debug *fallback* as the local `~/.android/debug.keystore`, and
rotate the key.

### 4.3 F-09 (Medium) — captive portal binds `0.0.0.0:8080`

`CaptivePortalServer` listens on all interfaces (`:8080`), while the redirect and the
`HS_IN` ACCEPT rules are correctly scoped to `-i $LAN_IF` (`scripts/setup_network.sh:522`,
`539`). `HS_IN` only ever *accepts*; it contains no DROP, so whether the WAN side can reach
the portal depends on Android's default `INPUT` policy, which on many builds is ACCEPT.

Impact today is limited: redeem requires `ArpResolver.macForIp(clientIp)` to resolve, which
fails for a WAN-side source, so a remote visitor can fetch the login page but not redeem.
On a WiFi uplink (repeater mode) that still means the portal is reachable by everyone on the
upstream network.

**Fix:** bind to the LAN gateway address instead of `0.0.0.0`, or append
`iptables -A HS_IN -i $WAN_IF -p tcp --dport 8080 -j DROP` and the IPv6 equivalent.

### 4.4 F-10 (Medium) — no rate limiting on voucher redemption; MAC-based auth is spoofable

`CaptivePortalServer.handleRedeem()` applies no rate limit, lockout or attempt logging. The
keyspace is adequate — `VoucherCodes` generates 8 characters from a 32-character alphabet
(32⁸ ≈ 1.1 × 10¹², and it correctly drops `0/O` and `1/I`) — so brute force is slow, but
nothing stops it and nothing records it.

Separately, the entire access model is MAC-based (`authorize <mac> <ip>` → `-m mac
--mac-source`), and MAC addresses are trivially spoofable by any client that can see a paying
device. For a billing system this is worth stating as an accepted limitation.

**Fix:** add a per-IP/per-MAC attempt counter with exponential backoff, log rejected
attempts, and document MAC spoofing as an accepted risk (or move to a portal-session token).

### 4.5 F-11 (Medium) — destructive database migration on a database that now holds money

```kotlin
.fallbackToDestructiveMigration()
```

(`db/Database.kt`, Room v3). When this was introduced the justification was *"no device in the
field holds data worth a hand-written migration yet"*. That justification expired the moment
the app ran on the Infinix: the database now holds generated vouchers, active bindings,
client profiles and session history. Any future schema bump without an explicit `Migration`
will **silently wipe every sold voucher**.

**Fix:** write explicit `Migration` objects for v3→v4 onward and delete
`fallbackToDestructiveMigration()`; also set `exportSchema = true` (currently `false`) so
Room can validate schemas.

### 4.6 Security findings that came back clean

These were checked and are correct — they are recorded so the next audit does not re-litigate
them:

- **Shell argument validation for interfaces, IPs, MACs and subnets** is real and applied at
  every call site that takes them (`RootShell.kt` — 11 `require`/`matches` sites). This covers
  the values that reach the shell from DHCP leases and ARP, which are the ones an attacker on
  the LAN can influence.
- **HTML escaping is implemented.** `PortalPages.escapeHtml()` replaces `& < > "` (it is a
  chained expression that a naive grep reads as an identity function — it is not).
  `errorHtml()` is only ever called with hardcoded literals today, so there is no current XSS,
  and the escaping is there if that changes.
- **Captive-portal protocol handling is right**: probes answered with HTTP 200 on the same
  host rather than a redirect to `:8080`; gzip disabled because a gzipped 200 is a failed
  probe on several Android builds; `Connection: close` forced; port 443 `REJECT`ed with
  tcp-reset so clients fall back to their plain-HTTP probe (this is the fix for AUDIT.md's
  original F9).
- **Default-deny ordering is correct** and asserted by the self-test: per-client `ACCEPT` →
  established return traffic → `REJECT tcp/443` → `DROP`, with `authorize` inserting via
  `ensure_top` (insert at 1, guarded by a `-C` existence check) so new authorisations always
  land above the deny. The original authentication bypass (AUDIT.md F7) is genuinely gone.
- **Manifest hygiene**: `allowBackup="false"`; `HotspotService` and `DebugActivity` not
  exported; the `FileProvider` not exported; `BootReceiver` is exported but only for the
  protected `BOOT_COMPLETED` broadcast.
- **Shaper rebuild design** (AUDIT.md F10) is sound: `tc` state is kept in a file and the
  whole HTB tree is rebuilt idempotently, so there is no dangling-filter state; both
  directions are shaped (F11 fixed).
- **The `DebugActivity` logcat reader uses `Runtime.exec`, not the shared libsu shell** — a
  deliberate and correct choice, or a streaming command would block the entire gateway.

---

## 5. Correctness and dead-code audit

### 5.1 F-06 (Medium) — per-session data usage is always zero

`core/UsageMonitor.kt:105–110`:

```kotlin
// Also update session bytes if there's an open session
val openSessions = db.sessionDao().getOpenByMac(stat.mac)
openSessions.forEach { session ->
    // Update session with latest bytes (we don't have exact per-session, use total for now)
    // Note: session close will set final bytes
}
```

This is an empty loop — it fetches rows and does nothing with them. The only writer of
`sessions.bytesUp/bytesDown` is `VoucherManager.kt:247`:

```kotlin
db.sessionDao().close(session.id, now, session.bytesUp, session.bytesDown)
```

which writes back the values **read from the row itself**, and nothing ever writes them. So
every row in the session history records 0 bytes up and 0 bytes down, permanently. Per-client
totals are fine (`clientDao().updateUsage(...)` at `UsageMonitor.kt:90`), so the Clients screen
works; only the session history is structurally empty.

This is the resurfacing of AUDIT.md's original finding O4 ("`bytesUp/Down` never populated").
PR #5 claimed Phase 7/Phase 11 closed it.

**Fix:** have `UsageMonitor` write per-session deltas (or delete the `bytesUp/bytesDown`
columns and the dead loop, and make the session history show the client's totals at the time).

### 5.2 F-07 (Medium) — dead code shipped as "completed" features

| Artefact | Lines | Uses outside its own file | Note |
| --- | --- | --- | --- |
| `core/NetworkEngine.kt` | 40 | **0** (only a comment in `NetworkController`) | Created by PR #5 as "alias for `NetworkController` per master plan". Never instantiated. |
| `core/PrinterManager.kt` | 106 | **0** | `printViaBluetooth()` and `printViaUsb()` are `// TODO` stubs that `return false`. There is no Print action anywhere in the UI. `IMPLEMENTATION.md` Phase 9 is marked **Completed**. |
| `dhcpLeaseDao` / `dhcp_leases` | — | **0** | Table and DAO created in PR #5 (Room v3); never read or written. |
| `settingDao` / `settings` | — | **0** | Same. |

`IMPLEMENTATION.md` Phase 9 (PRINTING) is marked *Completed* on the strength of a file that
is never called and whose two functional methods return `false`.

**Fix:** delete `NetworkEngine.kt`; either wire printing into the voucher row actions (share
text already works via `generateShareText`, which is the part users actually need) or move it
out of "Completed" into an explicit roadmap; drop the two unused tables, or use them.

### 5.3 F-19 (Medium) — PR #9's one-tap join QR advertises the wrong password

*This defect is in the unmerged PR #9, not in `main` — but it must be fixed before PR #9 is
merged, and it is a defect in the feature the PR is named after.*

PR #9 declares a fixed, deliberately public passphrase (`net/JoinConfig.kt:17`,
`FIXED_PASSPHRASE = "rns-open-2026"`) and renders a `WIFI:` QR from it:

```kotlin
fun wifiQrPayload(ssid: String): String =
    "WIFI:T:WPA;S:${qrEscape(ssid)};P:$FIXED_PASSPHRASE;;"
```

The problem is that on the target device the passphrase **cannot be set**, so Android generates
one. The device log says so explicitly:

```
+7s  ap[p2p]: could not set passphrase (NoSuchFieldException) - Android picks it; read back afterwards
+7s  ap[p2p]: group formed - "DIRECT-5O-Infinix HOT 8" / kamm4fFC (group owner confirmed)
+8s  ap: UP in 1s - Join WiFi "DIRECT-5O-Infinix HOT 8" password "kamm4fFC"
```

The app then handles the two outputs inconsistently:

| Surface | Value shown | Correct? |
| --- | --- | --- |
| Dashboard join-card text (`MainActivity.kt:445`) | `state.apPassword ?: FIXED_PASSPHRASE` → **`kamm4fFC`** | ✅ reads back the real credential |
| Dashboard QR (`MainActivity.kt:452` → `wifiQrPayload`) | `P:rns-open-2026` | ❌ hardcoded constant |
| Portal login page QR (`CaptivePortalServer.kt:42`) | `P:rns-open-2026` | ❌ hardcoded constant |

So on Android 9 a customer who **scans the QR** is handed `rns-open-2026` and cannot join,
while the text next to the QR shows the password that does work. The whole premise of the
"one-tap join" is defeated on exactly the hardware this project targets.

Note that PR #10's body already identified this class of problem and scoped it out:

> *"Honest credentials: Android 9 blocks `setNetworkName`/`setPassphrase` and
> `createGroup(config)` (`NoSuchFieldException` / `NoSuchMethodException` in the log), so WiFi
> Direct cannot be renamed. Plan: prefer the path that **can** set them (root `hostapd`), and
> always show the **real** active SSID/password in the UI instead of a requested one."*

**Fix:** make `wifiQrPayload(ssid, passphrase)` take the read-back passphrase as a parameter and
pass `state.apPassword` at both call sites; fall back to `FIXED_PASSPHRASE` only when no
network is up. Add a unit test asserting the QR payload matches the password displayed beside it.

### 5.4 What the code does well (verified)

- **The shell layer is genuinely tested.** `tools/run-script-selftest.sh` runs the real
  `setup_network.sh` and `bandwidth_control.sh` against stub `iptables`/`ip6tables`/`ip`/`tc`/
  `dnsmasq`/`sysctl` and asserts the resulting ruleset. I ran it: **ALL CHECKS PASSED**, with
  assertions covering probe keys, cleanup of an untracked (orphan) dnsmasq, removal of the
  port-67 `OUTPUT` guard, `nat` re-install without a DHCP restart and without duplicate
  redirects, idempotent `keepalive`, and `purge-state`. This is the strongest safety net in
  the project and it is wired as a CI gate before the build.
- **Voucher lifecycle handling is careful**: `ensureRestored()` re-seeds the in-memory IP pool
  and tc class-id counter from the database after a process restart (otherwise a restart would
  hand out IPs that are already bound) — a real bug class that is easy to miss.
- **The re-apply cooldown** (`REAPPLY_COOLDOWN_MS`, 20 s) directly addresses churn measured in
  the device log (one voucher redeemed 4× in 28 s).
- **CI is better than most projects of this size**: shell self-test gates the build, unit
  tests, lint, both APK variants, a retained diagnostics artifact on failure *and* success,
  and a PR comment with the artifact name. The failure-summary and annotation steps exist
  specifically because a previous failure left nothing downloadable — that is a team learning
  from its own incidents.

### 5.5 Findings from the Linux end-to-end environment (F-20, F-21) — both fixed

After this audit, `tools/linux-e2e/` was built: a real-kernel test environment (three network
namespaces = client / gateway / "the internet", a real dnsmasq 2.90 built from source, the real
`setup_network.sh` and `bandwidth_control.sh`, a Python stand-in for the portal) that walks the
whole customer journey — **65 passed, 0 failed, 3 skipped** (see `LINUX_E2E_RESULTS.md`). It
immediately found two defects that neither the stub self-test nor the device log had revealed:

| ID | Severity | Finding |
| --- | --- | --- |
| **F-20** | **High** | `is_dnsmasq_cmd()` matched `*/dnsmasq` anywhere in a command line, and `foreign_dnsmasq_running()` scans every process in `/proc`. Any process that merely *mentions* the dnsmasq path (a shell whose command line carries `DNSMASQ_BIN=…/dnsmasq`, the harness's own command line) was treated as Android's tether dnsmasq — `keepalive()` then **killed our own dnsmasq** and "stepped aside", and `start()` could decide to never start one. Reproduced live. **Fixed**: `is_dnsmasq_proc()` now requires the executable/argv to be dnsmasq; regression test 13b. |
| **F-21** | **Medium** | `probe()` detected masquerade and portal redirect by exact substring match on `iptables -S`. iptables ≥ 1.8 inserts `-m tcp` and prints `-s` before `-o` (verified on both legacy and nft backends), so both reported false negatives and the watchdog re-installed them every tick — the root-shell churn PR #10 set out to remove. **Fixed**: `probe()` now uses `iptables -C`; regression test 14a. |

Both fixes are in `scripts/setup_network.sh` (commit `18362c0`) and are locked in by regression
tests in `tools/linux-e2e/rns-e2e.sh`; `tools/run-script-selftest.sh` still passes in full.

---

## 6. Documentation audit (F-08, Medium)

The four documents have drifted badly out of step with the code, because they were written by
the PR that introduced a feature and never revisited.

| Document | Last touched | Age vs `main` | Problem |
| --- | --- | --- | --- |
| `README.md` | `bf7f393` (auto) | current | Only the *Pull requests* section is regenerated. The hand-written body still documents a 6-mode AP picker (`Automatic / NetShare / Local-only / Root hostapd / System / Manual`) — accurate for `main`, but exactly the design PR #9 deletes. Will be wrong the moment PR #9 lands. |
| `AUDIT.md` | `b47ecff` — PR #4, 02:28 | **7 PRs stale** | Its "Still open" list has not been updated since PR #4. Findings fixed by PRs #5–#11 (usage tracking O4, watchdog granularity, WAN detection, the `core/` architecture its §6 recommends) are still listed as open. |
| `IMPLEMENTATION.md` | `8b8269c` — PR #5, 03:41 | **6 PRs stale** | Says *Phase 10 — UI REDESIGN (**Partial**)* and lists as TODO "update `activity_main.xml`", "add Block/Limit buttons", "add voucher printing dialog". PR #11 rewrote the UI wholesale. Says Phase 9 printing is *Completed* (it is dead code, §5.2). States voucher codes are generated as `RNS-1001, RNS-1002, RNS-1003` — `VoucherCodes.generate()` actually emits `XXXX-XXXX` from a 32-char alphabet (the device log confirms: `HCSQ-KEBE`). |
| `PROJECT_AUDIT.md` | `90feb39` — 09:02, during PR #10 | 2 PRs stale | Sections 9–13 cover PR #10 only; PR #11 is absent. Its §12 verification section is the most honest writing in the repo and should be the template for the others. |

Also: `hotspot-billing.zip` (21 KB, the original AI drop) and `Hotspot .txt` are still in the
repository root. They are referenced as historical provenance, which is defensible, but they
make the root directory look like an inbox.

---

## 7. Verification status matrix

What each claim is actually backed by. ✅ verified · ⚠️ partial · ❌ not verified.

| Capability | Claimed by | CI (build + unit tests) | Shell self-test | On device |
| --- | --- | --- | --- | --- |
| Project compiles | PR #1 | ✅ `main` green since PR #10 | n/a | ✅ (PR #9 build only) |
| NAT / masquerade / policy routing | PRs #4, #5, #10 | ✅ | ✅ | ✅ |
| DHCP with no IP conflict | PRs #3, #5, #10 | ✅ | ✅ | ✅ |
| Captive portal pops the sign-in sheet | PRs #3, #4 | ✅ | ✅ | ✅ (probe + `/redeem` both seen in the log) |
| Voucher redeem → internet | PRs #2, #5 | ✅ | ✅ | ✅ |
| Per-plan `tc` shaping, both directions | PRs #1, #5 | ✅ | ✅ | ⚠️ (class `1:101` created in the log; throughput never measured) |
| AP bring-up on the Hot 8 (which method works) | PRs #4, #8, #9, #11 | ✅ | n/a | ✅ **PR #9 build only** (WiFi Direct group) |
| Evidence-based adoption (`ApEvidence`) | PR #11 | ✅ | ✅ | ❌ |
| Fast start / clean stop (`RootShell` choke point) | PR #10 | ✅ | ✅ | ❌ |
| Dark console UI | PR #11 | ✅ | n/a | ❌ |
| Idle launch, single Start/Stop, `manually_stopped` | PR #9 | ✅ (on an older commit only) | ✅ | ✅ **PR #9 build only — not on `main`** |
| One-tap join / QR | PR #9 | ✅ (on the tested commit) | n/a | ❌ **broken on the target device** — Android 9 refuses `setPassphrase`, so the network used a random `kamm4fFC` while the QR advertised `rns-open-2026` (F-19, §5.3) |
| Per-session data usage | PRs #5, #11 | ✅ | n/a | ❌ **structurally always 0** (§5.1) |
| Printer support | PR #5 | ✅ | n/a | ❌ **dead stub** (§5.2) |
| 5 simultaneous clients | master plan | ❌ | ⚠️ (pool only) | ❌ |
| Reboot survival | PR #2 | ❌ | n/a | ❌ (and `BootReceiver` restarts the gateway unconditionally — the `manually_stopped` fix is in the unmerged PR #9) |
| **Full gateway on a real kernel** (DHCP → block → portal → redeem → NAT → deauth → cleanup) | this audit | n/a | ⚠️ (stubbed binaries only) | ✅ **Linux e2e, 55/0/3** (`tools/linux-e2e/`, real dnsmasq + iptables + veth client) — see `LINUX_E2E_RESULTS.md`; found + fixed F-20/F-21 |

**Net:** the network core is verified once, on hardware, by a build that is not on `main`.
Everything merged since is verified by CI and by shell stubs — **and, after this audit, by a
real-kernel Linux end-to-end run** (`tools/linux-e2e/`), which is as close to the phone as this
environment allows and is where F-20/F-21 were caught.

---

## 8. Prioritised remediation plan

### P0 — do before the app touches a live network

1. **Fix F-01 (root command injection).** Route SSID and passphrase through one `shellSafe()`
   (or stop building command strings). Add unit tests asserting shell metacharacters cannot
   survive. ~30 minutes of work; it is a root shell.
2. **Resolve PR #9 (F-02/F-03).** Decide the architecture question explicitly:
   - If the PR #11 design wins (multi-method launcher + `ApPlan` + `ApMode`, remembered
     method), then cherry-pick PR #9's *non-architectural* fixes onto `main` as a fresh PR:
     boot IDLE, the single Start/Stop control with `Phase.STOPPING`, the persisted
     `manually_stopped` flag, confirmed-location gating, stale-`rnsap0` cleanup, and the
     one-tap join card. These are small, independent, and they are the items the operator
     asked for by name.
   - If the PR #9 design wins (WiFi Direct only, one method), then `main` has to give up
     `ApPlan` and the mode picker, and PR #11's "remembered method" feature goes with them.
     In that case **also fix F-19** (§5.3): the QR must carry the read-back passphrase, or
     one-tap join is broken on Android 9.
   Either way the current state — a conflicted PR holding the only working configuration — is
   the worst of both.
3. **Rebase (do not force-merge) PR #9 onto `main`** and re-run CI. The code is already
   green and device-proven at `4414333`/`51a91ff`, so the only new risk introduced is the
   conflict resolution itself — which is exactly why it must go through CI again.

> **Already done since this plan was written:** F-20 and F-21 (§5.5) are fixed in
> `scripts/setup_network.sh` and regression-tested. They are not open items.

### P1 — do before the next device test session

4. **Install the merged `main` APK on the Infinix Hot 8 and export a debugger report**
   (F-03). Specifically: does `ApEvidence` accept the WiFi Direct group that PR #9's build
   got working? Does START still succeed? Is the dark console legible? Everything else is
   guesswork until this exists.
5. **Fix F-05**: enable branch protection on `main` requiring the *Build APK* check (the
   shell self-test, unit tests, lint and both APK variants) to pass before merge. Three PRs
   were merged red; one of them broke `main` for nearly four hours.
6. **Fix F-11**: replace `fallbackToDestructiveMigration()` with real migrations and set
   `exportSchema = true`.

### P2 — correctness and hygiene

7. **Fix F-06** (per-session bytes always zero) — either write the deltas or delete the
   columns and the empty loop.
8. **Fix F-07** — delete `NetworkEngine.kt`; decide the fate of `PrinterManager` and the two
   unused tables.
9. **Fix F-08** — refresh `AUDIT.md` and `IMPLEMENTATION.md` against `bf7f393`, or mark them
   explicitly as historical snapshots of PRs #4 and #5. Delete the `RNS-1001` claim.
10. **Fix F-04** — move the keystore and both passwords to GitHub Actions secrets and rotate
    the key before distribution.

### P3 — hardening

11. **F-09**: bind the portal to the LAN address, or add an explicit WAN DROP for `:8080`.
12. **F-10**: rate-limit `/redeem`; log rejected attempts; document MAC spoofing as accepted.
13. **F-12/F-13**: restrict direct pushes to `main`; reconsider the 6-hourly blanket branch
    deletion or add `arena-*` to `KEEP_BRANCHES`.
14. **F-14**: remove `unitTests.returnDefaultValues = true` and mock properly, so unit tests
    stop passing against behaviour that crashes on a device.

---

## 9. Appendix — evidence

**Commands used for this audit** (all reproducible from a clone of the repo):

```bash
# PR and CI forensics
gh pr list --state all --limit 100
gh pr view N --json number,title,state,createdAt,mergedAt,additions,deletions,changedFiles,body
gh pr view 9 --json mergeable,mergeStateStatus
gh run list --workflow APK --limit 100 --json displayTitle,conclusion,headBranch,createdAt
gh api repos/OWNER/REPO/commits/<sha>/check-runs
gh api repos/OWNER/REPO/commits/f35d949          # resolves the tested build

# Git archaeology (note: a shallow clone reports a false "no merge base")
git fetch --unshallow origin
git log --first-parent --format='%h | %ad | %an | %s' --date=iso main
git merge-base main 51a91ff                       # → de0d96b (PR #9's base)
git diff --stat main...51a91ff                    # PR #9 vs main
git grep -n "staying IDLE" main -- 'app/src/main/java/*'      # → no match
git grep -n "staying IDLE" 51a91ff -- 'app/src/main/java/*'   # → HotspotService.kt:202

# Shell layer (executed in this audit: ALL CHECKS PASSED)
bash tools/run-script-selftest.sh

# Dead-code counts
for d in voucherDao sessionDao deviceProfileDao dhcpLeaseDao hotspotSessionDao \
         clientDao paymentDao settingDao voucherPlanDao; do
  grep -rn "$d" app/src/main/java --include=*.kt | grep -v "db/Database.kt" | wc -l
done
```

**Key evidence files referenced:** `Debuggerfitst semi success.txt` (the only on-device log),
`scripts/setup_network.sh:500–560` (`install_chains`), `:965–986` (`authorize`),
`app/src/main/java/com/hotspot/billing/net/ApLauncher.kt:325–330`,
`app/src/main/java/com/hotspot/billing/net/ApMode.kt:181–190`,
`app/src/main/java/com/hotspot/billing/net/SoftApController.kt:582`,
`app/src/main/java/com/hotspot/billing/util/RootShell.kt:96–136`,
`app/src/main/java/com/hotspot/billing/core/UsageMonitor.kt:105–110`,
`app/src/main/java/com/hotspot/billing/db/Database.kt` (Room v3 builder),
commit `90feb39` (records the two compile errors and that `main` did not compile).
