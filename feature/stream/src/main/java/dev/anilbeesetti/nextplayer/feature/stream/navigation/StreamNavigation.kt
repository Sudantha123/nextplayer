package dev.anilbeesetti.nextplayer.feature.stream.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.stream.StreamScreen
import dev.anilbeesetti.nextplayer.feature.stream.StreamViewModel
import kotlinx.serialization.Serializable

@Serializable
data object StreamRoute : NavKey

fun EntryProviderScope<NavKey>.streamEntry(
    onPlayVideo: (url: String, title: String) -> Unit,
) {
    entry<StreamRoute> {
        val viewModel = hiltViewModel<StreamViewModel>()
        StreamScreen(
            viewModel = viewModel,
            onPlayVideo = onPlayVideo,
        )
    }
}
