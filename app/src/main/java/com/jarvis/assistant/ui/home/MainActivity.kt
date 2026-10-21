package com.jarvis.assistant.ui.home

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.ui.chat.ChatFragment
import com.jarvis.assistant.ui.settings.SettingsFragment
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HOME = 0
        const val TAB_CHAT = 1
        const val TAB_SETTINGS = 2
        const val TAB_UPDATES = 3
    }

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()
    private var activeSnackbar: Snackbar? = null
    private var welcomeSpeechRequested = false

    private var currentTab = TAB_HOME
    private val homeFragment by lazy { HomeFragment() }
    private val chatFragment by lazy { ChatFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private val updatesFragment by lazy { UpdatesFragment() }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Sesli görüşme için mikrofon izni gerekli.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkPermissionsAndStart()
        } else {
            Toast.makeText(
                this,
                "Arka planda çalışmak için bildirim izni gerekli.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
    }

    val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                if (!JarvisConversationService.isSessionOn.value || JarvisConversationService.connectionStatus.value != "LIVE") {
                    Toast.makeText(this, "Ekranı paylaşmadan önce Jarvis’i başlatıp bağlantının kurulmasını bekle.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                if (!JarvisConversationService.elevateToMediaProjection()) {
                    Toast.makeText(this, "Android ekran yakalama hizmeti başlatılamadı.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val started = JarvisConversationService.startScreenCapture(projection, metrics)
                if (!started) {
                    JarvisConversationService.demoteFromMediaProjection()
                    Toast.makeText(this, "Ekran yakalama başlatılamadı. Yeniden dene.", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                Toast.makeText(this, "Ekran paylaşımı kontrol ediliyor…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    delay(2500L)
                    val captureManager = com.jarvis.assistant.vision.ScreenCaptureManager.getInstance()
                    if (!captureManager.isSharing.value) return@launch
                    if (captureManager.hasReceivedFrame.value) {
                        Toast.makeText(this@MainActivity, "Ekran paylaşımı etkin. Jarvis ekrandan görüntü alıyor.", Toast.LENGTH_LONG).show()
                    } else {
                        JarvisConversationService.stopScreenCapture()
                        Toast.makeText(this@MainActivity, "Ekran görüntüsü alınamadığı için paylaşım durduruldu. Yeniden dene.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting MediaProjection: ${e.message}", e)
                JarvisConversationService.demoteFromMediaProjection()
                Toast.makeText(this, "Screen capture failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Ekran yakalama izni verilmedi.", Toast.LENGTH_SHORT).show()
        }
    }

    private var appliedThemeKey: String = ""
    private var appliedCustomHex: String = ""

    fun toggleScreenShare() {
        if (com.jarvis.assistant.vision.ScreenCaptureManager.getInstance().isSharing.value) {
            JarvisConversationService.stopScreenCapture()
            Toast.makeText(this, "Ekran paylaşımı durduruldu.", Toast.LENGTH_SHORT).show()
        } else {
            if (!JarvisConversationService.isSessionOn.value || JarvisConversationService.connectionStatus.value != "LIVE") {
                Toast.makeText(this, "Ekranı paylaşmadan önce Jarvis’i başlatıp bağlantının kurulmasını bekle.", Toast.LENGTH_LONG).show()
                return
            }
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        appliedThemeKey = prefs.appTheme
        appliedCustomHex = prefs.customThemeColor
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!com.jarvis.assistant.security.AppIdentityVerifier.isTrustedRelease(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Uygulama bütünlük denetimi başarısız")
                .setMessage("Bu JARVIS sürümü ayarlanmış resmî imza sertifikasıyla imzalanmamış. Hassas özellikler engellendi.")
                .setCancelable(false)
                .setPositiveButton("Kapat") { _, _ -> finish() }
                .show()
            return
        }

        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.rootMainLayout)

        currentTab = savedInstanceState?.getInt("CURRENT_TAB", TAB_HOME) ?: TAB_HOME

        // Clear any old restored fragments to prevent ghost overlapping
        val existingFragments = supportFragmentManager.fragments
        if (existingFragments.isNotEmpty()) {
            val trans = supportFragmentManager.beginTransaction()
            for (fragment in existingFragments) {
                trans.remove(fragment)
            }
            trans.commitNowAllowingStateLoss()
        }

        // Add all 4 fragments fresh into container
        val trans = supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, homeFragment, "TAB_0")
            .add(R.id.fragmentContainer, chatFragment, "TAB_1")
            .add(R.id.fragmentContainer, settingsFragment, "TAB_2")
            .add(R.id.fragmentContainer, updatesFragment, "TAB_3")

        val allTabs = listOf(
            TAB_HOME to homeFragment,
            TAB_CHAT to chatFragment,
            TAB_SETTINGS to settingsFragment,
            TAB_UPDATES to updatesFragment
        )

        allTabs.forEach { (tabId, frag) ->
            if (tabId == currentTab) {
                trans.show(frag)
            } else {
                trans.hide(frag)
            }
        }
        trans.commitNow()

        setupNavigationDock()
        selectTab(currentTab)
        observeViewModel()
        maybeAskForNameAndEnrollVoice()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab != TAB_HOME) {
                    selectTab(TAB_HOME)
                } else {
                    // Preserve Android's normal root behavior (return to launcher) only
                    // after the user is already on JARVIS Home.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun maybeAskForNameAndEnrollVoice() {
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        if (prefs.userName.isNotBlank()) {
            if (!prefs.hasOwnerVoiceProfile()) {
                startActivity(Intent(this, com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity::class.java))
            }
            return
        }
        val nameInput = androidx.appcompat.widget.AppCompatEditText(this).apply {
            hint = "Adın"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
            setSingleLine(true)
            setPadding(48, 18, 48, 18)
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(nameInput, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "Bu bilgi yalnızca Jarvis deneyimini kişiselleştirmek için cihazında saklanır. Sonraki adımda ses profilini tanıtacaksın."
                textSize = 13f
                setPadding(0, 12, 0, 0)
            })
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Merhaba, ben Jarvis")
            .setMessage("Sana nasıl hitap etmeliyim?")
            .setView(content)
            .setCancelable(false)
            .setPositiveButton("Kaydet ve devam et", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.length < 2) {
                    nameInput.error = "Lütfen adını gir."
                    return@setOnClickListener
                }
                prefs.userName = name
                dialog.dismiss()
                if (!prefs.hasOwnerVoiceProfile()) {
                    startActivity(Intent(this, com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity::class.java))
                }
            }
            nameInput.requestFocus()
        }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("CURRENT_TAB", currentTab)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSettings()
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        if (appliedThemeKey != prefs.appTheme || appliedCustomHex != prefs.customThemeColor) {
            appliedThemeKey = prefs.appTheme
            appliedCustomHex = prefs.customThemeColor
            recreate()
            return
        }
        if (prefs.userName.isNotBlank() && prefs.hasOwnerVoiceProfile() && !prefs.hasSpokenWelcome) {
            speakFirstWelcome(prefs.userName)
        }
    }

    private fun speakFirstWelcome(name: String) {
        if (welcomeSpeechRequested || name.isBlank() || JarvisConversationService.isServiceRunning()) return
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        // A model-generated greeting needs the same live audio path as ordinary Jarvis speech.
        // Leave it pending when credentials or required permissions are absent so it can retry later.
        if (prefs.apiKey.isBlank() || !PermissionHelper.hasAllRequiredPermissions(this)) return
        welcomeSpeechRequested = true
        val greeting = "Hey, merhaba $name. Bugün sana nasıl yardımcı olabilirim? İstersen sohbet edebiliriz veya sohbet panelini açıp yapmak istediğin görevleri bana anlatabilirsin."
        val intent = Intent(this, JarvisConversationService::class.java).apply {
            action = JarvisConversationService.ACTION_START_CONVERSATION
            putExtra(JarvisConversationService.EXTRA_FIRST_WELCOME_TEXT, greeting)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            welcomeSpeechRequested = false
            android.util.Log.e("MainActivity", "Could not start model-voice welcome: ${e.message}", e)
        }
    }

    private fun setupNavigationDock() {
        binding.tabHome.setOnClickListener { selectTab(TAB_HOME) }
        binding.tabChat.setOnClickListener { selectTab(TAB_CHAT) }
        binding.tabSettings.setOnClickListener { selectTab(TAB_SETTINGS) }
        binding.tabUpdates.setOnClickListener { selectTab(TAB_UPDATES) }

        binding.tabMic.setOnClickListener {
            handleMicOrOrbClick()
        }

        binding.tabMic.setOnLongClickListener {
            handleHoldMicOrOrb()
            true
        }
    }

    fun selectTab(tab: Int) {
        val previousTab = currentTab
        currentTab = tab

        val tabs = listOf(
            TAB_HOME to homeFragment,
            TAB_CHAT to chatFragment,
            TAB_SETTINGS to settingsFragment,
            TAB_UPDATES to updatesFragment
        )

        val trans = supportFragmentManager.beginTransaction()
        tabs.forEach { (tabId, frag) ->
            if (frag.isAdded) {
                if (tabId == tab) {
                    trans.show(frag)
                } else {
                    trans.hide(frag)
                }
            }
        }
        trans.commitNowAllowingStateLoss()

        tabs.forEach { (tabId, frag) ->
            val v = frag.view
            if (v != null) {
                if (tabId == tab) {
                    v.visibility = View.VISIBLE
                    com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(v)
                    if (previousTab != tab) {
                        v.alpha = 0f
                        v.translationX = if (tab > previousTab) 18f else -18f
                        v.animate().cancel()
                        v.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(190L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    } else {
                        v.alpha = 1f
                        v.translationX = 0f
                    }
                } else {
                    v.animate().cancel()
                    v.visibility = View.GONE
                }
            }
        }

        updateDockUI(tab)
    }

    private fun updateDockUI(tab: Int) {
        val tvAccent = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
        val activeColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this, R.color.accent_sapphire)

        val tvMuted = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
        val inactiveColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this, R.color.text_subtle)

        val tvPrimary = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextPrimary, tvPrimary, true)
        val activeTextColor = if (tvPrimary.data != 0) tvPrimary.data else android.graphics.Color.WHITE

        val navItems = listOf(
            Triple(TAB_HOME, binding.pillTabHome, Pair(binding.ivTabHome, binding.tvTabHome)),
            Triple(TAB_CHAT, binding.pillTabChat, Pair(binding.ivTabChat, binding.tvTabChat)),
            Triple(TAB_SETTINGS, binding.pillTabSettings, Pair(binding.ivTabSettings, binding.tvTabSettings)),
            Triple(TAB_UPDATES, binding.pillTabUpdates, Pair(binding.ivTabUpdates, binding.tvTabUpdates))
        )

        navItems.forEach { (tabId, pill, views) ->
            val isSelected = tabId == tab
            val (iv, tv) = views
            if (isSelected) {
                pill.setBackgroundResource(R.drawable.bg_dock_item_active)
                pill.alpha = 0f
                pill.scaleX = 0.88f
                pill.scaleY = 0.88f
                pill.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                    .start()
                iv.imageTintList = ColorStateList.valueOf(activeTextColor)
                tv.setTextColor(activeTextColor)
                tv.alpha = 1f
            } else {
                pill.animate().cancel()
                pill.setBackgroundResource(0)
                pill.alpha = 1f
                pill.scaleX = 1f
                pill.scaleY = 1f
                iv.imageTintList = ColorStateList.valueOf(inactiveColor)
                tv.setTextColor(inactiveColor)
                tv.alpha = 0.75f
            }
        }
    }

    fun handleMicOrOrbClick() {
        if (!JarvisConversationService.isSessionOn.value) {
            checkPermissionsAndStart()
        } else if (JarvisConversationService.conversationState.value == ConversationState.SPEAKING) {
            JarvisConversationService.interruptPlayback()
        } else {
            viewModel.toggleMicMute()
        }
    }

    fun handleHoldMicOrOrb() {
        binding.tabMic.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (JarvisConversationService.isSessionOn.value) {
            viewModel.turnOffSession()
        } else {
            checkPermissionsAndStart()
        }
    }

    fun handleTurnOff() {
        handleHoldMicOrOrb()
    }

    fun handlePowerClick() {
        handleHoldMicOrOrb()
    }

    private fun checkPermissionsAndStart() {
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        if (!prefs.hasOwnerVoiceProfile()) {
            Toast.makeText(this, "Önce Ayarlar > Sesim bölümünden üç ses örneği kaydetmelisin.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity::class.java))
            return
        }
        // Personal voice verification is mandatory for microphone sessions.
        if (!prefs.isOwnerVoiceEnabled) prefs.isOwnerVoiceEnabled = true
        if (!PermissionHelper.hasRecordAudioPermission(this)) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!PermissionHelper.hasNotificationPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        if (!PermissionHelper.hasBatteryOptimizationPermission(this)) {
            showBatteryPermissionDialog()
            return
        }

        viewModel.toggleSession()
    }

    private fun showBatteryPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Sınırsız pil kullanımı gerekli")
            .setMessage("Jarvis’in arka planda etkin kalıp uyandırma sözlerine yanıt vermesi için sınırsız pil kullanımı izni gerekiyor.\n\nLütfen izin ver veya Ayarlar’ı aç.")
            .setPositiveButton("İZİN VER") { _, _ ->
                PermissionHelper.requestBatteryOptimization(this)
            }
            .setNeutralButton("AYARLARI AÇ") { _, _ ->
                selectTab(TAB_SETTINGS)
            }
            .setNegativeButton("İPTAL", null)
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.isMicMuted, viewModel.conversationState, viewModel.isSessionOn) { isMuted, state, isOn ->
                        Triple(isMuted, state, isOn)
                    }.collect { (isMuted, state, isOn) ->
                        val tvAccent = android.util.TypedValue()
                        theme.resolveAttribute(R.attr.themeAccent, tvAccent, true)
                        val accentColor = if (tvAccent.data != 0) tvAccent.data else ContextCompat.getColor(this@MainActivity, R.color.accent_sapphire)

                        val tvMuted = android.util.TypedValue()
                        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
                        val inactiveColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this@MainActivity, R.color.text_subtle)

                        if (!isOn) {
                            binding.ivTabMic.setImageResource(R.drawable.ic_mic_jarvis)
                            binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#94A3B8"))
                            binding.tvTabMic.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                            binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_off)
                            binding.pillTabMic.animate().cancel()
                            binding.pillTabMic.alpha = 0.85f
                            binding.pillTabMic.scaleX = 1f
                            binding.pillTabMic.scaleY = 1f
                        } else if (isMuted) {
                            binding.ivTabMic.setImageResource(R.drawable.ic_mic_mute_jarvis)
                            binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                            binding.tvTabMic.setTextColor(android.graphics.Color.WHITE)
                            binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_muted)
                            binding.pillTabMic.animate().cancel()
                            binding.pillTabMic.alpha = 1f
                            binding.pillTabMic.scaleX = 1f
                            binding.pillTabMic.scaleY = 1f
                        } else {
                            binding.ivTabMic.setImageResource(R.drawable.ic_mic_jarvis)
                            binding.ivTabMic.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                            binding.tvTabMic.setTextColor(android.graphics.Color.WHITE)
                            binding.pillTabMic.setBackgroundResource(R.drawable.bg_dock_mic_highlight)
                            binding.pillTabMic.alpha = 1f
                            if (state == ConversationState.LISTENING || state == ConversationState.SPEAKING) {
                                binding.pillTabMic.animate()
                                    .scaleX(1.08f)
                                    .scaleY(1.08f)
                                    .setDuration(300)
                                    .start()
                            } else {
                                binding.pillTabMic.animate().cancel()
                                binding.pillTabMic.scaleX = 1f
                                binding.pillTabMic.scaleY = 1f
                            }
                        }
                    }
                }

                launch {
                    viewModel.eventFlow.collect { message ->
                        activeSnackbar?.dismiss()
                        activeSnackbar = Snackbar.make(binding.rootMainLayout, message, Snackbar.LENGTH_SHORT).apply {
                            setAnchorView(binding.bottomDock)
                            show()
                        }
                    }
                }
            }
        }
    }
}
