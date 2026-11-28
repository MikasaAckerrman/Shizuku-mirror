package moe.shizuku.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLProtocolException

/**
 * Единый модуль обнаружения порта для ручного запуска (без автозапуска на boot).
 * Объединяет быстрый локальный зонд (getAdbTcpPort / getAdbTlsPort / FastPortScout)
 * и mDNS-дискавери (_adb-tls-connect._tcp) строго в фоновом потоке.
 */
object PortDiscovery {

    class Session(
        private val context: Context,
        private val scope: CoroutineScope,
        private val onPortFound: (Int) -> Unit
    ) {
        private val delivered = AtomicBoolean(false)
        private var adbMdns: AdbMdns? = null
        private var pollJob: Job? = null

        fun start() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT, Observer { port ->
                    if (port in 1..65535) {
                        scope.launch(Dispatchers.IO) {
                            if (!delivered.get() && AdbClient.isAdbListener("127.0.0.1", port, 300)) {
                                deliver(port)
                            }
                        }
                    }
                }).apply { start() }
            }

            pollJob = scope.launch(Dispatchers.IO) {
                val direct = findDirectAdbPort()
                if (direct in 1..65535) {
                    deliver(direct)
                    return@launch
                }

                while (isActive && !delivered.get()) {
                    delay(700)
                    val tlsPort = EnvironmentUtils.getAdbTlsPort()
                    if (tlsPort in 1..65535 && AdbClient.isAdbListener("127.0.0.1", tlsPort, 250)) {
                        deliver(tlsPort)
                        return@launch
                    }
                }
            }
        }

        fun stop() {
            pollJob?.cancel()
            pollJob = null
            adbMdns?.stop()
            adbMdns = null
        }

        private fun deliver(port: Int) {
            if (delivered.compareAndSet(false, true)) {
                onPortFound(port)
            }
        }
    }

    /**
     * Быстрая проверка готового локального ADB-порта (tcp.port, 5555, tls.port, procfs) в IO-потоке.
     */
    fun findDirectAdbPort(): Int {
        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        if (tcpPort in 1..65535 && AdbClient.isAdbListener("127.0.0.1", tcpPort, 200)) {
            return tcpPort
        }
        if (AdbClient.isAdbListener("127.0.0.1", 5555, 200)) {
            return 5555
        }
        val tlsPort = EnvironmentUtils.getAdbTlsPort()
        if (tlsPort in 1..65535 && AdbClient.isAdbListener("127.0.0.1", tlsPort, 200)) {
            return tlsPort
        }
        return FastPortScout.findActiveWadbPort(350)
    }

    /**
     * Повторное обнаружение живого WADB-порта при retry-попытках в StarterService / StarterActivity.
     * Сначала проверяет системный service.adb.tls.port и /proc/net/tcp, затем при необходимости
     * выполняет короткий live mDNS-поиск (_adb-tls-connect._tcp) с проверкой isAdbListener.
     */
    fun rediscoverWadbPort(
        context: Context,
        excludePort: Int = -1,
        mdnsTimeoutMs: Long = 1500
    ): Int {
        val tlsPort = EnvironmentUtils.getAdbTlsPort()
        if (tlsPort in 1..65535 && tlsPort != excludePort && AdbClient.isAdbListener("127.0.0.1", tlsPort, 250)) {
            return tlsPort
        }

        val scoutPort = FastPortScout.findActiveWadbPort(400, include5555 = excludePort != 5555)
        if (scoutPort in 1..65535 && scoutPort != excludePort) {
            return scoutPort
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return -1
        }

        val found = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(context.applicationContext, AdbMdns.TLS_CONNECT, Observer { port ->
            if (port in 1..65535 && port != excludePort) {
                if (found.compareAndSet(-1, port)) {
                    latch.countDown()
                }
            }
        })
        return try {
            mdns.start()
            latch.await(mdnsTimeoutMs, TimeUnit.MILLISECONDS)
            found.get()
        } catch (_: Throwable) {
            -1
        } finally {
            mdns.stop()
        }
    }

    /**
     * Находит активный WADB-порт (при необходимости поднимает adb_wifi_enabled=1,
     * если есть WRITE_SECURE_SETTINGS и Wi-Fi), отправляет `tcpip:5555` и ждёт
     * появления TCP-слушателя на targetPort.
     */
    fun activateTcpPortViaWadb(
        context: Context,
        key: AdbKey,
        targetPort: Int = 5555,
        log: ((String) -> Unit)? = null
    ): Boolean {
        if (AdbClient.isAdbListener("127.0.0.1", targetPort, 200)) {
            return true
        }

        var wadbPort = rediscoverWadbPort(context, excludePort = targetPort, mdnsTimeoutMs = 1200)
        if (wadbPort !in 1..65535
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            && EnvironmentUtils.isWifiConnected(context)
        ) {
            try {
                val cr = context.contentResolver
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                log?.invoke("Enabling Wireless debugging temporarily to open port $targetPort...\n")
                val deadline = System.currentTimeMillis() + 2500L
                while (System.currentTimeMillis() < deadline && wadbPort !in 1..65535) {
                    wadbPort = rediscoverWadbPort(context, excludePort = targetPort, mdnsTimeoutMs = 700)
                    if (wadbPort in 1..65535) break
                    Thread.sleep(150)
                }
            } catch (_: Throwable) {
            }
        }

        if (wadbPort !in 1..65535) {
            return false
        }

        return try {
            log?.invoke("Found wireless ADB on port $wadbPort, requesting tcpip:$targetPort...\n")
            val accepted = AdbClient("127.0.0.1", wadbPort, key, 8000).use { client ->
                client.connect()
                ShizukuSettings.setAdbTrusted(true)
                client.tcpip(targetPort)
            }
            if (!accepted) {
                log?.invoke("adbd rejected tcpip:$targetPort on port $wadbPort\n")
                return false
            }
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadline) {
                if (AdbClient.isAdbListener("127.0.0.1", targetPort, 200)) {
                    log?.invoke("Port $targetPort is now open.\n")
                    return true
                }
                Thread.sleep(150)
            }
            AdbClient.isAdbListener("127.0.0.1", targetPort, 250)
        } catch (e: SSLProtocolException) {
            ShizukuSettings.setAdbTrusted(false)
            Log.w("PortDiscovery", "activateTcpPortViaWadb pairing revoked on port $wadbPort", e)
            throw e
        } catch (e: Throwable) {
            Log.w("PortDiscovery", "activateTcpPortViaWadb failed on port $wadbPort", e)
            log?.invoke("Failed to activate port $targetPort via WADB port $wadbPort: ${e.message}\n")
            false
        }
    }
}
