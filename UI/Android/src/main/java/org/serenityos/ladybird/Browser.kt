/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged

// One tab. Its page only exists once the tab navigates somewhere, so that a new tab costs no WebContent process.
class Tab(val isPrivate: Boolean) {
    var webView: WebView? = null
    val state: PageState get() = webView?.state ?: PageState("", "", false, false, false)
    val isNewTabPage: Boolean get() = webView == null || state.url.isEmpty() || state.url == "about:blank"
    val host: String get() = Uri.parse(state.url).host?.removePrefix("www.") ?: state.url
    val title: String get() = if (isNewTabPage) "New tab" else state.title.ifBlank { host }

    // The name of the site, without subdomains or its top-level domain: "wikipedia" for en.wikipedia.org.
    val siteName: String get() = host.split('.').let { if (it.size >= 2) it[it.size - 2] else host }
}

// A page the user visited, for suggestions and for what was recently closed.
data class Visit(val url: String, val title: String, val time: Long = System.currentTimeMillis())

// The browser interface of UI/Android/design: the tab strip and navigation bar at the top, the page under them, and
// the address, tab overview and menu screens over it.
class Browser(activity: AppCompatActivity) {
    private lateinit var activity: AppCompatActivity
    private val tabs = mutableListOf<Tab>()
    private var activeTab: Tab? = null

    // FIXME: Keep history and bookmarks once the Hub offers storage for them. For now, both last as long as the app.
    private val history = mutableListOf<Visit>()
    private val recentlyClosed = mutableListOf<Visit>()

    private lateinit var ui: Ui
    private lateinit var root: FrameLayout
    // The pages live here, and move along to each new activity.
    private val content = FrameLayout(activity.applicationContext)
    private lateinit var tabStrip: LinearLayout
    private lateinit var tabStripScroller: HorizontalScrollView
    private lateinit var navigationRow: LinearLayout
    private lateinit var progress: View
    private lateinit var findBar: LinearLayout
    private var newTabPage: View? = null
    private var overlay: View? = null
    private var progressAnimator: ValueAnimator? = null
    private var shownProgressFor: Tab? = null
    private var wasLoading = false

    init {
        attach(activity)
    }

    fun attach(activity: AppCompatActivity) {
        this.activity = activity
        overlay = null
        root = FrameLayout(activity)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        activity.setContentView(root)
        buildChrome()
    }

    private val isNight: Boolean
        get() = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    // The colours follow the system's dark mode, so the chrome is built again when it changes. The pages stay.
    fun onConfigurationChanged() {
        closeOverlay()
        buildChrome()
    }

    private fun buildChrome() {
        ui = Ui(activity)
        (content.parent as? ViewGroup)?.removeView(content)
        root.removeAllViews()
        root.setBackgroundColor(ui.color(R.color.paper))
        WindowInsetsControllerCompat(activity.window, root).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }

        val column = ui.column()
        column.addView(buildHeader(), lp(MATCH, WRAP))
        findBar = buildFindBar()
        findBar.visibility = View.GONE
        column.addView(findBar, lp(MATCH, WRAP))
        content.setBackgroundColor(ui.color(R.color.surface))
        column.addView(content, lp(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        newTabPage?.let { content.removeView(it) }
        newTabPage = null
        render()
    }

    fun dispose() {
        for (tab in tabs)
            tab.webView?.dispose()
        tabs.clear()
    }

    // Tabs

    fun openTab(url: String?, isPrivate: Boolean) {
        val tab = Tab(isPrivate)
        tabs.add(tab)
        activate(tab)
        if (url != null)
            navigate(tab, url)
    }

    private fun activate(tab: Tab) {
        activeTab?.webView?.let {
            it.setPageVisible(false)
            it.visibility = View.GONE
        }
        activeTab = tab
        tab.webView?.let {
            it.visibility = View.VISIBLE
            it.setPageVisible(true)
            it.requestFocus()
        }
        endFindInPage()
        render()
    }

    private fun closeTab(tab: Tab) {
        if (!tab.isNewTabPage && !tab.isPrivate) {
            recentlyClosed.add(0, Visit(tab.state.url, tab.title))
            if (recentlyClosed.size > 10)
                recentlyClosed.removeAt(recentlyClosed.lastIndex)
            forgetNewTabPage()
        }
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        tab.webView?.let {
            content.removeView(it)
            it.dispose()
        }
        if (tab == activeTab) {
            activeTab = null
            if (tabs.isEmpty())
                openTab(null, isPrivate = false)
            else
                activate(tabs[minOf(index, tabs.lastIndex)])
        }
        render()
    }

    private fun webViewFor(tab: Tab): WebView {
        tab.webView?.let { return it }
        val webView = WebView(activity.applicationContext)
        webView.initialize(tab.isPrivate, isNight)
        webView.onStateChanged = { state -> onPageStateChanged(tab, state) }
        tab.webView = webView
        content.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        webView.visibility = if (tab == activeTab) View.VISIBLE else View.GONE
        webView.setPageVisible(tab == activeTab)
        return webView
    }

    private fun navigate(tab: Tab, input: String) {
        val url = urlFromInput(input) ?: return
        webViewFor(tab).loadURL(url)
        if (tab == activeTab)
            tab.webView?.requestFocus()
        render()
    }

    private fun onPageStateChanged(tab: Tab, state: PageState) {
        if (!state.isLoading && !tab.isPrivate && state.url.startsWith("http")) {
            history.removeAll { it.url == state.url }
            history.add(0, Visit(state.url, state.title.ifBlank { tab.host }))
            if (history.size > 200)
                history.removeAt(history.lastIndex)
        }
        render()
    }

    // Chrome

    private fun buildHeader(): View {
        val header = FrameLayout(activity)
        header.setBackgroundColor(ui.color(R.color.paper))

        val rows = ui.column()
        rows.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(8))

        val stripRow = ui.row()
        tabStrip = ui.row()
        tabStripScroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(tabStrip, FrameLayout.LayoutParams(WRAP, ui.dp(40)))
        }
        stripRow.addView(tabStripScroller, lp(0, ui.dp(40), 1f))
        stripRow.addView(ui.iconButton(R.drawable.ic_plus, 40, 20, "New tab", radius = 12f) { openTab(null, activeTab?.isPrivate ?: false) }, lp(ui.dp(40), ui.dp(40)).margins(left = ui.dp(6)))
        stripRow.addView(ui.iconButton(R.drawable.ic_tabs, 40, 20, "All tabs", radius = 12f) { showTabs() }, lp(ui.dp(40), ui.dp(40)).margins(left = ui.dp(6)))
        rows.addView(stripRow, lp(MATCH, WRAP))

        navigationRow = ui.row()
        rows.addView(navigationRow, lp(MATCH, ui.dp(44)).margins(top = ui.dp(8)))
        header.addView(rows, FrameLayout.LayoutParams(MATCH, WRAP))

        header.addView(ui.line(), FrameLayout.LayoutParams(MATCH, ui.dp(1), Gravity.BOTTOM))
        progress = View(activity).apply {
            setBackgroundColor(ui.color(R.color.accent))
            pivotX = 0f
            scaleX = 0f
            visibility = View.INVISIBLE
        }
        header.addView(progress, FrameLayout.LayoutParams(MATCH, ui.dp(3), Gravity.BOTTOM))
        return header
    }

    private fun render() {
        if (!::tabStrip.isInitialized)
            return
        renderTabStrip()
        renderNavigationRow()
        renderProgress()
        renderNewTabPage()
    }

    private fun renderTabStrip() {
        tabStrip.removeAllViews()
        val active = activeTab
        for (tab in tabs.filter { it.isPrivate == (active?.isPrivate ?: false) }) {
            val isActive = tab == active
            val chip = ui.row()
            chip.setPadding(ui.dp(6), 0, ui.dp(10), 0)
            chip.background = if (isActive) ui.pressable(R.color.surface, 12f, R.color.ink, 1.5f) else ui.pressable(R.color.chip, 12f)
            chip.contentDescription = tab.title
            chip.setOnClickListener { if (isActive) showAddress() else activate(tab) }
            chip.setOnLongClickListener { closeTab(tab); true }

            val initial = ui.text(initialOf(tab), 12f, 700, if (tab.isPrivate) R.color.on_accent else R.color.ink)
            initial.gravity = Gravity.CENTER
            initial.background = ui.rounded(if (tab.isPrivate) R.color.deep else Ui.tileTintFor(tab.siteName), 8f)
            chip.addView(initial, lp(ui.dp(26), ui.dp(26)))
            chip.addView(ui.text(tab.title, 14f, if (isActive) 600 else 500), lp(0, WRAP, 1f).margins(left = ui.dp(8)))

            val params = if (isActive) lp(WRAP, ui.dp(40), 1f).apply { chip.minimumWidth = ui.dp(150) } else lp(ui.dp(96), ui.dp(40))
            if (tabStrip.childCount > 0)
                params.margins(left = ui.dp(6))
            tabStrip.addView(chip, params)
            if (isActive)
                chip.post { tabStripScroller.requestChildRectangleOnScreen(chip, android.graphics.Rect(0, 0, chip.width, chip.height), false) }
        }
    }

    private fun initialOf(tab: Tab): String =
        if (tab.isNewTabPage) "+" else (tab.siteName.firstOrNull { it.isLetterOrDigit() } ?: '•').uppercase()

    private fun renderNavigationRow() {
        navigationRow.removeAllViews()
        val tab = activeTab
        val state = tab?.state

        val back = ui.iconButton(R.drawable.ic_back, 44, 22, "Back") { activeTab?.webView?.goBack() }
        back.isEnabled = state?.canGoBack == true
        back.alpha = if (back.isEnabled) 1f else 0.35f
        navigationRow.addView(back)

        val pill = ui.row()
        pill.setPadding(ui.dp(14), 0, ui.dp(14), 0)
        pill.background = ui.pressable(R.color.chip, 999f)
        pill.setOnClickListener { showAddress() }
        if (tab == null || tab.isNewTabPage) {
            pill.contentDescription = "Search or type address"
            pill.addView(ui.icon(R.drawable.ic_search, 18, R.color.text_secondary))
            pill.addView(ui.text("Search or type address", 16f, 400, R.color.text_secondary), lp(0, WRAP, 1f).margins(left = ui.dp(8)))
        } else {
            val isSecure = tab.state.url.startsWith("https:")
            pill.contentDescription = "Address, ${tab.host}"
            pill.addView(if (isSecure) ui.icon(R.drawable.ic_lock, 14) else ui.icon(R.drawable.ic_info, 16, R.color.deep))
            pill.addView(ui.text(tab.host, 16f, 500), lp(0, WRAP, 1f).margins(left = ui.dp(8)))
        }
        navigationRow.addView(pill, lp(0, ui.dp(44), 1f).margins(left = ui.dp(4), right = ui.dp(4)))

        if (tab != null && !tab.isNewTabPage) {
            val loading = state?.isLoading == true
            navigationRow.addView(ui.iconButton(if (loading) R.drawable.ic_close else R.drawable.ic_reload, 44, 20, if (loading) "Stop" else "Reload") {
                activeTab?.webView?.let { if (it.state.isLoading) it.stopLoading() else it.reload() }
            })
        }
        navigationRow.addView(ui.iconButton(R.drawable.ic_menu, 44, 22, "Menu") { showMenu() })
    }

    // The engine does not report how far a load has come, so the line eases towards the end while the page loads,
    // and finishes once it has.
    private fun renderProgress() {
        val tab = activeTab
        val loading = tab?.state?.isLoading == true
        if (tab != shownProgressFor) {
            shownProgressFor = tab
            wasLoading = false
            progressAnimator?.cancel()
            progress.scaleX = 0f
            progress.visibility = View.INVISIBLE
        }
        if (loading == wasLoading)
            return
        wasLoading = loading
        progressAnimator?.cancel()
        if (loading) {
            progress.visibility = View.VISIBLE
            progress.alpha = 1f
            progress.scaleX = 0.05f
            progressAnimator = ValueAnimator.ofFloat(0.05f, 0.85f).apply {
                duration = 4000
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { progress.scaleX = it.animatedValue as Float }
                start()
            }
        } else {
            progressAnimator = ValueAnimator.ofFloat(progress.scaleX, 1f).apply {
                duration = 200
                addUpdateListener { progress.scaleX = it.animatedValue as Float }
                doOnEnd { progress.animate().alpha(0f).setDuration(200).withEndAction { progress.visibility = View.INVISIBLE }.start() }
                start()
            }
        }
    }

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    // New tab page

    private fun renderNewTabPage() {
        val show = activeTab?.isNewTabPage != false
        if (!show) {
            newTabPage?.visibility = View.GONE
            return
        }
        newTabPage?.let {
            it.visibility = View.VISIBLE
            it.bringToFront()
            return
        }
        val page = buildNewTabPage()
        newTabPage = page
        content.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun buildNewTabPage(): View {
        val column = ui.column()
        column.setBackgroundColor(ui.color(R.color.paper))
        column.setPadding(ui.dp(20), ui.dp(56), ui.dp(20), ui.dp(24))

        val brand = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        brand.addView(ImageView(activity).apply { setImageResource(R.drawable.ladybird_logo); contentDescription = "Ladybird" }, lp(ui.dp(64), ui.dp(64)))
        brand.addView(ui.text("Ladybird", 28f, 700).apply { letterSpacing = -0.02f }, lp(WRAP, WRAP).margins(top = ui.dp(14)))
        column.addView(brand, lp(MATCH, WRAP))

        column.addView(sectionTitle("Favourites"), lp(MATCH, WRAP).margins(top = ui.dp(36), bottom = ui.dp(12)))
        for (rowStart in favourites.indices step 4) {
            val row = ui.row(Gravity.TOP)
            for (favourite in favourites.subList(rowStart, minOf(rowStart + 4, favourites.size))) {
                val item = ui.column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, ui.dp(4), 0, ui.dp(4))
                    background = ui.pressable(null, 16f)
                    contentDescription = favourite.name
                    setOnClickListener { activeTab?.let { navigate(it, favourite.url) } }
                }
                val tile = ui.text(favourite.name.first().toString(), 22f, 700)
                tile.gravity = Gravity.CENTER
                tile.background = ui.rounded(Ui.tileTintFor(Uri.parse(favourite.url).host!!.split('.').let { it[it.size - 2] }), 18f)
                item.addView(tile, lp(ui.dp(56), ui.dp(56)))
                item.addView(ui.text(favourite.name, 13f, 500), lp(WRAP, WRAP).margins(top = ui.dp(8)))
                row.addView(item, lp(0, WRAP, 1f))
            }
            column.addView(row, lp(MATCH, WRAP).margins(top = if (rowStart == 0) 0 else ui.dp(14)))
        }

        if (recentlyClosed.isNotEmpty()) {
            column.addView(sectionTitle("Recently closed"), lp(MATCH, WRAP).margins(top = ui.dp(36), bottom = ui.dp(6)))
            for (visit in recentlyClosed.take(5)) {
                val row = ui.row()
                row.setPadding(ui.dp(12), 0, ui.dp(12), 0)
                row.background = ui.pressable(R.color.surface, 16f, R.color.line)
                row.setOnClickListener {
                    recentlyClosed.remove(visit)
                    forgetNewTabPage()
                    activeTab?.let { navigate(it, visit.url) }
                }
                row.addView(ui.text(visit.title, 15f, 500), lp(0, WRAP, 1f))
                row.addView(ui.text(ago(visit.time), 13f, 400, R.color.text_muted), lp(WRAP, WRAP).margins(left = ui.dp(12)))
                column.addView(row, lp(MATCH, ui.dp(52)).margins(bottom = ui.dp(6)))
            }
        }

        return ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(ui.color(R.color.paper))
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // The page is built again the next time it shows, as what it lists has changed.
    private fun forgetNewTabPage() {
        newTabPage?.let { content.removeView(it) }
        newTabPage = null
    }

    private fun sectionTitle(title: String) = ui.text(title, 15f, 600, R.color.text_secondary).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }

    private fun ago(time: Long): String {
        val minutes = (System.currentTimeMillis() - time) / 60000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} d ago"
        }
    }

    // Overlays: the address, tab overview and menu screens.

    private fun showOverlay(view: View) {
        closeOverlay()
        overlay = view
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun closeOverlay(): Boolean {
        val view = overlay ?: return false
        hideKeyboard(view)
        root.removeView(view)
        overlay = null
        activeTab?.webView?.requestFocus()
        return true
    }

    fun handleBack(): Boolean {
        if (closeOverlay())
            return true
        if (findBar.visibility == View.VISIBLE) {
            endFindInPage()
            return true
        }
        val tab = activeTab ?: return false
        if (tab.state.canGoBack) {
            tab.webView?.goBack()
            return true
        }
        if (tabs.size > 1) {
            closeTab(tab)
            return true
        }
        return false
    }

    // Address screen

    private fun showAddress() {
        val screen = ui.column()
        screen.isClickable = true
        screen.setBackgroundColor(ui.color(R.color.paper))

        val top = ui.row()
        top.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(8))
        val field = ui.row()
        field.setPadding(ui.dp(16), 0, ui.dp(6), 0)
        field.background = ui.rounded(R.color.surface, 999f, R.color.accent, 2f)
        val input = EditText(activity).apply {
            background = null
            setPadding(0, 0, 0, 0)
            textSize = 16f
            typeface = ui.sans(400)
            setTextColor(ui.color(R.color.ink))
            setHintTextColor(ui.color(R.color.text_muted))
            hint = "Search or type address"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            val current = activeTab?.takeUnless { it.isNewTabPage }?.state?.url ?: ""
            setText(current)
            setSelectAllOnFocus(true)
        }
        field.addView(input, lp(0, WRAP, 1f))
        field.addView(ui.iconButton(R.drawable.ic_close, 36, 16, "Clear", R.color.chip) { input.setText("") }, lp(ui.dp(36), ui.dp(36)))
        top.addView(field, lp(0, ui.dp(48), 1f))
        val cancel = ui.text("Cancel", 16f, 600).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            background = ui.pressable(null, 999f)
            setOnClickListener { closeOverlay() }
        }
        top.addView(cancel, lp(WRAP, ui.dp(48)).margins(left = ui.dp(4)))
        screen.addView(top, lp(MATCH, WRAP))
        screen.addView(ui.line(), lp(MATCH, ui.dp(1)))

        val list = ui.column()
        list.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
        val actions = ui.row()
        actions.setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(10))
        actions.addView(actionChip(R.drawable.ic_paste, "Paste link") {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()
            if (text.isNullOrBlank())
                toast("Nothing to paste")
            else
                go(text)
        })
        if (activeTab?.isNewTabPage == false)
            actions.addView(actionChip(R.drawable.ic_share, "Share page") { closeOverlay(); sharePage() }, lp(WRAP, ui.dp(40)).margins(left = ui.dp(8)))
        list.addView(actions, lp(MATCH, WRAP))
        val suggestions = ui.column()
        list.addView(suggestions, lp(MATCH, WRAP))
        screen.addView(ScrollView(activity).apply { addView(list) }, lp(MATCH, 0, 1f))

        fun refresh() {
            suggestions.removeAllViews()
            val query = input.text.toString().trim()
            if (query.isNotEmpty()) {
                suggestions.addView(suggestion(true, query, "Search with DuckDuckGo", input) { go(query) })
                if (looksLikeAddress(query))
                    suggestions.addView(suggestion(true, query, "Go to ${urlFromInput(query)}", input) { go(query) })
            }
            val matches = history.filter { query.isEmpty() || it.url.contains(query, true) || it.title.contains(query, true) }.take(8)
            for (visit in matches)
                suggestions.addView(suggestion(false, visit.title, visit.url.removePrefix("https://").removePrefix("www."), input) { go(visit.url) })
        }
        input.doAfterTextChanged { refresh() }
        input.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                go(view.text.toString())
                true
            } else {
                false
            }
        }
        refresh()

        showOverlay(screen)
        input.requestFocus()
        input.selectAll()
        input.post {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun go(input: String) {
        if (input.isBlank())
            return
        closeOverlay()
        activeTab?.let { navigate(it, input) }
    }

    private fun actionChip(icon: Int, label: String, onClick: () -> Unit): View {
        val chip = ui.row()
        chip.setPadding(ui.dp(14), 0, ui.dp(14), 0)
        chip.background = ui.pressable(R.color.surface, 999f, R.color.line)
        chip.setOnClickListener { onClick() }
        chip.addView(ui.icon(icon, 16))
        chip.addView(ui.text(label, 14f, 500), lp(WRAP, WRAP).margins(left = ui.dp(8)))
        chip.layoutParams = lp(WRAP, ui.dp(40))
        return chip
    }

    private fun suggestion(isSearch: Boolean, title: String, detail: String, input: EditText, onClick: () -> Unit): View {
        val row = ui.row()
        row.minimumHeight = ui.dp(56)
        row.setPadding(ui.dp(12), ui.dp(6), ui.dp(6), ui.dp(6))
        row.background = ui.pressable(null, 16f)
        row.setOnClickListener { onClick() }

        val badge = FrameLayout(activity)
        badge.background = ui.rounded(if (isSearch) R.color.tint else R.color.chip, 12f)
        badge.addView(ui.icon(if (isSearch) R.drawable.ic_search else R.drawable.ic_clock, 18, if (isSearch) R.color.deep else R.color.ink), FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
        row.addView(badge, lp(ui.dp(40), ui.dp(40)))

        val texts = ui.column()
        texts.addView(ui.text(title, 16f, 500), lp(MATCH, WRAP))
        texts.addView(ui.text(detail, 12f, 400, R.color.text_muted, mono = true), lp(MATCH, WRAP).margins(top = ui.dp(2)))
        row.addView(texts, lp(0, WRAP, 1f).margins(left = ui.dp(14)))

        row.addView(ui.iconButton(R.drawable.ic_fill_in, 40, 18, "Edit") {
            input.setText(if (isSearch) title else detail)
            input.setSelection(input.text.length)
        }.apply { (getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.text_muted)) })
        return row
    }

    // Tab overview

    private fun showTabs(showPrivate: Boolean = activeTab?.isPrivate ?: false) {
        val screen = ui.column()
        screen.isClickable = true
        screen.setBackgroundColor(ui.color(R.color.sheet))

        val shown = tabs.filter { it.isPrivate == showPrivate }
        val header = ui.column()
        header.setPadding(ui.dp(16), ui.dp(26), ui.dp(16), ui.dp(14))
        header.addView(ui.text(if (shown.size == 1) "1 tab" else "${shown.size} tabs", 28f, 700).apply { letterSpacing = -0.02f }, lp(MATCH, WRAP))
        val segments = ui.row()
        segments.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        segments.background = ui.rounded(R.color.segment, 999f)
        for ((label, isPrivate) in listOf("Tabs" to false, "Private" to true)) {
            val selected = isPrivate == showPrivate
            val segment = ui.text(label, 15f, 600, if (selected) R.color.ink else R.color.text_secondary).apply {
                gravity = Gravity.CENTER
                background = if (selected) ui.rounded(R.color.segment_selected, 999f) else ui.pressable(null, 999f)
                setOnClickListener { if (!selected) showTabs(isPrivate) }
            }
            segments.addView(segment, lp(0, ui.dp(40), 1f).margins(left = if (isPrivate) ui.dp(4) else 0))
        }
        header.addView(segments, lp(MATCH, WRAP).margins(top = ui.dp(14)))
        screen.addView(header, lp(MATCH, WRAP))

        val grid = ui.column()
        grid.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(12))
        for (rowStart in shown.indices step 2) {
            val row = ui.row(Gravity.TOP)
            for (index in rowStart until minOf(rowStart + 2, shown.size))
                row.addView(tabCard(shown[index]), lp(0, WRAP, 1f).margins(left = if (index % 2 == 1) ui.dp(12) else 0))
            if (row.childCount == 1)
                row.addView(View(activity), lp(0, 0, 1f).margins(left = ui.dp(12)))
            grid.addView(row, lp(MATCH, WRAP).margins(top = if (rowStart == 0) 0 else ui.dp(12)))
        }
        if (shown.isEmpty())
            grid.addView(ui.text(if (showPrivate) "Private tabs keep no history or cookies once closed." else "No tabs", 15f, 400, R.color.text_secondary).apply { maxLines = 3 }, lp(MATCH, WRAP).margins(top = ui.dp(8)))
        screen.addView(ScrollView(activity).apply { addView(grid) }, lp(MATCH, 0, 1f))

        val actions = ui.row()
        actions.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28))
        val newTab = ui.row(Gravity.CENTER).apply {
            background = ui.pressable(R.color.accent, 999f)
            setOnClickListener { closeOverlay(); openTab(null, showPrivate) }
            addView(ui.icon(R.drawable.ic_plus, 20, R.color.on_accent))
            addView(ui.text(if (showPrivate) "New private tab" else "New tab", 16f, 600, R.color.on_accent), lp(WRAP, WRAP).margins(left = ui.dp(8)))
        }
        actions.addView(newTab, lp(0, ui.dp(52), 1f))
        val done = ui.text("Done", 16f, 600).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(22), 0, ui.dp(22), 0)
            background = ui.pressable(null, 999f, R.color.ink, 2f)
            setOnClickListener { closeOverlay() }
        }
        actions.addView(done, lp(WRAP, ui.dp(52)).margins(left = ui.dp(10)))
        screen.addView(actions, lp(MATCH, WRAP))

        showOverlay(screen)
    }

    private fun tabCard(tab: Tab): View {
        val isCurrent = tab == activeTab
        val card = ui.column()
        card.background = if (isCurrent) ui.pressable(R.color.surface, 20f, R.color.accent, 3f) else ui.pressable(R.color.surface, 20f, R.color.card_line)
        card.setOnClickListener { closeOverlay(); activate(tab) }

        val titleRow = ui.row()
        titleRow.setPadding(ui.dp(12), ui.dp(8), ui.dp(6), ui.dp(8))
        titleRow.addView(ui.text(tab.title, 14f, 600), lp(0, WRAP, 1f))
        titleRow.addView(ui.iconButton(R.drawable.ic_close, 32, 14, "Close ${tab.title}") {
            closeTab(tab)
            if (overlay != null)
                showTabs(tab.isPrivate)
        }.apply { (getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.text_muted)) })
        card.addView(titleRow, lp(MATCH, WRAP))

        val thumbnail = FrameLayout(activity)
        thumbnail.background = ui.rounded(if (tab.isPrivate) R.color.tint else Ui.tileTintFor(tab.siteName), 14f)
        thumbnail.clipToOutline = true
        tab.webView?.takeIf { !tab.isNewTabPage }?.snapshot()?.let { bitmap ->
            thumbnail.addView(ImageView(activity).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.MATRIX
                // Show the top of the page, scaled to the width of the card.
                addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
                    val scale = (right - left).toFloat() / bitmap.width
                    (view as ImageView).imageMatrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                }
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val host = ui.text(if (tab.isNewTabPage) "New tab" else tab.host, 11f, 400, R.color.text_secondary, mono = true)
        host.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        host.background = ui.rounded(R.color.surface, 8f)
        thumbnail.addView(host, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(6), 0, 0, ui.dp(6)) })
        card.addView(thumbnail, lp(MATCH, ui.dp(190)).margins(left = ui.dp(6), right = ui.dp(6), bottom = ui.dp(6)))
        return card
    }

    // Menu

    private fun showMenu() {
        val screen = FrameLayout(activity)
        screen.setBackgroundColor(ui.color(R.color.scrim))
        screen.isClickable = true
        screen.setOnClickListener { closeOverlay() }

        val menu = ui.column()
        menu.isClickable = true
        menu.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        menu.background = ui.rounded(R.color.surface, 24f)
        menu.elevation = ui.dp(12).toFloat()

        val tab = activeTab
        val hasPage = tab != null && !tab.isNewTabPage
        val quick = ui.row()
        quick.setPadding(0, 0, 0, ui.dp(8))
        val quickActions = listOf(
            Triple(R.drawable.ic_forward, "Forward", tab?.state?.canGoForward == true) to { tab?.webView?.goForward(); Unit },
            Triple(R.drawable.ic_bookmark, "Bookmark", hasPage) to { toast("Bookmarks are not available yet") },
            Triple(R.drawable.ic_find, "Find in page", hasPage) to { startFindInPage() },
            Triple(R.drawable.ic_share, "Share", hasPage) to { sharePage() },
        )
        for ((index, entry) in quickActions.withIndex()) {
            val (spec, action) = entry
            val button = ui.iconButton(spec.first, 52, 22, spec.second, R.color.chip, 16f) { closeOverlay(); action() }
            button.isEnabled = spec.third
            button.alpha = if (spec.third) 1f else 0.35f
            quick.addView(button, lp(0, ui.dp(52), 1f).margins(left = if (index == 0) 0 else ui.dp(6)))
        }
        menu.addView(quick, lp(MATCH, WRAP))
        menu.addView(ui.line(R.color.divider), lp(MATCH, ui.dp(1)).margins(bottom = ui.dp(4)))

        val rows = listOf(
            Triple(R.drawable.ic_plus, "New tab") { openTab(null, isPrivate = false) },
            Triple(R.drawable.ic_private, "New private tab") { openTab(null, isPrivate = true) },
            Triple(R.drawable.ic_bookmark, "Bookmarks") { toast("Bookmarks are not available yet") },
            Triple(R.drawable.ic_history, "History") { toast("History is not available yet") },
            Triple(R.drawable.ic_download, "Downloads") { toast("Downloads are not available yet") },
            Triple(R.drawable.ic_settings, "Settings") { toast("Settings are not available yet") },
        )
        for ((icon, label, action) in rows)
            menu.addView(menuRow(icon, label) { closeOverlay(); action() }, lp(MATCH, ui.dp(48)))

        menu.addView(ui.line(R.color.divider), lp(MATCH, ui.dp(1)).margins(top = ui.dp(4)))
        val desktop = menuRow(R.drawable.ic_desktop, "Desktop site") { toast("Desktop site is not available yet") }
        val toggle = FrameLayout(activity).apply {
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            background = ui.rounded(R.color.toggle_off, 999f)
            addView(View(activity).apply { background = ui.rounded(R.color.on_accent, 999f) }, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.START))
        }
        desktop.addView(toggle, lp(ui.dp(48), ui.dp(30)))
        menu.addView(desktop, lp(MATCH, ui.dp(48)))

        val headerHeight = (root.getChildAt(0) as ViewGroup).getChildAt(0).height
        screen.addView(menu, FrameLayout.LayoutParams(ui.dp(286), WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = headerHeight + ui.dp(4)
            rightMargin = ui.dp(8)
        })
        showOverlay(screen)
    }

    private fun menuRow(icon: Int, label: String, onClick: () -> Unit): LinearLayout {
        val row = ui.row()
        row.setPadding(ui.dp(10), 0, ui.dp(10), 0)
        row.background = ui.pressable(null, 14f)
        row.setOnClickListener { onClick() }
        row.addView(ui.icon(icon, 20))
        row.addView(ui.text(label, 16f, 500), lp(0, WRAP, 1f).margins(left = ui.dp(14)))
        return row
    }

    // Find in page

    private fun buildFindBar(): LinearLayout {
        val bar = ui.row()
        bar.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        bar.setBackgroundColor(ui.color(R.color.paper))
        val input = EditText(activity).apply {
            background = ui.rounded(R.color.chip, 999f)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            textSize = 16f
            typeface = ui.sans(400)
            setTextColor(ui.color(R.color.ink))
            setHintTextColor(ui.color(R.color.text_muted))
            hint = "Find in page"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            doAfterTextChanged { text -> if (!text.isNullOrEmpty()) activeTab?.webView?.findInPage(text.toString()) }
            setOnEditorActionListener { _, _, _ -> activeTab?.webView?.findInPageNextMatch(); true }
        }
        bar.addView(input, lp(0, ui.dp(44), 1f))
        bar.addView(ui.iconButton(R.drawable.ic_chevron_up, 44, 22, "Previous match") { activeTab?.webView?.findInPagePreviousMatch() })
        bar.addView(ui.iconButton(R.drawable.ic_chevron_down, 44, 22, "Next match") { activeTab?.webView?.findInPageNextMatch() })
        bar.addView(ui.iconButton(R.drawable.ic_close, 44, 20, "Close find in page") { endFindInPage() })
        return bar
    }

    private fun startFindInPage() {
        findBar.visibility = View.VISIBLE
        val input = findBar.getChildAt(0) as EditText
        input.setText("")
        input.requestFocus()
        input.post {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun endFindInPage() {
        if (!::findBar.isInitialized || findBar.visibility != View.VISIBLE)
            return
        hideKeyboard(findBar)
        findBar.visibility = View.GONE
        activeTab?.webView?.findInPageEnd()
    }

    // Helpers

    private fun sharePage() {
        val tab = activeTab?.takeUnless { it.isNewTabPage } ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, tab.state.url)
            putExtra(Intent.EXTRA_SUBJECT, tab.title)
        }
        activity.startActivity(Intent.createChooser(intent, null))
    }

    private fun hideKeyboard(view: View) {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    companion object {
        private data class Favourite(val name: String, val url: String)

        // FIXME: Let the user pick these once there are bookmarks.
        private val favourites = listOf(
            Favourite("Ladybird", "https://ladybird.org"),
            Favourite("Wikipedia", "https://en.wikipedia.org"),
            Favourite("DuckDuckGo", "https://duckduckgo.com"),
            Favourite("GitHub", "https://github.com"),
            Favourite("Hacker News", "https://news.ycombinator.com"),
            Favourite("YouTube", "https://www.youtube.com"),
            Favourite("Reddit", "https://www.reddit.com"),
            Favourite("Maps", "https://www.openstreetmap.org"),
        )

        // FIXME: Use the search engine from Ladybird's settings.
        private const val SEARCH_URL = "https://duckduckgo.com/?q="

        private val schemeRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://|(about|data|file|javascript|mailto):)")
        private val hostRegex = Regex("^(localhost|[^\\s/]+\\.[^\\s/]{2,}|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$")

        fun looksLikeAddress(input: String): Boolean = schemeRegex.containsMatchIn(input) || hostRegex.matches(input)

        // What the user typed, as an address to load: the address itself, or a search for it.
        fun urlFromInput(input: String): String? {
            val text = input.trim()
            if (text.isEmpty())
                return null
            if (text.contains(' ') || !looksLikeAddress(text))
                return SEARCH_URL + Uri.encode(text)
            if (schemeRegex.containsMatchIn(text))
                return text
            return "https://$text"
        }
    }
}
