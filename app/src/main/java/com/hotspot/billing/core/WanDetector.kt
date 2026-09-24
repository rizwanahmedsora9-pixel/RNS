package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell

/**
 * Phase 2 — Internet Source Detection
 * Detects WAN interface and type: Mobile Data, WiFi, USB tether, Ethernet
 *
 * WAN candidates on typical Android:
 *  - Mobile: ccmni0, ccmni1, rmnet_data0, rmnet0
 *  - WiFi: wlan0 (when phone is connected to router, for repeater mode)
 *  - USB: rndis0, usb0
 *  - Ethernet: eth0
 *
 * For NetShare-style repeater mode:
 *  WAN = wlan0 (phone's WiFi internet)
 *  LAN = p2p0 / ap0 / swlan0 / wlan1 (second interface)
 *
 * For normal mobile data sharing:
 *  WAN = ccmni0
 *  LAN = ap0 / p2p0
 */
object WanDetector {

    enum class WanType {
        MOBILE, WIFI, USB, ETHERNET, UNKNOWN
    }

    data class WanInfo(
        val interfaceName: String,
        val type: WanType,
        val hasInternet: Boolean,
        val ipAddress: String? = null,
        val dns1: String? = null,
        val dns2: String? = null
    )

    data class LanInfo(
        val interfaceName: String,
        val isUp: Boolean,
        val ipAddress: String? = null
    )

    /**
     * Detect current WAN interface via default route, then classify type.
     */
    fun detectWan(): WanInfo? {
        val defaultIf = RootShell.defaultRouteInterface()
        if (defaultIf == null) {
            AppLog.w(AppLog.TAG_NET, "wan: no default route found")
            return null
        }

        val type = classifyInterface(defaultIf)
        val addrs = try {
            RootShell.run("ip -o -4 addr show dev $defaultIf", quiet = true).out
        } catch (e: Throwable) {
            emptyList()
        }
        val ip = addrs.mapNotNull { line ->
            Regex("""(\d+\.\d+\.\d+\.\d+/\d+)""").find(line)?.groupValues?.get(1)
        }.firstOrNull()?.substringBefore('/')

        val dns1 = RootShell.getprop("net.dns1")
        val dns2 = RootShell.getprop("net.dns2")

        // Simple internet check: does default route exist and interface has IP?
        val hasInternet = ip != null && defaultIf.isNotEmpty()

        val info = WanInfo(
            interfaceName = defaultIf,
            type = type,
            hasInternet = hasInternet,
            ipAddress = ip,
            dns1 = dns1,
            dns2 = dns2
        )
        AppLog.i(AppLog.TAG_NET, "wan: detected $info")
        return info
    }

    /**
     * Detect all potential LAN interfaces (AP side).
     */
    fun detectLanInterfaces(): List<LanInfo> {
        val all = RootShell.interfaces()
        val wan = RootShell.defaultRouteInterface()
        return all.filter { (name, up) ->
            up && name != wan && name != "lo" && isPotentialLan(name)
        }.map { (name, up) ->
            val addrs = try {
                RootShell.lanAddresses(name)
            } catch (e: Throwable) {
                emptyList()
            }
            LanInfo(
                interfaceName = name,
                isUp = up,
                ipAddress = addrs.firstOrNull()
            )
        }
    }

    fun classifyInterface(iface: String): WanType {
        return when {
            iface.startsWith("ccmni") || iface.startsWith("rmnet") || iface.startsWith("ccemni") -> WanType.MOBILE
            iface.startsWith("wlan") -> WanType.WIFI
            iface.startsWith("rndis") || iface == "usb0" -> WanType.USB
            iface.startsWith("eth") -> WanType.ETHERNET
            else -> WanType.UNKNOWN
        }
    }

    fun isPotentialLan(name: String): Boolean {
        val lanPrefixes = listOf(
            "ap", "rnsap", "p2p", "swlan", "softap", "uap", "wlan1", "wlan2", "wifi_ap"
        )
        return lanPrefixes.any { name.startsWith(it) } || name == "ap0"
    }

    fun getUpstreamDns(): Pair<String, String> {
        val dns1 = RootShell.getprop("net.dns1")?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) } ?: "8.8.8.8"
        val dns2 = RootShell.getprop("net.dns2")?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) } ?: "1.1.1.1"
        return dns1 to dns2
    }

    /**
     * For repeater mode: check if wlan0 is connected and has internet,
     * while we want to share via p2p/ap interface.
     * Returns true if WiFi-to-WiFi sharing is possible.
     */
    fun isRepeaterModePossible(): Boolean {
        val interfaces = RootShell.interfaces()
        val hasWlan0Up = interfaces.any { it.first == "wlan0" && it.second }
        val hasApInterface = interfaces.any { isPotentialLan(it.first) && it.second }
        val defaultIf = RootShell.defaultRouteInterface()
        // If default is wlan0, we are in WiFi internet mode
        return defaultIf == "wlan0" && hasWlan0Up
    }
}
