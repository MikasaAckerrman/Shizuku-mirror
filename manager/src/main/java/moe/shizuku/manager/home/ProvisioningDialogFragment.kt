package moe.shizuku.manager.home

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import androidx.fragment.app.DialogFragment
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.adb.TrustSeeder
import moe.shizuku.manager.starter.StarterActivity

/**
 * [provisioning] First-time setup funnel. Shown when a start attempt fails
 * with a reason the user can fix themselves:
 *
 * - Unauthorized ("key not trusted") -> seed the key via root (one tap in a
 *   temporary-root window: no Wi-Fi, no cable) or pair via wireless debugging.
 * - NoListener ("nothing to connect to") -> the root path also fixes the port
 *   (persist.adb.tcp.port + adbd restart); otherwise Wi-Fi pairing is the
 *   only no-root option (OS-imposed constraint).
 *
 * Both paths finish with an automatic silent start retry.
 */
class ProvisioningDialogFragment : DialogFragment() {

    companion object {
        const val REASON_NO_LISTENER = "no_listener"
        const val REASON_UNAUTHORIZED = "unauthorized"

        const val TAG = "provisioning"

        fun show(fragmentManager: FragmentManager, reason: String) {
            // [dedupe] never stack the funnel: one instance at a time
            if (fragmentManager.isStateSaved) return
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            val fragment = ProvisioningDialogFragment()
            fragment.arguments = Bundle().apply { putString("reason", reason) }
            fragment.show(fragmentManager, TAG)
        }
    }

    private var alertDialog: androidx.appcompat.app.AlertDialog? = null

    // [api33] pm clear wipes POST_NOTIFICATIONS; the pairing flow is driven by
    // a notification, so the grant must be re-requested. Denied -> silent
    // fallback to the in-app dialog (its auto-restart is free anyway).
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPairingService() else fallbackToInAppPairing()
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.provisioning_title)
            .setMessage(R.string.provisioning_message)
            .setPositiveButton(R.string.provisioning_seed_root, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setNeutralButton(R.string.provisioning_pair_wifi, null)
        }
        val dialog = builder.create()
        alertDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                runRootProvisioning()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                // [wifi-flow] Pairing is driven by a NOTIFICATION (upstream UX):
                // the service discovers the pairing service via mDNS and the
                // 6-digit code is entered in the notification itself.
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return@setOnClickListener
                }
                startPairingService()
                // [ux] the redirect belongs HERE: the user needs Developer
                // Options to flip the wireless-debugging toggle and open the
                // pairing-code dialog the notification is waiting for.
                try {
                    val devSettings = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                    }
                    startActivity(devSettings)
                } catch (_: Exception) {
                }
            }
        }
        return dialog
    }

    private fun startPairingService() {
        // [dedupe] the service itself is idempotent (startSearch guard)
        AdbPairingService.autoRestartOnSuccess = true
        ContextCompat.startForegroundService(requireContext(), AdbPairingService.startIntent(requireContext()))
        dismissAllowingStateLoss()
    }

    private fun fallbackToInAppPairing() {
        // Free auto-restart from the activity context (no background-start ban)
        AdbPairDialogFragment.show(parentFragmentManager, autoRetry = true)
        dismissAllowingStateLoss()
    }

    private fun updateMessage(text: String) {
        alertDialog?.setMessage(text)
    }

    private fun runRootProvisioning() {
        val activity = activity ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            updateMessage(getString(R.string.provisioning_working))
            // [root-grant] libsu blocks here until the user answers the root
            // manager prompt. A missed prompt = no root: tell the user plainly
            // and let them re-tap instead of failing silently.
            if (!TrustSeeder.isRootAvailable()) {
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.provisioning_root_unavailable, Toast.LENGTH_LONG).show()
                    updateMessage(getString(R.string.provisioning_root_retry_hint))
                }
                return@launch
            }

            val seedOk = TrustSeeder.seed { line ->
                activity.runOnUiThread { updateMessage(getString(R.string.provisioning_working) + "\n" + line) }
            }
            updateMessage(getString(R.string.provisioning_working) + "\n" + "fixing TCP port 5555 + restarting adbd\n")
            TrustSeeder.fixTcpPortViaRoot()

            // [retry] adbd restart latency: the listener is not bound
            // instantly - poll the start instead of a single shot.
            var last: StarterEngine.Result? = null
            repeat(4) { attempt ->
                activity.runOnUiThread {
                    updateMessage(getString(R.string.provisioning_working) + "\n" + "start attempt ${attempt + 1}/4\n")
                }
                last = StarterEngine.adbStart(5555) { line ->
                    activity.runOnUiThread { updateMessage("\n" + line) }
                }
                if (last is StarterEngine.Result.Started || last is StarterEngine.Result.AlreadyRunning) return@repeat
                Thread.sleep(2000)
            }

            activity.runOnUiThread {
                when (val r = last) {
                    is StarterEngine.Result.Started, is StarterEngine.Result.AlreadyRunning -> {
                        Toast.makeText(activity, "Shizuku started", Toast.LENGTH_SHORT).show()
                        dismissAllowingStateLoss()
                    }
                    else -> updateMessage("\n" + (r as? StarterEngine.Result.Failed)?.detail
                        ?: getString(R.string.provisioning_seed_fail))
                }
            }
        }
    }
}
