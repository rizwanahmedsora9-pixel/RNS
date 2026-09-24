package com.hotspot.billing.util

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.LogLevel
import com.hotspot.billing.net.LanPlan
import com.hotspot.billing.net.LeaseParser
import com.topjohnwu.superuser.Shell
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Thin wrapper around libsu for running rooted shell commands.
 *
 * Every command is an argument-vector handed to the two bundled scripts; nothing
 * interpolates user input into a shell string except the MAC/IP values, which are
 * validated at the call sites.
 *
 * EVERY command goes through [run], which records the command line, its exit
 * code, its stdout/stderr and how long it took into [AppLog]. That record is the
 * debug report: when something on the network misbehaves there is no guessing
 * which rule was or was not applied.
 */
object RootShell {

    private const val SCRIPT_DIR = "/data/local/tmp"
    private const val SETUP = "$SCRIPT_DIR/setup_network.sh"
    private const val SHAPER = "$SCRIPT_DIR/bandwidth_control.sh"
    private const val ENV_FILE = "$SCRIPT_DIR/hotspot.env"
    private const val RUNTIME_FILE = "$SCRIPT_DIR/hotspot.runtime"

    private const val MAX_LOGGED_OUTPUT = 4_000

    /**
     * Every privileged command goes through this lock.
     *
     * libsu runs the jobs of one shell one after another, so a slow command does
     * not just take long itself - everything queued behind it "takes" that long
     * too. The 2026-09-24 debug log shows the result: 142 root commands, 170 s of
     * root-shell time in 7 minutes, a `cat /proc/net/arp` that reported 15.4 s
     * and `setup_network.sh keepalive` reporting 20 s every 30 s, because the
     * portal threads, the watchdog and the UI were all queued on the same shell.
     *
     * Holding this lock here (instead of inside libsu) lets us measure and log
     * the queue wait separately from the command itself, and lets the hot paths
     * give up instead of piling up more work.
     */
    private val gate = ReentrantLock(true)

    /** Wait time before we log that the shell was busy (queueing is the diagnosis). */
    private const val BUSY_WARN_MS = 1_500L

    /** A poll gives up after this long instead of queueing behind a long command. */
    private const val BUSY_SKIP_WAIT_MS = 1_200L

    /** The probe is worth waiting a moment for: one command replaces a dozen. */
    private const val PROBE_WAIT_MS = 2_500L

    /** Cache lifetimes: the UI polls every 3 s, the watchdog every 8 s. */
    private const val PROBE_TTL_MS = 2_000L
    private const val LEASE_TTL_MS = 3_000L
    private const val ARP_TTL_MS = 3_000L

    @Volatile
    private var busySkips = 0

    /** Is a privileged command running right now? */
    fun isBusy(): Boolean = gate.isLocked

    private fun waitsForGate(waitMs: Long): Boolean = try {
        if (waitMs == Long.MAX_VALUE) {
            gate.lock()
            true
        } else {
            gate.tryLock(waitMs, TimeUnit.MILLISECONDS)
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * Runs [cmd] in the (root) shell and records it.
     *
     * @param quiet record at VERBOSE instead of INFO. For the polls that run
     *   every couple of seconds, so the readable log stays about *events*.
     */
    fun run(cmd: String, quiet: Boolean = false): Shell.Result {
        val queuedAt = System.nanoTime()
        waitsForGate(Long.MAX_VALUE)
        val waited = millisSince(queuedAt)
        if (waited > BUSY_WARN_MS) {
            AppLog.w(
                AppLog.TAG_ROOT,
                "root shell was busy for ${waited}ms before: ${cmd.take(80)}"
            )
        }
        val started = System.nanoTime()
        val result = try {
            Shell.cmd(cmd).exec()
        } catch (e: Throwable) {
            AppLog.log(
                LogLevel.ERROR, AppLog.TAG_ROOT,
                "\$ $cmd\n  -> threw ${e.javaClass.simpleName}: ${e.message} " +
                    "after ${millisSince(started)}ms (root shell unavailable?)"
            )
            throw e
        } finally {
            gate.unlock()
        }
        val took = millisSince(started)
        val out = result.out.joinToString("\n").trim()
        val err = result.err.joinToString("\n").trim()
        val level = when {
            result.code != 0 -> LogLevel.WARN
            quiet -> LogLevel.VERBOSE
            else -> LogLevel.INFO
        }
        AppLog.log(
            level, AppLog.TAG_ROOT,
            buildString {
                append("$ ").append(cmd)
                append("\n  -> exit ").append(result.code)
                append(", ").append(took).append("ms")
                if (out.isNotEmpty()) append("\n  out: ").append(clip(out))
                if (err.isNotEmpty()) append("\n  err: ").append(clip(err))
            }
        )
        return result
    }

    private fun millisSince(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private fun clip(text: String) =
        if (text.length <= MAX_LOGGED_OUTPUT) text
        else text.take(MAX_LOGGED_OUTPUT) + "\n  ... (${text.length - MAX_LOGGED_OUTPUT} more chars)"

    // --- bounded commands, cached polls and the one-shot probe ------------------
    //
    // Everything below exists because of one measurement: on 2026-09-24 the app
    // ran 142 logged root commands (170 s of root-shell time) in 7 minutes, plus
    // the VERBOSE ones the debugger never shows. They queued behind each other -
    // the portal, the watchdog and the 1.5 s UI poller all on one shell - so a
    // single `cat /proc/net/arp` reported 15.4 s. Fewer, batched, cached commands
    // are the fix; a lock with a deadline is the safety net.

    /** Our own command result: [com.topjohnwu.superuser.Shell.Result] cannot be built by hand. */
    class Outcome(
        val out: List<String>,
        val err: List<String>,
        val code: Int,
        val ms: Long
    ) {
        val isSuccess: Boolean get() = code == 0
        fun firstLine(): String? =
            (out.firstOrNull { it.isNotBlank() } ?: err.firstOrNull { it.isNotBlank() })?.trim()
    }

    /**
     * Runs [cmd] only if the shell is free within [waitMs] milliseconds.
     * Returns null when it is not: the caller must treat that as "unknown",
     * never as "broken", otherwise a busy shell triggers repairs that make
     * everything slower.
     */
    fun tryRun(cmd: String, waitMs: Long = BUSY_SKIP_WAIT_MS, quiet: Boolean = true): Outcome? {
        val queuedAt = System.nanoTime()
        if (!waitsForGate(waitMs)) {
            busySkips++
            if (busySkips == 1 || busySkips % 20 == 0) {
                AppLog.w(
                    AppLog.TAG_ROOT,
                    "root shell busy - skipped $busySkips poll(s) instead of queueing " +
                        "behind them: ${cmd.take(60)}"
                )
            }
            return null
        }
        var result: Shell.Result? = null
        val started = System.nanoTime()
        try {
            result = Shell.cmd(cmd).exec()
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_ROOT, "root command could not run: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            gate.unlock()
        }
        val r = result ?: return null
        val took = millisSince(started)
        val out = r.out.joinToString("\n").trim()
        val err = r.err.joinToString("\n").trim()
        if (!quiet || r.code != 0) {
            AppLog.log(
                if (r.code == 0) LogLevel.VERBOSE else LogLevel.WARN, AppLog.TAG_ROOT,
                buildString {
                    append("$ ").append(cmd)
                    append("\n  -> exit ").append(r.code).append(", ").append(took).append("ms")
                    if (out.isNotEmpty()) append("\n  out: ").append(clip(out))
                    if (err.isNotEmpty()) append("\n  err: ").append(clip(err))
                }
            )
        }
        return Outcome(r.out, r.err, r.code, took)
    }

    /**
     * The whole health picture of the gateway from ONE root command
     * (`setup_network.sh probe`), parsed into key=value pairs.
     *
     * Per watchdog tick this replaces a dozen separate commands (interfaces,
     * addresses, ip rules, iptables -S per chain, dnsmasq pid, foreign dnsmasq,
     * lease count). Cached for [PROBE_TTL_MS] so the UI, the watchdog and the
     * portal cannot each start their own.
     */
    class Probe(val values: Map<String, String>, val tookMs: Long, val atMs: Long) {
        fun text(key: String): String? = values[key]?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
        fun flag(key: String): Boolean? = when (values[key]?.trim()) {
            "yes" -> true
            "no" -> false
            else -> null
        }
        fun num(key: String): Int? = values[key]?.trim()?.toIntOrNull()

        val lanIf: String? get() = text("lan")
        val wanIf: String? get() = text("wan")
        val subnet: String? get() = text("subnet")
        val gateway: String? get() = text("gateway")
        val lanAddress: String? get() = text("lan_addr")
        val lanUp: Boolean? get() = flag("lan_up")
        val ipForward: Boolean? get() = flag("ip_forward")
        val dhcpOurs: Boolean? get() = flag("dhcp_ours")
        val dhcpOrphan: Boolean? get() = flag("dhcp_orphan")
        val dhcpForeign: Boolean? get() = flag("dhcp_foreign")
        val natJump: Boolean? get() = flag("nat_jump")
        val forwardJump: Boolean? get() = flag("fwd_jump")
        val inputJump: Boolean? get() = flag("in_jump")
        val masquerade: Boolean? get() = flag("masq")
        val portalRedirect: Boolean? get() = flag("redirect")
        val ruleIif: Boolean? get() = flag("rule_iif")
        val ruleSubnet: Boolean? get() = flag("rule_subnet")
        val clients: Int get() = num("leases") ?: 0
        val authorized: Int get() = num("authed") ?: 0
        override fun toString(): String = "probe(${tookMs}ms) ${values.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
    }

    @Volatile private var cachedProbe: Probe? = null
    @Volatile private var cachedLeases: List<String>? = null
    @Volatile private var cachedLeasesAt = 0L
    @Volatile private var cachedArp: Map<String, String>? = null
    @Volatile private var cachedArpAt = 0L

    /**
     * @param fresh bypass the cache (the debugger's "Check now", and right after
     *   a start/stop where the answer must not be a leftover).
     */
    fun probe(lanIf: String? = null, fresh: Boolean = false): Probe? {
        val now = System.currentTimeMillis()
        cachedProbe?.let { if (!fresh && now - it.atMs < PROBE_TTL_MS) return it }
        val cmd = if (lanIf != null && lanIf.matches(IFACE_REGEX)) {
            "sh $SETUP probe $lanIf"
        } else {
            "sh $SETUP probe"
        }
        val outcome = tryRun(cmd, waitMs = PROBE_WAIT_MS, quiet = true) ?: return cachedProbe
        if (!outcome.isSuccess && outcome.out.isEmpty()) {
            AppLog.w(AppLog.TAG_ROOT, "probe failed (exit ${outcome.code}) - falling back to individual checks")
            return null
        }
        val values = LinkedHashMap<String, String>()
        for (line in outcome.out) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            values[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        if (values.isEmpty()) return null
        val probe = Probe(values, outcome.ms, now)
        cachedProbe = probe
        return probe
    }

    /** DHCP leases (ours and Android's), cached for a few seconds. */
    fun leases(fresh: Boolean = false): List<String> {
        val now = System.currentTimeMillis()
        cachedLeases?.let { if (!fresh && now - cachedLeasesAt < LEASE_TTL_MS) return it }
        val outcome = tryRun(
            "cat $SCRIPT_DIR/dnsmasq.leases /data/misc/dhcp/dnsmasq.leases " +
                "/data/misc/dhcp/dnsmasq.tether.leases 2>/dev/null; true",
            waitMs = POLL_WAIT_MS
        ) ?: return cachedLeases ?: emptyList()
        cachedLeases = outcome.out
        cachedLeasesAt = now
        return outcome.out
    }

    /** ARP table as ip -> mac, cached for a few seconds. */
    fun arp(fresh: Boolean = false): Map<String, String> {
        val now = System.currentTimeMillis()
        cachedArp?.let { if (!fresh && now - cachedArpAt < ARP_TTL_MS) return it }
        val outcome = tryRun("cat /proc/net/arp 2>/dev/null; true", waitMs = POLL_WAIT_MS)
            ?: return cachedArp ?: emptyMap()
        val map = LinkedHashMap<String, String>()
        for (line in outcome.out) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) continue
            val ip = parts[0]
            val mac = parts[3]
            if (!ip.matches(IP_REGEX)) continue
            if (mac.length != 17 || mac.count { it == ':' } != 5) continue
            if (mac == "00:00:00:00:00:00") continue
            map[ip] = mac.lowercase()
        }
        cachedArp = map
        cachedArpAt = now
        return map
    }

    /** Drops the cached poll results (after a start/stop, or on "Check now"). */
    fun invalidateCaches() {
        cachedProbe = null
        cachedLeases = null
        cachedArp = null
    }

    /**
     * Re-installs only what forwards packets (ip_forward, jumps, portal/DNS
     * redirects, masquerade, policy routing) for a given uplink.
     *
     * Used when Android rewrites its tables or the internet side changes. It
     * deliberately does NOT restart DHCP - `keepalive` and `start` do that, and
     * restarting the DHCP server drops every client.
     */
    fun repairNat(lanIf: String, wanIf: String, subnet: String): Boolean {
        if (!lanIf.matches(IFACE_REGEX) || !wanIf.matches(IFACE_REGEX)) return false
        if (!subnet.matches(SUBNET_REGEX)) return false
        val res = run("sh $SETUP nat $lanIf $wanIf $subnet")
        invalidateCaches()
        return res.isSuccess
    }

    /**
     * EXIT / emergency clean stop: removes every rule, every dnsmasq of ours
     * (tracked or orphaned), the shaper and the state files - whatever state the
     * previous session left behind.
     */
    fun cleanupAll(lanIf: String? = null): Boolean {
        val cmd = if (lanIf != null && lanIf.matches(IFACE_REGEX)) {
            "sh $SETUP cleanup $lanIf"
        } else {
            "sh $SETUP cleanup"
        }
        val res = run(cmd)
        invalidateCaches()
        return res.isSuccess
    }

    fun isRootAvailable(): Boolean {
        val root = try {
            Shell.getShell().isRoot
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_ROOT, "root check threw: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        AppLog.log(
            if (root) LogLevel.INFO else LogLevel.ERROR, AppLog.TAG_ROOT,
            if (root) "root shell available (uid 0)"
            else "NO ROOT - every privileged command below will fail until Magisk grants this app"
        )
        return root
    }

    /**
     * @param leaveAndroidDhcp do not kill the framework's tether dnsmasq. On the
     *   Hot 8, killing it (or holding port 53 so it cannot start) makes
     *   WifiService tear the softap down.
     */
    fun startNetwork(leaveAndroidDhcp: Boolean = false): Shell.Result =
        if (leaveAndroidDhcp) run("KEEP_ANDROID_DHCP=1 sh $SETUP start")
        else run("sh $SETUP start")

    fun stopNetwork(): Shell.Result = run("sh $SETUP stop")

    /** Re-assert DHCP without flushing the hotspot address. Safe to call often. */
    fun keepaliveNetwork(): Shell.Result = run("sh $SETUP keepalive")

    fun networkStatus(): List<String> = run("sh $SETUP status", quiet = true).out

    /** The script's own deep dump: interfaces, rules with counters, DHCP, leases. */
    fun networkDiag(): Shell.Result = run("sh $SETUP diag", quiet = true)

    fun authorizeMac(mac: String, ip: String, currentIp: String? = null): Shell.Result {
        require(mac.matches(MAC_REGEX)) { "not a MAC address: $mac" }
        require(ip.matches(IP_REGEX)) { "not an IPv4 address: $ip" }
        val extra = currentIp?.takeIf { it.matches(IP_REGEX) && it != ip }
        return if (extra != null) run("sh $SETUP authorize $mac $ip $extra")
        else run("sh $SETUP authorize $mac $ip")
    }

    fun deauthorizeMac(mac: String): Shell.Result {
        require(mac.matches(MAC_REGEX)) { "not a MAC address: $mac" }
        return run("sh $SETUP deauthorize $mac")
    }

    /** Reserves <mac> -> <ip> in dnsmasq so the client actually receives the assigned IP. */
    fun reserveIp(mac: String, ip: String): Shell.Result {
        require(mac.matches(MAC_REGEX)) { "not a MAC address: $mac" }
        require(ip.matches(IP_REGEX)) { "not an IPv4 address: $ip" }
        return run("sh $SETUP reserve $mac $ip")
    }

    fun releaseIp(mac: String): Shell.Result {
        require(mac.matches(MAC_REGEX)) { "not a MAC address: $mac" }
        return run("sh $SETUP unreserve $mac")
    }

    /**
     * DHCP leases from our dnsmasq and, if Android is still the DHCP server,
     * from its lease file too. "<expiry> <mac> <ip> <hostname> <clientid>".
     */
    fun readLeases(): List<String> = run(
        // "; true": a missing optional lease file must not report exit 1 - that
        // made every poll log a WARN (104 of them in the 2026-09-24 export).
        "cat $SCRIPT_DIR/dnsmasq.leases /data/misc/dhcp/dnsmasq.leases " +
            "/data/misc/dhcp/dnsmasq.tether.leases 2>/dev/null; true",
        quiet = true
    ).out

    /**
     * Devices currently on the LAN: lease file first (has the hostname), then
     * ARP for anyone who has an address but is not in a lease file we can read.
     */
    fun connectedClients(lanIf: String?): List<LeaseParser.Lease> {
        // Cached: the lease file and ARP are read by the service tick, the UI
        // poll and the voucher sweep within the same second.
        val arp = run("cat /proc/net/arp 2>/dev/null; true", quiet = true).out
        return LeaseParser.merge(
            LeaseParser.parse(leases()),
            LeaseParser.fromArp(arp, lanIf)
        )
    }

    /** Gateway, subnet and who is serving DHCP, as written by setup_network.sh. */
    fun readLanPlan(): LanPlan? = LanPlan.parse(readRuntimeFile())

    fun readRuntimeFile(): String =
        run("cat $RUNTIME_FILE 2>/dev/null; true", quiet = true).out.joinToString("\n")

    fun isDnsmasqRunning(): Boolean {
        val pid = run("cat $SCRIPT_DIR/dnsmasq_hotspot.pid", quiet = true).out
            .firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return run("kill -0 $pid", quiet = true).isSuccess
    }

    /** Is *another* dnsmasq (Android's) serving DHCP on the LAN interface? */
    fun isForeignDnsmasqRunning(): Boolean =
        run("sh $SETUP foreign-dhcp", quiet = true).out.any { it.trim() == "yes" }

    /** IPv4 address(es) currently on a LAN interface, e.g. ["10.66.0.1/24"]. */
    fun lanAddresses(lanIf: String): List<String> =
        run("ip -o -4 addr show dev $lanIf", quiet = true).out.mapNotNull { line ->
            Regex("(\\d+\\.\\d+\\.\\d+\\.\\d+/\\d+)").find(line)?.groupValues?.get(1)
        }

    fun initBandwidth(): Shell.Result = run("sh $SHAPER init")

    fun stopBandwidth(): Shell.Result = run("sh $SHAPER stop")

    fun addBandwidthClass(ip: String, classId: Int, rateKbit: Int, ceilKbit: Int): Shell.Result {
        require(ip.matches(IP_REGEX)) { "not an IPv4 address: $ip" }
        return run("sh $SHAPER add $ip $classId $rateKbit $ceilKbit")
    }

    fun removeBandwidthClass(ip: String, classId: Int): Shell.Result {
        require(ip.matches(IP_REGEX)) { "not an IPv4 address: $ip" }
        return run("sh $SHAPER remove $ip $classId")
    }

    /** Drops a tc class by id, whichever client IP it was attached to. */
    fun removeBandwidthByClass(classId: Int): Shell.Result =
        run("sh $SHAPER remove-class $classId")

    // --- environment / device introspection -------------------------------------

    /** System property, e.g. getprop("wifi.tethering.interface") -> "ap0". */
    fun getprop(name: String): String? =
        run("getprop $name", quiet = true).out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Every link-layer interface that exists right now, as (name, isUp) pairs.
     * Parses `ip -o link show`: "3: ccmni1: <NOARP,UP,LOWER_UP> ...".
     */
    fun interfaces(): List<Pair<String, Boolean>> =
        run("ip -o link show", quiet = true).out.mapNotNull { line ->
            val name = Regex("^[0-9]+: ([^:@]+)").find(line)?.groupValues?.get(1)?.trim()
                ?: return@mapNotNull null
            val up = Regex("<[^>]*UP[^>]*>").containsMatchIn(line)
            name to up
        }

    /** The interface that currently holds the default route (the internet side). */
    fun defaultRouteInterface(): String? =
        run("ip route show default", quiet = true).out
            .firstOrNull { it.contains(" dev ") }
            ?.let { Regex("dev (\\S+)").find(it)?.groupValues?.get(1) }

    /** Value of net.ipv4.ip_forward, or null when it could not be read. */
    fun ipForwardEnabled(): Boolean? =
        run("cat /proc/sys/net/ipv4/ip_forward 2>/dev/null", quiet = true).out
            .firstOrNull()?.trim()?.let { when (it) {
                "1" -> true
                "0" -> false
                else -> null
            } }

    /**
     * Is our jump still the first rule of [chain]? Android's tethering service
     * rewrites iptables when it wakes up; a jump that is no longer first means
     * someone else decided about the packet before we did.
     */
    fun jumpIsFirst(table: String, chain: String, target: String): Boolean? {
        val first = run("iptables -t $table -S $chain 2>/dev/null", quiet = true).out
            .firstOrNull { it.startsWith("-A ") } ?: return null
        return first == "-A $chain -j $target"
    }

    /**
     * Does the policy-routing rule that lets LAN traffic reach the main routing
     * table still exist? Without it, forwarded packets from a WiFi-Direct or
     * local-only-hotspot interface hit Android's trailing "unreachable" rule.
     */
    fun policyRoutingOk(lanIf: String): Boolean? {
        val rules = run("ip rule show 2>/dev/null", quiet = true).out
        if (rules.isEmpty()) return null // no root or no ip rule: unknown, do not alarm
        return rules.any { it.contains("iif $lanIf") && it.contains("lookup") }
    }

    /** Adds the routing rules a self-managed AP interface needs. Idempotent. */
    fun ensurePolicyRouting(lanIf: String, subnet: String): Shell.Result =
        run("sh $SETUP route $lanIf $subnet")

    /** Every dnsmasq/hostapd process with its full command line. */
    fun dhcpProcesses(): List<String> =
        run("sh $SETUP procs", quiet = true).out

    /**
     * Writes /data/local/tmp/hotspot.env from scratch. Keys and values are
     * validated tokens only (interface names), so plain echo quoting is safe.
     */
    fun writeEnvFile(values: Map<String, String>): Shell.Result {
        values.forEach { (k, v) ->
            require(k.matches(Regex("^[A-Z0-9_]+$"))) { "bad env key: $k" }
            require(v == "auto" || v.matches(IFACE_REGEX)) { "bad env value: $v" }
        }
        val sb = StringBuilder("rm -f $ENV_FILE; ")
        values.forEach { (k, v) -> sb.append("echo \"$k=$v\" >> $ENV_FILE; ") }
        return run(sb.toString())
    }

    fun readEnvFile(): String =
        run("cat $ENV_FILE 2>/dev/null; true", quiet = true).out.joinToString("\n")
            .ifBlank { "(not written yet)" }
}

