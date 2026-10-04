package org.astrasec.tv

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.astrasec.tv.epg.EpgProgramme
import org.astrasec.tv.epg.EpgRepository
import org.astrasec.tv.epg.EpgSnapshot
import org.astrasec.tv.playback.TvPlayer
import org.astrasec.tv.playlist.Channel
import org.astrasec.tv.playlist.PlaylistRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.net.URI
import java.util.concurrent.Executors

/** A small TV-first IPTV client. All app behavior is implemented in Kotlin. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity(), TvPlayer.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val epgIo = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var repository: PlaylistRepository
    private lateinit var epgRepository: EpgRepository
    private lateinit var video: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var channelList: ListView
    private lateinit var settingsList: ListView
    private lateinit var channelButton: Button
    private lateinit var settingsButton: Button
    private lateinit var countText: TextView
    private lateinit var navigationHint: TextView
    private lateinit var info: LinearLayout
    private lateinit var channelText: TextView
    private lateinit var programmeText: TextView
    private lateinit var decoderText: TextView
    private lateinit var stateText: TextView
    private lateinit var loading: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var clock: TextView
    private lateinit var adapter: ChannelAdapter
    private lateinit var settingsAdapter: SettingsAdapter
    private var setupView: View? = null
    private var setupSourceInput: EditText? = null
    private var setupEpgInput: EditText? = null
    private enum class MenuPage { CHANNELS, SETTINGS }
    private var menuPage = MenuPage.CHANNELS
    private var channelSummary = "正在读取频道…"
    private val settingsLabels = listOf("视频解码器", "画面比例", "刷新频道列表", "播放列表地址", "节目单地址", "开源许可")
    private var tvPlayer: TvPlayer? = null
    private var channels = emptyList<Channel>()
    private var current: Channel? = null
    private var source = PlaylistRepository.DEFAULT_URL
    private var epgSource = EpgRepository.DEFAULT_URL
    private var epgSnapshot = EpgSnapshot(emptyMap())
    private var epgFingerprint = ""
    private var epgGeneration = 0
    private var epgRefreshing = false
    private val programmeTime = SimpleDateFormat("HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }
    private var active = false
    private var panelOpen = true
    private var refreshing = false
    private var channelListState: Parcelable? = null
    private var settingsListState: Parcelable? = null
    private var listFocusGeneration = 0
    private var digits = ""
    private var lastBack = 0L
    private var sourceGeneration = 0
    private var autoCloseOnFirstFrame = true
    private val hideInfo = Runnable { if (!panelOpen) info.visibility = View.GONE }
    private val commitDigits = Runnable {
        val value = digits.trimStart('0').ifEmpty { "0" }
        digits = ""
        val found = channels.firstOrNull { it.number.trimStart('0').ifEmpty { "0" } == value }
        if (found != null) { play(found); showPanel(false) }
        else { Toast.makeText(this, "没有频道 $value", Toast.LENGTH_SHORT).show(); updateChannelTitle(); showInfo() }
    }
    private val updateClock = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
            main.postDelayed(this, 30_000)
        }
    }
    private val programmeBoundary = Runnable { updateProgrammeUi() }
    private val refreshEpg = object : Runnable {
        override fun run() {
            if (!active) return
            requestEpg()
            main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(getString(R.string.app_name)))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        prefs = getSharedPreferences("tv", MODE_PRIVATE)
        source = prefs.getString("source", PlaylistRepository.DEFAULT_URL).orEmpty().trim()
        repository = PlaylistRepository(this)
        epgSource = prefs.getString("epgSource", EpgRepository.DEFAULT_URL)?.trim() ?: EpgRepository.DEFAULT_URL
        epgRepository = EpgRepository(this)
        buildUi()
        if (source.isBlank()) {
            showFirstSetup(savedInstanceState)
        } else {
            applyChannels(repository.cached(source))
            refreshPlaylist()
        }
        main.post(updateClock)
    }

    override fun onStart() {
        super.onStart()
        active = true
        if (setupView == null && source.isNotBlank()) startPlayback()
    }

    private fun startPlayback() {
        if (!active || setupView != null || source.isBlank()) return
        tvPlayer = TvPlayer(this, this).also {
            it.attach(video)
            it.setPreferredVideoDecoder(prefs.getString("decoder", null))
        }
        current?.let { play(it) } ?: channels.firstOrNull()?.let { play(it) }
        updateProgrammeUi()
        main.post(refreshEpg)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        setupSourceInput?.let { outState.putString("setupSource", it.text.toString()) }
        setupEpgInput?.let { outState.putString("setupEpg", it.text.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        active = false
        main.removeCallbacks(hideInfo)
        main.removeCallbacks(commitDigits)
        main.removeCallbacks(refreshEpg)
        main.removeCallbacks(programmeBoundary)
        digits = ""
        tvPlayer?.release()
        tvPlayer = null
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        epgGeneration++
        epgIo.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        video = PlayerView(this).apply {
            useController = false
            keepScreenOn = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            resizeMode = prefs.getInt("aspect", AspectRatioFrameLayout.RESIZE_MODE_FIT)
            setOnClickListener { showPanel(!panelOpen) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = rounded(0xE6111C2B.toInt(), 16)
        }
        spinner = ProgressBar(this)
        loading.addView(spinner, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER })
        loadingText = text("正在连接直播…", 16).apply { setPadding(0, dp(12), 0, 0); gravity = Gravity.CENTER }
        loading.addView(loadingText)
        root.addView(loading, FrameLayout.LayoutParams(dp(360), -2, Gravity.CENTER))

        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(16), dp(26), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF5070B12.toInt(), 0xB0070B12.toInt(), Color.TRANSPARENT))
        }
        channelText = text(getString(R.string.app_name), 25, bold = true)
        programmeText = text("暂无节目单", 18, 0xFFE1EBF5.toInt()).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(3))
        }
        stateText = text("", 14, 0xFF32D5BB.toInt())
        decoderText = text("", 12, 0xFF9CAEC4.toInt())
        info.addView(channelText); info.addView(programmeText); info.addView(stateText); info.addView(decoderText)
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(14))
            setBackgroundColor(0xF50E1724.toInt())
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(text(getString(R.string.app_name), 26, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        clock = text("", 18, 0xFFB6C6D9.toInt())
        titleRow.addView(clock)
        panel.addView(titleRow)
        countText = text(channelSummary, 12, 0xFF96A8C0.toInt()).apply { setPadding(0, dp(4), 0, dp(12)) }
        panel.addView(countText)
        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelButton = button("频道") { selectMenuPage(MenuPage.CHANNELS); focusMenuContent() }.apply {
            id = R.id.navigation_channels
            isActivated = true
        }
        settingsButton = button("设置") { selectMenuPage(MenuPage.SETTINGS); focusMenuContent() }.apply { id = R.id.navigation_settings }
        navigation.addView(channelButton, LinearLayout.LayoutParams(-1, dp(48)))
        navigation.addView(settingsButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        content.addView(navigation, LinearLayout.LayoutParams(dp(84), -1))
        channelList = menuList(R.id.channel_list, channelButton.id)
        settingsList = menuList(R.id.settings_list, settingsButton.id).apply { visibility = View.GONE }
        channelButton.nextFocusUpId = channelButton.id
        channelButton.nextFocusDownId = settingsButton.id
        settingsButton.nextFocusUpId = channelButton.id
        settingsButton.nextFocusDownId = settingsButton.id
        for (item in listOf(channelButton, settingsButton)) {
            item.nextFocusLeftId = item.id
            item.nextFocusRightId = channelList.id
        }
        adapter = ChannelAdapter()
        channelList.adapter = adapter
        channelList.setOnItemClickListener { _, _, position, _ ->
            channels.getOrNull(position)?.let { play(it); showPanel(false) }
        }
        settingsAdapter = SettingsAdapter()
        settingsList.adapter = settingsAdapter
        settingsList.setOnItemClickListener { _, _, position, _ -> openSetting(position) }
        val contentPages = FrameLayout(this).apply {
            addView(channelList, FrameLayout.LayoutParams(-1, -1))
            addView(settingsList, FrameLayout.LayoutParams(-1, -1))
        }
        content.addView(contentPages, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(12) })
        panel.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        navigationHint = text("", 11, 0xFF8FA2BD.toInt()).apply { setPadding(0, dp(10), 0, 0) }
        panel.addView(navigationHint)
        root.addView(panel, FrameLayout.LayoutParams(dp(460), -1, Gravity.START))
        updatePanelLabels()
        channelButton.setOnFocusChangeListener { _, focused -> if (focused) selectMenuPage(MenuPage.CHANNELS) }
        settingsButton.setOnFocusChangeListener { _, focused -> if (focused) selectMenuPage(MenuPage.SETTINGS) }
        setContentView(root)
    }

    private fun validSourceUrl(value: String): Boolean = runCatching { URI(value) }.getOrNull()?.let {
        (it.scheme.equals("http", true) || it.scheme.equals("https", true)) && !it.host.isNullOrBlank()
    } == true

    private fun sourceInput(value: String, hintText: String) = EditText(this).apply {
        setSingleLine()
        setText(value)
        hint = hintText
        textSize = 17f
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF8FA2BD.toInt())
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun showFirstSetup(savedState: Bundle?) {
        panelOpen = false
        panel.visibility = View.GONE
        info.visibility = View.GONE
        loading.visibility = View.GONE
        autoCloseOnFirstFrame = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val screen = ScrollView(this).apply {
            id = R.id.first_setup
            isFillViewport = true
            isClickable = true
            setBackgroundColor(0xFF0E1724.toInt())
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(getString(R.string.app_name), 30, bold = true))
            addView(text("首次设置", 21).apply { setPadding(0, dp(8), 0, dp(6)) })
            addView(text("填写直播源后开始观看，之后可在设置中修改。", 14, 0xFFB6C6D9.toInt()))
            addView(text("直播源地址 · M3U", 15).apply { setPadding(0, dp(20), 0, dp(4)) })
        }
        val liveInput = sourceInput(savedState?.getString("setupSource") ?: source, "http:// 或 https://").apply {
            id = R.id.setup_source
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        }
        val epgInput = sourceInput(savedState?.getString("setupEpg") ?: epgSource, "可留空，不影响直播").apply {
            id = R.id.setup_epg
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        form.addView(liveInput, LinearLayout.LayoutParams(-1, dp(48)))
        form.addView(text("节目单地址 · XMLTV（可选）", 15).apply { setPadding(0, dp(14), 0, dp(4)) })
        form.addView(epgInput, LinearLayout.LayoutParams(-1, dp(48)))
        form.addView(text("支持 XML 或 XML.gz；保留默认地址即可使用现有节目单。", 12, 0xFF96A8C0.toInt()).apply {
            setPadding(0, dp(6), 0, 0)
        })
        val errorText = text("", 13, 0xFFFF9A9A.toInt()).apply {
            id = R.id.setup_error
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        form.addView(errorText)
        val save = button("保存并开始观看") {
            val liveValue = liveInput.text.toString().trim()
            val epgValue = epgInput.text.toString().trim()
            when {
                !validSourceUrl(liveValue) -> {
                    errorText.text = "请输入有效的 HTTP 或 HTTPS 直播源地址"
                    errorText.visibility = View.VISIBLE
                    liveInput.requestFocus()
                }
                epgValue.isNotEmpty() && !validSourceUrl(epgValue) -> {
                    errorText.text = "请输入有效的 HTTP 或 HTTPS 节目单地址，或留空"
                    errorText.visibility = View.VISIBLE
                    epgInput.requestFocus()
                }
                else -> {
                    source = liveValue
                    epgSource = epgValue
                    sourceGeneration++
                    epgGeneration++
                    prefs.edit().putString("source", source).putString("epgSource", epgSource).apply()
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(liveInput.windowToken, 0)
                    root.removeView(screen)
                    setupView = null
                    setupSourceInput = null
                    setupEpgInput = null
                    panelOpen = true
                    panel.visibility = View.VISIBLE
                    loadingText.text = "正在读取频道…"
                    spinner.visibility = View.VISIBLE
                    loading.visibility = View.VISIBLE
                    autoCloseOnFirstFrame = true
                    settingsAdapter.notifyDataSetChanged()
                    startPlayback()
                    applyChannels(repository.cached(source))
                    if (!channelList.requestFocus()) channelButton.requestFocus()
                    refreshPlaylist()
                }
            }
        }.apply { id = R.id.setup_save }
        form.addView(save, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
        liveInput.nextFocusUpId = liveInput.id
        liveInput.nextFocusDownId = epgInput.id
        epgInput.nextFocusUpId = liveInput.id
        epgInput.nextFocusDownId = save.id
        save.nextFocusUpId = epgInput.id
        save.nextFocusDownId = save.id
        for ((input, up, down) in listOf(
            Triple(liveInput, liveInput, epgInput),
            Triple(epgInput, liveInput, save),
        )) {
            input.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) up.requestFocus() else down.requestFocus()
                    true
                } else false
            }
        }
        liveInput.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { epgInput.requestFocus(); true } else false
        }
        epgInput.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { save.performClick(); true } else false
        }
        val width = dp(640).coerceAtMost(resources.displayMetrics.widthPixels - dp(48))
        center.addView(form, LinearLayout.LayoutParams(width, -2))
        screen.addView(center, FrameLayout.LayoutParams(-1, -2))
        setupView = screen
        setupSourceInput = liveInput
        setupEpgInput = epgInput
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        liveInput.requestFocus()
    }

    private fun menuList(listId: Int, navigationId: Int) = ListView(this).apply {
        id = listId
        divider = null
        dividerHeight = 0
        choiceMode = ListView.CHOICE_MODE_NONE
        setPadding(0, 0, 0, dp(6))
        clipToPadding = false
        setSelector(android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0xFF1F645E.toInt(), 7))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFF1F645E.toInt(), 7))
            addState(intArrayOf(), rounded(Color.TRANSPARENT, 7))
        })
        isFocusable = true
        isFocusableInTouchMode = true
        nextFocusUpId = id
        nextFocusDownId = id
        nextFocusLeftId = navigationId
        nextFocusRightId = id
    }

    private fun text(value: String, size: Int, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 16f; isAllCaps = false
        setTextColor(Color.WHITE); setPadding(dp(10), 0, dp(10), 0)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0xFF276E66.toInt(), 7))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFF276E66.toInt(), 7))
            addState(intArrayOf(android.R.attr.state_activated), rounded(0xFF243B52.toInt(), 7))
            addState(intArrayOf(), rounded(0xFF192A3D.toInt(), 7))
        }
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun key(c: Channel) = "${c.id}:${c.number}"

    private fun refreshPlaylist() {
        if (refreshing || source.isBlank() || setupView != null) return
        refreshing = true
        if (channels.isEmpty()) {
            loadingText.text = "正在读取频道…"
            spinner.visibility = View.VISIBLE
            loading.visibility = View.VISIBLE
        }
        settingsAdapter.notifyDataSetChanged()
        val requestedSource = source
        val generation = sourceGeneration
        io.execute {
            val result = runCatching { repository.refresh(requestedSource) }
            main.post {
                if (isDestroyed) return@post
                refreshing = false
                settingsAdapter.notifyDataSetChanged()
                if (generation != sourceGeneration) { refreshPlaylist(); return@post }
                result.onSuccess { applyChannels(it) }.onFailure {
                    if (channels.isEmpty()) {
                        spinner.visibility = View.GONE
                        loading.visibility = View.VISIBLE
                        loadingText.text = "无法读取频道列表\n请检查网络或在设置中重试"
                        showPanel(true)
                    } else {
                        channelSummary = "${channels.size} 个频道 · 已用本地列表"
                        updatePanelLabels()
                    }
                    android.util.Log.w("AstraTV", "Playlist refresh failed", it)
                }
            }
        }
    }
    private fun applyChannels(list: List<Channel>) {
        if (list.isEmpty()) {
            channelSummary = "${channels.size} 个频道"
            updatePanelLabels()
            adapter.notifyDataSetChanged()
            requestEpg()
            return
        }
        val previous = current
        channels = list
        current = list.firstOrNull { previous != null && key(it) == key(previous) }
            ?: list.firstOrNull { key(it) == prefs.getString("lastChannel", "") }
            ?: list.first()
        channelSummary = "${channels.size} 个频道"
        updatePanelLabels()
        adapter.notifyDataSetChanged()
        requestEpg()
        if (active && (previous == null || previous.url != current?.url)) current?.let { play(it) }
    }
    private fun play(c: Channel) {
        main.removeCallbacks(commitDigits)
        digits = ""
        current = c
        // A background refresh/reconnect must not dismiss a menu the user opened.
        autoCloseOnFirstFrame = !panelOpen || (autoCloseOnFirstFrame && menuPage == MenuPage.CHANNELS)
        prefs.edit().putString("lastChannel", key(c)).apply()
        updateChannelTitle()
        decoderText.text = ""
        stateText.text = "正在连接直播"
        showInfo(0)
        adapter.notifyDataSetChanged()
        tvPlayer?.play(c)
    }
    private fun updateChannelTitle() {
        current?.let { c -> channelText.text = "${c.number.padStart(3, '0')}  ${c.name}" }
        updateProgrammeUi()
    }

    /** Network and disk work stay off the playback/UI thread. */
    private fun requestEpg(force: Boolean = false) {
        if (isDestroyed) return
        if (channels.isEmpty() || epgSource.isBlank() || setupView != null) {
            epgGeneration++
            epgFingerprint = ""
            epgRefreshing = false
            epgSnapshot = EpgSnapshot(emptyMap())
            updateProgrammeUi()
            return
        }
        val requestedChannels = channels.toList()
        val requestedSource = epgSource
        val fingerprint = requestedSource + "\n" + requestedChannels.joinToString("\n") {
            "${it.id}\t${it.name}\t${it.epgId}\t${it.epgName}"
        }
        if (!force && fingerprint == epgFingerprint && epgRefreshing) return
        val changed = fingerprint != epgFingerprint
        val generation = ++epgGeneration
        epgFingerprint = fingerprint
        epgRefreshing = true
        if (changed) {
            epgSnapshot = EpgSnapshot(emptyMap())
            updateProgrammeUi()
        }
        epgIo.execute {
            val cached = runCatching { epgRepository.cached(requestedSource, requestedChannels) }
                .getOrDefault(EpgSnapshot(emptyMap()))
            main.post {
                if (!isDestroyed && generation == epgGeneration && cached.schedules.isNotEmpty()) {
                    epgSnapshot = cached
                    updateProgrammeUi()
                }
            }
            val result = runCatching {
                if (force || epgRepository.needsRefresh(requestedSource, requestedChannels)) {
                    epgRepository.refresh(requestedSource, requestedChannels, force)
                } else cached
            }
            main.post {
                if (isDestroyed || generation != epgGeneration) return@post
                epgRefreshing = false
                result.onSuccess { epgSnapshot = it }.onFailure {
                    android.util.Log.w("AstraTV", "EPG refresh failed", it)
                }
                updateProgrammeUi()
            }
        }
    }

    private fun programmeLabel(programme: EpgProgramme?): String = programme?.let {
        "${programmeTime.format(Date(it.startMs))}–${programmeTime.format(Date(it.endMs))}  ${it.title}"
    } ?: "暂无节目单"

    private fun updateProgrammeUi() {
        val now = System.currentTimeMillis()
        programmeText.text = programmeLabel(current?.let { epgSnapshot.current(it, now) })
        // Updating visible rows keeps the current cursor and scroll position intact.
        for (position in 0 until channelList.childCount) {
            val channel = channels.getOrNull(channelList.firstVisiblePosition + position) ?: continue
            val row = channelList.getChildAt(position) as? LinearLayout ?: continue
            val titleColumn = row.getChildAt(0) as? LinearLayout ?: continue
            val label = titleColumn.getChildAt(1) as? TextView ?: continue
            label.text = programmeLabel(epgSnapshot.current(channel, now))
            val playing = current?.let(::key) == key(channel)
            row.contentDescription = "频道 ${channel.number} ${channel.name}，${label.text}${if (playing) "，正在播放" else ""}"
        }
        main.removeCallbacks(programmeBoundary)
        if (active) {
            // Refresh labels at actual programme boundaries, also coping with clock changes.
            val boundary = epgSnapshot.schedules.values.asSequence().flatMap { it.asSequence() }
                .flatMap { sequenceOf(it.startMs, it.endMs) }.filter { it > now }.minOrNull()
            main.postDelayed(programmeBoundary, ((boundary ?: (now + 60_000)) - now).coerceIn(50, 60_000))
        }
    }
    private fun zap(offset: Int) {
        if (channels.isEmpty()) return
        val index = channels.indexOfFirst { key(it) == current?.let(::key) }.coerceAtLeast(0)
        play(channels[(index + offset + channels.size) % channels.size])
    }
    private fun focusNavigation() {
        saveMenuListState()
        listFocusGeneration++
        if (menuPage == MenuPage.CHANNELS) channelButton.requestFocus() else settingsButton.requestFocus()
    }
    private fun saveMenuListState() {
        if (menuPage == MenuPage.CHANNELS) channelListState = channelList.onSaveInstanceState()
        else settingsListState = settingsList.onSaveInstanceState()
    }
    private fun selectMenuPage(page: MenuPage) {
        if (menuPage != page) {
            saveMenuListState()
            listFocusGeneration++
            autoCloseOnFirstFrame = false
            menuPage = page
            if (page == MenuPage.SETTINGS) settingsAdapter.notifyDataSetChanged()
        }
        channelList.visibility = if (page == MenuPage.CHANNELS) View.VISIBLE else View.GONE
        settingsList.visibility = if (page == MenuPage.SETTINGS) View.VISIBLE else View.GONE
        channelButton.isActivated = page == MenuPage.CHANNELS
        settingsButton.isActivated = page == MenuPage.SETTINGS
        val contentId = if (page == MenuPage.CHANNELS) channelList.id else settingsList.id
        channelButton.nextFocusRightId = contentId
        settingsButton.nextFocusRightId = contentId
        updatePanelLabels()
        // Restore the hidden page without moving focus out of the sidebar.
        val list = if (page == MenuPage.CHANNELS) channelList else settingsList
        val savedState = if (page == MenuPage.CHANNELS) channelListState else settingsListState
        val generation = listFocusGeneration
        if (savedState != null) list.post {
            if (panelOpen && menuPage == page && generation == listFocusGeneration) list.onRestoreInstanceState(savedState)
        }
    }
    private fun updatePanelLabels() {
        countText.text = if (menuPage == MenuPage.CHANNELS) channelSummary else "播放设置"
        navigationHint.text = if (menuPage == MenuPage.CHANNELS) {
            "左键：功能栏  ·  右键：频道 / 全屏\n确定：播放  ·  返回：关闭菜单"
        } else {
            "左键：功能栏  ·  右键：设置 / 全屏\n确定：打开选项  ·  返回：关闭菜单"
        }
    }
    private fun focusMenuContent() {
        autoCloseOnFirstFrame = false
        if (menuPage == MenuPage.CHANNELS) {
            focusChannels(restore = true)
            return
        }
        val generation = ++listFocusGeneration
        val savedState = settingsListState
        settingsList.requestFocus()
        settingsList.post {
            if (panelOpen && menuPage == MenuPage.SETTINGS && settingsList.hasFocus() && generation == listFocusGeneration) {
                if (savedState != null) settingsList.onRestoreInstanceState(savedState)
                else settingsList.setSelection(0)
            }
        }
    }
    private fun focusChannels(restore: Boolean) {
        val generation = ++listFocusGeneration
        val savedState = if (restore) channelListState else null
        val index = channels.indexOfFirst { key(it) == current?.let(::key) }.coerceAtLeast(0)
        channelList.requestFocus()
        // Restore after ListView's focus/layout pass, which can otherwise select its first row.
        channelList.post {
            if (panelOpen && menuPage == MenuPage.CHANNELS && channelList.hasFocus() && generation == listFocusGeneration) {
                if (savedState != null) channelList.onRestoreInstanceState(savedState)
                else if (channels.isNotEmpty()) channelList.setSelection(index)
            }
        }
    }
    private fun showPanel(show: Boolean) {
        if (setupView != null) return
        val wasOpen = panelOpen
        if (!show && wasOpen) saveMenuListState()
        panelOpen = show
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            autoCloseOnFirstFrame = false
            if (!wasOpen) {
                selectMenuPage(MenuPage.CHANNELS)
                channelListState = null
                focusChannels(restore = false)
            }
            info.visibility = View.GONE
        } else { listFocusGeneration++; root.requestFocus(); showInfo() }
    }
    private fun showInfo(duration: Long = 4500) {
        main.removeCallbacks(hideInfo)
        info.visibility = if (panelOpen) View.GONE else View.VISIBLE
        if (duration > 0) main.postDelayed(hideInfo, duration)
    }

    private fun openSetting(index: Int) {
        when (index) {
            0 -> chooseDecoder()
            1 -> chooseAspect()
            2 -> { refreshPlaylist(); Toast.makeText(this, "正在刷新频道", Toast.LENGTH_SHORT).show() }
            3 -> editSource()
            4 -> editEpgSource()
            5 -> AlertDialog.Builder(this).setTitle("开源许可").setMessage("${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}\n\n本应用由 Kotlin 实现，源码随安装包提供。\n\nAndroidX Media3：Apache 2.0\nFFmpeg 音频解码：LGPL 2.1 or later\n\n对应源码、构建脚本和许可证见随附项目的 third_party、ffmpeg-audio 与 NOTICE。视频默认使用设备提供的解码器。").setPositiveButton("确定", null).show()
        }
    }
    private fun chooseDecoder() {
        val choices = tvPlayer?.availableVideoDecoders().orEmpty()
        val names = listOf("自动 · 优先硬件解码") + choices.map { it.label }
        val preferred = prefs.getString("decoder", null)
        val selected = if (preferred == null) 0 else choices.indexOfFirst { it.name == preferred } + 1
        AlertDialog.Builder(this).setTitle("视频解码器").setSingleChoiceItems(names.toTypedArray(), selected.coerceAtLeast(0)) { dialog, index ->
            val name = if (index == 0) null else choices[index-1].name
            prefs.edit().putString("decoder", name).apply()
            tvPlayer?.setPreferredVideoDecoder(name)
            dialog.dismiss()
            settingsAdapter.notifyDataSetChanged()
        }.setNegativeButton("取消", null).show()
    }
    private fun chooseAspect() {
        val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
        val labels = arrayOf("保持比例", "铺满屏幕", "裁剪铺满")
        AlertDialog.Builder(this).setTitle("画面比例").setSingleChoiceItems(labels, modes.indexOf(video.resizeMode)) { dialog, index ->
            video.resizeMode = modes[index]; prefs.edit().putInt("aspect", modes[index]).apply(); dialog.dismiss()
            settingsAdapter.notifyDataSetChanged()
        }.setNegativeButton("取消", null).show()
    }
    private fun editSource() {
        val input = sourceInput(source, "http:// 或 https://").apply { selectAll() }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(input) }
        val dialog = AlertDialog.Builder(this).setTitle("播放列表地址").setView(box)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (!validSourceUrl(value)) {
                    input.error = "请输入有效的 HTTP 或 HTTPS 直播源地址"
                    input.requestFocus()
                    return@setOnClickListener
                }
                if (value != source) {
                    source = value
                    sourceGeneration++
                    prefs.edit().putString("source", source).apply()
                    tvPlayer?.stop()
                    channels = emptyList()
                    current = null
                    channelListState = null
                    channelText.text = getString(R.string.app_name)
                    decoderText.text = ""
                    loadingText.text = "正在读取频道…"
                    spinner.visibility = View.VISIBLE
                    loading.visibility = View.VISIBLE
                    applyChannels(repository.cached(source))
                }
                settingsAdapter.notifyDataSetChanged()
                dialog.dismiss()
                refreshPlaylist()
            }
        }
        dialog.show()
    }

    private fun editEpgSource() {
        val input = sourceInput(epgSource, "可留空，不影响直播").apply { selectAll() }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(input) }
        val dialog = AlertDialog.Builder(this).setTitle("节目单地址")
            .setMessage("支持 XMLTV 节目单（XML 或 XML.gz）；留空可关闭节目单")
            .setView(box).setPositiveButton("保存", null)
            .setNeutralButton("默认地址", null).setNegativeButton("取消", null).create()
        fun save(value: String) {
            epgSource = value
            epgGeneration++
            prefs.edit().putString("epgSource", epgSource).apply()
            settingsAdapter.notifyDataSetChanged()
            requestEpg(force = true)
            dialog.dismiss()
            Toast.makeText(this, if (value.isEmpty()) "已关闭节目单" else "已保存节目单地址", Toast.LENGTH_SHORT).show()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isNotEmpty() && !validSourceUrl(value)) {
                    input.error = "请输入有效的 HTTP 或 HTTPS 节目单地址，或留空"
                    input.requestFocus()
                    return@setOnClickListener
                }
                save(value)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { save(EpgRepository.DEFAULT_URL) }
        }
        dialog.show()
    }

    override fun onState(text: String, buffering: Boolean) {
        if (!active || setupView != null) return
        stateText.text = text
        loadingText.text = text
        spinner.visibility = if (buffering) View.VISIBLE else View.GONE
        loading.visibility = if (buffering || text.contains("已重试")) View.VISIBLE else View.GONE
        // Short rebuffering does not produce another first-frame callback.
        // Resume the hide timer whenever buffering ends, including after a retry.
        if (buffering) showInfo(0) else showInfo()
        android.util.Log.i("AstraTV", "STATE $text")
    }
    override fun onDecoderInfo(text: String) {
        if (!active || setupView != null) return
        decoderText.text = text
        android.util.Log.i("AstraTV", "DECODER $text")
    }
    override fun onVideoReady() {
        if (!active || setupView != null) return
        loading.visibility = View.GONE
        if (panelOpen && menuPage == MenuPage.CHANNELS && autoCloseOnFirstFrame) showPanel(false) else showInfo()
        autoCloseOnFirstFrame = false
        android.util.Log.i("AstraTV", "FIRST_FRAME ${current?.name}")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Configuration fields own digits, arrows and Back; they are never channel controls.
        if (setupView != null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (event.repeatCount == 0) {
                showPanel(false)
                digits = (digits + (event.keyCode - KeyEvent.KEYCODE_0)).takeLast(5)
                channelText.text = "选台：$digits"
                showInfo(0); main.removeCallbacks(commitDigits); main.postDelayed(commitDigits, 1200)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!panelOpen) showPanel(true)
                    else if (channelList.hasFocus() || settingsList.hasFocus()) focusNavigation()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (panelOpen) {
                        if (channelList.hasFocus() || settingsList.hasFocus()) showPanel(false) else focusMenuContent()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> { if (!panelOpen) { zap(1); return true } }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (!panelOpen) { zap(-1); return true } }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (!panelOpen) { showPanel(true); return true } }
                KeyEvent.KEYCODE_BACK -> {
                    if (panelOpen) { showPanel(false); return true }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastBack < 1800) finish() else { lastBack = now; Toast.makeText(this, "再按一次返回退出", Toast.LENGTH_SHORT).show() }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private inner class SettingsAdapter : BaseAdapter() {
        override fun getCount() = settingsLabels.size
        override fun getItem(position: Int) = settingsLabels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minimumHeight = dp(64)
                addView(text("", 16).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
                addView(text("", 12, 0xFF9CAEC4.toInt()).apply {
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
            }
            val summary = when (position) {
                0 -> prefs.getString("decoder", null) ?: "自动 · 优先硬件解码"
                1 -> when (video.resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "铺满屏幕"
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "裁剪铺满"
                    else -> "保持比例"
                }
                2 -> if (refreshing) "正在后台刷新频道…" else "更新直播源中的频道"
                3 -> source.ifBlank { "未设置" }
                4 -> epgSource.ifBlank { "未启用" }
                else -> "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}"
            }
            (row.getChildAt(0) as TextView).text = settingsLabels[position]
            (row.getChildAt(1) as TextView).text = summary
            row.contentDescription = "${settingsLabels[position]}，$summary"
            return row
        }
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val c = channels[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minimumHeight = dp(64)
                val titleColumn = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("", 16).apply {
                        setSingleLine()
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(-1, -2))
                    addView(text("", 12, 0xFF9CAEC4.toInt()).apply {
                        setSingleLine()
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    }, LinearLayout.LayoutParams(-1, -2))
                }
                addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("", 11, 0xFF32D5BB.toInt()).apply {
                    setSingleLine()
                    setPadding(dp(8), 0, 0, 0)
                }, LinearLayout.LayoutParams(-2, -2))
            }
            val playing = current?.let(::key) == key(c)
            val titleColumn = row.getChildAt(0) as LinearLayout
            (titleColumn.getChildAt(0) as TextView).text = "${c.number.padStart(3, '0')}   ${c.name}"
            val programme = programmeLabel(epgSnapshot.current(c, System.currentTimeMillis()))
            (titleColumn.getChildAt(1) as TextView).text = programme
            (row.getChildAt(1) as TextView).text = if (playing) "播放中" else ""
            row.contentDescription = "频道 ${c.number} ${c.name}，$programme${if (playing) "，正在播放" else ""}"
            return row
        }
    }
}
