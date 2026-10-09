package br.com.alertaporlocal

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.PowerManager
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.util.Log
import android.location.LocationListener

class LocationMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private var lastAlertAt = 0L
    private val loop = object : Runnable {
        override fun run() {
            checkLocation()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("alerta", Context.MODE_PRIVATE)
        createChannel()
        startForeground(101, notification("Monitorando o local configurado"))
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun checkLocation() {
        if (!prefs.getBoolean("active", false)) {
            stopSelf()
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val loc = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
                      catch (_: Exception) { null }
                ?: try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
                ?: return
            val target = Location("target").apply {
                latitude = prefs.getFloat("lat", 0f).toDouble()
                longitude = prefs.getFloat("lon", 0f).toDouble()
            }
            val distance = loc.distanceTo(target)
            val radius = prefs.getInt("radius", 50).toFloat()
            val inside = distance <= radius
            val wasInside = prefs.getBoolean("inside", false)
            val ringing = prefs.getBoolean("ringing", false)

            if (inside && !wasInside && !ringing) {
                prefs.edit().putBoolean("ringing", true).apply()
                lastAlertAt = SystemClock.elapsedRealtime()
                val alarm = Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                alarm.putExtra("message", prefs.getString("message", "Você chegou ao local"))
                val pi = PendingIntent.getActivity(this, 222, alarm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(222, Notification.Builder(this, "alerts")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Alerta por Local")
                    .setContentText(prefs.getString("message", "Você chegou ao local"))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setAutoCancel(false)
                    .build())
                try { startActivity(alarm) } catch (_: Exception) {}
            }
            if (!inside && wasInside) {
                // Só rearma depois de sair do perímetro.
                prefs.edit().putBoolean("ringing", false).apply()
                getSystemService(NotificationManager::class.java).cancel(222)
            }
            prefs.edit().putBoolean("inside", inside).apply()
        } catch (e: Exception) {
            Log.e("AlertaPorLocal", "Falha ao verificar localização", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("monitor", "Monitoramento", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("alerts", "Alarmes de localização", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisos ao entrar no perímetro configurado"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(false)
            })
        }
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "monitor")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Alerta por Local")
            .setContentText(text).setContentIntent(pi).setOngoing(true).build()
        else Notification.Builder(this).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Alerta por Local").setContentText(text).setContentIntent(pi).setOngoing(true).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { handler.removeCallbacks(loop); super.onDestroy() }
}
