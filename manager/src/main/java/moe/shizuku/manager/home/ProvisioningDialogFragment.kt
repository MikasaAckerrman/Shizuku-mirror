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
        // [ux] Jump straight to Developer Options once per dialog instance:
        // the wireless-debugging toggle and the pairing-code dialog live there.
        if (savedInstanceState == null) {
            try {
                val devSettings = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                }
                requireContext().startActivity(devSettings)
            } catch (_: Exception) {
            }
        }
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
            if (!TrustSeeder.isRootAvailable()) {
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.provisioning_root_unavailable, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val seedOk = TrustSeeder.seed { line ->
                activity.runOnUiThread { updateMessage(getString(R.string.provisioning_working) + "\n" + line) }
            }

            // Listener: persist + runtime port, then restart adbd. The restart
            // also makes adbd re-read adb_keys with the freshly seeded key.
            TrustSeeder.fixTcpPortViaRoot()
            Thread.sleep(1500)

            activity.runOnUiThread {
                if (seedOk) {
                    val intent = Intent(activity, StarterActivity::class.java).apply {
                        putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                        putExtra(StarterActivity.EXTRA_SILENT, true)
                        putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                        putExtra(StarterActivity.EXTRA_PORT, 5555)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    dismissAllowingStateLoss()
                } else {
                    updateMessage(getString(R.string.provisioning_seed_fail))
                }
            }
        }
    }
}
