package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterEngine
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // [fix-6] Accept USER_UNLOCKED too: on devices with a lock screen
        // (PIN/pattern/password) the credential-encrypted storage — where
        // both the launch mode and the ADB key live — is NOT readable at
        // LOCKED_BOOT_COMPLETED / BOOT_COMPLETED while the phone is still
        // locked. The receiver would read mode=UNKNOWN and silently do
        // nothing. USER_UNLOCKED fires right after the user unlocks, when
        // CE storage becomes available — that is the moment the start can
        // actually succeed. pingBinder() guards against redundant work.
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action
            && Intent.ACTION_USER_UNLOCKED != intent.action) {
            return
        }

        if (UserHandleCompat.myUserId() > 0 || pingBinderSafe()) return

        // [fix-7] CE storage (where the launch mode lives) is unreadable at
        // LOCKED_BOOT_COMPLETED and at BOOT_COMPLETED while the screen is
        // locked — getLastLaunchMode() returns UNKNOWN and the start never
        // happens. Fall back to a device-encrypted mirror of the mode so
        // the server starts at LOCKED_BOOT_COMPLETED (~15-25 s from power),
        // BEFORE the user even enters their PIN.
        val mode = getLaunchModeWithDeFallback(context)
        if (mode == LaunchMethod.ROOT) {
            rootStart(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU // https://r.android.com/2128832
            && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            && (mode == LaunchMethod.ADB || mode == LaunchMethod.UNKNOWN)) {
            // [fix-7] UNKNOWN here means CE is locked but the mode may still
            // have been configured — and ADB is the only launch method that
            // can work without root surviving reboot. Attempt it; the
            // connectAndStart failure path is harmless (no dialog: our key
            // is pre-authorized in /data/misc/adb/adb_keys).
            adbStart(context)
            // Mirror the mode to DE for the next (even earlier) boot.
            runCatching {
                context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(DE_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_DE_MODE, LaunchMethod.ADB)
                    .putBoolean(KEY_DE_MIRRORED, true)
                    .apply()
            }
        } else {
            Log.w(AppConstants.TAG, "No support start on boot")
        }
    }

    /**
     * [fix-7] The launch mode as seen at the current boot stage: the CE value
     * when readable, otherwise the DE mirror written by a previous start.
     */
    private fun getLaunchModeWithDeFallback(context: Context): Int {
        val ceMode = ShizukuSettings.getLastLaunchMode()
        if (ceMode != LaunchMethod.UNKNOWN) return ceMode
        return runCatching {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(DE_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_DE_MODE, LaunchMethod.UNKNOWN)
        }.getOrDefault(LaunchMethod.UNKNOWN)
    }

    private companion object {
        const val DE_PREFS = "boot_de"
        const val KEY_DE_MODE = "mode"
        const val KEY_DE_MIRRORED = "mirrored"

        /** [fix-8] goAsync() grants ~10 s before ANR; leave a safety margin. */
        const val BROADCAST_DEADLINE_MS = 8_000L

        /** [fix-8] mDNS discovery + connect needs ~3 s; skip if we can't afford it. */
        const val MDNS_BUDGET_MS = 4_000L

        // Scan read budgets moved to StarterEngine (SCAN_READ_TIMEOUT_MS).
    }

    private fun rootStart(context: Context) {
        // [fix-15] Shell.getShell() blocks up to 10 s — running it inside
        // onReceive (main thread) is an ANR risk. Move the whole start into
        // the IO dispatcher, same as the ADB path.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!Shell.getShell().isRoot) {
                    Shell.getCachedShell()?.close()
                    return@launch
                }
                Shell.cmd(Starter.internalCommand).exec()
            } finally {
                pending.finish()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun adbStart(context: Context) {
        val cr = context.contentResolver
        // [fix-3] DO NOT put adb_wifi_enabled=1 here: on vivo OriginOS this wakes
        // the wireless-debugging controller which force-resets persist.adb.tls_server.enable
        // within ~30s. Only enable it later, if (and only if) we fall back to mDNS.
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                adbStartInner(context, cr)
            } finally {
                pending.finish()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun adbStartInner(context: Context, cr: android.content.ContentResolver) {
        // [fix-8] Hard deadline: goAsync() only grants ~10 s before the
        // system marks the receiver ANR and shows "Shizuku is not
        // responding". Track a wall-clock deadline and bail out before
        // the ANR window: a late start is better than a crash dialog.
        val deadline = System.currentTimeMillis() + BROADCAST_DEADLINE_MS

        // [fix-19] starter.cpp now BLOCKS until the server's binder is
        // registered (or its own 3 s timeout elapses). We only need a single
        // attempt and a very short safety poll; the 5 s polling loop is gone.
        if (pingBinderSafe()) return
        try {
            // Engine: fast candidates (forced → lastGood → prop → 5555 → tls)
            // guarded by the ~1 ms LISTEN check, then the full LISTEN scan.
            StarterEngine.connectAndStart(forcedPort = -1, deadline = deadline)
        } catch (_: Exception) {
            return
        }
        // Safety: if starter timed out, wait just a little longer for the
        // server that is already starting.
        for (i in 1..5) {
            if (pingBinderSafe()) return
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return
            }
            if (System.currentTimeMillis() > deadline) return
        }

        // Last resort: mDNS (needs Wi-Fi). Enable the toggle only here —
        // the controller reset no longer matters because direct connect failed.
        // [fix-8] Skip if we are already close to the ANR deadline.
        if (System.currentTimeMillis() + MDNS_BUDGET_MS > deadline) return
        try {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
        } catch (_: Exception) {
        }
        if (runCatching { Settings.Global.getInt(cr, "adb_wifi_enabled", 0) }.getOrDefault(0) == 1) {
            val latch = CountDownLatch(1)
            val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                if (port <= 0) return@AdbMdns
                try {
                    StarterEngine.connectAndStart(forcedPort = port, deadline = deadline)
                } catch (_: Exception) {
                }
                latch.countDown()
            }
            adbMdns.start()
            latch.await(3, TimeUnit.SECONDS)
            adbMdns.stop()
        }
    }

    /** [fix-11] pingBinder must never crash the receiver: a bad receiver gets
     *  permanently disabled by the system, killing autostart forever. */
    private fun pingBinderSafe(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
}
