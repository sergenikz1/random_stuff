package org.scryertv.client.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateMetadataTest {
    private fun manifest() = JSONObject().put("applicationId", "org.scryertv.client").put("versionCode", 4)
        .put("versionName", "1.3.0").put("minSdk", 21).put("apkName", "ScryerTV-1.3.0.apk")
        .put("size", 1000).put("sha256", "a".repeat(64)).put("certificateSha256", "b".repeat(64))
    private fun release() = JSONObject().put("tag_name", "scryer-tv-v1.3.0").put("draft", false).put("prerelease", true)
        .put("html_url", "https://github.com/sergenikz1/random_stuff/releases/tag/scryer-tv-v1.3.0")
        .put("assets", JSONArray().put(JSONObject().put("name", "ScryerTV-1.3.0.apk").put("size", 1000)
            .put("browser_download_url", "https://github.com/sergenikz1/random_stuff/releases/download/scryer-tv-v1.3.0/ScryerTV-1.3.0.apk")))

    @Test fun acceptsProjectPreviewAndExactApkAsset() {
        val update = UpdateMetadata.parse(manifest(), release())
        assertEquals(4L, update.versionCode)
        assertEquals("1.3.0", update.versionName)
        assertTrue(update.apkUrl.endsWith("ScryerTV-1.3.0.apk"))
    }
    @Test fun ignoresOtherProjectsAndDrafts() {
        assertFalse(UpdateMetadata.isProjectRelease(release().put("tag_name", "chitalka-v9.0")))
        assertFalse(UpdateMetadata.isProjectRelease(release().put("draft", true)))
        assertTrue(UpdateMetadata.isProjectRelease(release().put("tag_name", "scryer-tv-latest")))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDifferentPackage() {
        UpdateMetadata.parse(manifest().put("applicationId", "com.other.app"), release())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsAssetSizeMismatch() {
        UpdateMetadata.parse(manifest().put("size", 999), release())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidChecksum() {
        UpdateMetadata.parse(manifest().put("sha256", "bad"), release())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsAmbiguousApkAssets() {
        val release = release()
        release.getJSONArray("assets").put(release.getJSONArray("assets").getJSONObject(0))
        UpdateMetadata.parse(manifest(), release)
    }
    @Test fun rejectsForeignOrInsecureUrls() {
        assertFalse(UpdateMetadata.isAssetUrl("http://github.com/sergenikz1/random_stuff/releases/download/v/app.apk"))
        assertFalse(UpdateMetadata.isAssetUrl("https://github.com.evil.org/sergenikz1/random_stuff/releases/download/v/app.apk"))
        assertFalse(UpdateMetadata.isAssetUrl("https://github.com/other/repo/releases/download/v/app.apk"))
        assertFalse(UpdateMetadata.isAssetUrl("https://user:pass@github.com/sergenikz1/random_stuff/releases/download/v/app.apk"))
    }
}
