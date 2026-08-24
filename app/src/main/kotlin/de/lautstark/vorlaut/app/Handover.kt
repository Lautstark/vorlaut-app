package de.lautstark.vorlaut.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handing the tablet over: pinning the app to the screen, and the PIN that lets
 * a caregiver back out of it.
 *
 * **What screen pinning does and does not guarantee.** This app is not a device
 * owner, so `startLockTask` puts it in Android's *pinned* mode rather than a
 * locked task. In pinned mode the system's own escape — holding Back and
 * Overview together — still works, and no app can switch that off. Whether that
 * escape then asks for a credential is the device's setting
 * (Settings → Security → App pinning → "Ask for PIN before unpinning"), not
 * this app's.
 *
 * So the honest description is: the PIN here guards the way out *through the
 * app*, and pinning stops the ordinary ways out — Home, Overview, notifications,
 * a stray swipe. Against a child, that is the whole of the problem. Against
 * somebody who knows the gesture, it is not a lock, and this app should never
 * be described as though it were. Making it one needs device-owner
 * provisioning, which is a different piece of work with a different setup cost.
 */
class Handover(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val preferences =
        applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    val isPinSet: Boolean
        get() = stored() != null

    /**
     * Whether the tablet is currently handed over.
     *
     * Persisted rather than held for the session, because the case this feature
     * exists for is the tablet being out of the caregiver's hands. If the app is
     * killed — a crash, memory pressure, a battery dip — a guard that lived only
     * in memory would come back unlocked at exactly the moment nobody is watching.
     */
    var isHandedOver: Boolean
        get() = preferences.getBoolean(KEY_HANDED_OVER, false)
        set(value) = preferences.edit { putBoolean(KEY_HANDED_OVER, value) }

    /** The package to reopen on a restart, so a restart lands back on the board. */
    var lastPackageId: String?
        get() = preferences.getString(KEY_LAST_PACKAGE, null)
        set(value) = preferences.edit { putString(KEY_LAST_PACKAGE, value) }

    /**
     * Deliberately slow, and therefore never called on the main thread.
     *
     * PBKDF2 at this iteration count is the point of the exercise, but it took
     * long enough on a tablet to trip an ANR when it ran on the UI thread. Both
     * of these hop to a worker; the caller shows that something is happening.
     */
    suspend fun setPin(pin: String) =
        withContext(Dispatchers.Default) {
            val created = PinHash.create(pin)
            preferences.edit {
                putString(KEY_SALT, created.salt)
                putString(KEY_DIGEST, created.digest)
            }
        }

    suspend fun verify(pin: String): Boolean =
        withContext(Dispatchers.Default) {
            val stored = stored() ?: return@withContext false
            PinHash.matches(pin, stored)
        }

    private fun stored(): PinHash.Stored? {
        val salt = preferences.getString(KEY_SALT, null) ?: return null
        val digest = preferences.getString(KEY_DIGEST, null) ?: return null
        return PinHash.Stored(salt, digest)
    }

    /**
     * Whether the system will pin an app at all.
     *
     * Android's screen pinning is off until somebody turns it on in
     * Settings → Security → App pinning, and an app cannot turn it on for them.
     * With it off, `startLockTask` does nothing and reports nothing, which makes
     * for a "Hand over" button that looks like it worked and did not — so this
     * is checked and said out loud instead.
     */
    fun isPinningAllowedBySystem(): Boolean =
        android.provider.Settings.System.getInt(
            applicationContext.contentResolver,
            "lock_to_app_enabled",
            0,
        ) != 0

    /** True while the system has this app pinned to the screen. */
    fun isPinnedToScreen(): Boolean {
        val manager =
            applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /**
     * Asks the system to pin the app.
     *
     * The first time, Android shows its own confirmation and explains the unpin
     * gesture; that dialog is the platform's and cannot be suppressed, which is
     * as it should be — the person being handed the tablet deserves to be told
     * how to get out.
     */
    fun pinToScreen(activity: Activity): Boolean =
        try {
            activity.startLockTask()
            true
        } catch (e: IllegalStateException) {
            // Swallowing this is what made the failure invisible the first time
            // round: the button looked like it worked. The caller shows the
            // caregiver that pinning is not in force; this says why in the log.
            Log.w(TAG, "screen pinning refused", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "screen pinning not permitted", e)
            false
        }

    fun releaseScreen(activity: Activity) {
        runCatching { activity.stopLockTask() }
    }

    private companion object {
        const val TAG = "Handover"
        const val STORE = "handover"
        const val KEY_SALT = "pin_salt"
        const val KEY_DIGEST = "pin_digest"
        const val KEY_HANDED_OVER = "handed_over"
        const val KEY_LAST_PACKAGE = "last_package"
    }
}
