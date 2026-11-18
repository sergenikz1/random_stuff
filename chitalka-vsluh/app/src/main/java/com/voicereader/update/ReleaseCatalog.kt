package com.voicereader.update

data class ReleaseAsset(val name: String, val url: String, val bytes: Long, val digest: String? = null)
data class AppRelease(val tag: String, val draft: Boolean, val prerelease: Boolean, val assets: List<ReleaseAsset>) {
    val version: ReleaseVersion? get() = if (draft || prerelease) null else ReleaseVersion.fromTag(tag)
}
data class UpdateCandidate(val version: ReleaseVersion, val name: String, val asset: ReleaseAsset)

object ReleaseCatalog {
    const val REPOSITORY = "https://github.com/sergenikz1/random_stuff"

    fun newer(releases: List<AppRelease>, installed: ReleaseVersion): List<AppRelease> =
        releases.filter { it.version?.let { version -> version > installed } == true }

    fun select(releases: List<AppRelease>, installed: ReleaseVersion, abis: List<String>): UpdateCandidate? =
        newer(releases, installed).mapNotNull { release ->
            val name = release.tag.removePrefix("chitalka-v")
            val asset = abis.firstNotNullOfOrNull { abi ->
                release.assets.firstOrNull {
                    it.name == "chitalka-vsluh-$name-$abi.apk" &&
                        it.url.startsWith("$REPOSITORY/releases/download/") && it.bytes > 0
                }
            } ?: return@mapNotNull null
            UpdateCandidate(requireNotNull(release.version), name, asset)
        }.maxByOrNull { it.version }
}
