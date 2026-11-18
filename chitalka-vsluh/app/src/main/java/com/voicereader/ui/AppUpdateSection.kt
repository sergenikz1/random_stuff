package com.voicereader.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voicereader.BuildConfig
import com.voicereader.R
import com.voicereader.update.AppUpdater
import com.voicereader.update.UpdateState
import kotlin.math.roundToInt

@Composable
fun AppUpdateSection() {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    fun install() {
        val ready = AppUpdater.state.value as? UpdateState.Ready ?: return
        runCatching { context.startActivity(AppUpdater.installerIntent(context, ready.apk)) }
            .onFailure { AppUpdater.installationFailed() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
    }
    fun requestInstall() {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else runCatching {
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }.onFailure { AppUpdater.installationFailed() }
    }
    // После загрузки сразу переходим к системной установке; отмена оставляет кнопку повтора.
    LaunchedEffect(state is UpdateState.Ready) {
        if (state is UpdateState.Ready) requestInstall()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.update_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        when (val s = state) {
            UpdateState.Checking -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_checking))
            }
            is UpdateState.Downloading -> {
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_downloading, (s.progress * 100).roundToInt()))
            }
            UpdateState.Current -> Text(stringResource(R.string.update_current))
            is UpdateState.Failed -> Text(stringResource(s.message), color = MaterialTheme.colorScheme.error)
            is UpdateState.Ready -> Text(stringResource(R.string.update_ready, s.version))
            UpdateState.Idle -> Unit
        }
        Button(
            onClick = { if (state is UpdateState.Ready) requestInstall() else AppUpdater.check(context) },
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading
        ) {
            Text(stringResource(if (state is UpdateState.Ready) R.string.update_install else R.string.update_button))
        }
        Text(
            stringResource(R.string.update_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
