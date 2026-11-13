package moe.shizuku.manager.adb

import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * One-time provisioning: installs the app's ADB public key into the device
 * trust store (/data/misc/adb/adb_keys) via a root shell.
 *
 * This is the no-Wi-Fi, no-cable alternative to wireless debugging pairing:
 * after this single tap (performed once, during a window when root IS
 * available) the ADB start path works silently forever — adb_keys lives in
 * /data and survives reboots, and adbd picks up new keys dynamically.
 *
 * Never called automatically: the user presses the button.
 */
object TrustSeeder {

    private const val TAG = "TrustSeeder"

    private const val ADB_KEYS = "/data/misc/adb/adb_keys"

    fun isRootAvailable(): Boolean = try {
        Shell.getShell().isRoot
    } catch (t: Throwable) {
        false
    }

    /**
     * Append the current app key's public part to adb_keys (idempotent).
     *
     * @return true when the key line is verifiably present afterwards.
     */
    fun seed(onLine: ((String) -> Unit)? = null): Boolean {
        val line = try {
            AdbKeyHolder.get().adbPublicKeyLine.trim()
        } catch (t: Throwable) {
            Log.w(TAG, "key unavailable", t)
            return false
        }
        if (!line.contains(' ')) {
            Log.w(TAG, "unexpected public key line format")
            return false
        }
        val pub64 = line.substringBefore(' ')

        val exists = Shell.cmd("test -f $ADB_KEYS && echo yes || echo no")
            .exec().out.firstOrNull()?.trim() == "yes"

        val commands = buildList {
            add("mkdir -p /data/misc/adb")
            if (!exists) {
                // Fresh file: make sure adbd (uid shell + adb group) can read it.
                add("chmod 644 $ADB_KEYS 2>/dev/null || true")
                add("chown root:root $ADB_KEYS 2>/dev/null || true")
            }
            add("grep -qF '$pub64' $ADB_KEYS 2>/dev/null || echo '$line' >> $ADB_KEYS")
            add("grep -qF '$pub64' $ADB_KEYS 2>/dev/null && echo SEEDED || echo FAILED")
        }

        val result = Shell.cmd(*commands.toTypedArray()).exec()
        val ok = result.out.any { it.trim() == "SEEDED" }
        Log.i(TAG, "seed: ok=$ok code=${result.code}")
        onLine?.invoke(if (ok) "Trust seeded into $ADB_KEYS" else "Seed failed (exit ${result.code})")
        return ok
    }
}