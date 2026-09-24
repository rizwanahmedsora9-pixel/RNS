package com.hotspot.billing.debug

/**
 * Everything the watchdog needs to decide whether the gateway is healthy, as
 * plain values. Collecting them touches the shell; *evaluating* them is a pure
 * function so the failure codes are unit-tested.
 *
 * Every finding carries a stable code (H1..H12). The operator can say
 * "it logged H3" and that is enough to know exactly what broke.
 */
data class HealthInput(
    val lanIf: String?,
    val lanInterfaceExists: Boolean,
    val lanAddresses: List<String>,
    val expectedGateway: String?,
    val ipForwardEnabled: Boolean?,
    val ourDnsmasqRunning: Boolean,
    val foreignDnsmasqRunning: Boolean,
    val dhcpOwner: String?,
    val portalAlive: Boolean,
    val portalProbeStatus: Int?,
    val natJumpFirst: Boolean?,
    val forwardJumpFirst: Boolean?,
    val wanIf: String?,
    val defaultRouteIf: String?,
    val policyRoutingOk: Boolean?,
    val connectedClients: Int,
    val authorizedClients: Int,
    val apKind: String?
)

data class Finding(
    val code: String,
    val level: LogLevel,
    val problem: String,
    val hint: String
) {
    /** One loggable line: `H3 no DHCP server is running - clients stay on "Obtaining IP"`. */
    fun format(): String = "$code $problem\n       fix: $hint"
}

/** Pure evaluation of [HealthInput] -> findings, worst first. */
object GatewayHealth {

    fun evaluate(input: HealthInput): List<Finding> {
        val found = ArrayList<Finding>()

        val lan = input.lanIf
        if (lan == null || !input.lanInterfaceExists) {
            found += Finding(
                "H1", LogLevel.ERROR,
                "the hotspot interface (${lan ?: "none"}) is gone - the AP is off",
                "use NetShare mode in Settings, or switch the Android hotspot on; " +
                    "the gateway re-arms itself the moment an interface appears"
            )
            return found // nothing downstream makes sense without an interface
        }

        if (input.expectedGateway != null &&
            input.lanAddresses.none { it.substringBefore('/') == input.expectedGateway }
        ) {
            found += Finding(
                "H2", LogLevel.WARN,
                "the address on $lan is ${input.lanAddresses.joinToString().ifBlank { "missing" }}, " +
                    "expected ${input.expectedGateway}",
                "Android moved the interface address; the gateway re-reads and re-configures " +
                    "itself automatically - clients must forget the WiFi and rejoin once"
            )
        }

        if (input.ipForwardEnabled == false) {
            found += Finding(
                "H4", LogLevel.ERROR,
                "IP forwarding is off (net.ipv4.ip_forward=0) - clients get an IP but no internet",
                "something switched forwarding back off; the watchdog turns it on again"
            )
        }

        val anyDhcp = input.ourDnsmasqRunning || input.foreignDnsmasqRunning ||
            input.dhcpOwner == "android"
        if (!anyDhcp) {
            found += Finding(
                "H3", LogLevel.ERROR,
                "no DHCP server is running on $lan - every client sticks on \"Obtaining IP address\"",
                "dnsmasq could not bind; check whether Android's own server holds port 67 " +
                    "(the report's DHCP section shows both)"
            )
        }

        if (!input.portalAlive) {
            found += Finding(
                "H5", LogLevel.ERROR,
                "the captive portal is not listening on port 8080 - no sign-in sheet can appear",
                "the NanoHTTPD server died; restarting the gateway (Stop then Start) brings it back"
            )
        } else if (input.portalProbeStatus != null && input.portalProbeStatus != 200) {
            found += Finding(
                "H10", LogLevel.WARN,
                "the portal answered the probe with HTTP ${input.portalProbeStatus} instead of 200",
                "a probe that is not exactly 200 does not pop the sign-in sheet on Android/iOS"
            )
        }

        if (input.natJumpFirst == false) {
            found += Finding(
                "H6", LogLevel.WARN,
                "our nat PREROUTING jump is no longer first - Android rewrote iptables",
                "the watchdog re-inserts it; if this repeats, a system service is fighting us"
            )
        }
        if (input.forwardJumpFirst == false) {
            found += Finding(
                "H6", LogLevel.WARN,
                "our filter FORWARD jump is no longer first - clients may bypass the gate",
                "the watchdog re-inserts it; if this repeats, a system service is fighting us"
            )
        }

        val wan = input.defaultRouteIf
        if (wan == null) {
            found += Finding(
                "H7", LogLevel.ERROR,
                "there is no default route - the phone itself has no internet to share",
                "connect the phone to WiFi or mobile data first; the gateway shares whatever " +
                    "the phone has"
            )
        } else if (input.wanIf != null && input.wanIf != wan) {
            found += Finding(
                "H7", LogLevel.WARN,
                "the internet side changed from ${input.wanIf} to $wan",
                "the gateway re-applies NAT for the new interface automatically"
            )
        }

        if (input.policyRoutingOk == false) {
            found += Finding(
                "H8", LogLevel.ERROR,
                "the routing rule for $lan is missing - forwarded packets hit Android's " +
                    "\"unreachable\" rule and are dropped",
                "this is the NetShare/WiFi-Direct path: `ip rule add iif $lan lookup main` " +
                    "must exist; the watchdog re-adds it"
            )
        }

        if (input.connectedClients > 0 && input.authorizedClients == 0 &&
            input.portalProbeStatus != null
        ) {
            found += Finding(
                "H9", LogLevel.INFO,
                "${input.connectedClients} client(s) on the LAN and none of them has a voucher yet",
                "normal until they redeem; if they never see the sign-in sheet, look at the " +
                    "portal request lines in this log"
            )
        }

        return found.sortedByDescending { it.level.priority }
    }

    /** Findings that are worth putting on the notification / dashboard. */
    fun headline(findings: List<Finding>): String? =
        findings.firstOrNull { it.level.priority >= LogLevel.WARN.priority }
            ?.let { "${it.code} ${it.problem}" }
}
