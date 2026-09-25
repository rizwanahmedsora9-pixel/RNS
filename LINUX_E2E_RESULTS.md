# RNS — Linux end-to-end test results

**Date:** 2026-09-24 · **Code under test:** `main` @ `bf7f393` + F-20/F-21 fixes · **Harness:** `tools/linux-e2e/`

## Summary

I built a Linux environment that runs the real gateway scripts against a real
kernel with a real dnsmasq and a real test client, then walked the entire
customer journey through it.

> **65 passed · 0 failed · 3 skipped**

The gateway works end to end. A test client obtained an address via a genuine
DHCP handshake, was blocked from the internet, was served the captive portal on
the OS probe paths, redeemed a voucher, got internet through NAT, was cut off
again on `deauthorize`, and `cleanup` removed every trace.

That is the first time this project's network layer has been exercised against
a real kernel rather than against stubs — and it immediately surfaced **two real
defects** (F-20, F-21) that neither the shell self-test nor the device log had
revealed. **Both are now fixed in `scripts/setup_network.sh` and locked in by
regression tests** (test 13b and the value assertions in test 14 below): the
same harness that caught them now proves the fixes. The stub-based
`tools/run-script-selftest.sh` also passes in full after the fix.

## The environment

```
  [rns-client]              [rns-gw]  "the phone"                     [rns-wan]  "the internet"
     eth0  <===============>  eth-lan     [ dnsmasq + iptables ]      eth-wan <===========> eth0
   DHCP client              192.168.49.1   captive portal :8080       203.0.113.2           203.0.113.1
   (busybox udhcpc)                                                                        HTTP :80, DNS :53
```

- Three **network namespaces** joined by veth pairs; nothing touches the host's
  own networking.
- **Real dnsmasq 2.90**, built from source (no dnsmasq and no apt access here).
- **Real `iptables`** (1.8.9) and **real `tc`**.
- `portal.py` stands in for `CaptivePortalServer` + `VoucherManager.redeem`; on a
  successful redemption it runs the **same three shell commands the app runs** —
  `setup_network.sh reserve`, `setup_network.sh authorize`,
  `bandwidth_control.sh add`.
- `dns_upstream.py` is a minimal DNS server in the WAN namespace, standing in for
  the ISP's resolver.

Run it yourself:

```bash
cat > /tmp/run-e2e.sh <<'EOS'
#!/bin/bash
export DNSMASQ_BIN=/path/to/dnsmasq      # keep this out of the command line - see F-20
cd /path/to/RNS && bash tools/linux-e2e/rns-e2e.sh
EOS
bash /tmp/run-e2e.sh
```

## Results

| # | Test | Result |
| --- | --- | --- |
| 1 | `setup_network.sh start` on a real kernel | ✅ WAN/LAN resolved, address adopted (`192.168.49.1/24`), policy routing installed, `dhcp ours` |
| 1a | `HS_NAT` jump first in `nat/PREROUTING`, `HS_FWD` first in `filter/FORWARD` | ✅ |
| 1b | Port-80 REDIRECT to the portal installed | ✅ |
| 1c | 443 `REJECT --reject-with tcp-reset` | ✅ |
| 1d | `HS_FWD` ends in `DROP` (default deny) | ✅ |
| 2 | Real DHCP handshake, client gets `192.168.49.29` | ✅ router `192.168.49.1`, DNS `192.168.49.1`, lease file written |
| 3 | Unauthenticated client blocked from the internet | ✅ |
| 4 | HTTP intercepted → portal login page | ✅ |
| 4a | `/generate_204` answered **200** | ✅ the thing that actually pops the sign-in sheet |
| 4b | `/connecttest.txt` answered **200** | ✅ (the path seen in the real device log) |
| 5 | HTTPS reset fast (rc=7 in 13 ms), not timed out | ✅ clients fall back to the plain-HTTP probe |
| 6 | Client query to `192.168.49.1` resolves | ✅ |
| 6a | Client query to `8.8.8.8` hijacked and answered | ✅ the "client ignores DHCP DNS" case |
| 7 | Wrong voucher rejected, client stays blocked | ✅ |
| 8 | Good voucher → `reserve` + `authorize`, both recorded | ✅ |
| 8a | Authorisation rule inserted **above** the DROP | ✅ |
| 9 | Authorised client fetches the real page | ✅ `RNS-E2E-WAN-OK` |
| 9a | MASQUERADE counter non-zero | ✅ traffic genuinely NATted, not just permitted |
| 10 | Same voucher refused from a second device | ✅ (client changed MAC, took a new lease) |
| 11 | `deauthorize` cuts the client off | ✅ |
| 12 | Reserved address `192.168.49.77` handed out on renew | ✅ `reserve` + SIGHUP works |
| 13 | `keepalive` idempotent, no duplicated rules | ✅ |
| 13a | `keepalive` does not restart a healthy dnsmasq | ✅ |
| 13b | **F-20 regression:** a process whose command line merely mentions the dnsmasq path cannot stop our DHCP | ✅ decoy visible to the old predicate (`old-pattern=1`), ignored by the new one (`new-pattern=0`); dnsmasq pid unchanged |
| 14 | `probe` returns all expected keys | ✅ |
| 14a | **F-21 regression:** with the rules installed, `probe` actually says so | ✅ `masq=yes`, `redirect=yes`, `nat_jump=yes`, `fwd_jump=yes`, `in_jump=yes`, `dhcp_ours=yes`, `dhcp_orphan=no`, `dhcp_foreign=no` — before the fix, `masq`/`redirect` reported `no` on iptables 1.8.9 |
| 15 | `bandwidth_control.sh` degrades gracefully with no HTB | ✅ |
| 16 | `cleanup` removes chains, rules, masquerade, dnsmasq | ✅ all four chains gone, no process left |

### Skipped (environment, not the app)

| Test | Why |
| --- | --- |
| `-m mac` matcher | This kernel has no loadable modules and no `xt_mac`. The shim rewrites those rules to source-IP; everything except the matcher itself is still exercised. |
| `tc` shaping | Only `pfifo_fast` exists here — no `sch_htb`, `sch_tbf`, `ingress` or `u32`. Covered by the stub-based `run-script-selftest.sh` instead. |
| Throughput measurement | Needs a traffic generator; not implemented. |

---

## Defects found (both fixed and regression-tested)

### F-20 (High) — a stray process that merely mentions `dnsmasq` can shut our DHCP server down

`scripts/setup_network.sh:189`:

```sh
is_dnsmasq_cmd() {
    case "$1" in
        */dnsmasq|*/dnsmasq\ *|*dnsmasq\ --*) return 0 ;;
    esac
    return 1
}
```

`foreign_dnsmasq_running()` (`:196`) and `save_foreign_cmdline()` (`:214`) then
walk **`/proc/[0-9]*`** — every process in the PID namespace, not just DHCP
servers — and treat anything matching as Android's tether dnsmasq.

The first alternative, `*/dnsmasq`, matches **any command line containing a token
that ends in `/dnsmasq`**. It does not have to be a dnsmasq process at all.

**What that does.** In `keepalive()` (`:897`):

```sh
if dnsmasq_pid >/dev/null && foreign_dnsmasq_running; then
    log "Android DHCP came back alongside ours - stepping aside so clients are not stuck obtaining an IP"
    kill "$P"          # <-- kills OUR dnsmasq
    DHCP_OWNER=android
```

and in `start()` (`:829`), the same predicate makes the gateway decide Android
owns DHCP and **never start its own** — leaving clients on "obtaining IP
address", the exact symptom this project has spent eleven PRs chasing.

**Observed.** During the first clean run of this harness, `keepalive` killed our
dnsmasq (pid 19081 → gone). The diagnostic now built into the harness
(`shim/find_dnsmasq.sh`) showed the two "foreign" processes it had found:

```
pid=18450 : dnsmasq --interface=eth-lan ...        <- a leftover from an earlier
                                                      namespace: real dnsmasq, but
                                                      in a DIFFERENT network namespace
pid=18701 : /bin/bash -l -c cd /home/user/RNS/...  <- the harness's OWN command line,
            DNSMASQ_BIN=/tmp/dnsmasq-build/src/dnsmasq ...   matched on the path alone
```

Re-running through a wrapper script (so the dnsmasq path never appears in a
command line) made it disappear, and test 13a went green.

**Fix (applied).** `is_dnsmasq_cmd()` is replaced by `is_dnsmasq_proc()`, which
accepts a process only when the program being executed is dnsmasq — `argv[0]`,
or `argv[1]` when the kernel went through `/usr/bin/env` for a shebang, or the
executable itself — never a later argument:

```sh
is_dnsmasq_proc() {
    _cmd=$(cat "$1/cmdline" 2>/dev/null) || return 1
    case "$_cmd" in *dnsmasq*) ;; *) return 1 ;; esac     # cheap pre-filter
    _exe=$(readlink "$1/exe" 2>/dev/null)
    case "${_exe##*/}" in dnsmasq|dnsmasq-*) return 0 ;; esac
    tr '\0' '\n' < "$1/cmdline" 2>/dev/null | head -n 2 | awk '
        { b = $0; sub(/.*\//, "", b); if (b == "dnsmasq" || index(b, "dnsmasq-") == 1) f = 1 }
        END { exit f ? 0 : 1 }' && return 0
    return 1
}
```

Two details that matter. The `argv[1]` check exists because a script started
via `#!/usr/bin/env` has the interpreter as `argv[0]` (the self-test's orphan
fixture is exactly that, and an argv[0]-only check broke the orphan test). And
the pre-filter keeps the per-process cost at one fork — `keepalive()` scans all
of `/proc` every tick, and this project exists to reduce root-shell churn.
`is_our_dnsmasq_cmd()` got the same treatment via `is_our_dnsmasq_proc()`,
because its results feed a `kill` loop.

**Verified.** Test 13b launches a decoy (`bash -c 'sleep 30; :' /tmp/rns-e2e/
bin/dnsmasq --interface=eth-lan --pid-file=… --dhcp-leasefile=…`), asserts the
OLD pattern matches it and the NEW one does not, then runs `keepalive` and
asserts our dnsmasq's pid is unchanged. It also asserts the decoy is actually
visible to the old pattern, so the test fails loudly if it ever becomes vacuous.
(The decoy must be two commands, `sleep 30; :` — bash exec-optimises a single
command over its own command line, which silently stripped the path in the
first version of this test.)

---

### F-21 (Medium) — `probe` reports `masq=no` and `redirect=no` on iptables ≥ 1.8

`probe()` (`:1146`) decides whether NAT and the portal redirect are in place by
matching **exact substrings** of `iptables -S` output:

| probe expects | what iptables 1.8.9 actually prints |
| --- | --- |
| `-A HS_NAT -i eth-lan -p tcp --dport 80 -j REDIRECT --to-ports 8080` | `-A HS_NAT -i eth-lan -p tcp -m tcp --dport 80 -j REDIRECT --to-ports 8080` |
| `-A POSTROUTING -o eth-wan -s 192.168.49.0/24 -j MASQUERADE` | `-A POSTROUTING -s 192.168.49.0/24 -o eth-wan -j MASQUERADE` |

Two differences: `-m tcp` is inserted, and `-s`/`-o` are emitted in the opposite
order. I verified both against **iptables-legacy and iptables-nft**, so this is
not an nft-backend artefact — it is 1.8.x behaviour.

**Consequence.** The watchdog believes NAT and the portal redirect are missing
and re-installs them on every tick. That is exactly the root-shell churn PR #10
set out to remove (the device log measured 142 root commands / 170.7 s of shell
time in 7 minutes). The harness reproduces the false negative: `probe` reported
`masq=no` and `redirect=no` while both rules were demonstrably installed and
working.

**Caveat.** The Hot 8 runs iptables 1.6.1, where the current strings probably
still match — the device log contains no `probe` output, so I could not confirm
either way. This is a latent defect that will bite on any device shipping
iptables ≥ 1.8, and this project builds against `compileSdk 34`.

**Fix (applied).** `probe()` no longer parses `-S` for these two; it uses the
formatting-independent existence check:

```sh
if [ -n "$WAN" ] && iptables -t nat -C POSTROUTING -o "$WAN" -s "$LAN_SUBNET" -j MASQUERADE 2>/dev/null; then
    echo "masq=yes"; else echo "masq=no"; fi
if iptables -t nat -C HS_NAT -i "$LAN_IF" -p tcp --dport 80 -j REDIRECT --to-ports "$PORTAL_PORT" 2>/dev/null; then
    echo "redirect=yes"; else echo "redirect=no"; fi
```

`keepalive()` already used exactly this `-C` idiom for the redirect — only
`probe()` parsed text. `-C` exits non-zero when the chain does not exist, so a
stopped gateway still reports `no` correctly.

**Verified.** Test 14 runs `probe` while every rule is demonstrably installed
and asserts `masq=yes`, `redirect=yes` (plus the other seven values). Against
the unfixed script, on this kernel's iptables 1.8.9, the same assertion reported
`masq=no` / `redirect=no` — which is exactly how the defect was found.

---

## What this environment cannot tell you

- **The Kotlin app is not exercised at all.** There is no JDK/Android SDK and no
  route to Maven Central here, so nothing was compiled. The harness tests the
  shell layer that the app drives, with a Python stand-in for the portal.
- **No Wi-Fi.** The LAN is a veth, so `ApLauncher`, `ApEvidence`, WiFi Direct and
  the Android-9 permission dance remain untested — they still need the phone.
- **`tc` shaping** is unverified on a real kernel (see skipped tests).

Combined with the existing `tools/run-script-selftest.sh` (stub-based, asserts
command text) this closes the middle gap: the scripts are now proven to produce a
network that actually works.

## Files added

| File | Purpose |
| --- | --- |
| `tools/linux-e2e/rns-e2e.sh` | topology + 16 test groups + teardown |
| `tools/linux-e2e/portal.py` | captive portal + voucher redemption stand-in |
| `tools/linux-e2e/dns_upstream.py` | minimal DNS server for the WAN namespace |
| `tools/linux-e2e/shim/iptables` | `-m mac` / `-m owner` translation (no kernel modules here) |
| `tools/linux-e2e/shim/udhcpc.script` | applies the lease in the client namespace |
| `tools/linux-e2e/shim/find_dnsmasq.sh` | diagnostic for F-20 |
| `tools/linux-e2e/README.md` | prerequisites, usage, limitations |
