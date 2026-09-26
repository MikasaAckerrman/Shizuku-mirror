package moe.shizuku.manager.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeTerminalBinding
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Карточка «Получить рут (KernelSU)» на месте карточки терминала.
 *
 * Клик запускает /data/local/tmp/restore.sh. Основной путь — собственный
 * AdbClient приложения (127.0.0.1:5555, тот же механизм, что silent start):
 * процесс исполняется adbd — раннее поддерево задач → короткий walk
 * (~24 RPC, ~20 с против ~410 RPC через newProcess сервера).
 *
 * Физика эксплойта (доказано 26.09): GPU надёжен для эксплойта один раз
 * за загрузку — после любого прогона состояние KGSL клинчует повторные
 * сканы (это же ломает системные анимации). Поэтому гвард
 * «один прогон за бут» честно требует ребут вместо клинча.
 *
 * Все binder/adb-вызовы асинхронны — карточка ничего не делает при
 * загрузке приложения (требование: не влиять на скорость старта Shizuku).
 */
class TerminalViewHolder(private val binding: HomeTerminalBinding, private val root: View) :
    BaseViewHolder<ServiceStatus>(root),
    View.OnClickListener {

    companion object {
        private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "home-root-card").apply { isDaemon = true }
        }
        private val MAIN: Handler = Handler(Looper.getMainLooper())

        private val CMD_CHECK =
            arrayOf("sh", "-c", "grep -q '^kernelsu' /proc/modules")
        private val CMD_START =
            arrayOf("sh", "/data/local/tmp/restore.sh")

        /** Бюджет чтения adb на весь прогон: root.sh сам ограничивает себя
         *  600-секундным watchdog — сессия переживает любой сценарий,
         *  а дерево процессов на устройстве не убивается таймаутом клиента
         *  (26.09: 30-с таймаут убивал живой скан → клинч GPU). */
        private const val ADB_READ_BUDGET_MS = 600_000
        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_PORT = 5555
        private const val PROGRESS_PERIOD_MS = 700L

        private const val ROOT_ACTIVE = 0
        private const val ROOT_INACTIVE = 1
        private const val SERVICE_ERROR = -1
        private const val PREFS = "root_button"
        private const val PREF_LAST_RUN = "last_run_elapsed"
        private const val PREF_BOOT_ID = "boot_id"

        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeTerminalBinding.inflate(inflater, outer.root, true)
            TerminalViewHolder(inner, outer.root)
        }
    }

    init {
        root.setOnClickListener(this)
    }

    private val running = AtomicBoolean(false)
    private var defaultSummaryColor: Int? = null
    private var lastProgressPostMs = 0L

    private inline val summary get() = binding.text2

    override fun onBind() {
        val context = itemView.context
        if (defaultSummaryColor == null) {
            defaultSummaryColor = summary.currentTextColor
        }
        if (!data.isRunning) {
            root.isEnabled = false
            summary.text =
                context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
            defaultSummaryColor?.let { summary.setTextColor(it) }
        } else {
            root.isEnabled = true
            summary.text = context.getString(R.string.home_terminal_description)
            refreshStatus()
        }
    }

    override fun onClick(v: View) {
        val d = data ?: return
        if (!d.isRunning) return
        if (!running.compareAndSet(false, true)) return

        val context = itemView.context
        // Гвард «один прогон за бут»: boot_id (/proc/sys/kernel/random/boot_id) —
        // UUID меняется на каждый ребут. НЕ elapsedRealtime: счётчик обнуляется
        // при ребуте и через N минут снова превосходит старое значение —
        // межбутовое сравнение ложно (баг r1154: ложный «ребутни» после ребута).
        val bootId = runCatching {
            java.io.File("/proc/sys/kernel/random/boot_id").bufferedReader().use { it.readLine()?.trim() }
        }.getOrNull()
        val storedBootId = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_BOOT_ID, null)
        }.getOrNull()
        if (bootId != null && bootId == storedBootId) {
            // Прогон на этой загрузке уже был. Если рут жив — показать статус,
            // нет — честно требовать ребут вместо гарантированного клинча.
            EXECUTOR.execute {
                val status = queryRootStatus()
                MAIN.post {
                    if (status == ROOT_ACTIVE) {
                        applyStatus(status)
                    } else {
                        summary.text = context.getString(R.string.home_root_cooldown)
                        summary.setTextColor(context.getColor(R.color.home_root_status_error))
                    }
                }
            }
            return
        }
        // GPU оседает ~90с после бута: ранний тап = скан по переходному
        // состоянию KGSL → клинч (26.09: тап на uptime 48с — 6 мин ретраев).
        val now = SystemClock.elapsedRealtime()
        if (now < 90_000L) {
            summary.text = context.getString(R.string.home_root_boot_wait)
            summary.setTextColor(context.getColor(R.color.home_root_status_error))
            return
        }
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_BOOT_ID, bootId)
                .putLong(PREF_LAST_RUN, now)
                .apply()
        }

        // v3 (r1156): экран не гаснет весь прогон — погасший экран = GPU в idle =
        // латентность RPC взлетает (замер 26.09: 1.1с/батч @231МГц vs 0.4с @578)
        itemView.keepScreenOn = true
        // v3 (r1157): живой прогресс — поллинг хвоста root_run.log вторым adb-сеансом
        // (v1.3 restore.sh перенаправляет весь вывод в файл — стрим кнопки пуст).
        // Поллер: daemon-поток, 1 раз в 2с, завершается с running=false.
        val poller = Thread {
            while (running.get()) {
                val line = pollLogTail()
                if (!line.isNullOrBlank()) {
                    MAIN.post {
                        val d = data ?: return@post
                        if (d.isRunning && running.get()) summary.text = line.take(120)
                    }
                }
                try { Thread.sleep(2_000) } catch (e: InterruptedException) { break }
            }
        }
        poller.isDaemon = true
        poller.start()
        summary.text = context.getString(R.string.home_root_starting)
        EXECUTOR.execute {
            var tail: String? = null
            var serverExit = 0
            try {
                tail = startViaAdb()
            } catch (e: Throwable) {
                // adb-канал не ответил — медленный, но рабочий путь через сервер.
                MAIN.post {
                    if (data?.isRunning == true) {
                        summary.text = itemView.context.getString(R.string.home_root_starting_slow)
                    }
                }
                try {
                    serverExit = startViaServer()
                } catch (ignored: Throwable) {
                    serverExit = -100
                }
            }
            val status = queryRootStatus()
            MAIN.post {
                running.set(false)
                itemView.keepScreenOn = false   // v3: гашение экрана только по системному таймауту
                applyStatus(status)
                if (status != ROOT_ACTIVE) {
                    when {
                        !tail.isNullOrBlank() -> summary.text = tail.take(120)
                        serverExit > 0 -> applyFailure(serverExit)
                    }
                }
            }
        }
    }

    /**
     * Быстрый путь: команда исполняется adbd (поддерево adbd — короткий
     * walk). Сессия adb живёт весь прогон (бюджет [ADB_READ_BUDGET_MS]),
     * вывод стримится в карточку живьём. Возвращает последнюю непустую
     * строку лога — причину, если прогон провалился.
     */
    private fun startViaAdb(): String? {
        val output = StringBuilder()
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        AdbClient(ADB_HOST, ADB_PORT, key, ADB_READ_BUDGET_MS).use { client ->
            client.connect()
            client.shellCommand(CMD_START.joinToString(" ")) { chunk ->
                output.append(String(chunk))
                showProgress(output)
            }
        }
        return output.toString().lineSequence().lastOrNull { it.isNotBlank() }
    }

    /** Живой прогресс: последняя строка лога через короткий adb-сеанс (~0.5с). */
    private fun pollLogTail(): String? = try {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        AdbClient(ADB_HOST, ADB_PORT, key, 8_000).use { client ->
            client.connect()
            val sb = StringBuilder()
            client.shellCommand("tail -1 /data/local/tmp/root_run.log 2>/dev/null") { chunk ->
                sb.append(String(chunk))
            }
            sb.toString().trim().ifBlank { null }
        }
    } catch (e: Throwable) {
        null
    }

    /** Живой прогресс: последняя строка вывода → summary (троттлинг). */
    private fun showProgress(output: StringBuilder) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressPostMs < PROGRESS_PERIOD_MS) return
        lastProgressPostMs = now
        val line = output.toString().lineSequence().lastOrNull { it.isNotBlank() } ?: return
        MAIN.post {
            val d = data ?: return@post
            if (d.isRunning) summary.text = line.take(120)
        }
    }

    private fun refreshStatus() {
        EXECUTOR.execute {
            val status = queryRootStatus()
            MAIN.post { applyStatus(status) }
        }
    }

    private fun applyStatus(status: Int) {
        val d = data ?: return
        if (!d.isRunning) return
        val context = itemView.context
        when (status) {
            ROOT_ACTIVE -> {
                summary.text = context.getString(R.string.home_root_active)
                summary.setTextColor(context.getColor(R.color.home_root_status_ok))
            }
            ROOT_INACTIVE -> {
                val upHours = (SystemClock.elapsedRealtime() / 3_600_000L).toInt()
                summary.text = if (upHours >= 3) {
                    context.getString(R.string.home_root_inactive_old, upHours)
                } else {
                    context.getString(R.string.home_root_inactive)
                }
                summary.setTextColor(context.getColor(R.color.home_root_status_off))
            }
            else -> {
                summary.text = context.getString(R.string.home_root_error)
                summary.setTextColor(context.getColor(R.color.home_root_status_error))
            }
        }
    }

    private fun applyFailure(exit: Int) {
        val d = data ?: return
        if (!d.isRunning) return
        val context = itemView.context
        summary.text = context.getString(R.string.home_root_fail, exit)
        summary.setTextColor(context.getColor(R.color.home_root_status_error))
    }

    private fun queryRootStatus(): Int {
        val binder = Shizuku.getBinder() ?: return SERVICE_ERROR
        if (!binder.pingBinder()) return SERVICE_ERROR
        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(CMD_CHECK, null, null)
            if (process.waitFor() == 0) ROOT_ACTIVE else ROOT_INACTIVE
        } catch (e: Throwable) {
            SERVICE_ERROR
        }
    }

    /** Запасной путь: через сервер Shizuku (медленный, до ~2 мин). */
    private fun startViaServer(): Int {
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku service is not running")
        if (!binder.pingBinder()) throw IllegalStateException("Shizuku binder is dead")
        val service = IShizukuService.Stub.asInterface(binder)
        return service.newProcess(CMD_START, null, null).waitFor()
    }
}
