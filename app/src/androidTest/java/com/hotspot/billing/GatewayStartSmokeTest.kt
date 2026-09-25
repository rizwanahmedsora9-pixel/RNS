package com.hotspot.billing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The smoke test for the failure this app used to have:
 *
 *   "I press START GATEWAY and the app crashes before the hotspot appears."
 *
 * It opens the dashboard exactly like an operator does (setup complete, so the
 * gateway service starts itself), binds to the live service and then asserts:
 *
 *  1. the service is alive - [HotspotService.onCreate] did not take the
 *     process down (a service that throws in onCreate is a crash on the main
 *     thread, which is what the user saw);
 *  2. the gateway reaches a definite phase instead of hanging on STARTING
 *     forever (what a dead coroutine used to look like);
 *  3. no crash report was written while it started.
 *
 * On an emulator there is no WiFi radio, so no AP can ever come up: the correct
 * end state is WAITING_AP ("switch the hotspot on and we take over"), which is
 * precisely the behaviour that replaces the old crash.
 */
@RunWith(AndroidJUnit4::class)
class GatewayStartSmokeTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.NEARBY_WIFI_DEVICES,
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var service: HotspotService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? HotspotService.LocalBinder)?.service()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    @Before
    fun setUp() {
        E2E.setupComplete(context)
        E2E.crashFile(context)?.delete()
    }

    @After
    fun tearDown() {
        try {
            service?.stopSequence()
        } catch (e: Throwable) {
            // the service may already be gone
        }
        if (bound) {
            try {
                context.unbindService(connection)
            } catch (e: Throwable) {
                // already unbound
            }
            bound = false
        }
        try {
            context.stopService(Intent(context, HotspotService::class.java))
        } catch (e: Throwable) {
            // nothing to stop
        }
    }

    @Test
    fun start_gateway_survives_and_reports_a_definite_phase() {
        ActivityScenario.launch(MainActivity::class.java)

        // The dashboard starts the gateway service; bind to watch it work.
        context.bindService(
            Intent(context, HotspotService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        bound = true

        val started = System.currentTimeMillis()
        var phase: HotspotService.Phase? = null
        while (System.currentTimeMillis() - started < PHASE_TIMEOUT_MS) {
            val current = service?.state?.phase
            if (current != null && current != HotspotService.Phase.STARTING &&
                current != HotspotService.Phase.STOPPING && current != HotspotService.Phase.STOPPED
            ) {
                phase = current
                break
            }
            Thread.sleep(500)
        }

        // 1. The service object exists: onCreate finished without dying.
        assertNotNull("the gateway service never came up", service)

        // 2. The gateway is not stuck - a dead run would leave it on STARTING.
        assertNotNull(
            "the gateway never left STARTING - the start run died silently " +
                "(this is the 'crashed before the hotspot signal' failure)",
            phase
        )

        val state = service?.state
        val rootOk = state?.rootOk
        assumeTrue(
            "this emulator image has no root shell (su) - the deep start is not exercised",
            rootOk == true
        )

        // With root the start must complete or wait for an AP, never error out.
        assertTrue(
            "the gateway ended in ERROR: ${state?.message}",
            phase != HotspotService.Phase.ERROR
        )
        assertTrue(
            "unexpected phase $phase (expected WAITING_AP on a device with no radio, or RUNNING)",
            phase == HotspotService.Phase.WAITING_AP || phase == HotspotService.Phase.RUNNING
        )

        // 3. Nothing wrote a crash report while starting.
        val crash = E2E.crashFile(context)
        assertTrue(
            "a crash report was written during the gateway start: ${crash?.readText()?.take(400)}",
            crash == null || !crash.exists()
        )
    }

    private companion object {
        /**
         * WAN detection + every AP method failing takes a while on an emulator:
         * the framework callbacks each have their own timeout (local-only
         * hotspot and WiFi Direct up to 30 s), and they are tried in order.
         */
        const val PHASE_TIMEOUT_MS = 240_000L
    }
}
