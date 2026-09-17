package dev.anilbeesetti.nextplayer.feature.stream.data

import android.net.Uri
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Singleton
class StreamVaultApi @Inject constructor() {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    fun listVideos(baseUrl: String): List<StreamVideo> =
        get(baseUrl, "api/videos")?.let { body ->
            json.decodeFromString<List<StreamVideo>>(body)
        }.orEmpty()

    fun getVideo(baseUrl: String, id: String): StreamVideo? =
        get(baseUrl, "api/videos/${Uri.encode(id)}")?.let { body ->
            json.decodeFromString<StreamVideo>(body)
        }

    fun addJob(baseUrl: String, url: String, title: String): StreamJob? {
        val payload = buildString {
            append('{')
            append("\"url\":")
            append(json.encodeToString(url))
            append(",\"title\":")
            append(json.encodeToString(title))
            append('}')
        }
        val body = post(baseUrl, "api/jobs", payload)
        return body?.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString<StreamJob>(it) }.getOrNull()
        }
    }

    fun cancelJob(baseUrl: String, id: String) {
        post(baseUrl, "api/jobs/${Uri.encode(id)}/cancel")
    }

    fun deleteVideo(baseUrl: String, id: String) {
        val request = Request.Builder()
            .url(endpoint(baseUrl, "api/videos/${Uri.encode(id)}"))
            .delete()
            .build()
        execute(request)
    }

    fun openUpdates(
        baseUrl: String,
        listener: (StreamSocketEvent) -> Unit,
        onOpen: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): WebSocket {
        val request = Request.Builder()
            .url(webSocketEndpoint(baseUrl))
            .build()
        return httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { decodeSocketEvent(text) }
                        .getOrNull()
                        ?.let(listener)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    onFailure(t)
                }
            },
        )
    }

    fun close() {
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
    }

    private fun get(baseUrl: String, path: String): String? {
        val request = Request.Builder()
            .url(endpoint(baseUrl, path))
            .get()
            .build()
        return execute(request)
    }

    private fun post(baseUrl: String, path: String, body: String? = null): String? {
        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoint(baseUrl, path))
            .post(requestBody ?: "".toRequestBody(null))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): String? {
        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw StreamVaultApiException(
                        code = response.code,
                        message = body.takeIf(String::isNotBlank) ?: response.message,
                    )
                }
                return body
            }
        } catch (error: StreamVaultApiException) {
            throw error
        } catch (error: IOException) {
            throw StreamVaultApiException(message = error.message ?: "Unable to reach Stream-Vault", cause = error)
        }
    }

    private fun decodeSocketEvent(text: String): StreamSocketEvent? {
        val root = json.parseToJsonElement(text).jsonObject
        val data = root["data"] ?: return null
        return when (root["type"]?.jsonPrimitive?.content) {
            "job_update" -> StreamSocketEvent.JobUpdated(
                json.decodeFromJsonElement(StreamJob.serializer(), data),
            )

            "job_done" -> {
                val payload = data.jsonObject
                val job = payload["job"]?.let {
                    json.decodeFromJsonElement(StreamJob.serializer(), it)
                }
                val video = payload["video"]?.let {
                    json.decodeFromJsonElement(StreamVideo.serializer(), it)
                }
                StreamSocketEvent.JobDone(job = job, video = video)
            }

            "video_deleted" -> StreamSocketEvent.VideoDeleted(data.jsonPrimitive.content)
            else -> null
        }
    }

    private fun endpoint(baseUrl: String, path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun webSocketEndpoint(baseUrl: String): String {
        val normalized = baseUrl.trimEnd('/')
        return when {
            normalized.startsWith("https://", ignoreCase = true) -> "wss://${normalized.removePrefixIgnoreCase("https://")}/ws"
            normalized.startsWith("http://", ignoreCase = true) -> "ws://${normalized.removePrefixIgnoreCase("http://")}/ws"
            else -> error("Stream-Vault server URL must use HTTP or HTTPS")
        }
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

fun normalizeServerUrl(rawUrl: String): String? {
    val value = rawUrl.trim().trimEnd('/')
    val uri = Uri.parse(value)
    val hasHttpScheme = uri.scheme.equals("http", ignoreCase = true) ||
        uri.scheme.equals("https", ignoreCase = true)
    return value.takeIf { hasHttpScheme && !uri.host.isNullOrBlank() }
}

fun resolveStreamUrl(baseUrl: String, path: String): String =
    if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
        path
    } else {
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
    }

class StreamVaultApiException(
    val code: Int? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
