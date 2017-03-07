/*
 * 				Twidere - Twitter client for Android
 *
 *  Copyright (C) 2012-2014 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.activity

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OnAccountsUpdateListener
import android.app.Dialog
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.os.Bundle
import android.preference.PreferenceActivity
import android.support.annotation.StringRes
import android.support.v4.app.Fragment
import android.support.v4.app.NotificationCompat
import android.support.v4.view.GravityCompat
import android.support.v4.view.ViewPager.OnPageChangeListener
import android.support.v4.widget.DrawerLayout
import android.support.v4.widget.DrawerLayoutAccessor
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatDelegate
import android.support.v7.widget.TintTypedArray
import android.util.SparseIntArray
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.View.OnClickListener
import android.view.View.OnLongClickListener
import android.view.ViewGroup.MarginLayoutParams
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetView
import com.squareup.otto.Subscribe
import kotlinx.android.synthetic.main.activity_home.*
import kotlinx.android.synthetic.main.activity_home_content.*
import kotlinx.android.synthetic.main.layout_empty_tab_hint.*
import org.mariotaku.abstask.library.AbstractTask
import org.mariotaku.abstask.library.TaskStarter
import org.mariotaku.chameleon.ChameleonUtils
import org.mariotaku.kpreferences.get
import org.mariotaku.kpreferences.set
import org.mariotaku.ktextension.addOnAccountsUpdatedListenerSafe
import org.mariotaku.ktextension.coerceInOr
import org.mariotaku.ktextension.contains
import org.mariotaku.ktextension.removeOnAccountsUpdatedListenerSafe
import org.mariotaku.twidere.Constants.*
import org.mariotaku.twidere.R
import org.mariotaku.twidere.activity.iface.IControlBarActivity.ControlBarShowHideHelper
import org.mariotaku.twidere.adapter.SupportTabsAdapter
import org.mariotaku.twidere.annotation.CustomTabType
import org.mariotaku.twidere.annotation.ReadPositionTag
import org.mariotaku.twidere.constant.*
import org.mariotaku.twidere.extension.applyTheme
import org.mariotaku.twidere.fragment.AccountsDashboardFragment
import org.mariotaku.twidere.fragment.BaseDialogFragment
import org.mariotaku.twidere.fragment.CustomTabsFragment
import org.mariotaku.twidere.fragment.iface.IFloatingActionButtonFragment
import org.mariotaku.twidere.fragment.iface.RefreshScrollTopInterface
import org.mariotaku.twidere.fragment.iface.SupportFragmentCallback
import org.mariotaku.twidere.graphic.EmptyDrawable
import org.mariotaku.twidere.model.AccountDetails
import org.mariotaku.twidere.model.SupportTabSpec
import org.mariotaku.twidere.model.Tab
import org.mariotaku.twidere.model.UserKey
import org.mariotaku.twidere.model.event.UnreadCountUpdatedEvent
import org.mariotaku.twidere.provider.TwidereDataStore.Activities
import org.mariotaku.twidere.provider.TwidereDataStore.Statuses
import org.mariotaku.twidere.service.StreamingService
import org.mariotaku.twidere.util.*
import org.mariotaku.twidere.util.KeyboardShortcutsHandler.KeyboardShortcutCallback
import org.mariotaku.twidere.view.HomeDrawerLayout
import org.mariotaku.twidere.view.TabPagerIndicator

class HomeActivity : BaseActivity(), OnClickListener, OnPageChangeListener, SupportFragmentCallback,
        OnLongClickListener, DrawerLayout.DrawerListener {

    private val accountUpdatedListener = AccountUpdatedListener(this)

    private var selectedAccountToSearch: AccountDetails? = null

    private lateinit var multiSelectHandler: MultiSelectEventHandler
    private lateinit var pagerAdapter: SupportTabsAdapter
    private lateinit var drawerToggle: ActionBarDrawerToggle

    private var propertiesInitialized = false

    private var updateUnreadCountTask: UpdateUnreadCountTask? = null
    private val readStateChangeListener = OnSharedPreferenceChangeListener { sharedPreferences, key -> updateUnreadCount() }
    private val controlBarShowHideHelper = ControlBarShowHideHelper(this)

    private val homeDrawerToggleDelegate = object : ActionBarDrawerToggle.Delegate {
        override fun setActionBarUpIndicator(upDrawable: Drawable, @StringRes contentDescRes: Int) {
            drawerToggleButton.setImageDrawable(upDrawable)
            drawerToggleButton.setColorFilter(ChameleonUtils.getColorDependent(overrideTheme.colorToolbar))
            drawerToggleButton.contentDescription = getString(contentDescRes)
        }

        override fun setActionBarDescription(@StringRes contentDescRes: Int) {
            drawerToggleButton.contentDescription = getString(contentDescRes)
        }

        override fun getThemeUpIndicator(): Drawable {
            val a = TintTypedArray.obtainStyledAttributes(actionBarThemedContext, null,
                    HOME_AS_UP_ATTRS)
            val result = a.getDrawable(0)
            a.recycle()
            return result
        }

        override fun getActionBarThemedContext(): Context {
            return toolbar.context
        }

        override fun isNavigationVisible(): Boolean {
            return true
        }
    }

    override val controlBarHeight: Int
        get() {
            return mainTabs.height - mainTabs.stripHeight
        }

    fun closeAccountsDrawer() {
        if (homeMenu == null) return
        homeMenu.closeDrawers()
    }

    private val activatedAccountKeys: Array<UserKey>
        get() = DataStoreUtils.getActivatedAccountKeys(this)

    override val currentVisibleFragment: Fragment?
        get() {
            val currentItem = mainPager.currentItem
            if (currentItem < 0 || currentItem >= pagerAdapter.count) return null
            return pagerAdapter.instantiateItem(mainPager, currentItem)
        }

    override fun triggerRefresh(position: Int): Boolean {
        val f = pagerAdapter.instantiateItem(mainPager, position)
        if (f.activity == null || f.isDetached) return false
        if (f !is RefreshScrollTopInterface) return false
        return f.triggerRefresh()
    }

    val leftDrawerFragment: Fragment?
        get() = supportFragmentManager.findFragmentById(R.id.leftDrawer)

    private val isDrawerOpen: Boolean
        get() {
            val drawer = homeMenu ?: return false
            return drawer.isDrawerOpen(GravityCompat.START) || drawer.isDrawerOpen(GravityCompat.END)
        }

    /**
     * Called when the context is first created.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        multiSelectHandler = MultiSelectEventHandler(this)
        multiSelectHandler.dispatchOnCreate()
        DataStoreUtils.prepareDatabase(this)
        if (!DataStoreUtils.hasAccount(this)) {
            val signInIntent = Intent(INTENT_ACTION_TWITTER_LOGIN)
            signInIntent.setClass(this, SignInActivity::class.java)
            startActivity(signInIntent)
            finish()
            if (defaultAutoRefreshAskedKey !in kPreferences) {
                // Assume first install
                kPreferences[defaultAutoRefreshAskedKey] = false
            }
            return
        } else {
            notifyAccountsChanged()
        }
        supportRequestWindowFeature(AppCompatDelegate.FEATURE_ACTION_MODE_OVERLAY)
        setContentView(R.layout.activity_home)
        setSupportActionBar(toolbar)

        drawerToggle = ActionBarDrawerToggle(this, homeMenu, R.string.open_accounts_dashboard,
                R.string.close_accounts_dashboard)
        pagerAdapter = SupportTabsAdapter(this, supportFragmentManager, mainTabs)
        propertiesInitialized = true

        ThemeUtils.setCompatContentViewOverlay(window, EmptyDrawable())

        val refreshOnStart = preferences.getBoolean(SharedPreferenceConstants.KEY_REFRESH_ON_START, false)
        var tabDisplayOptionInt = Utils.getTabDisplayOptionInt(this)

        homeContent.setOnFitSystemWindowsListener(this)
        mainPager.adapter = pagerAdapter
        mainTabs.setViewPager(mainPager)
        mainTabs.setOnPageChangeListener(this)
        if (tabDisplayOptionInt == 0) {
            tabDisplayOptionInt = TabPagerIndicator.DisplayOption.ICON
        }
        mainTabs.setTabDisplayOption(tabDisplayOptionInt)
        mainTabs.setTabExpandEnabled(TabPagerIndicator.DisplayOption.LABEL !in tabDisplayOptionInt)
        mainTabs.setDisplayBadge(preferences[unreadCountKey])
        mainTabs.updateAppearance()

        if (preferences[drawerToggleKey]) {
            drawerToggleButton.visibility = View.VISIBLE
        } else {
            drawerToggleButton.visibility = View.GONE
        }

        if (preferences[fabVisibleKey]) {
            actionsButton.visibility = View.VISIBLE
        } else {
            actionsButton.visibility = View.GONE
        }

        homeContent.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (top != oldTop) {
                val fragment = leftDrawerFragment
                if (fragment is AccountsDashboardFragment) {
                    fragment.setStatusBarHeight(top)
                }
            }
        }

        actionsButton.setOnClickListener(this)
        actionsButton.setOnLongClickListener(this)
        drawerToggleButton.setOnClickListener(this)
        emptyTabHint.setOnClickListener(this)

        setupSlidingMenu()
        setupBars()
        showDataProfilingRequest()
        initUnreadCount()
        setupHomeTabs()
        updateActionsButton()

        if (savedInstanceState == null) {
            if (refreshOnStart) {
                twitterWrapper.refreshAll(activatedAccountKeys)
            }
            if (intent.getBooleanExtra(EXTRA_OPEN_ACCOUNTS_DRAWER, false)) {
                openAccountsDrawer()
            }
        }

        val initialTabPosition = handleIntent(intent, savedInstanceState == null)
        setTabPosition(initialTabPosition)

        if (Utils.isStreamingEnabled()) {
            startService(Intent(this, StreamingService::class.java))
        }

        if (!showDrawerTutorial() && !kPreferences[defaultAutoRefreshAskedKey]) {
            showAutoRefreshConfirm()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Sync the toggle state after onRestoreInstanceState has occurred.
        drawerToggle.syncState()
    }

    override fun onStart() {
        super.onStart()
        multiSelectHandler.dispatchOnStart()
        AccountManager.get(this).addOnAccountsUpdatedListenerSafe(accountUpdatedListener, updateImmediately = false)
        bus.register(this)

        readStateManager.registerOnSharedPreferenceChangeListener(readStateChangeListener)
        updateUnreadCount()
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
        updateActionsButton()
    }

    override fun onStop() {
        multiSelectHandler.dispatchOnStop()
        readStateManager.unregisterOnSharedPreferenceChangeListener(readStateChangeListener)
        bus.unregister(this)
        AccountManager.get(this).removeOnAccountsUpdatedListenerSafe(accountUpdatedListener)
        preferences.edit().putInt(SharedPreferenceConstants.KEY_SAVED_TAB_POSITION, mainPager.currentItem).apply()

        super.onStop()
    }

    override fun onDestroy() {
        stopService(Intent(this, StreamingService::class.java))

        // Delete unused items in databases.

        val context = applicationContext
        TaskStarter.execute(object : AbstractTask<Any?, Any?, Any?>() {
            override fun doLongOperation(params: Any?): Any? {
                DataStoreUtils.cleanDatabasesByItemLimit(context)
                return null
            }
        })
        super.onDestroy()
    }

    override fun onAttachFragment(fragment: Fragment?) {
        super.onAttachFragment(fragment)
        updateActionsButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onClick(v: View) {
        when (v) {
            actionsButton -> {
                triggerActionsClick()
            }
            emptyTabHint -> {
                val intent = Intent(this, SettingsActivity::class.java)
                intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, CustomTabsFragment::class.java.name)
                intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT_TITLE, R.string.tabs)
                startActivityForResult(intent, REQUEST_SETTINGS)
            }
            drawerToggleButton -> {
                if (homeMenu.isDrawerOpen(GravityCompat.START) || homeMenu.isDrawerOpen(GravityCompat.END)) {
                    homeMenu.closeDrawers()
                } else {
                    homeMenu.openDrawer(GravityCompat.START)
                }
            }
        }
    }

    override fun onLongClick(v: View): Boolean {
        when (v) {
            actionsButton -> {
                Utils.showMenuItemToast(v, v.contentDescription, true)
                return true
            }
        }
        return false
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
    }

    override fun onPageSelected(position: Int) {
        //TODO handle secondary drawer
        if (homeMenu.isDrawerOpen(GravityCompat.START)) {
            homeMenu.closeDrawers()
        }
        updateActionsButton()
    }

    override fun onPageScrollStateChanged(state: Int) {
        setControlBarVisibleAnimate(true)
    }

    override fun onSearchRequested(): Boolean {
        startActivity(Intent(this, QuickSearchBarActivity::class.java))
        return true
    }

    override fun onFitSystemWindows(insets: Rect) {
        super.onFitSystemWindows(insets)
        val fragment = leftDrawerFragment
        if (fragment is AccountsDashboardFragment) {
            fragment.requestFitSystemWindows()
        }
    }

    override fun onNewIntent(intent: Intent) {
        val tabPosition = handleIntent(intent, false)
        if (tabPosition >= 0) {
            mainPager.currentItem = tabPosition.coerceInOr(0 until pagerAdapter.count, 0)
        }
    }

    override fun getSystemWindowsInsets(insets: Rect): Boolean {
        if (mainTabs == null || homeContent == null) return false
        val height = mainTabs.height
        if (height != 0) {
            insets.top = height
        } else {
            insets.top = ThemeUtils.getActionBarHeight(this)
        }
        return true
    }

    override fun setControlBarVisibleAnimate(visible: Boolean,
            listener: ControlBarShowHideHelper.ControlBarAnimationListener?) {
        controlBarShowHideHelper.setControlBarVisibleAnimate(visible, listener)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        when (item.itemId) {
            android.R.id.home -> {
                val fm = supportFragmentManager
                val count = fm.backStackEntryCount
                if (homeMenu.isDrawerOpen(GravityCompat.START) || homeMenu.isDrawerOpen(GravityCompat.END)) {
                    homeMenu.closeDrawers()
                    return true
                } else if (count == 0) {
                    homeMenu.openDrawer(GravityCompat.START)
                    return true
                }
                return true
            }
            R.id.search -> {
                openSearchView(selectedAccountToSearch)
                return true
            }
            R.id.actions -> {
                triggerActionsClick()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun handleKeyboardShortcutSingle(handler: KeyboardShortcutsHandler, keyCode: Int,
            event: KeyEvent, metaState: Int): Boolean {
        if (handleFragmentKeyboardShortcutSingle(handler, keyCode, event, metaState)) return true
        var action = handler.getKeyAction(KeyboardShortcutConstants.CONTEXT_TAG_HOME, keyCode, event, metaState)
        if (action != null) {
            when (action) {
                KeyboardShortcutConstants.ACTION_HOME_ACCOUNTS_DASHBOARD -> {
                    if (homeMenu.isDrawerOpen(GravityCompat.START)) {
                        homeMenu.closeDrawers()
                    } else {
                        homeMenu.openDrawer(GravityCompat.START)
                        setControlBarVisibleAnimate(true)
                    }
                    return true
                }
            }
        }
        action = handler.getKeyAction(KeyboardShortcutConstants.CONTEXT_TAG_NAVIGATION, keyCode, event, metaState)
        if (action != null) {
            when (action) {
                KeyboardShortcutConstants.ACTION_NAVIGATION_PREVIOUS_TAB -> {
                    val previous = mainPager.currentItem - 1
                    if (previous < 0 && DrawerLayoutAccessor.findDrawerWithGravity(homeMenu, Gravity.START) != null) {
                        homeMenu.openDrawer(GravityCompat.START)
                        setControlBarVisibleAnimate(true)
                    } else if (previous < pagerAdapter.count) {
                        if (homeMenu.isDrawerOpen(GravityCompat.END)) {
                            homeMenu.closeDrawers()
                        } else {
                            mainPager.setCurrentItem(previous, true)
                        }
                    }
                    return true
                }
                KeyboardShortcutConstants.ACTION_NAVIGATION_NEXT_TAB -> {
                    val next = mainPager.currentItem + 1
                    if (next >= pagerAdapter.count && DrawerLayoutAccessor.findDrawerWithGravity(homeMenu, Gravity.END) != null) {
                        homeMenu.openDrawer(GravityCompat.END)
                        setControlBarVisibleAnimate(true)
                    } else if (next >= 0) {
                        if (homeMenu.isDrawerOpen(GravityCompat.START)) {
                            homeMenu.closeDrawers()
                        } else {
                            mainPager.setCurrentItem(next, true)
                        }
                    }
                    return true
                }
            }
        }
        return handler.handleKey(this, null, keyCode, event, metaState)
    }

    override fun isKeyboardShortcutHandled(handler: KeyboardShortcutsHandler, keyCode: Int,
            event: KeyEvent, metaState: Int): Boolean {
        if (isFragmentKeyboardShortcutHandled(handler, keyCode, event, metaState)) return true
        return super.isKeyboardShortcutHandled(handler, keyCode, event, metaState)
    }

    override fun handleKeyboardShortcutRepeat(handler: KeyboardShortcutsHandler, keyCode: Int,
            repeatCount: Int, event: KeyEvent, metaState: Int): Boolean {
        if (handleFragmentKeyboardShortcutRepeat(handler, keyCode, repeatCount, event, metaState))
            return true
        return super.handleKeyboardShortcutRepeat(handler, keyCode, repeatCount, event, metaState)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (isDrawerOpen) {
                    homeMenu.closeDrawers()
                } else {
                    homeMenu.openDrawer(GravityCompat.START)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isDrawerOpen) {
                    homeMenu.closeDrawers()
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }


    fun notifyAccountsChanged() {
    }

    @Subscribe
    fun notifyUnreadCountUpdated(event: UnreadCountUpdatedEvent) {
        updateUnreadCount()
    }

    fun openSearchView(account: AccountDetails?) {
        selectedAccountToSearch = account
        onSearchRequested()
    }

    fun updateUnreadCount() {
        if (mainTabs == null || updateUnreadCountTask != null && updateUnreadCountTask!!.status == AsyncTask.Status.RUNNING)
            return
        updateUnreadCountTask = UpdateUnreadCountTask(this, preferences, readStateManager, mainTabs,
                pagerAdapter.tabs.toTypedArray())
        AsyncTaskUtils.executeTask<UpdateUnreadCountTask, Any>(updateUnreadCountTask)
        mainTabs.setDisplayBadge(preferences.getBoolean(SharedPreferenceConstants.KEY_UNREAD_COUNT, true))
    }

    val tabs: List<SupportTabSpec>
        get() = pagerAdapter.tabs

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Pass any configuration change to the drawer toggle
        drawerToggle.onConfigurationChanged(newConfig)
    }

    override var controlBarOffset: Float
        get() {
            if (mainTabs.columns > 1) {
                val lp = actionsButton.layoutParams
                val total: Float
                if (lp is MarginLayoutParams) {
                    total = (lp.bottomMargin + actionsButton.height).toFloat()
                } else {
                    total = actionsButton.height.toFloat()
                }
                return 1 - actionsButton.translationY / total
            }
            val totalHeight = controlBarHeight.toFloat()
            return 1 + toolbar.translationY / totalHeight
        }
        set(offset) {
            val translationY = if (mainTabs.columns > 1) 0 else (controlBarHeight * (offset - 1)).toInt()
            toolbar.translationY = translationY.toFloat()
            windowOverlay.translationY = translationY.toFloat()
            val lp = actionsButton.layoutParams
            if (lp is MarginLayoutParams) {
                actionsButton.translationY = (lp.bottomMargin + actionsButton.height) * (1 - offset)
            } else {
                actionsButton.translationY = actionsButton.height * (1 - offset)
            }
            notifyControlBarOffsetChanged()
        }

    override fun onDrawerSlide(drawerView: View, slideOffset: Float) {

    }

    override fun onDrawerOpened(drawerView: View) {

    }

    override fun onDrawerClosed(drawerView: View) {

    }

    override fun onDrawerStateChanged(newState: Int) {
        val fragment = leftDrawerFragment
        if (newState == DrawerLayout.STATE_DRAGGING && fragment is AccountsDashboardFragment) {
            fragment.loadAccounts()
        }
    }

    override fun getDrawerToggleDelegate(): ActionBarDrawerToggle.Delegate? {
        return homeDrawerToggleDelegate
    }

    private val keyboardShortcutRecipient: Fragment?
        get() {
            if (homeMenu.isDrawerOpen(GravityCompat.START)) {
                return leftDrawerFragment
            } else if (homeMenu.isDrawerOpen(GravityCompat.END)) {
                return null
            } else {
                return currentVisibleFragment
            }
        }

    private fun handleFragmentKeyboardShortcutRepeat(handler: KeyboardShortcutsHandler,
            keyCode: Int, repeatCount: Int,
            event: KeyEvent, metaState: Int): Boolean {
        val fragment = keyboardShortcutRecipient
        if (fragment is KeyboardShortcutCallback) {
            return fragment.handleKeyboardShortcutRepeat(handler, keyCode,
                    repeatCount, event, metaState)
        }
        return false
    }

    private fun handleFragmentKeyboardShortcutSingle(handler: KeyboardShortcutsHandler,
            keyCode: Int, event: KeyEvent,
            metaState: Int): Boolean {
        val fragment = keyboardShortcutRecipient
        if (fragment is KeyboardShortcutCallback) {
            return fragment.handleKeyboardShortcutSingle(handler, keyCode,
                    event, metaState)
        }
        return false
    }

    private fun isFragmentKeyboardShortcutHandled(handler: KeyboardShortcutsHandler,
            keyCode: Int, event: KeyEvent, metaState: Int): Boolean {
        val fragment = keyboardShortcutRecipient
        if (fragment is KeyboardShortcutCallback) {
            return fragment.isKeyboardShortcutHandled(handler, keyCode,
                    event, metaState)
        }
        return false
    }

    private fun handleIntent(intent: Intent, handleExtraIntent: Boolean): Int {
        // use package's class loader to prevent BadParcelException
        intent.setExtrasClassLoader(classLoader)
        // reset intent
        setIntent(Intent(this, HomeActivity::class.java))
        val action = intent.action
        if (Intent.ACTION_SEARCH == action) {
            val query = intent.getStringExtra(SearchManager.QUERY)
            val appSearchData = intent.getBundleExtra(SearchManager.APP_DATA)
            val accountKey: UserKey?
            if (appSearchData != null && appSearchData.containsKey(EXTRA_ACCOUNT_KEY)) {
                accountKey = appSearchData.getParcelable<UserKey>(EXTRA_ACCOUNT_KEY)
            } else {
                accountKey = Utils.getDefaultAccountKey(this)
            }
            IntentUtils.openSearch(this, accountKey, query)
            return -1
        }
        val refreshOnStart = preferences.getBoolean(SharedPreferenceConstants.KEY_REFRESH_ON_START, false)
        if (handleExtraIntent && refreshOnStart) {
            twitterWrapper.refreshAll()
        }
        val extraIntent = intent.getParcelableExtra<Intent>(EXTRA_EXTRA_INTENT)

        val uri = intent.data
        @CustomTabType
        val tabType = if (uri != null) Utils.matchTabType(uri) else null
        var initialTab = -1
        if (tabType != null) {
            val accountKey = uri?.getQueryParameter(QUERY_PARAM_ACCOUNT_KEY)?.let(UserKey::valueOf)
            val adapter = pagerAdapter
            for (i in 0 until adapter.count) {
                val tab = adapter.getTab(i)
                if (tabType == Tab.getTypeAlias(tab.type)) {
                    val args = tab.args
                    if (args != null && CustomTabUtils.hasAccountId(this, args,
                            activatedAccountKeys, accountKey)) {
                        initialTab = i
                        break
                    }
                }
            }
            if (initialTab == -1 && (extraIntent == null || !handleExtraIntent)) {
                // Tab not found, open account specific page
                when (tabType) {
                    CustomTabType.NOTIFICATIONS_TIMELINE -> {
                        IntentUtils.openInteractions(this, accountKey)
                        return -1
                    }
                    CustomTabType.DIRECT_MESSAGES -> {
                        IntentUtils.openDirectMessages(this, accountKey)
                        return -1
                    }
                }
            }
        }
        if (extraIntent != null && handleExtraIntent) {
            extraIntent.setExtrasClassLoader(classLoader)
            startActivity(extraIntent)
        }
        return initialTab
    }

    private fun initUnreadCount() {
        for (i in 0 until mainTabs.count) {
            mainTabs.setBadge(i, 0)
        }
    }

    private fun openAccountsDrawer() {
        if (homeMenu == null) return
        homeMenu.openDrawer(GravityCompat.START)
    }

    private fun showDrawerTutorial(): Boolean {
        if (preferences[drawerTutorialCompleted]) return false
        val targetSize = resources.getDimensionPixelSize(R.dimen.element_size_mlarge)
        val height = resources.displayMetrics.heightPixels
        val listener: TapTargetView.Listener = object : TapTargetView.Listener() {
            override fun onTargetClick(view: TapTargetView?) {
                if (!homeMenu.isDrawerOpen(GravityCompat.START)) {
                    homeMenu.openDrawer(GravityCompat.START)
                }
                super.onTargetClick(view)
            }

            override fun onTargetDismissed(view: TapTargetView?, userInitiated: Boolean) {
                preferences[drawerTutorialCompleted] = true
                showAutoRefreshConfirm()

            }
        }
        val target = Rect(0, 0, targetSize, targetSize)
        target.offsetTo(0, height / 2 - targetSize / 2)
        TapTargetView.showFor(this, TapTarget.forBounds(target,
                getString(R.string.hint_accounts_dashboard_title),
                getString(R.string.hint_accounts_dashboard_message))
                .apply {
                    outerCircleColor(R.color.branding_color)
                    dimColor(android.R.color.black)
                }, listener)

        return true
    }

    private fun showAutoRefreshConfirm() {
        if (isFinishing) return
        executeAfterFragmentResumed { activity ->
            val df = AutoRefreshConfirmDialogFragment()
            df.show(activity.supportFragmentManager, "auto_refresh_confirm")
        }
    }

    private fun setTabPosition(initialTab: Int) {
        val rememberPosition = preferences.getBoolean(SharedPreferenceConstants.KEY_REMEMBER_POSITION, true)
        if (initialTab >= 0) {
            mainPager.currentItem = initialTab.coerceInOr(0 until pagerAdapter.count, 0)
        } else if (rememberPosition) {
            val position = preferences.getInt(SharedPreferenceConstants.KEY_SAVED_TAB_POSITION, 0)
            mainPager.currentItem = position.coerceInOr(0 until pagerAdapter.count, 0)
        }
    }

    private fun setupBars() {
        val backgroundOption = currentThemeBackgroundOption
        val isTransparent = ThemeUtils.isTransparentBackground(backgroundOption)
        val actionBarAlpha = if (isTransparent) ThemeUtils.getActionBarAlpha(ThemeUtils.getUserThemeBackgroundAlpha(this)) else 0xFF
        actionsButton.alpha = actionBarAlpha / 255f
    }

    private fun setupHomeTabs() {
        pagerAdapter.clear()
        pagerAdapter.addTabs(CustomTabUtils.getHomeTabs(this))
        val hasNoTab = pagerAdapter.count == 0
        emptyTabHint.visibility = if (hasNoTab) View.VISIBLE else View.GONE
        mainPager.visibility = if (hasNoTab) View.GONE else View.VISIBLE
        if (pagerAdapter.count > 1 && hasMultiColumns()) {
            mainPager.pageMargin = resources.getDimensionPixelOffset(R.dimen.home_page_margin)
            mainPager.setPageMarginDrawable(ThemeUtils.getDrawableFromThemeAttribute(this, R.attr.dividerVertical))
            pagerAdapter.hasMultipleColumns = true
            pagerAdapter.preferredColumnWidth = when (preferences[multiColumnWidthKey]) {
                "narrow" -> resources.getDimension(R.dimen.preferred_tab_column_width_narrow)
                "wide" -> resources.getDimension(R.dimen.preferred_tab_column_width_wide)
                else -> resources.getDimension(R.dimen.preferred_tab_column_width_normal)
            }
            mainTabs.columns = Math.floor(1.0 / pagerAdapter.getPageWidth(0)).toInt()
        } else {
            mainPager.pageMargin = 0
            mainPager.setPageMarginDrawable(null)
            pagerAdapter.hasMultipleColumns = false
            mainTabs.columns = 1
        }
    }

    private fun setupSlidingMenu() {
        homeMenu.setDrawerShadow(R.drawable.drawer_shadow_start, GravityCompat.START)
        homeMenu.addDrawerListener(drawerToggle)
        homeMenu.addDrawerListener(this)
        homeMenu.setShouldDisableDecider(HomeDrawerLayout.ShouldDisableDecider { e ->
            val fragment = leftDrawerFragment
            if (fragment is AccountsDashboardFragment) {
                return@ShouldDisableDecider fragment.shouldDisableDrawerSlide(e)
            }
            false
        })
    }

    private fun showDataProfilingRequest() {
        //spice
        if (preferences.contains(KEY_USAGE_STATISTICS)) {
            return
        }
        val intent = Intent(this, UsageStatisticsActivity::class.java)
        val contentIntent = PendingIntent.getActivity(this, 0, intent, 0)
        val builder = NotificationCompat.Builder(this)
        builder.setAutoCancel(true)
        builder.setSmallIcon(R.drawable.ic_stat_info)
        builder.setTicker(getString(R.string.usage_statistics))
        builder.setContentTitle(getString(R.string.usage_statistics))
        builder.setContentText(getString(R.string.usage_statistics_notification_summary))
        builder.setContentIntent(contentIntent)
        notificationManager.notify(NOTIFICATION_ID_DATA_PROFILING, builder.build())
    }

    private fun triggerActionsClick() {
        val position = mainPager.currentItem
        if (pagerAdapter.count == 0) return
        val fragment = pagerAdapter.instantiateItem(mainPager, position) as? IFloatingActionButtonFragment
        val handled = fragment?.onActionClick("home") ?: false
        if (!handled) {
            startActivity(Intent(INTENT_ACTION_COMPOSE))
        }
    }

    private fun updateActionsButton() {
        if (!propertiesInitialized) return
        val fragment = run {
            if (pagerAdapter.count == 0) return@run null
            val position = mainPager.currentItem
            val f = pagerAdapter.instantiateItem(mainPager, position) as? IFloatingActionButtonFragment
            if (f is Fragment && (f.isDetached || f.host == null)) {
                return@run null
            }
            return@run f
        }
        val info = fragment?.getActionInfo("home") ?: run {
            actionsButton.setImageResource(R.drawable.ic_action_status_compose)
            actionsButton.contentDescription = getString(R.string.action_compose)
            return
        }

        actionsButton.setImageResource(info.icon)
        actionsButton.contentDescription = info.title
    }


    fun hasMultiColumns(): Boolean {
        if (!Utils.isDeviceTablet(this) || !Utils.isScreenTablet(this)) return false
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return preferences.getBoolean("multi_column_tabs_landscape", resources.getBoolean(R.bool.default_multi_column_tabs_land))
        }
        return preferences.getBoolean("multi_column_tabs_portrait", resources.getBoolean(R.bool.default_multi_column_tabs_port))
    }


    private class AccountUpdatedListener(private val activity: HomeActivity) : OnAccountsUpdateListener {

        override fun onAccountsUpdated(accounts: Array<out Account>?) {
            activity.notifyAccountsChanged()
            activity.updateUnreadCount()
        }
    }

    private class UpdateUnreadCountTask(
            private val context: Context,
            private val preferences: SharedPreferences,
            private val readStateManager: ReadStateManager,
            private val indicator: TabPagerIndicator,
            private val tabs: Array<SupportTabSpec>
    ) : AsyncTask<Any, UpdateUnreadCountTask.TabBadge, SparseIntArray>() {

        private val activatedKeys = DataStoreUtils.getActivatedAccountKeys(context)

        override fun doInBackground(vararg params: Any): SparseIntArray {
            val result = SparseIntArray()
            tabs.forEachIndexed { i, spec ->
                if (spec.type == null) {
                    publishProgress(TabBadge(i, -1))
                    return@forEachIndexed
                }
                when (spec.type) {
                    CustomTabType.HOME_TIMELINE -> {
                        val accountKeys = Utils.getAccountKeys(context, spec.args) ?: activatedKeys
                        val position = accountKeys.map {
                            val tag = Utils.getReadPositionTagWithAccount(ReadPositionTag.HOME_TIMELINE, it)
                            readStateManager.getPosition(tag)
                        }.fold(0L, Math::max)
                        val count = DataStoreUtils.getStatusesCount(context, preferences,
                                Statuses.CONTENT_URI, spec.args, position, Statuses.STATUS_TIMESTAMP,
                                true, accountKeys)
                        result.put(i, count)
                        publishProgress(TabBadge(i, count))
                    }
                    CustomTabType.NOTIFICATIONS_TIMELINE -> {
                        val accountKeys = Utils.getAccountKeys(context, spec.args) ?: activatedKeys
                        val position = accountKeys.map {
                            val tag = Utils.getReadPositionTagWithAccount(ReadPositionTag.ACTIVITIES_ABOUT_ME, it)
                            readStateManager.getPosition(tag)
                        }.fold(0L, Math::max)
                        val count = DataStoreUtils.getInteractionsCount(context, spec.args,
                                accountKeys, position, Activities.TIMESTAMP)
                        publishProgress(TabBadge(i, count))
                        result.put(i, count)
                    }
                    else -> {
                        publishProgress(TabBadge(i, -1))
                    }
                }
            }
            return result
        }

        override fun onPostExecute(result: SparseIntArray) {
            indicator.clearBadge()
            for (i in 0 until result.size()) {
                indicator.setBadge(result.keyAt(i), result.valueAt(i))
            }
        }

        override fun onProgressUpdate(vararg values: TabBadge) {
            for (value in values) {
                indicator.setBadge(value.index, value.count)
            }
        }

        internal class TabBadge(var index: Int, var count: Int)
    }

    class AutoRefreshConfirmDialogFragment : BaseDialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.auto_refresh)
            builder.setMessage(R.string.message_auto_refresh_confirm)
            builder.setPositiveButton(android.R.string.ok) { dialog, which ->
                kPreferences[defaultAutoRefreshKey] = true
            }
            builder.setNegativeButton(R.string.no_thanks) { dialog, which ->
                kPreferences[defaultAutoRefreshKey] = false
            }
            val dialog = builder.create()
            dialog.setOnShowListener {
                it as AlertDialog
                it.applyTheme()
            }
            return dialog
        }

        override fun onDismiss(dialog: DialogInterface?) {
            kPreferences[defaultAutoRefreshAskedKey] = true
            super.onDismiss(dialog)
        }
    }

    companion object {
        private val HOME_AS_UP_ATTRS = intArrayOf(android.support.v7.appcompat.R.attr.homeAsUpIndicator)
    }


}
