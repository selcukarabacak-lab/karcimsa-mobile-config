package com.karcimsa.mobile

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.karcimsa.mobile.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy {
        getSharedPreferences(PushRegistration.PREFS_NAME, MODE_PRIVATE)
    }

    companion object {
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

        binding.salesNotificationsSwitch.isChecked =
            prefs.getBoolean(PushRegistration.PREF_NOTIFY_SALES, true)
        binding.cem1NotificationsSwitch.isChecked =
            prefs.getBoolean(PushRegistration.PREF_NOTIFY_CEM1, true)
        binding.keepScreenOnSwitch.isChecked =
            prefs.getBoolean(PREF_KEEP_SCREEN_ON, false)
        binding.versionText.text = "Sürüm ${BuildConfig.VERSION_NAME}"

        binding.salesNotificationsSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit()
                .putBoolean(PushRegistration.PREF_NOTIFY_SALES, enabled)
                .apply()
            updateTopic(enabled, "Satış bildirimleri")
        }

        binding.cem1NotificationsSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit()
                .putBoolean(PushRegistration.PREF_NOTIFY_CEM1, enabled)
                .apply()
            updateTopic(enabled, "CEM I bildirimleri")
        }

        binding.repairNotificationsRow.setOnClickListener {
            repairNotificationConnection()
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
            Toast.makeText(
                this,
                "Önbellek temizlenecek ve panel yeniden yüklenecek.",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        binding.closeSettingsButton.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationHealth()
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

    private fun updateTopic(enabled: Boolean, label: String) {
        binding.settingsStatusText.text = if (enabled) {
            "$label açılıyor..."
        } else {
            "$label kapatılıyor..."
        }

        PushRegistration.sync(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                binding.settingsStatusText.text = when {
                    result.success && enabled -> "$label açık ve Firebase kaydı hazır."
                    result.success && !enabled -> "$label kapalı."
                    else -> "Ayar kaydedildi; bağlantı kurulunca otomatik yeniden denenecek."
                }
            }
        }
    }

    private fun refreshNotificationHealth() {
        if (!notificationDeliveryAllowed()) {
            binding.settingsStatusText.text =
                "Telefon bildirim izni veya KARÇİMSA bildirim kanalı kapalı."
            return
        }

        binding.settingsStatusText.text = "Bildirim bağlantısı doğrulanıyor..."

        PushRegistration.sync(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                binding.settingsStatusText.text = if (result.success) {
                    "Bildirim bağlantısı hazır."
                } else {
                    "Bildirim bağlantısı kurulamadı. Onarmak için yukarıdaki satıra dokunun."
                }
            }
        }
    }

    private fun repairNotificationConnection() {
        if (!notificationDeliveryAllowed()) {
            openNotificationSettings()
            return
        }

        binding.settingsStatusText.text = "Bildirim bağlantısı onarılıyor..."

        PushRegistration.sync(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                binding.settingsStatusText.text = if (result.success) {
                    "Bildirim bağlantısı onarıldı."
                } else {
                    "Firebase bağlantısı kurulamadı. İnternet bağlantısını kontrol edin."
                }

                Toast.makeText(
                    this,
                    if (result.success) {
                        "Bildirim bağlantısı hazır."
                    } else {
                        "Bildirim bağlantısı henüz kurulamadı."
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun notificationDeliveryAllowed(): Boolean {
        val runtimePermissionAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        val appNotificationsAllowed =
            NotificationManagerCompat.from(this).areNotificationsEnabled()

        val channelAllowed =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(NotificationManager::class.java)
                val channel = manager.getNotificationChannel(
                    MainActivity.VEHICLE_CHANNEL_ID
                )
                channel == null ||
                    channel.importance != NotificationManager.IMPORTANCE_NONE
            } else {
                true
            }

        return runtimePermissionAllowed &&
            appNotificationsAllowed &&
            channelAllowed
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        }

        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }

        binding.settingsStatusText.text =
            "Android bildirim ayarlarında bildirimleri ve KARÇİMSA kanalını açın."
    }
}
