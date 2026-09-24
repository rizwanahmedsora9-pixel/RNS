package com.hotspot.billing.debug

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import com.hotspot.billing.util.RootShell

/**
 * Point-in-time view of the gateway, handed to [Diagnostics] by the service so
 * the report can be built without touching service internals.
 */
data class GatewaySnapshot(
    val phase: String = "?",
    val rootOk: Boolean? = null,
    val lanIf: String? = null,
    val wanIf: String? = null,
    val gatewayIp: String? = null,
    val dhcpOwner: String? = null,
    val portalRunning: Boolean = false,
    val onlineClients: Int = 0,
    val message: String = "",
    val apKind: String? = null,
    val apSsid: String? = null,
    val apPassword: String? = null,
    val apMode: String? = null,
    val startedAt: String? = null
)

/**
 * Collects one complete, copyable report of everything the app can see about
 * itself and the phone: device, permissions, WiFi/P2P/AP state, interfaces,
 * routing policy, the exact iptables rules **with packet counters**, both DHCP
 * servers, leases, ARP, the shaper, a live probe of the captive portal, a
 * logcat capture, the app's own log and any saved crash.
 *
 * Blocking - call it on [kotlinx.coroutines.Dispatchers.IO].
 */
object Diagnostics {

    fun collect(
        context: Context,
        snapshot: GatewaySnapshot,
        findings: List<Finding>,
        voucherSummary: String
    ): String {
        val report = ReportBuilder("RNS hotspot gateway - full diagnostic report")
        val app = context.applicationContext

        report.header(
            listOf(
                "generated" to LogFormat.timestamp(System.currentTimeMillis()),
                "app" to appVersion(app),
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, ${Build.DISPLAY})",
                "hardware" to Build.HARDWARE,
                "uptime" to uptime(),
                "log lines kept" to "${AppLog.size()} in memory",
                "logcat watcher" to LogcatWatcher.status()
            )
        )

        gatewaySection(report, snapshot, findings)
        permissionSection(report, app)
        wifiSection(report)
        interfaceSection(report)
        firewallSection(report)
        dhcpSection(report)
        shaperSection(report)
        portalSection(report, snapshot)
        voucherSection(report, voucherSummary)
        logcatSection(report)
        appLogSection(report)

        return report.build()
    }

    // ------------------------------------------------------------------ sections

    private fun gatewaySection(report: ReportBuilder, s: GatewaySnapshot, findings: List<Finding>) {
        report.section("GATEWAY STATE (what the app believes)")
        report.kv("phase", s.phase)
        report.kv("status message", s.message)
        report.kv("root", when (s.rootOk) {
            null -> "unknown"
            true -> "granted"
            false -> "NOT granted"
        })
        report.kv("ap mode (setting)", s.apMode)
        report.kv("ap kind (actual)", s.apKind)
        report.kv("ap ssid", s.apSsid)
        report.kv("ap password", s.apPassword)
        report.kv("LAN interface", s.lanIf)
        report.kv("WAN interface", s.wanIf)
        report.kv("gateway ip", s.gatewayIp)
        report.kv("dhcp owner", s.dhcpOwner)
        report.kv("portal running", s.portalRunning)
        report.kv("clients seen", s.onlineClients)
        report.kv("started at", s.startedAt)

        report.block(
            "watchdog findings (codes H1..H12)",
            if (findings.isEmpty()) "none - the gateway looks healthy"
            else findings.joinToString("\n") { it.format() }
        )
    }

    private fun permissionSection(report: ReportBuilder, context: Context) {
        report.section("PERMISSIONS")
        for (p in listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.NEARBY_WIFI_DEVICES,
            android.Manifest.permission.POST_NOTIFICATIONS
        )) {
            val granted = try {
                context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) {
                false
            }
            val short = p.substringAfterLast('.')
            report.kv(short, if (granted) "granted" else "NOT granted")
        }
        report.kv("location services", locationEnabled(context))
        report.kv("wifi enabled", wifiEnabled(context))
        report.note(
            "Android refuses to create any WiFi network (hotspot, local-only " +
                "hotspot or WiFi Direct group) while Location is off - that is the " +
                "single most common reason NetShare-style sharing fails."
        )
    }

    private fun wifiSection(report: ReportBuilder) {
        report.section("WIFI / AP / P2P (system side)")
        shell(report, "id (are we really root?)", "id")
        shell(report, "getprop wifi.tethering.interface", "getprop wifi.tethering.interface")
        shell(report, "getprop wifi.direct.interface", "getprop wifi.direct.interface")
        shell(report, "getprop ro.hardware", "getprop ro.hardware")
        shell(report, "cmd wifi status", "cmd wifi status")
        shell(report, "dumpsys wifi: softap / tethering state", "dumpsys wifi | grep -iE 'softap|soft ap|tether|localonly|local-only|Wi-Fi is|ap state|hotspot' | head -n 40")
        shell(report, "dumpsys wifip2p (group owner state)", "dumpsys wifip2p | head -n 60")
        shell(report, "hostapd config the system is using (real SSID/passphrase)",
            "cat /data/vendor/wifi/hostapd/hostapd.conf /data/misc/wifi/hostapd.conf 2>/dev/null | grep -iE '^(interface|ssid|wpa_passphrase|channel|hw_mode|driver)' ")
        shell(report, "running hostapd / dnsmasq / p2p processes",
            "ps -A 2>/dev/null | grep -iE 'hostapd|dnsmasq|p2p' | grep -v grep")
        shell(report, "iw dev (concurrency: STA + AP at once)", "iw dev 2>/dev/null")
    }

    private fun interfaceSection(report: ReportBuilder) {
        report.section("INTERFACES & ROUTING")
        shell(report, "ip -o link show", "ip -o link show")
        shell(report, "ip -o -4 addr show", "ip -o -4 addr show")
        shell(report, "ip -6 addr show (should be empty on the LAN side)", "ip -o -6 addr show | grep -v ' lo ' | head -n 20")
        shell(report, "ip route show (main table)", "ip route show")
        shell(report, "ip rule show (Android's per-network policy rules)", "ip rule show")
        shell(report, "ip_forward", "cat /proc/sys/net/ipv4/ip_forward")
        shell(report, "rp_filter (all / LAN)", "cat /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null; echo -n ' '; cat /proc/sys/net/ipv4/conf/wlan0/rp_filter 2>/dev/null")
    }

    private fun firewallSection(report: ReportBuilder) {
        report.section("FIREWALL / NAT (rules + packet counters)")
        shell(report, "iptables -S (filter, all chains)", "iptables -S 2>/dev/null")
        shell(report, "iptables -t nat -S", "iptables -t nat -S 2>/dev/null")
        shell(report, "HS_FWD with counters (do packets reach the gate?)", "iptables -t filter -L HS_FWD -v -n -x 2>/dev/null")
        shell(report, "HS_NAT with counters (is port 80 being redirected?)", "iptables -t nat -L HS_NAT -v -n -x 2>/dev/null")
        shell(report, "HS_IN with counters", "iptables -t filter -L HS_IN -v -n -x 2>/dev/null")
        shell(report, "FORWARD chain head (our jump must be first)", "iptables -t filter -L FORWARD -v -n --line-numbers 2>/dev/null | head -n 15")
        shell(report, "nat PREROUTING head (our jump must be first)", "iptables -t nat -L PREROUTING -v -n --line-numbers 2>/dev/null | head -n 15")
        shell(report, "nat POSTROUTING (MASQUERADE)", "iptables -t nat -L POSTROUTING -v -n --line-numbers 2>/dev/null | head -n 15")
        shell(report, "ip6tables -S FORWARD", "ip6tables -S FORWARD 2>/dev/null")
    }

    private fun dhcpSection(report: ReportBuilder) {
        report.section("DHCP / LEASES / CLIENTS")
        shell(report, "setup_network.sh status", "sh /data/local/tmp/setup_network.sh status")
        shell(report, "hotspot.env (our pins)", "cat /data/local/tmp/hotspot.env 2>/dev/null")
        shell(report, "hotspot.runtime (what start() decided)", "cat /data/local/tmp/hotspot.runtime 2>/dev/null")
        shell(report, "our dnsmasq pidfile", "cat /data/local/tmp/dnsmasq_hotspot.pid 2>/dev/null")
        shell(report, "dnsmasq log tail (why it refused to start, if it did)", "tail -n 40 /data/local/tmp/dnsmasq_hotspot.log 2>/dev/null")
        shell(report, "every dnsmasq / hostapd process on the phone (pid -> binary)",
            "ls -l /proc/[0-9]*/exe 2>/dev/null | grep -iE 'dnsmasq|hostapd'")
        shell(report, "leases (ours)", "cat /data/local/tmp/dnsmasq.leases 2>/dev/null")
        shell(report, "leases (Android's)", "cat /data/misc/dhcp/dnsmasq.leases /data/misc/dhcp/dnsmasq.tether.leases 2>/dev/null")
        shell(report, "ARP table (who is really on the LAN)", "cat /proc/net/arp 2>/dev/null")
        shell(report, "authorized MACs", "cat /data/local/tmp/authorized_macs.txt 2>/dev/null")
        shell(report, "dhcp reservations", "cat /data/local/tmp/dhcp_hosts 2>/dev/null")
        shell(report, "listening sockets", "ss -tulpn 2>/dev/null | head -n 30 || netstat -tulnp 2>/dev/null | head -n 30")
    }

    private fun shaperSection(report: ReportBuilder) {
        report.section("BANDWIDTH SHAPER (tc)")
        shell(report, "bandwidth_control.sh list", "sh /data/local/tmp/bandwidth_control.sh list")
        shell(report, "tc qdisc show", "tc qdisc show 2>/dev/null | head -n 30")
        shell(report, "available schedulers (is sch_htb present?)", "cat /proc/modules 2>/dev/null | grep -iE 'sch_|xt_|act_|nf_nat' | head -n 40")
    }

    private fun portalSection(report: ReportBuilder, s: GatewaySnapshot) {
        report.section("CAPTIVE PORTAL (live probes from the phone itself)")
        report.kv("portal object alive", s.portalRunning)
        report.block(
            "GET http://127.0.0.1:8080/generate_204 (the portal directly)",
            HttpProbe.get("http://127.0.0.1:8080/generate_204").describe()
        )
        val gw = s.gatewayIp
        if (!gw.isNullOrBlank()) {
            report.block(
                "GET http://$gw/generate_204 (through the iptables redirect a client would hit)",
                HttpProbe.get("http://$gw/generate_204").describe()
            )
            report.block(
                "GET http://$gw/ (portal root)",
                HttpProbe.get("http://$gw/").describe()
            )
        } else {
            report.line("(no gateway address known - the AP interface has no IP)")
        }
        report.note(
            "A client pops the sign-in sheet only when the probe returns HTTP 200 " +
                "with a body. 302 to another port, 204, or a timeout all mean \"no sheet\"."
        )
    }

    private fun voucherSection(report: ReportBuilder, summary: String) {
        report.section("VOUCHERS")
        report.block("from the database", summary.ifBlank { "(none)" })
    }

    private fun logcatSection(report: ReportBuilder) {
        report.section("LOGCAT CAPTURE (system side, last ~800 lines)")
        val rooted = try {
            RootShell.isRootAvailable()
        } catch (e: Throwable) {
            false
        }
        report.block(
            "logcat -d (rooted=$rooted, filtered to networking tags)",
            LogcatWatcher.dump(root = rooted, filtered = true)
        )
    }

    private fun appLogSection(report: ReportBuilder) {
        report.section("APP LOG (every command this app ran, newest last)")
        report.block("in-memory log (${AppLog.size()} lines)", AppLog.text(limit = 1_200))
        val crash = AppLog.readCrashFile()
        if (crash != null) {
            report.block("last_crash.txt", crash)
        } else {
            report.line("(no saved crash report)")
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Runs one root-shell command and records it in the report. */
    private fun shell(report: ReportBuilder, label: String, command: String) {
        val started = System.nanoTime()
        val result = try {
            RootShell.run(command)
        } catch (e: Throwable) {
            report.command(label, -1, ms(started), "", "exception: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        report.command(
            label, result.code, ms(started),
            result.out.joinToString("\n"),
            result.err.joinToString("\n")
        )
    }

    private fun ms(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (code ${info.versionCode})"
    } catch (e: Exception) {
        "unknown (${e.message})"
    }

    private fun uptime(): String = try {
        val seconds = android.os.SystemClock.elapsedRealtime() / 1000
        "${seconds / 3600}h ${(seconds % 3600) / 60}m since boot"
    } catch (e: Throwable) {
        "?"
    }

    private fun wifiEnabled(context: Context): String = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifi.isWifiEnabled) "on" else "OFF"
    } catch (e: Throwable) {
        "unknown (${e.message})"
    }

    private fun locationEnabled(context: Context): String = try {
        val lm = context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.GPS_PROVIDER
        )
        if (providers.any { lm.isProviderEnabled(it) }) "on" else "OFF (blocks every WiFi-sharing API)"
    } catch (e: Throwable) {
        "unknown (${e.message})"
    }
}
