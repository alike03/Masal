package com.alike.masal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Restart after a crash (home.md): the guard starts Masal again only as the home screen with "display over
 * other apps" granted, at most 3 times in 10 minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuardTest {
    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    @Before
    fun clean() {
        app.getSharedPreferences("guard", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app.packageManager).removeResolveInfosForIntent(home, app.packageName)
        ShadowSettings.setCanDrawOverlays(true)
    }

    private fun homeScreen(packageName: String) {
        shadowOf(app.packageManager).addResolveInfoForIntent(home, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { this.packageName = packageName; name = "$packageName.Home" }
            isDefault = true
        })
    }

    private fun nextStart(): Intent? = shadowOf(app).nextStartedActivity

    private fun guard() = Robolectric.buildService(GuardService::class.java).create().get()

    @Test
    fun policyCountsOnlyTheLastTenMinutes() {
        val now = 100 * GuardPolicy.WINDOW_MS
        assertTrue(GuardPolicy.allows(listOf(now - 1, now - 2), now))
        assertFalse(GuardPolicy.allows(listOf(now - 1, now - 2, now - 3), now))
        assertTrue(GuardPolicy.allows(listOf(now - GuardPolicy.WINDOW_MS, now - 2, now - 3), now))
        // A clock set back does not count old entries as recent ones.
        assertEquals(emptyList<Long>(), GuardPolicy.recent(listOf(now + 5_000), now))
    }

    @Test
    fun restartsMasalAsHomeScreenThreeTimes() {
        homeScreen(app.packageName)
        assertTrue(isDefaultHome(app))
        val guard = guard()
        repeat(GuardPolicy.MAX_RESTARTS) {
            guard.restartAfterDeath()
            val started = nextStart()
            assertEquals(ComponentName(app, MainActivity::class.java), started?.component)
            assertTrue(started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        // The fourth death within 10 minutes: Masal stays closed.
        guard.restartAfterDeath()
        assertNull(nextStart())
    }

    @Test
    fun noRestartWithAnotherHomeScreen() {
        homeScreen("com.example.launcher")
        assertFalse(isDefaultHome(app))
        guard().restartAfterDeath()
        assertNull(nextStart())
    }

    @Test
    fun noRestartWithoutOverlayPermission() {
        homeScreen(app.packageName)
        ShadowSettings.setCanDrawOverlays(false)
        guard().restartAfterDeath()
        assertNull(nextStart())
        // Not counted: once granted, all three restarts are still there.
        ShadowSettings.setCanDrawOverlays(true)
        repeat(GuardPolicy.MAX_RESTARTS) { guard().restartAfterDeath(); assertEquals(ComponentName(app, MainActivity::class.java), nextStart()?.component) }
    }

    @Test
    fun appProcessIsNotTheGuard() = assertFalse(isGuardProcess())
}
