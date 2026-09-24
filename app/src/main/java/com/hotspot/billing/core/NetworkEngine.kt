package com.hotspot.billing.core

import com.hotspot.billing.net.ApLauncher

/**
 * Phase 1 — NetworkEngine (alias for NetworkController per master plan)
 * Master plan calls it NetworkEngine.kt, implementation is NetworkController.kt
 * This wrapper ensures both names work.
 *
 * Responsibilities:
 * START:
 *  Detect internet source (WanDetector)
 *  Create LAN (HotspotManager)
 *  Start DHCP (DhcpManager)
 *  Start DNS (DnsManager)
 *  Enable NAT (NatManager)
 *  Test internet
 *  Return SUCCESS
 *
 * STOP:
 *  Clean shutdown: DHCP, DNS, NAT, Hotspot
 */
class NetworkEngine(
    apLauncher: ApLauncher
) {
    private val controller = NetworkController(apLauncher)

    suspend fun start(
        mode: com.hotspot.billing.net.ApMode,
        ssid: String,
        password: String,
        pinnedLanIf: String?,
        log: (String) -> Unit
    ) = controller.start(mode, ssid, password, pinnedLanIf, log)

    suspend fun stop(
        apHandle: com.hotspot.billing.net.ApHandle?,
        log: (String) -> Unit
    ) = controller.stop(apHandle, log)
}
