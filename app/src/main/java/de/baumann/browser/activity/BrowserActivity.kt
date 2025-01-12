package de.baumann.browser.activity

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.Intent.ACTION_VIEW
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Browser
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.View.*
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebView.HitTestResult
import android.widget.*
import android.widget.TextView.OnEditorActionListener
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import de.baumann.browser.Ninja.R
import de.baumann.browser.Ninja.databinding.*
import de.baumann.browser.browser.*
import de.baumann.browser.database.*
import de.baumann.browser.epub.EpubManager
import de.baumann.browser.preference.*
import de.baumann.browser.service.ClearService
import de.baumann.browser.task.SaveScreenshotTask
import de.baumann.browser.unit.BrowserUnit
import de.baumann.browser.unit.HelperUnit
import de.baumann.browser.unit.HelperUnit.toNormalScheme
import de.baumann.browser.unit.IntentUnit
import de.baumann.browser.unit.ViewUnit
import de.baumann.browser.unit.ViewUnit.dp
import de.baumann.browser.util.Constants
import de.baumann.browser.view.*
import de.baumann.browser.view.adapter.*
import de.baumann.browser.view.dialog.*
import de.baumann.browser.view.viewControllers.OverviewDialogController
import de.baumann.browser.view.viewControllers.ToolbarViewController
import de.baumann.browser.view.viewControllers.TouchAreaViewController
import de.baumann.browser.view.viewControllers.TranslationViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.*
import java.lang.reflect.Field
import java.util.*
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.system.exitProcess


open class BrowserActivity : AppCompatActivity(), BrowserController, OnClickListener {
    private lateinit var fabImageButtonNav: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var searchBox: EditText
    private lateinit var ninjaWebView: NinjaWebView
    private lateinit var omniboxTitle: TextView

    private var bottomSheetDialog: Dialog? = null
    private var videoView: VideoView? = null
    private var customView: View? = null

    // Layouts
    private lateinit var mainToolbar: RelativeLayout
    private lateinit var searchPanel: ViewGroup
    private lateinit var mainContentLayout: FrameLayout
    private lateinit var subContainer: RelativeLayout

    private var fullscreenHolder: FrameLayout? = null

    // Others
    private var title: String? = null
    private var url: String? = null
    private var downloadReceiver: BroadcastReceiver? = null
    private val sp: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val config: ConfigManager by lazy { ConfigManager(this) }
    private fun prepareRecord(): Boolean {
        val webView = currentAlbumController as NinjaWebView
        val title = webView.title
        val url = webView.url
        return (title == null || title.isEmpty()
                || url == null || url.isEmpty()
                || url.startsWith(BrowserUnit.URL_SCHEME_ABOUT)
                || url.startsWith(BrowserUnit.URL_SCHEME_MAIL_TO)
                || url.startsWith(BrowserUnit.URL_SCHEME_INTENT))
    }

    private var originalOrientation = 0
    private var searchOnSite = false
    private var customViewCallback: CustomViewCallback? = null
    private var currentAlbumController: AlbumController? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var binding: ActivityMainBinding

    private val bookmarkManager: BookmarkManager by lazy {  BookmarkManager(this) }

    private val epubManager: EpubManager by lazy { EpubManager(this) }

    private var uiMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    private var shouldLoadTabState: Boolean = false

    private val toolbarViewController: ToolbarViewController by lazy { ToolbarViewController(this, binding.toolbarScroller) }

    private lateinit var overviewDialogController: OverviewDialogController

    private val browserContainer: BrowserContainer = BrowserContainer()

    private val touchController: TouchAreaViewController by lazy {
        TouchAreaViewController(
            context = this,
            rootView = binding.root,
            pageUpAction = {ninjaWebView.pageUpWithNoAnimation()},
            pageTopAction = { ninjaWebView.jumpToTop() },
            pageDownAction = { ninjaWebView.pageDownWithNoAnimation() },
            pageBottomAction = { ninjaWebView.jumpToBottom() },
        )
    }

    private val translateController: TranslationViewController by lazy {
        TranslationViewController(
            this,
            binding.subContainer,
            binding.twoPanelLayout,
            { showTranslation() },
            { if (ninjaWebView.isReaderModeOn) ninjaWebView.toggleReaderMode() },
        )
    }

    private val dialogManager: DialogManager by lazy { DialogManager(this) }

    // Classes
    private inner class VideoCompletionListener : OnCompletionListener, MediaPlayer.OnErrorListener {
        override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
            return false
        }

        override fun onCompletion(mp: MediaPlayer) {
            onHideCustomView()
        }
    }

    // Overrides
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)

        lifecycleScope.launch {
            bookmarkManager.migrateOldData()
        }

        savedInstanceState?.let {
            shouldLoadTabState = it.getBoolean(K_SHOULD_LOAD_TAB_STATE)
        }

        WebView.enableSlowWholeDocumentDraw()

        sp.edit().putInt("restart_changed", 0).apply()
        HelperUnit.applyTheme(this)
        setContentView(binding.root)
        if (sp.getString("saved_key_ok", "no") == "no") {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890!§$%&/()=?;:_-.,+#*<>".toCharArray()
            val sb = StringBuilder()
            val random = Random()
            for (i in 0..24) {
                val c = chars[random.nextInt(chars.size)]
                sb.append(c)
            }
            if (Locale.getDefault().country == "CN") {
                sp.edit().putString(getString(R.string.sp_search_engine), "2").apply()
            }
            sp.edit().putString("saved_key", sb.toString()).apply()
            sp.edit().putString("saved_key_ok", "yes").apply()
            sp.edit().putString("setting_gesture_tb_up", "08").apply()
            sp.edit().putString("setting_gesture_tb_down", "01").apply()
            sp.edit().putString("setting_gesture_tb_left", "07").apply()
            sp.edit().putString("setting_gesture_tb_right", "06").apply()
            sp.edit().putString("setting_gesture_nav_up", "04").apply()
            sp.edit().putString("setting_gesture_nav_down", "05").apply()
            sp.edit().putString("setting_gesture_nav_left", "03").apply()
            sp.edit().putString("setting_gesture_nav_right", "02").apply()
            sp.edit().putBoolean(getString(R.string.sp_location), false).apply()
        }
        mainContentLayout = findViewById(R.id.main_content)
        subContainer = findViewById(R.id.sub_container)
        initToolbar()
        initSearchPanel()
        initOverview()
        initTouchArea()
        updateWebViewCountUI()

        AdBlock(this) // For AdBlock cold boot
        Javascript(this)
        Cookie(this)

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                dialogManager.showOkCancelDialog(
                    messageResId = R.string.toast_downloadComplete,
                    okAction = { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                )
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        registerReceiver(downloadReceiver, filter)
        dispatchIntent(intent)
        // after dispatching intent, the value should be reset to false
        shouldLoadTabState = false

        if (config.keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightModeFlags != uiMode) {
                //restartApp()
                recreate()
            }
        }
    }

    private fun initTouchArea() {
        binding.omniboxTouch.setOnLongClickListener {
            TouchAreaDialog(this@BrowserActivity).show()
            true
        }

        updateTouchView()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == WRITE_EPUB_REQUEST_CODE && resultCode == RESULT_OK) {
            val nonNullData = data?.data ?: return
            saveEpub(nonNullData)

            return
        }

        if (requestCode == WRITE_PDF_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { printPDF() }

            return
        }

        if (requestCode == INPUT_FILE_REQUEST_CODE && filePathCallback != null) {
            var results: Array<Uri>? = null
            // Check that the response is a good one
            if (resultCode == RESULT_OK) {
                if (data != null) {
                    // If there is not data, then we may have taken a photo
                    val dataString = data.dataString
                    if (dataString != null) {
                        results = arrayOf(Uri.parse(dataString))
                    }
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
        return
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatchIntent(intent)
    }

    public override fun onResume() {
        super.onResume()
        if (sp.getInt("restart_changed", 1) == 1) {
            sp.edit().putInt("restart_changed", 0).apply()
            showRestartConfirmDialog()
        }

        updateOmnibox()
        overridePendingTransition(0, 0)
        uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    private fun showRestartConfirmDialog() {
        dialogManager.showOkCancelDialog(
            messageResId = R.string.toast_restart,
            okAction = { restartApp() }
        )
    }

    private fun restartApp() {
        finishAffinity() // Finishes all activities.
        startActivity(packageManager.getLaunchIntentForPackage(packageName))    // Start the launch activity
        overridePendingTransition(0,0)
        exitProcess(0)
    }

    private fun showFileListConfirmDialog() {
        dialogManager.showOkCancelDialog(
            messageResId = R.string.toast_downloadComplete,
            okAction = { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        )
    }

    public override fun onDestroy() {
        if (sp.getBoolean(getString(R.string.sp_clear_quit), false)) {
            val toClearService = Intent(this, ClearService::class.java)
            startService(toClearService)
        }
        browserContainer.clear()
        IntentUnit.setContext(null)
        unregisterReceiver(downloadReceiver)
        bookmarkManager.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                return if (config.volumePageTurn) {
                    ninjaWebView.pageDownWithNoAnimation()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (config.volumePageTurn) {
                    ninjaWebView.pageUpWithNoAnimation()
                    return true
                } else {
                    return false
                }
            }
            KeyEvent.KEYCODE_MENU -> return showMenuDialog()
            KeyEvent.KEYCODE_BACK -> {
                ViewUnit.hideKeyboard(this@BrowserActivity)
                if (overviewDialogController.isVisible()) {
                    hideOverview()
                    return true
                }
                if (fullscreenHolder != null || customView != null || videoView != null) {
                    return onHideCustomView()
                } else if (mainToolbar.visibility == GONE && sp.getBoolean("sp_toolbarShow", true)) {
                    showToolbar()
                } else if (!toolbarViewController.isDisplayed()) {
                    toolbarViewController.show()
                } else {
                    if (ninjaWebView.canGoBack()) {
                        ninjaWebView.goBack()
                    } else {
                        removeAlbum(currentAlbumController!!)
                    }
                }
                return true
            }
            // vim bindings
            KeyEvent.KEYCODE_O -> {
                binding.omniboxInput.performClick()
            }
        }
        return false
    }

    @Synchronized
    override fun showAlbum(controller: AlbumController) {
        if (currentAlbumController != null) {
            if (currentAlbumController == controller) {
                return
            }

            currentAlbumController?.deactivate()
            val av = controller as View
            mainContentLayout.removeAllViews()
            mainContentLayout.addView(av)
        } else {
            mainContentLayout.removeAllViews()
            mainContentLayout.addView(controller as View)
        }
        currentAlbumController = controller
        currentAlbumController?.activate()
        updateOmnibox()

        updateSavedAlbumInfo()
    }

    override fun updateAutoComplete() {
        lifecycleScope.launch {
            val activity = this@BrowserActivity
            val action = RecordAction(activity)
            action.open(false)
            val list = action.listEntries(activity, true)
            action.close()

            val adapter = CompleteAdapter(activity, R.layout.complete_item, list) { record ->
                updateAlbum(record.url)
                ViewUnit.hideKeyboard(this@BrowserActivity)
            }
            binding.omniboxInput.setAdapter(adapter)
            binding.omniboxInput.threshold = 1
            binding.omniboxInput.dropDownVerticalOffset = -16
            binding.omniboxInput.dropDownWidth = ViewUnit.getWindowWidth(activity)
        }
    }

    private fun showOverview() = overviewDialogController.show()

    override fun hideOverview() = overviewDialogController.hide()

    private fun hideBottomSheetDialog() {
        bottomSheetDialog?.cancel()
    }

    @SuppressLint("RestrictedApi")
    override fun onClick(v: View) {
        ninjaWebView = currentAlbumController as NinjaWebView
        try {
            title = ninjaWebView.title?.trim { it <= ' ' }
            url = ninjaWebView.url?.trim { it <= ' ' }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        hideBottomSheetDialog()
        when (v.id) {
            R.id.button_size -> showFontSizeChangeDialog()
            R.id.omnibox_title -> {
                toolbarViewController.hide()
                binding.omniboxInput.requestFocus()
                ViewUnit.showKeyboard(this@BrowserActivity)
            }
            R.id.omnibox_input_clear -> {
                if (binding.omniboxInput.text.isEmpty()) {
                    showToolbar()
                } else {
                    binding.omniboxInput.text.clear()
                }
            }
            R.id.tab_plus_incognito -> {
                hideOverview()
                addAlbum(getString(R.string.app_name), "", incognito = true)
                binding.omniboxInput.requestFocus()
                ViewUnit.showKeyboard(this@BrowserActivity)
            }
            R.id.new_window -> {
                hideOverview()
                launchNewBrowser()
            }
            R.id.tab_plus_bottom -> {
                hideOverview()
                addAlbum(getString(R.string.app_name), "")
                binding.omniboxInput.requestFocus()
                ViewUnit.showKeyboard(this@BrowserActivity)
            }
            R.id.menu_save_pdf -> showPdfFilePicker()

            // --- tool bar handling
            R.id.omnibox_tabcount -> showOverview()
            R.id.omnibox_touch -> toggleTouchTurnPageFeature()
            R.id.omnibox_font -> showFontSizeChangeDialog()
            R.id.omnibox_reader -> ninjaWebView.toggleReaderMode()
            R.id.omnibox_bold_font -> {
                config.boldFontStyle = !config.boldFontStyle
            }
            R.id.omnibox_back -> if (ninjaWebView.canGoBack()) {
                ninjaWebView.goBack()
            } else {
                removeAlbum(currentAlbumController!!)
            }
            R.id.toolbar_forward -> if (ninjaWebView.canGoForward()) {
                ninjaWebView.goForward()
            }
            R.id.omnibox_page_up -> ninjaWebView.pageUpWithNoAnimation()
            R.id.omnibox_page_down -> {
                keepToolbar = true
                ninjaWebView.pageDownWithNoAnimation()
            }
            R.id.omnibox_vertical_read -> ninjaWebView.toggleVerticalRead()

            R.id.omnibox_refresh -> if (url != null && ninjaWebView.isLoadFinish) {
                if (url?.startsWith("https://") != true) {
                    dialogManager.showOkCancelDialog(
                        messageResId = R.string.toast_unsecured,
                        okAction = {
                            ninjaWebView.loadUrl(
                                url?.replace("http://", "https://") ?: ""
                            )
                        },
                        cancelAction = { ninjaWebView.reload() }
                    )
                } else {
                    ninjaWebView.reload()
                }
            } else if (url == null) {
                val text = getString(R.string.toast_load_error) + ": " + url
                NinjaToast.show(this, text)
            } else {
                ninjaWebView.stopLoading()
            }
            R.id.toolbar_setting -> ToolbarConfigDialog(this).show()
            R.id.toolbar_increase_font -> increaseFontSize()
            R.id.toolbar_decrease_font -> decreaseFontSize()
            R.id.toolbar_fullscreen -> fullscreen()
            R.id.toolbar_rotate -> rotateScreen()
            R.id.toolbar_translate -> showTranslation()
            else -> {
            }
        }
    }

    private fun launchNewBrowser() {
        val intent = Intent(this, ExtraBrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            action = ACTION_VIEW
            data = Uri.parse(config.favoriteUrl)
        }

        startActivity(intent)
    }

    private var isRotated:Boolean = false
    private fun rotateScreen() {
        isRotated = !isRotated
        requestedOrientation = if (!isRotated) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun saveBookmark(url: String? = null, title: String? = null) {
        val currentUrl = url ?: ninjaWebView.url ?: return
        val title = title ?: HelperUnit.secString(ninjaWebView.title)
        val context = this
        try {
            lifecycleScope.launch {
                if (bookmarkManager.existsUrl(currentUrl)) {
                    NinjaToast.show(context, R.string.toast_newTitle)
                } else {
                    dialogManager.showBookmarkEditDialog(
                        bookmarkManager,
                        Bookmark(title, currentUrl),
                        {
                            ViewUnit.hideKeyboard(context as Activity)
                            NinjaToast.show(this@BrowserActivity, R.string.toast_edit_successful)
                            updateAutoComplete()
                        },
                        { ViewUnit.hideKeyboard(context as Activity) }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            NinjaToast.show(this, R.string.toast_error)
        }
    }

    private fun  toggleTouchTurnPageFeature() {
        config.enableTouchTurn = !config.enableTouchTurn
        updateTouchView()
    }

    private fun updateTouchView() {
        val fabResourceId = if (config.enableTouchTurn) R.drawable.icon_overflow_fab else R.drawable.ic_touch_disabled
        fabImageButtonNav.setImageResource(fabResourceId)
        val touchResourceId = if (config.enableTouchTurn) R.drawable.ic_touch_enabled else R.drawable.ic_touch_disabled
        binding.omniboxTouch.setImageResource(touchResourceId)

        touchController.toggleTouchPageTurn(config.enableTouchTurn)
    }

    // Methods
    private fun showFontSizeChangeDialog() =
        dialogManager.showFontSizeChangeDialog { changeFontSize(it) }

    private fun changeFontSize(size: Int) {
        config.fontSize = size
        ninjaWebView.settings.textZoom = size
    }

    private fun increaseFontSize() = changeFontSize(config.fontSize + 20)

    private fun decreaseFontSize() {
        if (config.fontSize <= 50) return

        changeFontSize(config.fontSize - 20)
    }

    private fun showPdfFilePicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = Constants.MIME_TYPE_PDF
        intent.putExtra(Intent.EXTRA_TITLE, "einkbro.pdf")
        startActivityForResult(intent, WRITE_PDF_REQUEST_CODE)
    }

    private fun saveEpub(fileUri: Uri) {
        lifecycleScope.launch(Dispatchers.Main) {
            val bookName = if (isNewEpubFile) epubManager.getBookName() else ""
            val chapterName = epubManager.getChapterName(ninjaWebView.title)

            val progressDialog = ProgressDialog(this@BrowserActivity, R.style.TouchAreaDialog).apply {
                setTitle(R.string.saving_epub)
                show()
            }

            val rawHtml = ninjaWebView.getRawHtml()
            epubManager.saveEpub(
                    isNewEpubFile,
                    fileUri,
                    rawHtml,
                    bookName,
                    chapterName,
                    ninjaWebView.url ?: "") {
                progressDialog.dismiss()
                HelperUnit.openFile(this@BrowserActivity, fileUri, Constants.MIME_TYPE_EPUB)
                isNewEpubFile = false
            }
        }
    }

    private fun showTranslation() {
        lifecycleScope.launch(Dispatchers.Main) {
            translateController.showTranslation(ninjaWebView)
        }
    }

    private fun printPDF() {
        try {
            val title = HelperUnit.fileName(ninjaWebView.url)
            val printManager = getSystemService(PRINT_SERVICE) as PrintManager
            val printAdapter = ninjaWebView.createPrintDocumentAdapter(title) {
                showFileListConfirmDialog()
            }
            printManager.print(title, printAdapter, PrintAttributes.Builder().build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(K_SHOULD_LOAD_TAB_STATE, true)
        super.onSaveInstanceState(outState)
    }

    private fun dispatchIntent(intent: Intent) {
        when(intent.action) {
            "", Intent.ACTION_MAIN -> { // initial case
                if (currentAlbumController == null) { // newly opened Activity
                    if ((shouldLoadTabState || config.shouldSaveTabs) &&
                        config.savedAlbumInfoList.isNotEmpty()) {
                        // fix current album index is larger than album size
                        if (config.currentAlbumIndex >= config.savedAlbumInfoList.size) {
                            config.currentAlbumIndex = config.savedAlbumInfoList.size -1
                        }
                        val albumList = config.savedAlbumInfoList.toList()
                        var savedIndex = config.currentAlbumIndex
                        // fix issue
                        if (savedIndex == -1) savedIndex = 0
                        Log.w(TAG, "savedIndex:$savedIndex")
                        Log.w(TAG, "albumList:$albumList")
                        albumList.forEachIndexed { index, albumInfo ->
                            addAlbum(
                                title = albumInfo.title,
                                url = albumInfo.url,
                                foreground = (index == savedIndex))
                        }
                    } else {
                        addAlbum()
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                // if webview for that url already exists, show the original tab, otherwise, create new
                val url = intent.data?.toNormalScheme()?.toString() ?: return
                getUrlMatchedBrowser(url)?. let { showAlbum(it) } ?: addAlbum(url = url)
            }
            Intent.ACTION_WEB_SEARCH -> addAlbum(url = intent.getStringExtra(SearchManager.QUERY))
            "sc_history" -> {
                addAlbum()
                openHistoryPage()
            }
            "sc_bookmark" -> {
                addAlbum()
                openBookmarkPage()
            }
            Intent.ACTION_SEND -> {
                val url = intent.getStringExtra(Intent.EXTRA_TEXT)
                addAlbum(url = url)
            }
            else -> { }
        }
        getIntent().action = ""
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initToolbar() {
        mainToolbar = findViewById(R.id.main_toolbar)
        omniboxTitle = findViewById(R.id.omnibox_title)
        progressBar = findViewById(R.id.main_progress_bar)
        initFAB()
        binding.omniboxSetting.setOnLongClickListener {
            showFastToggleDialog()
            false
        }
        binding.omniboxSetting.setOnClickListener { showMenuDialog() }
        if (sp.getBoolean("sp_gestures_use", true)) {
            val onTouchListener = object : SwipeTouchListener(this) {
                override fun onSwipeTop() = performGesture("setting_gesture_nav_up")
                override fun onSwipeBottom() = performGesture("setting_gesture_nav_down")
                override fun onSwipeRight() = performGesture("setting_gesture_nav_right")
                override fun onSwipeLeft() = performGesture("setting_gesture_nav_left")
            }
            fabImageButtonNav.setOnTouchListener(onTouchListener)
            binding.omniboxSetting.setOnTouchListener(onTouchListener)
        }
        binding.omniboxInput.setOnEditorActionListener(OnEditorActionListener { _, _, _ ->
            val query = binding.omniboxInput.text.toString().trim { it <= ' ' }
            if (query.isEmpty()) {
                NinjaToast.show(this, getString(R.string.toast_input_empty))
                return@OnEditorActionListener true
            }
            updateAlbum(query)
            showToolbar()
            false
        })
        binding.omniboxInput.onFocusChangeListener = OnFocusChangeListener { _, _ ->
            if (binding.omniboxInput.hasFocus()) {
                binding.omniboxInput.setText(ninjaWebView.url)
                binding.omniboxInput.setSelection(0, binding.omniboxInput.text.toString().length)
                toolbarViewController.hide()
            } else {
                toolbarViewController.show()
                omniboxTitle.text = ninjaWebView.title
                ViewUnit.hideKeyboard(this@BrowserActivity)
            }
        }
        updateAutoComplete()

        // long click on overview, show bookmark
        binding.omniboxTabcount.setOnLongClickListener {
            config.isIncognitoMode = !config.isIncognitoMode
            true
        }

        // scroll to top
        binding.omniboxPageUp.setOnLongClickListener {
            ninjaWebView.jumpToTop()
            true
        }

        // hide bottom bar when refresh button is long pressed.
        binding.omniboxRefresh.setOnLongClickListener {
            fullscreen()
            true
        }

        binding.omniboxBookmark.setOnClickListener { openBookmarkPage() }
        binding.omniboxBookmark.setOnLongClickListener { saveBookmark(); true }
        binding.toolbarTranslate.setOnLongClickListener { translateController.showTranslationConfigDialog(); true }

        sp.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        toolbarViewController.reorderIcons()
    }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when {
            key.equals(ConfigManager.K_TOOLBAR_ICONS) -> { toolbarViewController.reorderIcons() }
            key.equals(ConfigManager.K_BOLD_FONT) -> {
                if (config.boldFontStyle) {
                    ninjaWebView.updateCssStyle()
                } else {
                    ninjaWebView.reload()
                }
            }
            key.equals(ConfigManager.K_FONT_STYLE_SERIF) -> {
                if (config.fontStyleSerif) {
                    ninjaWebView.updateCssStyle()
                } else {
                    ninjaWebView.reload()
                }
            }
            key.equals(ConfigManager.K_IS_INCOGNITO_MODE) -> {
                updateWebViewCountUI()
                NinjaToast.showShort(
                    this,
                    "Incognito mode is " + if (config.isIncognitoMode) "enabled." else "disabled."
                )
            }
            key.equals(ConfigManager.K_KEEP_AWAKE) -> {
                if (config.keepAwake) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun initFAB() {
        fabImageButtonNav = findViewById(R.id.fab_imageButtonNav)
        val params = RelativeLayout.LayoutParams(fabImageButtonNav.layoutParams.width, fabImageButtonNav.layoutParams.height)
        when (config.fabPosition) {
            FabPosition.Left -> {
                fabImageButtonNav.layoutParams = params.apply {
                    addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                    addRule(RelativeLayout.ALIGN_BOTTOM, R.id.main_content)
                }
            }
            FabPosition.Center -> {
                fabImageButtonNav.layoutParams = params.apply {
                    addRule(RelativeLayout.CENTER_HORIZONTAL)
                    addRule(RelativeLayout.ALIGN_BOTTOM, R.id.main_content)
                }
            }
        }

        ViewUnit.expandViewTouchArea(fabImageButtonNav, 20.dp(this))
        fabImageButtonNav.setOnClickListener { showToolbar() }
        fabImageButtonNav.setOnLongClickListener {
            showFastToggleDialog()
            false
        }
    }

    private fun performGesture(gesture: String) {
        val gestureAction = Objects.requireNonNull(sp.getString(gesture, "0"))
        val controller: AlbumController?
        ninjaWebView = currentAlbumController as NinjaWebView
        when (gestureAction) {
            "01" -> {
            }
            "02" -> if (ninjaWebView.canGoForward()) {
                ninjaWebView.goForward()
            } else {
                NinjaToast.show(this, R.string.toast_webview_forward)
            }
            "03" -> if (ninjaWebView.canGoBack()) {
                ninjaWebView.goBack()
            } else {
                removeAlbum(currentAlbumController!!)
            }
            "04" -> ninjaWebView.jumpToTop()
            "05" -> ninjaWebView.pageDownWithNoAnimation()
            "06" -> {
                controller = nextAlbumController(false)
                showAlbum(controller!!)
            }
            "07" -> {
                controller = nextAlbumController(true)
                showAlbum(controller!!)
            }
            "08" -> showOverview()
            "09" -> addAlbum(getString(R.string.app_name), sp.getString("favoriteURL", Constants.DEFAULT_HOME_URL))
            "10" -> removeAlbum(currentAlbumController!!)
            // page up
            "11" -> ninjaWebView.pageUpWithNoAnimation()
            // page down
            "12" -> ninjaWebView.pageDownWithNoAnimation()
        }
    }

    private fun initOverview() {
        overviewDialogController = OverviewDialogController(
            this,
            binding.layoutOverview,
            gotoUrlAction = { url -> updateAlbum(url) },
            addTabAction = { title, url, isForeground -> addAlbum(title, url, isForeground) },
            onBookmarksChanged = { updateAutoComplete() },
            onHistoryChanged = { updateAutoComplete() }
        )
    }

    private fun openHistoryPage() = overviewDialogController.openHistoryPage()

    private fun openBookmarkPage() = overviewDialogController.openBookmarkPage()

    private fun initSearchPanel() {
        searchPanel = binding.mainSearchPanel
        searchBox = binding.mainSearchBox
        searchBox.addTextChangedListener(searchBoxTextChangeListener)
        searchBox.setOnEditorActionListener(searchBoxEditorActionListener)
        binding.mainSearchUp.setOnClickListener { searchUp() }
        binding.mainSearchDown.setOnClickListener { searchDown() }
        binding.mainSearchCancel.setOnClickListener { hideSearchPanel() }
    }

    private val searchBoxEditorActionListener = object: OnEditorActionListener {
        override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
            if (actionId != EditorInfo.IME_ACTION_DONE) {
                return false
            }
            if (searchBox.text.toString().isEmpty()) {
                NinjaToast.show(this@BrowserActivity, getString(R.string.toast_input_empty))
                return true
            }
            return false
        }
    }

    private val searchBoxTextChangeListener = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            (currentAlbumController as NinjaWebView?)?.findAllAsync(s.toString())
        }
    }

    private fun searchUp() {
        val query = searchBox.text.toString()
        if (query.isEmpty()) {
            NinjaToast.show(this, getString(R.string.toast_input_empty))
            return
        }
        ViewUnit.hideKeyboard(this)
        (currentAlbumController as NinjaWebView).findNext(false)
    }
    private fun searchDown() {
        val query = searchBox.text.toString()
        if (query.isEmpty()) {
            NinjaToast.show(this, getString(R.string.toast_input_empty))
            return
        }
        ViewUnit.hideKeyboard(this)
        (currentAlbumController as NinjaWebView).findNext(true)
    }

    private fun showFastToggleDialog() =
            FastToggleDialog(this, ninjaWebView.url ?: "") {
                if (ninjaWebView != null) {
                    ninjaWebView.initPreferences()
                    ninjaWebView.reload()
                }
            }.show()

    override fun addNewTab(url: String?) = addAlbum(url = url)

    private fun getUrlMatchedBrowser(url: String): NinjaWebView? {
        return browserContainer.list().firstOrNull { it.albumUrl == url } as NinjaWebView?
    }

    @Synchronized
    private fun addAlbum(
        title: String = "",
        url: String? = config.favoriteUrl,
        foreground: Boolean = true,
        incognito: Boolean = false
    ) {
        if (url == null) return

        ninjaWebView = NinjaWebView(this, this)
        ninjaWebView.albumTitle = title
        ninjaWebView.incognito = incognito
        ViewUnit.bound(this, ninjaWebView)
        val albumView = ninjaWebView.albumView
        if (currentAlbumController != null) {
            val index = browserContainer.indexOf(currentAlbumController) + 1
            browserContainer.add(ninjaWebView, index)
            updateWebViewCount()
            overviewDialogController.addTabPreview(albumView)
        } else {
            browserContainer.add(ninjaWebView)
            updateWebViewCount()
            overviewDialogController.addTabPreview(albumView)
        }
        if (!foreground) {
            ViewUnit.bound(this, ninjaWebView)
            ninjaWebView.loadUrl(url)
            ninjaWebView.deactivate()
        } else {
            showToolbar()
            showAlbum(ninjaWebView)
            if (url.isNotEmpty()) {
                ninjaWebView.loadUrl(url)
            }
        }

        updateSavedAlbumInfo()
    }

    private fun updateSavedAlbumInfo() {
        val albumControllers = browserContainer.list()
        val albumInfoList = albumControllers
                .filter { it.albumUrl.isNotBlank() }
                .map { controller -> AlbumInfo(controller.albumTitle, controller.albumUrl) }
        config.savedAlbumInfoList = albumInfoList
        config.currentAlbumIndex = browserContainer.indexOf(currentAlbumController)
        // fix if current album is still with null url
        if (albumInfoList.isNotEmpty() && config.currentAlbumIndex >= albumInfoList.size) {
            config.currentAlbumIndex = albumInfoList.size - 1
        }
    }

    private fun updateWebViewCount() {
        binding.omniboxTabcount.text = browserContainer.size().toString()
        updateWebViewCountUI()
    }

    private fun updateWebViewCountUI() {
        binding.omniboxTabcount.setBackgroundResource(
            if (config.isIncognitoMode
                || (this::ninjaWebView.isInitialized && ninjaWebView.incognito)) R.drawable.button_border_bg_dash else R.drawable.button_border_bg
        )
    }

    @Synchronized
    private fun updateAlbum(url: String?) {
        if (url == null) return
        (currentAlbumController as NinjaWebView).loadUrl(url)
        updateOmnibox()

        updateSavedAlbumInfo()
    }

    private fun closeTabConfirmation(okAction: () -> Unit) {
        if (!sp.getBoolean("sp_close_tab_confirm", false)) {
            okAction()
        } else {
            dialogManager.showOkCancelDialog(
                messageResId = R.string.toast_close_tab,
                okAction = okAction,
            )
        }
    }

    @Synchronized
    override fun removeAlbum(controller: AlbumController) {
        updateSavedAlbumInfo()

        if (browserContainer.size() <= 1) {
                finish()
        } else {
            closeTabConfirmation {
                overviewDialogController.removeTabView(controller.albumView)
                var index = browserContainer.indexOf(controller)
                val currentIndex = browserContainer.indexOf(currentAlbumController)
                browserContainer.remove(controller)
                // only refresh album when the delete one is current one
                if (index == currentIndex) {
                    if (index >= browserContainer.size()) {
                        index = browserContainer.size() - 1
                    }
                    showAlbum(browserContainer.get(index))
                }
                updateWebViewCount()
            }
        }
    }

    private fun updateOmnibox() {
        if(!this::ninjaWebView.isInitialized) return

        if (this::ninjaWebView.isInitialized && ninjaWebView === currentAlbumController) {
            omniboxTitle.text = ninjaWebView.title
        } else {
            ninjaWebView = currentAlbumController as? NinjaWebView ?: return
            updateProgress(ninjaWebView.progress)
        }
    }

    var keepToolbar = false
    private fun scrollChange() {
        ninjaWebView.setOnScrollChangeListener(object : NinjaWebView.OnScrollChangeListener {
            override fun onScrollChange(scrollY: Int, oldScrollY: Int) {
                if (!sp.getBoolean("hideToolbar", false)) return

                val height = floor(x = ninjaWebView.contentHeight * ninjaWebView.resources.displayMetrics.density.toDouble()).toInt()
                val webViewHeight = ninjaWebView.height
                val cutoff = height - webViewHeight - 112 * resources.displayMetrics.density.roundToInt()
                if (scrollY in (oldScrollY + 1)..cutoff) {
                    if (!keepToolbar) {
                        // Daniel
                        fullscreen();
                    } else {
                        keepToolbar = false
                    }
                } else if (scrollY < oldScrollY) {
                    //showOmnibox()
                }
            }
        })
    }

    @Synchronized
    override fun updateProgress(progress: Int) {
        progressBar.progress = progress
        updateOmnibox()
        updateAutoComplete()
        scrollChange()
        HelperUnit.initRendering(mainContentLayout, config.shouldInvert)
        ninjaWebView.requestFocus()
        if (progress < BrowserUnit.PROGRESS_MAX) {
            updateRefresh(true)
            progressBar.visibility = View.VISIBLE
        } else {
            updateRefresh(false)
            progressBar.visibility = View.GONE
        }
    }

    private fun updateRefresh(running: Boolean) {
        if (running) {
            binding.omniboxRefresh.setImageResource(R.drawable.icon_close)
        } else {
            try {
                if (ninjaWebView.url?.contains("https://") == true) {
                    binding.omniboxRefresh.setImageResource(R.drawable.icon_refresh)
                } else {
                    binding.omniboxRefresh.setImageResource(R.drawable.icon_alert)
                }
            } catch (e: Exception) {
                binding.omniboxRefresh.setImageResource(R.drawable.icon_refresh)
            }
        }
    }

    override fun showFileChooser(filePathCallback: ValueCallback<Array<Uri>>) {
        this.filePathCallback?.onReceiveValue(null)
        this.filePathCallback = filePathCallback
        val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT)
        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE)
        contentSelectionIntent.type = "*/*"
        val chooserIntent = Intent(Intent.ACTION_CHOOSER)
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
        startActivityForResult(chooserIntent, INPUT_FILE_REQUEST_CODE)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (view == null) {
            return
        }
        if (customView != null && callback != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        originalOrientation = requestedOrientation
        fullscreenHolder = FrameLayout(this).apply{
            addView(
                customView,
                FrameLayout.LayoutParams( FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )

        }
        val decorView = window.decorView as FrameLayout
        decorView.addView(
            fullscreenHolder,
            FrameLayout.LayoutParams( FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        customView?.keepScreenOn = true
        (currentAlbumController as View?)?.visibility = View.GONE
        ViewUnit.setCustomFullscreen(window, true)
        if (view is FrameLayout) {
            if (view.focusedChild is VideoView) {
                videoView = view.focusedChild as VideoView
                videoView?.setOnErrorListener(VideoCompletionListener())
                videoView?.setOnCompletionListener(VideoCompletionListener())
            }
        }
        customViewCallback = callback
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    override fun onHideCustomView(): Boolean {
        if (customView == null || customViewCallback == null || currentAlbumController == null) {
            return false
        }

        // prevent inputBox to get the focus
        binding.omniboxInput.isEnabled = false

        (window.decorView as FrameLayout).removeView(fullscreenHolder)
        customView?.keepScreenOn = false
        (currentAlbumController as View).visibility = View.VISIBLE
        ViewUnit.setCustomFullscreen(window, false)
        fullscreenHolder = null
        customView = null
        if (videoView != null) {
            videoView?.setOnErrorListener(null)
            videoView?.setOnCompletionListener(null)
            videoView = null
        }
        requestedOrientation = originalOrientation

        // re-enable inputBox after fullscreen view is removed.
        binding.omniboxInput.isEnabled = true
        return true
    }

    private var previousKeyEvent: KeyEvent? = null
    override fun handleKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action != KeyEvent.ACTION_DOWN) return false
        if (ninjaWebView.hitTestResult.type == HitTestResult.EDIT_TEXT_TYPE) return false

        if (event.isShiftPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_J -> {
                    val controller = nextAlbumController(true) ?: return true
                    showAlbum(controller)
                }
                KeyEvent.KEYCODE_K -> {
                    val controller = nextAlbumController(false) ?: return true
                    showAlbum(controller)
                }
                KeyEvent.KEYCODE_G -> ninjaWebView.jumpToBottom()
                else -> return false
            }
        } else { // non-capital
            when (event.keyCode) {
                // vim bindings
                KeyEvent.KEYCODE_B -> openBookmarkPage()
                KeyEvent.KEYCODE_O -> {
                    if (previousKeyEvent?.keyCode == KeyEvent.KEYCODE_V) {
                        decreaseFontSize()
                        previousKeyEvent = null
                    } else {
                        binding.omniboxInput.requestFocus()
                    }
                }
                KeyEvent.KEYCODE_J -> ninjaWebView.pageDownWithNoAnimation()
                KeyEvent.KEYCODE_K -> ninjaWebView.pageUpWithNoAnimation()
                KeyEvent.KEYCODE_H -> ninjaWebView.goBack()
                KeyEvent.KEYCODE_L -> ninjaWebView.goForward()
                KeyEvent.KEYCODE_D -> removeAlbum(currentAlbumController!!)
                KeyEvent.KEYCODE_T -> {
                    addAlbum(getString(R.string.app_name), "", true)
                    binding.omniboxInput.requestFocus()
                }
                KeyEvent.KEYCODE_SLASH -> showSearchPanel()
                KeyEvent.KEYCODE_G -> {
                    when {
                        previousKeyEvent == null -> {
                            previousKeyEvent = event
                        }
                        previousKeyEvent?.keyCode == KeyEvent.KEYCODE_G -> {
                            // gg
                            ninjaWebView.jumpToTop()
                            previousKeyEvent = null
                        }
                        else -> {
                            previousKeyEvent = null
                        }
                    }
                }
                KeyEvent.KEYCODE_V -> {
                    if (previousKeyEvent == null) {
                        previousKeyEvent = event
                    } else {
                        previousKeyEvent = null
                    }
                }
                KeyEvent.KEYCODE_I -> {
                    if (previousKeyEvent?.keyCode == KeyEvent.KEYCODE_V) {
                        increaseFontSize()
                        previousKeyEvent = null
                    }
                }

                else -> return false
            }
        }
        return true

    }

    private fun showContextMenuLinkDialog(url: String?) {
        val dialogView = DialogMenuContextLinkBinding.inflate(layoutInflater)
        val dialog = dialogManager.showOptionDialog(dialogView.root)
        dialogView.contextLinkNewTab.setOnClickListener {
            dialog.dismissWithAction {
                addAlbum(getString(R.string.app_name), url, false)
                NinjaToast.show(this, getString(R.string.toast_new_tab_successful))
            }
        }
        dialogView.contextLinkShareLink.setOnClickListener {
            dialog.dismissWithAction {
                if (prepareRecord())  NinjaToast.show(this, getString(R.string.toast_share_failed))
                else IntentUnit.share(this, "", url)
            }
        }
        dialogView.contextLinkOpenWith.setOnClickListener {
            dialog.dismissWithAction { HelperUnit.showBrowserChooser( this@BrowserActivity, url, getString(R.string.menu_open_with) ) }
        }
        dialogView.contextLinkSaveBookmark.setOnClickListener {
            dialog.dismissWithAction { saveBookmark(url, title = "") }
        }
        dialogView.contextLinkNewTabOpen.setOnClickListener {
            dialog.dismissWithAction { addAlbum(getString(R.string.app_name), url) }
        }
        dialogView.menuSavePdf.setOnClickListener {
            dialog.dismissWithAction { showSavePdfDialog(url) }
        }
    }

    override fun onLongPress(url: String?) {
        val result = ninjaWebView.hitTestResult
        if (url != null) {
            showContextMenuLinkDialog(url)
        } else if (result.type == HitTestResult.IMAGE_TYPE || result.type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE || result.type == HitTestResult.SRC_ANCHOR_TYPE) {
            showContextMenuLinkDialog(result.extra)
        }
    }

    private fun showSavePdfDialog(url: String?) {
        val url = url ?: return

        dialogManager.showSavePdfDialog(url = url, savePdf = { url, fileName -> savePdf(url, fileName) } )
    }

    private fun savePdf(url: String, fileName: String) {
        if (HelperUnit.needGrantStoragePermission(this)) { return }

        val source = Uri.parse(url)
        val request = DownloadManager.Request(source).apply {
            addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val dm = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager)
        dm.enqueue(request)
        ViewUnit.hideKeyboard(this)
    }

    @SuppressLint("RestrictedApi")
    private fun showToolbar() {
        if (!searchOnSite) {
            fabImageButtonNav.visibility = INVISIBLE
            searchPanel.visibility = GONE
            mainToolbar.visibility = VISIBLE
            binding.appBar.visibility = VISIBLE
            toolbarViewController.show()
            ViewUnit.hideKeyboard(this)
            showStatusBar()
        }
    }

    @SuppressLint("RestrictedApi")
    private fun fullscreen() {
        if (!searchOnSite) {
            fabImageButtonNav.visibility = VISIBLE
            searchPanel.visibility = GONE
            binding.appBar.visibility = GONE
            hideStatusBar()
        }
    }

    private fun hideSearchPanel() {
        searchOnSite = false
        searchBox.setText("")
        showToolbar()
    }

    private fun hideStatusBar() = window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

    private fun showStatusBar() = window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

    @SuppressLint("RestrictedApi")
    private fun showSearchPanel() {
        searchOnSite = true
        fabImageButtonNav.visibility = INVISIBLE
        mainToolbar.visibility = GONE
        searchPanel.visibility = VISIBLE
        omniboxTitle.visibility = GONE
        binding.appBar.visibility = VISIBLE
        searchBox.requestFocus()
        ViewUnit.showKeyboard(this)
    }

    private var isNewEpubFile = false
    private fun showSaveEpubDialog() = dialogManager.showSaveEpubDialog { isNew ->
        isNewEpubFile = isNew
        epubManager.showEpubFilePicker()
    }

    private fun showMenuDialog(): Boolean {
        MenuDialog(
            this,
            ninjaWebView,
            { updateAlbum(sp.getString("favoriteURL", "https://github.com/plateaukao/browser")) },
            { removeAlbum(currentAlbumController!!) },
            this::saveBookmark,
            this::showSearchPanel,
            this::showSaveEpubDialog,
            this::printPDF,
            this::showFontSizeChangeDialog,
            this::saveScreenshot,
        ).show()
        return true
    }

    private fun saveScreenshot() {
        lifecycleScope.launch(Dispatchers.Main) {
            SaveScreenshotTask(this@BrowserActivity, ninjaWebView).execute()
        }
    }

    private fun nextAlbumController(next: Boolean): AlbumController? {
        if (browserContainer.size() <= 1) {
            return currentAlbumController
        }

        val list = browserContainer.list()
        var index = list.indexOf(currentAlbumController)
        if (next) {
            index++
            if (index >= list.size) {
                return list.first()
            }
        } else {
            index--
            if (index < 0) {
                return list.last()
            }
        }
        return list[index]
    }

    private var mActionMode: ActionMode? = null
    override fun onActionModeStarted(mode: ActionMode) {
        if (mActionMode == null) {
            var isNaverDictExist = false
            mActionMode = mode
            val menu = mode.menu
            val toBeAddedLaterList: MutableList<MenuItem> = mutableListOf()
            for (index in 0 until menu.size()) {
                val item = menu.getItem(index)
                if (item.intent?.component?.packageName == "info.plateaukao.naverdict") {
                    isNaverDictExist = true
                    break
                }
            }

            for (index in 0 until menu.size()) {
                val item = menu.getItem(index)
                if (item.intent?.component?.packageName == "info.plateaukao.naverdict") {
                    continue
                }
                if (isNaverDictExist && item.intent?.component?.packageName == "com.onyx.dict") {
                    continue
                }

                toBeAddedLaterList.add(item)
            }

            if (isNaverDictExist) {
                for (item in toBeAddedLaterList) {
                    menu.removeItem(item.itemId)
                }
                val subMenu = menu.addSubMenu("Others")
                for (item in toBeAddedLaterList) {
                    if (item.title.equals("Copy")) {
                        menu.add(item.groupId, item.itemId, Menu.NONE, item.title)
                    } else {
                        subMenu.add(item.groupId, item.itemId, Menu.NONE, item.title)
                    }
                }
            }
        }
        super.onActionModeStarted(mode)
    }

    override fun onPause() {
        super.onPause()
        mActionMode?.finish()
        mActionMode = null
    }

    override fun onActionModeFinished(mode: ActionMode?) {
        super.onActionModeFinished(mode)
        mActionMode = null
    }

    companion object {
        private const val TAG = "BrowserActivity"
        private const val INPUT_FILE_REQUEST_CODE = 1
        const val WRITE_EPUB_REQUEST_CODE = 2
        private const val WRITE_PDF_REQUEST_CODE = 3
        private const val K_SHOULD_LOAD_TAB_STATE = "k_should_load_tab_state"
    }
}

private fun Dialog.dismissWithAction(action: ()-> Unit) {
    dismiss()
    action()
}