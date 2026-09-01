package com.karcimsa.mobile

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        const val PREF_FORCE_REFRESH = "force_refresh"
        const val PREF_CLEAR_CACHE = "clear_cache"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSafeArea()

        binding.salesNotificationsSwitch.isChecked = prefs.getBoolean(PREF_NOTIFY_SALES, true)
        binding.cem1NotificationsSwitch.isChecked = prefs.getBoolean(PREF_NOTIFY_CEM1, true)
        binding.keepScreenOnSwitch.isChecked = prefs.getBoolean(PREF_KEEP_SCREEN_ON, false)
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupSafeArea() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                safeInsets.left,
                safeInsets.top,
                safeInsets.right,
                safeInsets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
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
