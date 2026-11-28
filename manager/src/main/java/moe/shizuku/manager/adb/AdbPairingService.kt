package moe.shizuku.manager.adb

import android.annotation.TargetApi
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import rikka.core.ktx.unsafeLazy
import java.net.ConnectException

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val notificationChannel = "adb_pairing"

        private const val tag = "AdbPairingService"

        private const val notificationId = 1
        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val replyAction = "reply"
        private const val remoteInputResultKey = "paring_code"
        private const val portKey = "port"
        private const val forceRestartKey = "force_restart"

        fun startIntent(context: Context, forceRestart: Boolean = false): Intent {
            return Intent(context, AdbPairingService::class.java)
                .setAction(startAction)
                .putExtra(forceRestartKey, forceRestart)
        }

        private fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(stopAction)
        }

        private fun replyIntent(context: Context, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)
        }
    }

    private var adbMdns: AdbMdns? = null

    private val observer = Observer<Int> { port ->
        Log.i(tag, "Pairing service port: $port")
        if (port <= 0) {
            lastFoundPort = -1
            if (started) {
                getSystemService(NotificationManager::class.java).notify(notificationId, searchingNotification)
            }
            return@Observer
        }

        lastFoundPort = port
        // Since the service could be killed before user finishing input,
        // we need to put the port into Intent
        val notification = createInputNotification(port)

        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    @Volatile
    private var started = false

    @Volatile
    private var lastFoundPort = -1

    override fun onCreate() {
        super.onCreate()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            startAction -> {
                onStart(forceRestart = intent.getBooleanExtra(forceRestartKey, false))
            }
            replyAction -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                if (port != -1) {
                    onInput(code.toString(), port)
                } else {
                    onStart(forceRestart = true)
                }
            }
            stopAction -> {
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).cancel(notificationId)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        if (notification != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        notificationId,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                    )
                } else {
                    startForeground(notificationId, notification)
                }
            } catch (e: Throwable) {
                Log.e(tag, "startForeground failed", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException) {
                    getSystemService(NotificationManager::class.java).notify(notificationId, notification)
                }
            }
        }
        return if (intent.action == startAction) START_REDELIVER_INTENT else START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(tag, "shortService timeout reached (startId=$startId)")
        stopSearch()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val timeoutNotification = Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_failed_title))
            .setContentText(getString(R.string.notification_adb_pairing_retry))
            .addAction(retryNotificationAction)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, timeoutNotification)
        stopSelf(startId)
    }

    private fun startSearch(forceRestart: Boolean = false) {
        if (started) {
            if (forceRestart || adbMdns == null) {
                lastFoundPort = -1
                if (adbMdns != null) {
                    adbMdns?.restart()
                } else {
                    adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
                }
            }
            return
        }
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    private fun stopSearch() {
        started = false
        lastFoundPort = -1
        adbMdns?.stop()
        adbMdns = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
    }

    private fun onStart(forceRestart: Boolean = false): Notification {
        if (!forceRestart) {
            val existingPort = lastFoundPort
            if (started && existingPort in 1..65535) {
                return createInputNotification(existingPort)
            }
        }
        startSearch(forceRestart)
        return searchingNotification
    }

    private fun onInput(code: String, port: Int): Notification {
        GlobalScope.launch(Dispatchers.IO) {
            val host = "127.0.0.1"

            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                handleResult(false, AdbKeyException(e))
                return@launch
            }

            AdbPairingClient(host, port, code, key).runCatching {
                try {
                    start()
                } finally {
                    try {
                        close()
                    } catch (_: Throwable) {
                    }
                }
            }.onFailure {
                handleResult(false, it)
            }.onSuccess {
                handleResult(it, null)
            }
        }

        return workingNotification
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        val title: String
        val text: String?

        if (success) {
            Log.i(tag, "Pair succeed")
            ShizukuSettings.setAdbTrusted(true)

            title = getString(R.string.notification_adb_pairing_succeed_title)
            text = getString(R.string.notification_adb_pairing_succeed_text)

            stopSearch()
        } else {
            stopSearch()
            title = getString(R.string.notification_adb_pairing_failed_title)

            text = when (exception) {
                is ConnectException -> {
                    getString(R.string.cannot_connect_port)
                }
                is AdbInvalidPairingCodeException -> {
                    getString(R.string.paring_code_is_wrong)
                }
                is AdbKeyException -> {
                    getString(R.string.adb_error_key_store)
                }
                else -> {
                    exception?.let { Log.getStackTraceString(it) }
                }
            }

            if (exception != null) {
                Log.w(tag, "Pair failed", exception)
            } else {
                Log.w(tag, "Pair failed")
            }
        }

        val builder = Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)

        if (success) {
            val launchIntent = Intent(this, moe.shizuku.manager.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(moe.shizuku.manager.home.HomeActivity.EXTRA_AUTO_START_WIRELESS, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                1002,
                launchIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else
                    PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(pendingIntent)
        } else {
            builder.addAction(retryNotificationAction)
        }

        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            builder.build()
        )
        stopSelf()
    }

    private val stopNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_stop_searching),
            pendingIntent
        )
            .build()
    }

    private val retryNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            retryRequestId,
            startIntent(this, forceRestart = true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_retry),
            pendingIntent
        )
            .build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        val remoteInput = RemoteInput.Builder(remoteInputResultKey).run {
            setLabel(getString(R.string.dialog_adb_pairing_paring_code))
            build()
        }

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_input_paring_code),
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private val searchingNotification by unsafeLazy {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        val tapIntent = Intent(this, AdbPairingTutorialActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            1001,
            tapIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(tapPendingIntent)
            .addAction(replyNotificationAction(port))
            .build()
    }

    private val workingNotification by unsafeLazy {
        Notification.Builder(this, notificationChannel)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
