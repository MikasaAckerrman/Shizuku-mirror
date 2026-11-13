package moe.shizuku.manager.starter

import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyHolder
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * [engine] Single implementation of the ADB start flow. StarterActivity,
 * StarterService and BootCompleteReceiver are thin wrappers around this.
 *
 * Happy path (the agreed fast-path mechanic): cached key + cached port +
 * a ~1 ms LISTEN guard, then ONE connect attempt with tight budgets.
 * Expensive discovery (full port scan) runs only after the fast path misses.
 *
 * Cache invalidation rules:
 * - success                → lastGoodPort = port, authorized = true
 * - TCP refused (no adbd)  → lastGoodPort cleared, next start goes full discovery
 * - key rejected by adbd   → authorized = false (port is live and kept)
 */
object StarterEngine {

    private const val TAG = "StarterEngine"

    private const val PREF_LAST_GOOD_PORT = "engine_last_good_port"
    private const val PREF_AUTHORIZED = "engine_authorized"

    private const val MARKER = "info: shizuku_starter exit with 0"

    // [fix] Printed by the native starter when SIGKILL fails with EPERM —
    // the running server was started as root and a shell-uid starter cannot
    // kill it.
    private const val ROOT_ZOMBIE_MARKER = "fatal: can't kill"

    // Happy-path budgets: loopback adbd connects in <1 ms. The observed
    // "ECONNREFUSED after 10000 ms" (TUN/firewall swallowing SYN) proves
    // refusal is NOT always instant, so every attempt is tightly bounded.
    private const val FAST_CONNECT_TIMEOUT_MS = 1_500
    private const val FAST_READ_TIMEOUT_MS = 3_000
    private const val SCAN_READ_TIMEOUT_MS = 1_500
    private const val VERIFY_TIMEOUT_MS = 8_000L
    private const val MANUAL_TOTAL_BUDGET_MS = 45_000L

    sealed class Failure {
        /** Nothing is listening on any candidate port. */
        object NoListener : Failure()

        /** TCP/TLS reached adbd but it rejected our key. */
        object Unauthorized : Failure()

        /** Start command was dispatched but the server never confirmed. */
        object ServerDidNotConfirm : Failure()

        data class Error(val throwable: Throwable) : Failure()
    }

    sealed class Result {
        object AlreadyRunning : Result()
        data class Started(val port: Int, val elapsedMs: Long) : Result()
        data class Failed(val failure: Failure, val detail: String?) : Result()
    }

    private enum class Attempt { DISPATCHED, AUTH_REJECTED, NO_CONNECT, ERROR }

    // ---------- public API ----------

    /**
     * Manual start (button press): fast path → discovery → verify → cache.
     * Blocking — call from a background dispatcher.
     */
    fun adbStart(forcedPort: Int = -1, onLine: (String) -> Unit): Result {
        val t0 = SystemClock.elapsedRealtime()
        if (pingBinderSafe()) {
            onLine("Service is already running, nothing to do.")
            return Result.AlreadyRunning
        }

        val key = try {
            AdbKeyHolder.get()
        } catch (t: Throwable) {
            Log.w(TAG, "adb key unavailable", t)
            return Result.Failed(Failure.Error(t), Log.getStackTraceString(t))
        }

        val deadline = System.currentTimeMillis() + MANUAL_TOTAL_BUDGET_MS
        val tried = LinkedHashSet<Int>()

        // Stage A — fast path: candidate ports validated by a ~1 ms LISTEN
        // check, one tight-budget attempt each.
        for (port in fastCandidates(forcedPort)) {
            tried.add(port)
            val output = StringBuilder()
            var zombieRetryAllowed = true
            while (true) {
                if (System.currentTimeMillis() > deadline) break
                when (attemptConnect(key, port, FAST_CONNECT_TIMEOUT_MS, FAST_READ_TIMEOUT_MS, output, onLine)) {
                    Attempt.DISPATCHED -> {
                        if (verifyStarted(output)) {
                            saveLastGood(port)
                            markAuthorized(true)
                            logTiming(t0, port)
                            return Result.Started(port, SystemClock.elapsedRealtime() - t0)
                        }
                        if (output.contains(ROOT_ZOMBIE_MARKER) && zombieRetryAllowed) {
                            zombieRetryAllowed = false
                            onLine("\n[the running server was started as root, stopping it via root...]")
                            if (killServerViaRoot()) {
                                onLine("[stopped, retrying]")
                                continue
                            }
                            onLine("[no root access: use \"Start with root\" to restart the server]")
                        }
                        return Result.Failed(Failure.ServerDidNotConfirm, output.toString())
                    }
                    Attempt.AUTH_REJECTED -> {
                        markAuthorized(false)
                        return Result.Failed(Failure.Unauthorized, output.toString())
                    }
                    Attempt.NO_CONNECT -> {
                        if (port == lastGoodPort()) saveLastGood(-1)
                        break
                    }
                    Attempt.ERROR -> break
                }
            }
        }

        // Stage B — full discovery (deadline-aware, shared with boot path).
        if (connectAndStart(forcedPort = -1, deadline = deadline, onLine = onLine, exclude = tried, key = key)) {
            if (pingBinderSafe() || verifyStarted(StringBuilder())) {
                markAuthorized(true)
                logTiming(t0, -1)
                return Result.Started(-1, SystemClock.elapsedRealtime() - t0)
            }
            return Result.Failed(Failure.ServerDidNotConfirm, null)
        }

        return Result.Failed(Failure.NoListener, null)
    }

    /**
     * Public verify for root starts: binder alive now, otherwise a short
     * adaptive poll (the server may still be publishing its binder).
     */
    fun verifyBinderAlive(): Boolean = verifyStarted(StringBuilder())

    /**
     * Boot-style dispatch: fast candidates + full LISTEN scan, deadline aware.
     * No binder guard and no verify — the caller polls the binder itself
     * (BootCompleteReceiver owns the ANR budget).
     */
    fun connectAndStart(
        forcedPort: Int = -1,
        deadline: Long = Long.MAX_VALUE,
        onLine: ((String) -> Unit)? = null,
        exclude: Set<Int> = emptySet(),
        key: AdbKey? = null
    ): Boolean {
        val k = key ?: try {
            AdbKeyHolder.get()
        } catch (t: Throwable) {
            Log.w(TAG, "adb key unavailable", t)
            return false
        }
        val tried = LinkedHashSet<Int>().apply { addAll(exclude) }

        for (port in fastCandidates(forcedPort)) {
            if (System.currentTimeMillis() > deadline) return false
            if (port in tried) continue
            tried.add(port)
            if (attemptConnect(k, port, FAST_CONNECT_TIMEOUT_MS, FAST_READ_TIMEOUT_MS, StringBuilder(), onLine ?: {}) == Attempt.DISPATCHED) {
                saveLastGood(port)
                return true
            }
        }
        for (port in listListenPorts()) {
            if (System.currentTimeMillis() > deadline) return false
            if (port in tried) continue
            tried.add(port)
            if (attemptConnect(k, port, FAST_CONNECT_TIMEOUT_MS, SCAN_READ_TIMEOUT_MS, StringBuilder(), onLine ?: {}) == Attempt.DISPATCHED) {
                saveLastGood(port)
                return true
            }
        }
        return false
    }

    // ---------- discovery helpers ----------

    /**
     * Snapshot of ports actually LISTENing, or null when /proc/net/tcp is not
     * readable by this process. Apps LOSE access to /proc/net on Android 10+
     * (SELinux proc_net restriction) - proven on-device by "Access denied
     * finding property" + silent no-op start - so the guard degrades: callers
     * attempt candidates directly, bounded by tight budgets.
     */
    fun listenPorts(): Set<Int>? {
        val set = HashSet<Int>()
        for (f in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                File(f).forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4 && parts[3] == "0A") { // 0A = LISTEN
                        val p = parts[1].substringAfterLast(':').toIntOrNull(16)
                        if (p != null && p in 1..65535) set.add(p)
                    }
                }
            } catch (e: Exception) {
                return null
            }
        }
        return set
    }

    /**
     * "Should we attempt this port?" guard: true when listening, and ALSO true
     * when the snapshot is unavailable (unknown) - a direct attempt is bounded
     * by tight budgets, so attempting is always cheap.
     */
    fun isPortListening(port: Int): Boolean {
        if (port !in 1..65535) return false
        return listenPorts()?.contains(port) ?: true
    }

    /** [fix-1] Full-range LISTEN scan; empty when /proc is unreadable. 5555 is covered by fastCandidates. */
    fun listListenPorts(): List<Int> {
        val set = listenPorts() ?: return emptyList()
        return set.filter { it != 5555 }.sorted()
    }

    /**
     * Ordered candidate ports (fastest/most reliable first), all filtered by
     * the LISTEN guard: forced (mDNS) → last good → service.adb.tcp.port /
     * persist.adb.tcp.port (the no-Wi-Fi path) → 5555 → service.adb.tls.port.
     */
    private fun fastCandidates(forcedPort: Int): List<Int> {
        val ports = LinkedHashSet<Int>()
        if (forcedPort in 1..65535) ports.add(forcedPort)
        lastGoodPort().takeIf { it in 1..65535 }?.let { ports.add(it) }
        runCatching { EnvironmentUtils.getAdbTcpPort() }.getOrNull()
            ?.takeIf { it in 1..65535 }?.let { ports.add(it) }
        ports.add(5555)
        runCatching { EnvironmentUtils.getAdbTlsPort() }.getOrNull()
            ?.takeIf { it in 1..65535 }?.let { ports.add(it) }
        // [fix] When /proc is unreadable (Android 10+ apps) the guard cannot
        // vouch for anything - attempt the candidates directly instead of
        // filtering them all out (the old behavior that made the start a
        // silent no-op).
        val snapshot = listenPorts()
        return if (snapshot == null) ports.toList() else ports.filter { snapshot.contains(it) }
    }

    // ---------- connection ----------

    private fun attemptConnect(
        key: AdbKey,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        output: StringBuilder,
        onLine: (String) -> Unit
    ): Attempt {
        val tConnect = SystemClock.elapsedRealtime()
        var client: AdbClient? = null
        return try {
            client = AdbClient("127.0.0.1", port, key, readTimeoutMs, connectTimeoutMs)
            client.connect()
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "connect($port) took ${SystemClock.elapsedRealtime() - tConnect} ms")
            }
            client.shellCommand(Starter.internalCommand) { bytes ->
                val chunk = String(bytes)
                output.append(chunk)
                onLine(chunk)
            }
            Attempt.DISPATCHED
        } catch (e: ConnectException) {
            Attempt.NO_CONNECT
        } catch (e: SocketTimeoutException) {
            Attempt.NO_CONNECT
        } catch (e: SSLException) {
            Log.i(TAG, "TLS/auth rejected on port $port: ${e.message}")
            Attempt.AUTH_REJECTED
        } catch (e: IOException) {
            // adbd closed the stream during auth — classic "unauthorized"
            if (e.message?.contains("A_CNXN") == true) Attempt.AUTH_REJECTED else Attempt.ERROR
        } catch (e: Throwable) {
            Attempt.ERROR
        } finally {
            runCatching { client?.close() }
        }
    }

    /** Adaptive verify: 50 ms steps at first, 250 ms later. */
    private fun verifyStarted(output: StringBuilder): Boolean {
        if (pingBinderSafe()) return true
        if (output.contains(MARKER)) return true
        var waited = 0L
        while (waited < VERIFY_TIMEOUT_MS) {
            val step = if (waited < 500L) 50L else 250L
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                return output.contains(MARKER)
            }
            waited += step
            if (pingBinderSafe()) return true
        }
        return output.contains(MARKER)
    }

    /** Kill a running shizuku_server through a ROOT shell (EPERM zombie case). */
    private fun killServerViaRoot(): Boolean {
        return try {
            if (!Shell.getShell().isRoot) return false
            val pid = Shell.cmd("pidof -s shizuku_server").exec()
                .out.firstOrNull()?.trim().orEmpty()
            if (pid.isEmpty()) {
                true // already gone
            } else {
                Shell.cmd("kill -9 $pid").exec().isSuccess
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    // ---------- cache ----------

    private val prefs get() = ShizukuSettings.getPreferences()

    fun lastGoodPort(): Int = prefs.getInt(PREF_LAST_GOOD_PORT, -1)

    /** True only after adbd explicitly rejected our key (not the default). */
    fun isKnownUnauthorized(): Boolean = !prefs.getBoolean(PREF_AUTHORIZED, true)

    private fun saveLastGood(port: Int) {
        prefs.edit().putInt(PREF_LAST_GOOD_PORT, port).apply()
    }

    private fun markAuthorized(ok: Boolean) {
        prefs.edit().putBoolean(PREF_AUTHORIZED, ok).apply()
    }

    private fun pingBinderSafe(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun logTiming(t0: Long, port: Int) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "start ok port=$port total=${SystemClock.elapsedRealtime() - t0} ms")
        }
    }
}
