package com.fillearoom.waskitaexam

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etExamUrl: EditText
    private lateinit var badgePermission: TextView
    private lateinit var tvIndoDate: TextView
    private lateinit var tvRecentHeader: TextView
    private lateinit var tvRecentExamTitle: TextView
    private lateinit var tvRecentExamUrl: TextView
    private lateinit var tvRecentAction: TextView
    private lateinit var cardRecentExam: LinearLayout

    private val prefs by lazy { getSharedPreferences("waskita_prefs", Context.MODE_PRIVATE) }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            etExamUrl.setText(result.contents.trim())
            Toast.makeText(this, "QR Code terbaca", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateDashboardStatus()
        if (!granted) {
            Toast.makeText(this, "Izin kamera dibutuhkan untuk Scan QR", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etExamUrl = findViewById(R.id.etExamUrl)
        badgePermission = findViewById(R.id.badgePermission)
        tvIndoDate = findViewById(R.id.tvIndoDate)
        tvRecentHeader = findViewById(R.id.tvRecentHeader)
        tvRecentExamTitle = findViewById(R.id.tvRecentExamTitle)
        tvRecentExamUrl = findViewById(R.id.tvRecentExamUrl)
        tvRecentAction = findViewById(R.id.tvRecentAction)
        cardRecentExam = findViewById(R.id.cardRecentExam)

        val btnScanQr = findViewById<LinearLayout>(R.id.btnScanQr)
        val btnPasteUrl = findViewById<TextView>(R.id.btnPasteUrl)
        val btnStartExam = findViewById<Button>(R.id.btnStartExam)
        val btnSettings = findViewById<TextView>(R.id.btnSettings)

        updateIndonesianDate()
        updateRecentExamCard()

        if (!prefs.getBoolean("first_launch_prompted", false)) {
            prefs.edit().putBoolean("first_launch_prompted", true).apply()
            autoRequestInitialPermissions()
        }

        btnScanQr.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Arahkan kamera ke QR Code ujian")
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                }
                qrLauncher.launch(options)
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnPasteUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clipText.isNullOrEmpty()) {
                etExamUrl.setText(clipText)
            } else {
                Toast.makeText(this, "Papan klip kosong", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartExam.setOnClickListener {
            var inputUrl = etExamUrl.text.toString().trim()
            if (inputUrl.isEmpty()) {
                Toast.makeText(this, "Masukkan tautan atau pindai QR terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
                inputUrl = "https://$inputUrl"
            }

            val timeFormat = SimpleDateFormat("dd MMM, HH:mm", Locale("id", "ID"))
            val timestamp = timeFormat.format(Date())
            val fallbackHost = Uri.parse(inputUrl).host ?: "Sesi Ujian"

            prefs.edit()
                .putString("last_exam_url", inputUrl)
                .putString("last_exam_title", fallbackHost)
                .putString("last_exam_time", timestamp)
                .apply()

            updateRecentExamCard()

            val intent = Intent(this, ExamActivity::class.java).apply {
                putExtra("EXAM_URL", inputUrl)
            }
            startActivity(intent)
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        updateIndonesianDate()
        updateDashboardStatus()
        updateRecentExamCard()
    }

    private fun updateIndonesianDate() {
        val formatter = SimpleDateFormat("EEEE, dd MMM yyyy", Locale("id", "ID"))
        tvIndoDate.text = formatter.format(Date())
    }

    private fun updateRecentExamCard() {
        val savedUrl = prefs.getString("last_exam_url", "") ?: ""
        val savedTitle = prefs.getString("last_exam_title", "") ?: ""
        val savedTime = prefs.getString("last_exam_time", "") ?: ""

        if (savedUrl.isNotEmpty()) {
            tvRecentHeader.text = if (savedTime.isNotEmpty()) "UJIAN TERAKHIR • $savedTime" else "UJIAN TERAKHIR DIKERJAKAN"
            tvRecentExamTitle.text = if (savedTitle.isNotEmpty()) savedTitle else (Uri.parse(savedUrl).host ?: "Sesi Ujian")
            tvRecentExamUrl.text = savedUrl
            tvRecentExamUrl.visibility = View.VISIBLE
            tvRecentAction.visibility = View.VISIBLE

            cardRecentExam.setOnClickListener {
                etExamUrl.setText(savedUrl)
                Toast.makeText(this, "Tautan ujian terakhir dimuat", Toast.LENGTH_SHORT).show()
            }
        } else {
            tvRecentHeader.text = "UJIAN TERAKHIR DIKERJAKAN"
            tvRecentExamTitle.text = "Belum ada riwayat ujian"
            tvRecentExamUrl.visibility = View.GONE
            tvRecentAction.visibility = View.GONE
            cardRecentExam.setOnClickListener(null)
        }
    }

    private fun autoRequestInitialPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
        requestBatteryOptimization()
    }

    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isOverlayGranted(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun updateDashboardStatus() {
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val batOk = isBatteryUnrestricted()
        val dndOk = isDndGranted()
        val ovlOk = isOverlayGranted()

        if (camOk && batOk && dndOk && ovlOk) {
            badgePermission.text = "● Proteksi Penuh"
            badgePermission.setBackgroundResource(R.drawable.bg_pill_green)
            badgePermission.setTextColor(Color.parseColor("#15803D"))
        } else {
            badgePermission.text = "● Izin Perlu Dicek"
            badgePermission.setBackgroundColor(Color.parseColor("#FEF3C7"))
            badgePermission.setTextColor(Color.parseColor("#B45309"))
        }
    }

    private fun requestBatteryOptimization() {
        if (!isBatteryUnrestricted()) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val tvStatusCamera = dialogView.findViewById<TextView>(R.id.tvStatusCamera)
        val tvStatusBattery = dialogView.findViewById<TextView>(R.id.tvStatusBattery)
        val tvStatusDnd = dialogView.findViewById<TextView>(R.id.tvStatusDnd)
        val tvStatusOverlay = dialogView.findViewById<TextView>(R.id.tvStatusOverlay)

        setBadgeState(tvStatusCamera, ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        setBadgeState(tvStatusBattery, isBatteryUnrestricted())
        setBadgeState(tvStatusDnd, isDndGranted())
        setBadgeState(tvStatusOverlay, isOverlayGranted())

        dialogView.findViewById<LinearLayout>(R.id.btnPermCamera).setOnClickListener {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
            dialog.dismiss()
        }

        dialogView.findViewById<LinearLayout>(R.id.btnPermBattery).setOnClickListener {
            requestBatteryOptimization()
            dialog.dismiss()
        }

        dialogView.findViewById<LinearLayout>(R.id.btnPermDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            dialog.dismiss()
        }

        dialogView.findViewById<LinearLayout>(R.id.btnPermOverlay).setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.btnCheckWebView).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")))
            }
        }

        dialogView.findViewById<TextView>(R.id.btnTestAudio).setOnClickListener {
            testWarningSoundAndVibrate()
        }

        dialogView.findViewById<TextView>(R.id.btnClearCache).setOnClickListener {
            WebView(this).clearCache(true)
            Toast.makeText(this, "Cache sesi ujian dibersihkan", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun setBadgeState(badge: TextView, isActive: Boolean) {
        if (isActive) {
            badge.text = "✓ Aktif"
            badge.setBackgroundColor(Color.parseColor("#DCFCE7"))
            badge.setTextColor(Color.parseColor("#15803D"))
        } else {
            badge.text = "Atur →"
            badge.setBackgroundColor(Color.parseColor("#E4E4E7"))
            badge.setTextColor(Color.parseColor("#3F3F46"))
        }
    }

    private fun testWarningSoundAndVibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300)
        }

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = (maxVol * 0.20).toInt().coerceAtLeast(1)
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < targetVol) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
            val mp = MediaPlayer.create(this, R.raw.bell_warning)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (e: Exception) {
            Toast.makeText(this, "File audio belum dipasang di res/raw", Toast.LENGTH_SHORT).show()
        }
    }
}