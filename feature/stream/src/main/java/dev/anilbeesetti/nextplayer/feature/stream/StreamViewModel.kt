package dev.anilbeesetti.nextplayer.feature.stream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.feature.stream.data.StreamJob
import dev.anilbeesetti.nextplayer.feature.stream.data.StreamSocketEvent
import dev.anilbeesetti.nextplayer.feature.stream.data.StreamVaultApi
import dev.anilbeesetti.nextplayer.feature.stream.data.StreamVideo
import dev.anilbeesetti.nextplayer.feature.stream.data.normalizeServerUrl
import dev.anilbeesetti.nextplayer.feature.stream.data.resolveStreamUrl
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket

@HiltViewModel
class StreamViewModel @Inject constructor(
    private val api: StreamVaultApi,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {
    private val stateInternal = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = stateInternal.asStateFlow()

    private var socket: WebSocket? = null
    private var loadJob: Job? = null
    private var reconnectJob: Job? = null
    private var activeServerUrl = ""

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences
                .map { it.streamServerUrl.trimEnd('/') }
                .collectLatest { serverUrl ->
                    activeServerUrl = serverUrl
                    closeSocket()
                    stateInternal.value = StreamUiState(serverUrl = serverUrl, isLoading = serverUrl.isNotBlank())
                    if (serverUrl.isNotBlank()) {
                        refresh()
                        openSocket(serverUrl)
                    }
                }
        }
    }

    fun saveServerUrl(rawUrl: String) {
        val normalized = normalizeServerUrl(rawUrl)
        if (normalized == null) {
            stateInternal.update { it.copy(error = "Enter a valid HTTP or HTTPS server URL") }
            return
        }
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences ->
                preferences.copy(streamServerUrl = normalized)
            }
        }
    }

    fun refresh() {
        val serverUrl = activeServerUrl.takeIf(String::isNotBlank) ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            stateInternal.update { it.copy(isLoading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { api.listVideos(serverUrl) }
            }.onSuccess { videos ->
                stateInternal.update { it.copy(videos = videos, isLoading = false) }
            }.onFailure { error ->
                stateInternal.update {
                    it.copy(isLoading = false, error = error.message ?: "Could not load Stream-Vault videos")
                }
            }
        }
    }

    fun addJob(url: String, title: String) {
        val serverUrl = activeServerUrl.takeIf(String::isNotBlank) ?: return
        if (state.value.isAddingJob) return
        viewModelScope.launch {
            stateInternal.update { it.copy(isAddingJob = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) { api.addJob(serverUrl, url.trim(), title.trim()) }
            }.onSuccess { job ->
                job?.let { addOrReplaceJob(it) }
                stateInternal.update { it.copy(isAddingJob = false) }
            }.onFailure { error ->
                stateInternal.update {
                    it.copy(isAddingJob = false, error = error.message ?: "Could not add download job")
                }
            }
        }
    }

    fun cancelJob(id: String) {
        val serverUrl = activeServerUrl.takeIf(String::isNotBlank) ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.cancelJob(serverUrl, id) }
            }.onFailure { error ->
                stateInternal.update { it.copy(error = error.message ?: "Could not cancel job") }
            }
        }
    }

    fun deleteVideo(id: String) {
        val serverUrl = activeServerUrl.takeIf(String::isNotBlank) ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.deleteVideo(serverUrl, id) }
            }.onSuccess {
                stateInternal.update { current ->
                    current.copy(videos = current.videos.filterNot { it.id == id })
                }
            }.onFailure { error ->
                stateInternal.update { it.copy(error = error.message ?: "Could not delete video") }
            }
        }
    }

    fun playVideo(video: StreamVideo, onReady: (url: String, title: String) -> Unit) {
        val serverUrl = activeServerUrl.takeIf(String::isNotBlank) ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.getVideo(serverUrl, video.id) ?: video
                }
            }.onSuccess { detail ->
                val path = detail.fmp4Path ?: video.fmp4Path
                if (path.isNullOrBlank()) {
                    stateInternal.update { it.copy(error = "This video does not have a playable stream") }
                } else {
                    onReady(resolveStreamUrl(serverUrl, path), detail.title.ifBlank { video.title })
                }
            }.onFailure { error ->
                stateInternal.update { it.copy(error = error.message ?: "Could not open video") }
            }
        }
    }

    fun clearError() {
        stateInternal.update { it.copy(error = null) }
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        closeSocket()
        api.close()
        super.onCleared()
    }

    private fun openSocket(serverUrl: String) {
        if (serverUrl != activeServerUrl || serverUrl.isBlank()) return
        socket = runCatching {
            api.openUpdates(
                baseUrl = serverUrl,
                onOpen = {
                    if (serverUrl == activeServerUrl) {
                        stateInternal.update { it.copy(isConnected = true) }
                    }
                },
                listener = { event ->
                    if (serverUrl == activeServerUrl) handleSocketEvent(event)
                },
                onFailure = {
                    if (serverUrl == activeServerUrl) {
                        stateInternal.update { it.copy(isConnected = false) }
                        scheduleReconnect(serverUrl)
                    }
                },
            )
        }.getOrElse {
            stateInternal.update { it.copy(isConnected = false) }
            scheduleReconnect(serverUrl)
            null
        }
    }

    private fun handleSocketEvent(event: StreamSocketEvent) {
        when (event) {
            is StreamSocketEvent.JobUpdated -> addOrReplaceJob(event.job)
            is StreamSocketEvent.JobDone -> {
                event.job?.let(::addOrReplaceJob)
                event.video?.let { video ->
                    stateInternal.update { current ->
                        current.copy(videos = listOf(video) + current.videos.filterNot { it.id == video.id })
                    }
                }
            }

            is StreamSocketEvent.VideoDeleted -> stateInternal.update { current ->
                current.copy(videos = current.videos.filterNot { it.id == event.id })
            }
        }
    }

    private fun addOrReplaceJob(job: StreamJob) {
        if (job.id.isBlank()) return
        stateInternal.update { current ->
            val withoutCurrent = current.jobs.filterNot { it.id == job.id }
            current.copy(jobs = withoutCurrent + job)
        }
        if (job.status == "done") {
            viewModelScope.launch {
                delay(DONE_JOB_DISPLAY_MILLIS)
                stateInternal.update { current ->
                    current.copy(jobs = current.jobs.filterNot { it.id == job.id })
                }
            }
        }
    }

    private fun scheduleReconnect(serverUrl: String) {
        if (serverUrl != activeServerUrl || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            delay(RECONNECT_DELAY_MILLIS)
            if (serverUrl == activeServerUrl) openSocket(serverUrl)
        }
    }

    private fun closeSocket() {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "server changed")
        socket = null
        stateInternal.update { it.copy(isConnected = false) }
    }

    companion object {
        private const val RECONNECT_DELAY_MILLIS = 2_500L
        private const val DONE_JOB_DISPLAY_MILLIS = 5_000L
    }
}

data class StreamUiState(
    val serverUrl: String = "",
    val videos: List<StreamVideo> = emptyList(),
    val jobs: List<StreamJob> = emptyList(),
    val isLoading: Boolean = false,
    val isAddingJob: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
)
