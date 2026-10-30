package org.scryertv.client.update

import java.io.IOException

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    val signers: Set<String>
)

internal object UpdatePolicy {
    fun verifyDownload(update: UpdateMetadata, size: Long, sha256: String) {
        if (size != update.size || sha256 != update.sha256) throw IOException("APK повреждён: контрольная сумма или размер не совпадает. Скачайте обновление снова.")
    }

    fun verifyArchive(update: UpdateMetadata, archive: ApkIdentity, installed: ApkIdentity) {
        if (archive.packageName != installed.packageName || archive.packageName != UpdateMetadata.PACKAGE ||
            archive.versionCode != update.versionCode || archive.versionName != update.versionName) {
            throw IOException("Версия или имя приложения в APK не совпадает с релизом")
        }
        if (archive.versionCode <= installed.versionCode) throw IOException("Эта версия уже установлена или новее обновления")
        if (archive.minSdk != null && archive.minSdk != update.minSdk) throw IOException("Требования APK к Android не совпадают с релизом")
        if (archive.signers.size != 1 || archive.signers.single() != update.certificateSha256 || archive.signers != installed.signers) {
            throw IOException("Обновление подписано другим ключом. Установка поверх этой версии невозможна. Настройки не изменены.")
        }
    }
}
