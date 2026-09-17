package dev.anilbeesetti.nextplayer.feature.stream.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StreamVideo(
    val id: String = "",
    val title: String = "",
    val thumbnail: String? = null,
    val duration: Double? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("fmp4_path") val fmp4Path: String? = null,
)

@Serializable
data class StreamJob(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val status: String = "pending",
    @SerialName("download_pct") val downloadPct: Double? = null,
    @SerialName("transcode_pct") val transcodePct: Double? = null,
    @SerialName("download_speed") val downloadSpeed: Long? = null,
    @SerialName("downloaded_bytes") val downloadedBytes: Long? = null,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    @SerialName("download_eta") val downloadEta: Double? = null,
    val error: String? = null,
)

sealed interface StreamSocketEvent {
    data class JobUpdated(val job: StreamJob) : StreamSocketEvent
    data class JobDone(val job: StreamJob?, val video: StreamVideo?) : StreamSocketEvent
    data class VideoDeleted(val id: String) : StreamSocketEvent
}
