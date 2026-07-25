package com.betterxcloud.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.betterxcloud.app.databinding.ActivitySettingsBinding
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings get() = BxSettingsStore.current

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.settings_title)
        }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Refresh from localStorage before binding (so we don't show stale cache)
        BxApp.instance.bridge?.refreshSettings()

        // Give the bridge a moment to update the cache
        binding.root.postDelayed({ bindSettings() }, 200)

        bindAbout()
    }

    private fun bindSettings() {
        // ---- Stream section ----
        // Resolution dropdown
        bindDropdown(
            spinner = binding.spinnerResolution,
            options = listOf("auto" to "Automática", "720p" to "720p", "1080p" to "1080p", "1600p" to "1600p (Beta)"),
            selected = settings.streamResolution,
        ) { settings.streamResolution = it; persist() }

        // Codec dropdown
        bindDropdown(
            spinner = binding.spinnerCodec,
            options = listOf(
                "default" to "Padrão",
                "low" to "Baixo (visual)",
                "normal" to "Normal",
                "high" to "Alto (visual)"
            ),
            selected = settings.streamCodec,
        ) { settings.streamCodec = it; persist() }

        // Bitrate slider (0-15 Mbps, 0 = auto)
        binding.sliderBitrate.apply {
            valueFrom = 0f
            valueTo = 15f
            stepSize = 1f
            value = settings.streamBitrate.coerceIn(0, 15).toFloat()
            addOnChangeListener { _, value, _ ->
                settings.streamBitrate = value.toInt()
                binding.tvBitrateValue.text = if (value.toInt() == 0) "Auto" else "${value.toInt()} Mbps"
                persist()
            }
        }
        binding.tvBitrateValue.text = if (settings.streamBitrate == 0) "Auto" else "${settings.streamBitrate} Mbps"

        // Combine audio
        bindSwitch(binding.switchCombineAudio, settings.streamCombineAudio) {
            settings.streamCombineAudio = it; persist()
        }
        // Prevent resolution drops
        bindSwitch(binding.switchPreventDrops, settings.streamPreventDrops) {
            settings.streamPreventDrops = it; persist()
        }

        // ---- Controller section ----
        bindDropdown(
            spinner = binding.spinnerTouchMode,
            options = listOf(
                "all" to "Sempre ligado",
                "off" to "Desligado",
                "default" to "Padrão (auto)"
            ),
            selected = settings.touchMode,
        ) { settings.touchMode = it; persist() }

        binding.sliderTouchOpacity.apply {
            valueFrom = 10f; valueTo = 100f; stepSize = 5f
            value = settings.touchOpacity.coerceIn(10, 100).toFloat()
            addOnChangeListener { _, value, _ ->
                settings.touchOpacity = value.toInt()
                binding.tvTouchOpacityValue.text = "${value.toInt()}%"
                persist()
            }
        }
        binding.tvTouchOpacityValue.text = "${settings.touchOpacity}%"

        bindDropdown(
            spinner = binding.spinnerGamebarPosition,
            options = listOf("left" to "Esquerda", "right" to "Direita", "top" to "Topo", "bottom" to "Base"),
            selected = settings.gamebarPosition,
        ) { settings.gamebarPosition = it; persist() }

        bindSwitch(binding.switchMkbEnabled, settings.mkbEnabled) {
            settings.mkbEnabled = it; persist()
        }

        // ---- Interface section ----
        bindSwitch(binding.switchControllerFriendly, settings.uiControllerFriendly) {
            settings.uiControllerFriendly = it; persist()
        }
        bindSwitch(binding.switchHideScrollbar, settings.uiHideScrollbar) {
            settings.uiHideScrollbar = it; persist()
        }
        bindSwitch(binding.switchSkipSplash, settings.uiSkipSplash) {
            settings.uiSkipSplash = it; persist()
        }
        bindSwitch(binding.switchReduceAnimations, settings.uiReduceAnimations) {
            settings.uiReduceAnimations = it; persist()
        }
        bindSwitch(binding.switchLoadingArt, settings.loadingArt) {
            settings.loadingArt = it; persist()
        }
        bindSwitch(binding.switchLoadingWait, settings.loadingWait) {
            settings.loadingWait = it; persist()
        }

        // ---- Audio section ----
        bindSwitch(binding.switchMicOnPlay, settings.audioMicOnPlay) {
            settings.audioMicOnPlay = it; persist()
        }
        bindSwitch(binding.switchVolumeBooster, settings.audioVolumeBooster) {
            settings.audioVolumeBooster = it; persist()
        }

        // ---- Region section ----
        bindDropdown(
            spinner = binding.spinnerServerRegion,
            options = listOf(
                "default" to "Padrão (automático)",
                "WestUS" to "Oeste dos EUA",
                "EastUS" to "Leste dos EUA",
                "BrazilSouth" to "Sul do Brasil",
                "WestEurope" to "Europa Ocidental",
                "EastAsia" to "Ásia Oriental",
                "JapanEast" to "Leste do Japão",
                "AustraliaEast" to "Leste da Austrália"
            ),
            selected = settings.serverRegion,
        ) { settings.serverRegion = it; persist() }

        bindDropdown(
            spinner = binding.spinnerServerBypass,
            options = listOf("off" to "Desligado", "Korea" to "Coreia do Sul", "Singapore" to "Singapura"),
            selected = settings.serverBypass,
        ) { settings.serverBypass = it; persist() }

        bindSwitch(binding.switchIpv6, settings.serverIpv6) {
            settings.serverIpv6 = it; persist()
        }

        // ---- Advanced section ----
        bindSwitch(binding.switchBlockTracking, settings.blockTracking) {
            settings.blockTracking = it; persist()
        }
        bindSwitch(binding.switchScreenshotFilters, settings.screenshotFilters) {
            settings.screenshotFilters = it; persist()
        }
    }

    private fun bindAbout() {
        binding.tvAboutVersion.text = "Better xCloud for Android v${BuildConfig.VERSION_NAME}"
        binding.tvAboutUserscript.text = "Userscript Better xCloud v6.7.12 (redphx)"
        binding.btnGithub.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/deivid22srk/better-xcloud-android")))
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private fun bindSwitch(switch: SwitchMaterial, initial: Boolean, onChange: (Boolean) -> Unit) {
        switch.setOnCheckedChangeListener(null)  // detach during initial set
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
    }

    private fun bindDropdown(
        spinner: android.widget.Spinner,
        options: List<Pair<String, String>>,
        selected: String,
        onChange: (String) -> Unit
    ) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.second })
        spinner.adapter = adapter
        spinner.setSelection(options.indexOfFirst { it.first == selected }.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val newKey = options[position].first
                if (newKey != selected) onChange(newKey)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun persist() {
        BxApp.instance.bridge?.persistSettings(settings)
    }
}
