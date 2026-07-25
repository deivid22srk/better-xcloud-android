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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gamesAdapter: GamesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen — must be called before super.onCreate
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Keep splash visible until either the auth state resolves OR 2.5s pass,
        // whichever comes first — gives the WebView a moment to pick up cookies.
        var authResolved = false
        val app = application as BxApp
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
            // Trigger a library refresh in case the WebView already has the auth
            BxApp.instance.bridge?.fetchLibraryAndCache()
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
                    BxApp.instance.bridge?.fetchLibraryAndCache()
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
            // Click on a game → launch the stream activity with the deep link
            val intent = Intent(this, StreamActivity::class.java).apply {
                putExtra(StreamActivity.EXTRA_TITLE_ID, game.id)
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

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.md_theme_primary, R.color.md_theme_tertiary)
        binding.swipeRefresh.setOnRefreshListener {
            BxApp.instance.bridge?.fetchLibraryAndCache()
        }
    }

    private fun setupSessionObserver() {
        (application as BxApp).sessionState.observe(this) { state ->
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
                        .setAction(R.string.error_retry) { BxApp.instance.bridge?.fetchLibraryAndCache() }
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
            if (games.isEmpty()) {
                binding.layoutEmptyState.root.visibility = View.VISIBLE
                binding.recyclerGames.visibility = View.GONE
            } else {
                binding.layoutEmptyState.root.visibility = View.GONE
                binding.recyclerGames.visibility = View.VISIBLE
                gamesAdapter.submitXcloudGames(games)
            }
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
