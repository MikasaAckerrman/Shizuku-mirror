package moe.shizuku.manager.starter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat as NotifCompat
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException

/**
 * Foreground service that actually performs the Shizuku start. This keeps the
 * start alive even when the user swipes the app away from recents.
 */
class StarterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sb = StringBuilder()
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Shizuku is starting...", true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        StarterState.setStarting(true)

        val root = intent.getBooleanExtra(EXTRA_IS_ROOT, true)
        val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 0)

        // [fix] Do NOT stop the server here. Shizuku.exit() is a
        // synchronous binder call and Thread.sleep() would block the MAIN
        // thread (jank/ANR risk). Worse, it raced with the start paths:
        // if the server was slow to die, the "already running" fast path
        // in startRoot()/startAdb() could report success WITHOUT starting
        // anything, leaving Shizuku dead. Stopping (for a mode switch) now
        // happens inside the coroutines, off the main thread.
        if (root) {
            startRoot()
        } else {
            startAdb(host, port)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shizuku starter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the status while Shizuku is starting"
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean): android.app.Notification {
        val builder = NotifCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle("Shizuku")
            .setContentText(text)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
        if (!ongoing && text.contains("failed")) {
            // [fix] "tap for details" now really opens the details: the
            // error notification carries the same intent the service
            // launches directly. Flag_IMMUTABLE is required on API 31+.
            val intent = Intent(this, StarterActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_SILENT, false)
                putExtra(StarterActivity.EXTRA_OUTPUT, sb.toString())
            }
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pi)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, ongoing: Boolean = false) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text, ongoing))
    }

    private fun finishSuccess(showToast: Boolean = true) {
        StarterState.setStarting(false)
        updateNotification("Shizuku started", false)
        scope.launch(Dispatchers.Main) {
            if (showToast) {
                Toast.makeText(this@StarterService, "Shizuku started", Toast.LENGTH_SHORT).show()
            }
            kotlinx.coroutines.delay(1500)
            stopSelf()
        }
    }

    private fun finishError(throwable: Throwable) {
        StarterState.setStarting(false)
        updateNotification("Shizuku failed - tap for details", false)
        val intent = Intent(this, StarterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
            putExtra(StarterActivity.EXTRA_SILENT, false)
            putExtra(StarterActivity.EXTRA_OUTPUT, sb.toString())
        }
        startActivity(intent)
        stopSelf()
    }

    private fun startRoot() {
        sb.append("Starting with root...").append('\n').append('\n')

        scope.launch {
            // [fix] Any unexpected failure must reach finishError():
            // otherwise StarterState stays "starting" forever and both
            // start buttons remain disabled until the app restarts.
            try {
                // [fix] If a REACHABLE server is running, stop it first:
                // the user pressed "Start with root" to switch modes (or
                // to force a restart). An UNREACHABLE server (a zombie
                // that survived a soft reboot) is left alone here — the
                // native starter kills it by name.
                stopExistingServerIfRunning()

                if (!Shell.getShell().isRoot) {
                    Shell.getCachedShell()?.close()
                    sb.append("Can't open root shell, try again...\n")
                    if (!Shell.getShell().isRoot) {
                        sb.append("Still not :(\n")
                        finishError(RuntimeException("Root start failed"))
                        return@launch
                    }
                }

            Shell.cmd(Starter.internalCommand).to(object : CallbackList<String?>() {
                override fun onAddElement(s: String?) {
                    sb.append(s).append('\n')
                }
            }).submit {
                val shellCode = it.code
                // [fix] submit() callbacks run on the main thread; the
                // verification polls with sleeps, so run it on IO.
                scope.launch(Dispatchers.IO) {
                    if (shellCode == 0 && StarterEngine.verifyBinderAlive()) {
                        finishSuccess()
                    } else {
                        sb.append("Send this to developer may help solve the problem.\n")
                        finishError(RuntimeException("Root start failed"))
                    }
                }
            }
            } catch (t: Throwable) {
                // [fix] unexpected failure — must release StarterState.
                finishError(t)
            }
        }
    }

    private fun startAdb(host: String, port: Int) {
        val mode = if (port == 5555) "ADB" else "wireless adb"
        sb.append("Starting with $mode in port $port...").append('\n').append('\n')

        scope.launch {
            // [fix] Any unexpected failure must reach finishError():
            // otherwise StarterState stays "starting" forever and both
            // start buttons remain disabled until the app restarts.
            try {
                // [fix] If a REACHABLE server is running, stop it first
                // (the user pressed Start to switch modes or to force a
                // restart). An UNREACHABLE server — a zombie after a soft
                // reboot — is handled by the native starter, which kills
                // it by name; a ROOT-owned zombie (which the shell
                // starter cannot SIGKILL) gets the root-shell fallback
                // inside the engine.
                stopExistingServerIfRunning()

                when (val result = StarterEngine.adbStart(port) { line -> sb.append(line).append('\n') }) {
                    is StarterEngine.Result.AlreadyRunning -> finishSuccess()
                    is StarterEngine.Result.Started -> finishSuccess()
                    is StarterEngine.Result.Failed -> {
                        sb.append('\n').append(describe(result))
                        finishError(failureToThrowable(result))
                    }
                }
            } catch (t: Throwable) {
                // [fix] unexpected failure — must release StarterState.
                finishError(t)
            }
        }
    }

    private fun describe(result: StarterEngine.Result.Failed): String = when (result.failure) {
        StarterEngine.Failure.NoListener -> "No ADB listener found: wireless debugging is off and adbd is not listening on TCP."
        StarterEngine.Failure.Unauthorized -> "adbd rejected our key: the device does not trust this app yet."
        StarterEngine.Failure.ServerDidNotConfirm -> "Server did not confirm it is running."
        is StarterEngine.Failure.Error -> Log.getStackTraceString(result.failure.throwable)
        else -> ""
    }

    private fun failureToThrowable(result: StarterEngine.Result.Failed): Throwable = when (val f = result.failure) {
        StarterEngine.Failure.NoListener -> ConnectException("no adbd listener")
        StarterEngine.Failure.Unauthorized -> SSLProtocolException("adb key not authorized")
        StarterEngine.Failure.ServerDidNotConfirm -> RuntimeException("Server did not confirm it is running.")
        is StarterEngine.Failure.Error -> f.throwable
        else -> RuntimeException("unknown start failure")
    }

    /**
     * Stop the running server if it is currently REACHABLE (binder alive).
     *
     * This covers the mode-switch case: the user pressed Start while a
     * working server of another mode is running. A server we cannot reach
     * (a zombie after a soft reboot — its binder delivery is dead even
     * though the process survives) is NOT handled here: the native starter
     * kills it by name, or the root-shell fallback in the engine does.
     */
    private fun stopExistingServerIfRunning(): Boolean {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return false
        sb.append("Service is already running, stopping it first...\n")
        try {
            Shizuku.exit()
        } catch (e: Throwable) {
            // The server may have died between the ping and the exit.
            e.printStackTrace()
        }
        // Wait (bounded) for the server to actually go down so the new
        // start is clean; the server exits asynchronously.
        val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
            try {
                Thread.sleep(STOP_POLL_MS)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
                return true
            }
        }
        return true
    }

    companion object {
        private const val CHANNEL_ID = "shizuku_starter"
        private const val NOTIFICATION_ID = 1

        // [fix] How long to wait for the server to die after Shizuku.exit().
        private const val STOP_TIMEOUT_MS = 2000L
        private const val STOP_POLL_MS = 100L

        const val EXTRA_IS_ROOT = "moe.shizuku.manager.starter.EXTRA_IS_ROOT"
        const val EXTRA_HOST = "moe.shizuku.manager.starter.EXTRA_HOST"
        const val EXTRA_PORT = "moe.shizuku.manager.starter.EXTRA_PORT"
    }
}
