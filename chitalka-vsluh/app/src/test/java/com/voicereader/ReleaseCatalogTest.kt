package com.voicereader

import com.voicereader.update.*
import org.junit.Assert.*
import org.junit.Test

class ReleaseCatalogTest {
    private val installed = requireNotNull(ReleaseVersion.parse("1.4"))
    private fun release(version: String, abis: List<String> = listOf("arm64-v8a", "armeabi-v7a")) =
        AppRelease("chitalka-v$version", false, false, abis.map { abi ->
            val name = "chitalka-vsluh-$version-$abi.apk"
            ReleaseAsset(name, "${ReleaseCatalog.REPOSITORY}/releases/download/chitalka-v$version/$name", 100)
        })

    @Test
    fun comparesVersionComponentsNumericallyAndAcceptsTrailingZeros() {
        assertTrue(ReleaseVersion.parse("1.10")!! > ReleaseVersion.parse("1.9")!!)
        assertEquals(0, installed.compareTo(ReleaseVersion.parse("1.4.0")!!))
        assertTrue(ReleaseVersion.parse("1.5.1")!! > ReleaseVersion.parse("1.5")!!)
        listOf("1.5-beta", "v1.5", "", "1..5", "99999999999999").forEach { assertNull(ReleaseVersion.parse(it)) }
        assertNull(ReleaseVersion.fromTag("another-app-v9.0"))
    }

    @Test
    fun ignoresOtherProjectsDraftsAndPrereleasesAndChoosesNewestCompatibleVersion() {
        val releases = listOf(
            release("1.5"), release("1.10"), release("1.9"), release("1.4"),
            release("2.0").copy(draft = true), release("3.0").copy(prerelease = true),
            release("9.0").copy(tag = "another-app-v9.0")
        )
        assertEquals("1.10", ReleaseCatalog.select(releases, installed, listOf("arm64-v8a"))?.name)
    }

    @Test
    fun respectsPhoneAbiOrderAndFallsBackTo32BitAsset() {
        val entry = release("1.5", listOf("armeabi-v7a", "arm64-v8a"))
        val abis = listOf("arm64-v8a", "armeabi-v7a")
        assertTrue(ReleaseCatalog.select(listOf(entry), installed, abis)!!.asset.name.endsWith("arm64-v8a.apk"))
        assertTrue(ReleaseCatalog.select(listOf(release("1.5", listOf("armeabi-v7a"))), installed, abis)!!
            .asset.name.endsWith("armeabi-v7a.apk"))
        assertNull(ReleaseCatalog.select(listOf(entry), installed, listOf("x86_64")))
    }

    @Test
    fun rejectsDowngradesMissingAssetsAndDownloadsOutsideTheRepository() {
        assertNull(ReleaseCatalog.select(listOf(release("1.3"), release("1.4")), installed, listOf("arm64-v8a")))
        val newer = release("1.5")
        assertNull(ReleaseCatalog.select(listOf(newer.copy(assets = emptyList())), installed, listOf("arm64-v8a")))
        val foreign = newer.copy(assets = newer.assets.map { it.copy(url = "https://example.com/${it.name}") })
        assertNull(ReleaseCatalog.select(listOf(foreign), installed, listOf("arm64-v8a")))
        assertTrue(ReleaseCatalog.newer(listOf(foreign), installed).isNotEmpty())
    }
}
