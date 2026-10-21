package com.jarvis.assistant.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.model.ChatSession
import com.jarvis.assistant.databinding.ItemChatSessionBinding

class ChatSessionAdapter(
    private val onOpen: (ChatSession) -> Unit,
    private val onLongPress: (ChatSession) -> Unit
) : ListAdapter<ChatSession, ChatSessionAdapter.SessionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        return SessionViewHolder(ItemChatSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) = holder.bind(getItem(position))

    inner class SessionViewHolder(private val binding: ItemChatSessionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(session: ChatSession) {
            binding.tvSessionTitle.text = if (session.isPinned) "📌 ${session.title}" else session.title
            binding.tvSessionPreview.text = session.preview
            binding.tvSessionTime.text = session.formattedUpdatedAt
            binding.root.setOnClickListener { onOpen(session) }
            binding.root.setOnLongClickListener {
                onLongPress(session)
                true
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ChatSession>() {
        override fun areItemsTheSame(oldItem: ChatSession, newItem: ChatSession) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatSession, newItem: ChatSession) = oldItem == newItem
    }
}
