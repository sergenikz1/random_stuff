package com.voicereader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.voicereader.data.LibraryStore
import com.voicereader.player.PlayerEngine
import com.voicereader.rewards.Rewards
import com.voicereader.ui.rewards.AppearanceScreen
import com.voicereader.ui.rewards.AwardsScreen
import com.voicereader.ui.rewards.DailyWelcomeScreen

private sealed interface Screen {
    data object Library : Screen
    data class Reader(val bookId: String) : Screen
    data object Voices : Screen
    data object Speed : Screen
    data class Awards(val tab: Int, val back: Screen) : Screen
    data class Appearance(val back: Screen) : Screen
}

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    val books by LibraryStore.books.collectAsState()
    val rewards by Rewards.state.collectAsState()
    // Экран дня — один раз в сутки, при первом заходе.
    var welcome by remember { mutableStateOf(Rewards.pendingWelcome()) }

    welcome?.let { kind ->
        BackHandler { Rewards.welcomeShown(); welcome = null }
        DailyWelcomeScreen(kind, rewards) { Rewards.welcomeShown(); welcome = null }
        return
    }

    val readerOrLibrary = { PlayerEngine.state.value.bookId?.let { Screen.Reader(it) } ?: Screen.Library }

    when (val s = screen) {
        is Screen.Library -> LibraryScreen(
            books = books,
            onOpen = { book ->
                PlayerEngine.openBook(book)
                screen = Screen.Reader(book.id)
            },
            onVoices = { screen = Screen.Voices },
            onAwards = { screen = Screen.Awards(0, Screen.Library) }
        )

        is Screen.Reader -> {
            BackHandler { screen = Screen.Library }
            ReaderScreen(
                onBack = { screen = Screen.Library },
                onVoices = { screen = Screen.Voices },
                onSpeedRead = { screen = Screen.Speed },
                onAwards = { tab -> screen = Screen.Awards(tab, s) },
                onAppearance = { screen = Screen.Appearance(s) }
            )
        }

        is Screen.Speed -> SpeedReaderScreen(onBack = { screen = readerOrLibrary() })

        is Screen.Voices -> {
            BackHandler { screen = Screen.Library }
            VoiceScreen(onBack = { screen = readerOrLibrary() })
        }

        is Screen.Awards -> {
            BackHandler { screen = s.back }
            AwardsScreen(s.tab, onBack = { screen = s.back }, onAppearance = { screen = Screen.Appearance(s) })
        }

        is Screen.Appearance -> {
            BackHandler { screen = s.back }
            AppearanceScreen(onBack = { screen = s.back })
        }
    }
}
