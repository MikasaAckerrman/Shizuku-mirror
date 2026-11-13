package moe.shizuku.manager.starter

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.StarterActivityBinding
import moe.shizuku.manager.home.ProvisioningDialogFragment
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLProtocolException
import android.content.Context

private class NotRootedException : Exception()

class StarterActivity : AppBarActivity() {

    private val viewModel by viewModels {
        ViewModel(
            this,
            intent.getBooleanExtra(EXTRA_IS_ROOT, true),
            intent.getStringExtra(EXTRA_HOST),
            intent.getIntExtra(EXTRA_PORT, 0)
        )
    }

    private val silent by lazy { intent.getBooleanExtra(EXTRA_SILENT, false) }
    private var binding: StarterActivityBinding? = null
    private val successHandled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        if (silent) {
            setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        }
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)
        if (silent) {
            supportActionBar?.hide()
        }

        val providedOutput = intent.getStringExtra(EXTRA_OUTPUT)
        if (providedOutput != null) {
            // Started from StarterService on error: just display the output.
            binding = StarterActivityBinding.inflate(layoutInflater)
            setContentView(binding!!.root)
            binding?.text1?.text = providedOutput
            when (intent.getStringExtra(StarterService.EXTRA_FAILURE_REASON)) {
                StarterService.REASON_NO_LISTENER -> showProvisioning(ProvisioningDialogFragment.REASON_NO_LISTENER)
                StarterService.REASON_UNAUTHORIZED -> showProvisioning(ProvisioningDialogFragment.REASON_UNAUTHORIZED)
            }
            return
        }

        // Silent mode: empty view until an error forces us to show the terminal.
        if (silent) {
            setContentView(View(this))
        } else {
            binding = StarterActivityBinding.inflate(layoutInflater)
            setContentView(binding!!.root)
        }

        // Fallback watchdog: even if output parsing misses the success marker,
        // finish as soon as the binder responds.
        lifecycleScope.launch(Dispatchers.IO) {
            repeat(150) { // 150 x 100 ms = 15 s max (post-reboot systems can be slow)
                delay(100)
                if (isFinishing) return@launch
                if (viewModel.output.value?.status == Status.ERROR) return@launch
                if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                    withContext(Dispatchers.Main) {
                        viewModel.appendOutput("Shizuku successfully started")
                        finishWithSuccess(silent)
                    }
                    return@launch
                }
            }
        }

        viewModel.output.observe(this) {
            val output = it.data!!.trim()
            if (output.contains("info: shizuku_starter exit with 0")) {
                viewModel.appendOutput("Shizuku successfully started")
                finishWithSuccess(silent)
                return@observe
            }

            if (it.status == Status.ERROR) {
                if (silent && binding == null) {
                    binding = StarterActivityBinding.inflate(layoutInflater)
                    setContentView(binding!!.root)
                }

                var message = 0
                when (it.error) {
                    is NotRootedException -> {
                        message = R.string.start_with_root_failed
                    }
                    is ConnectException -> {
                        showProvisioning(ProvisioningDialogFragment.REASON_NO_LISTENER)
                    }
                    is SSLProtocolException -> {
                        showProvisioning(ProvisioningDialogFragment.REASON_UNAUTHORIZED)
                    }
                }

                if (message != 0) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            binding?.text1?.text = output
        }
    }

    private var provisioningShown = false

    private fun showProvisioning(reason: String) {
        if (provisioningShown || isFinishing) return
        provisioningShown = true
        ProvisioningDialogFragment.show(supportFragmentManager, reason)
    }

    private fun finishWithSuccess(showToast: Boolean) {
        if (successHandled.getAndSet(true)) return
        if (showToast) {
            Toast.makeText(this, "Shizuku started", Toast.LENGTH_SHORT).show()
        }
        if (!isFinishing) {
            finish()
        }
    }

    companion object {

        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_HOST = "$EXTRA.HOST"
        const val EXTRA_PORT = "$EXTRA.PORT"
        const val EXTRA_SILENT = "$EXTRA.SILENT"
        const val EXTRA_OUTPUT = "$EXTRA.OUTPUT"
    }
}

private class ViewModel(context: Context, root: Boolean, host: String?, port: Int) : androidx.lifecycle.ViewModel() {

    private val sb = StringBuilder()
    private val _output = MutableLiveData<Resource<StringBuilder>>()

    val output = _output as LiveData<Resource<StringBuilder>>

    init {
        try {
            if (root) {
                startRoot()
            } else {
                startAdb(host!!, port)
            }
        } catch (e: Throwable) {
            postResult(e)
        }
    }

    fun appendOutput(line: String) {
        sb.appendLine(line)
        postResult()
    }

    private fun postResult(throwable: Throwable? = null) {
        if (throwable == null)
            _output.postValue(Resource.success(sb))
        else
            _output.postValue(Resource.error(throwable, sb))
    }

    private fun startRoot() {
        sb.append("Starting with root...").append('\n').append('\n')
        postResult()

        viewModelScope.launch(Dispatchers.IO) {
            // [fix-12] Same guard as startAdb: never kill a healthy server.
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                sb.append('\n').append("Service is already running, nothing to do.")
                postResult()
                return@launch
            }

            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                sb.append('\n').append("Can't open root shell, try again...").append('\n')

                postResult()
                if (!Shell.getShell().isRoot) {
                    sb.append('\n').append("Still not :(").append('\n')
                    postResult(NotRootedException())
                    return@launch
                }
            }

            Shell.cmd(Starter.internalCommand).to(object : CallbackList<String?>() {
                override fun onAddElement(s: String?) {
                    sb.append(s).append('\n')
                    postResult()
                }
            }).submit {
                if (it.code != 0) {
                    sb.append('\n').append("Send this to developer may help solve the problem.")
                    postResult()
                }
            }
        }
    }

    private fun startAdb(host: String, port: Int) {
        val mode = if (port == 5555) "ADB" else "wireless adb"
        sb.append("Starting with $mode in port $port...").append('\n').append('\n')
        postResult()

        viewModelScope.launch(Dispatchers.IO) {
            // Engine owns the ping guard, fast path, discovery, zombie
            // fallback and verify. This activity just renders the output.
            when (val result = StarterEngine.adbStart(port) { line -> appendOutput(line) }) {
                is StarterEngine.Result.AlreadyRunning -> {
                    appendOutput("Shizuku successfully started")
                }
                is StarterEngine.Result.Started -> {
                    appendOutput("Shizuku successfully started")
                }
                is StarterEngine.Result.Failed -> {
                    sb.append('\n').append(describe(result))
                    postResult(failureToThrowable(result))
                }
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
}
