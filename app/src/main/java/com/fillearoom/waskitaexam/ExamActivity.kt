package com.fillearoom.waskitaexam

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class ExamActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvWarningBanner: TextView

    private var violationCount = 0
    private var isExitingAllowed = false
    private var isExamReady = false
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Blokir Screenshot & Rekam Layar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        // Jaga layar tetap menyala selama ujian
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_exam)

        // Mode Layar Penuh (Sembunyikan Status Bar & Navigasi)
        enableImmersiveMode()

        // Kosongkan Clipboard saat mulai ujian
        clearSystemClipboard()

        // Aktifkan Mode Jangan Ganggu (DND) jika izin tersedia
        setDoNotDisturb(true)

        webView = findViewById(R.id.examWebView)
        progressBar = findViewById(R.id.examProgressBar)
        tvWarningBanner = findViewById(R.id.tvWarningBanner)

        val btnRefresh = findViewById<TextView>(R.id.btnRefreshExam)
        val btnExit = findViewById<TextView>(R.id.btnExitExam)

        setupSecureWebView()

        val examUrl = intent.getStringExtra("EXAM_URL") ?: "https://www.google.com"
        webView.loadUrl(examUrl)

        btnRefresh.setOnClickListener {
            webView.reload()
        }

        btnExit.setOnClickListener {
            showExitConfirmationDialog()
        }

        // Blokir tombol Back fisik/gestur agar tidak keluar tidak sengaja
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    showExitConfirmationDialog()
                }
            }
        })

        // Aktifkan Screen Pinning / Lock Task Mode
        try {
            startLockTask()
        } catch (_: Exception) {
        }

        // Beri jeda 2 detik saat awal masuk sebelum sensor pelanggaran aktif
        handler.postDelayed({ isExamReady = true }, 2000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupSecureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // Blokir tekan lama (Copy-Paste menu bawaan Android)
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
        webView.isHapticFeedbackEnabled = false

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank() && !title.startsWith("http")) {
                    getSharedPreferences("waskita_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_exam_title", title.trim())
                        .apply()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAntiCopyPasteScript()
            }
        }
    }

    // Suntikan JavaScript & CSS Anti-Copas + Blokir Autocomplete/AI Suggestion pada form
    private fun injectAntiCopyPasteScript() {
        val js = """
            (function() {
                var style = document.createElement('style');
                style.innerHTML = '* { -webkit-user-select: none !important; user-select: none !important; } input, textarea { -webkit-user-select: text !important; user-select: text !important; }';
                document.head.appendChild(style);

                ['copy', 'cut', 'paste', 'contextmenu'].forEach(function(evt) {
                    document.addEventListener(evt, function(e) {
                        e.preventDefault();
                        return false;
                    }, true);
                });

                var inputs = document.querySelectorAll('input, textarea');
                inputs.forEach(function(el) {
                    el.setAttribute('autocomplete', 'off');
                    el.setAttribute('autocorrect', 'off');
                    el.setAttribute('autocapitalize', 'off');
                    el.setAttribute('spellcheck', 'false');
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // Deteksi jika siswa mencoba keluar atau membuka aplikasi mengambang (Floating Apps)
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        } else if (isExamReady && !isExitingAllowed) {
            triggerViolationAlert()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isExamReady && !isExitingAllowed) {
            triggerViolationAlert()
        }
    }

    private fun triggerViolationAlert() {
        violationCount++
        clearSystemClipboard()

        // Tampilkan banner peringatan di layar
        tvWarningBanner.text = "⚠ Peringatan Pelanggaran #$violationCount: Dilarang meninggalkan layar ujian!"
        tvWarningBanner.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            tvWarningBanner.visibility = View.GONE
        }, 4000)

        // Pelanggaran 1 & 2: Getar senyap; Pelanggaran ke-3 dst: Getar + Lonceng Angin
        vibrateDevice(if (violationCount >= 3) 600 else 300)

        if (violationCount >= 3) {
            playSoundWithAutoVolume(R.raw.bell_warning, 0.20)
        }
    }

    private fun showExitConfirmationDialog() {
        // Izinkan fokus sementara untuk dialog konfirmasi internal
        isExitingAllowed = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("Akhiri Ujian?")
            .setMessage("Pastikan semua jawaban sudah dikirim sebelum keluar dari ruang ujian.")
            .setPositiveButton("Ya, Keluar") { _, _ ->
                playSoundWithAutoVolume(R.raw.bell_exit, 0.25)
                try {
                    stopLockTask()
                } catch (_: Exception) {
                }
                setDoNotDisturb(false)
                handler.postDelayed({
                    finish()
                }, 450)
            }
            .setNegativeButton("Batal") { d, _ ->
                isExitingAllowed = false
                d.dismiss()
            }
            .setOnCancelListener {
                isExitingAllowed = false
            }
            .create()

        dialog.show()
    }

    private fun playSoundWithAutoVolume(rawResId: Int, volumeRatio: Double) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = (maxVol * volumeRatio).toInt().coerceAtLeast(1)
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < targetVol) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
            val mp = MediaPlayer.create(this, rawResId)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (_: Exception) {
        }
    }

    private fun vibrateDevice(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun clearSystemClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {
        }
    }

    private fun setDoNotDisturb(enable: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(
                    if (enable) NotificationManager.INTERRUPTION_FILTER_NONE
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}