# Linux end-to-end test environment

`rns-e2e.sh` runs the **real** `scripts/setup_network.sh` and
`scripts/bandwidth_control.sh` against a **real Linux kernel**, with a **real
dnsmasq** and a **real test client**, and walks the whole customer journey.

It is the missing middle layer in this project. `tools/run-script-selftest.sh`
checks the scripts against *stub* `iptables`/`ip`/`tc`/`dnsmasq` — it proves the
text of the commands but not that the resulting network works. This harness
proves the network works.

```
  [rns-client]              [rns-gw]  "the phone"                  [rns-wan]  "the internet"
     eth0  <===============>  eth-lan      [ dnsmasq + iptables ]  eth-wan <============> eth0
   DHCP client              192.168.49.1    captive portal :8080   203.0.113.2            203.0.113.1
                                                                                         HTTP :80, DNS :53
```

One veth pair carries the LAN, one carries the uplink, each side in its own
network namespace. Nothing touches the host's own networking.

## Running it

```bash
# needs: passwordless sudo, iproute2, iptables, gcc/make (or an existing dnsmasq), python3
DNSMASQ_BIN=/path/to/dnsmasq bash tools/linux-e2e/rns-e2e.sh
```

> **Run it from a wrapper script, not with `DNSMASQ_BIN=...` on the command
> line.** `setup_network.sh`'s `is_dnsmasq_cmd()` matches any command line that
> contains a token ending in `/dnsmasq`, and it scans every process in
> `/proc` — so a shell command that merely *mentions* the dnsmasq path is
> mistaken for a foreign DHCP server, and `keepalive()` will then kill our own
> dnsmasq and "step aside". That is a real defect in the script (see
> `LINUX_E2E_RESULTS.md`, finding F-20), and it made the first clean run of this
> harness fail test 13 before the cause was understood.
>
> ```bash
> cat > /tmp/run-e2e.sh <<'EOS'
> #!/bin/bash
> export DNSMASQ_BIN=/path/to/dnsmasq
> cd /path/to/RNS && bash tools/linux-e2e/rns-e2e.sh
> EOS
> bash /tmp/run-e2e.sh
> ```
>
> Also make sure no stray `dnsmasq` is left running from a previous experiment:
> `sudo pkill -9 -x dnsmasq`. Deleting a network namespace does **not** kill the
> processes inside it.

`dnsmasq` is the only real prerequisite beyond sudo. If it is not installed:

```bash
curl -sL https://github.com/imp/dnsmasq/archive/refs/tags/v2.90.tar.gz | tar xz
cd dnsmasq-2.90 && make -j"$(nproc)"
DNSMASQ_BIN="$PWD/src/dnsmasq" bash tools/linux-e2e/../../../rns-e2e.sh   # from the repo root
```

Useful knobs:

| Variable | Meaning |
| --- | --- |
| `DNSMASQ_BIN` | path to a dnsmasq binary (defaults to whatever is on `PATH`) |
| `RNS_ROOT` | scratch directory, default `/tmp/rns-e2e` (state, logs, artefacts) |
| `KEEP_NS=1` | leave the namespaces up after the run so you can poke at them |

## What it covers

| # | Test |
| --- | --- |
| 1 | `setup_network.sh start` on a real kernel: chain jumps, port-80 redirect, 443 tcp-reset, default-deny tail |
| 2 | A test client completes a real DHCP handshake (DISCOVER/OFFER/REQUEST/ACK) and gets router + DNS |
| 3 | An unauthenticated client cannot reach the internet |
| 4 | The captive portal answers HTTP **and** the OS probe paths with 200 (this is what pops the sign-in sheet) |
| 5 | HTTPS is reset, not dropped, so the client falls back to its plain-HTTP probe |
| 6 | The gateway's DNS answers, and foreign DNS (8.8.8.8) is hijacked to it |
| 7 | A wrong voucher is rejected and the client stays blocked |
| 8 | A good voucher runs `reserve` + `authorize` and records both |
| 9 | The authorised client then really fetches a page from the internet (MASQUERADE counter proves NAT) |
| 10 | The same voucher is refused from a second device |
| 11 | `deauthorize` cuts the client off again |
| 12 | A reserved address is handed out on renew (`reserve` + SIGHUP) |
| 13 | `keepalive` is idempotent - it does not duplicate rules |
| 14 | `probe` returns a coherent snapshot |
| 15 | `bandwidth_control.sh` (see limits below) |
| 16 | `cleanup` removes every chain, rule, the masquerade and the dnsmasq |

## Known limits of this environment

These are properties of the container this was developed in, **not** of the app.
Run the harness on a normal machine and the first two disappear.

1. **No `xt_mac`.** The kernel here has no loadable modules (`/lib/modules` is
   empty), so `-m mac --mac-source` cannot be loaded. `shim/iptables` rewrites
   those rules to `-s <ip-of-that-MAC>`, resolving the address from the dnsmasq
   lease file and then the neighbour table — the same two sources the app's own
   `ArpResolver`/`LeaseParser` use. Chain choice, insert position, jump target,
   idempotency and default-deny ordering are all still exercised; only the
   kernel's MAC matcher itself is not. An unresolvable MAC becomes
   `-s 255.255.255.255`, which fails loudly instead of passing silently.

2. **No `sch_htb`.** Only `pfifo_fast` is available, so `tc` shaping cannot be
   exercised. The harness instead asserts that `bandwidth_control.sh` **degrades
   gracefully** (logs "this tc build has no HTB scheduler" and exits non-zero)
   rather than failing the run.

3. **No `-m owner`** either; `shim/iptables` rewrites that rule to
   `-s 255.255.255.255` so it loads but matches nothing, which is the truthful
   consequence of "there is no non-root dnsmasq here".
   *Do not simply delete the match* — that leaves `-p udp --sport 67 -j DROP`,
   which drops **our own** root dnsmasq's DHCPOFFER and every client sticks on
   "Obtaining IP address". That is exactly what the first run of this harness
   did before the shim was corrected.

Every substitution is written to `$RNS_ROOT/state/macshim.log`.

## Files

| File | Role |
| --- | --- |
| `rns-e2e.sh` | topology, the 16 tests, teardown |
| `portal.py` | stands in for `CaptivePortalServer` + `VoucherManager.redeem` |
| `dns_upstream.py` | a minimal DNS server in the WAN namespace ("the ISP's resolver") |
| `shim/iptables` | the `-m mac` / `-m owner` translation described above |
| `shim/udhcpc.script` | applies the lease in the client namespace |
