package com.hotspot.billing.util

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

    private val MAC_REGEX = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
    private val IP_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isRootAvailable(): Boolean = Shell.getShell().isRoot

    fun run(cmd: String): Shell.Result = Shell.cmd(cmd).exec()

    fun startNetwork(): Shell.Result = run("sh $SETUP start")

    fun stopNetwork(): Shell.Result = run("sh $SETUP stop")

    fun authorizeMac(mac: String, ip: String): Shell.Result {
        require(mac.matches(MAC_REGEX)) { "not a MAC address: $mac" }
        require(ip.matches(IP_REGEX)) { "not an IPv4 address: $ip" }
        return run("sh $SETUP authorize $mac $ip")
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

    /** Current dnsmasq leases: "<expiry> <mac> <ip> <hostname> <clientid>" per line. */
    fun readLeases(): List<String> = run("cat $SCRIPT_DIR/dnsmasq.leases").out

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
}
