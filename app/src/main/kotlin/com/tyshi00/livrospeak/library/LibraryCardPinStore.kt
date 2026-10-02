package com.tyshi00.livrospeak.library

import android.content.Context
import java.security.MessageDigest

private const val PREFERENCES_NAME = "library_card_pin"

// Fixed, non-secret salt. This is not what makes the PIN "secure" -- see the doc
// comment below -- it just means the raw PIN itself never sits in the prefs file,
// so a casual grep of a backup/shared-prefs dump doesn't hand it over verbatim.
private const val PIN_SALT = "livrospeak-library-card-pin"

/**
 * Stores a single 4-digit PIN gating the Library Card screen
 * (com.tyshi00.livrospeak.screens.LibraryCardScreen), mirroring why the real
 * Libby app itself normally keeps a card number hidden
 * behind one. This is deliberately NOT real security: no Android Keystore, no
 * biometric tie-in, just a single fixed-salt SHA-256 hash the same way the rest
 * of Livrospeak already treats on-device storage (nothing elsewhere in the app
 * has Keystore-backed encryption either). The goal is only to keep a card number
 * from being visible to anyone who picks up an unlocked phone, not to defend
 * against someone with access to the device's storage. Persisted the same way
 * every other store in this package persists things -- a dedicated
 * SharedPreferences file, following [AudiobookProgressStore]'s convention.
 *
 * [recordFailedAttempt]'s escalating lockout and moving [resetPin] out of the
 * PIN screen itself (now only reachable from Settings → Libby) both exist for
 * the same reason: without them, anyone holding the phone could reset the PIN
 * on the spot just by guessing wrong 3 times, which would have made the PIN
 * pointless. Neither change turns this into real security -- someone with
 * sustained access to an unlocked phone can still go find Settings -- but
 * idle or opportunistic guessing alone can no longer get past it.
 */
object LibraryCardPinStore {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun hasPin(): Boolean = preferences().contains("pin.hash")

    fun setPin(pin: String) {
        preferences().edit().putString("pin.hash", hash(pin)).commit()
    }

    fun verifyPin(pin: String): Boolean = preferences().getString("pin.hash", null) == hash(pin)

    /** Clears the failed-attempt count and any active lockout after a correct
     *  PIN entry, so the next mistake starts counting from zero again. */
    fun recordSuccess() {
        preferences().edit()
            .remove("pin.attempts")
            .remove("pin.lockoutUntil")
            .commit()
    }

    /**
     * Records one more wrong guess and, once that pushes the running count past
     * 3/6/9, locks further attempts out for 5/15/30 minutes respectively (capped
     * at 30 minutes beyond that) -- see the section comment above this object
     * for why a PIN this easy to brute-force otherwise needed this at all.
     * Persisted (not just in-memory UI state) so force-closing the app doesn't
     * reset the clock. Returns how many milliseconds remain locked out, or 0 if
     * this guess didn't trigger a lockout.
     */
    fun recordFailedAttempt(): Long {
        val attempts = preferences().getInt("pin.attempts", 0) + 1
        val lockoutMillis = when {
            attempts >= 9 -> 30 * 60_000L
            attempts >= 6 -> 15 * 60_000L
            attempts >= 3 -> 5 * 60_000L
            else -> 0L
        }
        val editor = preferences().edit().putInt("pin.attempts", attempts)
        if (lockoutMillis > 0) {
            editor.putLong("pin.lockoutUntil", System.currentTimeMillis() + lockoutMillis)
        }
        editor.commit()
        return lockoutRemainingMillis()
    }

    /** How many milliseconds remain on an active lockout, or 0 if there isn't
     *  one (including if it already expired) -- safe to call any time, e.g.
     *  right when the PIN screen opens, since the lockout itself is persisted
     *  rather than kept only in UI state. */
    fun lockoutRemainingMillis(): Long {
        val until = preferences().getLong("pin.lockoutUntil", 0L)
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    /** Lets someone start over if they forget their PIN -- there's no recovery
     *  path otherwise, since this isn't tied to their real Libby account
     *  credentials at all. Reachable only from Settings → Libby (not from the
     *  PIN entry screen itself), so failed guesses alone can never trigger a
     *  reset -- see [recordFailedAttempt] for what happens instead when
     *  someone just keeps guessing. */
    fun resetPin() {
        preferences().edit()
            .remove("pin.hash")
            .remove("pin.attempts")
            .remove("pin.lockoutUntil")
            .commit()
    }

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((PIN_SALT + pin).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun preferences() =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
