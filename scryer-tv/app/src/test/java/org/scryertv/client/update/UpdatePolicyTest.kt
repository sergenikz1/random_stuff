package org.scryertv.client.update

import org.junit.Test
import java.io.IOException

class UpdatePolicyTest {
    private val signer = "b".repeat(64)
    private val update = UpdateMetadata(4, "1.3.0", 21, "ScryerTV-1.3.0.apk", 1000, "a".repeat(64), signer, "", "")
    private val installed = ApkIdentity(UpdateMetadata.PACKAGE, 3, "1.2.0", 21, setOf(signer))
    private val archive = installed.copy(versionCode = 4, versionName = "1.3.0")

    @Test fun acceptsNewVersionWithOriginalSigner() { UpdatePolicy.verifyArchive(update, archive, installed) }
    @Test fun supportsLegacyAndroidWithoutArchiveMinSdk() { UpdatePolicy.verifyArchive(update, archive.copy(minSdk = null), installed) }
    @Test(expected = IOException::class) fun rejectsWrongSigningKey() {
        UpdatePolicy.verifyArchive(update, archive.copy(signers = setOf("c".repeat(64))), installed)
    }
    @Test(expected = IOException::class) fun rejectsUnsignedArchive() { UpdatePolicy.verifyArchive(update, archive.copy(signers = emptySet()), installed) }
    @Test(expected = IOException::class) fun rejectsAnotherApplication() { UpdatePolicy.verifyArchive(update, archive.copy(packageName = "com.other.app"), installed) }
    @Test(expected = IOException::class) fun rejectsVersionDifferentFromManifest() { UpdatePolicy.verifyArchive(update, archive.copy(versionCode = 5), installed) }
    @Test(expected = IOException::class) fun rejectsNameDifferentFromManifest() { UpdatePolicy.verifyArchive(update, archive.copy(versionName = "2.0.0"), installed) }
    @Test(expected = IOException::class) fun rejectsDowngradeOrReinstall() { UpdatePolicy.verifyArchive(update, archive, installed.copy(versionCode = 4)) }
    @Test(expected = IOException::class) fun rejectsUnexpectedAndroidRequirement() { UpdatePolicy.verifyArchive(update, archive.copy(minSdk = 26), installed) }
    @Test(expected = IOException::class) fun rejectsDifferentDeclaredCertificate() { UpdatePolicy.verifyArchive(update.copy(certificateSha256 = "c".repeat(64)), archive, installed) }
    @Test fun acceptsCorrectDownload() { UpdatePolicy.verifyDownload(update, 1000, update.sha256) }
    @Test(expected = IOException::class) fun rejectsPartialDownload() { UpdatePolicy.verifyDownload(update, 999, update.sha256) }
    @Test(expected = IOException::class) fun rejectsTamperedDownload() { UpdatePolicy.verifyDownload(update, 1000, "d".repeat(64)) }
}
