package com.hotspot.billing.net

/**
 * What the phone's radio side looks like right now.
 *
 * Half of it comes from ONE root command ([com.hotspot.billing.util.RootShell.radioSnapshot]):
 * links, addresses, the default route, the vendor properties, whether a hostapd
 * process exists, the hostapd config the framework wrote, and our own
 * netshare runtime file. The other half can only come from the framework
 * (`getWifiApState`, `requestGroupInfo`, a LocalOnlyHotspot reservation we hold)
 * and is filled in by [SoftApController] with `copy(...)`.
 *
 * Keeping the two together in one value is the point: deciding whether an
 * interface is *really* an AP needs all of it, and the decision itself is pure
 * so the rule that the 2026-09-24 14:42 log broke is unit-tested.
 */
data class RadioSnapshot(
    /** Every interface: name, administratively up, carrier/link up, IPv4 addresses. */
    val interfaces: List<Iface> = emptyList(),
    val defaultRoute: String? = null,
    /** `getprop wifi.tethering.interface` - "ap0" on the Infinix Hot 8. */
    val tetherIfaceProp: String? = null,
    /** `getprop wifi.direct.interface` - "p2p0" on the Infinix Hot 8. */
    val p2pIfaceProp: String? = null,
    /** A hostapd process exists (the vendor HAL runs one while a softap is up). */
    val hostapdProcess: Boolean = false,
    /** hostapd.conf as the framework wrote it, when it is readable. */
    val hostapdConf: ApConfigText.ApConfig? = null,
    /** netshare.runtime of our own root hostapd, when the file exists. */
    val ourHostapd: Map<String, String> = emptyMap(),

    // ---- framework facts (never from the shell) ----
    /** `WifiManager.getWifiApState()`; null when this build hides it from us. */
    val frameworkApState: Int? = null,
    val frameworkApSsid: String? = null,
    val frameworkApPassword: String? = null,
    /** We are holding a LocalOnlyHotspotReservation in this process. */
    val localOnlyReservation: Boolean = false,
    /** `requestGroupInfo` said we own a group (true), said there is none (false), or never answered (null). */
    val p2pGroupOwner: Boolean? = null,
    val p2pGroupIface: String? = null,
    val p2pGroupSsid: String? = null,
    val p2pGroupPassphrase: String? = null
) {

    data class Iface(
        val name: String,
        val up: Boolean,
        val carrier: Boolean,
        val addresses: List<String>
    ) {
        fun address(): String? = addresses.firstOrNull()
    }

    fun iface(name: String?): Iface? =
        if (name.isNullOrBlank()) null else interfaces.firstOrNull { it.name == name }

    fun names(): List<String> = interfaces.map { it.name }

    /**
     * Parses the output of the one snapshot command. Sections are introduced by
     * `@NAME` markers so a missing or failing sub-command cannot shift the
     * meaning of the lines that follow it.
     */
    companion object {
        fun parse(lines: List<String>): RadioSnapshot {
            val sections = LinkedHashMap<String, MutableList<String>>()
            var current: String? = null
            for (raw in lines) {
                val line = raw.trimEnd()
                val trimmed = line.trim()
                if (trimmed.startsWith("@") && trimmed.length <= 12 &&
                    trimmed.substring(1).all { it.isLetterOrDigit() }
                ) {
                    current = trimmed.substring(1)
                    sections.getOrPut(current!!) { mutableListOf() }
                    continue
                }
                val key = current ?: continue
                sections.getOrPut(key) { mutableListOf() }.add(line)
            }

            val interfaces = LinkedHashMap<String, Iface>()
            for (line in sections["LINK"] ?: emptyList()) {
                val name = Regex("^[0-9]+: ([^:@]+)").find(line)?.groupValues?.get(1)?.trim()
                    ?: continue
                val flags = Regex("<([^>]*)>").find(line)?.groupValues?.get(1) ?: ""
                val up = flags.split(',').any { it.trim() == "UP" }
                val carrier = flags.split(',').any { it.trim() == "LOWER_UP" }
                interfaces[name] = Iface(name, up, carrier, mutableListOf())
            }
            for (line in sections["ADDR"] ?: emptyList()) {
                // "7: p2p0    inet 192.168.49.1/24 brd ... scope global p2p0\ ..."
                val parts = line.trim().split(Regex("\\s+"))
                val name = parts.getOrNull(1)?.trim()?.removeSuffix(":") ?: continue
                val inetIndex = parts.indexOf("inet")
                val addr = if (inetIndex >= 0) parts.getOrNull(inetIndex + 1) else null
                if (addr == null || !addr.contains('.')) continue
                val known = interfaces[name]
                val addresses = (known?.addresses ?: mutableListOf()).toMutableList()
                addresses.add(addr)
                interfaces[name] = known?.copy(addresses = addresses)
                    ?: Iface(name, up = false, carrier = false, addresses = addresses)
            }

            val route = (sections["ROUTE"] ?: emptyList())
                .firstOrNull { it.contains(" dev ") }
                ?.let { Regex("dev (\\S+)").find(it)?.groupValues?.get(1) }

            val props = (sections["PROP"] ?: emptyList()).map { it.trim() }
            val proc = (sections["PROC"] ?: emptyList()).joinToString("\n")

            return RadioSnapshot(
                interfaces = interfaces.values.toList(),
                defaultRoute = route,
                tetherIfaceProp = props.getOrNull(0)?.takeIf { it.isNotBlank() && it != "-" },
                p2pIfaceProp = props.getOrNull(1)?.takeIf { it.isNotBlank() && it != "-" },
                hostapdProcess = proc.lineSequence().any { it.contains("hostapd") },
                hostapdConf = ApConfigText.parseHostapd(
                    (sections["CONF"] ?: emptyList()).joinToString("\n")
                ),
                ourHostapd = ApConfigText.parseKeyValue(
                    (sections["NETSHARE"] ?: emptyList()).joinToString("\n")
                )
            )
        }
    }
}

/** The answer: which interface customers can join, and *why* we believe it. */
data class ApDecision(
    val iface: String?,
    val kind: ApKind?,
    val ssid: String?,
    val password: String?,
    /** One line for the debugger: the evidence, or "nothing is beaconing". */
    val proof: String,
    /** Every candidate that was rejected, with the reason. This is the diagnosis. */
    val rejected: List<String> = emptyList()
) {
    val isBeaconing: Boolean get() = iface != null

    /** The rejected candidates as one readable block, for the event log. */
    fun rejectionLines(): List<String> = rejected
}

/**
 * Is that interface *actually* an access point a customer can join?
 *
 * Why this object exists: the 2026-09-24 14:42 export shows the gateway
 * "adopting" `p2p0` because `ip link` said UP, assigning 10.66.0.1/24 to it,
 * starting dnsmasq on it and reporting a running hotspot - while nothing was
 * beaconing. `p2p0` is the WiFi Direct interface: on this MediaTek build it is
 * created and brought UP as soon as WiFi is on, and it stays UP after a group is
 * removed (`P2P-GROUP-REMOVED ... AP-DISABLED` is followed by
 * `interfaceLinkStateChanged, iface: p2p0, up: true` in the 12:37 log). Link-up
 * is therefore *not* evidence of an AP, and adopting on link-up alone silently
 * produced a hotspot nobody could join.
 *
 * The rule: an interface counts only with positive evidence -
 *
 *  - our own root hostapd runtime file names it,
 *  - the framework says the softap is ENABLED (or is still ENABLING right after
 *    we asked it to start), or a hostapd process is running for it,
 *  - the framework says we own a WiFi Direct group, or the interface carries
 *    Android's group-owner address (192.168.49.1) and the framework does not
 *    contradict that,
 *  - it is a wired LAN (usb0/eth0) with link.
 *
 * The address this app assigns itself (10.66.0.1) is never evidence: a leftover
 * from the previous session is exactly what made a dead interface look alive.
 */
object ApEvidence {

    /** `WifiManager.WIFI_AP_STATE_*` (the framework constants, not the broadcast ones). */
    const val AP_STATE_DISABLING = 10
    const val AP_STATE_DISABLED = 11
    const val AP_STATE_ENABLING = 12
    const val AP_STATE_ENABLED = 13
    const val AP_STATE_FAILED = 14

    /** The fallback address `setup_network.sh configure_lan_address` writes. */
    const val OUR_FALLBACK_ADDRESS = "10.66.0.1"

    /** Android's fixed subnet for a WiFi Direct group owner. */
    const val P2P_GO_PREFIX = "192.168.49."

    /** Subnets the Android tethering stack itself writes on a softap interface. */
    private val FRAMEWORK_TETHER_PREFIXES = listOf(
        "192.168.42.", "192.168.43.", "192.168.44.", "192.168.49.", "192.168.137.", "192.168.50."
    )

    private class Proof(
        val kind: ApKind,
        val proof: String,
        val ssid: String? = null,
        val password: String? = null
    )

    /**
     * @param pin an interface the operator pinned in Settings. A pin still has to
     *   pass the evidence test - pinning `p2p0` is how the dead-interface bug was
     *   reproduced by hand - but it is checked first, and its rejection is the
     *   first line the operator reads.
     * @param expectStart true while we are waiting for an AP we just asked the
     *   framework to create: ENABLING then counts, because that is the state it
     *   is in for the first few hundred milliseconds.
     */
    fun evaluate(snap: RadioSnapshot, pin: String?, expectStart: Boolean = false): ApDecision {
        val rejected = ArrayList<String>()
        for (name in candidateOrder(snap, pin)) {
            val iface = snap.iface(name)
            if (iface == null) {
                rejected += "$name: no such interface"
                continue
            }
            if (name == snap.defaultRoute) {
                rejected += "$name: that is the internet side (it holds the default route)"
                continue
            }
            if (!iface.up) {
                rejected += "$name: exists but is DOWN"
                continue
            }
            val proof = proofFor(snap, iface, expectStart)
            if (proof == null) {
                rejected += "$name: ${whyNot(snap, iface)}"
                continue
            }
            return ApDecision(
                iface = iface.name,
                kind = proof.kind,
                ssid = proof.ssid ?: snap.hostapdConf?.ssid ?: snap.frameworkApSsid,
                password = proof.password ?: snap.hostapdConf?.passphrase ?: snap.frameworkApPassword,
                proof = proof.proof,
                rejected = rejected
            )
        }
        return ApDecision(
            iface = null,
            kind = null,
            ssid = null,
            password = null,
            proof = "nothing on this phone is beaconing a WiFi network right now",
            rejected = rejected
        )
    }

    /** Pin first, then the vendor's tether interface, then the usual suspects. */
    private fun candidateOrder(snap: RadioSnapshot, pin: String?): List<String> {
        val order = LinkedHashSet<String>()
        pin?.takeIf { it.isNotBlank() }?.let { order.add(it) }
        snap.ourHostapd["IFACE"]?.takeIf { it.isNotBlank() }?.let { order.add(it) }
        snap.p2pGroupIface?.takeIf { it.isNotBlank() }?.let { order.add(it) }
        snap.hostapdConf?.interfaceName?.takeIf { it.isNotBlank() }?.let { order.add(it) }
        snap.tetherIfaceProp?.takeIf { it.isNotBlank() }?.let { order.add(it) }
        snap.p2pIfaceProp?.takeIf { it.isNotBlank() }?.let { order.add(it) }
        // Everything that is up, in the order the well-known names are listed, so
        // a vendor name we have never seen still gets a chance.
        val up = snap.interfaces.filter { it.up }.map { it.name }
        for (candidate in ApPlan.AP_CANDIDATES) if (candidate in up) order.add(candidate)
        for (name in up) if (ApPlan.isWifiRadio(name)) order.add(name)
        return order.toList()
    }

    private fun proofFor(snap: RadioSnapshot, iface: RadioSnapshot.Iface, expectStart: Boolean): Proof? {
        val name = iface.name
        val address = iface.address()
        val state = snap.frameworkApState

        // 1. Our own root hostapd, still running from this or a previous process.
        if (snap.ourHostapd["IFACE"] == name) {
            val live = snap.hostapdProcess || address != null
            if (live) {
                return Proof(
                    ApKind.ROOT_HOSTAPD,
                    "our root hostapd is running on $name (netshare.runtime" +
                        ", channel ${snap.ourHostapd["CHANNEL"] ?: "?"})",
                    snap.ourHostapd["SSID"],
                    snap.ourHostapd["PASS"]
                )
            }
        }

        // 2. WiFi Direct group owner - the method that demonstrably worked on the
        //    Hot 8 (2026-09-24 12:37: AP-ENABLED, a client joined, DHCP + NAT ran).
        if (isP2pIface(snap, name)) {
            when {
                snap.p2pGroupOwner == true -> return Proof(
                    ApKind.WIFI_DIRECT,
                    "the framework reports a WiFi Direct group we own on $name",
                    snap.p2pGroupSsid,
                    snap.p2pGroupPassphrase
                )
                snap.p2pGroupOwner == false -> return null // definitive: no group
                address != null && address.startsWith(P2P_GO_PREFIX) -> return Proof(
                    ApKind.WIFI_DIRECT,
                    "$name carries Android's WiFi Direct group-owner address $address " +
                        "(the framework did not answer requestGroupInfo)",
                    snap.p2pGroupSsid,
                    snap.p2pGroupPassphrase
                )
                else -> return null
            }
        }

        // 3. Wired LAN (phone -> Router2 over USB-OTG). Link, not just admin-up.
        if (isWiredIface(name)) {
            return if (iface.carrier || address != null) {
                Proof(
                    ApKind.MANUAL_TOGGLE,
                    "$name is a wired LAN with link" + (address?.let { " and address $it" } ?: ""),
                    null,
                    null
                )
            } else {
                null
            }
        }

        // 4. The framework's own softap (ap0/swlan0/...), including the local-only
        //    hotspot, which the same state machine drives.
        if (isTetherIface(snap, name)) {
            if (state == AP_STATE_ENABLED) {
                val kind = if (snap.localOnlyReservation) ApKind.LOCAL_ONLY else ApKind.SYSTEM_HOTSPOT
                return Proof(
                    kind,
                    "WifiManager.getWifiApState()=ENABLED and $name is the tethering interface",
                    snap.frameworkApSsid ?: snap.hostapdConf?.ssid,
                    snap.frameworkApPassword ?: snap.hostapdConf?.passphrase
                )
            }
            if (expectStart && state == AP_STATE_ENABLING) {
                return Proof(
                    ApKind.SYSTEM_HOTSPOT,
                    "$name is coming up (getWifiApState()=ENABLING)",
                    snap.hostapdConf?.ssid,
                    snap.hostapdConf?.passphrase
                )
            }
            // A hostapd process is only meaningful *with* the config the
            // framework wrote for this interface - the vendor HAL daemon can be
            // alive while nothing is beaconing.
            if (snap.hostapdProcess && snap.hostapdConf?.interfaceName == name) {
                return Proof(
                    ApKind.SYSTEM_HOTSPOT,
                    "a hostapd process is running for $name (ssid ${snap.hostapdConf.ssid})",
                    snap.hostapdConf.ssid,
                    snap.hostapdConf.passphrase
                )
            }
            // The framework state could not be read on this build: fall back to
            // the address, but never to one this app wrote itself.
            if (state == null && address != null && !isOurOwnAddress(address) &&
                isFrameworkTetherAddress(address)
            ) {
                return Proof(
                    ApKind.SYSTEM_HOTSPOT,
                    "$name holds $address, an address the tethering stack assigns " +
                        "(getWifiApState() is not readable on this build)",
                    snap.hostapdConf?.ssid,
                    snap.hostapdConf?.passphrase
                )
            }
            // The framework answered and it says no: a leftover address on a
            // tether interface is a leftover, full stop. Do not look further.
            return null
        }

        // 5. A vendor AP name we do not know, with a real address on it.
        if (address != null && !isOurOwnAddress(address)) {
            return Proof(
                ApKind.SYSTEM_HOTSPOT,
                "$name is up with $address (an address this app did not assign)",
                snap.hostapdConf?.ssid,
                snap.hostapdConf?.passphrase
            )
        }
        return null
    }

    /** The sentence the operator reads in the debugger when a candidate is skipped. */
    fun whyNot(snap: RadioSnapshot, iface: RadioSnapshot.Iface): String {
        val name = iface.name
        val address = iface.address()
        val state = snap.frameworkApState
        return when {
            isP2pIface(snap, name) && snap.p2pGroupOwner == false ->
                "up, but the framework reports NO WiFi Direct group - $name is the WiFi Direct " +
                    "interface, not a hotspot. It is created and brought up whenever WiFi is on, " +
                    "and stays up after a group is removed."
            isP2pIface(snap, name) ->
                "up, but nothing is beaconing on it: no WiFi Direct group and no group-owner " +
                    "address (${address ?: "no IPv4 address at all"})."
            isWiredIface(name) ->
                "no link on the wired side (plug the OTG cable / Router2 in)"
            state == AP_STATE_DISABLED || state == AP_STATE_FAILED ->
                "the framework says the hotspot is ${if (state == AP_STATE_FAILED) "FAILED" else "DISABLED"} " +
                    "(getWifiApState()=$state), so ${address ?: "its address"} is a leftover, not an AP"
            isOurOwnAddress(address ?: "") ->
                "up with $address, but that is the address THIS app wrote last time - a leftover " +
                    "on a dead interface, not a running hotspot"
            address == null -> "up with no IPv4 address and no hostapd behind it"
            else -> "up, but no evidence that an AP is running on it"
        }
    }

    fun isP2pIface(snap: RadioSnapshot, name: String): Boolean {
        if (snap.p2pIfaceProp != null && snap.p2pIfaceProp == name) return true
        if (snap.p2pGroupIface != null && snap.p2pGroupIface == name) return true
        return ApConfigText.looksLikeP2pInterface(name)
    }

    fun isWiredIface(name: String): Boolean {
        val n = name.lowercase()
        return n == "usb0" || n == "eth0" || n.startsWith("eth") || n.startsWith("usb") ||
            n.startsWith("rndis")
    }

    fun isTetherIface(snap: RadioSnapshot, name: String): Boolean {
        val n = name.lowercase()
        if (snap.tetherIfaceProp == name) return true
        if (snap.hostapdConf?.interfaceName == name) return true
        return n == "ap0" || n == "ap1" || n.startsWith("swlan") || n.startsWith("softap") ||
            n.startsWith("wlan_ap") || n.startsWith("uap") || n.startsWith("rnsap") ||
            n == "wlan1" || n == "wlan2"
    }

    /** The fallback address our own script writes when an interface has none. */
    fun isOurOwnAddress(address: String?): Boolean {
        val ip = (address ?: return false).substringBefore('/')
        return ip == OUR_FALLBACK_ADDRESS
    }

    fun isFrameworkTetherAddress(address: String?): Boolean {
        val ip = (address ?: return false).substringBefore('/')
        return FRAMEWORK_TETHER_PREFIXES.any { ip.startsWith(it) }
    }

    /** Human words for a `getWifiApState()` value. */
    fun describeApState(state: Int?): String = when (state) {
        null -> "unreadable on this build"
        AP_STATE_DISABLING -> "DISABLING"
        AP_STATE_DISABLED -> "DISABLED"
        AP_STATE_ENABLING -> "ENABLING"
        AP_STATE_ENABLED -> "ENABLED"
        AP_STATE_FAILED -> "FAILED"
        else -> "unknown ($state)"
    }
}
