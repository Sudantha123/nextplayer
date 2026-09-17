package dev.anilbeesetti.nextplayer.navigation

import android.content.Context
import android.net.Uri
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.anilbeesetti.nextplayer.feature.stream.navigation.streamEntry

fun EntryProviderScope<NavKey>.streamNavGraph(
    context: Context,
    backStack: NavBackStack<NavKey>,
) {
    streamEntry(
        onPlayVideo = { url, title ->
            context.startPlayback(uri = Uri.parse(url), title = title)
        },
    )
}
