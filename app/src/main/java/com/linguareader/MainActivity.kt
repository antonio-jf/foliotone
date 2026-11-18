package com.linguareader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val importRequest = 10
    private val folderRequest = 11
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: BookStore
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var previewTts: TextToSpeech? = null
    private lateinit var previewAudioFocus: PlaybackAudioFocus
    private var previewUtterance: String? = null
    private var previewSequence = 0
    private var previewCloudPlayer: MediaPlayer? = null
    @Volatile private var previewCloudGeneration = 0
    private var previewReady = false
    private var previewEpoch = 0
    private var pendingVoiceRefresh = false
    private var inSettings = false
    private var settingsLanguage = "en"
    private val coverCache = LruCache<String, Bitmap>(12)
    private var selectedId: String? = null
    private var cachedBookId: String? = null
    private var cachedBook: ParsedBook? = null
    private var sessionSection: Int? = null
    private var sessionPage: Int? = null
    private var playing = false
    /** Book the narration service has loaded, from its last state broadcast. */
    private var serviceBookId: String? = null
    /** Positions shown by the current home screen, to redraw it when narration moves on. */
    private var homePositions: Map<String, Pair<Int, Int>> = emptyMap()
    private var playbackButton: TextView? = null
    private var busy = false
    private var opening = false
    private var offlineStatusView: TextView? = null
    private var browsingGutenberg = false
    private var browsingRatings = false
    private var ratingsFilter = 0
    private var libraryFilter = 0
    private var gutenbergQuery = ""
    private val gutenbergResults = mutableListOf<GutenbergBook>()
    private var gutenbergNext: String? = null
    private var gutenbergPrevious: String? = null
    private var gutenbergPageIndex = 1
    private var gutenbergTotal = 0
    private var gutenbergLoading = false
    private var gutenbergGeneration = 0
    private var gutenbergStatus: TextView? = null
    private var gutenbergDownloadStatus: TextView? = null
    private var gutenbergHomeStatus: TextView? = null
    private var gutenbergList: LinearLayout? = null
    private var gutenbergRenderedActiveId: Int? = null
    private var palette = AppThemes.all.first()
    private val background get() = palette.background
    private val surface get() = palette.surface
    private val surfaceRaised get() = palette.raised
    private val ink get() = palette.ink
    private val muted get() = palette.muted
    private val accent get() = palette.accent
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            playing = intent?.getBooleanExtra("playing", false) ?: false
            serviceBookId = intent?.getStringExtra("id")
            playbackButton?.text = playbackLabel()
            if (intent?.getStringExtra("id") == selectedId) {
                sessionSection = intent?.getIntExtra("section", 0)
                sessionPage = intent?.getIntExtra("page", 0)
            }
            intent?.getStringExtra("error")?.let(::toast)
            if (selectedId != null && !inSettings && !busy && !opening) showReader()
            else if (selectedId == null && !inSettings && !browsingGutenberg && !browsingRatings && !opening) {
                // The home screen shows positions; redraw it when narration has moved past them.
                val id = intent?.getStringExtra("id")
                val position = (intent?.getIntExtra("section", 0) ?: 0) to (intent?.getIntExtra("page", 0) ?: 0)
                if (intent?.getBooleanExtra("finished", false) == true ||
                    (id != null && id in homePositions && homePositions[id] != position)) refreshHome()
            }
        }
    }
    private val offlineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra("id") == selectedId) {
                offlineStatusView?.text = intent?.getStringExtra("message")
            }
        }
    }
    private val gutenbergReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: return
            val savedId = intent.getStringExtra("savedBookId")
            if (browsingGutenberg) {
                gutenbergDownloadStatus?.text = message
                if (savedId != null || GutenbergDownloadService.activeId != gutenbergRenderedActiveId)
                    renderGutenbergResults()
            } else if (selectedId == null && !inSettings && !browsingRatings) {
                if (savedId != null) showHome() else gutenbergHomeStatus?.text = message
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BookStore.get(this)
        I18n.init(this)
        previewAudioFocus = PlaybackAudioFocus(this, main) { stopVoicePreview() }
        palette = AppThemes.find(store.themeId)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = background
        window.navigationBarColor = background
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
            setBackgroundColor(this@MainActivity.background)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = if (Build.VERSION.SDK_INT >= 30) {
                    val area = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    intArrayOf(area.left, area.top, area.right, area.bottom)
                } else intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                view.setPadding(bars[0], bars[1], bars[2], bars[3])
                insets
            }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(36))
        }
        scroll.addView(content)
        setContentView(scroll)
        applyThemeColors()
        startPreviewTts()
        startService(Intent(this, ReaderService::class.java))
        showHome()
        openMediaBook(intent)
        AppUpdates.schedule(this)
        if (!handleUpdateIntent(intent)) checkForUpdateOnOpen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openMediaBook(intent)
        handleUpdateIntent(intent)
    }

    // ---- App updates from GitHub releases (AppUpdates.kt) ----

    /** Opened from the "update ready" notification: show the install prompt. */
    private fun handleUpdateIntent(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(AppUpdates.EXTRA_INSTALL, false) != true) return false
        intent.removeExtra(AppUpdates.EXTRA_INSTALL)
        AppUpdates.readyUpdate(this)?.let { offerUpdate(it) } ?: toast(tr("FolioTone is up to date."))
        return true
    }

    private fun checkForUpdateOnOpen() {
        AppUpdates.readyUpdate(this)?.let { ready ->
            if (!AppUpdates.wasOffered(this, ready)) offerUpdate(ready)
            return
        }
        if (!AppUpdates.checkIsDue(this)) return
        worker.execute {
            val result = AppUpdates.check(this)
            main.post {
                if (result is AppUpdates.Result.Downloaded && !isFinishing && !AppUpdates.wasOffered(this, result.ready)) {
                    offerUpdate(result.ready)
                }
            }
        }
    }

    private fun checkForUpdatesNow() {
        toast(tr("Checking for updates…"))
        worker.execute {
            val result = AppUpdates.check(this)
            main.post {
                when (result) {
                    is AppUpdates.Result.UpToDate -> toast(tr("FolioTone {0} is up to date.", result.name))
                    is AppUpdates.Result.Downloaded -> {
                        if (inSettings) showSettings()
                        offerUpdate(result.ready)
                    }
                    is AppUpdates.Result.Failed -> toast(tr("Could not check for updates: {0}", result.message))
                }
            }
        }
    }

    private fun offerUpdate(ready: AppUpdates.Ready) {
        AppUpdates.markOffered(this, ready)
        choiceSheet(tr("FolioTone {0} is ready", ready.name), listOf(
            MenuChoice(tr("Install now"), tr("FolioTone closes and opens again on the new version. Books, progress and downloaded narration stay.")),
            MenuChoice(tr("Later"), tr("Install it anytime from Listening settings → App updates."))
        )) { if (it == 0) installUpdate(ready) }
    }

    private fun installUpdate(ready: AppUpdates.Ready) {
        val started = runCatching { AppUpdates.install(this, ready) }
        when {
            started.isFailure -> toast(tr("Update not installed: {0}", started.exceptionOrNull()?.message ?: ""))
            started.getOrNull() == false -> toast(tr("Allow FolioTone to install apps, then tap Install again."))
        }
    }

    private fun openMediaBook(intent: Intent?) {
        val id = intent?.getStringExtra("mediaBookId") ?: return
        intent.removeExtra("mediaBookId")
        store.books().firstOrNull { it.id == id }?.let { openBook(it, fromMedia = true) }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.linguareader.STATE")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(stateReceiver, filter)
        val offlineFilter = IntentFilter(OfflineDownloadService.UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(offlineReceiver, offlineFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(offlineReceiver, offlineFilter)
        val gutenbergFilter = IntentFilter(GutenbergDownloadService.UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(gutenbergReceiver, gutenbergFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(gutenbergReceiver, gutenbergFilter)
        // Focus loss and remote controls can change playback while this screen is stopped.
        // Subscribe first, then ask for a snapshot so no background change is missed.
        command("GET_STATE")
    }
    override fun onResume() {
        super.onResume()
        if (pendingVoiceRefresh) {
            pendingVoiceRefresh = false
            refreshVoices()
        }
    }
    override fun onStop() {
        stopVoicePreview()
        unregisterReceiver(stateReceiver)
        unregisterReceiver(offlineReceiver)
        unregisterReceiver(gutenbergReceiver)
        super.onStop()
    }
    override fun onDestroy() {
        previewEpoch++
        stopVoicePreview()
        previewTts?.shutdown()
        worker.shutdown()
        super.onDestroy()
    }
    override fun onBackPressed() {
        if (inSettings) closeSettings()
        else if (browsingRatings) showHome()
        else if (browsingGutenberg) showHome()
        else if (selectedId != null) { selectedId = null; showHome() }
        else super.onBackPressed()
    }

    private fun startPreviewTts() {
        val epoch = ++previewEpoch
        previewReady = false
        stopVoicePreview()
        previewTts?.shutdown()
        val listener = TextToSpeech.OnInitListener { status ->
            main.post {
                if (epoch == previewEpoch) {
                    if (status != TextToSpeech.SUCCESS && store.speechEngine != null) {
                        store.speechEngine = null
                        startPreviewTts()
                        command("ENGINE_CHANGED")
                        toast(tr("Speech provider unavailable. Using the system default."))
                        return@post
                    }
                    previewReady = status == TextToSpeech.SUCCESS
                    if (previewReady) previewTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) { main.post { finishPreview(id) } }
                        @Deprecated("Platform callback")
                        override fun onError(id: String?) { main.post { finishPreview(id) } }
                        override fun onError(id: String?, code: Int) { main.post { finishPreview(id) } }
                    })
                    if (inSettings) showSettings()
                }
            }
        }
        val engine = store.speechEngine
        previewTts = if (engine == null) TextToSpeech(this, listener)
            else TextToSpeech(this, listener, engine)
    }

    private fun refreshVoices() {
        startPreviewTts()
        command("ENGINE_CHANGED")
        if (inSettings) showSettings()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun gradient(colors: IntArray, radius: Int = 22) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 16f, color: Int = ink): TextView = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun addText(value: String, size: Float = 16f, color: Int = ink) = text(value, size, color).also { content.addView(it) }
    private fun button(label: String, action: () -> Unit): TextView = text(label, 14f).apply {
        setTextColor(palette.onAccent)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER; minHeight = dp(46); setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(accent, 12)
        setOnClickListener { action() }
        isFocusable = true
    }
    private fun secondary(label: String, action: () -> Unit) = button(label, action).apply {
        setTextColor(ink)
        background = rounded(surfaceRaised, 12)
    }
    private fun gap(height: Int) { content.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }

    private fun applyThemeColors() {
        scroll.setBackgroundColor(background)
        window.statusBarColor = background
        window.navigationBarColor = background
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (palette.light) flags else 0, flags)
        } else {
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = (window.decorView.systemUiVisibility and flags.inv()) or
                if (palette.light) flags else 0
        }
    }

    private fun refreshCurrentScreen() {
        when {
            inSettings -> showSettings()
            browsingRatings -> showRatings()
            browsingGutenberg -> { showGutenberg(); renderGutenbergResults() }
            selectedId != null -> showReader()
            else -> showHome()
        }
    }

    private fun bookStatusOptions(book: Book) {
        choiceSheet(book.title, listOf(
            MenuChoice(if (store.isRead(book.id)) tr("Mark as unread") else tr("Mark as read")),
            MenuChoice(tr("Rate book")), MenuChoice(tr("Open book"))
        )) { index ->
            when (index) {
                0 -> { store.setRead(book.id, !store.isRead(book.id)); refreshCurrentScreen() }
                1 -> rateBook(book)
                2 -> openBook(book)
            }
        }
    }

    private fun chooseTheme() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            background = rounded(surface, 22)
        }
        panel.addView(text(tr("Choose a theme"), 26f).apply { typeface = Typeface.SERIF })
        panel.addView(text(tr("{0} palettes · applies throughout the app", AppThemes.all.size), 13f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(16) })
        val list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            isVerticalScrollBarEnabled = true
        }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = AppThemes.all.size
            override fun getItem(position: Int): Any = AppThemes.all[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val theme = AppThemes.all[position]
                val card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = rounded(theme.surface, 14).apply {
                        setStroke(dp(1), if (theme.id == palette.id) theme.accent else theme.raised)
                    }
                }
                val words = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                words.addView(text(theme.name + if (theme.id == palette.id) "  ✓" else "", 18f, theme.ink).apply {
                    typeface = Typeface.DEFAULT_BOLD
                })
                words.addView(text(tr(theme.mood), 12f, theme.muted),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
                listOf(theme.background, theme.raised, theme.accent).forEach { color ->
                    card.addView(View(this@MainActivity).apply { background = rounded(color, 12) },
                        LinearLayout.LayoutParams(dp(22), dp(30)).apply { marginStart = dp(5) })
                }
                card.contentDescription = "${theme.name}, ${tr(theme.mood)}" + if (theme.id == palette.id) tr(", selected") else ""
                return card
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(secondary(tr("Cancel")) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        list.setOnItemClickListener { _, _, position, _ ->
            palette = AppThemes.all[position]
            store.themeId = palette.id
            dialog.dismiss()
            applyThemeColors()
            refreshCurrentScreen()
        }
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.55f }
            setLayout(minOf(dp(460), resources.displayMetrics.widthPixels - dp(24)),
                (resources.displayMetrics.heightPixels * 0.82f).toInt())
        }
        list.setSelection(AppThemes.all.indexOf(palette))
    }
    /**
     * Percent of the book read, counted in pages: sections can differ hugely in length, so counting
     * each section as an equal share is badly off. Books not opened since this was added only know the
     * current section's size and fall back to that estimate until they are opened once.
     */
    private fun progress(book: Book): Int {
        if (store.isRead(book.id)) return 100
        if (book.sectionCount <= 0) return 0
        val pages = book.sectionPages
        val total = pages.sum()
        if (pages.size == book.sectionCount && total > 0) {
            val read = pages.take(book.section.coerceIn(0, pages.size)).sum() + book.page
            return (read * 100L / total).toInt().coerceIn(0, 100)
        }
        return (((book.section + book.page.toFloat() / book.pagesInSection.coerceAtLeast(1)) / book.sectionCount) * 100)
            .toInt().coerceIn(0, 100)
    }
    private fun progressBar(value: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progress = value
        progressTintList = android.content.res.ColorStateList.valueOf(accent)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(surfaceRaised)
    }
    private fun poster(book: Book, width: Int, height: Int) = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(width), dp(height))
        val palette = intArrayOf(Color.rgb(88, 102, 92), Color.rgb(107, 93, 81), Color.rgb(71, 95, 100), Color.rgb(112, 103, 79))
        background = rounded(palette[(book.id.hashCode() and Int.MAX_VALUE) % palette.size], 14)
        clipToOutline = true
        val cover = store.coverFile(book)
        val cacheKey = "${book.id}:${width}x$height"
        val image = coverCache.get(cacheKey) ?: cover?.let {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.path, bounds)
                val sample = maxOf(bounds.outWidth / dp(width), bounds.outHeight / dp(height)).coerceAtLeast(1)
                BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.also { bitmap -> coverCache.put(cacheKey, bitmap) }
            }.getOrNull()
        }
        if (image != null) addView(ImageView(this@MainActivity).apply {
            setImageBitmap(image); scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = tr("Cover of {0}", book.title)
        }, FrameLayout.LayoutParams(-1, -1))
        else {
            val coverInk = Color.rgb(241, 233, 216)
            if (width >= 75) addView(text("FOLIOTONE", 10f, coverInk),
                FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(14), dp(15), dp(10), 0) })
            addView(text(book.title.trim().take(1).uppercase(), if (width < 75) 34f else 49f, coverInk).apply {
                typeface = Typeface.SERIF; gravity = Gravity.CENTER
            },
                FrameLayout.LayoutParams(-1, -1))
            if (width >= 75) addView(text("◆", 12f, coverInk),
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(10), dp(10)) })
        }
    }
    private fun command(action: String, vararg extras: Pair<String, Any>) {
        val intent = Intent(this, ReaderService::class.java).setAction(action)
        extras.forEach { (key, value) -> when (value) {
            is String -> intent.putExtra(key, value)
            is Int -> intent.putExtra(key, value)
        } }
        startService(intent)
    }

    private data class MenuChoice(val title: String, val detail: String = "")

    private fun choiceSheet(
        title: String,
        choices: List<MenuChoice>,
        selectedIndex: Int = -1,
        confirmLabel: String? = null,
        onPreview: (Int) -> Unit = {},
        onDismiss: () -> Unit = {},
        onChoose: (Int) -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var selected = selectedIndex
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val shape = rounded(surface, 24)
            background = shape
            setPadding(dp(20), dp(18), dp(20), dp(22))
            if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { view, insets ->
                val bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom
                view.setPadding(dp(20), dp(18), dp(20), dp(22) + bottom)
                insets
            }
        }
        panel.addView(View(this).apply { background = rounded(muted, 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        panel.addView(text(title, 23f).apply { typeface = Typeface.SERIF })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val markers = mutableListOf<TextView>()
        choices.forEachIndexed { index, choice ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(surfaceRaised, 12)
                isClickable = true; isFocusable = true
            }
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(text(choice.title, 16f).apply { typeface = Typeface.DEFAULT_BOLD })
            if (choice.detail.isNotBlank()) words.addView(text(choice.detail, 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            item.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            val mark = text(if (index == selected) "✓" else "", 20f, accent)
            item.addView(mark, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            markers += mark
            item.setOnClickListener {
                if (confirmLabel == null) { dialog.dismiss(); onChoose(index) }
                else {
                    selected = index
                    markers.forEachIndexed { i, marker -> marker.text = if (i == selected) "✓" else "" }
                    onPreview(index)
                }
            }
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val optionsScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(list)
        }
        panel.addView(optionsScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(15) })
        if (confirmLabel != null) panel.addView(button(confirmLabel) {
            if (selected in choices.indices) { dialog.dismiss(); onChoose(selected) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        dialog.setContentView(panel)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.55f; gravity = Gravity.BOTTOM }
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
            val wanted = dp(126 + choices.size * 76 + if (confirmLabel == null) 0 else 58)
            setLayout(-1, minOf(wanted, (resources.displayMetrics.heightPixels * 0.82f).toInt()))
        }
        if (selectedIndex in choices.indices) optionsScroll.post {
            optionsScroll.scrollTo(0, list.getChildAt(selectedIndex).top)
        }
    }

    private fun showHome() {
        playbackButton = null
        inSettings = false
        browsingRatings = false
        browsingGutenberg = false
        gutenbergGeneration++
        gutenbergStatus = null
        gutenbergDownloadStatus = null
        gutenbergHomeStatus = null
        gutenbergList = null
        gutenbergRenderedActiveId = null
        offlineStatusView = null
        homePositions = emptyMap()
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_foliotone_foreground)
            contentDescription = tr("FolioTone logo")
            background = rounded(surface, 12)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("FolioTone", 25f).apply { typeface = Typeface.SERIF })
        brand.addView(text(tr("Your listening library"), 12f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        header.addView(secondary(tr("Settings")) {
            settingsLanguage = store.books().firstOrNull { it.id == store.lastOpenedId }
                ?.language?.ifBlank { "en" } ?: "en"
            showSettings()
        }, LinearLayout.LayoutParams(-2, -2))
        content.addView(header)
        gap(22)
        content.addView(button(tr("＋  Add a book")) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/epub+zip"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/octet-stream"))
            }, importRequest)
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(secondary(tr("Browse Project Gutenberg")) { openGutenberg() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(secondary(tr("★  Ratings & best books")) { ratingsFilter = 0; showRatings() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(secondary(tr("Themes · {0}", palette.name)) { chooseTheme() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        GutenbergDownloadService.progressText?.takeIf { GutenbergDownloadService.activeId != null }
            ?.let {
                val status = text(it, 12f, accent)
                gutenbergHomeStatus = status
                content.addView(status,
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        val books = store.books()
        gap(24)
        if (books.isEmpty()) {
            addText(tr("Your shelf is ready"), 23f).typeface = Typeface.SERIF
            gap(10)
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(20), dp(18), dp(20))
                background = rounded(surface, 16)
                addView(text(tr("Add an EPUB to begin reading and listening."), 15f, muted))
            }
            content.addView(empty)
        } else {
            addText(tr("Continue"), 23f).typeface = Typeface.SERIF
            gap(10)
            homePositions = books.associate { it.id to (it.section to it.page) }
            val recent = books.firstOrNull { it.id == store.lastOpenedId } ?: books.first()
            val continueCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(surface, 18)
                isClickable = true; isFocusable = true
                setOnClickListener { openBook(recent) }
            }
            continueCard.addView(poster(recent, 78, 112))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(recent.title, 21f).apply { typeface = Typeface.SERIF; maxLines = 2 })
            if (recent.author.isNotBlank()) info.addView(text(recent.author, 12f, muted).apply { maxLines = 1 },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            info.addView(text(tr("Section {0} · Page {1}", recent.section + 1, recent.page + 1), 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })
            info.addView(progressBar(progress(recent)), LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
            if (store.isRead(recent.id)) info.addView(text(tr("✓ Read"), 12f, accent),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            narrationBadge(recent)?.let { info.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
            info.addView(text(tr("Resume reading  →"), 13f, accent).apply { typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            continueCard.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(15) })
            content.addView(continueCard)
            gap(26)
            addText(tr("Library"), 23f).typeface = Typeface.SERIF
            gap(5)
            addText("${books.size} ${if (books.size == 1) "book" else "books"}", 13f, muted)
            gap(12)
            val filters = listOf(tr("All books"), tr("Unread"), tr("Read"))
            val libraryControls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            libraryControls.addView(secondary("${filters[libraryFilter]}  ▾") {
                choiceSheet(tr("Library view"), filters.map { MenuChoice(it) }, libraryFilter) { index ->
                    libraryFilter = index; showHome()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            fun viewToggle(label: String, grid: Boolean): TextView {
                val selected = store.libraryGrid == grid
                val action = { setLibraryGrid(grid) }
                return (if (selected) button(label, action) else secondary(label, action)).apply {
                    isSelected = selected
                    contentDescription = tr("{0} view{1}", label, if (selected) tr(", selected") else "")
                }
            }
            libraryControls.addView(viewToggle(tr("List"), false),
                LinearLayout.LayoutParams(dp(64), -2).apply { marginEnd = dp(6) })
            libraryControls.addView(viewToggle(tr("Grid"), true), LinearLayout.LayoutParams(dp(64), -2))
            content.addView(libraryControls)
            gap(10)
            val visible = books.filter { libraryFilter == 0 || store.isRead(it.id) == (libraryFilter == 2) }
            if (visible.isEmpty()) addText(if (libraryFilter == 2) tr("No read books yet.") else tr("No unread books."), 14f, muted)
            if (store.libraryGrid) showLibraryGrid(visible)
            else visible.forEach { book ->
                val bookRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(11), dp(10), dp(11), dp(10))
                    background = rounded(surface, 15)
                    addView(poster(book, 59, 83))
                    val details = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                    details.addView(text(book.title, 18f).apply { typeface = Typeface.SERIF; maxLines = 2 })
                    details.addView(text(book.author.ifBlank { tr("Unknown author") }, 12f, muted).apply { maxLines = 1 },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                    details.addView(text(tr("Section {0} · {1}%", book.section + 1, progress(book)), 12f, accent),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
                    store.rating(book.id)?.let { score ->
                        details.addView(text("★ ${ratingScore(score)} / 5.0", 12f, accent),
                            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                    }
                    details.addView(text(if (store.isRead(book.id)) tr("✓ Read") else tr("Unread"), 12f, muted),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                    narrationBadge(book)?.let { details.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) }) }
                    addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
                    addView(text("›", 30f, muted), LinearLayout.LayoutParams(-2, -2))
                    isClickable = true; isFocusable = true
                    setOnClickListener { openBook(book) }
                    setOnLongClickListener { bookStatusOptions(book); true }
                }
                content.addView(bookRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
            }
        }
        gap(17)
        addText(tr("Long-press a library book to change its read status."), 12f, muted)
        gap(10)
        content.addView(secondary(if (store.folderUri == null) tr("Choose a book folder") else tr("Scan book folder")) {
            if (store.folderUri == null) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), folderRequest)
            else scanFolder()
        })
    }

    /** "🎧 Server narration downloaded" (accent) or a partial/other-voice note (muted); null if none saved. */
    private fun narrationBadge(book: Book): TextView? =
        runCatching { NarrationLibrary.badge(this, book) }.getOrNull()?.let { (label, complete) ->
            text(label, 12f, if (complete) accent else muted).apply {
                if (complete) typeface = Typeface.DEFAULT_BOLD
                contentDescription = label.removePrefix("🎧 ")
            }
        }

    private fun refreshHome() {
        val position = scroll.scrollY
        showHome()
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun setLibraryGrid(grid: Boolean) {
        if (store.libraryGrid == grid) return
        store.libraryGrid = grid
        val position = scroll.scrollY
        showHome()
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun showLibraryGrid(books: List<Book>) {
        val widthDp = (resources.displayMetrics.widthPixels - scroll.paddingLeft - scroll.paddingRight -
            content.paddingLeft - content.paddingRight) / resources.displayMetrics.density
        val columns = (widthDp / 150).toInt().coerceIn(if (widthDp >= 260) 2 else 1, 5)
        val cardWidth = ((widthDp - (columns - 1) * 10) / columns).toInt()
        val coverWidth = (cardWidth - 24).coerceAtLeast(40)
        val coverHeight = (coverWidth * 1.4f).toInt()
        books.chunked(columns).forEach { group ->
            val gridRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            repeat(columns) { column ->
                val book = group.getOrNull(column)
                val cell = if (book == null) View(this) else LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = rounded(surface, 15)
                    addView(poster(book, coverWidth, coverHeight),
                        LinearLayout.LayoutParams(dp(coverWidth), dp(coverHeight)).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        })
                    addView(text(book.title, 17f).apply {
                        typeface = Typeface.SERIF
                        maxLines = 2
                        minLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                    addView(text(book.author.ifBlank { tr("Unknown author") }, 12f, muted).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                    addView(progressBar(progress(book)),
                        LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(12) })
                    addView(text("${progress(book)}% · ${if (store.isRead(book.id)) tr("Read") else tr("Unread")}", 12f, accent),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
                    addView(text(store.rating(book.id)?.let { "★ ${ratingScore(it)} / 5.0" } ?: tr("Not rated yet"), 12f, muted),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                    narrationBadge(book)?.let {
                        addView(it.apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },
                            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openBook(book) }
                    setOnLongClickListener { bookStatusOptions(book); true }
                }
                gridRow.addView(cell, LinearLayout.LayoutParams(0, if (book == null) 1 else -1, 1f).apply {
                    if (column < columns - 1) marginEnd = dp(10)
                })
            }
            content.addView(gridRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun ratingScore(tenths: Int) = "${tenths / 10}.${tenths % 10}"

    private fun refreshRatingScreen() {
        if (selectedId != null) showReader()
        else if (browsingRatings) showRatings()
        else showHome()
    }

    private fun rateBook(book: Book) {
        val existing = store.rating(book.id)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = rounded(surface, 22)
        }
        panel.addView(text(tr("Your rating"), 25f).apply { typeface = Typeface.SERIF })
        panel.addView(text(book.title, 16f).apply { maxLines = 3 },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        panel.addView(text(tr("Choose 0.0 to 5.0 stars in steps of 0.1."), 13f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val score = text("", 30f, accent).apply { gravity = Gravity.CENTER }
        panel.addView(score, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        val slider = SeekBar(this).apply {
            max = 50
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    score.text = "★ ${ratingScore(value)} / 5.0"
                    contentDescription = tr("Book rating, {0} out of 5 stars", ratingScore(value))
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
            progress = existing ?: 0
        }
        score.text = "★ ${ratingScore(slider.progress)} / 5.0"
        slider.contentDescription = tr("Book rating, {0} out of 5 stars", ratingScore(slider.progress))
        panel.addView(slider, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        val adjustments = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        adjustments.addView(secondary("− 0.1") { slider.progress = (slider.progress - 1).coerceAtLeast(0) }
            .apply { contentDescription = tr("Decrease rating by 0.1 stars") },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        adjustments.addView(secondary("+ 0.1") { slider.progress = (slider.progress + 1).coerceAtMost(50) }
            .apply { contentDescription = tr("Increase rating by 0.1 stars") },
            LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(adjustments)
        panel.addView(button(tr("Save rating")) {
            store.setRating(book.id, slider.progress)
            dialog.dismiss()
            refreshRatingScreen()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        if (existing != null) panel.addView(secondary(tr("Remove rating")) {
            store.setRating(book.id, null)
            dialog.dismiss()
            refreshRatingScreen()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.addView(secondary(tr("Cancel")) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        dialog.setContentView(ScrollView(this).apply { addView(panel) })
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.55f }
            setLayout(minOf(dp(420), resources.displayMetrics.widthPixels - dp(32)), -2)
        }
    }

    private fun showRatings() {
        selectedId = null
        inSettings = false
        browsingRatings = true
        browsingGutenberg = false
        gutenbergGeneration++
        gutenbergHomeStatus = null
        offlineStatusView = null
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        content.addView(secondary(tr("←  Library")) { showHome() }, LinearLayout.LayoutParams(-2, -2))
        gap(20)
        addText(tr("Your book ratings"), 29f).typeface = Typeface.SERIF
        gap(8)
        addText(tr("Score your reads and find your favorites."), 14f, muted)
        val books = store.books()
        val scores = books.associate { it.id to store.rating(it.id) }
        val rated = books.filter { scores[it.id] != null }
        gap(18)
        addText(tr("{0} of {1} books rated", rated.size, books.size), 16f, accent)
        if (rated.isNotEmpty()) {
            val average = rated.map { scores.getValue(it.id)!! }.average() / 10.0
            gap(6)
            addText(tr("Average rating · {0} / 5.0", String.format(java.util.Locale.US, "%.1f", average)), 14f, muted)
        }
        val filters = listOf(tr("Highest rated"), tr("Lowest rated"), tr("All books · A–Z"), tr("Unrated books"))
        gap(20)
        content.addView(secondary("${filters[ratingsFilter]}  ▾") {
            choiceSheet(tr("Browse ratings"), filters.map { MenuChoice(it) }, ratingsFilter) { index ->
                ratingsFilter = index
                showRatings()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        gap(16)
        val byTitle = compareBy<Book> { it.title.lowercase(java.util.Locale.ROOT) }.thenBy { it.id }
        val visible = when (ratingsFilter) {
            0 -> rated.sortedWith(compareByDescending<Book> { scores.getValue(it.id)!! }.then(byTitle))
            1 -> rated.sortedWith(compareBy<Book> { scores.getValue(it.id)!! }.then(byTitle))
            2 -> books.sortedWith(byTitle)
            else -> books.filter { scores[it.id] == null }.sortedWith(byTitle)
        }
        if (visible.isEmpty()) {
            addText(when {
                books.isEmpty() -> tr("Add an EPUB to your library to start rating books.")
                ratingsFilter == 3 -> tr("Every book in your library has a rating.")
                else -> tr("No ratings yet. Choose All books or Unrated books to rate your first read.")
            }, 15f, muted)
        }
        visible.forEachIndexed { index, book ->
            val rating = scores[book.id]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(surface, 16)
            }
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(poster(book, 59, 83))
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(text(book.title, 19f).apply { typeface = Typeface.SERIF; maxLines = 3 })
            details.addView(text(book.author.ifBlank { tr("Unknown author") }, 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            val rank = if (ratingsFilter == 0) "#${index + 1} · " else ""
            details.addView(text(rating?.let { "$rank★ ${ratingScore(it)} / 5.0" } ?: tr("Not rated yet"), 14f, accent),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            heading.addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            card.addView(heading)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(secondary(tr("Read book")) { openBook(book) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            actions.addView(button(if (rating == null) tr("Rate book") else tr("Edit rating")) { rateBook(book) },
                LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun openGutenberg() {
        selectedId = null
        gutenbergQuery = ""
        gutenbergResults.clear()
        gutenbergNext = null
        gutenbergPrevious = null
        gutenbergPageIndex = 1
        gutenbergTotal = 0
        showGutenberg()
        loadGutenbergPage(true)
    }

    private fun showGutenberg() {
        browsingGutenberg = true
        inSettings = false
        gutenbergHomeStatus = null
        offlineStatusView = null
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        content.addView(secondary(tr("←  Library")) { showHome() }, LinearLayout.LayoutParams(-2, -2))
        gap(20)
        addText(tr("Project Gutenberg"), 29f).typeface = Typeface.SERIF
        gap(8)
        addText(tr("Find free EPUB books and save them to your library."), 14f, muted)
        gap(18)
        val search = EditText(this).apply {
            hint = tr("Search title or author")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextColor(ink); setHintTextColor(muted)
            textSize = 16f
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = rounded(surfaceRaised, 12)
            setText(gutenbergQuery)
            contentDescription = tr("Search Project Gutenberg books")
        }
        content.addView(search)
        fun submit() {
            gutenbergQuery = search.text.toString().trim()
            loadGutenbergPage(true)
        }
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                submit(); true
            } else false
        }
        content.addView(button(tr("Search books")) { submit() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        gap(14)
        addText(tr("Catalog: Gutendex · EPUB files: Project Gutenberg"), 12f, muted)
        gap(5)
        addText(tr("Copyright status may differ outside the United States. Check your local rules before downloading."),
            12f, muted)
        gutenbergDownloadStatus = addText(GutenbergDownloadService.progressText.orEmpty(), 13f, accent)
        gap(18)
        gutenbergStatus = addText("", 13f, accent)
        gap(12)
        gutenbergList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            .also { content.addView(it) }
    }

    private fun loadGutenbergPage(reset: Boolean, pageUrl: String? = null) {
        if (!browsingGutenberg || (!reset && gutenbergLoading)) return
        if (reset) {
            gutenbergResults.clear()
            gutenbergNext = null
            gutenbergPrevious = null
            gutenbergPageIndex = 1
            gutenbergTotal = 0
            gutenbergList?.removeAllViews()
        } else if (pageUrl == null || pageUrl != gutenbergNext && pageUrl != gutenbergPrevious) return
        val token = ++gutenbergGeneration
        val query = gutenbergQuery
        val next = if (reset) null else pageUrl
        val pageIndex = if (reset) 1 else if (pageUrl == gutenbergNext) gutenbergPageIndex + 1
            else (gutenbergPageIndex - 1).coerceAtLeast(1)
        gutenbergLoading = true
        gutenbergStatus?.text = if (reset) tr("Loading books…") else tr("Loading more books…")
        worker.execute {
            val result = runCatching { GutenbergCatalog.search(query, next) }
            main.post {
                if (!browsingGutenberg || token != gutenbergGeneration || isDestroyed) return@post
                gutenbergLoading = false
                result.onSuccess { page ->
                    gutenbergResults.clear()
                    gutenbergResults += page.books
                    gutenbergNext = page.next
                    gutenbergPrevious = page.previous
                    gutenbergPageIndex = pageIndex
                    gutenbergTotal = page.total
                    gutenbergStatus?.text = if (gutenbergResults.isEmpty())
                        tr("No supported EPUBs on this page · {0} catalog matches", gutenbergTotal)
                    else tr("Page {0} · {1} EPUBs · {2} catalog matches", gutenbergPageIndex, gutenbergResults.size, gutenbergTotal)
                    renderGutenbergResults()
                    if (!reset) scroll.smoothScrollTo(0, 0)
                }.onFailure {
                    gutenbergStatus?.text = tr("Could not load books: {0}", GutenbergCatalog.message(it))
                    renderGutenbergResults()
                }
            }
        }
    }

    private fun renderGutenbergResults() {
        val list = gutenbergList ?: return
        gutenbergRenderedActiveId = GutenbergDownloadService.activeId
        list.removeAllViews()
        if (gutenbergResults.isEmpty() && !gutenbergLoading) {
            list.addView(text(tr("Try the next page or another title or author."), 14f, muted))
        }
        val owned = store.books().mapNotNull { it.gutenbergId?.let { id -> id to it } }.toMap()
        gutenbergResults.forEach { entry ->
            val card = settingsCard(entry.title)
            if (entry.author.isNotBlank()) card.addView(text(entry.author, 13f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            card.addView(text(tr("{0} · Project Gutenberg #{1}", entry.languages.joinToString(", ").uppercase(), entry.id),
                12f, accent), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            val existing = owned[entry.id]
            val active = GutenbergDownloadService.activeId == entry.id
            card.addView(secondary(when {
                existing != null -> tr("Open from library")
                active -> tr("Cancel download")
                else -> tr("Download EPUB")
            }) {
                when {
                    existing != null -> openBook(existing)
                    active -> startService(Intent(this, GutenbergDownloadService::class.java)
                        .setAction("CANCEL").putExtra("id", entry.id))
                    else -> downloadGutenberg(entry)
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        if (!gutenbergLoading && (gutenbergPrevious != null || gutenbergNext != null)) {
            val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            gutenbergPrevious?.let { previous ->
                navigation.addView(secondary(tr("← Previous")) { loadGutenbergPage(false, previous) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
            }
            gutenbergNext?.let { next ->
                navigation.addView(secondary(tr("Next →")) { loadGutenbergPage(false, next) },
                    LinearLayout.LayoutParams(0, -2, 1f))
            }
            list.addView(navigation, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }

    private fun downloadGutenberg(entry: GutenbergBook) {
        if (GutenbergDownloadService.activeId != null) {
            toast(tr("Another book is downloading. Cancel it before starting this one."))
            return
        }
        gutenbergDownloadStatus?.text = tr("Preparing {0}…", entry.title)
        runCatching {
            startForegroundService(Intent(this, GutenbergDownloadService::class.java)
                .setAction("DOWNLOAD").putExtra("id", entry.id)
                .putExtra("title", entry.title).putExtra("url", entry.epubUrl))
        }.onFailure { gutenbergDownloadStatus?.text = tr("Could not start download: {0}", it.message) }
    }

    private fun closeSettings() {
        inSettings = false
        if (selectedId == null) showHome() else showReader()
    }

    private fun settingsCard(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(surface, 18)
        addView(text(title, 18f).apply { typeface = Typeface.SERIF })
    }

    // ---- Compact settings list: a small heading over a card of tappable rows. ----

    private fun compact(view: TextView, height: Int = 40) = view.apply {
        minHeight = dp(height); textSize = 13f; setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun rowOf(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, view ->
            addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i < views.lastIndex) marginEnd = dp(6)
            })
        }
    }

    private fun rowRipple() = android.util.TypedValue().let { value ->
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId)
    }

    private fun settingsGroup(title: String): LinearLayout {
        content.addView(text(title.uppercase(), 11f, accent).apply {
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(6); marginStart = dp(4) })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
            background = rounded(surface, 16)
        }.also { content.addView(it) }
    }

    private fun addSettingRow(card: LinearLayout, row: View) {
        if (card.childCount > 0) card.addView(View(this).apply { setBackgroundColor(surfaceRaised) },
            LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2 + 1)))
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun settingTexts(title: String, detail: String?): Pair<LinearLayout, TextView?> {
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f))
        val detailView = detail?.let {
            text(it, 12.5f, muted).also { view ->
                texts.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
        }
        return texts to detailView
    }

    /** A tappable row: title, optional value underneath, chevron. Returns the value view for later updates. */
    private fun settingRow(card: LinearLayout, title: String, value: String? = null, chevron: Boolean = true,
                           action: () -> Unit): TextView? {
        val (texts, valueView) = settingTexts(title, value)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50); setPadding(0, dp(10), 0, dp(10))
            background = rowRipple(); isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (chevron) row.addView(text("›", 22f, muted), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        addSettingRow(card, row)
        return valueView
    }

    private fun switchRow(card: LinearLayout, title: String, detail: String?, checked: Boolean,
                          onChange: (Boolean) -> Unit) {
        val (texts, _) = settingTexts(title, detail)
        val toggle = Switch(this).apply {
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50); setPadding(0, dp(10), 0, dp(10))
            background = rowRipple()
            setOnClickListener { toggle.toggle() }
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        addSettingRow(card, row)
    }

    private fun settingNote(card: LinearLayout, value: String) {
        card.addView(text(value, 12f, muted).apply { setLineSpacing(0f, 1.1f) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12); topMargin = dp(2) })
    }

    private fun showSettings() {
        playbackButton = null
        inSettings = true
        offlineStatusView = null
        content.removeAllViews()
        scroll.scrollTo(0, 0)
        content.addView(compact(secondary(if (selectedId == null) tr("←  Library") else tr("←  Reader")) { closeSettings() }),
            LinearLayout.LayoutParams(-2, -2))
        gap(12)
        addText(tr("Listening settings"), 24f).typeface = Typeface.SERIF

        val general = settingsGroup(tr("General"))
        val languageChoice = store.appLanguage
        settingRow(general, tr("App language"), if (languageChoice == I18n.SYSTEM)
            tr("System default · {0}", I18n.LANGUAGES.first { it.first == I18n.language }.second)
            else I18n.LANGUAGES.firstOrNull { it.first == languageChoice }?.second ?: "English") { chooseAppLanguage() }
        settingRow(general, tr("Theme"), palette.name) { chooseTheme() }
        val speedValue = text("%.2f×".format(store.speechRate), 13f, muted)
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50); setPadding(0, dp(6), 0, dp(6))
        }
        speedRow.addView(text(tr("Speed"), 15f))
        speedRow.addView(SeekBar(this).apply {
            max = 9
            progress = ((store.speechRate - 0.75f) / 0.05f).toInt().coerceIn(0, 9)
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            contentDescription = tr("Narration speed")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) speedValue.text = "%.2f×".format(0.75f + value * 0.05f)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    store.speechRate = 0.75f + (bar?.progress ?: 4) * 0.05f
                    if (store.narrationSource == "elevenlabs") pauseOfflineDownload()
                    command("VOICE_CHANGED")
                }
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        speedRow.addView(speedValue, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        addSettingRow(general, speedRow)

        val narration = settingsGroup(tr("Narration"))
        settingRow(narration, tr("Source"), when (store.narrationSource) {
            "elevenlabs" -> tr("ElevenLabs")
            "server" -> tr("Your narration server")
            else -> tr("Device voices")
        }) { chooseNarrationSource() }
        when (store.narrationSource) {
            "elevenlabs" -> showElevenSettings(narration)
            "server" -> showServerSettings(narration)
            else -> showDeviceVoiceSettings(narration)
        }

        val reading = settingsGroup(tr("Reading"))
        switchRow(reading, tr("Resume one page earlier"), tr("Keeps a little context when reopening a book."),
            store.contextResume) { store.contextResume = it }
        switchRow(reading, tr("Automatically mark finished books as read"),
            tr("When narration finishes or you go past the last page"), store.autoMarkRead) { store.autoMarkRead = it }

        val updates = settingsGroup(tr("App updates"))
        val ready = AppUpdates.readyUpdate(this)
        settingRow(updates, tr("Version {0}", AppUpdates.installedName(this)),
            if (ready != null) tr("Version {0} is downloaded and ready") else tr("Tap to check for updates")) {
            checkForUpdatesNow()
        }
        if (ready != null) settingRow(updates, tr("Install version {0}", ready.name),
            tr("FolioTone closes and opens again on the new version.")) { installUpdate(ready) }
        switchRow(updates, tr("Download updates automatically"),
            if (AppUpdates.isDevelopmentBuild(this)) tr("Off for builds from Android Studio; you can still check by hand.")
            else tr("Checks GitHub twice a day; you confirm each install."),
            AppUpdates.isAutomatic(this)) { AppUpdates.setAutomatic(this, it) }

        val storage = settingsGroup(tr("Storage"))
        val storageValue = settingRow(storage, tr("Delete all downloaded narration"), tr("Checking saved audio…"),
            chevron = false) { deleteAllNarration() }
        gap(24)
        worker.execute {
            val bytes = runCatching { NarrationLibrary.bytesUsed(this) }.getOrDefault(0L)
            main.post {
                storageValue?.text = if (bytes == 0L) tr("No narration is saved on this device.")
                    else tr("{0} MB of server and ElevenLabs audio · books and progress stay", "%.1f".format(bytes / 1_048_576.0))
            }
        }
    }

    private fun showDeviceVoiceSettings(card: LinearLayout) {
        val engine = store.speechEngine
        val provider = previewTts?.engines?.firstOrNull { it.name == engine }?.label?.toString()
            ?: if (engine == null) tr("System default") else tr("Unavailable provider")
        settingRow(card, tr("Speech provider"), provider) { chooseEngine() }
        settingRow(card, tr("Language"), languageName(settingsLanguage)) { chooseSettingsLanguage() }
        val voices = if (previewReady) previewTts?.let { NarrationVoices.available(it, settingsLanguage) }.orEmpty()
            else emptyList()
        val saved = store.voiceName(settingsLanguage)
        val current = voices.firstOrNull { it.name == saved }
        val voiceStatus = when {
            !previewReady -> tr("Loading voices…")
            current != null -> NarrationVoices.label(current, voices.indexOf(current) + 1)
            saved != null -> tr("Saved voice is unavailable. Choose another voice.")
            voices.isNotEmpty() -> tr("Recommended: {0}", NarrationVoices.label(voices.first(), 1))
            else -> tr("No {0} voice in this provider.", languageName(settingsLanguage))
        }
        settingRow(card, tr("Voice"), voiceStatus) { chooseVoice(settingsLanguage) }
        settingRow(card, tr("Refresh downloaded voices")) { refreshVoices() }
        settingRow(card, tr("Download more voices"), chevron = false) { installVoices() }
        switchRow(card, tr("Gapless playback"),
            tr("Prepares the next sentences and page in advance, so voices like SherpaTTS read without pauses."),
            store.renderAhead) {
            store.renderAhead = it
            command("VOICE_CHANGED")
        }
    }

    private fun showElevenSettings(card: LinearLayout) {
        val connected = ElevenLabsKey(this).read() != null
        settingRow(card, tr("API key"), if (connected) tr("Connected on this device") else tr("Not connected · tap to connect")) {
            enterElevenKey()
        }
        if (!connected) {
            settingRow(card, tr("How to get an API key ↗"), chevron = false) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://elevenlabs.io/docs/help-center/technical/how-do-i-authorize-myself-using-an-api-key")))
            }
        } else {
            settingRow(card, tr("Language"), languageName(settingsLanguage)) { chooseSettingsLanguage() }
            settingRow(card, tr("Voice"), store.elevenVoiceName(settingsLanguage) ?: tr("None selected")) {
                chooseElevenVoice(settingsLanguage)
            }
            settingRow(card, tr("Remove key and saved audio"), chevron = false) { removeElevenKey() }
        }
        settingNote(card, tr("Book text is sent to ElevenLabs; creating audio uses your account credits."))
    }

    private fun chooseNarrationSource() {
        val sources = listOf("device", "elevenlabs", "server")
        choiceSheet(tr("Narration source"), listOf(
            MenuChoice(tr("Device voices"), tr("Downloaded voices work offline")),
            MenuChoice(tr("ElevenLabs"), tr("Natural cloud narration with your API key")),
            MenuChoice(tr("Your narration server"), "Voxtral voices on your own Modal server · download books, then listen offline")
        ), sources.indexOf(store.narrationSource).coerceAtLeast(0)) { index ->
            when {
                index == 1 && ElevenLabsKey(this).read() == null -> enterElevenKey()
                index == 2 && ServerCredentials(this).read() == null -> enterServerConfig()
                else -> {
                    if (sources[index] != store.narrationSource) pauseOfflineDownload()
                    store.narrationSource = sources[index]
                    command("SOURCE_CHANGED")
                    showSettings()
                }
            }
        }
    }

    private fun enterElevenKey() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            background = rounded(surface, 22)
        }
        panel.addView(text(tr("Connect ElevenLabs"), 24f).apply { typeface = Typeface.SERIF })
        panel.addView(text(tr("Create a key in ElevenLabs → Developers → API Keys, then copy its full value when it appears. Enable Voices (read) and Text to Speech. Book text uses your credits; the key is encrypted on this device."), 13f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val input = EditText(this).apply {
            hint = tr("ElevenLabs API key")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ink); setHintTextColor(muted)
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surfaceRaised, 12)
        }
        panel.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        var keyVisible = false
        lateinit var visibility: TextView
        visibility = secondary(tr("Show key")) {
            keyVisible = !keyVisible
            input.inputType = InputType.TYPE_CLASS_TEXT or if (keyVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_PASSWORD
            input.setSelection(input.text.length)
            visibility.text = if (keyVisible) tr("Hide key") else tr("Show key")
        }
        panel.addView(visibility, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val status = text("", 13f, muted)
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        var checking = false
        val connect = button(tr("Save and connect")) {
            if (checking) return@button
            val key = ElevenLabs.normalizeKey(input.text.toString())
            if (key.isBlank()) { status.text = tr("Enter your API key first."); return@button }
            if (key.any { it.isWhitespace() }) {
                status.text = tr("This key contains spaces or line breaks. Paste only the full API key.")
                return@button
            }
            checking = true
            status.text = tr("Checking ElevenLabs voices…")
            input.isEnabled = false
            worker.execute {
                val result = runCatching { ElevenLabs(this).voices(key) }
                main.post {
                    if (!dialog.isShowing) return@post
                    result.onSuccess { voices ->
                        if (voices.isEmpty()) {
                            status.text = tr("No voices found in this account. Add one in ElevenLabs, then try again.")
                            input.isEnabled = true
                            checking = false
                        } else runCatching {
                            pauseOfflineDownload()
                            ElevenLabsKey(this).save(key)
                            ElevenLabs(this).clearCache()
                            store.resetElevenVoices(voices)
                            store.narrationSource = "elevenlabs"
                        }.onSuccess {
                            dialog.dismiss()
                            command("SOURCE_CHANGED")
                            showSettings()
                            toast(tr("ElevenLabs connected. Choose a voice to hear a sample."))
                        }.onFailure {
                            status.text = tr("Could not securely save the key. Try again.")
                            input.isEnabled = true
                            checking = false
                        }
                    }.onFailure {
                        status.text = ElevenLabs.message(it)
                        input.isEnabled = true
                        checking = false
                    }
                }
            }
        }
        panel.addView(connect, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        panel.addView(secondary(tr("How to get an API key ↗")) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://elevenlabs.io/docs/help-center/technical/how-do-i-authorize-myself-using-an-api-key")))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        panel.addView(secondary(tr("Cancel")) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        dialog.setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        })
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply { dimAmount = 0.55f; gravity = Gravity.CENTER }
            setLayout(resources.displayMetrics.widthPixels - dp(24), -2)
        }
    }

    private fun chooseElevenVoice(language: String) {
        val key = ElevenLabsKey(this).read()
        if (key == null) { enterElevenKey(); return }
        toast(tr("Loading ElevenLabs voices…"))
        worker.execute {
            val result = runCatching { ElevenLabs(this).voices(key) }
            main.post {
                if (store.narrationSource != "elevenlabs" || (!inSettings && selectedId == null)) return@post
                result.onSuccess { voices ->
                    if (voices.isEmpty()) { toast(tr("No voices found in this ElevenLabs account.")); return@onSuccess }
                    val sorted = voices.sortedWith(compareByDescending<ElevenVoice> { language in it.languages }
                        .thenBy { it.name.lowercase() })
                    val selected = sorted.indexOfFirst { it.id == store.elevenVoiceId(language) }
                    choiceSheet(tr("{0} · ElevenLabs", languageName(language)), sorted.map {
                        val details = listOfNotNull(
                            if (language in it.languages) tr("Verified for {0}", languageName(language)) else null,
                            it.detail.takeIf(String::isNotBlank), tr("Tap to preview · uses credits"))
                        MenuChoice(it.name, details.joinToString(" · "))
                    }, selected, tr("Use this voice"),
                        onPreview = { previewElevenVoice(key, sorted[it], language) },
                        onDismiss = { stopVoicePreview() }) { index ->
                        pauseOfflineDownload()
                        store.setElevenVoice(language, sorted[index].id, sorted[index].name)
                        command("VOICE_CHANGED")
                        if (inSettings) showSettings()
                        toast(tr("{0} saved for {1}", sorted[index].name, languageName(language)))
                    }
                }.onFailure { toast(ElevenLabs.message(it)) }
            }
        }
    }

    private fun previewElevenVoice(key: String, voice: ElevenVoice, language: String) {
        stopVoicePreview()
        val token = previewCloudGeneration
        if (playing) command("PAUSE")
        if (!previewAudioFocus.acquire()) { toast(tr("Audio is in use by another app. Try again shortly.")); return }
        toast(tr("Preparing {0} sample…", voice.name))
        worker.execute {
            if (token != previewCloudGeneration) return@execute
            val result = runCatching {
                ElevenLabs(this).audio(key, voice.id, NarrationVoices.sample(language), store.speechRate)
            }
            main.post {
                if (token != previewCloudGeneration) return@post
                result.onSuccess { file -> runCatching {
                    previewCloudPlayer = MediaPlayer().apply {
                        setAudioAttributes(PlaybackAudioFocus.attributes)
                        setDataSource(file.path)
                        setOnPreparedListener { if (token == previewCloudGeneration && previewAudioFocus.hasFocus) it.start() }
                        setOnCompletionListener { if (token == previewCloudGeneration) stopVoicePreview() }
                        setOnErrorListener { _, _, _ ->
                            if (token == previewCloudGeneration) stopVoicePreview()
                            true
                        }
                        prepareAsync()
                    }
                }.onFailure { stopVoicePreview(); toast(tr("Could not play voice preview")) } }
                    .onFailure { stopVoicePreview(); toast(ElevenLabs.message(it)) }
            }
        }
    }

    private fun finishPreview(id: String?) {
        if (id != null && id == previewUtterance) {
            previewUtterance = null
            previewAudioFocus.release()
        }
    }

    private fun stopVoicePreview() {
        previewUtterance = null
        previewTts?.stop()
        previewCloudGeneration++
        previewCloudPlayer?.apply {
            setOnPreparedListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            release()
        }
        previewCloudPlayer = null
        previewAudioFocus.release()
    }

    private fun removeElevenKey() {
        choiceSheet(tr("Remove ElevenLabs data?"), listOf(
            MenuChoice(tr("Remove key and saved audio"), tr("Device voices will become the narration source"))
        )) {
            stopVoicePreview()
            pauseOfflineDownload()
            ElevenLabsKey(this).clear()
            ElevenLabs(this).clearCache()
            store.narrationSource = "device"
            command("SOURCE_CHANGED")
            showSettings()
        }
    }

    // ---- Your narration server (tts-server/app.py on Modal) -------------------------------

    private fun showServerSettings(card: LinearLayout) {
        val config = ServerCredentials(this).read()
        settingRow(card, tr("Server"), config?.host ?: tr("Not connected · tap to connect")) { enterServerConfig() }
        if (config != null) {
            settingRow(card, tr("Language"), languageName(settingsLanguage)) { chooseSettingsLanguage() }
            settingRow(card, tr("Voice"), NarrationServer.voiceLabel(store.serverVoice(settingsLanguage))) {
                chooseServerVoice(settingsLanguage)
            }
            switchRow(card, tr("Auto-switch to device voice"), tr("Pages without saved audio are read by your device voice"),
                store.serverDeviceFallback) {
                store.serverDeviceFallback = it
                command("VOICE_CHANGED")
            }
            settingRow(card, tr("Remove server and saved audio"), chevron = false) { removeServer() }
        }
        settingNote(card, tr("Book text is sent to your Modal server; the audio is saved here for offline listening."))
    }

    private fun enterServerConfig() {
        val existing = ServerCredentials(this).read()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            background = rounded(surface, 22)
        }
        panel.addView(text(tr("Connect your server"), 24f).apply { typeface = Typeface.SERIF })
        panel.addView(text(tr("Paste the address printed by `modal deploy tts-server/app.py` (it ends in .modal.run) and a proxy auth token from Modal → Settings → Proxy Auth Tokens. Everything is encrypted on this device."), 13f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        fun field(hint: String, value: String, secret: Boolean) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(ink); setHintTextColor(muted)
            textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surfaceRaised, 12)
        }
        val url = field("https://…-foliotone-tts-api.modal.run", existing?.url.orEmpty(), false)
        val tokenId = field(tr("Token ID (wk-…)"), existing?.tokenId.orEmpty(), false)
        val tokenSecret = field(tr("Token secret (ws-…)"), existing?.tokenSecret.orEmpty(), true)
        panel.addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        panel.addView(tokenId, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        panel.addView(tokenSecret, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        val status = text("", 13f, muted)
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        var checking = false
        panel.addView(button(tr("Save and connect")) {
            if (checking) return@button
            val config = runCatching {
                ServerConfig(NarrationServer.normalizeUrl(url.text.toString()),
                    tokenId.text.toString().trim(), tokenSecret.text.toString().trim())
            }.getOrElse { status.text = it.message; return@button }
            if (config.tokenId.isBlank() != config.tokenSecret.isBlank()) {
                status.text = tr("Enter both the token ID and the token secret.")
                return@button
            }
            checking = true
            status.text = tr("Checking the server…")
            listOf(url, tokenId, tokenSecret).forEach { it.isEnabled = false }
            worker.execute {
                val result = runCatching { NarrationServer(this, config).health() }
                main.post {
                    if (!dialog.isShowing) return@post
                    result.onSuccess { info ->
                        runCatching {
                            if (existing != null && existing.url != config.url) pauseOfflineDownload()
                            ServerCredentials(this).save(config)
                            store.saveServerInfo(info)
                            if (store.narrationSource != "server") pauseOfflineDownload()
                            store.narrationSource = "server"
                        }.onSuccess {
                            dialog.dismiss()
                            command("SOURCE_CHANGED")
                            showSettings()
                            toast(tr("Server connected · {0} voices", info.voices.size))
                        }.onFailure {
                            status.text = tr("Could not securely save the settings. Try again.")
                            listOf(url, tokenId, tokenSecret).forEach { it.isEnabled = true }
                            checking = false
                        }
                    }.onFailure {
                        status.text = NarrationServer.message(it)
                        listOf(url, tokenId, tokenSecret).forEach { it.isEnabled = true }
                        checking = false
                    }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        panel.addView(secondary(tr("Cancel")) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        dialog.setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        })
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            attributes = attributes.apply { dimAmount = 0.55f; gravity = Gravity.CENTER }
            setLayout(resources.displayMetrics.widthPixels - dp(24), -2)
        }
    }

    private fun chooseServerVoice(language: String) {
        val config = ServerCredentials(this).read()
        if (config == null) { enterServerConfig(); return }
        val voices = NarrationServer.rankVoices(store.serverVoices().ifEmpty {
            listOf("neutral_female", "neutral_male", "casual_female", "casual_male", "cheerful_female",
                "it_female", "it_male", "fr_female", "fr_male", "de_female", "de_male", "es_female", "es_male")
        }, language)
        val current = voices.indexOf(store.serverVoice(language))
        choiceSheet(tr("{0} · your server", languageName(language)), voices.map { voice ->
            val native = voice.startsWith("${language}_")
            MenuChoice(NarrationServer.voiceLabel(voice),
                listOfNotNull(if (native) tr("Native {0} voice", languageName(language)) else null, tr("Tap to preview"))
                    .joinToString(" · "))
        }, current, tr("Use this voice"),
            onPreview = { previewServerVoice(config, voices[it], language) },
            onDismiss = { stopVoicePreview() }) { index ->
            if (voices[index] != store.serverVoice(language)) {
                OfflineDownloadService.activeBookId?.let { active ->
                    if (store.books().firstOrNull { it.id == active }?.language == language) pauseOfflineDownload()
                }
            }
            store.setServerVoice(language, voices[index])
            command("VOICE_CHANGED")
            if (inSettings) showSettings() else if (selectedId != null) showReader()
            toast(tr("{0} saved for {1}. Books in this language need audio for the new voice.", NarrationServer.voiceLabel(voices[index]), languageName(language)))
        }
    }

    private fun previewServerVoice(config: ServerConfig, voice: String, language: String) {
        stopVoicePreview()
        val token = previewCloudGeneration
        if (playing) command("PAUSE")
        if (!previewAudioFocus.acquire()) { toast(tr("Audio is in use by another app. Try again shortly.")); return }
        worker.execute {
            if (token != previewCloudGeneration) return@execute
            val result = runCatching { NarrationServer(this, config).sample(voice, language) }
            main.post {
                if (token != previewCloudGeneration) return@post
                result.onSuccess { file -> runCatching {
                    previewCloudPlayer = MediaPlayer().apply {
                        setAudioAttributes(PlaybackAudioFocus.attributes)
                        setDataSource(file.path)
                        setOnPreparedListener {
                            if (token == previewCloudGeneration && previewAudioFocus.hasFocus) {
                                it.start()
                                if (store.speechRate != 1f) runCatching {
                                    it.playbackParams = it.playbackParams.setSpeed(store.speechRate)
                                }
                            }
                        }
                        setOnCompletionListener { if (token == previewCloudGeneration) stopVoicePreview() }
                        setOnErrorListener { _, _, _ ->
                            if (token == previewCloudGeneration) stopVoicePreview()
                            true
                        }
                        prepareAsync()
                    }
                }.onFailure { stopVoicePreview(); toast(tr("Could not play voice preview")) } }
                    .onFailure { stopVoicePreview(); toast(NarrationServer.message(it)) }
            }
        }
    }

    private fun chooseAppLanguage() {
        val codes = listOf(I18n.SYSTEM) + I18n.LANGUAGES.map { it.first }
        val choices = listOf(MenuChoice(tr("System default"), tr("Follow the phone's language"))) +
            I18n.LANGUAGES.map { MenuChoice(it.second) }
        choiceSheet(tr("App language"), choices, codes.indexOf(store.appLanguage).coerceAtLeast(0)) { index ->
            store.appLanguage = codes[index]
            I18n.apply(this, codes[index])
            command("GET_STATE")   // refreshes the media notification text
            showSettings()
        }
    }

    private fun deleteAllNarration() {
        worker.execute {
            val bytes = runCatching { NarrationLibrary.bytesUsed(this) }.getOrDefault(0L)
            main.post {
                if (bytes == 0L) { toast(tr("No narration is saved on this device.")); return@post }
                choiceSheet(tr("Delete all downloaded narration?"), listOf(
                    MenuChoice(tr("Delete {0} MB of narration", "%.1f".format(bytes / 1_048_576.0)),
                        tr("Removes every saved server and ElevenLabs page from this device. Books, progress, voices and keys stay. Downloading again regenerates the audio."))
                )) {
                    stopVoicePreview()
                    pauseOfflineDownload()
                    if (playing && store.narrationSource != "device") command("PAUSE")
                    val config = ServerCredentials(this).read()
                    val jobs = store.serverJobs()
                    worker.execute {
                        if (config != null) jobs.forEach { (_, job) -> runCatching { NarrationServer(this, config).delete(job) } }
                        val result = runCatching { NarrationLibrary.deleteAll(this) }
                        main.post {
                            result.onSuccess { toast(tr("All downloaded narration deleted")) }
                                .onFailure { toast(it.message ?: tr("Could not delete all narration")) }
                            if (inSettings) showSettings()
                        }
                    }
                }
            }
        }
    }

    private fun removeServer() {
        choiceSheet(tr("Remove server data?"), listOf(
            MenuChoice(tr("Remove server and saved audio"), tr("Device voices will become the narration source"))
        )) {
            stopVoicePreview()
            pauseOfflineDownload()
            val config = ServerCredentials(this).read()
            val jobs = store.serverJobs()
            if (config != null && jobs.isNotEmpty()) worker.execute {
                jobs.forEach { (_, job) -> runCatching { NarrationServer(this, config).delete(job) } }
            }
            ServerCredentials(this).clear()
            store.clearServerSettings()
            worker.execute { runCatching { ServerAudio.clearAll(this) } }
            store.narrationSource = "device"
            command("SOURCE_CHANGED")
            showSettings()
        }
    }

    private fun refreshServerStatus(book: Book, parsed: ParsedBook, status: TextView) {
        if (ServerCredentials(this).read() == null) { status.text = tr("Connect your narration server to save this book"); return }
        val voice = store.serverVoice(NarrationVoices.locale(book.language).language)
        worker.execute {
            val ahead = downloadRanges(book, parsed).firstOrNull { it.to == parsed.sections.sumOf { s -> s.pages.size } }
            val count = runCatching {
                val pages = parsed.sections.flatMap { it.pages }
                val present = BooleanArray(pages.size) { ServerAudio.isSaved(this, book.id, ServerAudio.key(voice, pages[it])) }
                store.setNarrationStatus("server", book.id, NarrationStatus(voice, present.count { it }, pages.size))
                val aheadSaved = ahead != null && (ahead.from until ahead.to).all { present[it] }
                Triple(present.count { it } to pages.size, ServerAudio.hasAny(this, book.id), aheadSaved)
            }
            main.post {
                if (offlineStatusView !== status || selectedId != book.id ||
                    OfflineDownloadService.activeBookId == book.id) return@post
                status.text = count.fold({ (progress, hasAny, aheadSaved) ->
                    val (saved, total) = progress
                    when {
                        saved == total -> tr("All {0} pages saved · ready to listen offline", total)
                        aheadSaved -> tr("Saved from your current page to the end · {0} of {1} pages in the book", saved, total)
                        store.serverJob(book.id) != null -> tr("{0} of {1} pages saved · your server may still be generating; resume the download", saved, total)
                        else -> tr("{0} of {1} pages saved for offline listening", saved, total) +
                            if (saved == 0 && hasAny) tr(" · audio for another voice is stored") else ""
                    }
                }, { tr("Could not check saved audio") })
            }
        }
    }

    private fun manageServerAudio(book: Book, parsed: ParsedBook) {
        val config = ServerCredentials(this).read()
        if (config == null) { enterServerConfig(); return }
        val voice = store.serverVoice(NarrationVoices.locale(book.language).language)
        val total = parsed.sections.sumOf { it.pages.size }
        val resume = store.serverJob(book.id)?.takeIf { it.second == voice }
            ?.let { store.serverRange(book.id) ?: (0 to total) }
        offerDownloads(book, parsed, "server", resume,
            cost = { _, chars -> tr("{0} · roughly {1} min on your server, ", NarrationServer.voiceLabel(voice), chars / 240 / 60 + 1) +
                tr("about {0} MB", "%.0f".format(chars / 1000.0 * 0.4)) },
            saved = { pages -> BooleanArray(pages.size) { ServerAudio.isSaved(this, book.id, ServerAudio.key(voice, pages[it])) } },
            hasAny = { ServerAudio.hasAny(this, book.id) }) {
            if (playing) command("PAUSE")
            offlineStatusView?.text = tr("Removing saved audio…")
            val job = store.serverJob(book.id)
            store.clearServerJob(book.id)
            worker.execute {
                if (job != null) runCatching { NarrationServer(this, config).delete(job.first) }
                val removed = runCatching { ServerAudio.remove(this, book.id) }
                store.setNarrationStatus("server", book.id, null)
                main.post {
                    removed.onFailure { toast(it.message ?: tr("Could not remove saved audio")) }
                    if (selectedId == book.id) offlineStatusView?.let { refreshOfflineStatus(book, parsed, it) }
                }
            }
        }
    }

    private data class DownloadRange(val title: String, val detail: String, val from: Int, val to: Int)

    /** Parts of the book worth offering: from the reader's current page, the rest of its section, the whole book. */
    private fun downloadRanges(book: Book, parsed: ParsedBook): List<DownloadRange> {
        val sections = parsed.sections
        val total = sections.sumOf { it.pages.size }
        if (sections.isEmpty() || total == 0) return emptyList()
        val section = (sessionSection ?: book.section).coerceIn(sections.indices)
        val page = (sessionPage ?: book.page).coerceIn(0, (sections[section].pages.size - 1).coerceAtLeast(0))
        val start = sections.take(section).sumOf { it.pages.size } + page
        val sectionEnd = sections.take(section + 1).sumOf { it.pages.size }
        val here = tr("Section {0}, page {1} onward", section + 1, page + 1)
        return listOfNotNull(
            if (start > 0) DownloadRange(tr("from your current page to the end"), here, start, total) else null,
            if (sectionEnd < total) DownloadRange(tr("rest of this section"),
                tr("{0}, to the end of “{1}”", here, sections[section].title), start, sectionEnd) else null,
            DownloadRange(tr("whole book"), tr("From the beginning"), 0, total)
        )
    }

    /**
     * Shared download sheet for ElevenLabs and the narration server. It offers each part of the book
     * that still needs audio for the current voice, so a reader halfway through a book can switch
     * voice or source and only generate what is left.
     */
    private fun offerDownloads(
        book: Book, parsed: ParsedBook, source: String, resume: Pair<Int, Int>?,
        cost: (pages: Int, chars: Int) -> String,
        saved: (List<String>) -> BooleanArray,
        hasAny: () -> Boolean,
        remove: () -> Unit
    ) {
        val ranges = downloadRanges(book, parsed)
        worker.execute {
            val result = runCatching {
                val pages = parsed.sections.flatMap { it.pages }
                val present = saved(pages)
                fun need(from: Int, to: Int): Pair<Int, Int> {
                    val missing = (from.coerceIn(0, pages.size) until to.coerceIn(0, pages.size)).filter { !present[it] }
                    return missing.size to missing.sumOf { pages[it].length }
                }
                Triple(ranges.map { it to need(it.from, it.to) },
                    resume?.let { it to need(it.first, it.second) }, hasAny())
            }
            main.post {
                if (selectedId != book.id) return@post
                result.onSuccess { (needs, resumeNeed, any) ->
                    fun start(from: Int, to: Int) {
                        runCatching {
                            startForegroundService(Intent(this, OfflineDownloadService::class.java)
                                .setAction("DOWNLOAD").putExtra("id", book.id).putExtra("source", source)
                                .putExtra("from", from).putExtra("to", to))
                        }.onFailure { toast(tr("Could not start download: {0}", it.message)) }
                    }
                    val actions = mutableListOf<Pair<MenuChoice, () -> Unit>>()
                    if (resumeNeed != null && resumeNeed.second.first > 0) {
                        val (range, need) = resumeNeed
                        actions += MenuChoice(tr("Resume download"),
                            tr("{0} pages left · {1}", need.first, cost(need.first, need.second))) to { start(range.first, range.second) }
                    }
                    needs.forEach { (range, need) ->
                        if (need.first > 0) actions += MenuChoice(tr("Download {0}", range.title),
                            tr("{0} · {1} pages · {2}", range.detail, need.first, cost(need.first, need.second))) to
                            { start(range.from, range.to) }
                    }
                    if (any) actions += MenuChoice(tr("Remove offline download"),
                        tr("Free space used by this book's saved audio")) to remove
                    if (actions.isEmpty()) { toast(tr("Every page is saved for this voice.")); return@onSuccess }
                    choiceSheet(tr("Offline audio · {0}", book.title), actions.map { it.first }) { actions[it].second() }
                }.onFailure { toast(tr("Could not check saved audio: {0}", it.message)) }
            }
        }
    }

    private fun chooseEngine() {
        val tts = previewTts
        if (!previewReady || tts == null) { toast(tr("Speech providers are loading. Try again in a moment.")); return }
        val engines = tts.engines.orEmpty()
        val choices = listOf(MenuChoice(tr("System default"), tr("Use Android's selected speech provider"))) +
            engines.map { MenuChoice(it.label?.toString().orEmpty().ifBlank { it.name }, it.name) }
        val current = engines.indexOfFirst { it.name == store.speechEngine }
        choiceSheet(tr("Speech provider"), choices, current + 1) { index ->
            store.speechEngine = if (index == 0) null else engines[index - 1].name
            startPreviewTts()
            command("ENGINE_CHANGED")
            showSettings()
        }
    }

    private fun chooseSettingsLanguage() {
        val codes = listOf("en", "it", "fr", "de", "es")
        choiceSheet(tr("Preview language"), codes.map { MenuChoice(languageName(it)) },
            codes.indexOf(settingsLanguage)) { index ->
            settingsLanguage = codes[index]
            showSettings()
        }
    }

    private fun openBook(book: Book, fromMedia: Boolean = false) {
        playbackButton = null
        browsingRatings = false
        browsingGutenberg = false
        gutenbergGeneration++
        gutenbergStatus = null
        gutenbergDownloadStatus = null
        gutenbergHomeStatus = null
        gutenbergList = null
        gutenbergRenderedActiveId = null
        selectedId = book.id
        cachedBookId = null; cachedBook = null
        sessionSection = null; sessionPage = null
        opening = true
        content.removeAllViews()
        addText(tr("Opening {0}…", book.title), 20f)
        worker.execute {
            val result = runCatching { Epub.parse(store.file(book)) }
            main.post {
                if (selectedId != book.id) return@post
                opening = false
                result.onSuccess { parsed ->
                    store.lastOpenedId = book.id
                    cachedBookId = book.id; cachedBook = parsed
                    // `book` may come from a screen drawn before narration moved on: never write it back.
                    val fresh = (if (fromMedia) store.books().firstOrNull { it.id == book.id }
                        else store.edit(book.id) {
                            it.sectionCount = parsed.sections.size
                            it.pagesInSection = parsed.sections.getOrNull(it.section)?.pages?.size ?: 0
                            it.sectionPages = parsed.sections.map { section -> section.pages.size }
                        }) ?: book
                    // Reopening the book that is playing keeps its place instead of rewinding and stopping.
                    val alreadyPlaying = playing && serviceBookId == book.id
                    command(if (fromMedia || alreadyPlaying) "OPEN_CURRENT" else "OPEN", "id" to book.id)
                    showReader()
                    if (fresh.language.isBlank()) chooseLanguage(fresh, true)
                }.onFailure { toast(tr("Cannot read this EPUB: {0}", it.message)); selectedId = null; showHome() }
            }
        }
    }

    private fun showReader() {
        inSettings = false
        offlineStatusView = null
        val book = store.books().firstOrNull { it.id == selectedId } ?: return
        val parsed = runCatching {
            if (cachedBookId == book.id) cachedBook ?: Epub.parse(store.file(book))
            else Epub.parse(store.file(book)).also { cachedBookId = book.id; cachedBook = it }
        }.getOrElse {
            toast(tr("Cannot read this EPUB: {0}", it.message)); selectedId = null; showHome(); return
        }
        val sectionIndex = (sessionSection ?: book.section).coerceIn(parsed.sections.indices)
        val section = parsed.sections[sectionIndex]
        val pageIndex = (sessionPage ?: book.page).coerceIn(section.pages.indices)
        val bookProgress = progress(book.copy(
            section = sectionIndex,
            page = pageIndex,
            sectionCount = parsed.sections.size,
            pagesInSection = section.pages.size,
            sectionPages = parsed.sections.map { it.pages.size }
        ))
        content.removeAllViews()
        content.addView(rowOf(compact(secondary(tr("←  Library")) { selectedId = null; showHome() }, 38),
            compact(secondary(tr("Themes")) { chooseTheme() }, 38)))
        gap(12)
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(poster(book, 58, 84))
        val headingText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(book.title, 19f).apply {
                typeface = Typeface.SERIF; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (book.author.isNotBlank()) addView(text(book.author, 12.5f, muted).apply { maxLines = 1 },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            fun chip(label: String, action: () -> Unit) = secondary(label, action).apply {
                textSize = 12f; minHeight = dp(30); setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            val chips = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            chips.addView(chip(store.rating(book.id)?.let { tr("★ {0} / 5.0", ratingScore(it)) } ?: tr("☆  Rate")) {
                rateBook(book)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            chips.addView(chip(if (store.isRead(book.id)) tr("✓ Read") else tr("Mark as read")) {
                store.setRead(book.id, !store.isRead(book.id))
                showReader()
            }, LinearLayout.LayoutParams(-2, -2))
            addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        heading.addView(headingText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        content.addView(heading)
        gap(12)
        val chapter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(6))
            background = rounded(surface, 16)
        }
        val where = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        where.addView(text(tr("SECTION {0} OF {1}", sectionIndex + 1, parsed.sections.size) + "  ·  " +
            tr("Page {0} of {1}", pageIndex + 1, section.pages.size).uppercase(), 11f, accent),
            LinearLayout.LayoutParams(0, -2, 1f))
        where.addView(text(tr("{0}% of book", bookProgress), 11f, muted))
        chapter.addView(where)
        chapter.addView(text(section.title, 18f).apply {
            typeface = Typeface.SERIF; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val seek = SeekBar(this).apply {
            max = section.pages.lastIndex
            progress = pageIndex
            contentDescription = tr("Position in section")
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar?) { busy = true }
                override fun onStopTrackingTouch(bar: SeekBar?) { busy = false; command("SEEK", "page" to (bar?.progress ?: 0)) }
            })
        }
        chapter.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(chapter)
        gap(10)
        val listen = compact(button(playbackLabel()) { command(if (playing) "PAUSE" else "PLAY") }, 44)
        playbackButton = listen
        content.addView(rowOf(compact(secondary(tr("◀  Back")) { command("PREVIOUS") }, 44),
            listen,
            compact(secondary(tr("Next  ▶")) { command("NEXT") }, 44)))
        gap(6)
        val contents = compact(secondary(tr("Contents")) { showContents(book, parsed, sectionIndex, pageIndex) }, 38)
        val options = compact(secondary(tr("Voice & options")) { readerOptions(book) }, 38)
        content.addView(if (store.replayUseful(this))
            rowOf(compact(secondary(tr("↺ Replay")) { command("REPLAY") }, 38), contents, options)
            else rowOf(contents, options))
        if (store.narrationSource == "elevenlabs" || store.narrationSource == "server") showOfflineAudio(book, parsed)
        gap(16)
        addText(tr("READING PAGE"), 11f, accent).typeface = Typeface.DEFAULT_BOLD
        gap(8)
        content.addView(text(section.pages[pageIndex], 19f, palette.paperInk).apply {
            typeface = Typeface.SERIF
            setLineSpacing(dp(7).toFloat(), 1.06f)
            setPadding(dp(16), dp(18), dp(16), dp(20))
            background = rounded(palette.paper, 16)
        })
    }

    private fun playbackLabel() = if (playing) tr("Ⅱ  Pause") else tr("▶  Listen")

    private fun showOfflineAudio(book: Book, parsed: ParsedBook) {
        gap(10)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surface, 16)
        }
        val language = NarrationVoices.locale(book.language).language
        val voice = if (store.narrationSource == "server") NarrationServer.voiceLabel(store.serverVoice(language))
            else store.elevenVoiceName(language)
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(text(tr("OFFLINE LISTENING"), 11f, accent).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (voice != null) title.addView(text(voice, 11f, muted).apply { maxLines = 1 },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        card.addView(title)
        val status = text(if (OfflineDownloadService.activeBookId == book.id)
            OfflineDownloadService.progressText ?: tr("Saving book audio…")
            else tr("Checking saved audio…"), 13f, ink)
        offlineStatusView = status
        card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        card.addView(rowOf(compact(secondary(tr("Manage offline audio")) { manageOfflineAudio(book, parsed) }, 38),
            compact(secondary(tr("Regenerate this page")) { regenerateCurrentPage(book) }, 38)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(card)
        refreshOfflineStatus(book, parsed, status)
    }

    private fun pauseOfflineDownload() {
        OfflineDownloadService.activeBookId?.let { id ->
            startService(Intent(this, OfflineDownloadService::class.java)
                .setAction("CANCEL").putExtra("id", id))
        }
    }

    private fun refreshOfflineStatus(book: Book, parsed: ParsedBook, status: TextView) {
        if (store.narrationSource == "server") { refreshServerStatus(book, parsed, status); return }
        val key = ElevenLabsKey(this).read()
        if (key == null) { status.text = tr("Connect ElevenLabs to save this book"); return }
        val voice = store.elevenVoiceId(NarrationVoices.locale(book.language).language)
        if (voice == null) { status.text = tr("Choose an ElevenLabs voice for this book"); return }
        val rate = store.speechRate
        worker.execute {
            val count = runCatching {
                ElevenLabs(this).let { eleven ->
                    eleven.savedPages(book.id, parsed.sections, key, voice, rate).also { (saved, total) ->
                        store.setNarrationStatus("elevenlabs", book.id, NarrationStatus("$voice@$rate", saved, total))
                    } to eleven.hasBookDownload(book.id)
                }
            }
            main.post {
                if (offlineStatusView !== status || selectedId != book.id ||
                    OfflineDownloadService.activeBookId == book.id) return@post
                status.text = count.fold({ (progress, hasAny) ->
                    val (saved, total) = progress
                    tr("{0} of {1} pages saved for offline listening", saved, total) +
                        if (saved == 0 && hasAny) tr(" · earlier voice or speed audio is stored") else ""
                },
                    { tr("Could not check saved audio") })
            }
        }
    }

    private fun manageOfflineAudio(book: Book, parsed: ParsedBook) {
        if (OfflineDownloadService.activeBookId == book.id) {
            choiceSheet(tr("Offline download"), listOf(
                MenuChoice(tr("Pause download"), tr("Pages already saved will remain on this device"))
            )) {
                startService(Intent(this, OfflineDownloadService::class.java).setAction("CANCEL")
                    .putExtra("id", book.id))
            }
            return
        }
        if (OfflineDownloadService.activeBookId != null) {
            toast(tr("Another book is downloading. Pause it before starting this one."))
            return
        }
        if (store.narrationSource == "server") { manageServerAudio(book, parsed); return }
        val key = ElevenLabsKey(this).read()
        val voice = store.elevenVoiceId(NarrationVoices.locale(book.language).language)
        if (key == null || voice == null) {
            toast(tr("Connect ElevenLabs and choose a voice for this book first."))
            return
        }
        val rate = store.speechRate
        offerDownloads(book, parsed, "elevenlabs", null,
            cost = { _, chars -> tr("about {0} ElevenLabs credits", "%,d".format(chars)) },
            saved = { pages ->
                val eleven = ElevenLabs(this)
                BooleanArray(pages.size) {
                    eleven.isPageSaved(book.id, key, voice, pages[it], rate, pages.getOrNull(it - 1), pages.getOrNull(it + 1))
                }
            },
            hasAny = { ElevenLabs(this).hasBookDownload(book.id) }) {
            if (playing) command("PAUSE")
            offlineStatusView?.text = tr("Removing saved audio…")
            worker.execute {
                val removed = runCatching { ElevenLabs(this).removeBookDownload(book.id) }
                store.setNarrationStatus("elevenlabs", book.id, null)
                main.post {
                    removed.onFailure { toast(it.message ?: tr("Could not remove offline audio")) }
                    if (selectedId == book.id) offlineStatusView?.let { refreshOfflineStatus(book, parsed, it) }
                }
            }
        }
    }

    private fun showContents(book: Book, parsed: ParsedBook, current: Int, currentPage: Int) {
        val sections = parsed.sections
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 24)
            setPadding(dp(20), dp(18), dp(20), dp(20))
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { view, insets ->
                val bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom
                view.setPadding(dp(20), dp(18), dp(20), dp(20) + bottom)
                insets
            }
        }
        panel.addView(View(this).apply { background = rounded(muted, 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18)
            })
        panel.addView(text(tr("Contents"), 27f).apply { typeface = Typeface.SERIF })
        panel.addView(text(tr("{0}  ·  {1} sections", book.title, sections.size), 13f, muted).apply {
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val search = EditText(this).apply {
            hint = tr("Find a section")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextColor(ink); setHintTextColor(muted)
            textSize = 15f
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = rounded(surfaceRaised, 12)
            contentDescription = tr("Search book sections")
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        val matches = sections.indices.toMutableList()
        val list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = matches.size
            override fun getItem(position: Int): Any = matches[position]
            override fun getItemId(position: Int) = matches[position].toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val index = matches[position]
                val section = sections[index]
                val active = index == current
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(76)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(surfaceRaised, 14).apply {
                        if (active) setStroke(dp(1), accent)
                    }
                    contentDescription = tr("Section {0} of {1}, {2}, ", index + 1, sections.size, section.title) +
                        tr("{0} pages{1}", section.pages.size, if (active) tr(", currently reading") else "")
                }
                row.addView(text("%02d".format(index + 1), 13f, accent).apply {
                    typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(dp(35), -2))
                val words = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                words.addView(text(section.title, 17f).apply {
                    typeface = Typeface.SERIF
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val detail = if (active) tr("Now reading  ·  Page {0} of {1}", currentPage + 1, section.pages.size)
                    else "${section.pages.size} ${if (section.pages.size == 1) "page" else "pages"}" +
                        if (section.title.matches(Regex("Section \\d+"))) {
                            "  ·  ${section.pages.first().replace(Regex("\\s+"), " ").take(72)}"
                        } else ""
                words.addView(text(detail, 12f, if (active) accent else muted).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text(if (active) "●" else "›", if (active) 12f else 22f, accent),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
                return row
            }
        }
        list.adapter = adapter
        val empty = text(tr("No matching sections"), 14f, muted).apply { gravity = Gravity.CENTER }
        val listFrame = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -1))
        }
        list.emptyView = empty
        panel.addView(listFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        list.setOnItemClickListener { _, _, position, _ ->
            val selected = matches[position]
            dialog.dismiss()
            if (selected != current) command("SECTION_JUMP", "id" to book.id, "section" to selected)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                matches.clear()
                matches.addAll(sections.indices.filter { index ->
                    query.isEmpty() || (index + 1).toString() == query ||
                        sections[index].title.contains(query, ignoreCase = true)
                })
                adapter.notifyDataSetChanged()
                list.setSelection(0)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun shortcut(label: String, enabled: Boolean, action: String): TextView = secondary(label) {
            dialog.dismiss(); command(action)
        }.apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.4f }
        shortcuts.addView(shortcut(tr("◀ Previous"), current > 0, "SECTION_PREVIOUS"),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        shortcuts.addView(shortcut(tr("Restart"), true, "SECTION_START"),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        shortcuts.addView(shortcut(tr("Next ▶"), current < sections.lastIndex, "SECTION_NEXT"),
            LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(shortcuts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.55f; gravity = Gravity.BOTTOM }
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(-1, (resources.displayMetrics.heightPixels * 0.88f).toInt())
        }
        panel.requestFocus()
        list.post { list.setSelection(current) }
    }

    private fun readerOptions(book: Book) {
        val cloud = store.narrationSource == "server" || store.narrationSource == "elevenlabs"
        val actions = mutableListOf<Pair<MenuChoice, () -> Unit>>(
            MenuChoice(tr("Stop narration")) to { command("STOP") },
            MenuChoice(tr("Book language"), languageName(book.language)) to { chooseLanguage(book, false) },
            MenuChoice(tr("Choose voice"), tr("Preview voices for this book")) to {
                when (store.narrationSource) {
                    "elevenlabs" -> chooseElevenVoice(book.language.ifBlank { "en" })
                    "server" -> chooseServerVoice(book.language.ifBlank { "en" })
                    else -> chooseVoice(book.language.ifBlank { "en" })
                }
            }
        )
        if (cloud) actions += MenuChoice(tr("Regenerate this page"),
            tr("Create this page's audio again if it sounds wrong or will not play")) to { regenerateCurrentPage(book) }
        actions += MenuChoice(tr("Listening settings"), tr("Provider, speed and resume")) to {
            settingsLanguage = book.language.ifBlank { "en" }; showSettings()
        }
        choiceSheet(tr("Listening options"), actions.map { it.first }) { actions[it].second() }
    }

    private fun regenerateCurrentPage(book: Book) {
        val parsed = cachedBook?.takeIf { cachedBookId == book.id }
        if (parsed == null) { toast(tr("Open the book again, then try once more.")); return }
        if (OfflineDownloadService.activeBookId != null) {
            toast(tr("A download is running. Pause it in Manage offline audio first."))
            return
        }
        val source = store.narrationSource
        if (source == "server" && ServerCredentials(this).read() == null) { enterServerConfig(); return }
        if (source == "elevenlabs" && (ElevenLabsKey(this).read() == null ||
                store.elevenVoiceId(NarrationVoices.locale(book.language).language) == null)) {
            toast(tr("Connect ElevenLabs and choose a voice for this book first."))
            return
        }
        val sections = parsed.sections
        val section = (sessionSection ?: book.section).coerceIn(sections.indices)
        val page = (sessionPage ?: book.page).coerceIn(sections[section].pages.indices)
        val index = sections.take(section).sumOf { it.pages.size } + page
        val chars = sections[section].pages[page].length
        val detail = if (source == "server")
            tr("Your server reads it again with {0}. If the server is not running, starting it takes a few minutes.", NarrationServer.voiceLabel(store.serverVoice(NarrationVoices.locale(book.language).language)))
            else tr("Uses about {0} ElevenLabs credits.", "%,d".format(chars))
        choiceSheet(tr("Regenerate this page?"), listOf(
            MenuChoice(tr("Regenerate section {0}, page {1}", section + 1, page + 1), detail)
        )) {
            if (playing) command("PAUSE")
            runCatching {
                startForegroundService(Intent(this, OfflineDownloadService::class.java)
                    .setAction("DOWNLOAD").putExtra("id", book.id).putExtra("source", source)
                    .putExtra("from", index).putExtra("to", index + 1).putExtra("regenerate", true))
            }.onSuccess { toast(tr("Regenerating this page… Press Listen when it is ready.")) }
                .onFailure { toast(tr("Could not start: {0}", it.message)) }
        }
    }

    private fun chooseLanguage(book: Book, required: Boolean) {
        val codes = listOf("en", "it", "fr", "de", "es")
        choiceSheet(tr("Book language"), codes.map { MenuChoice(languageName(it)) },
            codes.indexOf(book.language)) { index ->
            if (OfflineDownloadService.activeBookId == book.id) pauseOfflineDownload()
            command("LANGUAGE", "language" to codes[index])
            book.language = codes[index]
            store.edit(book.id) { it.language = codes[index] }
            showReader()
        }
    }
    private fun languageName(code: String) = when (code) {
        "en" -> tr("English"); "it" -> tr("Italian"); "fr" -> tr("French"); "de" -> tr("German"); "es" -> tr("Spanish"); else -> tr("Select language")
    }

    private fun chooseVoice(language: String) {
        val tts = previewTts
        if (!previewReady || tts == null) { toast(tr("Voices are still loading. Try again in a moment.")); return }
        val voices = NarrationVoices.available(tts, language)
        if (voices.isEmpty()) {
            choiceSheet(tr("No {0} voices", languageName(language)), listOf(
                MenuChoice(tr("Download voices"), tr("Install voices from your selected provider")),
                MenuChoice(tr("Change speech provider"), tr("Try Google, Samsung, or another installed engine"))
            )) { index ->
                if (index == 0) installVoices() else chooseEngine()
            }
            return
        }
        val current = store.voiceName(language)
        val chosen = voices.indexOfFirst { it.name == current }.let { if (it < 0) 0 else it }
        choiceSheet(
            tr("{0} voices", languageName(language)),
            voices.mapIndexed { index, voice ->
                MenuChoice(NarrationVoices.label(voice, index + 1), tr("{0} · tap to preview", voice.name))
            },
            selectedIndex = chosen,
            confirmLabel = tr("Use this voice"),
            onPreview = { index ->
                stopVoicePreview()
                if (playing) command("PAUSE")
                if (!previewAudioFocus.acquire()) { toast(tr("Audio is in use by another app. Try again shortly.")); return@choiceSheet }
                val id = "voice-preview-${++previewSequence}"
                previewUtterance = id
                tts.setAudioAttributes(PlaybackAudioFocus.attributes)
                tts.setVoice(voices[index])
                tts.setSpeechRate(store.speechRate)
                if (tts.speak(NarrationVoices.sample(language), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS)
                    stopVoicePreview()
            },
            onDismiss = { stopVoicePreview() }
        ) { index ->
                store.setVoiceName(language, voices[index].name)
                command("VOICE_CHANGED")
                if (inSettings) showSettings()
                toast(tr("Voice saved for {0}", languageName(language)))
        }
    }

    private fun installVoices() {
        pendingVoiceRefresh = true
        val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            store.speechEngine?.let(::setPackage)
        }
        runCatching { startActivity(install) }
            .onFailure {
                pendingVoiceRefresh = false
                toast(tr("Open your speech provider's voice settings to install more voices."))
            }
    }

    @Deprecated("Platform activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            importRequest -> {
                toast(tr("Importing EPUB…"))
                worker.execute {
                    val result = runCatching { store.importBook(uri) }
                    main.post { result.onSuccess { openBook(it) }.onFailure {
                        Log.e("FolioTone", tr("EPUB import failed"), it)
                        AlertDialog.Builder(this).setTitle(tr("Import failed"))
                            .setMessage(it.message ?: tr("Could not read the selected EPUB"))
                            .setPositiveButton("OK", null).show()
                    } }
                }
            }
            folderRequest -> {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                store.folderUri = uri.toString(); scanFolder()
            }
        }
    }

    private fun scanFolder() {
        toast(tr("Scanning folder…"))
        worker.execute {
            val result = runCatching { store.scanFolder() }
            main.post {
                result.onSuccess { (added, failed) -> toast(tr("Added {0} books{1}", added, if (failed > 0) tr("; {0} could not be imported", failed) else "")) }
                    .onFailure { toast(tr("Cannot scan folder: {0}", it.message)) }
                showHome()
            }
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
