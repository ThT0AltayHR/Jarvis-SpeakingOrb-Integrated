package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.ItemNairaChatMessageBinding

class JarvisChatAdapter(
    private val onEditUserMessage: ((String) -> Unit)? = null
) : ListAdapter<ChatTurn, JarvisChatAdapter.MessageViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<ChatTurn>() {
        override fun areItemsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemNairaChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemNairaChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private var lastAssistantText: String = ""

        fun bind(turn: ChatTurn) {
            val context = binding.root.context

            // User Bubble
            if (turn.userTranscript.isNotBlank()) {
                binding.layoutUserContainer.visibility = View.VISIBLE
                binding.tvUserMessage.text = turn.userTranscript

                binding.btnUserCopy.setOnClickListener {
                    copyToClipboard(context, "User message", turn.userTranscript)
                }

                binding.btnUserEdit.setOnClickListener {
                    onEditUserMessage?.invoke(turn.userTranscript)
                }
            } else {
                binding.layoutUserContainer.visibility = View.GONE
            }

            // Assistant Bubble
            if (turn.jarvisResponse.isNotBlank()) {
                binding.layoutAssistantContainer.visibility = View.VISIBLE
                ChatMessageFormatter.render(binding.tvAssistantMessage, turn.jarvisResponse)
                if (turn.jarvisResponse != lastAssistantText) binding.tvAssistantMessage.playDustReveal()
                lastAssistantText = turn.jarvisResponse
                val artifact = ChatMessageFormatter.extractFirstFile(turn.jarvisResponse)
                binding.layoutFileCard.isVisible = artifact != null
                if (artifact != null) {
                    binding.tvFileArtifactName.text = artifact.fileName
                    binding.btnFileArtifactCopy.setOnClickListener {
                        copyToClipboard(context, artifact.fileName, artifact.content)
                    }
                    binding.btnFileArtifactDownload.setOnClickListener {
                        try {
                            val savedUri = ChatFileSaver.save(context, artifact)
                            val message = when {
                                savedUri == null -> "Dosya kaydedilemedi"
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> "Dosya İndirilenler/Jarvis klasörüne kaydedildi"
                                else -> "Dosya uygulamanın indirme klasörüne kaydedildi"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Dosya kaydedilemedi: ${e.message ?: "bilinmeyen hata"}", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                binding.btnAssistantCopy.setOnClickListener {
                    copyToClipboard(context, "Jarvis response", turn.jarvisResponse)
                }

                binding.btnAssistantPlay.setOnClickListener {
                    Toast.makeText(context, "Playing response...", Toast.LENGTH_SHORT).show()
                }
            } else {
                binding.layoutAssistantContainer.visibility = View.GONE
                binding.layoutFileCard.visibility = View.GONE
            }
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
