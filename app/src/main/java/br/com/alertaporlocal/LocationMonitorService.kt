package br.com.alertaporlocal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class LocationMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var locationManager: LocationManager
    private val registeredProviders = mutableSetOf<String>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Não use posições antigas em cache: elas podem pertencer a outro lugar.
            val ageMs = System.currentTimeMillis() - location.time
            if (ageMs < 0L || ageMs > 60_000L) return
            evaluateLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            handler.post { ensureLocationUpdates() }
        }

        override fun onProviderDisabled(provider: String) {
            registeredProviders.remove(provider)
        }

        @Deprecated("Deprecated by Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val updateCheck = object : Runnable {
        override fun run() {
            if (!prefs.getBoolean("active", false)) {
                stopSelf()
                return
            }
            ensureLocationUpdates()
            handler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("alerta", Context.MODE_PRIVATE)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
        startForeground(101, notification("Monitorando o local configurado"))
        ensureLocationUpdates()
        handler.postDelayed(updateCheck, 15_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureLocationUpdates()
        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocationUpdates() {
        if (!prefs.getBoolean("active", false)) {
            stopSelf()
            return
        }
        if (!hasLocationPermission()) return

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            if (provider in registeredProviders) continue
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        5_000L,
                        0f,
                        locationListener,
                        Looper.getMainLooper()
                    )
                    registeredProviders.add(provider)
                }
            } catch (e: SecurityException) {
                Log.w("AlertaPorLocal", "Sem permissão para usar $provider", e)
            } catch (e: IllegalArgumentException) {
                Log.w("AlertaPorLocal", "Provedor indisponível: $provider", e)
            } catch (e: Exception) {
                Log.e("AlertaPorLocal", "Não foi possível solicitar localização de $provider", e)
            }
        }
    }

    private fun evaluateLocation(loc: Location) {
        if (!prefs.getBoolean("active", false)) {
            stopSelf()
            return
        }
        try {
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
                val alarm = Intent(this, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("message", prefs.getString("message", "Você chegou ao local"))
                }
                val pi = PendingIntent.getActivity(
                    this, 222, alarm,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(
                    222,
                    Notification.Builder(this, "alerts")
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentTitle("Alerta por Local")
                        .setContentText(prefs.getString("message", "Você chegou ao local"))
                        .setCategory(Notification.CATEGORY_ALARM)
                        .setPriority(Notification.PRIORITY_MAX)
                        .setFullScreenIntent(pi, true)
                        .setContentIntent(pi)
                        .setAutoCancel(false)
                        .build()
                )
                try {
                    startActivity(alarm)
                } catch (e: Exception) {
                    Log.w("AlertaPorLocal", "O Android não permitiu abrir a tela diretamente", e)
                }
            }

            // Só rearma quando uma localização nova confirma que saiu do raio.
            if (!inside && wasInside) {
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
            nm.createNotificationChannel(
                NotificationChannel("monitor", "Monitoramento", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel("alerts", "Alarmes de localização", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos ao entrar no perímetro configurado"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(false)
                }
            )
        }
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "monitor")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Alerta por Local")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Alerta por Local")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(updateCheck)
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        registeredProviders.clear()
        super.onDestroy()
    }
}
