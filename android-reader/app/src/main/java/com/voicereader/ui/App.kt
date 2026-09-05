package com.voicereader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import com.voicereader.data.LibraryStore
import com.voicereader.player.PlayerEngine

private sealed interface Screen {
    data object Library : Screen
    data class Reader(val bookId: String) : Screen
    data object Voices : Screen
}

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    val books by LibraryStore.books.collectAsState()

    when (val s = screen) {
        is Screen.Library -> LibraryScreen(
            books = books,
            onOpen = { book ->
                PlayerEngine.openBook(book)
                screen = Screen.Reader(book.id)
            },
            onVoices = { screen = Screen.Voices }
        )

        is Screen.Reader -> {
            BackHandler { screen = Screen.Library }
            ReaderScreen(
                onBack = { screen = Screen.Library },
                onVoices = { screen = Screen.Voices }
            )
        }

        is Screen.Voices -> {
            BackHandler { screen = Screen.Library }
            VoiceScreen(onBack = {
                screen = PlayerEngine.state.value.bookId
                    ?.let { Screen.Reader(it) } ?: Screen.Library
            })
        }
    }
}
