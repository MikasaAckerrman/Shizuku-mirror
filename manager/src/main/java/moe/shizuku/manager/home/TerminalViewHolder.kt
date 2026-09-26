package moe.shizuku.manager.home

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
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
 * Клик запускает /data/local/tmp/restore.sh через Shizuku-сервер (uid 2000);
 * менеджер пропускается сервером без разрешений (см. ShizukuService.checkCallerPermission:
 * appId == managerAppId). Статус отражает модуль kernelsu в /proc/modules:
 * зелёный — рут активен, красный — выключен, оранжевый — сервис недоступен.
 *
 * Все binder-вызовы выполняются на одном фоновом daemon-потоке, создающемся
 * лениво при первой задаче: карточка не выполняет никакой работы при старте
 * приложения и не влияет на скорость запуска Shizuku.
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
            // exit: код возврата restore.sh/root.sh; -100 = процесс вообще не запустился.
            val exit = try {
                startRoot()
            } catch (e: Throwable) {
                -100
            }
            val status = queryRootStatus()
            MAIN.post {
                running.set(false)
                if (status == ROOT_ACTIVE) {
                    applyStatus(status)
                } else if (exit != 0 && exit != -100) {
                    applyFailure(exit)
                } else {
                    applyStatus(status)
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
                // 26.09: все провалы рута были при uptime > 3 ч (GPU-состояние уезжает,
                // скан не находит паттерн). Подсказываем ребут до запуска.
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
     * Диагностика провала: код возврата root.sh (1=cheese/GPU, 2=insmod,
     * 3=kbase, 5=файлы, 6=slide, 7=слот, 8=ядро) + универсальное лечение.
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

    private fun startRoot(): Int {
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku service is not running")
        if (!binder.pingBinder()) throw IllegalStateException("Shizuku binder is dead")
        val service = IShizukuService.Stub.asInterface(binder)
        return service.newProcess(CMD_START, null, null).waitFor()
    }
}
