package com.karcimsa.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.karcimsa.mobile.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var mainFrameFailed = false

    private var pendingFocusPlate: String? = null
    private var pendingFocusEventType: String? = null
    private var pendingFocusStartTime: String? = null

    companion object {
        const val VEHICLE_CHANNEL_ID = "karcimsa_vehicle_alerts"
        const val FCM_TOPIC = "karcimsa_ops"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_PLATE = "plate"
        const val EXTRA_START_TIME = "start_time"
        private const val NOTIFICATION_PERMISSION_REQUEST = 1101
        private const val RECONNECT_CHECK_MS = 8000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        captureNotificationIntent(intent)
        createVehicleNotificationChannel()
        requestNotificationPermissionIfNeeded()
        subscribeToPushTopic()
        setupWebView()

        binding.webView.clearCache(true)
        binding.webView.clearHistory()

        binding.swipeRefresh.setOnRefreshListener {
            reconnectJob?.cancel()
            resolveHealthyPanel(showLoading = false)
        }
        binding.retryButton.setOnClickListener {
            reconnectJob?.cancel()
            resolveHealthyPanel(showLoading = true)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        resolveHealthyPanel(showLoading = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationIntent(intent)

        if (initialPageLoaded) {
            focusNotificationTarget()
        } else {
            resolveHealthyPanel(showLoading = false)
        }
    }

    private fun captureNotificationIntent(sourceIntent: Intent?) {
        val plate = sourceIntent?.getStringExtra(EXTRA_PLATE)?.trim().orEmpty()
        val eventType = sourceIntent?.getStringExtra(EXTRA_EVENT_TYPE)?.trim().orEmpty()
        val startTime = sourceIntent?.getStringExtra(EXTRA_START_TIME)?.trim().orEmpty()

        if (plate.isNotBlank()) {
            pendingFocusPlate = plate
            pendingFocusEventType = eventType
            pendingFocusStartTime = startTime
        }
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
                mainFrameFailed = false
                binding.swipeRefresh.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.swipeRefresh.isRefreshing = false
                if (!mainFrameFailed && !url.isNullOrBlank() && url != "about:blank") {
                    initialPageLoaded = true
                    reconnectJob?.cancel()
                    binding.statusPanel.visibility = View.GONE
                    focusNotificationTarget()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) handleMainFrameFailure()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 500) {
                    handleMainFrameFailure()
                }
            }
        }
    }

    private fun focusNotificationTarget() {
        val plate = pendingFocusPlate?.trim().orEmpty()
        if (plate.isBlank()) return

        val eventType = pendingFocusEventType.orEmpty()
        val startTime = pendingFocusStartTime.orEmpty()

        val plateJs = JSONObject.quote(plate)
        val eventJs = JSONObject.quote(eventType)
        val startJs = JSONObject.quote(startTime)

        val script = """
            (function(){
              const targetPlate = $plateJs;
              const eventType = $eventJs;
              const startTime = $startJs;
              let tries = 0;

              function norm(v){
                return String(v || '').replace(/\s+/g,' ').trim().toUpperCase();
              }

              function findTarget(){
                tries++;
                const target = norm(targetPlate);

                const preferredSelectors = eventType === 'cem1_entry'
                  ? ['#trucks .truck', '.truck', '[class*="truck"]', 'tr', '[class*="card"]']
                  : ['[class*="waiting"]', '[class*="truck"]', '.truck', 'tr', '[class*="card"]'];

                let candidates = [];
                preferredSelectors.forEach(function(sel){
                  try { candidates = candidates.concat(Array.from(document.querySelectorAll(sel))); } catch(e) {}
                });

                if (!candidates.length) {
                  candidates = Array.from(document.querySelectorAll('div,li,tr'));
                }

                const seen = new Set();
                candidates = candidates.filter(function(el){
                  if (!el || seen.has(el)) return false;
                  seen.add(el);
                  return norm(el.innerText).includes(target);
                });

                let el = candidates.find(function(x){
                  const txt = norm(x.innerText);
                  return startTime ? txt.includes(norm(startTime)) : true;
                }) || candidates[0];

                if (!el) {
                  if (tries < 14) setTimeout(findTarget, 500);
                  return;
                }

                const row = el.closest('.truck, tr, [class*="waiting"], [class*="vehicle"], [class*="card"]') || el;
                row.scrollIntoView({behavior:'smooth', block:'center'});

                const oldOutline = row.style.outline;
                const oldShadow = row.style.boxShadow;
                const oldTransition = row.style.transition;
                row.style.transition = 'outline .2s ease, box-shadow .2s ease';
                row.style.outline = '3px solid rgba(255,193,7,.95)';
                row.style.boxShadow = '0 0 0 6px rgba(255,193,7,.18), 0 8px 28px rgba(0,0,0,.35)';

                setTimeout(function(){
                  row.style.outline = oldOutline;
                  row.style.boxShadow = oldShadow;
                  row.style.transition = oldTransition;
                }, 3500);
              }

              setTimeout(findTarget, 650);
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)

        pendingFocusPlate = null
        pendingFocusEventType = null
        pendingFocusStartTime = null
    }

    private fun handleMainFrameFailure() {
        mainFrameFailed = true
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        showReconnecting()
        startHealthWatch()
    }

    private fun resolveHealthyPanel(showLoading: Boolean) {
        if (showLoading) showLoading()

        lifecycleScope.launch {
            val remote = fetchRemotePanelUrl()
            val cached = prefs.getString("last_working_url", null)
            val candidates = linkedSetOf<String>()
            if (!remote.isNullOrBlank()) candidates.add(remote)
            if (!cached.isNullOrBlank()) candidates.add(cached)

            val healthy = candidates.firstOrNull { isPanelHealthy(it) }

            if (healthy != null) {
                prefs.edit().putString("last_working_url", healthy).apply()
                currentPanelUrl = healthy
                binding.webView.clearCache(true)
                loadFreshUrl(healthy)
            } else {
                binding.webView.stopLoading()
                binding.webView.loadUrl("about:blank")
                showReconnecting()
                startHealthWatch()
            }
        }
    }

    private fun startHealthWatch() {
        if (isFinishing || isDestroyed) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = lifecycleScope.launch {
            while (!isFinishing && !isDestroyed) {
                delay(RECONNECT_CHECK_MS)

                val remote = fetchRemotePanelUrl()
                val cached = prefs.getString("last_working_url", null)
                val candidates = linkedSetOf<String>()
                if (!remote.isNullOrBlank()) candidates.add(remote)
                if (!cached.isNullOrBlank()) candidates.add(cached)

                val healthy = candidates.firstOrNull { isPanelHealthy(it) }
                if (healthy != null) {
                    prefs.edit().putString("last_working_url", healthy).apply()
                    currentPanelUrl = healthy
                    binding.webView.clearCache(true)
                    loadFreshUrl(healthy)
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

    private suspend fun isPanelHealthy(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val healthUrl = baseUrl.trimEnd('/') + "/data/latest.json?health_ts=${System.currentTimeMillis()}"
            val c = URL(healthUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 3500
            c.readTimeout = 3500
            c.instanceFollowRedirects = true
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("User-Agent", "KARCIMSA-Mobile/1.1.4")
            val code = c.responseCode
            c.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchRemotePanelUrl(): String? = withContext(Dispatchers.IO) {
        try {
            val c = URL("$configUrl?ts=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
            c.connectTimeout = 3500
            c.readTimeout = 3500
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            c.setRequestProperty("Pragma", "no-cache")
            c.setRequestProperty("User-Agent", "KARCIMSA-Mobile/1.1.4")

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
        binding.statusText.text = "KARÇİMSA paneli kontrol ediliyor..."
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.statusPanel.visibility = View.VISIBLE
    }

    private fun showReconnecting() {
        binding.swipeRefresh.isRefreshing = false
        binding.statusTitle.text = "Sunucu yeniden bağlanıyor"
        binding.statusText.text = "Cloudflare bağlantısı geçici olarak kapalı. Yeni bağlantı hazır olduğunda uygulama otomatik açılacak."
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.VISIBLE
        binding.statusPanel.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        super.onDestroy()
    }
}
