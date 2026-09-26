package moe.shizuku.manager.home

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
 * Карточка «Получить рут (KernelSU)» на месте бывшей карточки терминала.
 *
 * Запуск идёт в два пути:
 *  1. Быстрый (~20 с): restore.sh исполняет adbd через собственный [AdbClient]
 *     приложения — тот же механизм, что и silent start (StarterService.startAdb).
 *     adbd — ранний потомок init, walk в cheese короткий (~24 RPC против
 *     ~410 RPC через newProcess сервера). Живой вывод лога идёт в summary.
 *  2. Запасной (~2 мин): IShizukuService.newProcess — если adb-канал недоступен.
 *
 * Статус: зелёный — kernelsu в /proc/modules; красный — выключен; оранжевый —
 * сервис недоступен. При uptime ≥ 3 ч подсказка «сначала перезагрузи» (GPU-
 * состояние уезжает, скан не находит паттерн — 26.09).
 *
 * Все сетевые/binder-операции — на одном ленивом daemon-потоке; карточка не
 * выполняет работы при старте приложения и не влияет на скорость запуска.
 */
class TerminalViewHolder(private val binding: HomeTerminalBinding, private val root: View) :
    BaseViewHolder<ServiceStatus>(root),
    View.OnClickListener {

    companion object {
        private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "home-root-card").apply { isDaemon = true }
        }
        private val MAIN: Handler = Handler(Looper.getMainLooper())

        private val CMD_CHECK = arrayOf("sh", "-c", "grep -q '^kernelsu' /proc/modules")
        private val CMD_START = arrayOf("sh", "/data/local/tmp/restore.sh")

        /** 127.0.0.1:5555 — локальный adbd, авторизован ключом приложения. */
        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_PORT = 5555

        /**
         * Бюджет чтения adb-канала: покрывает молчаливые фазы прогона (walk
         * ~15 с, 6×sleep(1)) и ограничивает зависший handshake сверху.
         */
        private const val ADB_READ_TIMEOUT_MS = 30_000

        private const val ROOT_ACTIVE = 0
        private const val ROOT_INACTIVE = 1
        private const val SERVICE_ERROR = -1

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
        summary.text = itemView.context.getString(R.string.home_root_starting)
        EXECUTOR.execute {
            var tail: String? = null      // последняя строка живого вывода (adb-путь)
            var serverExit = 0            // код возврата запасного пути (0 = не использовался)
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

    private fun refreshStatus() {
        EXECUTOR.execute {
            val status = queryRootStatus()
            MAIN.post { applyStatus(status) }
        }
    }

    /**
     * Быстрый путь: adb-клиент приложения (как silent start). Возвращает
     * последнюю непустую строку вывода — она же причина, если прогон провалился.
     *
     * Таймаут чтения НЕ считается ошибкой: прогон на устройстве, скорее всего,
     * продолжается (walk молчит ~15 с, при троттле дольше) — поллим модуль
     * 90 с и не перезапускаем, чтобы не устроить два конкурирующих cheese.
     */
    private fun startViaAdb(): String? {
        val output = StringBuilder()
        try {
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            AdbClient(ADB_HOST, ADB_PORT, key, ADB_READ_TIMEOUT_MS).use { client ->
                client.connect()
                client.shellCommand(CMD_START.joinToString(" ")) { chunk ->
                    output.append(String(chunk))
                    showProgress(output)
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            pollRootStatus(90_000)
        }
        return output.toString().lineSequence().lastOrNull { it.isNotBlank() }
    }

    /** Ожидание появления модуля (после таймаута чтения adb). */
    private fun pollRootStatus(timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (queryRootStatus() == ROOT_ACTIVE) return
            Thread.sleep(3_000)
        }
    }

    /** Живой прогресс: последняя строка лога → summary, троттлинг ~0.7 с. */
    private fun showProgress(output: StringBuilder) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressPostMs < 700) return
        lastProgressPostMs = now
        val line = output.toString().lineSequence().lastOrNull { it.isNotBlank() } ?: return
        MAIN.post {
            val d = data ?: return@post
            if (d.isRunning) summary.text = line.take(120)
        }
    }

    /** Запасной путь: newProcess через Shizuku-сервер (uid 2000, ~2 мин). */
    private fun startViaServer(): Int {
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku service is not running")
        if (!binder.pingBinder()) throw IllegalStateException("Shizuku binder is dead")
        val service = IShizukuService.Stub.asInterface(binder)
        return service.newProcess(CMD_START, null, null).waitFor()
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

    /**
     * Диагностика провала запасного пути: код возврата root.sh
     * (1=cheese/GPU, 2=insmod, 3=kbase, 5=файлы, 6=slide, 7=слот, 8=ядро).
     */
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
}
