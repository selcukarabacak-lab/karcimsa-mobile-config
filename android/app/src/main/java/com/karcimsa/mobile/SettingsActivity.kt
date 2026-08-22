package com.karcimsa.mobile

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import com.karcimsa.mobile.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("karcimsa_mobile", MODE_PRIVATE) }

    companion object {
        const val TOPIC_SALES = "karcimsa_sales"
        const val TOPIC_CEM1 = "karcimsa_cem1"
        const val PREF_NOTIFY_SALES = "notify_sales"
        const val PREF_NOTIFY_CEM1 = "notify_cem1"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val salesEnabled = prefs.getBoolean(PREF_NOTIFY_SALES, true)
        val cem1Enabled = prefs.getBoolean(PREF_NOTIFY_CEM1, true)

        binding.salesNotificationsSwitch.isChecked = salesEnabled
        binding.cem1NotificationsSwitch.isChecked = cem1Enabled

        binding.salesNotificationsSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(PREF_NOTIFY_SALES, enabled).apply()
            updateTopic(TOPIC_SALES, enabled, "Satış bildirimleri")
        }

        binding.cem1NotificationsSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(PREF_NOTIFY_CEM1, enabled).apply()
            updateTopic(TOPIC_CEM1, enabled, "CEM I bildirimleri")
        }

        binding.closeSettingsButton.setOnClickListener {
            finish()
        }
    }

    private fun updateTopic(topic: String, enabled: Boolean, label: String) {
        val operation = if (enabled) {
            FirebaseMessaging.getInstance().subscribeToTopic(topic)
        } else {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
        }

        binding.settingsStatusText.text = if (enabled) {
            "$label açılıyor..."
        } else {
            "$label kapatılıyor..."
        }

        operation.addOnCompleteListener { task ->
            binding.settingsStatusText.text = when {
                task.isSuccessful && enabled -> "$label açık."
                task.isSuccessful && !enabled -> "$label kapalı."
                else -> "Ayar kaydedildi. Bağlantı geldiğinde Firebase tercihi yeniden uygulanacak."
            }
        }
    }
}
