package org.astrasec.tv.epg

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import org.astrasec.tv.BuildConfig
import org.astrasec.tv.playlist.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Disk and network methods belong on the activity's EPG executor, never the video/UI thread. */
class EpgRepository(context: Context) {
    private val cacheDirectory = File(context.applicationContext.filesDir, "epg")
    // The UI may ask needsRefresh while the executor is downloading; that read never waits on I/O.
    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val sourceUrl: String,
        val mappingFingerprint: String,
        val snapshot: EpgSnapshot,
        val lastAttemptAtMs: Long = 0,
        val failed: Boolean = false,
    )

    /** Each URL and channel identity mapping has its own cache; no other source is consulted. */
    fun cached(sourceUrl: String, channels: List<Channel>): EpgSnapshot {
        if (channels.isEmpty()) return EpgSnapshot(emptyMap())
        return cacheEntry(sourceUrl.trim(), mappingFingerprint(channels)).snapshot
    }

    /** This is a memory-only check after cached(); calling refresh still checks the TTL itself. */
    fun needsRefresh(
        sourceUrl: String,
        channels: List<Channel>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (channels.isEmpty()) return false
        val key = cacheKey(sourceUrl.trim(), mappingFingerprint(channels))
        val entry = memoryCache[key] ?: return true
        return shouldRefresh(entry, nowMs)
    }

    /** Failures preserve the cached schedule, with a short retry delay instead of a source fallback. */
    fun refresh(
        sourceUrl: String,
        channels: List<Channel>,
        force: Boolean = false,
    ): EpgSnapshot {
        if (channels.isEmpty()) return EpgSnapshot(emptyMap())
        val source = sourceUrl.trim()
        val fingerprint = mappingFingerprint(channels)
        val key = cacheKey(source, fingerprint)
        val existing = cacheEntry(source, fingerprint)
        val nowMs = System.currentTimeMillis()
        if (!force && !shouldRefresh(existing, nowMs)) return existing.snapshot

        val refreshed = try {
            val schedules = download(source, channels, nowMs)
            if (!hasUsefulSchedule(schedules, nowMs)) {
                throw IOException("节目单没有可匹配的当前或后续排期")
            }
            val fetchedAtMs = System.currentTimeMillis()
            Log.i(TAG, "EPG 已更新 ${sourceLabel(source)}: ${schedules.size}/${channels.size} 频道，" +
                "${schedules.values.sumOf { it.size }} 个节目")
            CacheEntry(source, fingerprint, EpgSnapshot(schedules, fetchedAtMs), fetchedAtMs)
        } catch (error: Exception) {
            Log.i(TAG, "EPG 更新失败 ${sourceLabel(source)}: ${error.javaClass.simpleName} " +
                "${error.message.orEmpty().take(160)}；保留缓存")
            existing.copy(lastAttemptAtMs = System.currentTimeMillis(), failed = true)
        }
        remember(key, refreshed)
        persist(key, refreshed)
        return refreshed.snapshot
    }

    private fun shouldRefresh(entry: CacheEntry, nowMs: Long): Boolean {
        val sinceAttempt = nowMs - entry.lastAttemptAtMs
        if (entry.failed && sinceAttempt in 0 until RETRY_DELAY_MS) return false
        val sinceSuccess = nowMs - entry.snapshot.fetchedAtMs
        if (entry.snapshot.fetchedAtMs <= 0 || sinceSuccess !in 0 until REFRESH_INTERVAL_MS) return true
        // A six-hour fetch TTL does not turn expired programmes into valid ones.
        if (broadcastDay(nowMs) != broadcastDay(entry.snapshot.fetchedAtMs)) return true
        return !hasUsefulSchedule(entry.snapshot.schedules, nowMs)
    }

    private fun hasUsefulSchedule(schedules: Map<String, List<EpgProgramme>>, nowMs: Long): Boolean =
        schedules.values.any { programmes ->
            programmes.any { it.endMs > nowMs && it.startMs < nowMs + FUTURE_WINDOW_MS }
        }

    private fun cacheEntry(sourceUrl: String, fingerprint: String): CacheEntry {
        val key = cacheKey(sourceUrl, fingerprint)
        memoryCache[key]?.let { return it }
        val entry = runCatching {
            val envelope = cacheFile(key).openRead().use { input ->
                JSONObject(readLimited(input, MAX_CACHE_BYTES).toString(Charsets.UTF_8))
            }
            if (envelope.optInt("schema") != CACHE_SCHEMA ||
                envelope.getString("sourceUrl") != sourceUrl ||
                envelope.getString("mappingFingerprint") != fingerprint
            ) throw IOException("节目单缓存身份不匹配")
            val schedules = LinkedHashMap<String, List<EpgProgramme>>()
            val stored = envelope.getJSONObject("schedules")
            val keys = stored.keys()
            var programmeCount = 0
            while (keys.hasNext()) {
                val id = keys.next()
                val array = stored.getJSONArray(id)
                val programmes = ArrayList<EpgProgramme>(array.length())
                for (index in 0 until array.length()) {
                    if (++programmeCount > MAX_CACHED_PROGRAMMES) throw IOException("节目单缓存过大")
                    val value = array.getJSONObject(index)
                    val startMs = value.getLong("startMs")
                    val endMs = value.getLong("endMs")
                    val title = value.getString("title").take(MAX_TITLE_LENGTH).trim()
                    if (endMs > startMs && title.isNotEmpty()) {
                        programmes += EpgProgramme(startMs, endMs, title, sourceUrl)
                    }
                }
                if (programmes.isNotEmpty()) schedules[id] = programmes.sortedBy { it.startMs }
            }
            CacheEntry(
                sourceUrl,
                fingerprint,
                EpgSnapshot(schedules, envelope.getLong("fetchedAtMs")),
                envelope.optLong("lastAttemptAtMs"),
                envelope.optBoolean("failed"),
            )
        }.getOrElse { CacheEntry(sourceUrl, fingerprint, EpgSnapshot(emptyMap())) }
        remember(key, entry)
        return entry
    }

    private fun remember(key: String, entry: CacheEntry) {
        memoryCache[key] = entry
        // Changing sources does not need to keep every historical feed in memory.
        while (memoryCache.size > MAX_MEMORY_CACHES) {
            val oldest = memoryCache.entries.filter { it.key != key }
                .minByOrNull { it.value.lastAttemptAtMs } ?: break
            memoryCache.remove(oldest.key, oldest.value)
        }
    }

    private fun persist(key: String, entry: CacheEntry) {
        runCatching {
            if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
                throw IOException("无法创建节目单缓存目录")
            }
            val schedules = JSONObject()
            entry.snapshot.schedules.forEach { (id, programmes) ->
                val array = JSONArray()
                programmes.forEach { programme ->
                    array.put(JSONObject()
                        .put("startMs", programme.startMs)
                        .put("endMs", programme.endMs)
                        .put("title", programme.title.take(MAX_TITLE_LENGTH)))
                }
                schedules.put(id, array)
            }
            val envelope = JSONObject()
                .put("schema", CACHE_SCHEMA)
                .put("sourceUrl", entry.sourceUrl)
                .put("mappingFingerprint", entry.mappingFingerprint)
                .put("fetchedAtMs", entry.snapshot.fetchedAtMs)
                .put("lastAttemptAtMs", entry.lastAttemptAtMs)
                .put("failed", entry.failed)
                .put("schedules", schedules)
                .toString().toByteArray(Charsets.UTF_8)
            if (envelope.size > MAX_CACHE_BYTES) throw IOException("节目单缓存超过大小限制")
            val target = cacheFile(key)
            val output = target.startWrite()
            try {
                output.write(envelope)
                target.finishWrite(output)
            } catch (error: Exception) {
                target.failWrite(output)
                throw error
            }
        }.onFailure { error ->
            Log.i(TAG, "EPG 缓存保存失败: ${error.javaClass.simpleName}；内存排期仍可用")
        }
    }

    private fun download(
        sourceUrl: String,
        channels: List<Channel>,
        nowMs: Long,
    ): Map<String, List<EpgProgramme>> {
        var currentUrl = validateUrl(sourceUrl)
        val deadlineMs = SystemClock.elapsedRealtime() + NETWORK_BUDGET_MS
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (SystemClock.elapsedRealtime() >= deadlineMs) throw IOException("节目单请求超时")
            val connection = currentUrl.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "AstraTV/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/xml, text/xml, application/gzip, */*")
            // Inspect the actual bytes: some .gz endpoints serve plain XML, and vice versa.
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (hop == MAX_REDIRECTS) throw IOException("节目单重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("节目单重定向缺少目标地址")
                    currentUrl = validateUrl(URI(currentUrl.toString()).resolve(location).toString())
                } else {
                    if (status !in 200..299) throw IOException("节目单请求失败（HTTP $status）")
                    val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
                    if (length != null && length > MAX_DOWNLOAD_BYTES) {
                        throw IOException("节目单下载超过大小限制")
                    }
                    connection.inputStream.use { raw ->
                        val compressed = BufferedInputStream(
                            LimitedInputStream(raw, MAX_DOWNLOAD_BYTES, deadlineMs), BUFFER_BYTES)
                        compressed.mark(2)
                        val first = compressed.read()
                        val second = compressed.read()
                        compressed.reset()
                        val decoded = if (first == 0x1f && second == 0x8b) {
                            GZIPInputStream(compressed, BUFFER_BYTES)
                        } else compressed
                        LimitedInputStream(decoded, MAX_EXPANDED_BYTES, deadlineMs).use { xml ->
                            return XmlTvParser.parse(xml, channels, sourceUrl, nowMs)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("节目单重定向次数过多")
    }

    private fun validateUrl(value: String): URL {
        val uri = try { URI(value) } catch (error: Exception) {
            throw IOException("节目单地址格式无效", error)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            throw IOException("节目单必须使用 HTTP 或 HTTPS 地址")
        }
        return uri.toURL()
    }

    private fun mappingFingerprint(channels: List<Channel>): String = digest(
        channels.sortedBy { it.id }.joinToString("\u0000") { channel ->
            listOf(channel.id, channel.name, channel.epgId.orEmpty(), channel.epgName.orEmpty())
                .joinToString("\u0001")
        })

    private fun cacheKey(sourceUrl: String, fingerprint: String): String = digest("$sourceUrl\u0000$fingerprint")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun cacheFile(key: String): AtomicFile = AtomicFile(File(cacheDirectory, "$key.json"))

    private fun sourceLabel(sourceUrl: String): String = runCatching {
        val uri = URI(sourceUrl)
        (uri.host.orEmpty() + uri.path.orEmpty()).take(120)
    }.getOrDefault("自定义节目单")

    private fun broadcastDay(nowMs: Long): Long = (nowMs + BEIJING_OFFSET_MS) / DAY_MS

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return bytes.toByteArray()
            if (bytes.size() + count > limit) throw IOException("节目单缓存超过大小限制")
            bytes.write(buffer, 0, count)
        }
    }

    /** Limits both transfer/decompression sizes and slow trickle responses. */
    private class LimitedInputStream(
        input: InputStream,
        private val limit: Int,
        private val deadlineMs: Long,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            checkDeadline()
            val value = `in`.read()
            if (value >= 0) record(1)
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkDeadline()
            val result = `in`.read(bytes, offset, length)
            if (result > 0) record(result)
            return result
        }

        private fun record(length: Int) {
            count += length
            if (count > limit) throw IOException("节目单超过大小限制")
            checkDeadline()
        }

        private fun checkDeadline() {
            if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() >= deadlineMs) {
                throw IOException("节目单请求超时")
            }
        }
    }

    companion object {
        const val DEFAULT_URL = "http://epg.51zmt.top:8000/e.xml.gz"
        private const val TAG = "AstraTV"
        private const val CACHE_SCHEMA = 1
        private const val DAY_MS = 24L * 60 * 60 * 1_000
        private const val BEIJING_OFFSET_MS = 8L * 60 * 60 * 1_000
        private const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1_000
        private const val RETRY_DELAY_MS = 10L * 60 * 1_000
        private const val FUTURE_WINDOW_MS = 36L * 60 * 60 * 1_000
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 4_000
        private const val NETWORK_BUDGET_MS = 12_000L
        private const val MAX_REDIRECTS = 4
        private const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 24 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
        private const val MAX_CACHED_PROGRAMMES = 50_000
        private const val MAX_TITLE_LENGTH = 1_024
        private const val MAX_MEMORY_CACHES = 4
        private const val BUFFER_BYTES = 8_192
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
