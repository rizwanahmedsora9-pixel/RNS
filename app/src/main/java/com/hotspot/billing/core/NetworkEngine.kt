package com.hotspot.billing.core

import com.hotspot.billing.net.ApHandle
import com.hotspot.billing.net.ApLauncher

/**
 * Phase 1 — NetworkEngine (alias for NetworkController per master plan).
 * The master plan calls it NetworkEngine.kt; the implementation is
 * NetworkController.kt. This wrapper ensures both names work.
 */
class NetworkEngine(
    apLauncher: ApLauncher
) {
    private val controller = NetworkController(apLauncher)

    suspend fun start(
        ssid: String,
        log: (String) -> Unit,
        onStep: (Int) -> Unit = {}
    ) = controller.start(ssid, log, onStep)

    suspend fun stop(
        apHandle: ApHandle?,
        log: (String) -> Unit
    ) = controller.stop(apHandle, log)
}
