package org.scryertv.client.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object UpdateRepository {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

    private fun get(url: String): Response {
        var target = url
        repeat(6) {
            val parsed = target.toHttpUrlOrNull() ?: throw IOException("Некорректный адрес GitHub")
            if (parsed.scheme != "https" || parsed.host !in hosts || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw IOException("Небезопасное перенаправление при обновлении")
            val response = http.newCall(Request.Builder().url(parsed)
                .header("User-Agent", "ScryerTV-Updater")
                .header("Accept", if (parsed.host == "api.github.com") "application/vnd.github+json" else "*/*").build()).execute()
            if (response.code in listOf(301,302,303,307,308)) {
                val next = response.header("Location")?.let { parsed.resolve(it) }?.toString()
                response.close()
                target = next ?: throw IOException("GitHub не вернул адрес файла")
            } else {
                if (!response.isSuccessful) {
                    val code = response.code; response.close()
                    throw IOException(if (code == 403 || code == 429) "GitHub временно ограничил запросы. Попробуйте позже." else "Ошибка GitHub: HTTP $code")
                }
                return response
            }
        }
        throw IOException("Слишком много перенаправлений GitHub")
    }

    private fun json(url: String, limit: Int = 1024 * 1024): String = get(url).use { response ->
        val body = response.body ?: throw IOException("Пустой ответ GitHub")
        val out = java.io.ByteArrayOutputStream()
        body.byteStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (out.size() + count > limit) throw IOException("Слишком большой ответ GitHub")
                out.write(buffer, 0, count)
            }
        }
        out.toString("UTF-8")
    }

    suspend fun check(installedCode: Long): UpdateMetadata? = withContext(Dispatchers.IO) {
        val found = mutableListOf<UpdateMetadata>()
        var firstFailure: Exception? = null
        var matchingReleases = 0
        for (page in 1..3) {
            currentCoroutineContext().ensureActive()
            val releases = JSONArray(json("https://api.github.com/repos/${UpdateMetadata.REPOSITORY}/releases?per_page=30&page=$page"))
            for (index in 0 until releases.length()) {
                val release = releases.getJSONObject(index)
                if (!UpdateMetadata.isProjectRelease(release)) continue
                val assets = release.optJSONArray("assets") ?: continue
                val manifest = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == UpdateMetadata.MANIFEST } ?: continue
                matchingReleases++
                try {
                    val url = manifest.getString("browser_download_url")
                    require(UpdateMetadata.isAssetUrl(url)) { "Некорректный адрес описания обновления" }
                    found += UpdateMetadata.parse(JSONObject(json(url, 64 * 1024)), release)
                } catch (e: Exception) { if (firstFailure == null) firstFailure = e }
                if (matchingReleases >= 10) break
            }
            if (releases.length() < 30 || matchingReleases >= 10) break
        }
        if (found.isEmpty() && firstFailure != null) throw IOException("Не удалось прочитать описание обновления", firstFailure)
        found.filter { it.versionCode > installedCode }.maxByOrNull { it.versionCode }
    }

    suspend fun download(context: Context, update: UpdateMetadata, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File.createTempFile("download-", ".part", folder)
        val target = File(folder, "update-${update.versionCode}.apk")
        try {
            get(update.apkUrl).use { response ->
                val body = response.body ?: throw IOException("GitHub вернул пустой APK")
                if (body.contentLength() > 0 && body.contentLength() != update.size) throw IOException("Размер APK изменился")
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                var lastPercent = -1
                body.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > update.size) throw IOException("Размер APK больше ожидаемого")
                        digest.update(buffer, 0, read); output.write(buffer, 0, read)
                        val percent = (count * 100 / update.size).toInt()
                        if (lastPercent != percent) { lastPercent = percent; progress(percent) }
                    }
                } }
                UpdatePolicy.verifyDownload(update, count, hex(digest.digest()))
            }
            currentCoroutineContext().ensureActive()
            verifyArchive(context, update, part)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Не удалось сохранить обновление")
            folder.listFiles()?.filter { it != target && it.extension == "apk" }?.forEach { it.delete() }
            target
        } finally { part.delete() }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(context: Context, update: UpdateMetadata, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Файл не является APK")
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun certificates(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
        }
        fun identity(info: android.content.pm.PackageInfo) = ApkIdentity(info.packageName, versionCode(info), info.versionName,
            if (Build.VERSION.SDK_INT >= 24) info.applicationInfo?.minSdkVersion else null, certificates(info))
        UpdatePolicy.verifyArchive(update, identity(apk), identity(installed))
    }

    suspend fun verify(context: Context, update: UpdateMetadata, file: File) = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() != update.size) throw IOException("APK отсутствует. Скачайте обновление снова.")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        UpdatePolicy.verifyDownload(update, file.length(), hex(digest.digest()))
        verifyArchive(context, update, file)
    }

    @Suppress("DEPRECATION")
    fun versionCode(info: android.content.pm.PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
