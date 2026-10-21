package com.jarvis.assistant.ui.chat

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private var pendingTextSend: Job? = null
    private var responseTimeoutJob: Job? = null
    private val selectedAttachments = mutableListOf<ChatAttachmentAnalyzer.SelectedFile>()
    private var selectedVideoMode = ChatAttachmentAnalyzer.DetailMode.QUICK
    private var thinkingState: String? = null
    private var thinkingLabel: String = "Jarvis çalışıyor"
    private var awaitingTurnText: String? = null
    private var widgetPageReady = false

    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) handleSelectedUris(uris)
    }

    private val chatAdapter = JarvisChatAdapter(
        onEditUserMessage = { text ->
            binding.etMessageInput.setText(text)
            binding.etMessageInput.setSelection(text.length)
            binding.etMessageInput.requestFocus()
        }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.rvChatMessages.adapter = chatAdapter

        binding.webThinkingWidget.setBackgroundColor(Color.TRANSPARENT)
        binding.webThinkingWidget.isVerticalScrollBarEnabled = false
        binding.webThinkingWidget.isHorizontalScrollBarEnabled = false
        binding.webThinkingWidget.overScrollMode = View.OVER_SCROLL_NEVER
        binding.webThinkingWidget.settings.javaScriptEnabled = true
        binding.webThinkingWidget.settings.domStorageEnabled = false
        binding.webThinkingWidget.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                widgetPageReady = true
                thinkingState?.let { applyThinkingState(it, thinkingLabel) }
            }
        }
        binding.webThinkingWidget.loadUrl("file:///android_asset/chat-thinking-orb.html")

        binding.btnSendMessage.setOnClickListener { sendMessage() }
        binding.btnChatNew.setOnClickListener {
            JarvisApp.instance.chatRepository.startNewConversation()
            selectedAttachments.clear()
            updateAttachmentPreview()
            clearWaitingState()
            Toast.makeText(requireContext(), "Yeni sohbet açıldı", Toast.LENGTH_SHORT).show()
        }
        binding.btnChatMenu.setOnClickListener {
            ChatHistoryBottomSheet.newInstance().show(parentFragmentManager, ChatHistoryBottomSheet.TAG)
        }
        binding.chipPersona.setOnClickListener {
            Toast.makeText(requireContext(), "Jarvis modu etkin", Toast.LENGTH_SHORT).show()
        }
        binding.chipMode.setOnClickListener {
            Toast.makeText(requireContext(), "Metin, kod ve dosya analizi", Toast.LENGTH_SHORT).show()
        }
        binding.btnAttach.setOnClickListener { attachmentPicker.launch(arrayOf("*/*")) }

        observeChatTurns()
    }

    private fun handleSelectedUris(uris: List<android.net.Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val files = withContext(Dispatchers.IO) {
                uris.take(8).mapNotNull { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) { }
                    try { ChatAttachmentAnalyzer.describe(context, uri) } catch (_: Exception) { null }
                }
            }
            if (files.isEmpty()) {
                Toast.makeText(requireContext(), "Seçilen dosyalar açılamadı", Toast.LENGTH_LONG).show()
                return@launch
            }
            selectedAttachments.clear()
            selectedAttachments.addAll(files)
            selectedVideoMode = ChatAttachmentAnalyzer.DetailMode.QUICK
            updateAttachmentPreview()

            val longestVideo = withContext(Dispatchers.IO) {
                files.mapNotNull { file -> ChatAttachmentAnalyzer.videoDurationMs(context, file)?.let { file to it } }
                    .maxByOrNull { it.second }
            }
            if (longestVideo != null && longestVideo.second > 120_000L && _binding != null) {
                val mins = ((longestVideo.second + 59_999L) / 60_000L).coerceAtLeast(1L)
                AlertDialog.Builder(requireContext())
                    .setTitle("Uzun video seçildi")
                    .setMessage("${longestVideo.first.name} yaklaşık $mins dakika. Hızlı özet az sayıda kare ve kısa ses örnekleri kullanır. Detaylı analiz videonun farklı bölümlerinden daha çok kare ve daha uzun ses örnekleri işler; daha fazla süre ve veri kullanır.")
                    .setItems(arrayOf("Hızlı özet", "Detaylı analiz")) { _, which ->
                        selectedVideoMode = if (which == 1) ChatAttachmentAnalyzer.DetailMode.DETAILED else ChatAttachmentAnalyzer.DetailMode.QUICK
                        updateAttachmentPreview()
                    }
                    .setOnCancelListener { selectedVideoMode = ChatAttachmentAnalyzer.DetailMode.QUICK; updateAttachmentPreview() }
                    .show()
            }
        }
    }

    private fun updateAttachmentPreview() {
        val b = _binding ?: return
        if (selectedAttachments.isEmpty()) {
            b.tvAttachmentPreview.visibility = View.GONE
            b.tvAttachmentPreview.text = ""
            return
        }
        val names = selectedAttachments.take(3).joinToString(", ") { it.name }
        val rest = if (selectedAttachments.size > 3) " +${selectedAttachments.size - 3}" else ""
        val mode = if (selectedVideoMode == ChatAttachmentAnalyzer.DetailMode.DETAILED) " · detaylı medya" else ""
        b.tvAttachmentPreview.text = "📎 $names$rest$mode"
        b.tvAttachmentPreview.visibility = View.VISIBLE
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim().orEmpty()
        if ((text.isBlank() && selectedAttachments.isEmpty()) || pendingTextSend?.isActive == true) return

        val files = selectedAttachments.toList()
        val displayText = if (files.isEmpty()) text else buildString {
            if (text.isNotBlank()) append(text).append("\n")
            append("[Ekler: ").append(files.joinToString(", ") { it.name }).append("]")
        }
        val isSearch = Regex("(?i)(web['’ ]de ara|internette ara|webde ara|web araştır|internetten araştır)").containsMatchIn(text)
        val initialState = when {
            isSearch -> "searching"
            files.isNotEmpty() -> "solving"
            else -> "working"
        }
        val initialLabel = when {
            isSearch -> "Web’de arıyor"
            files.isNotEmpty() -> "Dosyaları inceliyor"
            else -> "Yanıtı hazırlıyor"
        }
        showThinking(initialState, initialLabel)
        binding.btnSendMessage.isEnabled = false
        binding.btnAttach.isEnabled = false

        pendingTextSend = viewLifecycleOwner.lifecycleScope.launch {
            var sent = false
            try {
                var prompt = text
                var imageFrames = emptyList<ByteArray>()
                var audioSamples = emptyList<ByteArray>()
                if (files.isNotEmpty()) {
                    showThinking("solving", "Dosya içeriği çözümleniyor")
                    val analyses = withContext(Dispatchers.IO) {
                        files.map { file -> ChatAttachmentAnalyzer.analyze(requireContext().applicationContext, file, selectedVideoMode) }
                    }
                    imageFrames = analyses.flatMap { it.imageFrames }.take(12)
                    audioSamples = analyses.flatMap { it.audioPcm16k }.take(8)
                    prompt = buildAnalysisPrompt(text, analyses)
                }

                if (!JarvisConversationService.isLiveConnectionReady()) {
                    if (!JarvisConversationService.isServiceRunning()) (activity as? MainActivity)?.handleMicOrOrbClick()
                    val ready = withTimeoutOrNull(30_000L) {
                        while (!JarvisConversationService.isLiveConnectionReady()) delay(150)
                        true
                    } ?: false
                    if (!ready) {
                        Toast.makeText(requireContext(), "Jarvis bağlantısı hazır değil. Metin ve ekler korunuyor; tekrar deneyebilirsin.", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }

                sent = if (files.isEmpty()) {
                    JarvisConversationService.sendTextMessage(text)
                } else {
                    JarvisConversationService.sendAttachmentMessage(displayText, prompt, imageFrames, audioSamples)
                }
                if (sent) {
                    binding.etMessageInput.text?.clear()
                    selectedAttachments.clear()
                    updateAttachmentPreview()
                    awaitingTurnText = displayText
                    responseTimeoutJob?.cancel()
                    responseTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(180_000L)
                        if (awaitingTurnText == displayText) {
                            clearWaitingState()
                            Toast.makeText(requireContext(), "Yanıt bekleme zaman aşımına uğradı; bağlantıyı kontrol edebilirsin.", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Gönderilemedi. Metin ve ekler korunuyor; tekrar deneyebilirsin.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Dosya işlenemedi: ${e.message ?: "bilinmeyen hata"}", Toast.LENGTH_LONG).show()
            } finally {
                if (_binding != null) {
                    binding.btnSendMessage.isEnabled = true
                    binding.btnAttach.isEnabled = true
                }
                if (!sent) hideThinking()
            }
        }
    }

    private fun buildAnalysisPrompt(question: String, analyses: List<ChatAttachmentAnalyzer.Analysis>): String {
        val out = StringBuilder()
        out.append("Kullanıcının isteği: ").append(question.ifBlank { "Eklediğim içerikleri analiz et ve önemli bulguları açıkla." }).append("\n\n")
        out.append("Dosya içeriklerini isimlerinden tahmin etme. Çıkarılmış metni gerçek içerik olarak, eklenen görüntü/ses baytlarını görsel-işitsel içerik olarak incele.\n")
        var remainingChars = 90_000
        analyses.forEachIndexed { index, analysis ->
            out.append("\n===== EK ").append(index + 1).append(" =====\n")
            out.append("Dosya: ").append(analysis.file.name).append("\nMIME: ").append(analysis.file.mimeType)
                .append("\nBoyut: ").append(analysis.file.sizeBytes?.let { "${it / 1024} KB" } ?: "bilinmiyor").append("\n")
            if (analysis.durationMs != null) out.append("Süre: ").append(analysis.durationMs / 1000).append(" saniye\n")
            if (analysis.extractedText.isNotBlank() && remainingChars > 0) {
                val value = analysis.extractedText.take(remainingChars)
                out.append("İçerik / çıkarılan metin:\n").append(value).append("\n")
                remainingChars -= value.length
            }
            if (analysis.imageFrames.isNotEmpty()) out.append("Görsel örnek sayısı: ").append(analysis.imageFrames.size).append("; sıralı kareler aynı dosyadan gelir.\n")
            if (analysis.audioPcm16k.isNotEmpty()) out.append("Ses örnekleri: ").append(analysis.audioPcm16k.size).append(" adet 16 kHz mono ses parçası.\n")
            analysis.notes.forEach { out.append("Not: ").append(it).append("\n") }
        }
        if (remainingChars <= 0) out.append("\n[Ek metinlerin bir bölümü güvenli istek boyutu için kısaltıldı.]\n")
        out.append("\nYanıt verirken analiz edilen gerçek metin/görüntü/ses bulgularını ayır; desteklenmeyen bir ikili biçimi çözmüş gibi davranma. Video örnekleme kullanılmışsa bunun tüm karelerin eksiksiz incelenmesi olmadığını açıkça belirt.")
        return out.toString()
    }

    private fun showThinking(state: String, label: String) {
        thinkingState = state
        thinkingLabel = label
        val b = _binding ?: return
        b.webThinkingWidget.visibility = View.VISIBLE
        if (widgetPageReady) applyThinkingState(state, label)
    }

    private fun applyThinkingState(state: String, label: String) {
        val safeState = state.filter { it.isLetter() || it == '_' }
        val safeLabel = label.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        _binding?.webThinkingWidget?.evaluateJavascript("window.setJarvisThinkingState && window.setJarvisThinkingState('$safeState','$safeLabel');", null)
    }

    private fun hideThinking() {
        thinkingState = null
        val b = _binding ?: return
        if (widgetPageReady) b.webThinkingWidget.evaluateJavascript("window.hideJarvisThinking && window.hideJarvisThinking();", null)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(200L)
            if (thinkingState == null && _binding != null) binding.webThinkingWidget.visibility = View.GONE
        }
    }

    private fun clearWaitingState() {
        awaitingTurnText = null
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
        hideThinking()
    }

    private fun observeChatTurns() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatTurns.collect { turns ->
                    chatAdapter.submitList(turns) {
                        if (turns.isNotEmpty()) binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                    }
                    val waitingFor = awaitingTurnText
                    if (waitingFor != null) {
                        val relatedTurn = turns.lastOrNull { it.userTranscript == waitingFor }
                        if (relatedTurn?.jarvisResponse?.isNotBlank() == true) {
                            awaitingTurnText = null
                            responseTimeoutJob?.cancel()
                            showThinking("breathing", "Yanıt hazır")
                            viewLifecycleOwner.lifecycleScope.launch { delay(700L); if (awaitingTurnText == null) hideThinking() }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            if (thinkingState != null) binding.webThinkingWidget.visibility = View.VISIBLE
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        pendingTextSend?.cancel()
        responseTimeoutJob?.cancel()
        pendingTextSend = null
        responseTimeoutJob = null
        try { _binding?.webThinkingWidget?.stopLoading(); _binding?.webThinkingWidget?.loadUrl("about:blank") } catch (_: Exception) { }
        widgetPageReady = false
        super.onDestroyView()
        _binding = null
    }
}
