package com.alike.masal.setup

import com.alike.masal.data.Settings
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Sign-in for the setup page (setup.md): the PIN shown in Settings on the TV, then a session
 * cookie the device keeps. After 5 wrong PINs in a row the page refuses every PIN for a minute.
 */
class SetupAuth(private val settings: Settings, private val clock: () -> Long = System::currentTimeMillis) {
    sealed interface Result {
        data class Ok(val token: String) : Result
        data object Wrong : Result
        data class Locked(val seconds: Int) : Result
    }

    private var failures = 0
    private var lockedUntil = 0L

    @Synchronized
    fun login(pin: String): Result {
        val now = clock()
        if (now < lockedUntil) return Result.Locked(((lockedUntil - now + 999) / 1000).toInt())
        if (!MessageDigest.isEqual(pin.trim().toByteArray(), settings.setupPin.toByteArray())) {
            failures++
            if (failures >= MaxFailures) { failures = 0; lockedUntil = now + LockMs }
            return Result.Wrong
        }
        failures = 0
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        settings.addSetupSession(hash(token))
        return Result.Ok(token)
    }

    /** Whether the cookie belongs to a signed-in device (a new PIN signs every device out). */
    fun valid(token: String?): Boolean = !token.isNullOrBlank() && hash(token) in settings.setupSessions

    private fun hash(token: String) =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val Cookie = "masal_setup"
        const val MaxFailures = 5
        const val LockMs = 60_000L
    }
}
