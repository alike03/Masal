package com.alike.masal

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log

/*
 * Restart after a crash (docs/specs/home.md): while a Masal activity is started, the app process is
 * bound to GuardService, which runs in its own process (":guard") and watches a binder of the app
 * process. When that binder dies (crash, native crash, "Close app" after "not responding"), the guard
 * starts MainActivity again, as long as Masal is the device's home screen, may display over other apps
 * (Android blocks the start otherwise) and the restart limit allows.
 */

private const val TAG = "MasalGuard"
private const val WATCH = 1
private const val RELEASE = 2

/** The restart limit: at most [MAX_RESTARTS] within [WINDOW_MS], so a crash at start-up cannot loop. */
object GuardPolicy {
    const val MAX_RESTARTS = 3
    const val WINDOW_MS = 10 * 60_000L

    /** The earlier restarts that still count at [now]. */
    fun recent(history: List<Long>, now: Long): List<Long> = history.filter { now - it in 0 until WINDOW_MS }

    fun allows(history: List<Long>, now: Long): Boolean = recent(history, now).size < MAX_RESTARTS
}

/** Whether Masal is the device's default home screen (not just one of several launchers). */
fun isDefaultHome(context: Context): Boolean = context.packageManager.resolveActivity(
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
)?.activityInfo?.packageName == context.packageName

/** True in the guard's own process, where the app's data layer is not set up. */
fun isGuardProcess(): Boolean = currentProcessName().endsWith(":guard")

private fun currentProcessName(): String =
    if (android.os.Build.VERSION.SDK_INT >= 28) (Application.getProcessName() ?: "")
    else runCatching { java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")

/** Runs in the ":guard" process. */
class GuardService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var watched: IBinder? = null
    private var recipient: IBinder.DeathRecipient? = null

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            WATCH -> msg.replyTo?.binder?.let(::watch)
            RELEASE -> release()
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun watch(token: IBinder) {
        release()
        val onDeath = IBinder.DeathRecipient { main.post { died(token) } }
        watched = token
        recipient = onDeath
        try {
            token.linkToDeath(onDeath, 0)
        } catch (_: RemoteException) {
            main.post { died(token) } // already gone
        }
    }

    /** Masal left the screen normally: its death from now on is no reason to restart. */
    private fun release() {
        val token = watched ?: return
        recipient?.let { runCatching { token.unlinkToDeath(it, 0) } }
        watched = null
        recipient = null
    }

    // onDestroy leaves the watch alone: Android may destroy the service (its client is gone) before
    // the death notice is handled, and that notice must still restart Masal.

    private fun died(token: IBinder) {
        if (watched !== token) return
        watched = null
        recipient = null
        restartAfterDeath()
    }

    /** The app process died on screen: start Masal again if it is the home screen and the limit allows. */
    internal fun restartAfterDeath() {
        val app = applicationContext
        if (!isDefaultHome(app)) {
            Log.i(TAG, "app process died on screen; not restarted: Masal is not the home screen")
            return
        }
        // Android 10+ blocks activity starts from the background, also for the home screen; an app
        // allowed to display over other apps is exempt. Granted once over ADB (README, "Home screen").
        if (!android.provider.Settings.canDrawOverlays(app)) {
            Log.i(TAG, "app process died on screen; not restarted: \"display over other apps\" is not granted")
            return
        }
        val prefs = app.getSharedPreferences("guard", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val history = prefs.getString("restarts", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        if (!GuardPolicy.allows(history, now)) {
            Log.i(TAG, "app process died on screen; not restarted: ${GuardPolicy.MAX_RESTARTS} restarts within 10 minutes")
            return
        }
        prefs.edit().putString("restarts", (GuardPolicy.recent(history, now) + now).joinToString(",")).apply()
        Log.i(TAG, "app process died on screen; restarting Masal")
        try {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "restart failed: ${e.javaClass.simpleName}")
        }
    }
}

/** Runs in the app process: binds the guard while any Masal activity is started. */
object GuardLink : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var bound = false
    private var service: Messenger? = null
    private lateinit var app: Application

    /** Lives in this process; the guard watches its binder. */
    private val token by lazy { Messenger(Handler(Looper.getMainLooper())) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // Also called again after the guard process was restarted: watch again.
            service = binder?.let(::Messenger)
            if (started > 0) send(WATCH)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(this)
    }

    private fun send(what: Int) {
        val target = service ?: return
        try {
            target.send(Message.obtain(null, what).apply { replyTo = token })
        } catch (_: RemoteException) {
            service = null
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ > 0) return
        if (service != null) send(WATCH)
        if (!bound) bound = runCatching {
            app.bindService(Intent(app, GuardService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    override fun onActivityStopped(activity: Activity) {
        if (started == 0 || --started > 0) return
        // Masal leaves the screen normally (another app, standby): no restart from here on.
        send(RELEASE)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
