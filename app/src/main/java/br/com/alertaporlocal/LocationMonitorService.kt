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

/**
 * Monitoramento adaptativo para reduzir consumo:
 * - usa o provedor de rede (baixo consumo) para acompanhar a região;
 * - liga o GPS de alta precisão apenas quando a estimativa fica a até 1,5 km do destino;
 * - desliga o GPS quando a estimativa confiável indica que está a mais de 2,5 km;
 * - exige uma posição GPS razoavelmente precisa para disparar o alarme.
 */
class LocationMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var locationManager: LocationManager
    private val registeredProviders = mutableSetOf<String>()
    private var lastGpsLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val ageMs = System.currentTimeMillis() - location.time
            if (ageMs < 0L || ageMs > 120_000L) return

            when (location.provider) {
                LocationManager.NETWORK_PROVIDER -> {
                    updateGpsPolicy(location)
                    // A rede serve para aproximar o telefone do destino, não para disparar
                    // o alarme dentro de 50 m, pois sua precisão pode ser insuficiente.
                    if (prefs.getBoolean("inside", false)) {
                        val target = targetLocation()
                        if (location.distanceTo(target) > prefs.getInt("radius", 50) + 300f) {
                            // Uma posição de rede muito distante ajuda a rearmar caso o GPS
                            // tenha perdido sinal durante a saída do local.
                            prefs.edit().putBoolean("inside", false).putBoolean("ringing", false).apply()
                            getSystemService(NotificationManager::class.java).cancel(222)
                        }
                    }
                }
                LocationManager.GPS_PROVIDER -> {
                    lastGpsLocation = location
                    updateGpsPolicy(location)
                    evaluateGpsLocation(location)
                }
            }
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
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("alerta", Context.MODE_PRIVATE)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
        startForeground(101, notification("Monitorando o local configurado"))
        ensureLocationUpdates()
        handler.postDelayed(updateCheck, 60_000L)
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

        try {
            // Provedor de rede: baixa frequência e deslocamento mínimo para poupar bateria.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) &&
                LocationManager.NETWORK_PROVIDER !in registeredProviders) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    60_000L,
                    100f,
                    locationListener,
                    Looper.getMainLooper()
                )
                registeredProviders.add(LocationManager.NETWORK_PROVIDER)
            }

            // Se não há provedor de rede, é necessário recorrer ao GPS como alternativa.
            if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) &&
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                registerGps()
            } else {
                // Uma localização de rede recente pode ativar o GPS perto do destino.
                val networkLast = try {
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (_: Exception) { null }
                if (networkLast != null && isFresh(networkLast, 180_000L)) updateGpsPolicy(networkLast)
            }
        } catch (e: SecurityException) {
            Log.w("AlertaPorLocal", "Permissão de localização ausente", e)
        } catch (e: Exception) {
            Log.e("AlertaPorLocal", "Não foi possível configurar a localização", e)
        }
    }

    private fun updateGpsPolicy(location: Location) {
        val distance = location.distanceTo(targetLocation())
        if (distance <= 1_500f) {
            registerGps()
        } else if (distance >= 2_500f) {
            unregisterGps()
        }
    }

    private fun registerGps() {
        if (!hasLocationPermission() || LocationManager.GPS_PROVIDER in registeredProviders) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    10_000L,
                    20f,
                    locationListener,
                    Looper.getMainLooper()
                )
                registeredProviders.add(LocationManager.GPS_PROVIDER)
            }
        } catch (e: SecurityException) {
            Log.w("AlertaPorLocal", "Sem permissão para GPS", e)
        } catch (e: Exception) {
            Log.w("AlertaPorLocal", "Não foi possível ativar o GPS", e)
        }
    }

    private fun unregisterGps() {
        if (LocationManager.GPS_PROVIDER !in registeredProviders) return
        try {
            locationManager.removeUpdates(locationListener)
            registeredProviders.remove(LocationManager.GPS_PROVIDER)
            // removeUpdates remove todos os provedores desse listener; registra novamente
            // a rede para que o monitoramento de baixo consumo continue.
            registeredProviders.remove(LocationManager.NETWORK_PROVIDER)
            if (hasLocationPermission() && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    60_000L,
                    100f,
                    locationListener,
                    Looper.getMainLooper()
                )
                registeredProviders.add(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            Log.w("AlertaPorLocal", "Não foi possível reduzir o uso do GPS", e)
        }
    }

    private fun targetLocation() = Location("target").apply {
        latitude = prefs.getFloat("lat", 0f).toDouble()
        longitude = prefs.getFloat("lon", 0f).toDouble()
    }

    private fun isFresh(location: Location, maxAgeMs: Long): Boolean {
        val age = System.currentTimeMillis() - location.time
        return age in 0..maxAgeMs
    }

    private fun evaluateGpsLocation(loc: Location) {
        if (!prefs.getBoolean("active", false)) {
            stopSelf()
            return
        }
        // Evita falsos alertas quando o GPS ainda está muito impreciso.
        if (loc.hasAccuracy() && loc.accuracy > 100f) return
        try {
            val distance = loc.distanceTo(targetLocation())
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

            // Rearma ao confirmar que saiu do raio. A rede também rearma se indicar
            // que o aparelho já está bem longe, por segurança quando o GPS falha.
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
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        registeredProviders.clear()
        super.onDestroy()
    }
}
