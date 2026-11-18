package com.voicereader.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.voicereader.BuildConfig
import com.voicereader.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Ready(val apk: File, val version: String) : UpdateState
    data class Failed(val message: Int) : UpdateState
}

/** Запускается только кнопкой. Книги, настройки и статистика в сеть не передаются. */
object AppUpdater {
    private const val RELEASES = "https://api.github.com/repos/sergenikz1/random_stuff/releases?per_page=100"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null

    private class UpdateFailure(val messageId: Int) : Exception()

    fun check(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = UpdateState.Checking
            try {
                val ready = withContext(Dispatchers.IO) { findAndDownload(app) }
                _state.value = ready ?: UpdateState.Current
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed((e as? UpdateFailure)?.messageId ?: R.string.update_network_error)
            }
        }
    }

    fun installationFailed() { _state.value = UpdateState.Failed(R.string.update_install_error) }

    fun installerIntent(context: Context, apk: File): Intent {
        require(apk.isFile) { "Downloaded APK is no longer available" }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.updates", apk),
                "application/vnd.android.package-archive"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun connect(url: String): HttpURLConnection {
        require(URL(url).protocol == "https")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Chitalka/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    private fun findAndDownload(context: Context): UpdateState.Ready? {
        val installed = ReleaseVersion.parse(BuildConfig.VERSION_NAME) ?: error("Invalid application version")
        val connection = connect(RELEASES)
        val releases = try {
            if (connection.responseCode != 200) throw UpdateFailure(R.string.update_network_error)
            connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
        } finally { connection.disconnect() }
        val catalog = (0 until releases.length()).map { i ->
            val release = releases.getJSONObject(i)
            val releaseAssets = release.optJSONArray("assets") ?: JSONArray()
            AppRelease(release.optString("tag_name"), release.optBoolean("draft"), release.optBoolean("prerelease"),
                (0 until releaseAssets.length()).map { index ->
                    val asset = releaseAssets.getJSONObject(index)
                    ReleaseAsset(asset.optString("name"), asset.optString("browser_download_url"), asset.optLong("size"),
                        asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"))
                })
        }
        // Максимальная версия Читалки, затем первая подходящая архитектура телефона.
        val candidate = ReleaseCatalog.select(catalog, installed, Build.SUPPORTED_ABIS.toList())
        if (candidate == null) {
            if (ReleaseCatalog.newer(catalog, installed).isNotEmpty()) {
                throw UpdateFailure(R.string.update_no_apk)
            }
            return null
        }
        val asset = candidate.asset
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val part = File(directory, "update.part")
        val apk = File(directory, "update.apk")
        try {
            _state.value = UpdateState.Downloading(0f)
            val download = connect(asset.url)
            try {
                if (download.responseCode != 200) throw UpdateFailure(R.string.update_network_error)
                val digest = MessageDigest.getInstance("SHA-256")
                download.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var bytes = 0L
                        var reported = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (asset.bytes <= 0 || bytes > asset.bytes) throw UpdateFailure(R.string.update_invalid_apk)
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val percent = (bytes * 100 / asset.bytes).toInt()
                            if (percent != reported) {
                                reported = percent
                                _state.value = UpdateState.Downloading(percent / 100f)
                            }
                        }
                        if (bytes != asset.bytes) throw UpdateFailure(R.string.update_invalid_apk)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (asset.digest != null && !hash.equals(asset.digest, ignoreCase = true))
                    throw UpdateFailure(R.string.update_invalid_apk)
            } finally { download.disconnect() }
            if (!part.renameTo(apk)) throw UpdateFailure(R.string.update_invalid_apk)
            validateApk(context, apk, candidate.version)
            return UpdateState.Ready(apk, candidate.name)
        } catch (e: Exception) {
            apk.delete()
            throw e
        } finally { part.delete() }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(context: Context, apk: File, expectedVersion: ReleaseVersion) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw UpdateFailure(R.string.update_invalid_apk)
        val installed = pm.getPackageInfo(context.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        if (candidate.packageName != context.packageName || version <= BuildConfig.VERSION_CODE ||
            ReleaseVersion.parse(candidate.versionName.orEmpty())?.compareTo(expectedVersion) != 0)
            throw UpdateFailure(R.string.update_invalid_apk)
        val installedSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val candidateHistory = if (Build.VERSION.SDK_INT >= 28) candidate.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else candidate.signatures
        if (installedSignatures.isNullOrEmpty() || candidateHistory.isNullOrEmpty() ||
            !installedSignatures.all { it in candidateHistory }) throw UpdateFailure(R.string.update_signature_error)
    }
}
