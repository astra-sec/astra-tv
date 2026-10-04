package org.astrasec.tv.playlist

import android.content.Context
import android.util.AtomicFile
import org.astrasec.tv.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale

/** All methods do disk/network work and must be called on a background thread. */
class PlaylistRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.filesDir, "playlists")

    fun cached(): List<Channel> = cached(DEFAULT_URL)

    /** Caches belong to one source; a different source never inherits a stale list. */
    fun cached(sourceUrl: String): List<Channel> {
        if (sourceUrl.isBlank()) return emptyList()
        return runCatching {
            val cachedJson = cacheFile(sourceUrl).openRead().use { input ->
                JSONObject(readLimited(input, MAX_CACHE_BYTES).toString(Charsets.UTF_8))
            }
            if (cachedJson.getString("sourceUrl") != sourceUrl) return@runCatching emptyList()
            M3uParser.parse(cachedJson.getString("text"), cachedJson.getString("baseUrl"))
        }.getOrDefault(emptyList())
    }

    /** On failure, throws and keeps the existing cache intact. */
    @Throws(IOException::class)
    fun refresh(sourceUrl: String): List<Channel> {
        if (sourceUrl.isBlank()) throw IOException("请先设置直播源地址")
        val downloaded = download(sourceUrl)
        val channels = M3uParser.parse(downloaded.text, downloaded.baseUrl)
        if (channels.isEmpty()) throw IOException("频道源没有可播放的 HTTP/HTTPS 频道")

        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw IOException("无法创建频道缓存目录")
        }
        val envelope = JSONObject()
            .put("sourceUrl", sourceUrl)
            .put("baseUrl", downloaded.baseUrl)
            .put("text", downloaded.text)
            .toString()
        val target = cacheFile(sourceUrl)
        val output = target.startWrite()
        try {
            output.write(envelope.toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw IOException("无法保存频道缓存", error)
        }
        return channels
    }

    private data class DownloadedPlaylist(val text: String, val baseUrl: String)

    private fun download(sourceUrl: String): DownloadedPlaylist {
        var currentUrl = validateUrl(sourceUrl)
        repeat(MAX_REDIRECTS + 1) { hop ->
            val connection = currentUrl.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", "AstraTV/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl, audio/x-mpegurl, text/plain, */*")
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (hop == MAX_REDIRECTS) throw IOException("频道源重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("频道源重定向缺少目标地址")
                    currentUrl = validateUrl(URI(currentUrl.toString()).resolve(location).toString())
                } else {
                    if (status !in 200..299) throw IOException("频道源请求失败（HTTP $status）")
                    val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
                    if (contentLength != null && contentLength > MAX_DOWNLOAD_BYTES) {
                        throw IOException("频道源超过 4 MiB 大小限制")
                    }
                    val bytes = connection.inputStream.use { readLimited(it, MAX_DOWNLOAD_BYTES) }
                    val charset = responseCharset(connection.contentType)
                    return DownloadedPlaylist(bytes.toString(charset), currentUrl.toString())
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("频道源重定向次数过多")
    }

    private fun validateUrl(value: String): URL {
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IOException("频道源地址格式无效", error)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            throw IOException("频道源必须使用 HTTP 或 HTTPS 地址")
        }
        return uri.toURL()
    }

    private fun responseCharset(contentType: String?): Charset {
        val charsetName = contentType?.split(';')?.firstNotNullOfOrNull { part ->
            val pieces = part.trim().split('=', limit = 2)
            pieces.takeIf { it.size == 2 && it[0].trim().equals("charset", ignoreCase = true) }
                ?.get(1)?.trim()?.trim('"', '\'')
        }
        return charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    private fun cacheFile(sourceUrl: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(sourceUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return AtomicFile(File(cacheDirectory, "$digest.json"))
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (result.size() + count > limit) throw IOException("频道源超过大小限制")
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    companion object {
        const val DEFAULT_URL = ""
        private const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
        // JSON escaping can make the envelope larger than the original UTF-8 playlist.
        private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
