package com.hotspot.billing

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.anyOf
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matcher

/**
 * The onboarding wizard, end to end on a real device (or emulator):
 *
 * ```
 * splash -> root check -> hotspot -> vouchers -> START GATEWAY -> dashboard
 * ```
 *
 * This is exactly what the operator runs on the phone, so a crash on any of
 * these screens - the "the app died before the hotspot ever appeared" failure -
 * becomes a red CI job with a stack trace instead of a mystery in the field.
 *
 * The deep walk needs a root shell: the app refuses to continue without one
 * (by design). The default Android emulator images are userdebug and provide
 * `su`; when they do not, the test states that and stops rather than failing
 * on a missing button.
 */
@RunWith(AndroidJUnit4::class)
class WizardFlowTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.NEARBY_WIFI_DEVICES,
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun firstRun() {
        E2E.firstRun(context)
        // A crash report from an earlier test run is not this run's crash.
        E2E.crashFile(context)?.delete()
    }

    @Test
    fun splash_routes_to_the_root_check() {
        ActivityScenario.launch(SplashActivity::class.java)
        // The splash waits 1.2 s before routing, so poll instead of assuming.
        require(await(R.id.root_step, 15_000)) { "the root check screen never appeared" }
        onView(withId(R.id.root_step)).check(matches(withText("STEP 1 OF 4")))
    }

    @Test
    fun full_wizard_to_the_dashboard() {
        ActivityScenario.launch(SplashActivity::class.java)
        require(await(R.id.root_step, 15_000)) { "the root check screen never appeared" }

        val rootConfirmed = awaitRootCheck()
        assumeTrue(
            "this emulator image has no root shell (su) - the wizard stops here by design",
            rootConfirmed
        )
        onView(withId(R.id.btn_continue)).perform(click())

        // Step 2 of 4: hotspot name, password, creation mode.
        require(await(R.id.btn_hs_next, 20_000)) { "the hotspot setup screen never appeared" }
        onView(withId(R.id.btn_hs_next)).perform(click())

        // Step 3 of 4: voucher plan.
        require(await(R.id.btn_vz_next, 20_000)) { "the voucher setup screen never appeared" }
        onView(withId(R.id.btn_vz_next)).perform(click())

        // Step 4 of 4: START GATEWAY.
        require(await(R.id.btn_start_gw, 20_000)) { "the ready screen never appeared" }
        onView(withId(R.id.btn_start_gw)).perform(click())

        // The freshly minted codes are handed over in a dialog, then the
        // dashboard opens with the gateway running.
        require(awaitDialogText("Open dashboard", 60_000)) {
            "the codes dialog never appeared - voucher generation or the service start failed"
        }
        onView(withText("Open dashboard")).inRoot(isDialog()).perform(click())

        require(awaitText(R.id.dash_root, "granted", 30_000)) {
            "the dashboard never reported root as granted"
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Waits until [viewId] is on screen. Espresso fails immediately on a view
     * that is not there yet, and this app's screens appear after coroutines,
     * framework callbacks and (on the splash) a fixed delay - so the wait is
     * part of the test, not a sleep.
     */
    private fun await(viewId: Int, timeoutMs: Long): Boolean =
        await(withId(viewId), timeoutMs)

    private fun await(matcher: Matcher<View>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return true
            } catch (t: Throwable) {
                sleepQuietly(200)
            }
        }
        return false
    }

    private fun awaitText(viewId: Int, text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withId(viewId)).check(matches(withText(text)))
                return true
            } catch (t: Throwable) {
                sleepQuietly(250)
            }
        }
        return false
    }

    /** Waits for a button inside a dialog window (dialogs are their own root). */
    private fun awaitDialogText(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withText(text)).inRoot(isDialog()).check(matches(isDisplayed()))
                return true
            } catch (t: Throwable) {
                sleepQuietly(250)
            }
        }
        return false
    }

    /**
     * The root check is a coroutine with a 20 s budget: waits until Continue is
     * enabled (root confirmed) or the screen says root is unavailable.
     *
     * @return true when root was confirmed.
     */
    private fun awaitRootCheck(): Boolean {
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withId(R.id.btn_continue)).check(matches(androidx.test.espresso.matcher.ViewMatchers.isEnabled()))
                return true
            } catch (t: Throwable) {
                // Not confirmed yet - or never. The title tells the two apart.
                try {
                    onView(withId(R.id.root_box_title)).check(
                        matches(
                            anyOf(
                                withText("No root access"),
                                withText("Root check timed out")
                            )
                        )
                    )
                    return false
                } catch (ignored: Throwable) {
                    // still checking
                }
                sleepQuietly(300)
            }
        }
        return false
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
