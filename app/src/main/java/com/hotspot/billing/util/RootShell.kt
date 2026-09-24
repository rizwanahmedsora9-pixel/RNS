package com.hotspot.billing.util

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.LogLevel
import com.hotspot.billing.net.LanPlan
import com.hotspot.billing.net.LeaseParser
import com.topjohnwu.superuser.Shell

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

    private val MAC_REGEX = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
    private val IP_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val IFACE_REGEX = Regex("^[a-zA-Z0-9._-]{1,15}$")

    /**
     * Runs [cmd] in the (root) shell and records it.
     *
     * @param quiet record at VERBOSE instead of INFO. For the polls that run
     *   every couple of seconds, so the readable log stays about *events*.
     */
    fun run(cmd: String, quiet: Boolean = false): Shell.Result {
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

    fun startNetwork(): Shell.Result = run("sh $SETUP start")

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
        "cat $SCRIPT_DIR/dnsmasq.leases /data/misc/dhcp/dnsmasq.leases " +
            "/data/misc/dhcp/dnsmasq.tether.leases 2>/dev/null",
        quiet = true
    ).out

    /**
     * Devices currently on the LAN: lease file first (has the hostname), then
     * ARP for anyone who has an address but is not in a lease file we can read.
     */
    fun connectedClients(lanIf: String?): List<LeaseParser.Lease> {
        val arp = run("cat /proc/net/arp 2>/dev/null", quiet = true).out
        return LeaseParser.merge(
            LeaseParser.parse(readLeases()),
            LeaseParser.fromArp(arp, lanIf)
        )
    }

    /** Gateway, subnet and who is serving DHCP, as written by setup_network.sh. */
    fun readLanPlan(): LanPlan? = LanPlan.parse(readRuntimeFile())

    fun readRuntimeFile(): String =
        run("cat $RUNTIME_FILE 2>/dev/null", quiet = true).out.joinToString("\n")

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
        run("cat $ENV_FILE 2>/dev/null", quiet = true).out.joinToString("\n")
            .ifBlank { "(not written yet)" }
}

