package org.scryertv.client.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.Serializable

data class UpdateMetadata(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkName: String,
    val size: Long,
    val sha256: String,
    val certificateSha256: String,
    val apkUrl: String,
    val releaseUrl: String
) : Serializable {
    companion object {
        const val REPOSITORY = "sergenikz1/random_stuff"
        const val PACKAGE = "org.scryertv.client"
        const val MANIFEST = "scryer-tv-update.json"
        const val MAX_APK_SIZE = 100L * 1024 * 1024
        private val hash = Regex("[0-9a-f]{64}")

        fun isProjectRelease(release: JSONObject): Boolean = !release.optBoolean("draft") &&
            (release.optString("tag_name").startsWith("scryer-tv-v") || release.optString("tag_name") == "scryer-tv-latest")

        fun isAssetUrl(value: String): Boolean {
            val url = value.toHttpUrlOrNull() ?: return false
            return url.scheme == "https" && url.host == "github.com" && url.username.isEmpty() && url.password.isEmpty() &&
                url.encodedPath.startsWith("/$REPOSITORY/releases/download/")
        }

        fun parse(json: JSONObject, release: JSONObject): UpdateMetadata {
            require(isProjectRelease(release)) { "Это релиз другого приложения" }
            require(json.getString("applicationId") == PACKAGE) { "APK другого приложения" }
            val code = json.getLong("versionCode")
            val version = json.getString("versionName")
            val sdk = json.getInt("minSdk")
            val name = json.getString("apkName")
            val size = json.getLong("size")
            val sha = json.getString("sha256").lowercase()
            val cert = json.getString("certificateSha256").lowercase()
            require(code > 0 && version.isNotBlank() && sdk > 0) { "Некорректная версия обновления" }
            require(name.startsWith("ScryerTV-") && name.endsWith(".apk") && '/' !in name && '\\' !in name) { "Некорректное имя APK" }
            require(size in 1..MAX_APK_SIZE && hash.matches(sha) && hash.matches(cert)) { "Некорректная контрольная сумма или размер" }
            val assets = release.getJSONArray("assets")
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
            require(matches.size == 1) { "APK не найден в релизе" }
            val asset = matches.single()
            val url = asset.getString("browser_download_url")
            require(isAssetUrl(url) && asset.getLong("size") == size) { "Адрес или размер APK не совпадает с релизом" }
            val releaseUrl = release.getString("html_url")
            val page = releaseUrl.toHttpUrlOrNull()
            require(page?.scheme == "https" && page.host == "github.com" && page.encodedPath.startsWith("/$REPOSITORY/releases/tag/")) { "Некорректный адрес релиза" }
            return UpdateMetadata(code, version, sdk, name, size, sha, cert, url, releaseUrl)
        }
    }
}
