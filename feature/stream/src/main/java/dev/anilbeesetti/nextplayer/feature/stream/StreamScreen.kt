package dev.anilbeesetti.nextplayer.feature.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.LocalNavigationBottomPadding
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusRing
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.stream.data.StreamJob
import dev.anilbeesetti.nextplayer.feature.stream.data.StreamVideo
import dev.anilbeesetti.nextplayer.feature.stream.data.normalizeServerUrl
import dev.anilbeesetti.nextplayer.feature.stream.data.resolveStreamUrl
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun StreamScreen(
    viewModel: StreamViewModel,
    onPlayVideo: (url: String, title: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showServerDialog by rememberSaveable { mutableStateOf(false) }
    var showJobDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<StreamVideo?>(null) }

    val filteredVideos = state.videos.filter { video ->
        searchQuery.isBlank() || video.title.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.stream),
                fontWeight = FontWeight.Bold,
                actions = {
                    if (state.serverUrl.isNotBlank()) {
                        Text(
                            text = stringResource(
                                if (state.isConnected) R.string.stream_connected else R.string.stream_connecting,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        IconButton(
                            onClick = viewModel::refresh,
                            modifier = Modifier.tvFocusRing(),
                        ) {
                            Icon(
                                imageVector = NextIcons.Update,
                                contentDescription = stringResource(R.string.stream_refresh),
                            )
                        }
                        IconButton(
                            onClick = { showJobDialog = true },
                            modifier = Modifier.tvFocusRing(),
                        ) {
                            Icon(
                                imageVector = NextIcons.Add,
                                contentDescription = stringResource(R.string.stream_add_job),
                            )
                        }
                    }
                    IconButton(
                        onClick = { showServerDialog = true },
                        modifier = Modifier.tvFocusRing(),
                    ) {
                        Icon(
                            imageVector = NextIcons.Settings,
                            contentDescription = stringResource(R.string.stream_settings),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        if (state.serverUrl.isBlank()) {
            StreamEmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                icon = NextIcons.Cloud,
                message = stringResource(R.string.stream_no_server),
                actionLabel = stringResource(R.string.stream_configure_server),
                onAction = { showServerDialog = true },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true,
                    leadingIcon = { Icon(NextIcons.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.stream_search)) },
                    shape = RoundedCornerShape(16.dp),
                )

                state.error?.let { error ->
                    ErrorBanner(
                        message = error,
                        onRetry = {
                            viewModel.clearError()
                            viewModel.refresh()
                        },
                    )
                }

                if (state.isLoading && state.videos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 12.dp,
                            bottom = LocalNavigationBottomPadding.current + 28.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            StreamStats(
                                videos = filteredVideos,
                                jobs = state.jobs,
                            )
                        }

                        if (state.jobs.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = stringResource(R.string.stream_active_jobs),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(
                                items = state.jobs,
                                key = { job -> "job-${job.id}" },
                                span = { GridItemSpan(maxLineSpan) },
                            ) { job ->
                                StreamJobCard(job = job, onCancel = { viewModel.cancelJob(job.id) })
                            }
                        }

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = stringResource(R.string.stream_videos),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        if (filteredVideos.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                StreamEmptyState(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 44.dp),
                                    icon = NextIcons.Video,
                                    message = if (searchQuery.isBlank()) {
                                        stringResource(R.string.stream_no_videos)
                                    } else {
                                        stringResource(R.string.no_results_found)
                                    },
                                    actionLabel = if (searchQuery.isBlank()) {
                                        stringResource(R.string.stream_add_job)
                                    } else {
                                        null
                                    },
                                    onAction = if (searchQuery.isBlank()) {
                                        { showJobDialog = true }
                                    } else {
                                        null
                                    },
                                )
                            }
                        } else {
                            items(
                                items = filteredVideos,
                                key = { video -> video.id },
                            ) { video ->
                                StreamVideoCard(
                                    video = video,
                                    serverUrl = state.serverUrl,
                                    onClick = {
                                        viewModel.playVideo(video) { url, title ->
                                            onPlayVideo(url, title)
                                        }
                                    },
                                    onDelete = { deleteTarget = video },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showServerDialog) {
        ServerSettingsDialog(
            currentUrl = state.serverUrl,
            onDismiss = { showServerDialog = false },
            onSave = { url ->
                showServerDialog = false
                viewModel.saveServerUrl(url)
            },
        )
    }

    if (showJobDialog) {
        AddJobDialog(
            isSaving = state.isAddingJob,
            onDismiss = { if (!state.isAddingJob) showJobDialog = false },
            onAdd = { url, title ->
                viewModel.addJob(url, title)
                showJobDialog = false
            },
        )
    }

    deleteTarget?.let { video ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.stream_delete_video)) },
            text = { Text(stringResource(R.string.stream_delete_video_confirmation, video.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteVideo(video.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.tvFocusRing(),
                ) {
                    Text(stringResource(R.string.stream_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.tvFocusRing(),
                ) {
                    Text(stringResource(R.string.stream_cancel))
                }
            },
        )
    }
}

@Composable
private fun StreamStats(
    videos: List<StreamVideo>,
    jobs: List<StreamJob>,
) {
    val totalBytes = videos.sumOf { it.sizeBytes ?: 0L }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        StatValue(value = videos.size.toString(), label = stringResource(R.string.stream_videos))
        StatValue(value = formatBytes(totalBytes), label = stringResource(R.string.size))
        if (jobs.isNotEmpty()) {
            StatValue(value = jobs.size.toString(), label = stringResource(R.string.stream_active_jobs))
        }
    }
}

@Composable
private fun StatValue(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StreamVideoCard(
    video: StreamVideo,
    serverUrl: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                imageVector = NextIcons.Video,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(42.dp),
            )
            video.thumbnail?.takeIf(String::isNotBlank)?.let { thumbnail ->
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(resolveStreamUrl(serverUrl, thumbnail))
                        .crossfade(true)
                        .build(),
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                        ),
                    ),
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.58f))
                    .size(34.dp)
                    .tvFocusRing(),
            ) {
                Icon(
                    imageVector = NextIcons.Delete,
                    contentDescription = stringResource(R.string.stream_delete_video),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            video.duration?.takeIf { it > 0 }?.let { duration ->
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            text = video.title.ifBlank { "Untitled video" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp, top = 9.dp, end = 10.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = video.createdAt?.substringBefore('T').orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatBytes(video.sizeBytes ?: 0L),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StreamJobCard(job: StreamJob, onCancel: () -> Unit) {
    val downloadProgress = ((job.downloadPct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val transcodeProgress = ((job.transcodePct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = job.title.ifBlank { job.id },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = job.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (job.status in ACTIVE_JOB_STATUSES) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.tvFocusRing(),
                    ) {
                        Icon(
                            imageVector = NextIcons.Close,
                            contentDescription = stringResource(R.string.stream_cancel_job),
                        )
                    }
                }
            }
            JobProgressRow(
                label = stringResource(R.string.stream_download),
                progress = downloadProgress,
                percentage = job.downloadPct ?: 0.0,
            )
            JobProgressRow(
                label = stringResource(R.string.stream_transcode),
                progress = transcodeProgress,
                percentage = job.transcodePct ?: 0.0,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = formatSpeed(job.downloadSpeed ?: 0L),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatEta(job.downloadEta ?: 0.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if ((job.totalBytes ?: 0L) > 0L) {
                    Text(
                        text = "${formatBytes(job.downloadedBytes ?: 0L)} / ${formatBytes(job.totalBytes ?: 0L)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            job.error?.takeIf(String::isNotBlank)?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun JobProgressRow(label: String, progress: Float, percentage: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(94.dp),
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${percentage.coerceIn(0.0, 100.0).formatOneDecimal()}%",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(52.dp).padding(start = 6.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, modifier = Modifier.tvFocusRing()) {
            Text(stringResource(R.string.stream_retry))
        }
    }
}

@Composable
private fun StreamEmptyState(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.size(16.dp))
            FilledTonalButton(onClick = onAction, modifier = Modifier.tvFocusRing()) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ServerSettingsDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var url by rememberSaveable(currentUrl) { mutableStateOf(currentUrl) }
    val normalizedUrl = normalizeServerUrl(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stream_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.stream_server_url_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.stream_server_url)) },
                    placeholder = { Text(stringResource(R.string.stream_server_url_hint)) },
                    singleLine = true,
                    isError = url.isNotBlank() && normalizedUrl == null,
                    supportingText = if (url.isNotBlank() && normalizedUrl == null) {
                        { Text(stringResource(R.string.invalid_url)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { normalizedUrl?.let(onSave) },
                enabled = normalizedUrl != null,
                modifier = Modifier.tvFocusRing(),
            ) {
                Text(stringResource(R.string.stream_save_server))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusRing()) {
                Text(stringResource(R.string.stream_cancel))
            }
        },
    )
}

@Composable
private fun AddJobDialog(
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onAdd: (url: String, title: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    val canSubmit = url.trim().isNotEmpty() && !isSaving
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stream_add_job)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.stream_direct_url)) },
                    singleLine = true,
                    enabled = !isSaving,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.stream_title_optional)) },
                    singleLine = true,
                    enabled = !isSaving,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(url, title) },
                enabled = canSubmit,
                modifier = Modifier.tvFocusRing(),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.stream_add))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving, modifier = Modifier.tvFocusRing()) {
                Text(stringResource(R.string.stream_cancel))
            }
        },
    )
}

private val ACTIVE_JOB_STATUSES = setOf("pending", "downloading", "transcoding", "thumbnailing")

private fun formatDuration(seconds: Double): String {
    val totalSeconds = floor(seconds).toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val remainingSeconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824 -> "${(bytes / 1_048_576.0).formatOneDecimal()} MB"
    else -> "${(bytes / 1_073_741_824.0).formatTwoDecimals()} GB"
}

private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0 -> "—"
    bytesPerSecond < 1_048_576 -> "${bytesPerSecond / 1_024} KB/s"
    else -> "${(bytesPerSecond / 1_048_576.0).formatOneDecimal()} MB/s"
}

private fun formatEta(seconds: Double): String {
    if (seconds <= 0) return "ETA —"
    val totalSeconds = ceil(seconds).toInt()
    return if (totalSeconds < 60) {
        "ETA ${totalSeconds}s"
    } else {
        "ETA ${totalSeconds / 60}m ${totalSeconds % 60}s"
    }
}

private fun Double.formatOneDecimal(): String = "%.1f".format(this)

private fun Double.formatTwoDecimals(): String = "%.2f".format(this)
