package com.voicereader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.voicereader.data.LibraryStore
import com.voicereader.data.Settings
import com.voicereader.player.PlayerEngine
import com.voicereader.rewards.Rewards
import com.voicereader.ui.App
import com.voicereader.ui.VoiceReaderTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AppText.init(this)
        Settings.init(this)
        Rewards.init(this)
        LibraryStore.init(this)
        PlayerEngine.attach(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            VoiceReaderTheme {
                App()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStop() {
        super.onStop()
        // Позиция чтения не должна теряться при сворачивании.
        PlayerEngine.state.value.let { s ->
            s.bookId?.let { LibraryStore.savePosition(it, s.index) }
        }
        Rewards.save()
    }
}
