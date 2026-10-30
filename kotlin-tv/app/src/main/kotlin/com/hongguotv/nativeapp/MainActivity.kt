// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// TV 主页面入口：协调栏目切换、详情和正式播放；具体界面组件及数据请求由协作对象负责。

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private val bg get() = TvStyle.background
    private val surface get() = TvStyle.surface
    private val accent get() = TvStyle.accent
    private val white get() = TvStyle.text
    private val muted get() = TvStyle.muted
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val receiveRate = NetworkRateMeter { android.os.SystemClock.elapsedRealtime() }
    private val repository = ContentRepository().let { source ->
        ContentRepository(source.http.newBuilder().addNetworkInterceptor(ReceiveRateInterceptor(receiveRate)).build())
    }
    // 榜单专用并行加载通道：最多四榜各自在途、互不取消，与全局单飞 work() 隔离。
    private val rankWorker = Executors.newFixedThreadPool(3)
    private val networkCleanup = Executors.newSingleThreadExecutor()
    private val prefetchWorker = Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient? = null
    private var playbackHttp: okhttp3.OkHttpClient? = null
    private val preparedNext =
        PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    private var prefetchJob: java.util.concurrent.Future<*>? = null
    private var prefetchScope: RequestScope? = null
    private data class QueuedEpisode(val index: Int,val key: String,val remote: RemoteVideo,
        val http: okhttp3.OkHttpClient,val preparedAt: Long,val opened: OpenedStream? = null)
    private var queuedNext: QueuedEpisode?=null
    // A claimed speculative stream still needs an owner if resolution is cancelled before adoption.
    private var pendingPrepared: Pair<RemoteVideo,okhttp3.OkHttpClient?>?=null
    private val bufferingHint=Runnable {
        if (showReceiveIndicator()) {
            playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            bufferingRate?.apply { text = "接收 ${receiveRate.label()}"; visibility = View.VISIBLE }
        }
    }
    private var bufferingRate: TextView? = null
    private var prefetchedFor = ""
    private var readySince = 0L
    private lateinit var artwork: ArtworkLoader

    private data class DeferredUi(val apply: () -> Unit, val discard: () -> Unit = {})

    private var deferredWork: DeferredUi? = null
    private var initialCatalogPending = true
    private var restoreStoppedPage = false
    private var pendingDetail: Series? = null
    private var detailAutoplay = true
    private var libraryLoadError: Throwable? = null
    private var workScope: RequestScope? = null
    private var workJob: java.util.concurrent.Future<*>? = null
    private var playerListener: Player.Listener? = null
    private lateinit var playbackTitle: TextView
    private var directPlayback = false
    private var collection: String? = null
    private var collectionReturn: CatalogState? = null
    private var collectionFocus = ""
    private var collectionBack: View? = null
    private val pageSize = 20
    private var continuousCatalog: ContinuousCatalogView? = null
    private val continuousPositions = linkedMapOf<String, ContinuousCatalogView.Position>()
    private var continuousKey = ""
    private var catalogScroll: ScrollView? = null
    private var catalogBody: LinearLayout? = null
    private var homePosition: HomeScreen.Position? = null
    private var catalogPageKey = ""
    private val scrollPositions = mutableMapOf<String, Int>()
    private val catalogCards = linkedMapOf<String, View>()
    private val cardImages = linkedMapOf<String, Pair<ImageView, String>>()
    private var prefetchAttemptAt = 0L
    private var prefetchAttempts = 0
    private val homeFeedState = DramaFeedState()
    private var homeFeed: HomeFeedScreen? = null
    private var homeFeedIdentity = ""
    private var homeScreen: HomeScreen? = null
    private var selectionPreview: SeriesPreview? = null
    private var homeFromCache = false
    private var homeUpdateCount = 0
    private var launchStarted = android.os.SystemClock.elapsedRealtime()
    private val activityStarted = launchStarted
    private var homeContentLogged = false
    private lateinit var library: Library
    private var searchDraft: String? = null
    private var searchSuggestions: SearchSuggestionsView? = null
    private var tvSearchPanel: TvSearchPanel? = null
    private var tvSearchOpen = false
    private val discoveryBrowser = DiscoveryBrowserState()
    private var discoveryScreen: DiscoveryScreen? = null
    private lateinit var featureTools: FeatureTools
    private lateinit var appearance: AppearanceController
    private lateinit var backupController: CompleteBackupController
    private var appliedAppearanceTheme: FeatureTheme? = null
    private var exitDialog: AlertDialog? = null
    private val incompatibleSkips = IncompatibleSkipBudget()
    private var applyingAutomaticSkip = false
    private lateinit var favoriteSeasons: FavoriteSeasonStore
    private var nextSeasonButton: TextView? = null
    private var favoriteGroup: String? = null
    private var pendingSeriesLink: String? = null
    private var lastPlaybackTick = 0L
    private var heldPlayer: ExoPlayer? = null
    private var heldSpeed = 1f
    private var holdFired = false
    private val startHoldSpeed = Runnable {
        val p = player
        if (foreground && screen == "player" && !panel && episodePanel == null && p?.isPlaying == true) {
            holdFired = true
            heldPlayer = p
            heldSpeed = p.playbackParameters.speed
            p.setPlaybackSpeed(featureTools.holdSpeed)
            showHud()
        }
    }
    private lateinit var tvTools: TvTools

    private data class DetailVisit(val detail: Detail, val episode: Int, val group: Int)

    private val detailTrail = java.util.ArrayDeque<DetailVisit>()
    private var relatedSection: RelatedWorksView? = null
    private var accountFavoriteButton: TextView? = null
    private var reservationOnline = true
    private val reservationFilters = mutableListOf<View>()
    private var accountHistoryScope = 0
    private lateinit var cloudAccount: AccountController
    private var accountAvatar: AccountAvatarView? = null
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer = SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped = false
    private var playerView: PlayerView? = null
    private val social by lazy {
        TvSocial(
            this,
            { player },
            {
                if (screen == "player" || screen == "detail")
                    detail?.let { data ->
                        data.episodes.getOrNull(episodeIndex)?.let { data.series.id to it }
                    }
                else null
            },
        )
    }
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges = mutableMapOf<String, TextView>()
    private var favoriteStatus: TextView? = null
    private var favoriteCheck: TextView? = null
    private var homeUpdates: TextView? = null
    private val resumeCards = mutableListOf<View>()
    private var episodePanel: EpisodePanel? = null
    private val recovery = RecoveryBudget()
    private var retryPending = false
    private var autoRecovery = false
    private var startPosition = 0L
    private var retryPosition = 0L
    private var requestedAutoplay = true
    private var retryAutoplay = true
    private var foreground = false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered = false
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                main.post {
                    if (connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch()
                    if (foreground && screen == "player" && playError && autoRecovery)
                        scheduleRecovery()
                }
            }

            override fun onAvailable(network: Network) {
                changed()
            }

            override fun onLost(network: Network) {
                changed()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                changed()
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                changed()
            }
        }
    private val retryRunnable = Runnable {
        retryPending = false
        if (foreground && screen == "player" && playError && autoRecovery) {
            if (!networkAvailable()) scheduleRecovery()
            else if (recovery.consume()) {
                android.util.Log.i(
                    "HongguoTV",
                    "Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms",
                )
                playEpisode(episodeIndex, retryPosition, retryAutoplay, recovering = true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation = 0
    private var screen = "catalog"
    private var tab = 0
    private lateinit var librarySources: LibrarySources
    private val sourceButtons = mutableListOf<View>()
    private var page = 1
    private var query = ""
    private var catalog = emptyList<Series>()
    private var catalogLoadedPage = 1
    private var hasMore = false
    private var catalogFocus = ""

    private data class CatalogState(
        val page: Int,
        val items: List<Series>,
        val hasMore: Boolean,
        val ranking: ComicRanking?,
    )

    private val rankBrowser = RankBrowserState()
    private var ranking: ComicRanking? = null
    private var rankRefresh: View? = null
    private var rankSubtitle: TextView? = null
    private val tabState = mutableMapOf<Int, CatalogState>()
    private val tabFocus = mutableMapOf<Int, String>()
    private val nav = mutableListOf<View>()
    private val typeButtons = mutableMapOf<ContentType, View>()
    private var searchInput: View? = null
    private var searchButton: View? = null
    private val searchActions = mutableListOf<View>()
    private var historyManage: View? = null
    private var detail: Detail? = null
    private var episodeIndex = 0
    private var group = 0
    private var synopsisExpanded = false
    private var player: ExoPlayer? = null
    private var video: RemoteVideo? = null
    private var playbackReady = false
    private var pausedForLifecycle = false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel = false
    private var transportPlay: TextView? = null
    private var sleepButton: TextView? = null
    private var speedDialog: AlertDialog? = null
    private var pendingSeek: Long? = null
    private var playError = false
    private var lastSaved = 0L
    private var quality = ""
    private var attemptedQuality = 0
    private var currentTrack: VideoTracks.Track? = null
    private var currentRendition: Rendition? = null
    private var currentTiers: List<Rendition> = emptyList()
    private var autoDowngrades = 0
    private val tick =
        object : Runnable {
            override fun run() {
                if (!foreground || isDestroyed) return
                if (screen == "player") {
                    if (sleepTimer.poll()) stopForSleep()
                    if (bufferingRate?.visibility == View.VISIBLE) bufferingRate?.text = "接收 ${receiveRate.label()}"
                    if (hud.visibility == View.VISIBLE) updatePlaybackText()
                    val p = player
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (p?.isPlaying == true && !sleepStopped && episodePanel == null) {
                        if (lastPlaybackTick > 0) featureTools.recordPlayback((now - lastPlaybackTick).coerceIn(0, 2000))
                        detail?.series?.id?.let { id ->
                            featureTools.onPlaybackTick(id, p.currentPosition, p.duration)?.let { target -> p.seekTo(target) }
                        }
                    }
                    maybePrefetch()
                    if (player?.isPlaying == true && now - lastSaved > 5000) {
                        saveProgress()
                        lastSaved = now
                    }
                }
                lastPlaybackTick = android.os.SystemClock.elapsedRealtime()
                main.postDelayed(this, 1000)
            }
        }
    private val hideHud = Runnable {
        if (screen == "player" && !panel && player?.isPlaying == true && !playError)
            hidePlaybackOverlay()
    }
    private val seekRunnable = Runnable {
        pendingSeek?.let { player?.seekTo(it) }
        pendingSeek = null
    }

    private fun dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

    private fun widthDp() = resources.displayMetrics.widthPixels / resources.displayMetrics.density

    private fun lp(
        w: Int = LinearLayout.LayoutParams.MATCH_PARENT,
        h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
    ) = LinearLayout.LayoutParams(w, h)

    private fun rounded(color: Int, border: Int = Color.TRANSPARENT) =
        TvStyle.shape(this, color, border)

    private fun text(value: String, size: Float = 16f, color: Int = white) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun focusStyle(view: View, selected: Boolean = false) {
        view.id = View.generateViewId()
        view.isSelected = selected
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        fun paint(focused: Boolean) {
            view.background =
                rounded(
                    if (focused) TvStyle.raised
                    else if (selected) Color.rgb(61, 39, 34) else surface,
                    if (focused) accent else Color.TRANSPARENT,
                )
        }
        paint(false)
        view.setOnFocusChangeListener { _, focused -> paint(focused) }
    }

    // 统一创建可点击的文字按钮；将界面样式、焦点效果与业务回调连接起来。
    private fun button(label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(42)
            if (selected) setTextColor(accent)
            focusStyle(this, selected)
            setOnClickListener { onClick() }
        }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        selected: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val view = button(label, selected, onClick)
        parent.addView(
            view,
            lp().apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                rightMargin = dp(8)
            },
        )
        return view
    }

    // 启动入口：创建根容器，初始化账户和媒体控制，再异步读取本机记录。
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val identity = getSharedPreferences("device_identity", Context.MODE_PRIVATE)
        val savedId = identity.getString("device_id", null)
        val deviceId = savedId?.takeIf(DeviceIdentity::isValid) ?: DeviceIdentity.create().also { identity.edit().putString("device_id", it).apply() }
        DeviceIdentity.use(deviceId)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        librarySources = LibrarySources(this)
        library = Library(this)
        featureTools = FeatureTools(this, library, repository,
            openSeries = { openToolSeries(it) },
            onPlaySource = ::openUserSource,
            onAppearanceChanged = { theme ->
                TvStyle.apply(theme)
                appearanceChanged()
            },
            onDesktopChannel = ::publishDesktopChannel,
            onChooseBackupFolder = ::chooseBackupFolder,
            onSeasonUpdates = ::showNextSeasons,
            onAppearance = { appearance.show() },
            onPhoneBackup = { includeExtras -> backupController.show(includeExtras) },
            onRestoreBackup = { backup -> backupController.preview(backup) },
            portableExtras = { SourceLibraryBackup(this).exportState() to appearance.exportState() },
            onCacheManage = { featureTools.showCache(artwork) },
        )
        favoriteSeasons = FavoriteSeasonStore(this)
        TvStyle.apply(featureTools.theme)
        appliedAppearanceTheme = featureTools.theme
        pendingSeriesLink = AndroidTvChannels.seriesId(intent.dataString)
        artwork = ArtworkLoader(this)
        tvTools = TvTools(this)
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        setContentView(root)
        appearance = AppearanceController(this, ::appearanceChanged, ::chooseAppearanceImage)
        backupController = CompleteBackupController(this, library, featureTools.preferences, appearance) {
            social.reset()
            social.foreground(foreground)
            TvStyle.apply(featureTools.theme)
            appliedAppearanceTheme = featureTools.theme
            appearance.loadAsync()
            if (foreground && library.isLoaded) {
                when (screen) {
                    "player" -> if (panel) showPanel()
                    "detail" -> showDetail(false)
                    else -> showCatalog()
                }
            }
        }
        appearance.loadAsync()
        CodecCapabilities.init(this)
        CodecCapabilities.logDeviceCapabilities()
        cloudAccount =
            AccountController(this) { remote ->
                openDetail(remote.series, true, cloudProgress = remote)
            }
        cloudAccount.onHistoryChanged = {
            refreshAccountAvatar()
            if (homeFeedIdentity != cloudAccount.recommendationIdentity) {
                homeFeedState.refresh(resetRecommendations = true)
                homeFeedIdentity = cloudAccount.recommendationIdentity
                if (homeFeed != null) showCatalog()
            }
            homeFeed?.refreshAccountLabel()
            relatedSection?.refreshLabels()
            detail?.series?.let {
                accountFavoriteButton?.text = cloudAccount.favoriteActionLabel(it.id)
            }
            if (
                screen == "detail" &&
                    relatedSection != null &&
                    cloudAccount.signedIn &&
                    cloudAccount.reservationState(false).message == "尚未读取预约记录"
            )
                cloudAccount.refreshReservations()
            if (accountHistoryScope != cloudAccount.historyScope) {
                accountHistoryScope = cloudAccount.historyScope
                listOf(3, 4, 5).forEach {
                    tabState.remove(it)
                    tabFocus.remove(it)
                }
                if (accountCatalog()) {
                    page = 1
                    catalogFocus = ""
                }
            }
            if (screen == "catalog" && accountCatalog() && library.isLoaded) {
                val focusNav = nav.any { it.hasFocus() }
                val focusSource = sourceButtons.any { it.hasFocus() }
                val focusManage = historyManage?.hasFocus() == true
                showCatalog(focusNav = focusNav)
                if (focusSource) sourceButtons.getOrNull(1)?.requestFocus()
                else if (focusManage) historyManage?.requestFocus()
                if (cloudAccount.signedIn) {
                    if (
                        tab == 5 &&
                            cloudAccount.reservationState(reservationOnline).message == "尚未读取预约记录"
                    )
                        cloudAccount.refreshReservations()
                    if (isAccountFavorites() && cloudAccount.favoriteState().message == "尚未读取账户收藏")
                        cloudAccount.refreshFavorites()
                }
            }
        }
        connectivity = getSystemService(ConnectivityManager::class.java)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 24)
                connectivity.registerDefaultNetworkCallback(networkCallback)
            else
                connectivity.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback,
                )
            networkRegistered = true
        }
        favoriteMonitor = FavoriteMonitor(library, repository) { refreshFavoriteLabels() }.apply {
            onNextSeason = { current, next ->
                if (library.favorite(current.id)) { favoriteSeasons.observe(current, next); refreshFavoriteLabels() }
            }
        }
        mediaSession =
            TvMediaSession(
                this,
                { if (foreground && screen == "player") requestPlayback(true) },
                { if (foreground && screen == "player") requestPlayback(false) },
                { position -> if (foreground && screen == "player") seekTo(position) },
                { direction -> skipEpisode(direction) },
                { if (foreground && screen == "player") returnToDetail() },
            )
        message(base(), "正在读取本机记录…")
        loadLibrary()
    }

    private fun showLibraryError(problem: Throwable) {
        val body = base()
        error(body, problem) {
            message(base(), "正在读取本机记录…")
            loadLibrary()
        }
    }

    // 本机记录加载成功后才展示内容；失败时保留错误并在前台提供重试入口。
    private fun loadLibrary() {
        libraryLoadError = null
        library.loadAsync(
            onFailure = { problem ->
                if (!isDestroyed) {
                    libraryLoadError = problem
                    if (foreground) showLibraryError(problem)
                }
            }
        ) {
            if (!isDestroyed && foreground) {
                if (!handleSeriesLink()) showCatalog(load = true)
                favoriteMonitor.check()
            }
        }
    }

    private fun appearanceChanged() {
        if (isDestroyed || !::featureTools.isInitialized || !::root.isInitialized) return
        val changedTheme = appliedAppearanceTheme != featureTools.theme
        TvStyle.apply(featureTools.theme)
        appliedAppearanceTheme = featureTools.theme
        social.reset()
        social.foreground(foreground)
        if (screen == "player") {
            // Appearance changes never replace or restart an active player.
            if (::playbackTitle.isInitialized) playbackTitle.setTextColor(Color.WHITE)
            if (::playbackText.isInitialized) playbackText.setTextColor(Color.LTGRAY)
            return
        }
        if (changedTheme && foreground && library.isLoaded) {
            if (screen == "detail" && detail != null) showDetail(false) else showCatalog()
        } else {
            root.setBackgroundColor(bg)
            if (::appearance.isInitialized) appearance.apply(root)
        }
    }

    private fun chooseAppearanceImage() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivityForResult(picker, 7115) }.onFailure {
            tvTools.info("选择背景图片", "当前设备没有图片选择器，可使用外观中的手机扫码上传。", null)
        }
    }

    // 创建页面公共容器；清除旧视图后设置边距，可按需添加标题和返回按钮。
    private fun base(title: String? = null): LinearLayout {
        root.removeAllViews()
        root.setBackgroundColor(bg)
        if (::appearance.isInitialized) appearance.apply(root)
        val container = column()
        val horizontal = dp(widthDp() * .05f)
        val vertical = (resources.displayMetrics.heightPixels * .05f).toInt()
        container.setPadding(horizontal, vertical, horizontal, vertical)
        root.addView(container, FrameLayout.LayoutParams(-1, -1))
        if (title != null) {
            val heading = row()
            addButton(heading, "‹ 返回") { goBack() }
            heading.addView(
                text(title, 22f).apply { setTypeface(null, Typeface.BOLD) },
                lp(0, dp(44)).apply { weight = 1f },
            )
            if (::cloudAccount.isInitialized) {val account=createAccountAvatar();heading.addView(account, lp(account.actionWidthPx, dp(44)))}
            container.addView(heading)
        }
        return container
    }

    private fun createAccountAvatar(): AccountAvatarView = AccountAvatarView(this) { cloudAccount.show() }.also {view->
        accountAvatar=view;view.bind(cloudAccount.accountProfile,cloudAccount.signedIn,::loadCover)
    }
    private fun refreshAccountAvatar() {
        accountAvatar?.bind(cloudAccount.accountProfile,cloudAccount.signedIn,::loadCover)
    }

    private fun loadCover(view: ImageView, url: String) {
        var ancestor: View? = view
        var focused = false
        while (ancestor != null && ancestor !== root) {
            if (ancestor.isFocused) {
                focused = true
                break
            }
            ancestor = ancestor.parent as? View
        }
        artwork.load(
            view,
            url,
            if (focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE,
        )
    }

    private fun cancelWork() {
        deferredWork?.discard?.invoke()
        deferredWork = null
        workScope?.cancel()
        workScope = null
        workJob?.cancel(true)
        workJob = null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }

    private fun invalidatePage() {
        generation++
        cancelWork()
        artwork.cancelPage()
    }

    private fun releaseCatalogViews() {
        searchSuggestions?.close()
        searchSuggestions = null
        tvSearchPanel = null
        discoveryScreen?.close()
        discoveryScreen = null
        continuousCatalog?.let { view ->
            view.capture()?.let { continuousPositions[continuousKey] = it }
            view.close()
        }
        continuousCatalog = null
        while (continuousPositions.size > 20) continuousPositions.remove(
            continuousPositions.keys.first()
        )
        homeFeed?.close()
        homeFeed = null
        relatedSection = null
        accountAvatar = null
        accountFavoriteButton = null
        homeScreen?.let { homePosition = it.capturePosition() }
        catalogScroll?.let { scrollPositions[catalogPageKey] = it.scrollY }
        catalogBody = null
        homeScreen = null
        selectionPreview = null
        catalogScroll = null
        nav.clear()
        sourceButtons.clear()
        typeButtons.clear()
        searchInput = null
        searchButton = null
        searchActions.clear()
        historyManage = null
        rankRefresh = null
        rankSubtitle = null
        collectionBack = null
        favoriteBadges.clear()
        favoriteStatus = null
        favoriteCheck = null
        homeUpdates = null
        resumeCards.clear()
        catalogCards.clear()
        cardImages.clear()
    }

    // 后台执行耗时任务；回到主线程前校验页面代次，避免旧请求覆盖新页面。
    private fun <T> work(action: () -> T, done: (T) -> Unit, failed: (Throwable) -> Unit) {
        cancelWork()
        val ticket = generation
        val scope = RequestScope()
        workScope = scope
        workJob =
            io.submit {
                try {
                    val result = scope.run(action)
                    main.post {
                        val discard = {
                            if (result is RemoteVideo) result.close()
                            if (result is OpenedStream) result.video.close()
                            Unit
                        }
                        if (isDestroyed || generation != ticket) discard()
                        else if (foreground) done(result)
                        else {
                            deferredWork?.discard?.invoke()
                            deferredWork = DeferredUi({ done(result) }, discard)
                        }
                    }
                } catch (e: Exception) {
                    main.post {
                        if (!isDestroyed && generation == ticket) {
                            if (foreground) failed(e) else deferredWork = DeferredUi({ failed(e) })
                        }
                    }
                }
            }
    }

    private fun message(parent: LinearLayout, label: String) {
        parent.addView(text(label, 17f, muted).apply { setPadding(0, dp(28), 0, dp(18)) })
    }

    private fun error(parent: LinearLayout, problem: Throwable, retry: () -> Unit) {
        message(parent, "暂时无法加载，请检查网络后重试。")
        val detail =
            if (problem is java.net.UnknownHostException) "无法连接内容网站"
            else if (problem is java.net.SocketTimeoutException) "连接超时"
            else problem.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(160)
        parent.addView(text(detail, 13f, muted))
        addButton(parent, "重试", onClick = retry).requestFocus()
    }

    // 导航按钮的点击入口：记录当前栏目状态，再切换到目标栏目。
    private fun switchTab(next: Int) {
        if (tab == 1) {
            saveDiscoveryDraft()
            continuousCatalog?.capture()?.let { catalogFocus = it.id }
        }
        leaveCollection()
        tabState[tab] = CatalogState(page, catalog, hasMore, ranking)
        tabFocus[tab] = catalogFocus
        tab = next
        val saved = tabState[next]
        page = saved?.page ?: 1
        catalogLoadedPage = page
        catalog = saved?.items ?: emptyList()
        hasMore = saved?.hasMore ?: false
        ranking = saved?.ranking
        catalogFocus = tabFocus[next].orEmpty()
        showCatalog(load = (next <= 2 && catalog.isEmpty()))
        refreshAccountSection()
    }

    private fun switchContentType(type: ContentType) {
        if (library.contentType == type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query = it.text.toString().trim() }
        library.contentType = type
        tabState.remove(0)
        tabFocus.remove(0)
        releaseCatalogViews()
        page = 1
        catalog = emptyList()
        hasMore = false
        ranking = null
        catalogFocus = ""
        homePosition = null
        showCatalog(load = (tab == 0 || query.isNotBlank()), focusType = true)
    }

    private fun leaveCollection() {
        if (collection == null) return
        collection = null
        collectionReturn?.let {
            page = it.page
            catalog = it.items
            hasMore = it.hasMore
            ranking = it.ranking
        }
        catalogFocus = collectionFocus
        collectionReturn = null
    }

    private fun showCollection(kind: String) {
        collectionReturn = CatalogState(page, catalog, hasMore, ranking)
        collectionFocus = catalogFocus
        collection = kind
        page = 1
        catalogFocus = ""
        showCatalog()
    }

    private fun inlineHomeFilters() =
        tab == 0 && resources.configuration.fontScale <= 1.2f && widthDp() >= 1200

    private fun isAccountHistory() = tab == 4 && librarySources.accountHistory

    private fun isAccountFavorites() = tab == 3 && librarySources.accountFavorites

    private fun isLocalHistory() = tab == 4 && !librarySources.accountHistory

    private fun isLocalFavorites() = tab == 3 && !librarySources.accountFavorites

    private fun accountCatalog() = isAccountHistory() || isAccountFavorites() || tab == 5

    private fun refreshAccountSection() {
        if (isAccountHistory()) cloudAccount.refreshHistory()
        if (isAccountFavorites()) {
            cloudAccount.refreshFavorites()
            cloudAccount.refreshHistory()
        }
        if (tab == 5) {
            cloudAccount.refreshReservations()
            cloudAccount.refreshHistory()
        }
    }

    private fun collectionEpisodeLabel(series: Series): String {
        val remote = isAccountFavorites() || isAccountHistory() || tab == 5
        val cloud = if (remote) cloudAccount.progress(series.id) else null
        val local = if (!remote) library.progress(series.id) else null
        val update = if (!remote) library.favoriteUpdate(series.id) else null
        val total =
            maxOf(
                EpisodeSummary.total(series.badge),
                EpisodeSummary.total(cloud?.series?.badge.orEmpty()),
                EpisodeSummary.total(local?.series?.badge.orEmpty()),
                cloud?.episodeCount ?: 0,
                update?.total ?: 0,
            )
        return EpisodeSummary.label(
            total,
            cloud?.episodeIndex ?: local?.episodeIndex,
            account = remote,
            ready = cloudAccount.historyReady,
            loading = cloudAccount.historyLoading,
            markedWatched = !remote && library.watched(series.id),
            pending = tab == 5 && !reservationOnline,
            added = update?.added ?: 0,
        )
    }

    private fun switchLibrarySource(account: Boolean) {
        if (tab == 3) librarySources.accountFavorites = account
        else librarySources.accountHistory = account
        page = 1
        catalogFocus = ""
        catalog = emptyList()
        tabState.remove(tab)
        tabFocus.remove(tab)
        showCatalog()
        sourceButtons.getOrNull(if (account) 1 else 0)?.requestFocus()
        refreshAccountSection()
    }

    private fun localCatalog() = tab in 3..5 || collection != null

    private fun localItems(): List<Series> =
        when (collection) {
            "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
            "later" -> library.later()
            "updates" ->
                library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0) > 0 }
            "resume" ->
                library.history().filterNot { library.watched(it.series.id) }.map { it.series }
            else ->
                when {
                    isAccountFavorites() -> cloudAccount.favoriteState().rows.map { it.series }
                    isAccountHistory() -> cloudAccount.historyRows().map { it.series }
                    tab == 5 ->
                        cloudAccount.reservationState(reservationOnline).rows.map { it.series }
                    isLocalFavorites() -> featureTools.filterFavorites(library.favorites(), favoriteGroup)
                    else -> library.history().map { it.series }
                }
        }

    // 内容页总入口：构建顶部导航，再根据当前栏目挂载首页、搜索或其他列表。
    private fun showCatalog(
        load: Boolean = false,
        focusNav: Boolean = false,
        focusType: Boolean = false,
    ) {
        relatedSection = null
        detailTrail.clear()
        reservationFilters.clear()
        initialCatalogPending = false
        restoreStoppedPage = false
        pendingDetail = null
        val previousCatalog = CatalogState(catalogLoadedPage, catalog, hasMore, ranking)
        // The pending page stays separate from the last successful page; failures restore it.
        if (load) {
            val cached =
                if (tab == 0 && page == 1) library.cachedHome(library.contentType) else null
            catalog = cached?.items ?: emptyList()
            hasMore = cached?.hasMore ?: false
            ranking = null
            homeFromCache = cached != null
        }
        invalidatePage()
        screen = "catalog"
        releaseCatalogViews()
        val container = base()
        if (tab != 0 && foreground && library.isLoaded && !favoriteMonitor.running)
            main.post { if (foreground && screen == "catalog") favoriteMonitor.check() }
        val top = row().apply { setPadding(0, 0, 0, dp(8)) }
        val brand = row()
        brand.addView(
            ImageView(this).apply { setImageResource(com.hongguotv.nativeapp.R.drawable.app_icon) },
            lp(dp(28), dp(28)).apply { rightMargin = dp(9) },
        )
        brand.addView(
            text("红果TV", if (widthDp() < 800) 17f else 22f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        )
        top.addView(brand, lp(dp(if (widthDp() < 800) 105 else 140), dp(44)))
        listOf("首页", "找剧", "排行榜", "收藏", "观看历史", "账户预约", "预览").forEachIndexed { index, label ->
            val item = addButton(top, label, index == tab) { switchTab(index) }
            item.textSize = if (widthDp() < 1000) 13f else 15f
            item.setPadding(dp(9), dp(10), dp(9), dp(10))
            fun paint(focused: Boolean) {
                item.setTextColor(if (focused) bg else if (index == tab) accent else muted)
                item.background =
                    rounded(
                        if (focused) white
                        else if (index == tab) Color.rgb(48, 32, 29) else Color.TRANSPARENT
                    )
                item.setTypeface(
                    null,
                    if (focused || index == tab) Typeface.BOLD else Typeface.NORMAL,
                )
            }
            paint(false)
            item.setOnFocusChangeListener { _, focused -> paint(focused) }
            nav += item
        }
        addButton(top, "工具") { featureTools.setCurrentSeries(null); featureTools.show() }.also { nav += it }
        val header = row()
        header.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(top) },lp(0,-2).apply {weight=1f})
        val account=createAccountAvatar()
        header.addView(account,lp(account.actionWidthPx,dp(44)).apply {leftMargin=dp(12)})
        container.addView(header,lp(-1,-2))
        container.addView(
            View(this).apply { setBackgroundColor(TvStyle.outline) },
            lp(-1, dp(1)).apply { bottomMargin = dp(6) },
        )
        if (tab == 6) {
            favoriteMonitor.stop()
            homeFeedIdentity = cloudAccount.recommendationIdentity
            val feed =
                HomeFeedScreen(
                    this,
                    homeFeedState,
                    nav.toList(),
                    { openDetail(it, false) },
                    ::accountFavoriteAction,
                    cloudAccount::favoriteActionLabel,
                    PublicFeedBackend(
                        library.maxQuality,
                        account = cloudAccount.recommendationCredentials(),
                        startupCache = FeedStartupCache(this),
                    ),
                    Media3TrialPlayback(this),
                )
            homeFeed = feed
            container.addView(feed, lp(-1, 0).apply { weight = 1f })
            feed.start(foreground, focusNav)
            return
        }
        if (tab == 2) {
            val ranks =
                RankingScreen(
                    this,
                    rankBrowser,
                    nav,
                    tvTools,
                    ::loadCover,
                    { item -> openDetail(item, false) },
                    { item, anchor -> quickActions(item, anchor) },
                    { board, done ->
                        // 榜单加载走独立并行通道：不与全局 work() 互相取消，快速切换
                        // 多个榜单时各自的读取都会完成并写入会话缓存。
                        rankWorker.submit {
                            val result = runCatching { repository.rankSnapshot(board) }
                            main.post {
                                if (isDestroyed) return@post
                                result.fold(
                                    { done(it, null) },
                                    { done(null, it.message ?: "网络暂不可用") },
                                )
                            }
                        }
                    },
                )
            container.addView(ranks, lp(-1, 0).apply { weight = 1f })
            ranks.start(load, focusNav)
            return
        }
        if (tab == 0) {
            val types = row().apply { setPadding(0, dp(8), 0, dp(6)) }
            if (!inlineHomeFilters()) types.addView(text("片库", 12f, muted), lp(dp(42), -2))
            ContentType.entries.forEach { type ->
                typeButtons[type] =
                    addButton(types, type.label, library.contentType == type) {
                            switchContentType(type)
                        }
                        .apply {
                            isSelected = library.contentType == type
                            nextFocusUpId = nav[tab].id
                        }
            }
            if (inlineHomeFilters()) {
                top.addView(Space(this), lp(0, 1).apply { weight = 1f })
                typeButtons.values.forEach { view ->
                    (view as TextView).apply {
                        textSize = 12f
                        minHeight = dp(34)
                        setPadding(dp(12), dp(7), dp(12), dp(7))
                    }
                }
                types.setPadding(0, 0, 0, 0)
                top.addView(types)
            } else {
                if (tab == 0)
                    types.addView(
                        text("长按确认 · 管理片单", 12f, muted).apply { setPadding(dp(14), 0, 0, 0) }
                    )
                container.addView(types)
            }
            nav.forEach { it.nextFocusDownId = typeButtons.getValue(library.contentType).id }
        }
        if (tab == 1) {
            if (tvSearchOpen || query.isNotBlank()) {
                val panel =
                    TvSearchPanel(
                        this,
                        searchDraft ?: query,
                        library.searches().map { it.query },
                        nav[tab],
                        repository::searchSuggestions,
                        ::runSearch,
                        { anchor -> tvTools.phoneInput(anchor, ::runSearch) },
                        ::returnToDiscovery,
                    )
                tvSearchPanel = panel
                searchInput = panel.input
                searchButton = panel.searchButton
                searchSuggestions = panel.suggestions
                panel.resultEntry = { continuousCatalog?.grid?.takeIf { catalog.isNotEmpty() } }
                container.addView(panel, lp(-1, 0).apply { weight = 1f })
                nav.forEach { it.nextFocusDownId = panel.entry().id }
                if (query.isBlank()) {
                    message(panel.results, "输入关键词后选择联想词，或按「搜索」查看剧集")
                    if (focusNav) nav[tab].requestFocus() else panel.entry().requestFocus()
                    return
                }
            } else {
                val searchRow = row()
                val input =
                    EditText(this).apply {
                        id = View.generateViewId()
                        hint = "输入剧名、关键词或拼音"
                        setText(searchDraft ?: query)
                        textSize = 16f
                        setTextColor(white)
                        setHintTextColor(muted)
                        isSingleLine = true
                        maxLines = 1
                        filters = arrayOf(android.text.InputFilter.LengthFilter(80))
                        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    }
                searchRow.addView(
                    input,
                    lp(0, dp(45)).apply {
                        weight = 1f
                        rightMargin = dp(10)
                    },
                )
                fun search() {
                    val next = input.text.toString().trim()
                    if (next.isBlank()) {
                        returnToDiscovery()
                        return
                    }
                    runSearch(next)
                }
                searchInput = input
                searchButton = addButton(searchRow, "搜索") { search() }
                input.nextFocusUpId = nav[tab].id
                searchButton?.nextFocusUpId = input.nextFocusUpId
                lateinit var recent: TextView
                recent = addButton(searchRow, "历史") { showSearchHistory(recent) }
                recent.nextFocusUpId = input.nextFocusUpId
                lateinit var phone: TextView
                phone =
                    addButton(searchRow, "手机输入") {
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(input.windowToken, 0)
                        tvTools.phoneInput(phone) { runSearch(it) }
                    }
                phone.nextFocusUpId = input.nextFocusUpId
                val keyboard =
                    addButton(searchRow, "屏幕键盘") {
                            saveDiscoveryDraft()
                            tvSearchOpen = true
                            showCatalog()
                        }
                        .apply { nextFocusUpId = input.nextFocusUpId }
                searchActions.addAll(listOf(recent, phone, keyboard))
                if (query.isNotBlank())
                    searchActions +=
                        addButton(searchRow, "返回分类") { returnToDiscovery() }
                            .apply { nextFocusUpId = input.nextFocusUpId }

                nav.forEach { it.nextFocusDownId = input.id }
                input.setOnEditorActionListener { _, _, _ ->
                    search()
                    true
                }
                container.addView(searchRow)
                searchSuggestions =
                    SearchSuggestionsView(
                            this,
                            input,
                            listOfNotNull(searchButton) + searchActions,
                            {
                                if (query.isBlank()) discoveryScreen?.entry()
                                else
                                    continuousCatalog?.grid
                                        ?: catalogBody
                                            ?.getFocusables(View.FOCUS_DOWN)
                                            ?.firstOrNull()
                            },
                            repository::searchSuggestions,
                            ::runSearch,
                        )
                        .also { container.addView(it) }
                if (query.isBlank()) {
                    val browser =
                        DiscoveryScreen(
                            this,
                            discoveryBrowser,
                            input,
                            ::loadCover,
                            { openDetail(it, false) },
                            ::quickActions,
                            { selection, cursor, done ->
                                work(
                                    { repository.filteredDiscovery(selection, cursor) },
                                    { done(it, null) },
                                    { done(null, it) },
                                )
                            },
                        )
                    discoveryScreen = browser
                    browser.navigationChanged = { searchSuggestions?.updateNavigation() }
                    container.addView(browser, lp(-1, 0).apply { weight = 1f })
                    browser.start(!focusNav)
                    searchSuggestions?.updateNavigation()
                    if (focusNav) nav[tab].requestFocus()
                    return
                }
                container.addView(
                    text("关键词搜索覆盖全部剧集 · 返回分类可组合筛选", 12f, muted).apply {
                        setPadding(0, dp(6), 0, dp(5))
                    }
                )
            }
        } else if (tab > 2) {
            val heading = row().apply { setPadding(0, dp(12), 0, dp(6)) }
            heading.addView(
                text(
                        when (tab) {
                            3 -> "我的收藏"
                            4 -> "观看历史"
                            else -> "账户预约"
                        },
                        25f,
                    )
                    .apply { setTypeface(null, Typeface.BOLD) },
                lp(0, -2).apply { weight = 1f },
            )
            if (isLocalFavorites())
                favoriteCheck =
                    addButton(heading, "检查更新") { favoriteMonitor.check(force = true) }
                        .apply { nextFocusUpId = nav[tab].id }
            if (isLocalFavorites()) addButton(heading, "分组 · ${favoriteGroup ?: "全部"}") {
                featureTools.showFavoriteGroupPicker { selected -> favoriteGroup = selected; page = 1; catalogFocus = ""; showCatalog() }
            }
            if (isLocalFavorites()) {
                favoriteSeasons.prune(library.favorites().map { it.id }.toSet())
                nextSeasonButton = addButton(heading, "下一季 · ${favoriteSeasons.pending().size}") { showNextSeasons() }
            }
            if (isLocalHistory() && library.history().isNotEmpty()) {
                historyManage =
                    addButton(heading, "管理本机记录") {
                            val rows = library.history()
                            tvTools.choose(
                                "选择要管理的本机观看记录",
                                rows.map { it.series.title },
                                historyManage,
                                { index -> manageHistory(rows[index].series, historyManage) },
                                "清空本机记录",
                            ) {
                                tvTools.confirm(
                                    "清空本机观看记录",
                                    "只删除这台电视的观看进度，账户历史和本机收藏均保留。",
                                    historyManage,
                                ) {
                                    library.clearHistory()
                                    catalogFocus = ""
                                    showCatalog()
                                }
                            }
                        }
                        .apply { nextFocusUpId = nav[tab].id }
            }
            if (accountCatalog()) {
                historyManage =
                    addButton(
                            heading,
                            if (!cloudAccount.signedIn) "登录红果"
                            else
                                when {
                                    isAccountFavorites() -> "刷新收藏"
                                    isAccountHistory() -> "刷新历史"
                                    else -> "刷新预约"
                                },
                        ) {
                            if (!cloudAccount.signedIn) cloudAccount.show()
                            else refreshAccountSection()
                        }
                        .apply { nextFocusUpId = nav[tab].id }
                addButton(heading, "账号管理") { cloudAccount.show() }
                    .apply { nextFocusUpId = nav[tab].id }
            }
            container.addView(heading)
            if (tab == 3 || tab == 4) {
                val sources = row().apply { setPadding(0, dp(4), 0, dp(8)) }
                val account =
                    if (tab == 3) librarySources.accountFavorites else librarySources.accountHistory
                listOf(false, true).forEach { remote ->
                    sourceButtons +=
                        addButton(
                                sources,
                                if (tab == 3) if (remote) "账户收藏" else "本机收藏"
                                else if (remote) "账户历史" else "本机历史",
                                account == remote,
                            ) {
                                if (account != remote) switchLibrarySource(remote)
                            }
                            .apply {
                                nextFocusUpId =
                                    historyManage?.id ?: favoriteCheck?.id ?: nav[tab].id
                            }
                }
                sourceButtons.forEach { it.nextFocusDownId = it.id }
                sourceButtons[0].nextFocusLeftId = sourceButtons[0].id
                sourceButtons[0].nextFocusRightId = sourceButtons[1].id
                sourceButtons[1].nextFocusLeftId = sourceButtons[0].id
                sourceButtons[1].nextFocusRightId = sourceButtons[1].id
                container.addView(sources)
                nav.forEach { it.nextFocusDownId = sourceButtons[if (account) 1 else 0].id }
                favoriteCheck?.nextFocusDownId = sourceButtons[if (account) 1 else 0].id
                historyManage?.nextFocusDownId = sourceButtons[if (account) 1 else 0].id
                val note =
                    when {
                        isAccountFavorites() ->
                            "${cloudAccount.accountLabel} · ${cloudAccount.favoriteState().message}\n与手机红果同步；长按剧集可管理账户收藏"
                        isAccountHistory() ->
                            "${cloudAccount.accountLabel} · ${cloudAccount.historyStatus}\n显示当前账号最近 50 部云端记录，续播使用账号进度"
                        isLocalHistory() -> "只保存在这台电视，管理和清空不影响账户历史"
                        else -> "只保存在这台电视，不自动上传或合并到账户收藏"
                    }
                container.addView(text(note, 13f, muted).apply { setPadding(0, 0, 0, dp(6)) })
            }
            if (tab == 5) {
                val state = cloudAccount.reservationState(reservationOnline)
                container.addView(
                    text(
                            "${cloudAccount.accountLabel} · ${state.message}\n每类读取最近 20 部预约，以下数量为当前已加载数",
                            13f,
                            muted,
                        )
                        .apply { setPadding(0, 0, 0, dp(6)) }
                )
                val filters = row()
                for (online in listOf(true, false)) {
                    val category = cloudAccount.reservationState(online)
                    val count = if (category.loaded) category.rows.size.toString() else "—"
                    reservationFilters +=
                        addButton(
                                filters,
                                "${if(online) "已上线" else "待上线"} · $count",
                                reservationOnline == online,
                            ) {
                                reservationOnline = online
                                page = 1
                                catalogFocus = ""
                                showCatalog()
                            }
                            .apply { nextFocusUpId = historyManage!!.id }
                }
                container.addView(filters)
                historyManage?.nextFocusDownId =
                    reservationFilters[if (reservationOnline) 0 else 1].id
                nav.forEach { it.nextFocusDownId = historyManage!!.id }
            }
            if (isLocalFavorites()) {
                favoriteStatus =
                    text(favoriteMonitor.status, 13f, muted).apply { setPadding(0, 0, 0, dp(6)) }
                container.addView(favoriteStatus)
            }
        }
        val body = column()
        catalogBody = body
        (tvSearchPanel?.results ?: container).addView(body, lp(-1, 0).apply { weight = 1f })
        if (localCatalog()) {
            catalog = localItems()
            ranking = null
            page = page.coerceIn(1, maxOf(1, (catalog.size + pageSize - 1) / pageSize))
            hasMore = page * pageSize < catalog.size
        }
        if (load) {
            if (tab == 0) catalogGrid(body, focusNav, focusType, loading = true)
            else {
                message(body, "正在加载…")
                if (tvSearchPanel != null) tvSearchPanel?.entry()?.requestFocus()
                else if (focusType) typeButtons[library.contentType]?.requestFocus()
                else nav[tab].requestFocus()
            }
            val requestTab = tab
            val requestPage = page
            val requestQuery = query
            val requestType = library.contentType
            work(
                {
                    val result =
                        when (requestTab) {
                            0 -> repository.home(requestPage, requestType)
                            2 -> repository.comicRanking(requestPage)
                            else -> repository.searchAll(requestQuery, requestPage)
                        }
                    result
                },
                { result ->
                    catalogLoadedPage = requestPage
                    if (requestTab == 0 && requestPage == 1)
                        library.cacheHome(requestType, result.items, result.hasMore)
                    if (
                        requestTab == 0 &&
                            requestPage == 1 &&
                            (homeFromCache || tvTools.showing)
                    ) {
                        homeScreen?.refresh?.text = "热门已更新 · 按确认查看"
                        homeScreen?.refresh?.setOnClickListener {
                            catalog = result.items
                            hasMore = result.hasMore
                            ranking = result.ranking
                            showCatalog()
                        }
                    } else {
                        catalog = result.items
                        hasMore = result.hasMore
                        ranking = result.ranking
                        val keepNav = nav.any { it.hasFocus() }
                        val keepType = typeButtons.values.any { it.hasFocus() }
                        val keepInput = searchInput?.hasFocus() == true
                        val keepSuggestion =
                            tvSearchPanel?.findFocus() ?: searchSuggestions?.findFocus()
                        rankSubtitle?.text =
                            ranking?.updatedText?.ifBlank { "来源：红果漫剧热播榜" } ?: "来源：红果漫剧热播榜"
                        body.removeAllViews()
                        catalogGrid(body, keepNav || focusNav, keepType || focusType)
                        if (keepInput) searchInput?.requestFocus()
                        else if (keepSuggestion != null) keepSuggestion.requestFocus()
                    }
                },
                { problem ->
                    if(previousCatalog.items.isNotEmpty()) {
                        catalog=previousCatalog.items
                        hasMore=previousCatalog.hasMore
                        ranking=previousCatalog.ranking
                        page=previousCatalog.page
                        catalogLoadedPage=previousCatalog.page
                    }
                    if (requestTab == 0 && requestPage == 1) {
                        homeScreen?.refresh?.text =
                            if (catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                        homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                        return@work
                    }
                    rankSubtitle?.text = "读取失败 · 保留上次内容"
                    if (problem is SearchSessionExpiredException) {
                        page = 1
                        catalogFocus = ""
                        Toast.makeText(this, "搜索结果已过期，已回到第 1 页刷新", Toast.LENGTH_LONG).show()
                        showCatalog(true)
                    } else {
                        body.removeAllViews()
                        if(catalog.isNotEmpty()) catalogGrid(body,false,false)
                        error(body, problem) { showCatalog(true) }
                        searchSuggestions?.updateNavigation()
                        if (resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                    }
                },
            )
        } else catalogGrid(body, focusNav, focusType)
    }

    private fun catalogGrid(
        body: LinearLayout,
        focusNav: Boolean,
        focusType: Boolean = false,
        loading: Boolean = false,
    ) {
        if (tab == 0 && page == 1 && collection == null) {
            renderHome(body, focusNav, focusType, loading)
            return
        }
        resumeCards.clear()
        catalogCards.clear()
        cardImages.clear()
        collectionBack = null
        if (collection != null) {
            val heading = row()
            collectionBack =
                addButton(heading, "‹ 返回首页") {
                        leaveCollection()
                        showCatalog()
                    }
                    .apply {
                        nextFocusUpId = typeButtons[library.contentType]?.id ?: nav[tab].id
                        nextFocusDownId = id
                    }
            heading.addView(
                text(
                    when (collection) {
                        "later" -> "稍后看"
                        "updates" -> "收藏有更新"
                        "hot" -> "热门发现"
                        else -> "接着看"
                    },
                    22f,
                )
            )
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId = collectionBack!!.id }
        }
        catalogPageKey =
            "$tab:${collection.orEmpty()}:$page:$query:${if(accountCatalog()) cloudAccount.historyScope else 0}:$reservationOnline:${if(tab==3) librarySources.accountFavorites else if(tab==4) librarySources.accountHistory else false}"
        if (tab == 1 && collection == null) {
            renderContinuousDiscovery(body, focusNav, focusType)
            return
        }
        val preview = SeriesPreview(this)
        selectionPreview = preview
        body.addView(preview)
        val scroll =
            ScrollView(this).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                clipToPadding = false
            }
        catalogScroll = scroll
        val list = column()
        scroll.addView(list)
        body.addView(scroll, lp(-1, 0).apply { weight = 1f })
        var restoringScroll = true
        val displayed =
            if (localCatalog()) catalog.drop((page - 1) * pageSize).take(pageSize)
            else if (tab == 0) catalog.filterNot { it.id in library.hidden() } else catalog.take(30)
        if (displayed.isEmpty()) {
            val empty =
                when {
                    tab == 1 -> if (query.isBlank()) "该分类暂无剧集，可切换题材或输入关键词搜索" else "没有找到相关剧集，换个关键词试试"
                    isAccountFavorites() ->
                        if (!cloudAccount.signedIn) "登录红果后查看账户收藏，本机收藏仍可使用"
                        else if (cloudAccount.favoriteState().loading) "正在读取账户收藏…"
                        else if (!cloudAccount.favoriteState().loaded) "账户收藏读取失败，请按上方刷新重试"
                        else "暂无已读取的账户收藏，可在手机收藏后刷新"
                    isLocalFavorites() -> "在剧集详情中加入本机收藏，喜欢的剧就会出现在这里"
                    isAccountHistory() ->
                        if (!cloudAccount.signedIn) "登录红果后查看账户历史，本机历史仍可使用"
                        else if (cloudAccount.historyLoading) "正在读取账户观看历史…"
                        else "暂无已读取的云端记录，可按上方刷新重试"
                    isLocalHistory() -> "在这台电视播放过的剧集会自动保存在这里"
                    tab == 5 ->
                        if (!cloudAccount.signedIn) "登录红果账号后，在这里同步手机预约"
                        else if (cloudAccount.reservationState(reservationOnline).loading)
                            "正在读取预约记录…"
                        else if (!cloudAccount.reservationState(reservationOnline).loaded)
                            "预约读取失败，请按上方刷新预约重试"
                        else if (reservationOnline) "当前没有已上线的预约" else "当前没有待上线的预约"
                    else -> "本页没有更多内容"
                }
            message(list, if (loading) "正在加载推荐…" else empty)
            if ((tab <= 2 || localCatalog()) && page > 1)
                addButton(list, "上一页") {
                    page--
                    showCatalog(!localCatalog())
                }
            if ((tab <= 2 || localCatalog()) && hasMore)
                addButton(list, "下一页") {
                    page++
                    showCatalog(!localCatalog())
                }
            if (resumeCards.isNotEmpty()) {
                typeButtons.values.forEach { it.nextFocusDownId = resumeCards.first().id }
                resumeCards.forEach { it.nextFocusDownId = it.id }
            }
            if (focusType) typeButtons[library.contentType]?.requestFocus()
            else if (collectionBack != null && !focusNav) collectionBack?.requestFocus()
            else if (sourceButtons.isNotEmpty() && !focusNav)
                sourceButtons[if (isAccountHistory() || isAccountFavorites()) 1 else 0]
                    .requestFocus()
            else if (focusNav || resumeCards.isEmpty()) nav[tab].requestFocus()
            else resumeCards.first().requestFocus()
            return
        }
        val cards = mutableListOf<View>()
        val count = 6
        val gap = dp(14)
        val width =
            ((resources.displayMetrics.widthPixels * .9f - gap * (count - 1)) / count).toInt()
        displayed.chunked(count).forEach { items ->
            val line = row()
            line.gravity = Gravity.TOP
            items.forEachIndexed { columnIndex, item ->
                val card = column()
                card.setPadding(dp(4), dp(4), dp(4), dp(9))
                focusStyle(card)
                card.contentDescription = item.title
                card.tag = item.id
                catalogCards[item.id] = card
                val position = if (tab == 2) ranking?.positions?.get(item.id) else null
                val artwork = FrameLayout(this)
                card.addView(artwork, lp(-1, ((width - dp(8)) / TvStyle.POSTER_ASPECT).toInt()))
                val image =
                    ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
                loadCover(image, item.cover)
                cardImages[item.id] = image to item.cover
                if (tab == 2) {
                    val rankLabel = position?.rank?.let { "第 $it 名" } ?: "名次暂无"
                    artwork.addView(
                        text(rankLabel, 15f).apply {
                            setTypeface(null, Typeface.BOLD)
                            setPadding(dp(8), dp(5), dp(8), dp(5))
                            background =
                                rounded(
                                    if ((position?.rank ?: Int.MAX_VALUE) <= 3) accent
                                    else Color.rgb(28, 31, 39)
                                )
                        },
                        FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START),
                    )
                    card.contentDescription =
                        "$rankLabel，${item.title}，${position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
                }
                card.addView(
                    text(item.title, 14f).apply {
                        maxLines = 2
                        minLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(3), dp(7), dp(3), 0)
                    },
                    lp(-1, -2).apply { weight = 1f },
                )
                val cloud = if (isAccountHistory()) cloudAccount.progress(item.id) else null
                val progress =
                    cloud?.let {
                        WatchProgress(
                            it.series,
                            it.episodeId,
                            it.episodeIndex,
                            it.position,
                            it.duration,
                            false,
                            it.updatedAt,
                        )
                    }
                        ?: if (isLocalHistory() || collection == "resume") library.progress(item.id)
                        else null
                val summary =
                    when {
                        tab == 5 -> collectionEpisodeLabel(item) + "\n" + selectionStatus(item)
                        tab == 3 -> collectionEpisodeLabel(item)
                        tab == 4 ->
                            collectionEpisodeLabel(item) +
                                (progress?.let { " · ${formatTime(it.position)}" } ?: "")
                        tab == 2 -> position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"
                        else -> item.badge
                    }
                val badge =
                    text(summary, 12f, if (tab == 2 || isLocalFavorites()) accent else muted)
                        .apply {
                            maxLines = if (tab == 5) 3 else if (tab == 3 || tab == 4) 2 else 1
                            minLines = maxLines
                            minHeight =
                                dp(if (tab == 5) 50 else if (tab == 3 || tab == 4) 34 else 19)
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(dp(3), 0, 0, 0)
                        }
                card.addView(badge, lp(-1, -2))
                if (isLocalFavorites()) favoriteBadges[item.id] = badge
                card.setOnClickListener {
                    if (reservationNotice(item, card)) return@setOnClickListener
                    catalogFocus = item.id
                    openDetail(
                        item,
                        isLocalHistory() ||
                            isAccountHistory() ||
                            collection == "resume" ||
                            collection == "updates",
                        cloudProgress =
                            if (isAccountHistory()) cloudAccount.progress(item.id) else null,
                    )
                }
                card.setOnLongClickListener {
                    catalogFocus = item.id
                    quickActions(item, card)
                    true
                }
                card.setOnKeyListener { _, key, event ->
                    if (key == KeyEvent.KEYCODE_MENU) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            catalogFocus = item.id
                            quickActions(item, card)
                        }
                        true
                    } else false
                }
                card.setOnFocusChangeListener { _, focused ->
                    card.background = TvStyle.card(this, focused)
                    if (focused) {
                        catalogFocus = item.id
                        loadCover(image, item.cover)
                        preview.show(item, selectionStatus(item))
                        if (!restoringScroll)
                            scroll.post {
                                scroll.smoothScrollTo(
                                    0,
                                    when {
                                        line.top < scroll.scrollY -> line.top
                                        line.bottom > scroll.scrollY + scroll.height ->
                                            (line.bottom - scroll.height).coerceAtLeast(0)
                                        else -> scroll.scrollY
                                    },
                                )
                            }
                    }
                }
                // The row measures its tallest card, then stretches siblings to keep badges
                // aligned.
                line.addView(
                    card,
                    lp(width, -1).apply { if (columnIndex < count - 1) rightMargin = gap },
                )
                cards += card
            }
            list.addView(line, lp(-1, -2).apply { bottomMargin = dp(10) })
        }
        val paging = row()
        paging.gravity = Gravity.CENTER
        var prev: View? = null
        var next: View? = null
        if (tab <= 2 || localCatalog()) {
            prev =
                addButton(paging, "上一页") {
                        if (page > 1) {
                            page--
                            catalogFocus = ""
                            showCatalog(!localCatalog())
                        }
                    }
                    .apply {
                        isEnabled = page > 1
                        isFocusable = page > 1
                        alpha = if (page > 1) 1f else .4f
                    }
            paging.addView(
                text(
                        if (localCatalog())
                            "第 $page / ${maxOf(1,(catalog.size+pageSize-1)/pageSize)} 页"
                        else if (tab == 2 && ranking != null) "第 $page / ${ranking!!.totalPages} 页"
                        else "第 $page 页",
                        14f,
                        muted,
                    )
                    .apply { gravity = Gravity.CENTER },
                lp(dp(if (tab == 2) 135 else 95), dp(44)),
            )
            next =
                addButton(paging, "下一页") {
                        if (hasMore) {
                            page++
                            catalogFocus = ""
                            showCatalog(!localCatalog())
                        }
                    }
                    .apply {
                        isEnabled = hasMore
                        isFocusable = hasMore
                        alpha = if (hasMore) 1f else .4f
                    }
            if (collection == "hot" && !hasMore && collectionReturn?.hasMore == true) {
                next =
                    addButton(paging, "下一批热门") {
                        collection = null
                        collectionReturn = null
                        page = 2
                        catalogFocus = ""
                        showCatalog(true)
                    }
            }
            list.addView(paging, lp(-1, dp(50)))
        }
        cards.forEachIndexed { index, card ->
            card.nextFocusLeftId = if (index % count == 0) card.id else cards[index - 1].id
            card.nextFocusRightId =
                if (index % count == count - 1 || index == cards.lastIndex) card.id
                else cards[index + 1].id
            card.nextFocusUpId =
                if (index < count)
                    (searchInput?.id
                        ?: sourceButtons
                            .getOrNull(if (isAccountHistory() || isAccountFavorites()) 1 else 0)
                            ?.id
                        ?: collectionBack?.id
                        ?: reservationFilters.getOrNull(if (reservationOnline) 0 else 1)?.id
                        ?: typeButtons[library.contentType]?.id
                        ?: rankRefresh?.id
                        ?: favoriteCheck?.id
                        ?: historyManage?.id
                        ?: resumeCards.getOrNull(minOf(index, 2))?.id
                        ?: nav[tab].id)
                else cards[index - count].id
            card.nextFocusDownId =
                if (index + count < cards.size) cards[index + count].id
                else if (index / count < cards.lastIndex / count) cards.last().id
                else
                    next?.takeIf { it.isFocusable }?.id
                        ?: prev?.takeIf { it.isFocusable }?.id
                        ?: card.id
        }
        nav.forEach {
            it.nextFocusDownId =
                searchInput?.id
                    ?: typeButtons[library.contentType]?.id
                    ?: rankRefresh?.id
                    ?: favoriteCheck?.id
                    ?: historyManage?.id
                    ?: cards.first().id
        }
        rankRefresh?.nextFocusDownId = cards.first().id
        historyManage?.nextFocusDownId =
            reservationFilters.getOrNull(if (reservationOnline) 0 else 1)?.id ?: cards.first().id
        reservationFilters.forEach { it.nextFocusDownId = cards.first().id }
        favoriteCheck?.nextFocusDownId = cards.first().id
        collectionBack?.nextFocusDownId = cards.first().id
        resumeCards.forEachIndexed { index, v ->
            v.nextFocusDownId = cards[minOf(index, cards.lastIndex)].id
        }
        if (resumeCards.isNotEmpty())
            cards.take(count).forEachIndexed { index, v ->
                v.nextFocusUpId = resumeCards[minOf(index, resumeCards.lastIndex)].id
            }
        else
            homeUpdates?.let { updates ->
                updates.nextFocusUpId = typeButtons[library.contentType]?.id ?: nav[0].id
                updates.nextFocusDownId = cards.first().id
                cards.take(count).forEach { it.nextFocusUpId = updates.id }
            }
        typeButtons.values.forEach {
            it.nextFocusDownId =
                searchInput?.id
                    ?: collectionBack?.id
                    ?: resumeCards.firstOrNull()?.id
                    ?: homeUpdates?.id
                    ?: cards.first().id
        }
        if (sourceButtons.isNotEmpty()) {
            val selected = sourceButtons[if (isAccountHistory() || isAccountFavorites()) 1 else 0]
            nav.forEach { it.nextFocusDownId = selected.id }
            sourceButtons.forEach { it.nextFocusDownId = cards.first().id }
            favoriteCheck?.nextFocusDownId = selected.id
            historyManage?.nextFocusDownId = selected.id
        }
        searchInput?.nextFocusDownId = cards.first().id
        searchButton?.nextFocusDownId = cards[minOf(4, cards.lastIndex)].id
        searchActions.forEach { it.nextFocusDownId = cards[minOf(4, cards.lastIndex)].id }
        if (focusType) typeButtons[library.contentType]?.requestFocus()
        else if (focusNav) nav[tab].requestFocus()
        else if (
            resumeCards.isNotEmpty() &&
                (catalogFocus.isEmpty() || catalogFocus.startsWith("resume:"))
        )
            (resumeCards.firstOrNull { it.tag == catalogFocus } ?: resumeCards.first())
                .requestFocus()
        else cards[displayed.indexOfFirst { it.id == catalogFocus }.coerceAtLeast(0)].requestFocus()
        val savedScroll = scrollPositions[catalogPageKey]
        scroll.post {
            if (catalogScroll !== scroll) return@post
            cards.forEach { view ->
                val card = view as LinearLayout
                val image = card.getChildAt(0)
                val textHeight =
                    card.paddingTop +
                        card.paddingBottom +
                        (1 until card.childCount).sumOf { card.getChildAt(it).measuredHeight }
                val target =
                    minOf(
                        ((width - dp(8)) / TvStyle.POSTER_ASPECT).toInt(),
                        (scroll.height - textHeight - dp(4)).coerceAtLeast(dp(48)),
                    )
                if (image.layoutParams.height != target)
                    image.layoutParams = image.layoutParams.apply { height = target }
            }
            scroll.post settle@{
                if (catalogScroll !== scroll) return@settle
                savedScroll?.let { scroll.scrollTo(0, it) }
                (cards.firstOrNull { it.hasFocus() }?.parent as? View)?.let { line ->
                    val y =
                        when {
                            line.top < scroll.scrollY -> line.top
                            line.bottom > scroll.scrollY + scroll.height ->
                                if (line.height >= scroll.height) line.top
                                else line.bottom - scroll.height
                            else -> scroll.scrollY
                        }
                    scroll.scrollTo(0, y.coerceAtLeast(0))
                }
                restoringScroll = false
            }
        }
    }

    private fun renderContinuousDiscovery(
        body: LinearLayout,
        focusNav: Boolean,
        focusType: Boolean,
    ) {
        val requestQuery = query
        continuousKey = requestQuery
        val upper = searchInput ?: nav[tab]
        lateinit var browser: ContinuousCatalogView
        browser =
            ContinuousCatalogView(
                this,
                if (tvSearchPanel != null) {
                    if (widthDp() < 800) 2 else if (widthDp() < 1200) 3 else 4
                } else 6,
                upper,
                { item, recycled ->
                    val card = recycled as? CatalogPosterCard ?: CatalogPosterCard(this)
                    card.show(item)
                    loadCover(card.image, item.cover)
                    card
                },
                { item -> catalogFocus = item.id },
                { item ->
                    catalogFocus = item.id
                    openDetail(item, false)
                },
                { item, anchor ->
                    catalogFocus = item.id
                    quickActions(item, anchor)
                },
                {
                    val nextPage = page + 1
                    if (nextPage > 100) {
                        hasMore = false
                        browser.append(emptyList(), false)
                    } else
                        work(
                            { repository.searchAll(requestQuery, nextPage) },
                            { result ->
                                if (continuousCatalog === browser) {
                                    val seen = catalog.mapTo(HashSet()) { it.id }
                                    catalog = catalog + result.items.filter { seen.add(it.id) }
                                    page = nextPage
                                    catalogLoadedPage = page
                                    hasMore = result.hasMore && page < 100
                                    browser.append(result.items, hasMore)
                                }
                            },
                            { problem ->
                                if (continuousCatalog === browser) {
                                    browser.fail(problem)
                                    if (problem is SearchSessionExpiredException)
                                        tvTools.confirm(
                                            "搜索结果已过期",
                                            "当前列表已保留，是否重新搜索？",
                                            browser.next,
                                        ) {
                                            runSearch(requestQuery)
                                        }
                                }
                            },
                        )
                },
            )
        continuousCatalog = browser
        body.addView(browser, lp(-1, 0).apply { weight = 1f })
        val position =
            catalogFocus
                .takeIf { it.isNotBlank() }
                ?.let { id ->
                    continuousPositions[continuousKey]?.takeIf { it.id == id }
                        ?: ContinuousCatalogView.Position(id, 0)
                }
        browser.show(catalog, hasMore && page < 100, position)
        nav.forEach { it.nextFocusDownId = searchInput?.id ?: upper.id }
        searchInput?.nextFocusDownId = browser.grid.id
        searchButton?.nextFocusDownId = browser.grid.id
        searchActions.forEach { it.nextFocusDownId = browser.grid.id }
        searchSuggestions?.updateNavigation()
        tvSearchPanel?.let { panel ->
            panel.updateNavigation()
            browser.setSideEntry(panel.middleEntry() ?: panel.entry())
            nav.forEach { it.nextFocusDownId = panel.entry().id }
        }
        if (focusNav) nav[tab].requestFocus()
        else if (focusType) upper.requestFocus() else browser.enter()
    }

    private fun reservationNotice(series: Series, anchor: View): Boolean {
        if (tab != 5) return false
        val row =
            cloudAccount.reservationState(reservationOnline).rows.firstOrNull {
                it.series.id == series.id
            } ?: return true
        if (row.online) return false
        tvTools.info(series.title, reservationDescription(row) + "\n上线后可在「已上线」中打开观看。", anchor)
        return true
    }

    private fun reservationDescription(row: CloudReservation): String {
        if (row.online) return "已上线 · 可观看"
        if (row.publishText.isNotBlank()) return "待上线 · ${row.publishText}"
        return if (row.publishAt > 0)
            "待上线 · 预计 " +
                java.text
                    .SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(row.publishAt))
        else "待上线 · 上线时间暂未公布"
    }

    private fun selectionStatus(series: Series): String {
        if (tab == 5)
            return cloudAccount
                .reservationState(reservationOnline)
                .rows
                .firstOrNull { it.series.id == series.id }
                ?.let(::reservationDescription)
                .orEmpty()
        if (isAccountHistory())
            return cloudAccount
                .progress(series.id)
                ?.let { "账号进度 · 第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }
                .orEmpty()
        val progress = library.progress(series.id)
        return listOf(
                if (library.favorite(series.id)) "已在本机收藏" else "",
                if (library.queued(series.id)) "稍后看" else "",
                if (library.watched(series.id)) "整剧已看完"
                else
                    progress
                        ?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }
                        .orEmpty(),
            )
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun renderHome(
        body: LinearLayout,
        focusNav: Boolean,
        focusType: Boolean,
        loading: Boolean,
    ) {
        homeUpdateCount = library.updatedFavorites()
        val preview = SeriesPreview(this)
        selectionPreview = preview
        body.addView(preview)
        val top = if (inlineHomeFilters()) nav[0] else typeButtons.getValue(library.contentType)
        val home =
            HomeScreen(
                this,
                top,
                ::loadCover,
                { item, label ->
                    preview.show(
                        item,
                        if (label.startsWith("第 ")) selectionStatus(item)
                        else
                            listOf(label, selectionStatus(item))
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                    )
                },
                { item, resume -> openDetail(item, resume) },
                ::quickActions,
                { catalogFocus = it },
            )
        homeScreen = home
        body.addView(home, lp(-1, 0).apply { weight = 1f })
        val hot = catalog.filterNot { it.id in library.hidden() }
        home.render(
            homeShelves(),
            catalogFocus,
            !focusNav && !focusType,
            if (hasMore) {
                {
                    page = 2
                    catalogFocus = ""
                    showCatalog(true)
                }
            } else null,
            homePosition,
        )
        home.refresh.text =
            if (loading) if (homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        typeButtons.values.forEach { it.nextFocusDownId = home.firstId() }
        if (inlineHomeFilters()) nav.forEach { it.nextFocusDownId = home.firstId() }
        if (focusNav) nav[0].requestFocus()
        else if (focusType) typeButtons.getValue(library.contentType).requestFocus()
        if (hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged = true
            body.post {
                android.util.Log.i(
                    "HongguoTV",
                    "Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}",
                )
            }
        }
        if (launchStarted > 0) {
            val started = launchStarted
            launchStarted = 0
            body.post {
                android.util.Log.i(
                    "HongguoTV",
                    "Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}",
                )
            }
        }
    }

    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent = library.history().filterNot { library.watched(it.series.id) }
        val updates = library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0) > 0 }
        val later = library.later()
        val hidden = library.hidden()
        val hot = catalog.filterNot { it.id in hidden }
        return listOf(
            HomeScreen.Shelf(
                "resume",
                "接着看",
                recent.take(8).map { p ->
                    HomeScreen.Entry(
                        p.series,
                        "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",
                        true,
                        if (p.duration > 0) (p.position * 100 / p.duration).toInt().coerceIn(0, 100)
                        else null,
                    )
                },
                if (recent.size > 8) {
                    { showCollection("resume") }
                } else null,
            ),
            HomeScreen.Shelf(
                "updates",
                "收藏有更新",
                updates.take(8).map { HomeScreen.Entry(it, library.favoriteLabel(it.id), true) },
                if (updates.size > 8) {
                    { showCollection("updates") }
                } else null,
            ),
            HomeScreen.Shelf(
                "later",
                "稍后看",
                later.take(10).map { HomeScreen.Entry(it, "稍后看 · 长按管理") },
                if (later.size > 10) {
                    { showCollection("later") }
                } else null,
            ),
            HomeScreen.Shelf(
                "hot",
                "热门发现 · ${library.contentType.label}",
                hot.take(10).map { HomeScreen.Entry(it, it.badge.ifBlank { "查看剧集" }) },
                if (hot.size > 10) {
                    { showCollection("hot") }
                } else null,
            ),
        )
    }

    private fun refreshLibraryCards(changedId: String, removed: Boolean = false) {
        if (screen != "catalog") return
        val home = homeScreen
        if (home != null) {
            home.updateShelves(homeShelves(), requestFocus = true)
            home.refreshSelection()
            return
        }
        if (removed && (localCatalog() || tab == 0)) {
            val oldIds = catalogCards.keys.toList()
            val index = oldIds.indexOf(changedId).coerceAtLeast(0)
            if (localCatalog()) {
                catalog = localItems()
                page = page.coerceIn(1, maxOf(1, (catalog.size + pageSize - 1) / pageSize))
                hasMore = page * pageSize < catalog.size
            }
            val remaining =
                if (localCatalog()) catalog.drop((page - 1) * pageSize).take(pageSize)
                else catalog.filterNot { it.id in library.hidden() }
            catalogFocus =
                remaining
                    .getOrNull(index.coerceAtMost((remaining.size - 1).coerceAtLeast(0)))
                    ?.id
                    .orEmpty()
            val y = catalogScroll?.scrollY ?: 0
            scrollPositions[catalogPageKey] = y
            artwork.cancelPage()
            favoriteBadges.clear()
            catalogBody?.let { body ->
                body.removeAllViews()
                catalogGrid(body, false)
            }
        } else {
            catalog
                .firstOrNull { it.id == changedId }
                ?.let { selectionPreview?.show(it, selectionStatus(it)) }
            library
                .favorites()
                .firstOrNull { it.id == changedId }
                ?.let { favoriteBadges[changedId]?.text = collectionEpisodeLabel(it) }
        }
    }

    private fun quickActions(series: Series, anchor: View) {
        if (reservationNotice(series, anchor)) return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions +=
            (if (
                library.progress(series.id) != null ||
                    isAccountHistory() && cloudAccount.progress(series.id) != null
            )
                "继续观看"
            else "直接播放") to
                {
                    openDetail(
                        series,
                        true,
                        cloudProgress =
                            if (isAccountHistory()) cloudAccount.progress(series.id) else null,
                    )
                }
        actions += "查看剧集详情" to { openDetail(series) }
        actions +=
            cloudAccount.favoriteActionLabel(series.id) to { accountFavoriteAction(series, anchor) }
        actions +=
            (if (library.favorite(series.id)) "取消本机收藏" else "加入本机收藏") to
                {
                    val added = library.toggle(series)
                    Toast.makeText(this, if (added) "已加入本机收藏" else "已取消本机收藏", Toast.LENGTH_SHORT)
                        .show()
                    refreshLibraryCards(
                        series.id,
                        !added && (isLocalFavorites() || collection == "updates"),
                    )
                    if (added) favoriteMonitor.check()
                }
        if (isLocalHistory()) actions += "管理观看记录" to { manageHistory(series, anchor) }
        actions += "本剧工具 · 跳过 / 分组" to { featureTools.showSeries(series) }
        tvTools.choose(
            series.title,
            actions.map { it.first },
            anchor,
            { actions[it].second() },
            compact = true,
        )
    }

    private fun accountFavoriteAction(series: Series, anchor: View?) {
        if (!cloudAccount.signedIn) {
            cloudAccount.show()
            return
        }
        if (cloudAccount.favoriteState().loading) {
            Toast.makeText(this, "账户收藏正在处理，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (cloudAccount.favoriteNeedsRefresh(series.id)) {
            cloudAccount.refreshFavorites()
            return
        }
        val add = !cloudAccount.accountFavorite(series.id)
        val scope = cloudAccount.historyScope
        tvTools.confirm(
            if (add) "加入账户收藏" else "取消账户收藏",
            "${if(add) "将《${series.title}》加入" else "将《${series.title}》从"}${cloudAccount.accountLabel}的红果收藏${if(add) "，手机端也会同步显示" else "中移除"}。本机收藏保持独立。",
            anchor,
        ) {
            if (scope != cloudAccount.historyScope || !cloudAccount.signedIn) return@confirm
            cloudAccount.changeFavorite(series, add) { message ->
                tvTools.info("账户收藏", message, anchor)
            }
        }
    }

    private fun refreshFavoriteLabels() {
        if (screen != "catalog" || !library.isLoaded) return
        nextSeasonButton?.text = "下一季 · ${favoriteSeasons.pending().size}"
        favoriteBadges.forEach { (id, label) ->
            val value =
                library.favorites().firstOrNull { it.id == id }?.let(::collectionEpisodeLabel)
                    ?: library.favoriteLabel(id)
            if (label.text.toString() != value) label.text = value
        }
        favoriteStatus?.text = favoriteMonitor.status
        favoriteCheck?.text = if (favoriteMonitor.running) "正在检查…" else "检查更新"
        homeUpdates?.text = "收藏更新 ${library.updatedFavorites()} 部"
        val home = homeScreen
        if (
            screen == "catalog" &&
                tab == 0 &&
                page == 1 &&
                collection == null &&
                home != null &&
                library.updatedFavorites() != homeUpdateCount
        ) {
            // A stable resume key can be restored without moving the user's selection.
            if (
                !tvTools.showing &&
                    currentFocus?.tag?.toString()?.startsWith("resume:") == true
            ) {
                homeUpdateCount = library.updatedFavorites()
                home.updateShelves(homeShelves())
            } else {
                home.refresh.text = "收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }

    private fun saveDiscoveryDraft() {
        (searchInput as? EditText)?.let { searchDraft = it.text.toString() }
    }

    private fun returnToDiscovery() {
        query = ""
        searchDraft = ""
        tvSearchOpen = false
        page = 1
        catalog = emptyList()
        hasMore = false
        catalogFocus = ""
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput?.windowToken, 0)
        showCatalog(load = false)
    }

    // 统一接收键盘、历史等来源的搜索词，保存历史并重置到第一页。
    private fun runSearch(value: String) = runSearch(value, library.contentType)

    private fun runSearch(value: String, type: ContentType) {
        if(library.contentType != type) library.contentType=type
        val clean = value.trim().take(80)
        if (clean.isEmpty()) {
            returnToDiscovery()
            return
        }
        (searchInput as? EditText)?.let {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
        if(clean != query) {
            catalog=emptyList(); hasMore=false; ranking=null; catalogLoadedPage=1
        }
        library.rememberSearch(clean, type)
        tvSearchOpen = true
        searchDraft = clean
        query = clean
        tab = 1
        page = 1
        catalogFocus = ""
        showCatalog(true)
    }

    private fun showSearchHistory(anchor: View) {
        val history = library.searches().distinctBy { it.query }
        if (history.isEmpty()) {
            tvTools.info("搜索历史", "搜索过的剧名会保存在这里，最多保留 20 条。", anchor)
            return
        }
        tvTools.choose(
            "搜索历史",
            history.map { it.query },
            anchor,
            { index -> runSearch(history[index].query) },
            "清空历史",
        ) {
            tvTools.confirm("清空搜索历史", "清除本机保存的搜索词？", anchor) { library.clearSearches() }
        }
    }

    private fun manageHistory(series: Series, anchor: View?) {
        tvTools.choose(
            series.title,
            listOf("继续观看", if (library.watched(series.id)) "恢复未看状态" else "标记整剧已看", "删除这条记录"),
            anchor,
            { index ->
                when (index) {
                    0 -> openDetail(series, true)
                    1 -> {
                        library.setWatched(series.id, !library.watched(series.id))
                        catalogFocus = series.id
                        showCatalog()
                    }
                    2 ->
                        tvTools.confirm("删除本机观看记录", "删除《${series.title}》的本机观看进度？收藏会保留。", anchor) {
                            library.removeHistory(series.id)
                            showCatalog()
                        }
                }
            },
        )
    }

    // 作品详情入口：衔接来源页面、详情数据与后续观看操作。
    private fun openDetail(
        series: Series,
        resume: Boolean = false,
        autoplay: Boolean = true,
        cloudProgress: CloudProgress? = null,
        fromRelated: Boolean = false,
    ) {
        if (!fromRelated) detailTrail.clear()
        relatedSection = null
        val accountScope = cloudAccount.historyScope
        pendingDetail = series
        detailAutoplay = autoplay
        invalidatePage()
        releaseCatalogViews()
        screen = "detail"
        directPlayback = resume
        detail = null
        synopsisExpanded = false
        val body = base(series.title)
        message(body, "正在加载剧集…")
        work(
            { repository.detail(series.id) },
            { result ->
                detail = result
                library.observeFavorite(
                    series.id,
                    result.episodes.size,
                    System.currentTimeMillis(),
                    acknowledge = true,
                )
                if (
                    cloudProgress != null &&
                        (accountScope != cloudAccount.historyScope || !cloudAccount.signedIn)
                ) {
                    showCatalog()
                    return@work
                }
                val remote = if (resume) cloudProgress else null
                val progress =
                    remote?.let {
                        WatchProgress(
                            it.series,
                            it.episodeId,
                            it.episodeIndex,
                            it.position,
                            it.duration,
                            false,
                            it.updatedAt,
                        )
                    } ?: library.progress(series.id)
                val target =
                    ResumePlayback.target(
                        result.episodes,
                        progress?.episodeId,
                        progress?.position ?: 0,
                        progress?.completed == true,
                    )
                episodeIndex = target.index
                group = episodeIndex / 20
                if (resume) playEpisode(target.index, target.position, autoplay = detailAutoplay)
                else showDetail(false)
            },
            { problem ->
                error(body, problem) {
                    if (cloudProgress != null && accountScope != cloudAccount.historyScope)
                        showCatalog()
                    else openDetail(series, resume, detailAutoplay, cloudProgress, fromRelated)
                }
            },
        )
    }

    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage()
        releaseCatalogViews()
        screen = "detail"
        val data = detail ?: return showCatalog()
        val body = base("剧集详情")
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = column()
        scroll.addView(content)
        body.addView(scroll, lp(-1, 0).apply { weight = 1f })
        val hero = row()
        hero.gravity = Gravity.TOP
        hero.setPadding(0, dp(12), 0, dp(12))
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        hero.addView(image, lp(dp(123), dp(174)).apply { rightMargin = dp(24) })
        loadCover(image, data.series.cover)
        val info = column()
        hero.addView(info, lp(0, -2).apply { weight = 1f })
        info.addView(
            text(data.series.title, 26f).apply {
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        info.addView(
            text("${data.episodes.size} 集  ·  ${data.series.tags}", 13f, muted).apply {
                maxLines = 1
                setPadding(0, dp(8), 0, dp(8))
            }
        )
        val description = data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis =
            text(description, 15f, muted).apply {
                maxLines = if (synopsisExpanded) 20 else 2
                ellipsize = TextUtils.TruncateAt.END
            }
        info.addView(synopsis)
        val actions = row()
        actions.setPadding(0, dp(12), 0, 0)
        info.addView(actions)
        val progress = library.progress(data.series.id)
        val resumeIndex =
            progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n >= 0 } } ?: 0
        val watched = library.watched(data.series.id)
        val continueTarget =
            ResumePlayback.target(
                data.episodes,
                progress?.episodeId,
                progress?.position ?: 0,
                progress?.completed == true,
            )
        val play =
            addButton(
                actions,
                if (watched) "重新观看"
                else if (progress != null) "继续第 ${continueTarget.index+1} 集" else "开始观看",
            ) {
                playEpisode(
                    if (watched) 0 else continueTarget.index,
                    if (watched) 0 else continueTarget.position,
                )
            }
        lateinit var detailComments: TextView
        detailComments =
            addButton(actions, "评论 · 第 ${episodeIndex+1} 集") { social.showComments(detailComments) }
        val secondary = row().apply { setPadding(0, dp(8), 0, 0) }
        info.addView(secondary)
        accountFavoriteButton =
            addButton(secondary, cloudAccount.favoriteActionLabel(data.series.id)) {
                accountFavoriteAction(data.series, accountFavoriteButton)
            }
        lateinit var favorite: TextView
        favorite =
            addButton(secondary, if (library.favorite(data.series.id)) "已在本机收藏" else "加入本机收藏") {
                favoriteMonitor.stop()
                val saved = library.toggle(data.series)
                if (saved)
                    library.observeFavorite(
                        data.series.id,
                        data.episodes.size,
                        System.currentTimeMillis(),
                        acknowledge = true,
                    )
                favorite.text = if (saved) "已在本机收藏" else "加入本机收藏"
            }
        lateinit var synopsisButton: TextView
        synopsisButton =
            addButton(secondary, if (synopsisExpanded) "收起简介" else "完整简介") {
                val previousScroll = scroll.scrollY
                synopsisExpanded = !synopsisExpanded
                synopsis.maxLines = if (synopsisExpanded) 20 else 2
                synopsisButton.text = if (synopsisExpanded) "收起简介" else "完整简介"
                scroll.post { scroll.scrollTo(0, previousScroll) }
            }
        val seriesTools = addButton(secondary, "本剧工具") { featureTools.showSeries(data.series) }
        content.addView(hero)
        val groupRow = row()
        groupRow.setPadding(0, dp(3), 0, dp(5))
        val maxGroup = data.episodes.lastIndex / 20
        group = group.coerceIn(0, maxGroup)
        val previous =
            addButton(groupRow, "‹ 上一组") {
                    if (group > 0) {
                        group--
                        showDetail(true)
                    }
                }
                .apply {
                    isEnabled = group > 0
                    isFocusable = group > 0
                    alpha = if (group > 0) 1f else .4f
                }
        groupRow.addView(
            text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集", 16f).apply {
                gravity = Gravity.CENTER
            },
            lp(dp(190), dp(42)),
        )
        addButton(groupRow, "下一组 ›") {
                if (group < maxGroup) {
                    group++
                    showDetail(true)
                }
            }
            .apply {
                isEnabled = group < maxGroup
                isFocusable = group < maxGroup
                alpha = if (group < maxGroup) 1f else .4f
            }
        lateinit var jump: TextView
        jump =
            addButton(groupRow, "跳转集数") {
                tvTools.episodePicker(data.episodes.size, episodeIndex, jump) { target ->
                    episodeIndex = target
                    group = target / 20
                    showDetail(true)
                }
            }
        content.addView(groupRow)
        content.addView(
            text("橙色为当前集 · 上次为本机续播位置 · 方向键选择，确定播放", 12f, muted).apply { setPadding(0, 0, 0, dp(6)) }
        )
        val episodes = mutableListOf<View>()
        (group * 20 until minOf((group + 1) * 20, data.episodes.size))
            .toList()
            .chunked(10)
            .forEach { numbers ->
                val line = row()
                numbers.forEach { index ->
                    val item =
                        button(
                            "${index+1}" +
                                (if (index == episodeIndex) "\n当前"
                                else if (index == resumeIndex && progress != null)
                                    if (progress.completed) "\n已看" else "\n上次"
                                else ""),
                            index == episodeIndex,
                        ) {
                            playEpisode(
                                index,
                                if (
                                    progress?.episodeId == data.episodes[index] &&
                                        !progress.completed
                                )
                                    progress.position
                                else 0,
                            )
                        }
                    item.textSize = 12f
                    item.setPadding(dp(2), dp(3), dp(2), dp(3))
                    item.contentDescription = "第 ${index+1} 集"
                    line.addView(
                        item,
                        lp(0, dp(42)).apply {
                            weight = 1f
                            rightMargin = dp(6)
                        },
                    )
                    episodes += item
                }
                repeat(10 - numbers.size) {
                    line.addView(
                        Space(this),
                        lp(0, dp(42)).apply {
                            weight = 1f
                            rightMargin = dp(6)
                        },
                    )
                }
                content.addView(line, lp(-1, dp(49)))
            }
        episodes.forEachIndexed { i, v ->
            v.nextFocusLeftId = if (i % 10 == 0) v.id else episodes[i - 1].id
            v.nextFocusRightId =
                if (i % 10 == 9 || i == episodes.lastIndex) v.id else episodes[i + 1].id
            if (i >= 10) v.nextFocusUpId = episodes[i - 10].id
            v.nextFocusDownId = if (i + 10 < episodes.size) episodes[i + 10].id else v.id
        }
        val primary = listOf(play, detailComments)
        play.setTextColor(accent)
        val secondaryActions = listOf(accountFavoriteButton!!, favorite, synopsisButton, seriesTools)
        secondaryActions.forEach {
            it.textSize = 13f
            it.setTextColor(muted)
        }
        primary.forEachIndexed { i, v ->
            v.nextFocusLeftId = primary.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = primary.getOrNull(i + 1)?.id ?: v.id
            v.nextFocusDownId = secondaryActions[minOf(i, secondaryActions.lastIndex)].id
        }
        secondaryActions.forEachIndexed { i, v ->
            v.nextFocusUpId = primary[minOf(i, primary.lastIndex)].id
            v.nextFocusLeftId = secondaryActions.getOrNull(i - 1)?.id ?: v.id
            v.nextFocusRightId = secondaryActions.getOrNull(i + 1)?.id ?: v.id
            v.nextFocusDownId = jump.id
        }
        episodes.take(10).forEach { it.nextFocusUpId = jump.id }
        lateinit var section: RelatedWorksView
        var relatedPage: RelatedWorksPage? = null
        fun loadRelated(page: Int = 1, force: Boolean = false) {
            if (page == 1) section.loading() else section.loadingMore()
            work(
                { repository.relatedComplete(data.series, page, force) },
                { result -> relatedPage = result; section.show(result) },
                {
                    val before = relatedPage
                    if (before != null && page > 1)
                        section.show(before.copy(nextPage = page, searchComplete = false,
                            message = "后续搜索未完成，已保留当前结果；可加载更多重试，或刷新重新搜索"))
                    else section.failed()
                },
            )
        }
        section =
            RelatedWorksView(
                this,
                episodes,
                ::loadCover,
                cloudAccount::reservationLabel,
                { related, anchor ->
                    if (related.upcoming) {
                        if (!cloudAccount.signedIn) cloudAccount.show()
                        else
                            tvTools.choose(
                                related.series.title,
                                listOf(cloudAccount.reservationLabel(related)),
                                anchor,
                                { _ ->
                                    val scope = cloudAccount.historyScope
                                    cloudAccount.reserve(related) { message ->
                                        if (
                                            screen == "detail" &&
                                                detail?.series?.id == data.series.id &&
                                                scope == cloudAccount.historyScope
                                        )
                                            tvTools.info("预约状态", message, anchor)
                                    }
                                },
                            )
                    } else {
                        if (detailTrail.size >= 10) detailTrail.removeFirst()
                        detailTrail.addLast(DetailVisit(data, episodeIndex, group))
                        openDetail(related.series, fromRelated = true)
                    }
                },
                { loadRelated(force = true) },
                { next -> loadRelated(next) },
            )
        relatedSection = section
        content.addView(section)
        if (cloudAccount.signedIn && cloudAccount.reservationState(false).message == "尚未读取预约记录")
            cloudAccount.refreshReservations()
        loadRelated()
        if (
            cloudAccount.signedIn &&
                !cloudAccount.favoriteState().loaded &&
                !cloudAccount.favoriteState().loading
        )
            cloudAccount.refreshFavorites()
        if (focusEpisode)
            episodes
                .getOrNull((episodeIndex - group * 20).coerceIn(0, episodes.lastIndex))
                ?.requestFocus()
        else play.requestFocus()
    }

    // 正式播放入口：准备指定剧集的播放资源与播放器状态。
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false,autoDowngrade: Boolean=false) {
        restoreHoldSpeed()
        social.reset()
        if (!applyingAutomaticSkip && !recovering && !autoDowngrade) incompatibleSkips.rendered()
        receiveRate.reset()
        val data=detail ?: return
        discardPendingPrepared()
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        if(playQueuedEpisode(selectedIndex,position,autoplay,recovering)) return
        featureTools.setCurrentSeries(data.series)
        featureTools.playbackStarted(data.series.id)
        val selectedKey="${data.episodes[selectedIndex]}:${library.maxQuality}"
        val queuedClaim=queuedNext?.takeIf { position==0L && !recovering && it.key==selectedKey &&
            playbackStarted-it.preparedAt in 0..120_000 }?.also { queuedNext=null }
        val prefetched=queuedClaim?.remote ?: if(position==0L && !recovering) preparedNext.take(selectedKey) else null
        val claimedHttp=queuedClaim?.http ?: if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            // Keep the old surface/decoder and last frame while the new URL resolves. Stopping and
            // clearing here created a network-length empty interval even with keepContent enabled.
            player?.pause()
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        var firstReady=true; var firstFrame=true
        var currentId=data.episodes[selectedIndex]; var itemStarted=playbackStarted; var itemDuration=0L
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        if(autoplay) sleepStopped=false
        screen="player"; episodeIndex=selectedIndex; group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        attemptedQuality=0; currentTrack=null; currentRendition=null; currentTiers=emptyList()
        if(!autoDowngrade) autoDowngrades=0
        if(!reuse) player?.playWhenReady=requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            val view=PlayerView(this).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1)); social.attach(root)
            bufferingRate = text("", 15f, Color.WHITE).apply {
                visibility = View.GONE
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setBackgroundColor(Color.argb(190, 0, 0, 0))
            }.also { label ->
                root.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dp(90) })
            }
            hud=column().apply { setPadding(dp(widthDp()*.05f),dp(20),dp(widthDp()*.05f),(resources.displayMetrics.heightPixels*.05f).toInt()); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,15,21))) }
            playbackTitle=text("",23f,Color.WHITE).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(playbackTitle)
            playbackText=text("",15f,Color.LTGRAY).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))
            hud.addView(text("确认 暂停/播放    左右 快退/快进    ↓ 选集    ↑ 更多    返回 收起 / 退出",13f,Color.LTGRAY).apply { setPadding(0,dp(12),0,0) })
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        updateBufferingIndicator()
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
        pendingPrepared=prefetched?.let { it to claimedHttp }
        android.util.Log.i("HongguoTV","Episode resolve episode=${episodeIndex+1} prepared=${prefetched!=null} retainedFrame=$reuse")
        work({ queuedClaim?.opened ?: prefetched?.let { OpenedStream(it,null,emptyList(),it.trackInfo()) } ?: openStream(repository.http,repository,requestedId,maxQuality,CodecCapabilities::supports) { it==null || CodecCapabilities.supports(it) } ?: throw IncompatibleEpisodeException("没有电视可播放的编码或清晰度") }, { opened ->
            val remote=opened.video
            val previousVideo=video; val previousHttp=playbackHttp
            if(pendingPrepared?.first===remote) pendingPrepared=null
            playbackHttp=claimedHttp
            video=remote; quality=playedQualityLabel(opened,maxQuality)
            attemptedQuality=opened.rendition?.quality ?: 0; currentTrack=opened.track; currentRendition=opened.rendition; currentTiers=opened.tiers
            val p=player ?: run {
                val load=TvPlaybackBuffer.create()
                val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true).setMediaCodecSelector(DecoderPolicy.selector(this))
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    player=it; playerView?.player=it
                    it.preloadConfiguration=ExoPlayer.PreloadConfiguration(TvPlaybackBuffer.NEXT_DURATION_US)
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                    it.addAnalyticsListener(playbackDriftListener(it))
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun sessionActive()=player===p && generation==ticket && screen=="player"
                private fun active()=sessionActive() && p.currentMediaItem?.mediaId==currentId
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_BUFFERING) cancelPrefetch()
                    updateBufferingIndicator()
                    android.util.Log.i("HongguoTV","Playback state episode=${episodeIndex+1} state=$state queued=${queuedNext!=null}")
                    if(state==Player.STATE_READY) {
                        // A queued seek can report READY before its track-change event is delivered.
                        if(!p.currentTracks.isEmpty && !p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true; itemDuration=p.duration.coerceAtLeast(0)
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-itemStarted}") }
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && featureTools.preferences.nextSeason) continueNextSeason()
                        else finishSeriesPlayback()
                    }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        incompatibleSkips.rendered()
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-itemStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean,reason: Int) {
                    if(!active()) return
                    if(!playWhenReady) cancelPrefetch(resetBudget=true,keepQueued=episodePanel!=null)
                    updateBufferingIndicator(); syncMediaSession()
                }
                override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                    if(!active()) return
                    if(reason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE) cancelPrefetch(resetBudget=true)
                    updateBufferingIndicator(); syncMediaSession()
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
                    if(!sessionActive()) return
                    val queued=queuedNext
                    if(queued!=null && newPosition.mediaItem?.mediaId==data.episodes[queued.index] &&
                        newPosition.mediaItem?.mediaId!=currentId) {
                        val automatic=reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                        val previousIndex=episodeIndex
                        library.save(WatchProgress(data.series,currentId,previousIndex,oldPosition.positionMs.coerceAtLeast(0),
                            itemDuration.coerceAtLeast(oldPosition.positionMs).coerceAtLeast(0),automatic,System.currentTimeMillis()))
                        if(automatic && (sleepTimer.episodeEnded() || !library.autoNext || sleepStopped || !requestedAutoplay)) {
                            p.pause(); p.seekTo(oldPosition.mediaItemIndex,(oldPosition.positionMs-1).coerceAtLeast(0))
                            cancelPrefetch(); stopForSleep()
                            library.save(WatchProgress(data.series,currentId,previousIndex,oldPosition.positionMs.coerceAtLeast(0),
                                oldPosition.positionMs.coerceAtLeast(0),true,System.currentTimeMillis()))
                            return
                        }
                        cloudAccount.upload(CloudProgress(data.series,currentId,previousIndex,oldPosition.positionMs.coerceAtLeast(0),itemDuration.coerceAtLeast(oldPosition.positionMs),System.currentTimeMillis()),data.episodes.size,true)
                        social.reset()
                        featureTools.setCurrentSeries(data.series)
                        featureTools.playbackStarted(data.series.id)
                        val oldVideo=video; val oldHttp=playbackHttp
                        queuedNext=null; video=queued.remote; playbackHttp=queued.http
                        episodeIndex=queued.index; group=episodeIndex/20; currentId=data.episodes[episodeIndex]
                        receiveRate.reset()
                        itemStarted=android.os.SystemClock.elapsedRealtime(); readySince=itemStarted
                        firstFrame=true; firstReady=p.playbackState!=Player.STATE_READY
                        playbackReady=!firstReady; startPosition=0
                        quality=queued.opened?.let { playedQualityLabel(it,library.maxQuality) } ?: queued.remote.info.quality
                        currentTrack=queued.opened?.track ?: queued.remote.trackInfo()
                        currentRendition=queued.opened?.rendition
                        currentTiers=queued.opened?.tiers ?: emptyList()
                        attemptedQuality=queued.opened?.rendition?.quality ?: 0
                        autoDowngrades=0
                        itemDuration=p.duration.coerceAtLeast(0)
                        prefetchAttempts=0; prefetchAttemptAt=0; prefetchedFor=""
                        prefetchScope=null; prefetchJob=null
                        main.removeCallbacks(seekRunnable); pendingSeek=null
                        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
                        hidePlaybackOverlay(); controls.removeAllViews(); transportPlay=null; sleepButton=null
                        android.util.Log.i("HongguoTV","Autoplay transition episode=${episodeIndex+1} automatic=$automatic state=${p.playbackState} bufferedMs=${p.totalBufferedDuration} items=${p.mediaItemCount}")
                        if(p.currentMediaItemIndex>0) p.removeMediaItems(0,p.currentMediaItemIndex)
                        oldVideo?.close(); closeTransport(oldHttp)
                        updateBufferingIndicator()
                    }
                    if(active()) syncMediaSession()
                }
                override fun onTracksChanged(tracks: Tracks) {
                    if(active() && p.playbackState==Player.STATE_READY && !tracks.isEmpty && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                        playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=mediaSource(remote,requestedId)
            p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
            if(previousVideo!==remote) previousVideo?.close()
            if(previousHttp!==claimedHttp) closeTransport(previousHttp)
        }, { problem -> discardPendingPrepared(); playerFailure(problem) })
    }
    private fun playQueuedEpisode(index: Int,position: Long,autoplay: Boolean,recovering: Boolean): Boolean {
        val data=detail ?: return false; val p=player ?: return false; val queued=queuedNext ?: return false
        if(!foreground || screen!="player" || playError || recovering || position!=0L || index!=episodeIndex+1 ||
            queued.index!=index || queued.key!="${data.episodes[index]}:${library.maxQuality}" ||
            android.os.SystemClock.elapsedRealtime()-queued.preparedAt !in 0..120_000 ||
            p.currentMediaItem?.mediaId!=data.episodes[episodeIndex]) return false
        val nextIndex=(p.currentMediaItemIndex+1 until p.mediaItemCount).firstOrNull {
            p.getMediaItemAt(it).mediaId==data.episodes[index]
        } ?: return false
        cancelRecovery(); recovery.reset(); autoRecovery=false; tvTools.close()
        episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(seekRunnable); pendingSeek=null
        requestedAutoplay=autoplay; pausedForLifecycle=!autoplay
        if(autoplay) sleepStopped=false
        // Keep ownership in queuedNext until the discontinuity listener adopts this exact source.
        p.seekTo(nextIndex,0)
        p.playWhenReady=autoplay
        if(autoplay) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        library.flush(); hidePlaybackOverlay(); updatePlaybackText(); updateBufferingIndicator(); syncMediaSession()
        return true
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun discardPendingPrepared() {
        val pending=pendingPrepared ?: return
        pendingPrepared=null
        pending.first.close(); closeTransport(pending.second)
    }
    private fun updateBufferingIndicator() {
        main.removeCallbacks(bufferingHint)
        playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        bufferingRate?.visibility = View.GONE
        if (showReceiveIndicator()) main.postDelayed(bufferingHint,700)
    }
    private fun showReceiveIndicator(): Boolean {
        if (!foreground || screen != "player" || playError || episodePanel != null ||
            !requestedAutoplay || pausedForLifecycle || sleepStopped) return false
        val p = player ?: return !playbackReady
        return !playbackReady || (p.playWhenReady &&
            p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            p.playbackState == Player.STATE_BUFFERING)
    }
    private fun mediaSource(remote: RemoteVideo,id: String) = ProgressiveMediaSource.Factory {
        VideoDataSource(remote) {
            main.post {
                if(queuedNext?.remote===remote && player?.currentMediaItem?.mediaId!=id) {
                    android.util.Log.i("HongguoTV","Discarding failed queued media; current episode continues")
                    cancelPrefetch()
                }
            }
        }
    }.createMediaSource(MediaItem.Builder().setMediaId(id).setUri("hongguotv://episode/$id").setMimeType(MimeTypes.VIDEO_MP4).build())
    private fun discardQueuedEpisode() {
        val queued=queuedNext ?: return
        val p=player
        // A transition callback owns promotion. Never cancel a stream that is already current.
        if(p?.currentMediaItem?.mediaId==detail?.episodes?.getOrNull(queued.index)) return
        queuedNext=null
        if(p!=null) {
            val index=(p.currentMediaItemIndex+1 until p.mediaItemCount).firstOrNull {
                p.getMediaItemAt(it).mediaId==detail?.episodes?.getOrNull(queued.index)
            }
            if(index!=null) p.removeMediaItem(index)
        }
        queued.remote.close(); closeTransport(queued.http)
    }
    private fun cancelPrefetch(resetBudget: Boolean=false,keepQueued: Boolean=false) {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
        if(!keepQueued) discardQueuedEpisode()
        if(resetBudget) { prefetchAttempts=0; prefetchAttemptAt=0 }
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        val now=android.os.SystemClock.elapsedRealtime()
        val remaining=if(p.duration>0) ((p.duration-p.currentPosition).coerceAtLeast(0)/p.playbackParameters.speed).toLong() else Long.MAX_VALUE
        if(!library.autoNext || sleepStopped || !sleepTimer.canContinueAfter(remaining)) { cancelPrefetch(); return }
        queuedNext?.let {
            if(now-it.preparedAt in 0..120_000) return
            discardQueuedEpisode()
            // Expiry is not a failed request; allow a fresh bounded preparation on long episodes.
            prefetchAttempts=0; prefetchAttemptAt=0
        }
        if(!foreground || !p.isPlaying || playError || episodeIndex>=data.episodes.lastIndex) return
        if(p.duration<=0 || p.currentPosition>=p.duration) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key); val pageTicket=generation; val fromEpisode=episodeIndex
        // Carry the repository's network configuration, with independently owned cancellation/threads.
        val http=repository.http.newBuilder().dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool())
            .connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val opened=scope.run { openStream(http,source,id,quality,CodecCapabilities::supports) { it==null || CodecCapabilities.supports(it) } ?: throw java.io.IOException("没有可预载的资源") }
                val prepared=scope.run { opened.video.also { remote=it; it.warm() } }
                if(preparedNext.complete(ticket,key,prepared)) main.post {
                    if(!foreground || generation!=pageTicket || player!==p || episodeIndex!=fromEpisode ||
                        library.maxQuality!=quality || !library.autoNext || !requestedAutoplay || !p.isPlaying ||
                        episodePanel!=null || sleepStopped ||
                        !sleepTimer.canContinueAfter(((p.duration-p.currentPosition).coerceAtLeast(0)/p.playbackParameters.speed).toLong())) {
                        if(prefetchHttp===http) cancelPrefetch()
                        return@post
                    }
                    val next=preparedNext.take(key) ?: return@post
                    queuedNext=QueuedEpisode(fromEpisode+1,key,next,http,android.os.SystemClock.elapsedRealtime(),opened)
                    prefetchHttp=null; prefetchScope=null
                    p.addMediaSource(mediaSource(next,id))
                    android.util.Log.i("HongguoTV","Next episode queued episode=${fromEpisode+2} remainingMs=${p.duration-p.currentPosition} items=${p.mediaItemCount}")
                }
            } catch(_: Exception) {
                remote?.close()
                main.post { if(prefetchHttp===http) { closeTransport(http); prefetchHttp=null } }
            }
        }
    }
    private fun networkAvailable(): Boolean =
        connectivity
            .getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun currentPosition(): Long =
        pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition

    private fun cancelRecovery() {
        main.removeCallbacks(retryRunnable)
        retryPending = false
        autoRecovery = false
    }

    private fun scheduleRecovery() {
        if (!foreground || !playError || !autoRecovery || retryPending) return
        if (!recovery.remaining) {
            autoRecovery = false
            playbackText.text = "已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"
            return
        }
        if (!networkAvailable()) {
            playbackText.text = "网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"
            return
        }
        val delay = recovery.delayMillis ?: return
        playbackText.text =
            "${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending = true
        main.postDelayed(retryRunnable, delay)
    }

    /**
     * Detects growing audio/video drift (video frame processing offset stays signed) and re-seeks
     * to the current position once per cooldown window: a seek flushes the audio processors and
     * clock basis, which users confirmed restores sync; a pause does not.
     */
    private fun playbackDriftListener(p: ExoPlayer): AnalyticsListener {
        val guard = PlaybackSyncGuard()
        return object : AnalyticsListener {
            override fun onVideoFrameProcessingOffset(
                eventTime: AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int,
            ) {
                if(!getSharedPreferences("playback-controls",0).getBoolean("auto-resync",false)) return
                val window = guard.offer(totalProcessingOffsetUs, frameCount, p.isPlaying) ?: return
                android.util.Log.i("HongguoTV", "Video frame offset ${window.averageUs/1000}ms")
                if (window.resync && getSharedPreferences("playback-controls",0).getBoolean("auto-resync",false))
                    main.post {
                        if (
                            player === p &&
                                playbackReady &&
                                p.isPlaying &&
                                pendingSeek == null &&
                                !playError
                        ) {
                            android.util.Log.w(
                                "HongguoTV",
                                "Resync after audio/video drift ${window.averageUs/1000}ms at ${formatTime(p.currentPosition)}",
                            )
                            p.seekTo(p.currentPosition)
                        }
                    }
            }
        }
    }

    private fun playedQualityLabel(opened: OpenedStream, maxQuality: Int): String {
        val rendition = opened.rendition ?: return opened.video.info.quality
        val label = if (rendition.quality > 0) "${rendition.quality}P" else "自动"
        if (rendition.quality > maxQuality) return label + "（兼容资源）"
        val ideal = opened.tiers.filter { it.quality in 1..maxQuality }.maxOfOrNull { it.quality }
        return if (ideal != null && rendition.quality < ideal) label + "（自动降清）" else label
    }

    private fun playerFailure(problem: Throwable) {
        retryPosition = currentPosition().coerceAtLeast(0)
        retryAutoplay = requestedAutoplay && !sleepStopped
        saveProgress()
        cancelRecovery()
        cancelPrefetch()
        // The device claimed support it doesn't have (MediaCodecList over-reporting): learn the
        // runtime verdict and retry one tier lower automatically instead of surfacing the panel.
        if (
            problem is PlaybackException &&
                IncompatibleSkipBudget.codecError(problem.errorCode)
        ) {
            val track = currentTrack
            val rendition = currentRendition
            when {
                track != null ->
                    CodecCapabilities.learnFailure(track.fourcc, track.width, track.height)
                rendition != null ->
                    CodecCapabilities.learnFailure(
                        rendition.codec,
                        rendition.width,
                        rendition.height,
                    )
            }
            if (shouldAutoDowngrade(autoDowngrades, 2, currentTiers, attemptedQuality)) {
                autoDowngrades++
                android.util.Log.w(
                    "HongguoTV",
                    "Auto downgrading after decoder failure (attempt $autoDowngrades, was ${attemptedQuality}P)",
                )
                playEpisode(episodeIndex, retryPosition, retryAutoplay, autoDowngrade = true)
                return
            }
        }
        if (autoSkipIncompatible(problem)) return
        playError = true
        player?.pause()
        progressBar.visibility = View.GONE
        hud.visibility = View.VISIBLE
        controls.removeAllViews()
        controls.visibility = View.VISIBLE
        panel = true
        val decoding = incompatibleFailure(problem)
        val retryable =
            if (decoding) false else if (problem is PlaybackException)
                problem.errorCode in 2000..2999 ||
                    problem.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
            else problem is java.io.IOException
        playbackText.text = if (decoding) {
            if (incompatibleSkips.count >= 5) "已连续跳过5集，自动跳过已停止；可手动选择其他集。"
            else "电视不支持当前视频编码，可尝试更低清晰度或跳过这集。"
        } else "播放失败，可重试或切换清晰度。"
        val actions = row()
        controls.addView(actions)
        addButton(actions, "立即重试") { playEpisode(episodeIndex, retryPosition, retryAutoplay) }
            .requestFocus()
        nextLowerQuality(if (attemptedQuality > 0) attemptedQuality else library.maxQuality)?.let {
            lower ->
            addButton(actions, "尝试 ${lower}P") {
                library.maxQuality = lower
                playEpisode(episodeIndex, retryPosition, retryAutoplay)
            }
        }
        if (decoding && episodeIndex < (detail?.episodes?.lastIndex ?: -1)) {
            addButton(actions, "跳过这集") {
                if (foreground && screen == "player" && !sleepStopped) {
                    if (sleepTimer.poll()) stopForSleep()
                    else {
                        incompatibleSkips.rendered()
                        playEpisode(episodeIndex + 1, autoplay = requestedAutoplay && !pausedForLifecycle)
                    }
                }
            }
        }
        addButton(actions, "返回选集") { returnToDetail(true) }
        if (retryable && !sleepStopped) {
            addButton(controls, "停止自动重试") {
                cancelRecovery()
                playbackText.text = "自动重试已停止，观看位置已保留。"
            }
            autoRecovery = true
            scheduleRecovery()
        }
        android.util.Log.w(
            "HongguoTV",
            "Playback failure: ${problem.javaClass.simpleName}" +
                (if (problem is PlaybackException) " code=${problem.errorCodeName}" else ""),
        )
    }

    private fun incompatibleFailure(problem: Throwable): Boolean =
        problem is IncompatibleEpisodeException || problem is IncompatibleStreamException ||
            (problem is PlaybackException && IncompatibleSkipBudget.codecError(problem.errorCode))

    private fun autoSkipIncompatible(problem: Throwable): Boolean {
        if (!incompatibleFailure(problem) || !foreground || screen != "player" ||
            !requestedAutoplay || pausedForLifecycle || sleepStopped || episodePanel != null ||
            (playbackReady && player?.playWhenReady == false)) return false
        if (sleepTimer.poll()) { stopForSleep(); return true }
        if (!sleepTimer.canContinueAfter(0)) return false
        val data = detail ?: return false
        val target = incompatibleSkips.next(episodeIndex, data.episodes.lastIndex,
            featureTools.preferences.autoSkipIncompatible, codecFailure = true, canContinue = true) ?: return false
        Toast.makeText(this, "第${episodeIndex + 1}集编码不支持，跳到第${target + 1}集（${incompatibleSkips.count}/5）", Toast.LENGTH_SHORT).show()
        applyingAutomaticSkip = true
        try { playEpisode(target, autoplay = true) } finally { applyingAutomaticSkip = false }
        return true
    }

    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel = false
        controls.visibility = View.GONE
        controls.clearFocus()
        hud.visibility = View.GONE
    }

    private fun showHud() {
        if (screen != "player") return
        hud.visibility = View.VISIBLE
        updatePlaybackText()
        main.removeCallbacks(hideHud)
        if (!panel && player?.isPlaying == true) main.postDelayed(hideHud, 4500)
    }

    private fun updatePlaybackText() {
        transportPlay?.text = if (player?.playWhenReady == true) "暂停" else "播放"
        sleepButton?.text = "定时 ${sleepTimer.label()}"
        if (screen != "player" || playError) return
        if (sleepStopped) {
            playbackText.text = "定时停止已生效 · 观看位置已保存 · 按播放可继续"
            return
        }
        if (!playbackReady) {
            playbackText.text =
                (if (!requestedAutoplay) "准备后保持暂停 · " else "") +
                    (if (player?.currentMediaItem?.mediaId != detail?.episodes?.getOrNull(episodeIndex)) "正在获取播放地址…" else "正在缓冲…") + " · 接收 ${receiveRate.label()}"
            return
        }
        val p = player ?: return
        val duration = p.duration.coerceAtLeast(0)
        val position = pendingSeek ?: p.currentPosition
        val state =
            when {
                p.playbackState == Player.STATE_BUFFERING -> "缓冲中"
                p.playbackState == Player.STATE_ENDED -> "本集已结束"
                !p.playWhenReady -> "已暂停"
                else -> "正在播放"
            }
        playbackText.text =
            "$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}" +
                (if (p.playbackState == Player.STATE_BUFFERING) " · 接收 ${receiveRate.label()}" else "") +
                (if (sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress =
            if (duration > 0) (position * 1000 / duration).toInt().coerceIn(0, 1000) else 0
    }

    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { cancelPrefetch(); player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null && player?.currentMediaItem?.mediaId==detail?.episodes?.getOrNull(episodeIndex)) {
            if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0)
            player?.play()
        }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }

    private fun playbackIntent(): Boolean =
        if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay

    private fun togglePlayback() = requestPlayback(!playbackIntent())

    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }

    private fun skipEpisode(direction: Int) {
        if (!foreground || screen != "player") return
        val count = detail?.episodes?.size ?: return
        val target = episodeIndex + direction
        if (target in 0 until count) playEpisode(target)
    }

    private fun syncMediaSession() {
        if (!foreground || screen != "player") {
            mediaSession.deactivate()
            return
        }
        val data = detail ?: return
        val p = player
        val state =
            when {
                playError -> android.media.session.PlaybackState.STATE_ERROR
                sleepStopped || !requestedAutoplay ->
                    android.media.session.PlaybackState.STATE_PAUSED
                p == null || !playbackReady || p.playbackState == Player.STATE_BUFFERING ->
                    android.media.session.PlaybackState.STATE_BUFFERING
                !p.playWhenReady -> android.media.session.PlaybackState.STATE_PAUSED
                p.playbackState == Player.STATE_ENDED ->
                    android.media.session.PlaybackState.STATE_STOPPED
                p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
                else -> android.media.session.PlaybackState.STATE_PAUSED
            }
        mediaSession.update(
            data.series.title,
            episodeIndex,
            p?.duration?.coerceAtLeast(0) ?: 0,
            currentPosition(),
            library.playbackSpeed,
            state,
            episodeIndex > 0,
            episodeIndex < data.episodes.lastIndex,
        )
    }

    private fun stopForSleep() {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); cancelPrefetch(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }

    private fun showSleepPicker(anchor: TextView) {
        val labels =
            listOf("关闭定时停止", "15 分钟后", "30 分钟后", "60 分钟后", "90 分钟后", "本集播完", "再播完 3 集", "再播完 5 集")
        tvTools.choose(
            "定时停止 · ${sleepTimer.label()}",
            labels,
            anchor,
            { choice ->
                when (choice) {
                    0 -> sleepTimer.cancel()
                    in 1..4 -> sleepTimer.afterMinutes(listOf(15, 30, 60, 90)[choice - 1])
                    else -> sleepTimer.afterEpisodes(listOf(1, 3, 5)[choice - 5])
                }
                if (sleepTimer.episodesLeft == 1) cancelPrefetch()
                if (anchor.tag != "playback-settings") anchor.text = "定时 ${sleepTimer.label()}"
                updatePlaybackText()
                showHud()
            },
        )
    }

    private fun frameResizeMode() =
        when (library.frameMode) {
            VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }

    private fun showFramePicker(anchor: TextView, inPlayer: Boolean) {
        tvTools.choose(
            "全局画面模式",
            listOf("完整画面 · 保留比例与全部内容", "等比铺满 · 会裁掉部分画面与字幕", "拉伸铺满 · 画面比例会改变"),
            anchor,
            { index ->
                library.frameMode = VideoFrameMode.entries[index]
                playerView?.resizeMode = frameResizeMode()
                if (anchor.tag != "playback-settings")
                    anchor.text = (if (inPlayer) "画面 " else "画面模式  ·  ") + library.frameMode.label
            },
        )
    }

    private fun seek(direction: Int, repeat: Int) {
        val p = player ?: return
        if (!playbackReady) return
        val duration = p.duration
        if (duration <= 0) return
        val step = if (repeat > 4) maxOf(30000L, featureTools.seekStepMs) else featureTools.seekStepMs
        pendingSeek =
            ((pendingSeek ?: p.currentPosition) + direction * step).coerceIn(
                0,
                (duration - 500).coerceAtLeast(0),
            )
        main.removeCallbacks(seekRunnable)
        main.postDelayed(seekRunnable, 250)
        updatePlaybackText()
        showHud()
    }

    private fun showPanel() {
        if (playError) return
        panel = true
        showHud()
        controls.visibility = View.VISIBLE
        controls.removeAllViews()
        val transport = row()
        controls.addView(transport)
        val play =
            addButton(transport, if (player?.playWhenReady == true) "暂停" else "播放") {
                    togglePlayback()
                }
                .also { transportPlay = it }
        addButton(transport, "上一集") { if (episodeIndex > 0) playEpisode(episodeIndex - 1) }
            .apply {
                isEnabled = episodeIndex > 0
                isFocusable = episodeIndex > 0
                alpha = if (episodeIndex > 0) 1f else .4f
            }
        addButton(transport, "下一集") {
                if (episodeIndex < (detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex + 1)
            }
            .apply {
                val enabled = episodeIndex < (detail?.episodes?.lastIndex ?: 0)
                isEnabled = enabled
                isFocusable = enabled
                alpha = if (enabled) 1f else .4f
            }
        val options = row().apply { setPadding(0, dp(8), 0, 0) }
        controls.addView(options)
        addButton(options, "本剧工具") { detail?.series?.let { featureTools.showSeries(it) } }
        lateinit var episodes: TextView
        episodes = addButton(options, "选集") { showEpisodePanel(episodes) }
        lateinit var commentsButton: TextView
        commentsButton = addButton(options, "评论") { social.showComments(commentsButton) }
        lateinit var danmakuButton: TextView
        danmakuButton = addButton(options, "弹幕设置") { social.settings(danmakuButton) }
        addButton(options,"音画同步") {
            tvTools.choose("音画同步",listOf("立即重新同步","自动同步：" + if(getSharedPreferences("playback-controls",0).getBoolean("auto-resync",false)) "开" else "关"),options,{ choice ->
                if(choice==0) player?.let { it.seekTo(it.currentPosition) }
                else getSharedPreferences("playback-controls",0).edit().putBoolean("auto-resync",!getSharedPreferences("playback-controls",0).getBoolean("auto-resync",false)).apply()
            })
        }
        addButton(options,"解码：" + DecoderPolicy.modeLabel(this)) {
            DecoderPolicy.showChooser(this) {
                val savedPosition=currentPosition()
                val resume=requestedAutoplay && !pausedForLifecycle && !sleepStopped
                releasePlayer()
                playEpisode(episodeIndex,savedPosition,resume)
            }
        }
        lateinit var more: TextView
        more =
            addButton(options, "播放设置") {
                    tvTools.choose(
                        "播放设置",
                        listOf(
                            "倍速 ${PlaybackSpeed.label(library.playbackSpeed)}",
                            "清晰度 ${library.maxQuality}P",
                            "画面 ${library.frameMode.label}",
                            "定时 ${sleepTimer.label()}",
                            "从头播放",
                            "重置设备能力记录",
                            "自动跳过不支持编码：${if (featureTools.preferences.autoSkipIncompatible) "开启" else "关闭"}",
                        ),
                        more,
                        { choice ->
                            main.post {
                                if (screen == "player" && more.isAttachedToWindow)
                                    when (choice) {
                                        0 -> showSpeedPicker(more)
                                        1 -> showQualityPicker(more, true)
                                        2 -> showFramePicker(more, true)
                                        3 -> showSleepPicker(more)
                                        4 -> {
                                            seekTo(0)
                                            hidePanel()
                                        }
                                        5 -> {
                                            val count = CodecCapabilities.learnedCount()
                                            CodecCapabilities.resetLearned()
                                            android.util.Log.i(
                                                "HongguoTV",
                                                "Decoder capability lessons reset ($count entries)",
                                            )
                                            playbackText.text =
                                                if (count > 0) "已清除 $count 条设备能力记录，重新按解码器实际能力选流"
                                                else "暂无设备能力记录"
                                        }
                                        6 -> {
                                            featureTools.preferences.autoSkipIncompatible = !featureTools.preferences.autoSkipIncompatible
                                            incompatibleSkips.rendered()
                                            Toast.makeText(this, if (featureTools.preferences.autoSkipIncompatible)
                                                "仅自动跳过确认不支持编码的集，最多连续5集" else "已关闭自动跳过", Toast.LENGTH_LONG).show()
                                        }
                                    }
                            }
                        },
                    )
                }
                .apply { tag = "playback-settings" }
        play.requestFocus()
    }

    private fun showSpeedPicker(anchor: TextView, inPlayer: Boolean = true) {
        if (speedDialog != null) return
        val speeds = PlaybackSpeed.options
        val selected = speeds.indexOf(library.playbackSpeed)
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("全局默认倍速")
                .setSingleChoiceItems(speeds.map(PlaybackSpeed::label).toTypedArray(), selected) {
                    popup,
                    index ->
                    library.playbackSpeed = speeds[index]
                    if (inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
                    if (anchor.tag != "playback-settings")
                        anchor.text =
                            (if (inPlayer) "倍速 " else "默认倍速  ·  ") +
                                PlaybackSpeed.label(library.playbackSpeed)
                    if (inPlayer) updatePlaybackText()
                    popup.dismiss()
                }
                .setNegativeButton("取消", null)
                .create()
        speedDialog = dialog
        dialog.setOnDismissListener {
            speedDialog = null
            if (anchor.isAttachedToWindow) {
                anchor.requestFocus()
                if (inPlayer) showHud()
            }
        }
        dialog.show()
        dialog.listView.setSelection(selected)
        dialog.listView.requestFocus()
    }

    private fun showQualityPicker(anchor: TextView, inPlayer: Boolean) {
        val options = PlaybackQuality.options
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("全局默认清晰度")
                .setSingleChoiceItems(
                    options.map { "${it}P" }.toTypedArray(),
                    options.indexOf(library.maxQuality),
                ) { popup, index ->
                    val target = options[index]
                    val changed = library.maxQuality != target
                    library.maxQuality = target
                    popup.dismiss()
                    if (anchor.tag != "playback-settings")
                        anchor.text = (if (inPlayer) "清晰度 " else "默认清晰度  ·  ") + "${target}P"
                    if (inPlayer && changed)
                        playEpisode(
                            episodeIndex,
                            currentPosition(),
                            playbackIntent(),
                        )
                }
                .setNegativeButton("取消", null)
                .create()
        dialog.setOnDismissListener { if (anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
    }

    private fun showEpisodePanel(anchor: View) {
        val data = detail ?: return
        if (episodePanel != null) return
        val ticket = generation
        val overlayWasVisible = hud.visibility == View.VISIBLE
        requestedAutoplay = playbackIntent()
        saveProgress()
        var resumeOnClose = true
        val next =
            EpisodePanel(
                this,
                data.episodes.size,
                episodeIndex,
                { current, view, located ->
                    tvTools.episodePicker(data.episodes.size, current, view, located)
                },
                { selected ->
                    if (selected == episodeIndex) episodePanel?.dismiss()
                    else {
                        resumeOnClose = false
                        episodePanel?.dismiss()
                        playEpisode(selected)
                    }
                },
                {
                    episodePanel = null
                    if (resumeOnClose && foreground && generation == ticket && screen == "player") {
                        if (requestedAutoplay && !sleepStopped && !pausedForLifecycle &&
                            player?.currentMediaItem?.mediaId == data.episodes.getOrNull(episodeIndex))
                            player?.play()
                        if (anchor.isAttachedToWindow) anchor.requestFocus()
                        if (overlayWasVisible) showHud() else hidePlaybackOverlay()
                    }
                },
                lastWatched =
                    library.progress(data.series.id)?.let {
                        data.episodes.indexOf(it.episodeId).takeIf { n -> n >= 0 }
                    },
                lastCompleted = library.progress(data.series.id)?.completed == true,
            )
        episodePanel = next
        // Set the owner before pause callbacks run, so only unfinished speculation is cancelled.
        cancelPrefetch(resetBudget=true,keepQueued=true)
        player?.pause()
        next.show()
    }

    private fun hidePanel() {
        if (playError) return
        panel = false
        controls.visibility = View.GONE
        controls.clearFocus()
        showHud()
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0) / 1000)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    // 保存正式观看进度；首页试播使用独立状态，不从此处写入观看记录。
    private fun saveProgress(completed: Boolean = false) {
        val p = player ?: return
        val data = detail ?: return
        if (!playbackReady || episodeIndex !in data.episodes.indices) return
        val progress =
            WatchProgress(
                data.series,
                data.episodes[episodeIndex],
                episodeIndex,
                p.currentPosition.coerceAtLeast(0),
                p.duration.coerceAtLeast(0),
                completed || p.playbackState == Player.STATE_ENDED,
                System.currentTimeMillis(),
            )
        library.save(progress)
        cloudAccount.upload(
            CloudProgress(
                progress.series,
                progress.episodeId,
                progress.episodeIndex,
                progress.position,
                progress.duration,
                progress.updatedAt,
            ),
            data.episodes.size,
            completed || !p.isPlaying || !foreground,
        )
    }

    private fun releasePlayer() {
        restoreHoldSpeed()
        social.reset()
        discardPendingPrepared()
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        main.removeCallbacks(bufferingHint)
        bufferingRate = null
        playerView?.player=null; playerView=null; player?.release(); player=null
        video?.close(); video=null; queuedNext?.let { it.remote.close(); closeTransport(it.http) }; queuedNext=null
        playbackReady=false; transportPlay=null; sleepButton=null
        closeTransport(playbackHttp); playbackHttp=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun returnToDetail(forceDetail: Boolean = false) {
        sleepTimer.cancel()
        sleepStopped = false
        saveProgress()
        library.flush()
        invalidatePage()
        releasePlayer()
        if (directPlayback && !forceDetail) showCatalog()
        else {
            directPlayback = false
            showDetail(true)
        }
    }

    private fun goBack() {
        if (!library.isLoaded) {
            showExitConfirmation()
            return
        }
        if (screen == "catalog" && tab == 6 && homeFeed?.back() == true) return
        if (screen == "catalog" && tab == 1 && tvSearchPanel != null) {
            returnToDiscovery()
            return
        }
        if (screen == "catalog" && tab == 1 && query.isBlank() && !nav[1].hasFocus()) {
            discoveryScreen?.backToTop()
            nav[1].requestFocus()
            return
        }
        when (screen) {
            "player" ->
                if (hud.visibility == View.VISIBLE && !playError) hidePlaybackOverlay()
                else returnToDetail()
            "detail" -> {
                val previous = detailTrail.pollLast()
                if (previous == null) showCatalog()
                else {
                    detail = previous.detail
                    episodeIndex = previous.episode
                    group = previous.group
                    showDetail(false)
                }
            }
            else ->
                if (tab == 1 && query.isNotBlank()) returnToDiscovery()
                else if (collection != null) {
                    leaveCollection()
                    showCatalog()
                } else if (nav.any { it.hasFocus() }) {
                    if (tab != 0) {
                        switchTab(0)
                        nav[0].requestFocus()
                    } else showExitConfirmation()
                } else nav.getOrNull(tab)?.requestFocus()
        }
    }

    private fun showExitConfirmation() {
        if (isDestroyed || isFinishing || exitDialog?.isShowing == true) return
        val choices = if (library.isLoaded) {
            val updated = library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0) > 0 }
            val candidates = catalog + library.cachedHome(library.contentType)?.items.orEmpty() +
                library.later() + library.favorites() + library.history().map { it.series }
            ExitRecommendationPolicy.pick(updated, candidates, library.hidden())
        } else emptyList()
        val ticket = generation
        val body = column().apply { setPadding(dp(18), dp(6), dp(18), dp(8)); setBackgroundColor(surface) }
        body.addView(text(if (choices.isEmpty()) "确定退出应用吗？" else "继续看看这些作品，或退出应用。", 16f))
        choices.forEach { series ->
            addButton(body, series.title) {
                exitDialog?.dismiss()
                if (foreground && ticket == generation) openToolSeries(series)
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("退出红果TV？")
            .setView(body)
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("继续使用", null)
            .create()
        exitDialog = dialog
        dialog.setOnDismissListener { if (exitDialog === dialog) exitDialog = null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed() = goBack()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::featureTools.isInitialized && screen == "player") {
            val action = featureTools.keyAction(event.keyCode)
            if (action != FeatureKeyAction.DEFAULT && event.keyCode in FeaturePreferences.REMOTE_KEYS) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) performFeatureKey(action)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && (!panel || heldPlayer != null)) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) { holdFired = false; main.postDelayed(startHoldSpeed, 350) }
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    val wasHeld = holdFired
                    restoreHoldSpeed()
                    holdFired = false
                    if (!wasHeld) seek(1, 0)
                    return true
                }
            }
        }
        if (screen == "player") {
            val key = event.keyCode
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) goBack()
                return true
            }
            if (
                key in
                    listOf(
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_MEDIA_STOP,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_REWIND,
                    )
            ) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                    when (key) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                        KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                        KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1, 0)
                        KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1, 0)
                        else -> togglePlayback()
                    }
                return true
            }
            if (!panel) {
                if (
                    key in
                        listOf(
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_MENU,
                        )
                ) {
                    if (event.action == KeyEvent.ACTION_DOWN)
                        when (key) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1, event.repeatCount)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1, event.repeatCount)
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) togglePlayback()
                            KeyEvent.KEYCODE_DPAD_DOWN ->
                                if (event.repeatCount == 0) showEpisodePanel(hud)
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) showPanel()
                            else -> showHud()
                        }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!library.isLoaded) return
        when (screen) {
            "player" -> {
                val position = currentPosition()
                val autoplay = playbackIntent() && !pausedForLifecycle
                playEpisode(episodeIndex, position, autoplay)
            }
            "detail" -> if (detail != null) showDetail(false)
            else -> showCatalog()
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        social.foreground(true)
        main.removeCallbacks(tick)
        main.post(tick)

        if (library.isLoaded) {
            if (handleSeriesLink()) return
            if (initialCatalogPending) showCatalog(load = true)
            else if (restoreStoppedPage) {
                restoreStoppedPage = false
                when (screen) {
                    "player" -> playEpisode(episodeIndex, startPosition, autoplay = false)
                    "detail" ->
                        if (detail != null) showDetail(false)
                        else
                            pendingDetail?.let { openDetail(it, directPlayback, autoplay = false) }
                                ?: showCatalog()
                    else ->
                        showCatalog(
                            load =
                                !localCatalog() &&
                                    catalog.isEmpty() &&
                                    (tab == 0 || tab == 2 || tab == 1)
                        )
                }
            } else {
                val waiting = deferredWork
                deferredWork = null
                waiting?.apply?.invoke()
            }
            homeFeed?.resume()
            if (screen == "catalog" && tab != 0) favoriteMonitor.check()
            if (screen == "player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }

    override fun onPause() {
        restoreHoldSpeed()
        if (::featureTools.isInitialized) featureTools.preferences.flushStats()
        searchSuggestions?.cancel()
        social.foreground(false)

        homeFeed?.pause()
        foreground = false
        main.removeCallbacks(tick)
        cancelRecovery()
        cancelPrefetch()
        mediaSession.deactivate()
        sleepTimer.cancel()
        favoriteMonitor.stop()
        detailAutoplay = false
        requestedAutoplay = false
        pausedForLifecycle = true
        player?.pause()
        saveProgress()
        library.flush()
        super.onPause()
    }

    // 应用离开前台时暂停播放及后台活动，防止页面不可见时继续消耗资源。
    override fun onStop() {
        if (::featureTools.isInitialized) featureTools.close()
        if (::appearance.isInitialized) appearance.close()
        if (::backupController.isInitialized) backupController.close()
        exitDialog?.dismiss()
        homeFeed?.close()
        homeFeed = null
        restoreStoppedPage = true
        generation++
        cancelWork()
        tvTools.close()
        if (screen == "player") {
            startPosition = currentPosition()
            saveProgress()
            releasePlayer()
        }
        artwork.cancelPage(clearMemory = true)
        library.flush()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::artwork.isInitialized) artwork.trimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }

    private fun restoreHoldSpeed() {
        main.removeCallbacks(startHoldSpeed)
        heldPlayer?.let { held -> if (held === player) held.setPlaybackSpeed(heldSpeed) }
        heldPlayer = null
    }

    private fun performFeatureKey(action: FeatureKeyAction) {
        when (action) {
            FeatureKeyAction.TOOLS -> { featureTools.setCurrentSeries(detail?.series); featureTools.show() }
            FeatureKeyAction.EPISODES -> showEpisodePanel(playbackText)
            FeatureKeyAction.DANMAKU -> {
                val prefs = getSharedPreferences("social", MODE_PRIVATE)
                val enabled = !prefs.getBoolean("enabled", false)
                prefs.edit().putBoolean("enabled", enabled).apply()
                social.reset(); social.foreground(foreground)
                Toast.makeText(this, if (enabled) "弹幕已开启" else "弹幕已关闭", Toast.LENGTH_SHORT).show()
            }
            FeatureKeyAction.SPEED -> showSpeedPicker(playbackText)
            FeatureKeyAction.FAVORITE -> detail?.series?.let { series ->
                val added = library.toggle(series)
                if (added) library.observeFavorite(series.id, detail?.episodes?.size ?: 0, System.currentTimeMillis(), acknowledge = true)
                Toast.makeText(this, if (added) "已加入本机收藏" else "已取消本机收藏", Toast.LENGTH_SHORT).show()
            }
            FeatureKeyAction.SLEEP -> showSleepPicker(playbackText)
            else -> Unit
        }
    }

    private fun finishSeriesPlayback(message: String = "本季已结束") {
        requestedAutoplay = false
        player?.pause()
        playbackText.text = message
        showPanel()
    }

    private fun continueNextSeason() {
        val data = detail ?: return
        if (!foreground || sleepStopped || !requestedAutoplay || !featureTools.preferences.nextSeason) {
            finishSeriesPlayback(); return
        }
        playbackText.text = "本季已结束，正在查找下一季…"
        showHud()
        work({ SeasonContinuation.next(data.series, repository.related(data.series.id)) }, { next ->
            if (!foreground || screen != "player" || detail?.series?.id != data.series.id || !requestedAutoplay || pausedForLifecycle || sleepStopped) return@work
            if (sleepTimer.poll()) { stopForSleep(); return@work }
            if (!library.autoNext || !featureTools.preferences.nextSeason || next == null) finishSeriesPlayback()
            else { saveProgress(true); releasePlayer(); openDetail(next, resume = true) }
        }, { if (screen == "player" && detail?.series?.id == data.series.id) finishSeriesPlayback("本季已结束，下一季暂不可用") })
    }

    private fun openToolSeries(series: Series) {
        featureTools.close()
        if (screen == "player") { saveProgress(); releasePlayer() }
        openDetail(series)
    }

    private fun showNextSeasons() {
        featureTools.close()
        favoriteSeasons.prune(library.favorites().map { it.id }.toSet())
        val pending = favoriteSeasons.pending()
        if (pending.isEmpty()) {
            tvTools.info("追剧 · 下一季", "暂时没有可确认的下一季。可在收藏页检查更新；未标明季号的作品仍可从详情的相关作品中选择。", null)
            return
        }
        tvTools.choose("收藏剧集 · 可看下一季", pending.map { "${it.current.title} → ${it.next.title}" }, null, { index ->
            val notice = pending[index]
            favoriteSeasons.acknowledge(notice.current.id)
            openToolSeries(notice.next)
        })
    }

    private fun openUserSource(url: String, title: String) {
        if (!SourceMediaPolicy.valid(url)) return
        featureTools.close()
        if (screen == "player") returnToDetail(true)
        releasePlayer()
        releaseCatalogViews()
        startActivity(Intent(this, SourcePlaybackActivity::class.java).putExtra("url", url).putExtra("title", title))
    }

    private fun publishDesktopChannel() {
        featureTools.close()
        val rows = (library.history().map { it.series } + library.favorites()).distinctBy { it.id }.take(12)
        if (rows.isEmpty()) { tvTools.info("电视桌面续播", "先观看或收藏一部剧，再添加桌面续播。", null); return }
        val token = generation
        Toast.makeText(this, "正在准备桌面续播…", Toast.LENGTH_SHORT).show()
        io.execute {
            val result = AndroidTvChannels.publish(this, rows)
            main.post {
                if (isDestroyed || !foreground || token != generation) return@post
                result.onSuccess { published ->
                    runCatching { AndroidTvChannels.requestVisible(this, published.channelId) }
                        .onFailure { tvTools.info("电视桌面续播", "已准备 ${published.programs} 部剧；请在电视桌面的频道管理中启用红果TV。", null) }
                }.onFailure { tvTools.info("电视桌面续播", "这台设备的桌面暂未开放频道接口，可继续从应用首页续播。", null) }
            }
        }
    }

    private fun handleSeriesLink(): Boolean {
        val id = pendingSeriesLink ?: return false
        pendingSeriesLink = null
        restoreStoppedPage = false
        initialCatalogPending = false
        saveProgress(); releasePlayer()
        val series = library.history().firstOrNull { it.series.id == id }?.series
            ?: library.favorites().firstOrNull { it.id == id } ?: Series(id, "剧集")
        openDetail(series, resume = true)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSeriesLink = AndroidTvChannels.seriesId(intent.dataString)
        if (library.isLoaded && foreground) handleSeriesLink()
    }

    private fun chooseBackupFolder() {
        featureTools.close()
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        runCatching { startActivityForResult(picker, 7114) }.onFailure {
            tvTools.info("外部备份目录", "这台电视没有可用的系统目录选择器，请使用工具中的手机二维码备份。", null)
        }
    }

    @Deprecated("Used for the system folder picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7115) {
            if (resultCode == RESULT_OK) data?.data?.let { appearance.onImagePicked(it) }
            return
        }
        if (requestCode != 7114 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            tvTools.info("外部备份目录", "需要目录的读写授权，原有备份保留。请重新选择可读写目录。", null); return
        }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.onFailure {
            tvTools.info("外部备份目录", "未取得持续读写权限，原有备份保留。可重新选择目录或使用手机二维码。", null); return
        }
        val store = ExternalBackupStore(this)
        store.remember(uri)
        io.execute {
            val previous = runCatching { store.loadLatest() }.getOrNull()
            main.post {
                if (isDestroyed || !library.isLoaded) return@post
                if (previous != null) backupController.preview(previous)
                else {
                    val local = LibraryBackup.encode(library.snapshot())
                    val tools = featureTools.preferences.export()
                    io.execute {
                        val result = runCatching { LocalBackupStorage(this).save(local, tools) }
                        main.post { if (!isDestroyed) tvTools.info("外部备份", if (result.isSuccess) "已保存备份。以后在工具中备份时会同时写入这个目录；卸载重装后重新选择该目录即可恢复。" else "目录已记录，但首次备份未完成，可稍后重试。", null) }
                    }
                }
            }
        }
    }

    // 页面销毁时释放播放器、请求和工作线程，避免资源继续持有旧 Activity。
    override fun onDestroy() {
        generation++
        cancelWork()
        if (networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        social.destroy()
        favoriteMonitor.destroy()
        tvTools.destroy()
        if (::featureTools.isInitialized) featureTools.destroy()
        if (::appearance.isInitialized) appearance.destroy()
        if (::backupController.isInitialized) backupController.destroy()
        exitDialog?.dismiss()

        saveProgress()
        cloudAccount.destroy()
        releasePlayer()
        library.close()
        artwork.close()
        releaseCatalogViews()
        mediaSession.release()
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        rankWorker.shutdownNow()
        prefetchWorker.shutdownNow()
        networkCleanup.shutdown()
        Thread(
                {
                    repository.http.dispatcher.cancelAll()
                    repository.http.connectionPool.evictAll()
                    repository.http.dispatcher.executorService.shutdown()
                },
                "hongguotv-network-cleanup",
            )
            .start()
        super.onDestroy()
    }
}
