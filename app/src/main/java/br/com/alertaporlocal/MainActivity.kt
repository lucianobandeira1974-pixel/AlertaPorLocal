package br.com.alertaporlocal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.widget.*
import android.view.ViewGroup
import android.graphics.Color
import android.content.Context
import android.provider.Settings
import android.net.Uri
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var message: EditText
    private lateinit var radius: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (18 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(248, 249, 251))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, 12, 0, 5)
        }
        fun field(hint: String, single: Boolean = true) = EditText(this).apply {
            this.hint = hint
            textSize = 16f
            setSingleLine(single)
            setPadding(14, 12, 14, 12)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Alerta por Local"
            textSize = 27f
            setTextColor(Color.rgb(25, 55, 90))
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "O alarme dispara ao entrar no raio escolhido. Depois de parar, ele só será rearmado quando você sair do raio."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        })
        root.addView(label("Endereço do local"))
        address = field("Ex.: Avenida Paulista, 1000, São Paulo")
        root.addView(address)
        root.addView(label("Mensagem grande do alerta"))
        message = field("Ex.: DESÇA AQUI!", false)
        message.minLines = 2
        root.addView(message, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(label("Raio de disparo (metros)"))
        radius = field("50")
        radius.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        root.addView(radius)
        val save = Button(this).apply { text = "Salvar e ativar monitoramento" }
        root.addView(save)
        val stop = Button(this).apply { text = "Desativar monitoramento" }
        root.addView(stop)
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 16, 0, 0)
        }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        val prefs = getSharedPreferences("alerta", Context.MODE_PRIVATE)
        address.setText(prefs.getString("address", ""))
        message.setText(prefs.getString("message", ""))
        radius.setText(prefs.getInt("radius", 50).toString())
        status.text = if (prefs.getBoolean("active", false)) "Monitoramento configurado. Mantenha a permissão de localização em segundo plano habilitada." else "Monitoramento desligado."

        save.setOnClickListener {
            val addr = address.text.toString().trim()
            val msg = message.text.toString().trim()
            val r = radius.text.toString().toIntOrNull()
            if (addr.isBlank() || msg.isBlank() || r == null || r < 10 || r > 10000) {
                Toast.makeText(this, "Preencha endereço, mensagem e raio entre 10 e 10.000 metros.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!hasLocationPermission()) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
                Toast.makeText(this, "Permita a localização e toque em ativar novamente.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
            }
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val result = geocoder.getFromLocationName(addr, 1)
                if (result.isNullOrEmpty()) {
                    Toast.makeText(this, "Não encontrei esse endereço. Tente incluir cidade e estado.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val place = result[0]
                prefs.edit()
                    .putString("address", addr)
                    .putString("message", msg)
                    .putInt("radius", r)
                    .putFloat("lat", place.latitude.toFloat())
                    .putFloat("lon", place.longitude.toFloat())
                    .putBoolean("active", true)
                    .putBoolean("inside", false)
                    .putBoolean("ringing", false)
                    .apply()
                if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    status.text = "Endereço salvo. Para funcionar com a tela fechada, habilite Localização > Permitir o tempo todo nas configurações do app."
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                val intent = Intent(this, LocationMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                status.text = "Monitoramento ativado para: $addr\nRaio: $r m"
                Toast.makeText(this, "Monitoramento ativado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao localizar endereço. Verifique a internet e tente novamente.", Toast.LENGTH_LONG).show()
            }
        }
        stop.setOnClickListener {
            prefs.edit().putBoolean("active", false).putBoolean("ringing", false).apply()
            stopService(Intent(this, LocationMonitorService::class.java))
            status.text = "Monitoramento desligado."
            Toast.makeText(this, "Monitoramento desativado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
