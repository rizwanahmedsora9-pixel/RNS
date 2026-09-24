package com.hotspot.billing.net

/**
 * Pure decisions for how an AP is brought up. No Android types, so the rules
 * that the Infinix Hot 8 log exposed can be unit-tested:
 *
 *  - WiFi Direct reason 2 (BUSY) is what the P2P state machine answers when it
 *    is *disabled* (WiFi off, or Location services off). It is not evidence of
 *    another group.
 *  - On Android 9/10 with a mobile uplink, the framework softap is the method
 *    that actually beacons. Local-only hotspot and WiFi Direct need Location
 *    permission and an enabled radio, so they follow, they do not lead.
 *  - A mobile interface (ccmni*, rmnet*) is never the radio an AP is created on.
 */
object ApPlan {

    enum class Step(val label: String) {
        SYSTEM("system hotspot"),
        LOCAL_ONLY("local-only hotspot"),
        WIFI_DIRECT("WiFi Direct group (NetShare)"),
        ROOT_HOSTAPD("root hostapd")
    }

    /**
     * [wanIsWifi] is true when the default route is a WiFi STA. Starting the
     * system hotspot on a single-radio phone disconnects that STA, so it is
     * tried last in repeater mode and first when the uplink is mobile data
     * (the Hot 8 case: ccmni0).
     */
    fun steps(mode: ApMode, sdk: Int, wanIsWifi: Boolean): List<Step> {
        val systemFirst = !wanIsWifi
        val concurrent = listOf(Step.LOCAL_ONLY, Step.WIFI_DIRECT, Step.ROOT_HOSTAPD)
        val auto = if (systemFirst) {
            listOf(Step.SYSTEM) + concurrent
        } else {
            concurrent + Step.SYSTEM
        }
        // Android 12+ can start the real hotspot from `cmd wifi`; still keep
        // the no-toggle methods behind it. Android 9/10 uses the same order
        // when the uplink is not WiFi, because that is the order the radio
        // actually supports — the shell command is just not how we start it.
        return when (mode) {
            ApMode.MANUAL -> emptyList()
            ApMode.SYSTEM -> listOf(Step.SYSTEM)
            ApMode.LOCAL_ONLY -> listOf(Step.LOCAL_ONLY, Step.SYSTEM, Step.WIFI_DIRECT, Step.ROOT_HOSTAPD)
            ApMode.NETSHARE ->
                if (systemFirst) {
                    listOf(Step.WIFI_DIRECT, Step.SYSTEM, Step.LOCAL_ONLY, Step.ROOT_HOSTAPD)
                } else {
                    listOf(Step.WIFI_DIRECT, Step.LOCAL_ONLY, Step.ROOT_HOSTAPD, Step.SYSTEM)
                }
            ApMode.ROOT_AP -> listOf(Step.ROOT_HOSTAPD, Step.SYSTEM, Step.LOCAL_ONLY, Step.WIFI_DIRECT)
            ApMode.AUTO -> if (sdk >= 31 && !wanIsWifi) {
                listOf(Step.SYSTEM, Step.LOCAL_ONLY, Step.WIFI_DIRECT, Step.ROOT_HOSTAPD)
            } else {
                auto
            }
        }
    }

    /** ccmni0 / rmnet0 are the mobile uplink, not a WiFi phy. */
    fun isMobileIface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("ccmni") || n.startsWith("rmnet") || n.startsWith("ccemni") ||
            n.startsWith("pdp") || n == "ppp0" || n.startsWith("ppp")
    }

    /** A STA radio we may ask the driver for a second interface on. */
    fun isWifiRadio(name: String): Boolean {
        val n = name.lowercase()
        return (n.startsWith("wlan") || n.startsWith("swlan")) && !isMobileIface(n)
    }

    /**
     * Android's own tether dnsmasq is what keeps a system softap alive on the
     * Hot 8. Killing it (or holding port 53 so it cannot start) makes the
     * framework tear ap0 down within a few hundred milliseconds.
     */
    fun frameworkLikelyOwnsDhcp(iface: String): Boolean {
        val n = iface.lowercase()
        return n == "ap0" || n == "ap1" || n.startsWith("swlan") ||
            n.startsWith("softap") || n.startsWith("wlan_ap")
    }

    /**
     * `/vendor/bin/hw/hostapd` on Android 8+ is the HIDL HAL, not a CLI that
     * accepts `-B conf`. The Hot 8 has this binary and no other hostapd.
     */
    fun isHalHostapd(path: String): Boolean {
        val p = path.trim()
        return p.startsWith("HAL:") || p.contains("/vendor/bin/hw/hostapd")
    }

    /**
     * First little-endian int in a `service call` parcel dump.
     * `0000000b` is 11 (WIFI_AP_STATE_DISABLED). Returns null when the dump
     * does not look like a parcel, so we never treat an error string as a state.
     */
    fun parseServiceCallInt(text: String): Int? {
        val hex = Regex("""0x[0-9a-fA-F]+:\s+([0-9a-fA-F]{8})""")
            .find(text)?.groupValues?.get(1)
            ?: Regex("""Parcel\(\s*([0-9a-fA-F]{8})""").find(text)?.groupValues?.get(1)
            ?: return null
        return hex.toLongOrNull(16)?.toInt()
    }

    /** WIFI_AP_STATE_* lives in 10..14. Anything else is not that getter. */
    fun looksLikeApState(value: Int?): Boolean = value != null && value in 10..14

    /**
     * What reason=2 actually means. The framework uses one code for "my state
     * machine is not in P2pEnabledState", which includes WiFi off.
     */
    fun p2pBusyHint(wifiEnabled: Boolean, locationPermission: Boolean, locationServicesOn: Boolean): String =
        when {
            !wifiEnabled ->
                "WiFi is off, so WiFi Direct is disabled. BUSY (reason 2) means the P2P " +
                    "state machine is not running — not that another group is active."
            !locationServicesOn ->
                "Location services are off. This phone leaves WiFi Direct disabled until " +
                    "they are on, and createGroup then returns BUSY."
            !locationPermission ->
                "Location permission is not granted. Android hides WiFi Direct from the app."
            else ->
                "WiFi Direct answered BUSY. That is the disabled-state reply as well as " +
                    "'another group'. A stale channel from when WiFi was off causes the same code."
        }
}
