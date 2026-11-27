package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val observer: Observer<Int>
) {

    @Volatile
    private var registered = false

    @Volatile
    private var running = false

    @Volatile
    private var serviceName: String? = null

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val activeInfoCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            if (!registered) {
                startDiscoveryLocked()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            mainHandler.removeCallbacksAndMessages(null)
            clearServiceInfoCallbacksLocked()
            if (!running) return
            running = false
            if (registered) {
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Throwable) {
                    Log.w(TAG, "stopServiceDiscovery failed", e)
                    registered = false
                }
            }
        }
    }

    /**
     * Принудительный перезапуск mDNS-поиска (например при Retry в AdbPairingService).
     */
    fun restart() {
        synchronized(lock) {
            mainHandler.removeCallbacksAndMessages(null)
            clearServiceInfoCallbacksLocked()
            running = false
            if (registered) {
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Throwable) {
                    Log.w(TAG, "restart stopServiceDiscovery failed", e)
                    registered = false
                }
            }
            running = true
            if (!registered) {
                startDiscoveryLocked()
            }
        }
    }

    private fun clearServiceInfoCallbacksLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val it = activeInfoCallbacks.values.iterator()
            while (it.hasNext()) {
                val cb = it.next()
                try {
                    nsdManager.unregisterServiceInfoCallback(cb)
                } catch (_: Throwable) {
                }
                it.remove()
            }
        } else {
            activeInfoCallbacks.clear()
        }
    }

    private fun startDiscoveryLocked() {
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            Log.w(TAG, "discoverServices failed", e)
            running = false
            registered = false
        }
    }

    private fun onDiscoveryStart() {
        synchronized(lock) {
            registered = true
            if (!running) {
                // Остановили до завершения регистрации — корректно гасим discovery.
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Throwable) {
                    Log.w(TAG, "stopServiceDiscovery on late start failed", e)
                    registered = false
                }
            }
        }
    }

    private fun onDiscoveryStop(failed: Boolean = false) {
        synchronized(lock) {
            registered = false
            if (failed) {
                running = false
                return
            }
            if (running) {
                // Если stop() + start() вызвали быстро, запускаем discovery сразу после остановки.
                startDiscoveryLocked()
            }
        }
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        if (!running) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val name = info.serviceName ?: return
            synchronized(lock) {
                if (!running) return
                activeInfoCallbacks.remove(name)?.let { old ->
                    try {
                        nsdManager.unregisterServiceInfoCallback(old)
                    } catch (_: Throwable) {
                    }
                }
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        Log.w(TAG, "onServiceInfoCallbackRegistrationFailed: $name, errorCode=$errorCode")
                    }

                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        onServiceResolved(serviceInfo)
                    }

                    override fun onServiceLost() {
                        onServiceLost(info)
                    }

                    override fun onServiceInfoCallbackUnregistered() {
                    }
                }
                try {
                    nsdManager.registerServiceInfoCallback(info, { r -> mainHandler.post(r) }, callback)
                    activeInfoCallbacks[name] = callback
                    return
                } catch (e: Throwable) {
                    Log.w(TAG, "registerServiceInfoCallback failed, falling back to resolveService", e)
                }
            }
        }
        resolveWithRetry(info, attempt = 0)
    }

    private fun resolveWithRetry(info: NsdServiceInfo, attempt: Int) {
        if (!running) return
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(info, ResolveListener(this, info, attempt))
        } catch (e: Throwable) {
            Log.w(TAG, "resolveService failed (attempt=$attempt)", e)
        }
    }

    private fun onResolveFailed(info: NsdServiceInfo, errorCode: Int, attempt: Int) {
        Log.w(TAG, "onResolveFailed: ${info.serviceName}, errorCode=$errorCode, attempt=$attempt")
        if (!running) return
        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 4) {
            mainHandler.postDelayed({ resolveWithRetry(info, attempt + 1) }, 250L * (attempt + 1))
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.serviceName?.let { name ->
                synchronized(lock) {
                    activeInfoCallbacks.remove(name)?.let { cb ->
                        try {
                            nsdManager.unregisterServiceInfoCallback(cb)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
        if (info.serviceName == serviceName) {
            serviceName = null
            observer.onChanged(-1)
        }
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val port = resolvedService.port
        if (port !in 1..65535) return

        val hosts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolvedService.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(resolvedService.host)
        }
        if (hosts.isEmpty()) return

        val isLocalHost = try {
            val localAddrs = NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.mapNotNull { it.hostAddress }
                ?.toSet()
                .orEmpty()
            hosts.any { it.hostAddress in localAddrs }
        } catch (_: Throwable) {
            false
        }

        if (!running || !isLocalHost) return
        val resolvedName = resolvedService.serviceName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && Looper.myLooper() == Looper.getMainLooper()) {
            // registerServiceInfoCallback диспатчит onServiceUpdated через mainHandler,
            // поэтому сетевую проверку порта переносим с главного потока, чтобы не словить NetworkOnMainThreadException.
            Thread({
                if (running && verifyPortAccepting(port)) {
                    mainHandler.post {
                        if (running) {
                            serviceName = resolvedName
                            observer.onChanged(port)
                        }
                    }
                }
            }, "AdbMdnsVerify").start()
        } else if (verifyPortAccepting(port)) {
            serviceName = resolvedName
            observer.onChanged(port)
        }
    }

    private fun verifyPortAccepting(port: Int): Boolean {
        if (serviceType == TLS_CONNECT) {
            return AdbClient.isAdbListener("127.0.0.1", port, 300)
        }
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 250)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.onDiscoveryStop(failed = true)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            adbMdns.onDiscoveryStop(failed = false)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.onDiscoveryStop(failed = true)
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")
            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(
        private val adbMdns: AdbMdns,
        private val info: NsdServiceInfo,
        private val attempt: Int
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {
            adbMdns.onResolveFailed(info, i, attempt)
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
