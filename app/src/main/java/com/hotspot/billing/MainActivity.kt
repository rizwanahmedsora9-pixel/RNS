package com.hotspot.billing

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.net.VoucherManager
import com.hotspot.billing.portal.CaptivePortalServer
import com.hotspot.billing.util.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var voucherManager: VoucherManager
    private var portalServer: CaptivePortalServer? = null

    @Volatile
    private var shuttingDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val db = AppDatabase.get(this)
        voucherManager = VoucherManager(db)

        scope.launch {
            // 1. Root has to exist before anything else - every other step shells out.
            if (!RootShell.isRootAvailable()) {
                status("Root not granted. Open Magisk and allow this app, then relaunch.")
                return@launch
            }

            // 2. Copy the bundled shell scripts to /data/local/tmp.
            status("Deploying network scripts...")
            deployScripts()

            // 3. Bring up NAT / iptables / dnsmasq, then the shaper.
            status("Starting NAT, DHCP and firewall...")
            RootShell.startNetwork()
            RootShell.initBandwidth()

            // 4. Start the captive portal HTTP server.
            val server = CaptivePortalServer(voucherManager)
            server.start()
            if (shuttingDown) {
                // onDestroy ran while we were starting - don't leak the socket.
                server.stop()
                return@launch
            }
            portalServer = server
            status("Captive portal listening on ${CaptivePortalServer.GATEWAY_IP}:${CaptivePortalServer.PORT}")
        }
    }

    private fun deployScripts() {
        for (name in listOf("setup_network.sh", "bandwidth_control.sh")) {
            val tmpLocal = java.io.File(cacheDir, name)
            assets.open(name).use { input ->
                tmpLocal.outputStream().use { output -> input.copyTo(output) }
            }
            RootShell.run("cp ${tmpLocal.absolutePath} /data/local/tmp/$name && chmod 755 /data/local/tmp/$name")
        }
    }

    private fun status(message: String) = runOnUiThread {
        findViewById<TextView>(R.id.status_text)?.text = message
    }

    override fun onDestroy() {
        shuttingDown = true
        scope.cancel()
        portalServer?.stop()
        portalServer = null
        RootShell.stopNetwork()
        super.onDestroy()
    }
}
