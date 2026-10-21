package com.jarvis.assistant.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.databinding.SheetChatHistoryBinding
import kotlinx.coroutines.launch

class ChatHistoryBottomSheet : BottomSheetDialogFragment() {
    private var _binding: SheetChatHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ChatSessionAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetChatHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chatRepo = JarvisApp.instance.chatRepository
        adapter = ChatSessionAdapter(
            onOpen = { session ->
                chatRepo.openConversation(session.id)
                dismiss()
            },
            onLongPress = { session -> showSessionActions(session.id, session.title, session.isPinned) }
        )
        binding.rvChatHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChatHistory.adapter = adapter

        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Tüm sohbetleri sil")
                .setMessage("Kaydedilmiş tüm sohbetler bu cihazdan silinsin mi?")
                .setPositiveButton("Tümünü sil") { _, _ -> chatRepo.clearHistory() }
                .setNegativeButton("Vazgeç", null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatRepo.sessions.collect { sessions ->
                    adapter.submitList(sessions)
                    val hasSessions = sessions.any { it.turns.isNotEmpty() }
                    binding.tvEmptyHistory.visibility = if (hasSessions) View.GONE else View.VISIBLE
                    binding.rvChatHistory.visibility = if (hasSessions) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showSessionActions(sessionId: String, currentTitle: String, isPinned: Boolean) {
        val options = arrayOf("Yeniden adlandır", if (isPinned) "Sabitlemeyi kaldır" else "Sohbeti sabitle", "Sohbeti sil")
        AlertDialog.Builder(requireContext())
            .setTitle(currentTitle)
            .setItems(options) { _, which ->
                val repo = JarvisApp.instance.chatRepository
                when (which) {
                    0 -> {
                        val input = android.widget.EditText(requireContext()).apply {
                            setSingleLine(true)
                            setText(currentTitle)
                            setSelection(text.length)
                            hint = "Sohbet adı"
                        }
                        AlertDialog.Builder(requireContext())
                            .setTitle("Sohbeti yeniden adlandır")
                            .setView(input)
                            .setPositiveButton("Kaydet") { _, _ ->
                                if (!repo.renameConversation(sessionId, input.text.toString())) {
                                    android.widget.Toast.makeText(requireContext(), "Sohbet adı boş olamaz", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Vazgeç", null)
                            .show()
                    }
                    1 -> {
                        val nowPinned = repo.togglePinned(sessionId)
                        android.widget.Toast.makeText(requireContext(), if (nowPinned) "Sohbet sabitlendi" else "Sabitleme kaldırıldı", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    2 -> AlertDialog.Builder(requireContext())
                        .setTitle("Sohbet silinsin mi?")
                        .setMessage("Bu sohbet ve içindeki mesajlar cihazdan silinecek.")
                        .setPositiveButton("Sil") { _, _ -> repo.deleteConversation(sessionId) }
                        .setNegativeButton("Vazgeç", null)
                        .show()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        binding.rvChatHistory.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "ChatHistoryBottomSheet"
        fun newInstance() = ChatHistoryBottomSheet()
    }
}
