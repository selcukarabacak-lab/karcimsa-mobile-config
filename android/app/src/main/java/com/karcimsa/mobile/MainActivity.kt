package com.karcimsa.mobile

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.view.animation.AccelerateDecelerateInterpolator
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
    private var truckAnimator: AnimatorSet? = null

    private var pendingFocusPlate: String? = null
    private var pendingFocusEventType: String? = null
    private var pendingFocusStartTime: String? = null

    companion object {
        const val VEHICLE_CHANNEL_ID = "karcimsa_vehicle_alerts"
        const val OLD_FCM_TOPIC = "karcimsa_ops"
        const val FCM_TOPIC_SALES = "karcimsa_sales"
        const val FCM_TOPIC_CEM1 = "karcimsa_cem1"
        const val PREF_NOTIFY_SALES = "notify_sales"
        const val PREF_NOTIFY_CEM1 = "notify_cem1"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_PLATE = "plate"
        const val EXTRA_START_TIME = "start_time"
        private const val NOTIFICATION_PERMISSION_REQUEST = 1101
        private const val RECONNECT_CHECK_MS = 6000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        captureNotificationIntent(intent)
        createVehicleNotificationChannel()
        requestNotificationPermissionIfNeeded()
        syncNotificationTopics()
        setupWebView()

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.webView.clearCache(true)
        binding.webView.clearHistory()
        binding.swipeRefresh.visibility = View.INVISIBLE
        binding.webView.visibility = View.INVISIBLE

        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
            reconnectJob?.cancel()
            resolveHealthyPanel(showLoading = true)
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

        showLoading()
        resolveHealthyPanel(showLoading = false)
    }

    override fun onResume() {
        super.onResume()
        syncNotificationTopics()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationIntent(intent)

        if (initialPageLoaded) {
            focusNotificationTarget()
        } else {
            resolveHealthyPanel(showLoading = true)
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

        val soundUri = Uri.parse("android.resource://$packageName/${R.raw.karcimsa_vehicle_alert}")
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

    private fun syncNotificationTopics() {
        val messaging = FirebaseMessaging.getInstance()

        messaging.unsubscribeFromTopic(OLD_FCM_TOPIC)

        val salesEnabled = prefs.getBoolean(PREF_NOTIFY_SALES, true)
        val cem1Enabled = prefs.getBoolean(PREF_NOTIFY_CEM1, true)

        if (salesEnabled) messaging.subscribeToTopic(FCM_TOPIC_SALES)
        else messaging.unsubscribeFromTopic(FCM_TOPIC_SALES)

        if (cem1Enabled) messaging.subscribeToTopic(FCM_TOPIC_CEM1)
        else messaging.unsubscribeFromTopic(FCM_TOPIC_CEM1)
    }

    private fun startTruckAnimation() {
        if (truckAnimator?.isRunning == true) return

        val road = ObjectAnimator.ofFloat(binding.truckContainer, View.TRANSLATION_X, -42f, 42f).apply {
            duration = 1250
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

        val bounce = ObjectAnimator.ofFloat(binding.truckContainer, View.TRANSLATION_Y, -3f, 3f).apply {
            duration = 330
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        val glow = ObjectAnimator.ofFloat(binding.truckContainer, View.ALPHA, 0.86f, 1f).apply {
            duration = 650
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        truckAnimator = AnimatorSet().apply {
            playTogether(road, bounce, glow)
            start()
        }
    }

    private fun stopTruckAnimation() {
        truckAnimator?.cancel()
        truckAnimator = null
        binding.truckContainer.translationX = 0f
        binding.truckContainer.translationY = 0f
        binding.truckContainer.alpha = 1f
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
                binding.swipeRefresh.isRefreshing = false
                binding.swipeRefresh.visibility = View.INVISIBLE
                binding.webView.visibility = View.INVISIBLE
                showLoading()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.swipeRefresh.isRefreshing = false
                if (!mainFrameFailed && !url.isNullOrBlank() && url != "about:blank") {
                    initialPageLoaded = true
                    reconnectJob?.cancel()
                    stopTruckAnimation()
                    binding.statusPanel.visibility = View.GONE
                    binding.swipeRefresh.visibility = View.VISIBLE
                    binding.webView.visibility = View.VISIBLE
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
              function norm(v){ return String(v || '').replace(/\s+/g,' ').trim().toUpperCase(); }
              function findTarget(){
                tries++;
                const target = norm(targetPlate);
                const selectors = eventType === 'cem1_entry'
                  ? ['#trucks .truck','.truck','[class*="truck"]','tr','[class*="card"]']
                  : ['[class*="waiting"]','[class*="truck"]','.truck','tr','[class*="card"]'];
                let candidates=[];
                selectors.forEach(function(sel){ try { candidates=candidates.concat(Array.from(document.querySelectorAll(sel))); } catch(e){} });
                if(!candidates.length) candidates=Array.from(document.querySelectorAll('div,li,tr'));
                const seen=new Set();
                candidates=candidates.filter(function(el){
                  if(!el || seen.has(el)) return false;
                  seen.add(el);
                  return norm(el.innerText).includes(target);
                });
                let el=candidates.find(function(x){
                  const txt=norm(x.innerText);
                  return startTime ? txt.includes(norm(startTime)) : true;
                }) || candidates[0];
                if(!el){ if(tries<14) setTimeout(findTarget,500); return; }
                const row=el.closest('.truck,tr,[class*="waiting"],[class*="vehicle"],[class*="card"]') || el;
                row.scrollIntoView({behavior:'smooth',block:'center'});
                const oo=row.style.outline, os=row.style.boxShadow, ot=row.style.transition;
                row.style.transition='outline .2s ease, box-shadow .2s ease';
                row.style.outline='3px solid rgba(255,193,7,.95)';
                row.style.boxShadow='0 0 0 6px rgba(255,193,7,.18),0 8px 28px rgba(0,0,0,.35)';
                setTimeout(function(){ row.style.outline=oo; row.style.boxShadow=os; row.style.transition=ot; },3500);
              }
              setTimeout(findTarget,650);
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
        pendingFocusPlate = null
        pendingFocusEventType = null
        pendingFocusStartTime = null
    }

    private fun handleMainFrameFailure() {
        mainFrameFailed = true
        initialPageLoaded = false
        binding.swipeRefresh.visibility = View.INVISIBLE
        binding.webView.visibility = View.INVISIBLE
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
                binding.swipeRefresh.visibility = View.INVISIBLE
                binding.webView.visibility = View.INVISIBLE
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
        showLoading()
        binding.swipeRefresh.visibility = View.INVISIBLE
        binding.webView.visibility = View.INVISIBLE
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
            c.setRequestProperty("User-Agent", "KARCIMSA-Mobile/1.1.6")
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
            c.setRequestProperty("User-Agent", "KARCIMSA-Mobile/1.1.6")
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
        binding.swipeRefresh.isRefreshing = false
        binding.swipeRefresh.visibility = View.INVISIBLE
        binding.webView.visibility = View.INVISIBLE
        binding.statusTitle.text = "Sunucuya Bağlanırken Lütfen Bekleyin..."
        binding.statusText.text = "KARÇİMSA sunucusunun güvenli bağlantısı hazırlanıyor."
        binding.waitHint.text = "Bu işlem 1-2 dakika sürebilir."
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.statusPanel.visibility = View.VISIBLE
        startTruckAnimation()
    }

    private fun showReconnecting() {
        binding.swipeRefresh.isRefreshing = false
        binding.swipeRefresh.visibility = View.INVISIBLE
        binding.webView.visibility = View.INVISIBLE
        binding.statusTitle.text = "Sunucuya Bağlanırken Lütfen Bekleyin..."
        binding.statusText.text = "Cloudflare bağlantısı hazırlanıyor ve güncel sunucu adresi kontrol ediliyor."
        binding.waitHint.text = "Bu işlem 1-2 dakika sürebilir."
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.statusPanel.visibility = View.VISIBLE
        startTruckAnimation()
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        stopTruckAnimation()
        super.onDestroy()
    }
}
