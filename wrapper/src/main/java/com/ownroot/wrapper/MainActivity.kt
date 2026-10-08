package com.ownroot.wrapper

import android.app.Activity
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ownroot.wrapper.adb.AdbClient
import com.ownroot.wrapper.adb.AdbKey
import com.ownroot.wrapper.adb.AdbKeyStore
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * ownroot wrapper v5 — запуск рута конвейером oneclick.sh (продакшн-рецептура fuckneo10).
 *
 * Транспорт (приоритет): Shizuku (binder, всегда живой канал) →
 *   fallback AdbClient 127.0.0.1:5555 (статус/запуск при живом adbd и развёрнутом ките).
 *
 * Пайплайн: развёртка кита (15 файлов, md5) → sh oneclick.sh (cheese PHYSCAN a3-ae →
 * rootcmd → slide → insmod rsc/canonical → активация → регистрация менеджера → очистка)
 * → верификация (/sys/module/kernelsu | /proc/modules) → самолечение adb_keys.
 *
 * Инвариант: процесс эксплойта НИКОГДА не убивается (GPU-клинч от убийства).
 */
class MainActivity : Activity() {

    companion object {
        private const val T = "/data/local/tmp"
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555
        private const val REQUEST_SHIZUKU = 101

        /** Кит: имя → md5 (источник oneclick-kit-neo10; rsc сверен с посадкой 09.10 00:19) */
        private val KIT_MD5 = linkedMapOf(
            "cheese" to "c440a79dbc87fe017f867769f10c6c97",
            "device_reksu.sh" to "e502cf3007c9d47f8dc75421fd65b032",
            "getslide.sh" to "d95b0c46ed6b3d80ee4ee823ad8a027b",
            "ksu-manager.apk" to "0326f600099628f95bbcadc91b77c70f",
            "ksu_variant_flag" to "4fddbbb115f5423b3389a8921174b692",
            "libksud.dm" to "9afeaf768dfbcd55364bc3c0e509f778",
            "libksud.orig" to "1d2d90b14ed08db6fa77f16913d2bb65",
            "libksud.so.patched" to "0dbe71af1cd5c13e5e172470fc364728",
            "oneclick.sh" to "beac5e4f8d72ad6b96d10a607731eff8",
            "reksu-manager.apk" to "d7393839851650ae83d7054d12d8f635",
            "resukisu.ko.base.canonical" to "a93cb5dc1ef3dbd4a07084cdd1d91a23",
            "resukisu.ko.base.rsc" to "a572ab0b4a35859574a3e37de5a96c6f",
            "slidepatch" to "77b8b7b176d117eedd44602c482272cc",
            "su" to "265d7c94acce9d2c84a03424d11f3048",
            "su_bin" to "265d7c94acce9d2c84a03424d11f3048"
        )

        /** Исполняемые биты при развёртке */
        private val EXECUTABLES = setOf(
            "cheese", "su", "su_bin", "slidepatch", "device_reksu.sh", "getslide.sh",
            "oneclick.sh", "libksud.orig", "libksud.dm", "libksud.so.patched"
        )

        /** Self-heal: паб-ключи в /data/misc/adb/adb_keys (врапер-ключ + песочница Minis) */
        private val HEAL_PUBKEYS = listOf(
            "AAAAB3NzaC1yc2EAAAADAQABAAABAQC3S2N9rPeg9MenLQn/uHmm9DT/6qdpk7E43fT/yjuHu7sM43bMaZTyFwQE8JyoujyimCx/jCydJdb0iicPDAa2IwwXi8hcdes1BG4Vagpw7qoIWBwW1sH6EcdRN2ihKkQR2m/M1tQPkNBdVffFrfDlTHxYSxFc0ZY2c1fJ0dOfRUX+pYHl+oINH+5oGNQXh3cWRAmaQglOfDpMn/8HjV6rqgViEfyk9MLDQTPfgmuyQc8jR/MM/zyRvePwJEurXHbOGMCKOyKKnK1Ruwnrm5c8dYI+lPalxVCRW2Tk2kAHViqLhsy12ScTUrmc5oNNunBg+AG2QDNB3NL/WmDX6H5F ownroot-kit",
            "AAAAB3NzaC1yc2EAAAADAQABAAABAQC3S2N9rPeg9MenLQn/uHmm9DT/6qdpk7E43fT/yjuHu7sM43bMaZTyFwQE8JyoujyimCx/jCydJdb0iicPDAa2IwwXi8hcdes1BG4Vagpw7qoIWBwW1sH6EcdRN2ihKkQR2m/M1tQPkNBdVffFrfDlTHxYSxFc0ZY2c1fJ0dOfRUX+pYHl+oINH+5oGNQXh3cWRAmaQglOfDpMn/8HjV6rqgViEfyk9MLDQTPfgmuyQc8jR/MM/zyRvePwJEurXHbOGMCKOyKKnK1Ruwnrm5c8dYI+lPalxVCRW2Tk2kAHViqLhsy12ScTUrmc5oNNunBg+AG2QDNB3NL/WmDX6H5F minis-sandbox"
        )

        /** Перевод стадий oneclick.sh для UI */
        private val STAGE_RU = mapOf(
            "阶段1" to "ст.1 эксплойт cheese",
            "阶段2" to "ст.2 рут-канал",
            "阶段2.5" to "ст.2.5 миграция кита",
            "阶段3" to "ст.3 KASLR slide",
            "阶段4" to "ст.4 менеджер",
            "阶段5" to "ст.5 загрузка модуля",
            "阶段6" to "ст.6 активация",
            "阶段6.5" to "ст.6.5 регистрация менеджера",
            "阶段7" to "ст.7 верификация",
            "阶段8" to "ст.8 очистка"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val permListener = Shizuku.OnRequestPermissionResultListener { req, res ->
        if (req == REQUEST_SHIZUKU && res == PackageManager.PERMISSION_GRANTED && !running.get()) {
            toast("Разрешение Shizuku получено — запускаю")
            startFlow()
        }
    }

    private lateinit var statusView: TextView
    private lateinit var progressView: TextView
    private lateinit var timeView: TextView
    private lateinit var button: Button
    private val logTail = ArrayDeque<String>(24)

    // ---------------- UI ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 16).toInt()
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
        }
        statusView = TextView(this).apply { textSize = 20f; setPadding(0, 0, 0, pad / 2) }
        timeView = TextView(this).apply { textSize = 14f; setTextColor(Color.GRAY); setPadding(0, 0, 0, pad) }
        progressView = TextView(this).apply {
            textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
        }
        button = Button(this).apply { text = "ПОЛУЧИТЬ РУТ (ONECLICK)" }
        root.addView(statusView); root.addView(timeView); root.addView(button); root.addView(progressView)
        scroll.addView(root); setContentView(scroll)

        button.setOnClickListener { startFlow() }
        Shizuku.addRequestPermissionResultListener(permListener)
        status("Проверяю статус…")
        thread(name = "initial-check") { autoStatus() }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun status(s: String) = main.post {
        statusView.text = s
        statusView.setTextColor(
            when {
                s.contains("✓") -> Color.rgb(0x1B, 0x87, 0x3A)
                s.contains("✗") -> Color.rgb(0xC6, 0x28, 0x28)
                else -> Color.DKGRAY
            })
    }

    private fun progress(line: String) {
        val ru = STAGE_RU.entries.fold(line) { acc, (zh, ru2) -> acc.replace(zh, ru2) }
        synchronized(logTail) {
            if (logTail.isEmpty() || logTail.last() != ru) logTail.addLast(ru)
            while (logTail.size > 24) logTail.removeFirst()
            val text = logTail.joinToString("\n")
            main.post { progressView.text = text }
        }
    }

    private fun toast(s: String) = main.post {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun time(s: String) = main.post { timeView.text = s }

    // ---------------- Транспорт ----------------

    private fun shizukuReady(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    private fun shizukuPermitted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /**
     * Выполнить короткую команду (НЕ эксплойт — таймаут разрешает destroy).
     * Возвращает (rc, stdout+stderr) или null при недоступности транспорта.
     * stdin пишется только через Shizuku (binder-process).
     */
    private fun exec(cmd: String, stdin: ByteArray? = null, timeoutSec: Int = 90): Pair<Int, String>? {
        if (shizukuReady() && shizukuPermitted()) {
            return try {
                val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, T)
                val out = StringBuilder()
                val r1 = thread(name = "exec-out") {
                    try {
                        p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.append(it).append('\n') } }
                    } catch (_: Throwable) {}
                }
                val r2 = thread(name = "exec-err") {
                    try {
                        p.errorStream.bufferedReader().forEachLine { synchronized(out) { out.append(it).append('\n') } }
                    } catch (_: Throwable) {}
                }
                // НИКОГДА не открываем outputStream без данных: binder-канал stdin
                // ненадёжен (инцидент 01:28 — зависание записи/исключение на пустом open).
                if (stdin != null) p.outputStream.use { os -> os.write(stdin) }
                val done = p.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                if (!done) {
                    runCatching { p.destroy() }
                    124 to synchronized(out) { out.toString() }
                } else {
                    r1.join(3000); r2.join(3000)
                    p.exitValue() to synchronized(out) { out.toString() }
                }
            } catch (_: Throwable) { null }
        }
        if (stdin != null) return null // AdbClient не поддерживает stdin
        return try {
            AdbClient(HOST, PORT, key).use { c ->
                val sb = StringBuilder()
                c.shellCommand(cmd) { chunk -> sb.append(chunk) }
                0 to sb.toString()
            }
        } catch (_: Throwable) { null }
    }

    private val key by lazy {
        AdbKey.appContext = applicationContext
        AdbKey(object : AdbKeyStore {
            override fun get(): ByteArray? = null
            override fun put(bytes: ByteArray) {}
        }, "ownroot")
    }

    // ---------------- Пайплайн ----------------

    private fun startFlow() {
        if (!running.compareAndSet(false, true)) return
        button.isEnabled = false
        thread(name = "root-flow") {
            try { flow() } finally {
                running.set(false)
                main.post { button.isEnabled = true }
            }
        }
    }

    private fun flow() {
        val t0 = SystemClock.elapsedRealtime()
        synchronized(logTail) { logTail.clear() }
        main.post { progressView.text = "" }
        status("Запуск…")

        // --- рут уже есть? ---
        if (checkRooted()) {
            status("✓ Рут уже активен (KernelSU)")
            time("запуск не требуется")
            healAdbKeys()
            return
        }

        // --- выбор транспорта ---
        val viaShizuku: Boolean
        if (shizukuReady()) {
            if (!shizukuPermitted()) {
                status("Нужно разрешение Shizuku")
                time("диалог открыт — разреши, запуск продолжится сам")
                try { Shizuku.requestPermission(REQUEST_SHIZUKU) } catch (_: Throwable) {}
                return
            }
            viaShizuku = true
        } else {
            viaShizuku = false
            val probe = exec("ls $T/oneclick.sh >/dev/null 2>&1 && echo K")
            if (probe?.second?.contains("K") != true) {
                status("✗ Нет канала: Shizuku не запущен, adb 5555 недоступен")
                time("подними Shizuku (Start Shizuku) и нажми снова")
                return
            }
        }

        // --- развёртка кита (только через Shizuku, ~42МБ) ---
        if (viaShizuku) {
            status("Развёртка кита (15 файлов)…")
            val dep = deployKit()
            if (dep != null) {
                status("✗ Развёртка не удалась: $dep")
                time("см. лог выше")
                return
            }
        }

        // --- oneclick: запуск, НИКОГДА не убиваем ---
        status("Эксплойт: cheese → модуль (до 3 попыток × 300с)")
        time("старт " + java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()))
        val exit = runOneclick(viaShizuku)
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000

        // --- верификация ---
        if (checkRooted()) {
            status("✓ Рут активен (KernelSU) · за ${secs}с")
            time("канал: ${if (viaShizuku) "Shizuku" else "adb"} · oneclick exit=$exit")
            healAdbKeys()
        } else {
            status("✗ Рут не встал (exit=$exit, ${secs}с)")
            time("лог выше; попытки вероятностные — нажми снова")
        }
    }

    /** Развёртка кита из assets с md5-контролем. null = успех, иначе описание сбоя. */
    private fun deployKit(): String? {
        val files = (assets.list("kit") ?: emptyArray()).toSet()
        if (files.size < KIT_MD5.size) return "assets/kit неполон (${files.size}/${KIT_MD5.size})"
        for (name in KIT_MD5.keys) if (name !in files) return "нет в assets: $name"

        // Развёртка одним unzip из собственного APK — без stdin-стримов через binder
        // (стрим-канал IRemoteProcess ненадёжен: зависание записи 2.3МБ, см. инцидент 01:28).
        val apk = applicationInfo.sourceDir
        val rc = exec(
            "cd $T && rm -f ${KIT_MD5.keys.joinToString(" ")} && unzip -j -o '$apk' 'assets/kit/*' -d $T 2>&1 | tail -1",
            timeoutSec = 300
        )
        if (rc == null || rc.first != 0) return "unzip не выполнен (rc=${rc?.first} ${rc?.second?.take(120)})"
        progress("распакован кит из APK (${KIT_MD5.size} файлов)")

        exec("chmod 755 " + EXECUTABLES.joinToString(" ") { "$T/$it" } + " 2>/dev/null; echo OK")
        val got = exec("cd $T && md5sum ${KIT_MD5.keys.joinToString(" ")} 2>&1")
            ?: return "md5-проверка недоступна"
        val gotMap = got.second.lineSequence()
            .mapNotNull { l -> l.split("  ", limit = 2).takeIf { it.size == 2 } }
            .associate { (h, f) -> f.trim() to h.trim() }
        for ((name, md5) in KIT_MD5) {
            val actual = gotMap[name]
            if (actual != md5) return "md5 не сходится: $name ($actual ≠ $md5)"
        }
        progress("кит развёрнут, md5 ✓ (15 файлов)")
        return null
    }

    /** Запуск oneclick.sh с потоковым выводом. Возвращает exit-код (или -1). */
    private fun runOneclick(viaShizuku: Boolean): Int {
        if (viaShizuku) {
            return try {
                val p = Shizuku.newProcess(arrayOf("sh", "$T/oneclick.sh"), null, T)
                thread(name = "oneclick-out") {
                    try { p.inputStream.bufferedReader().forEachLine { progress(it) } } catch (_: Throwable) {}
                }
                thread(name = "oneclick-err") {
                    try { p.errorStream.bufferedReader().forEachLine { progress(it) } } catch (_: Throwable) {}
                }
                p.waitFor() // без таймаута и destroy: бюджет сам ограничен внутри скрипта
                val rc = p.exitValue()
                progress("[oneclick] завершён, exit=$rc")
                rc
            } catch (e: Throwable) {
                progress("ошибка запуска oneclick: ${e.message}")
                -1
            }
        }
        // adb-fallback: поток через AdbClient (живой вывод в progress)
        return try {
            AdbClient(HOST, PORT, key).use { c ->
                val sb = StringBuilder()
                c.shellCommand("sh $T/oneclick.sh 2>&1") { chunk ->
                    val s = String(chunk, Charsets.UTF_8)
                    synchronized(sb) { sb.append(s) }
                    s.lineSequence().filter { it.isNotBlank() }.forEach { progress(it) }
                }
                val tail = synchronized(sb) { sb.toString() }
                when {
                    tail.contains("ALL-DONE") -> 0
                    tail.contains("не смог") || tail.contains("失败") -> 1
                    else -> 0 // adb-канал не даёт rc; статус решает верификация
                }
            }
        } catch (e: Throwable) {
            progress("ошибка adb-запуска: ${e.message}")
            -1
        }
    }

    /** Широкая проверка рута: /sys/module/kernelsu (GKI) ИЛИ /proc/modules. */
    private fun checkRooted(): Boolean {
        val r = exec("[ -d /sys/module/kernelsu ] && echo R1; grep -q '^kernelsu' /proc/modules 2>/dev/null && echo R2; echo END")
        val out = r?.second ?: return false
        return out.contains("R1") || out.contains("R2")
    }

    /** Самолечение: добавить паб-ключи в /data/misc/adb/adb_keys через свежий рут. */
    private fun healAdbKeys() {
        val sb = StringBuilder()
        for (pub in HEAL_PUBKEYS) {
            val first = pub.substringBefore(' ')
            sb.append("grep -qF '$first' /data/misc/adb/adb_keys 2>/dev/null || ")
            sb.append("printf '%s\\n' '$pub' >> /data/misc/adb/adb_keys; ")
        }
        val heal = sb.toString()
        val cmds = listOf(
            "/system/bin/su 0 sh -c \"$heal\"",  // su модуля KernelSU (после очистки stage 8)
            "$T/su 0 sh -c \"$heal\""              // su кита (если ещё жив)
        )
        for (c in cmds) {
            val r = exec(c, timeoutSec = 25)
            if (r != null && r.first == 0) {
                progress("adb-ключи восстановлены (самолечение ✓)")
                return
            }
        }
        progress("⚠ самолечение adb_keys не выполнено (рут жив — не критично)")
    }

    /** Автостатус при открытии. */
    private fun autoStatus() {
        if (checkRooted()) {
            status("✓ Рут активен (KernelSU)")
            time("после ребута нажми кнопку для повторной установки")
        } else {
            status("Рут не активен")
            time("Shizuku: ${if (shizukuReady()) "жив ✓" else "не запущен"} · adb 5555: ${if (adbAlive()) "жив" else "нет"}")
        }
    }

    private fun adbAlive(): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(HOST, PORT), 3000); true
        }
    } catch (_: Throwable) { false }
}
