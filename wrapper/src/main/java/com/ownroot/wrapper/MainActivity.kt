package com.ownroot.wrapper

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ownroot.wrapper.adb.AdbClient
import com.ownroot.wrapper.adb.AdbKey
import com.ownroot.wrapper.adb.AdbKeyStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ownroot — оболочка эксплойта рута (в духе кита fuckneo10: отдельное приложение,
 * пред-авторизованный adb-ключ, запуск одной кнопкой).
 *
 * Механизм: собственный AdbClient → 127.0.0.1:5555 (adbd) → `sh restore.sh`
 * — привязанная adb-сессия = короткий walk (архитектура всех успешных прогонов).
 * Kit обязан быть развёрнут в /data/local/tmp (скрипты v3.9.8). Экран не гаснет
 * во время прогона; прогресс транслируется живьём из stdout restore.sh.
 */
class MainActivity : Activity() {

    companion object {
        private const val PREFS = "ownroot"
        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_PORT = 5555
        private const val CMD_RUN = "sh /data/local/tmp/restore.sh"
        private const val CMD_CHECK = "sh -c 'grep -q ^kernelsu /proc/modules && echo R1 || (ls -d /sys/module/kernelsu >/dev/null 2>&1 && echo R1 || echo R0)'"
        private const val COLOR_OK = 0xFF2E7D32.toInt()
        private const val COLOR_FAIL = 0xFFC62828.toInt()
    }

    private lateinit var status: TextView
    private lateinit var progress: TextView
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var lastProgressPost = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "ownroot"
            textSize = 22f
        }
        val subtitle = TextView(this).apply {
            text = "оболочка эксплойта рута"
            textSize = 13f
            setPadding(0, pad / 2, 0, pad)
        }
        status = TextView(this).apply {
            textSize = 17f
            setPadding(0, pad, 0, pad)
        }
        val button = Button(this).apply {
            text = "Получить рут (KernelSU)"
            setOnClickListener { launch() }
        }
        progress = TextView(this).apply {
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(button)
        root.addView(progress)
        setContentView(root)
        refreshStatus()
    }

    /** Ключ: первый запуск импортирует пред-авторизованный PKCS#8 из assets в prefs. */
    private fun key(): AdbKey {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val store = object : AdbKeyStore {
            override fun put(bytes: ByteArray) {
                prefs.edit().putString(
                    "k",
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                ).apply()
            }

            override fun get(): ByteArray? =
                prefs.getString("k", null)
                    ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        }
        if (store.get() == null) {
            val der = assets.open("adbkey.pkcs8").use { it.readBytes() }
            store.put(der)
        }
        return AdbKey(store, "ownroot")
    }

    private fun refreshStatus() = Thread {
        val ok = checkRooted()
        main.post { showStatus(ok) }
    }.start()

    private fun showStatus(ok: Boolean?) {
        when (ok) {
            true -> { status.text = "✓ Рут активен (KernelSU)"; status.setTextColor(COLOR_OK) }
            false -> { status.text = "Рут не активен"; status.setTextColor(COLOR_FAIL) }
            null -> { status.text = "✗ adbd не отвечает — поднять adb (5555)"; status.setTextColor(COLOR_FAIL) }
        }
    }

    /** null = adbd недоступен (не путать с «проверили — рута нет»). */
    private fun checkRooted(): Boolean? = try {
        AdbClient(ADB_HOST, ADB_PORT, key(), 10_000).use { c ->
            c.connect()
            val sb = StringBuilder()
            c.shellCommand(CMD_CHECK) { sb.append(String(it)) }
            sb.toString().contains("R1")
        }
    } catch (e: Throwable) {
        null
    }

    private fun launch() {
        if (!running.compareAndSet(false, true)) return
        status.text = "Запуск… (~60 с, зависит от загрузки)"
        progress.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            var fail: String? = null
            try {
                val sb = StringBuilder()
                AdbClient(ADB_HOST, ADB_PORT, key(), 600_000).use { c ->
                    c.connect()
                    c.shellCommand(CMD_RUN) { chunk ->
                        sb.append(String(chunk))
                        val line = sb.toString().lineSequence()
                            .lastOrNull { it.isNotBlank() } ?: return@shellCommand
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressPost > 700) {
                            lastProgressPost = now
                            main.post { if (running.get()) progress.text = line.take(120) }
                        }
                    }
                }
            } catch (e: Throwable) {
                fail = "✗ Ошибка запуска: ${e.message ?: e.javaClass.simpleName}"
            }
            val ok = checkRooted()
            val reason = if (ok == false) tailLog() else null
            main.post {
                running.set(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showStatus(ok)
                progress.text = fail ?: reason ?: (if (ok == true) "✓ Готово за этот прогон" else "")
            }
        }.start()
    }

    /** Хвост root_run.log — причина отказа (батарея/стадия) видна прямо в приложении. */
    private fun tailLog(): String = try {
        AdbClient(ADB_HOST, ADB_PORT, key(), 10_000).use { c ->
            c.connect()
            val sb = StringBuilder()
            c.shellCommand("tail -n 2 /data/local/tmp/root_run.log") { sb.append(String(it)) }
            sb.toString().trim().take(300).ifEmpty { "Не получен. Лог: /data/local/tmp/root_run.log" }
        }
    } catch (e: Throwable) {
        "Не получен. Лог: /data/local/tmp/root_run.log"
    }
}
