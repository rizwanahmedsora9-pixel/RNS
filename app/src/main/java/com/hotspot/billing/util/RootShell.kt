package com.hotspot.billing.util

import com.hotspot.billing.net.LanPlan
import com.hotspot.billing.net.LeaseParser
import com.topjohnwu.superuser.Shell

/**
 * Thin wrapper around libsu for running rooted shell commands.
 *
 * Every command is an argument-vector handed to the two bundled scripts; nothing
 * interpolates user input into a shell string except the MAC/IP values, which are
 * validated at the call sites.
 */
object RootShell {

    private const val SCRIPT_DIR = "/data/local/tmp"
    private const val SETUP = "$SCRIPT_DIR/setup_network.sh"
    private const val SHAPER = "$SCRIPT_DIR/bandwidth_control.sh"
    private const val ENV_FILE = "$SCRIPT_DIR/hotspot.env"
    private const val RUNTIME_FILE = "$SCRIPT_DIR/hotspot.runtime"

    private val MAC_REGEX = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
    private val IP_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val IFACE_REGEX = Regex("^[a-zA-Z0-9._-]{1,15}$")

    fun isRootAvailable(): Boolean = Shell.getShell().isRoot

    fun run(cmd: String): Shell.Result = Shell.cmd(cmd).exec()

    fun startNetwork(): Shell.Result = run("sh $SETUP start")

    fun stopNetwork(): Shell.Result = run("sh $SETUP stop")

    /** Re-assert DHCP without flushing the hotspot address. Safe to call often. */
    fun keepaliveNetwork(): Shell.Result = run("sh $SETUP keepalive")

    fun networkStatus(): List<String> = run("sh $SETUP status").out

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
            "/data/misc/dhcp/dnsmasq.tether.leases 2>/dev/null"
    ).out

    /**
     * Devices currently on the LAN: lease file first (has the hostname), then
     * ARP for anyone who has an address but is not in a lease file we can read.
     */
    fun connectedClients(lanIf: String?): List<LeaseParser.Lease> {
        val arp = run("cat /proc/net/arp 2>/dev/null").out
        return LeaseParser.merge(
            LeaseParser.parse(readLeases()),
            LeaseParser.fromArp(arp, lanIf)
        )
    }

    /** Gateway, subnet and who is serving DHCP, as written by setup_network.sh. */
    fun readLanPlan(): LanPlan? = LanPlan.parse(readRuntimeFile())

    fun readRuntimeFile(): String =
        run("cat $RUNTIME_FILE 2>/dev/null").out.joinToString("\n")

    fun isDnsmasqRunning(): Boolean {
        val pid = run("cat $SCRIPT_DIR/dnsmasq_hotspot.pid").out
            .firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return run("kill -0 $pid").isSuccess
    }

    /** IPv4 address(es) currently on a LAN interface, e.g. ["10.66.0.1/24"]. */
    fun lanAddresses(lanIf: String): List<String> =
        run("ip -o -4 addr show dev $lanIf").out.mapNotNull { line ->
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
        run("getprop $name").out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Every link-layer interface that exists right now, as (name, isUp) pairs.
     * Parses `ip -o link show`: "3: ccmni1: <NOARP,UP,LOWER_UP> ...".
     */
    fun interfaces(): List<Pair<String, Boolean>> =
        run("ip -o link show").out.mapNotNull { line ->
            val name = Regex("^[0-9]+: ([^:@]+)").find(line)?.groupValues?.get(1)?.trim()
                ?: return@mapNotNull null
            val up = Regex("<[^>]*UP[^>]*>").containsMatchIn(line)
            name to up
        }

    /** The interface that currently holds the default route (the internet side). */
    fun defaultRouteInterface(): String? =
        run("ip route show default").out
            .firstOrNull { it.contains(" dev ") }
            ?.let { Regex("dev (\\S+)").find(it)?.groupValues?.get(1) }

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
        run("cat $ENV_FILE 2>/dev/null").out.joinToString("\n").ifBlank { "(not written yet)" }
}
