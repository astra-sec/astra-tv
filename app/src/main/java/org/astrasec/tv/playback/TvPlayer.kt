package org.astrasec.tv.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import org.astrasec.tv.playlist.Channel

/** IPTV playback. All public methods are called from the activity's main thread. */
@UnstableApi
class TvPlayer(context: Context, private val listener: Listener) {
    data class DecoderChoice(val name: String, val label: String)

    interface Listener {
        fun onState(text: String, buffering: Boolean)
        fun onDecoderInfo(text: String)
        fun onVideoReady()
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var currentChannel: Channel? = null
    private var preferredVideoDecoder: String? = null
    private var released = false
    private var generation = 0L
    private var playerGeneration = 0L
    private var retries = 0
    private var reconnectTask: Runnable? = null
    private var bufferingWatchdog: Runnable? = null
    private var stablePlaybackTask: Runnable? = null
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var videoDimensions = ""
    private var firstFrameReported = false
    private val failedVideoDecoders = mutableSetOf<String>()
    private var rebuildOnReconnect = false

    init {
        createPlayer()
    }

    fun attach(view: PlayerView) {
        if (released) return
        playerView?.let { if (it !== view) it.player = null }
        playerView = view
        view.useController = false
        view.player = player
    }

    fun play(channel: Channel) {
        if (released) return
        generation++
        cancelTasks()
        retries = 0
        failedVideoDecoders.clear()
        rebuildOnReconnect = false
        currentChannel = channel
        resetDecoderInfo()
        prepareChannel(channel)
    }

    fun stop() {
        if (released) return
        generation++
        currentChannel = null
        cancelTasks()
        player?.stop()
        player?.clearMediaItems()
    }

    fun release() {
        if (released) return
        released = true
        generation++
        playerGeneration++
        currentChannel = null
        cancelTasks()
        playerView?.player = null
        playerView = null
        player?.release()
        player = null
    }

    /** The preference applies only when this codec can decode the current channel's video type. */
    fun setPreferredVideoDecoder(name: String?) {
        if (released || preferredVideoDecoder == name) return
        preferredVideoDecoder = name
        generation++
        cancelTasks()
        retries = 0
        failedVideoDecoders.clear()
        rebuildOnReconnect = false
        recreatePlayer()
        currentChannel?.let { prepareChannel(it) }
    }

    /** Uses Media3's codec workarounds, including its hardware approximation on older Android. */
    fun availableVideoDecoders(): List<DecoderChoice> {
        data class Available(val info: MediaCodecInfo, val formats: MutableList<String>)
        val available = linkedMapOf<String, Available>()
        for ((mime, label) in listOf(
            MimeTypes.VIDEO_H264 to "H.264",
            MimeTypes.VIDEO_H265 to "HEVC",
            MimeTypes.VIDEO_MPEG2 to "MPEG-2"
        )) {
            val infos = try {
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false)
            } catch (error: Exception) {
                Log.w(TAG, "Could not query $mime decoders", error)
                emptyList()
            }
            for (info in infos) {
                val item = available.getOrPut(info.name) { Available(info, mutableListOf()) }
                if (label !in item.formats) item.formats += label
            }
        }
        return available.values.sortedByDescending { it.info.hardwareAccelerated }.map {
            DecoderChoice(
                name = it.info.name,
                label = "${it.info.name}  ·  ${if (it.info.hardwareAccelerated) "硬件" else "软件"}  ·  ${it.formats.joinToString(" / ")}"
            )
        }
    }

    private fun createPlayer() {
        val instanceGeneration = ++playerGeneration
        val codecSelector = MediaCodecSelector { mime, secure, tunneled ->
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneled)
            if (!MimeTypes.isVideo(mime)) {
                infos
            } else {
                // Initialization fallback alone does not recover a codec that fails after playing.
                // Retry with a remaining system codec before trying a failed codec again.
                val remaining = infos.filterNot { it.name in failedVideoDecoders }
                val candidates = remaining.ifEmpty { infos }
                // Stable sorting preserves the device/Media3 preference within each priority.
                candidates.sortedWith(compareByDescending<MediaCodecInfo> {
                    it.name == preferredVideoDecoder
                }.thenByDescending { it.hardwareAccelerated })
            }
        }
        val renderers = DefaultRenderersFactory(appContext)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 6_000, 350, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()
        val instance = ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = instance
        playerView?.player = instance

        fun active(): Boolean = !released && playerGeneration == instanceGeneration && currentChannel != null

        instance.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!active()) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        listener.onState("正在缓冲…", true)
                        armBufferingWatchdog()
                        cancelStablePlaybackTask()
                    }
                    Player.STATE_READY -> {
                        if (firstFrameReported) {
                            cancelWatchdog()
                            listener.onState("正在播放", false)
                            armStablePlaybackReset()
                        } else {
                            // Audio can be ready while a faulty video codec produces no picture.
                            listener.onState("正在等待视频画面…", true)
                            armBufferingWatchdog()
                        }
                    }
                    Player.STATE_ENDED -> scheduleReconnect("直播连接已结束")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!active()) return
                Log.w(TAG, "Playback failed (${error.errorCodeName})", error)
                val failedCodec = quarantineFailedVideoCodec(error)
                scheduleReconnect(if (failedCodec) "视频解码器异常，正在尝试其他解码器" else errorText(error))
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!active()) return
                videoDimensions = if (videoSize.width > 0) "${videoSize.width} × ${videoSize.height}" else ""
                reportDecoderInfo()
            }

            override fun onRenderedFirstFrame() {
                if (!active()) return
                if (!firstFrameReported) {
                    firstFrameReported = true
                    cancelWatchdog()
                    listener.onState("正在播放", false)
                    armStablePlaybackReset()
                    listener.onVideoReady()
                }
            }
        })
        instance.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (!active()) return
                videoDecoder = decoderName
                reportDecoderInfo()
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (!active()) return
                audioDecoder = decoderName
                reportDecoderInfo()
            }
        })
    }

    private fun recreatePlayer() {
        playerGeneration++
        playerView?.player = null
        player?.release()
        player = null
        resetDecoderInfo()
        createPlayer()
    }

    private fun quarantineFailedVideoCodec(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
            error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
        val playbackError = error as? ExoPlaybackException ?: return false
        if (playbackError.type != ExoPlaybackException.TYPE_RENDERER) return false
        // The same error codes are also used by audio renderers. Never quarantine video for audio.
        if (!MimeTypes.isVideo(playbackError.rendererFormat?.sampleMimeType) &&
            playbackError.rendererName?.contains("Video") != true) return false
        var cause: Throwable? = playbackError.cause
        var failingInfo: MediaCodecInfo? = null
        repeat(8) {
            when (val current = cause) {
                is MediaCodecDecoderException -> failingInfo = current.codecInfo
                is MediaCodecRenderer.DecoderInitializationException -> failingInfo = current.codecInfo
            }
            if (failingInfo == null) cause = cause?.cause
        }
        val name = failingInfo?.name ?: videoDecoder.takeIf { it.isNotBlank() } ?: return false
        return quarantineVideoDecoder(name)
    }

    private fun quarantineVideoDecoder(name: String): Boolean {
        if (!failedVideoDecoders.add(name)) return false
        rebuildOnReconnect = true
        Log.w(TAG, "Quarantined video decoder for this channel: $name")
        return true
    }

    private fun prepareChannel(channel: Channel) {
        val instance = player ?: return
        val headers = channel.headers.toMutableMap()
        val userAgentKey = headers.keys.firstOrNull { it.equals("User-Agent", ignoreCase = true) }
        val userAgent = userAgentKey?.let { headers.remove(it) } ?: "AstraTV/${org.astrasec.tv.BuildConfig.VERSION_NAME} (Android TV)"
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)
        val extractors = DefaultExtractorsFactory().setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
        val mediaItem = MediaItem.Builder()
            .setMediaId(channel.url)
            .setUri(channel.url)
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
            .build()
        val source = ProgressiveMediaSource.Factory(dataSource, extractors)
            // Fail over to a new live connection rather than retrying an old byte range forever.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
            .setContinueLoadingCheckIntervalBytes(64 * 1024)
            .createMediaSource(mediaItem)
        listener.onState("正在连接 ${channel.name}…", true)
        instance.setMediaSource(source)
        instance.prepare()
        instance.playWhenReady = true
        armBufferingWatchdog()
    }

    private fun scheduleReconnect(reason: String) {
        if (released || currentChannel == null || reconnectTask != null) return
        cancelWatchdog()
        cancelStablePlaybackTask()
        val instance = player ?: return
        // Close a stalled HTTP request and release decoder work while the retry timer runs.
        instance.stop()
        if (retries >= MAX_RETRIES) {
            listener.onState("$reason。已重试 $MAX_RETRIES 次，请换台或重新选择当前频道。", false)
            return
        }
        retries++
        val delayMs = minOf(15_000L, 1_000L shl (retries - 1))
        val expectedGeneration = generation
        listener.onState("$reason · ${delayMs / 1_000} 秒后重连（$retries/$MAX_RETRIES）", true)
        val task = Runnable {
            reconnectTask = null
            if (!released && generation == expectedGeneration) {
                firstFrameReported = false
                if (rebuildOnReconnect) {
                    rebuildOnReconnect = false
                    // A fresh renderer guarantees its cached candidate list is discarded.
                    recreatePlayer()
                }
                currentChannel?.let { prepareChannel(it) }
            }
        }
        reconnectTask = task
        handler.postDelayed(task, delayMs)
    }

    private fun armBufferingWatchdog() {
        cancelWatchdog()
        val expectedGeneration = generation
        val task = Runnable {
            bufferingWatchdog = null
            if (!released && generation == expectedGeneration) {
                when (player?.playbackState) {
                    Player.STATE_BUFFERING -> scheduleReconnect("连接长时间未收到可播放数据")
                    Player.STATE_READY -> if (!firstFrameReported) {
                        if (videoDecoder.isNotBlank()) quarantineVideoDecoder(videoDecoder)
                        scheduleReconnect("视频解码器长时间未输出画面")
                    }
                }
            }
        }
        bufferingWatchdog = task
        handler.postDelayed(task, 15_000)
    }

    private fun armStablePlaybackReset() {
        cancelStablePlaybackTask()
        val expectedGeneration = generation
        val task = Runnable {
            stablePlaybackTask = null
            if (!released && generation == expectedGeneration && firstFrameReported &&
                player?.playbackState == Player.STATE_READY) {
                retries = 0
            }
        }
        stablePlaybackTask = task
        handler.postDelayed(task, 30_000)
    }

    private fun resetDecoderInfo() {
        videoDecoder = ""
        audioDecoder = ""
        videoDimensions = ""
        firstFrameReported = false
        listener.onDecoderInfo("等待解码器…")
    }

    private fun reportDecoderInfo() {
        val details = mutableListOf<String>()
        if (videoDecoder.isNotEmpty()) details += "视频：$videoDecoder"
        if (videoDimensions.isNotEmpty()) details += videoDimensions
        if (audioDecoder.isNotEmpty()) details += "音频：$audioDecoder"
        listener.onDecoderInfo(details.joinToString("  ·  ").ifEmpty { "等待解码器…" })
    }

    private fun cancelWatchdog() {
        bufferingWatchdog?.let(handler::removeCallbacks)
        bufferingWatchdog = null
    }

    private fun cancelStablePlaybackTask() {
        stablePlaybackTask?.let(handler::removeCallbacks)
        stablePlaybackTask = null
    }

    private fun cancelTasks() {
        reconnectTask?.let(handler::removeCallbacks)
        reconnectTask = null
        cancelWatchdog()
        cancelStablePlaybackTask()
    }

    private fun errorText(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "无法连接频道，请检查电视与直播服务器的网络"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "频道连接超时"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "频道服务器返回错误"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败，可在设置中选择其他解码器"
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "视频解码失败，可在设置中选择其他解码器"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "电视不支持此频道的编码格式"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "频道数据格式暂不支持"
        else -> "播放失败（${error.errorCode}）"
    }

    private companion object {
        const val TAG = "AstraTvPlayer"
        const val MAX_RETRIES = 5
    }
}
