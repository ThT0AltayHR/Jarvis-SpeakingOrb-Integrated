package com.jarvis.assistant.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.jarvis.assistant.databinding.ActivityPrivacyShieldBinding
import com.jarvis.assistant.util.ThemeManager

class PrivacyShieldActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyShieldBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyShieldBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        // Remove external social links from the original fork.
        binding.btnJoinOfficialTelegram.visibility = View.GONE
        binding.btnJoinTelegram.visibility = View.GONE
        binding.btnJoinInstagram.visibility = View.GONE
        binding.btnJoinYoutube.visibility = View.GONE
    }

}
