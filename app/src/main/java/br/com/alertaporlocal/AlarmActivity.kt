package br.com.alertaporlocal

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.app.NotificationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

class AlarmActivity : Activity() {
    private var tone: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val beep = object : Runnable {
        override fun run() {
            try { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900) } catch (_: Exception) {}
            handler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.statusBarColor = Color.rgb(130, 0, 0)
        tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        handler.post(beep)
        val message = intent.getStringExtra("message")
            ?: getSharedPreferences("alerta", Context.MODE_PRIVATE).getString("message", "Você chegou ao local")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 40, 28, 40)
            setBackgroundColor(Color.rgb(170, 0, 0))
        }
        root.addView(TextView(this).apply {
            text = message
            textSize = 38f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "PARAR ALARME"
            textSize = 22f
            setOnClickListener {
                handler.removeCallbacks(beep)
                tone?.stopTone()
                getSharedPreferences("alerta", Context.MODE_PRIVATE).edit().putBoolean("ringing", false).apply()
                getSystemService(NotificationManager::class.java).cancel(222)
                finish()
            }
        }, LinearLayout.LayoutParams(-1, 72))
        setContentView(root)
    }

    override fun onDestroy() {
        handler.removeCallbacks(beep)
        try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
