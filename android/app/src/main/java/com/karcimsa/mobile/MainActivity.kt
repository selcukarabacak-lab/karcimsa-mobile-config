package com.karcimsa.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Job
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.karcimsa.mobile.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("karcimsa_mobile", MODE_PRIVATE) }
    private val configUrl = "https://raw.githubusercontent.com/selcukarabacak-lab/karcimsa-mobile-config/main/cloudflare_url.json"
    private var currentPanelUrl: String? = null
    private var initialPageLoaded = false
    private var reconnectJob: Job? = null

    companion object {
        const val VEHICLE_CHANNEL_ID = "karcimsa_vehicle_alerts"
        const val FCM_TOPIC = "karcimsa_ops"
        private const val NOTIFICATION_PERMISSION_REQUEST = 1101
        private const val RECONNECT_DELAY_MS = 4000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createVehicleNotificationChannel()
        requestNotificationPermissionIfNeeded()
        subscribeToPushTopic()
        setupWebView()

        binding.webView.clearCache(true)
        binding.webView.clearHistory()

        binding.swipeRefresh.setOnRefreshListener { resolveAndOpenPanel(true, false) }
        binding.retryButton.setOnClickListener {
            reconnectJob?.cancel()
            resolveAndOpenPanel(true, true)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })
        resolveAndOpenPanel()
    }

    private fun createVehicleNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val soundUri = Uri.parse(
            "android.resource://$packageName/${R.raw.karcimsa_vehicle_alert}"
        )
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            VEHICLE_CHANNEL_ID,
            "KARÇİMSA Araç Bildirimleri",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "CEM I ve satış aracı giriş bildirimleri"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 90, 55, 120)
            setSound(soundUri, audioAttributes)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    private fun subscribeToPushTopic() {
        FirebaseMessaging.getInstance().subscribeToTopic(FCM_TOPIC)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.swipeRefresh.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                initialPageLoaded = true
                binding.swipeRefresh.isRefreshing = false
                reconnectJob?.cancel()
                binding.statusPanel.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    scheduleReconnect()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 500) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (isFinishing || isDestroyed) return
        if (reconnectJob?.isActive == true) return

        binding.swipeRefresh.isRefreshing = false
        binding.statusTitle.text = "Bağlantı yenileniyor"
        binding.statusText.text = "Cloudflare tüneli kontrol ediliyor. Güncel adres otomatik alınacak..."
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.statusPanel.visibility = View.VISIBLE

        reconnectJob = lifecycleScope.launch {
            while (true) {
                delay(RECONNECT_DELAY_MS)
                val remote = fetchRemotePanelUrl()
                if (!remote.isNullOrBlank()) {
                    currentPanelUrl = remote
                    prefs.edit().putString("last_working_url", remote).apply()
                    binding.webView.clearCache(true)
                    loadFreshUrl(remote)
                    return@launch
                }
            }
        }
    }

    private fun loadFreshUrl(target: String) {
        val separator = if (target.contains("?")) "&" else "?"
        val freshTarget = "$target${separator}app_ts=${System.currentTimeMillis()}"
        binding.webView.loadUrl(freshTarget)
    }

    private fun resolveAndOpenPanel(force: Boolean = false, showLoading: Boolean = true) {
        if (showLoading && !initialPageLoaded) showLoading()

        lifecycleScope.launch {
            val remote = fetchRemotePanelUrl()
            val fallback = prefs.getString("last_working_url", null)
            val target = remote ?: fallback

            if (target.isNullOrBlank()) {
                showError()
                scheduleReconnect()
                return@launch
            }

            prefs.edit().putString("last_working_url", target).apply()

            if (force || currentPanelUrl != target || binding.webView.url.isNullOrBlank()) {
                currentPanelUrl = target
                loadFreshUrl(target)
            } else {
                binding.webView.clearCache(true)
                loadFreshUrl(target)
            }
        }
    }

    private suspend fun fetchRemotePanelUrl(): String? = withContext(Dispatchers.IO) {
        try {
            val c = URL("$configUrl?ts=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            c.setRequestProperty("Pragma", "no-cache")
            c.setRequestProperty("User-Agent", "KARCIMSA-Mobile/1.1.1")

            c.inputStream.bufferedReader().use {
                JSONObject(it.readText()).optString("url").trim().takeIf { u ->
                    u.startsWith("https://") && u.contains(".trycloudflare.com")
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showLoading() {
        binding.statusTitle.text = "Sunucu bağlantısı kuruluyor"
        binding.statusText.text = "Güncel KARÇİMSA adresi alınıyor..."
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.statusPanel.visibility = View.VISIBLE
    }

    private fun showError() {
        binding.statusTitle.text = "Bağlantı kurulamadı"
        binding.statusText.text = "Sunucu geçici olarak erişilemiyor. Uygulama otomatik yeniden bağlanmayı deneyecek."
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.VISIBLE
        binding.statusPanel.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        super.onDestroy()
    }
}
