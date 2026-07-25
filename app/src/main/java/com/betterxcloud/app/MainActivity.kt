package com.betterxcloud.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.GridLayoutManager
import com.betterxcloud.app.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gamesAdapter: GamesAdapter

    /** Snapshot of all games currently loaded — used to filter per-tab. */
    private var allGames: List<XcloudGame> = emptyList()

    /** Currently selected tab — 0=All, 1=Purchased, 2=Game Pass. */
    private var selectedTab: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen — must be called before super.onCreate
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Keep splash visible until either the auth state resolves OR 2.5s pass,
        // whichever comes first — gives the WebView a moment to pick up cookies.
        //
        // NOTE: previously this did `application as BxApp`, which crashed with
        // ClassCastException on devices where the system fell back to the base
        // Application class (e.g. after an in-place upgrade with stale app data).
        // We now use the BxApp.instance singleton, assigned in BxApp.onCreate
        // before super.onCreate() — see BxApp.kt for the rationale.
        var authResolved = false
        val app = BxApp.instanceOrNull() ?: run {
            Log.e(TAG, "BxApp.instance is null — manifest android:name not honoured. " +
                    "Finishing activity to avoid ClassCastException.")
            finish()
            return
        }
        val contentReadyObserver = Observer<BxApp.SessionState> { state ->
            if (state == BxApp.SessionState.SIGNED_IN || state == BxApp.SessionState.SIGNED_OUT) {
                authResolved = true
            }
        }
        app.sessionState.observe(this, contentReadyObserver)
        splash.setKeepOnScreenCondition { !authResolved }

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupTabs()
        setupSwipeRefresh()
        setupSessionObserver()
        setupLibraryObserver()
        setupBackNavigation()

        // If no shared WebView exists yet (cold start), start the auth activity.
        // Otherwise, just observe the existing session.
        if (BxApp.instance.sharedWebView == null) {
            Log.i(TAG, "Cold start — launching AuthActivity")
            startActivity(Intent(this, AuthActivity::class.java))
        } else {
            // Trigger a library refresh using the modern classify flow
            // (separates purchased games from Game Pass via emerald entitlements).
            BxApp.instance.bridge?.fetchLibraryAndClassify()
        }

        // Stop the splash observer after a max of 2.5s regardless
        binding.root.postDelayed({ authResolved = true }, 2500)
    }

    private fun setupToolbar() {
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_refresh -> {
                    BxApp.instance.bridge?.fetchLibraryAndClassify()
                    true
                }
                R.id.action_sign_out -> {
                    // Clear the WebView state and restart auth
                    BxApp.instance.sharedWebView?.apply {
                        stopLoading()
                        clearCache(true)
                        clearHistory()
                        // Also clear localStorage / cookies via WebStorage
                        android.webkit.WebStorage.getInstance().deleteAllData()
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    }
                    BxApp.instance.clearWebView()
                    BxApp.instance.setSession(BxApp.SessionState.SIGNED_OUT)
                    startActivity(Intent(this, AuthActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        gamesAdapter = GamesAdapter { game ->
            // Click on a game → launch the stream activity with the deep link.
            // Prefer the xcloud.cloudId (modern play.xbox.com identifier) when
            // available; fall back to the BigId (storeId) used by xbox.com/play.
            val titleId = game.cloudId.takeIf { it.isNotEmpty() } ?: game.id
            val intent = Intent(this, StreamActivity::class.java).apply {
                putExtra(StreamActivity.EXTRA_TITLE_ID, titleId)
                putExtra(StreamActivity.EXTRA_TITLE_NAME, game.title)
            }
            startActivity(intent)
        }
        binding.recyclerGames.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = gamesAdapter
            setHasFixedSize(false)
            // Material 3 spacing
            val spacing = resources.getDimensionPixelSize(R.dimen.game_card_spacing)
            addItemDecoration(GridSpacingItemDecoration(3, spacing, true))
        }
    }

    /**
     * Material 3 tabs: Todos / Comprados / Game Pass.
     * Filters [allGames] based on the selected tab and re-renders the grid.
     * Tab labels include live counts (e.g. "Comprados (12)").
     */
    private fun setupTabs() {
        binding.tabsLibrary.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                applyFilterToGrid()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /**
     * Filters [allGames] based on [selectedTab] and submits the filtered list
     * to the adapter. Also updates the tab labels with counts.
     */
    private fun applyFilterToGrid() {
        val purchased = allGames.count {
            it.ownership == Ownership.PURCHASED || it.ownership == Ownership.BOTH
        }
        val gamePass = allGames.count {
            it.ownership == Ownership.GAME_PASS || it.ownership == Ownership.BOTH
        }

        // Update tab labels with counts
        binding.tabsLibrary.getTabAt(0)?.text = getString(R.string.tab_all) + " (${allGames.size})"
        binding.tabsLibrary.getTabAt(1)?.text = getString(R.string.tab_purchased) + " ($purchased)"
        binding.tabsLibrary.getTabAt(2)?.text = getString(R.string.tab_game_pass) + " ($gamePass)"

        // Filter for the selected tab
        val filtered: List<XcloudGame> = when (selectedTab) {
            1 -> allGames.filter {
                it.ownership == Ownership.PURCHASED || it.ownership == Ownership.BOTH
            }
            2 -> allGames.filter {
                it.ownership == Ownership.GAME_PASS || it.ownership == Ownership.BOTH
            }
            else -> allGames
        }

        if (filtered.isEmpty() && allGames.isNotEmpty()) {
            // The selected tab is empty (e.g. user has no purchased games yet)
            binding.layoutEmptyState.root.visibility = View.VISIBLE
            binding.recyclerGames.visibility = View.GONE
            // Show a tab-specific empty message and hide the progress spinner
            binding.layoutEmptyState.progressEmpty.visibility = View.GONE
            binding.layoutEmptyState.txtEmptyMessage.text = when (selectedTab) {
                1 -> getString(R.string.empty_purchased)
                2 -> getString(R.string.empty_game_pass)
                else -> getString(R.string.error_no_games)
            }
        } else if (allGames.isEmpty()) {
            // Still loading or no games at all — show spinner + default text
            binding.layoutEmptyState.root.visibility = View.VISIBLE
            binding.layoutEmptyState.progressEmpty.visibility = View.VISIBLE
            binding.layoutEmptyState.txtEmptyMessage.text = getString(R.string.loading_message)
            binding.recyclerGames.visibility = View.GONE
        } else {
            binding.layoutEmptyState.root.visibility = View.GONE
            binding.recyclerGames.visibility = View.VISIBLE
            gamesAdapter.submitXcloudGames(filtered)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.md_theme_primary, R.color.md_theme_tertiary)
        binding.swipeRefresh.setOnRefreshListener {
            BxApp.instance.bridge?.fetchLibraryAndClassify()
        }
    }

    private fun setupSessionObserver() {
        BxApp.instance.sessionState.observe(this) { state ->
            when (state) {
                BxApp.SessionState.SIGNED_IN -> {
                    binding.layoutSignedOut.root.visibility = View.GONE
                    binding.swipeRefresh.visibility = View.VISIBLE
                    binding.topAppBar.title = BxApp.instance.gamertag ?: getString(R.string.home_title)
                    binding.topAppBar.subtitle = getString(R.string.home_subtitle)
                }
                BxApp.SessionState.SIGNED_OUT -> {
                    binding.swipeRefresh.visibility = View.GONE
                    binding.layoutSignedOut.root.visibility = View.VISIBLE
                    binding.layoutSignedOut.btnSignIn.setOnClickListener {
                        startActivity(Intent(this, AuthActivity::class.java))
                    }
                }
                BxApp.SessionState.ERROR -> {
                    Snackbar.make(binding.root, R.string.error_no_games, Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.error_retry) { BxApp.instance.bridge?.fetchLibraryAndClassify() }
                        .show()
                }
                BxApp.SessionState.LOADING -> {
                    // Splash is still up; nothing to do
                }
            }
        }
    }

    private fun setupLibraryObserver() {
        BxApp.instance.bridge?.library?.observe(this) { games ->
            binding.swipeRefresh.isRefreshing = false
            allGames = games
            applyFilterToGrid()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishAffinity() }
        })
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
