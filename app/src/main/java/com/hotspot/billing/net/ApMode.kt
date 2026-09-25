package com.hotspot.billing.net

/**
 * How the phone should create the network the customers join.
 *
 * The point of the NetShare-style modes is that the phone keeps its own WiFi
 * connection (that is the internet side) and creates a *second* WiFi network at
 * the same time, so nobody has to touch the Android hotspot toggle - and on
 * Android 9/10 there is no root command that flips that toggle anyway.
 */
enum class ApMode(val key: String, val label: String, val description: String) {

    AUTO(
        "auto", "Automatic (recommended)",
        "Turn WiFi and Location on, then try the system hotspot (what the Hot 8 can " +
            "actually start), local-only hotspot, WiFi Direct, and root hostapd. " +
            "On a WiFi uplink the system hotspot is tried last so it does not " +
            "disconnect the internet."
    ),

    SYSTEM(
        "system", "Android hotspot only",
        "Use the phone's real hotspot (ap0). The app starts it as root — a normal " +
            "app is not allowed to, which is why the Hot 8 log shows a SecurityException " +
            "from the app uid. On a single-radio phone this disconnects a WiFi uplink."
    ),

    NETSHARE(
        "netshare", "NetShare - WiFi Direct AP",
        "Creates a WiFi Direct group the phone owns (DIRECT-xx network). Needs WiFi " +
            "and Location switched on — while either is off, createGroup returns BUSY, " +
            "which is the disabled state, not another group. If Direct still refuses, " +
            "the system hotspot is tried so the gateway is not stuck waiting."
    ),

    LOCAL_ONLY(
        "localonly", "Local-only hotspot API",
        "Android's LocalOnlyHotspot: an AP the app can create without the toggle. " +
            "The system gives it no internet by design - this app adds the NAT itself " +
            "with root, which is what makes it usable."
    ),

    ROOT_AP(
        "rootap", "Root hostapd (experimental)",
        "Asks the WiFi driver for a second interface and runs hostapd on it as root. " +
            "Only works when the chip supports STA+AP concurrency."
    ),

    MANUAL(
        "manual", "Wait for the toggle",
        "Do not create anything; configure the gateway the moment a hotspot interface " +
            "appears."
    );

    companion object {
        fun from(key: String?): ApMode = values().firstOrNull { it.key == key } ?: AUTO
    }
}

/** What actually ended up providing the AP. */
enum class ApKind(val label: String) {
    SYSTEM_HOTSPOT("Android hotspot"),
    LOCAL_ONLY("local-only hotspot"),
    WIFI_DIRECT("WiFi Direct group (NetShare)"),
    ROOT_HOSTAPD("root hostapd"),
    MANUAL_TOGGLE("manual toggle")
}

/**
 * A running AP we can hand to the gateway. [onClose] releases the framework
 * reservation/group; it must be called on teardown or the network stays up after
 * the app is stopped (and Android will not let us create a new one).
 */
class ApHandle(
    val kind: ApKind,
    val interfaceName: String?,
    val ssid: String?,
    val password: String?,
    val detail: String = "",
    /**
     * True when Android's tether stack owns DHCP for this AP. Killing that
     * dnsmasq makes the Hot 8 run stopSoftAp.
     */
    val leaveAndroidDhcp: Boolean = false,
    private val onClose: (() -> Unit)? = null
) {
    @Volatile private var closed = false

    fun isClosed(): Boolean = closed

    @Synchronized
    fun close(log: (String) -> Unit = {}) {
        if (closed) return
        closed = true
        val action = onClose
        if (action == null) {
            log("ap: nothing to release for ${kind.label}")
            return
        }
        try {
            action()
            log("ap: released ${kind.label}" + (interfaceName?.let { " ($it)" } ?: ""))
        } catch (e: Throwable) {
            log("ap: releasing ${kind.label} failed - ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** One line for the dashboard: what customers have to join. */
    fun joinInstructions(): String {
        val name = ssid ?: "(unknown SSID)"
        val pass = password ?: "(no password reported)"
        return "Join WiFi \"$name\" password \"$pass\"" +
            (interfaceName?.let { " - interface $it, ${kind.label}" } ?: " - ${kind.label}")
    }

    override fun toString(): String =
        "ApHandle(${kind.label}, if=$interfaceName, ssid=$ssid, detail=$detail)"
}

/**
 * Small pure helpers around AP credentials. Kept free of Android types so the
 * parsing rules are unit-tested.
 */
object ApConfigText {

    data class ApConfig(
        val interfaceName: String? = null,
        val ssid: String? = null,
        val passphrase: String? = null,
        val channel: Int? = null
    )

    /**
     * hostapd.conf as the system writes it (`/data/vendor/wifi/hostapd/hostapd.conf`).
     * This is the authoritative SSID/passphrase for whatever AP the phone is
     * running right now, including a local-only hotspot on Android 9/10 where the
     * public API hides them.
     */
    fun parseHostapd(text: String): ApConfig? {
        var iface: String? = null
        var ssid: String? = null
        var pass: String? = null
        var channel: Int? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (value.isEmpty()) continue
            when (key) {
                "interface" -> if (iface == null) iface = value
                "ssid" -> if (ssid == null) ssid = value
                "wpa_passphrase" -> if (pass == null) pass = value
                "channel" -> if (channel == null) channel = value.toIntOrNull()
            }
        }
        if (iface == null && ssid == null && pass == null) return null
        return ApConfig(iface, ssid, pass, channel)
    }

    /**
     * WiFi Direct group names must match `^DIRECT-[a-zA-Z0-9]{2}` or the framework
     * throws IllegalArgumentException - a crash the operator would see as
     * "the AP just does not start".
     */
    fun normalizeDirectSsid(requested: String?): String {
        val raw = (requested ?: "").trim()
        val withoutPrefix = if (raw.startsWith("DIRECT-")) raw.removePrefix("DIRECT-") else raw
        val body = withoutPrefix.replace(Regex("[^a-zA-Z0-9]"), "")
        val suffix = when {
            body.length >= 2 -> body.take(16)
            body.isEmpty() -> "RNS"
            else -> body + "X"
        }
        return "DIRECT-$suffix"
    }

    /**
     * WPA2 SSIDs are 1..32 printable ASCII characters.
     *
     * On top of the printable-ASCII restriction, the four characters that can
     * terminate or escape a quoted shell word are dropped: `"` `` ` `` `$` and
     * `\`. Every SSID on this codebase ends up in a uid-0 shell command
     * somewhere (netshare_ap.sh, hostapd conf writing), so this is the single
     * last-line defence against the F-01 injection - the setup wizard rejects
     * such characters up front so the operator sees the reason instead of a
     * silently altered network name.
     */
    fun sanitizeSsid(requested: String?, fallback: String = "RNS-Hotspot"): String {
        val clean = (requested ?: "")
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .replace(Regex("""["`$\\]"""), "")
            .trim()
            .replace(Regex("\\s{2,}"), " ")
        return when {
            clean.length in 1..32 -> clean
            clean.length > 32 -> clean.take(32)
            else -> fallback
        }
    }

    /**
     * WPA2 passphrases are 8..63 printable ASCII characters. Same shell
     * defence as [sanitizeSsid]: the characters that can break out of a
     * quoted root-shell word are stripped, not trusted to be absent.
     */
    fun sanitizePassphrase(requested: String?, fallback: String = "hotspot123"): String {
        val clean = (requested ?: "")
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .replace(Regex("""["`$\\]"""), "")
            .trim()
        return when {
            clean.length in 8..63 -> clean
            clean.length in 1..7 -> clean.padEnd(8, '0')
            clean.length > 63 -> clean.take(63)
            else -> fallback
        }
    }

    /**
     * Picks the interface an AP just appeared on.
     *
     * [before] is the interface list captured before the AP was requested, [after]
     * the list once it should be up. Anything new that is UP and is not the WAN
     * side wins; known AP names break ties.
     */
    fun pickApInterface(
        before: Set<String>,
        after: List<Pair<String, Boolean>>,
        wanIf: String?
    ): String? {
        val candidates = after.filter { it.second }.map { it.first }
            .filter { it != wanIf && it != "lo" }
        val fresh = candidates.filter { it !in before }
        if (fresh.isNotEmpty()) {
            AP_NAME_PRIORITY.firstOrNull { prefix -> fresh.any { it.startsWith(prefix) } }?.let { prefix ->
                return fresh.first { it.startsWith(prefix) }
            }
            return fresh.first()
        }
        // Nothing new appeared: maybe the AP re-used an interface we already saw.
        AP_NAME_PRIORITY.firstOrNull { prefix -> candidates.any { it.startsWith(prefix) } }?.let { prefix ->
            return candidates.first { it.startsWith(prefix) }
        }
        return null
    }

    /**
     * `KEY=value` lines, as written by netshare_ap.sh and hotspot.runtime.
     * Unknown and malformed lines are ignored rather than throwing - this parses
     * text produced by a shell on a device we cannot test on.
     */
    fun parseKeyValue(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isEmpty()) continue
            map[key] = value
        }
        return map
    }

    /** Ordered by "most likely to be the customer-facing AP". */
    val AP_NAME_PRIORITY = listOf(
        "ap", "rnsap", "softap", "swlan", "wifi_ap", "uap", "wlan1", "wlan2", "p2p", "usb0", "eth0"
    )

    /** True for names a WiFi-Direct group owner typically lands on. */
    fun looksLikeP2pInterface(name: String): Boolean =
        name.startsWith("p2p") || name.startsWith("wifi_p2p") || name.endsWith("-p2p-0")
}
