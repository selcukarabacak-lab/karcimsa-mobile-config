package com.karcimsa.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        const val PREF_KEEP_SCREEN_ON = "keep_screen_on"
        const val PREF_AUTO_REFRESH_SEC = "auto_refresh_sec"
        const val PREF_FORCE_REFRESH = "force_refresh"
        const val PREF_CLEAR_CACHE = "clear_cache"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.salesNotificationsSwitch.isChecked = prefs.getBoolean(PREF_NOTIFY_SALES, true)
        binding.cem1NotificationsSwitch.isChecked = prefs.getBoolean(PREF_NOTIFY_CEM1, true)
        binding.keepScreenOnSwitch.isChecked = prefs.getBoolean(PREF_KEEP_SCREEN_ON, false)
        updateAutoRefreshLabel()
        binding.versionText.text = "Sürüm ${BuildConfig.VERSION_NAME}"

        binding.salesNotificationsSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(PREF_NOTIFY_SALES, enabled).apply()
            updateTopic(TOPIC_SALES, enabled, "Satış bildirimleri")
        }

        binding.cem1NotificationsSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(PREF_NOTIFY_CEM1, enabled).apply()
            updateTopic(TOPIC_CEM1, enabled, "CEM I bildirimleri")
        }

        binding.keepScreenOnSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(PREF_KEEP_SCREEN_ON, enabled).apply()
            binding.settingsStatusText.text = if (enabled) {
                "Ekranı açık tutma etkinleştirildi."
            } else {
                "Ekranı açık tutma kapatıldı."
            }
        }

        binding.autoRefreshRow.setOnClickListener {
            showAutoRefreshDialog()
        }

        binding.refreshNowRow.setOnClickListener {
            prefs.edit().putBoolean(PREF_FORCE_REFRESH, true).apply()
            Toast.makeText(this, "Panel verileri yenilenecek.", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.clearCacheRow.setOnClickListener {
            prefs.edit()
                .putBoolean(PREF_CLEAR_CACHE, true)
                .putBoolean(PREF_FORCE_REFRESH, true)
                .apply()
            Toast.makeText(this, "Önbellek temizlenecek ve panel yeniden yüklenecek.", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.closeSettingsButton.setOnClickListener {
            finish()
        }
    }

    private fun showAutoRefreshDialog() {
        val labels = arrayOf("Kapalı", "15 saniye", "30 saniye", "1 dakika", "2 dakika")
        val values = intArrayOf(0, 15, 30, 60, 120)
        val current = prefs.getInt(PREF_AUTO_REFRESH_SEC, 30)
        val selectedIndex = values.indexOf(current).takeIf { it >= 0 } ?: 2

        AlertDialog.Builder(this)
            .setTitle("Otomatik Yenileme")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                prefs.edit().putInt(PREF_AUTO_REFRESH_SEC, values[which]).apply()
                updateAutoRefreshLabel()
                binding.settingsStatusText.text = if (values[which] == 0) {
                    "Otomatik yenileme kapatıldı."
                } else {
                    "Otomatik yenileme: ${labels[which]}."
                }
                dialog.dismiss()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun updateAutoRefreshLabel() {
        val seconds = prefs.getInt(PREF_AUTO_REFRESH_SEC, 30)
        val label = when (seconds) {
            0 -> "Kapalı"
            15 -> "15 sn"
            30 -> "30 sn"
            60 -> "1 dk"
            120 -> "2 dk"
            else -> "$seconds sn"
        }
        binding.autoRefreshValue.text = "$label  ›"
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
