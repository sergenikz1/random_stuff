package org.scryertv.client.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.scryertv.client.BuildConfig
import org.scryertv.client.R

class UpdateActivity : FragmentActivity() {
    private lateinit var model: UpdateViewModel
    private lateinit var status: TextView
    private lateinit var download: Button
    private lateinit var check: Button
    private lateinit var cancel: Button
    private lateinit var progress: ProgressBar
    private var installWhenReady = false
    private var awaitingPermission = false
    private var launchingInstaller = false
    private var wasReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        model = ViewModelProvider(this)[UpdateViewModel::class.java]
        status = findViewById(R.id.update_status)
        download = findViewById(R.id.update_install)
        check = findViewById(R.id.update_check)
        cancel = findViewById(R.id.update_cancel)
        progress = findViewById(R.id.update_progress)
        findViewById<TextView>(R.id.update_version).text = getString(R.string.update_installed_version, BuildConfig.VERSION_NAME)
        installWhenReady = savedInstanceState?.getBoolean("install_when_ready") ?: false
        awaitingPermission = savedInstanceState?.getBoolean("awaiting_permission") ?: false
        @Suppress("DEPRECATION")
        (savedInstanceState?.getSerializable("update") as? UpdateMetadata)?.let { model.restore(it) }
        check.setOnClickListener { installWhenReady = false; awaitingPermission = false; model.check() }
        cancel.setOnClickListener { installWhenReady = false; model.cancel() }
        download.setOnClickListener {
            installWhenReady = true
            if (model.state.value.file != null) maybeInstall() else model.download()
        }
        if (!model.initialized) model.check()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { render(it); maybeInstall() }
            }
        }
    }

    private fun render(state: UpdateState) {
        status.text = state.message
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.progress == null
        progress.progress = state.progress ?: 0
        check.isEnabled = !state.busy
        cancel.visibility = if (state.busy) View.VISIBLE else View.GONE
        download.visibility = if (state.update != null && state.update.minSdk <= Build.VERSION.SDK_INT) View.VISIBLE else View.GONE
        download.isEnabled = !state.busy && !launchingInstaller
        download.setText(if (state.file == null) R.string.update_download_install else R.string.update_install)
        val ready = state.update != null && !state.busy
        if (ready && !wasReady) download.requestFocus()
        if (state.update == null && !state.busy) check.requestFocus()
        wasReady = ready
    }

    override fun onResume() {
        super.onResume()
        if (awaitingPermission && canInstall()) { awaitingPermission = false; installWhenReady = true }
        if (::model.isInitialized) maybeInstall()
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun maybeInstall() {
        if (!installWhenReady || launchingInstaller || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val state = model.state.value
        val update = state.update ?: return
        val file = state.file ?: return
        installWhenReady = false
        if (!canInstall()) {
            awaitingPermission = true
            status.setText(R.string.update_allow_install)
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (_: ActivityNotFoundException) { awaitingPermission = false; status.setText(R.string.update_no_settings) }
            }
            return
        }
        launchingInstaller = true; download.isEnabled = false
        lifecycleScope.launch {
            try {
                status.setText(R.string.update_verifying)
                UpdateRepository.verify(this@UpdateActivity, update, file)
                val uri = FileProvider.getUriForFile(this@UpdateActivity, "$packageName.updates", file)
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("Scryer TV update", uri) }
                startActivity(intent)
                status.setText(R.string.update_confirm_install)
            } catch (e: CancellationException) { throw e
            } catch (_: ActivityNotFoundException) { status.setText(R.string.update_no_installer)
            } catch (e: Exception) { status.text = e.message ?: getString(R.string.update_failed)
            } finally { launchingInstaller = false; download.isEnabled = true }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("install_when_ready", installWhenReady)
        outState.putBoolean("awaiting_permission", awaitingPermission)
        if (model.state.value.file != null) outState.putSerializable("update", model.state.value.update)
        super.onSaveInstanceState(outState)
    }
}
