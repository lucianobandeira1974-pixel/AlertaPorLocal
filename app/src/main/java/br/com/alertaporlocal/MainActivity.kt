package br.com.alertaporlocal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Filter
import android.widget.Filterable
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var address: AutoCompleteTextView
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
            textSize = 17f
            setTextColor(Color.DKGRAY)
            setPadding(0, 16, 0, 7)
        }

        fun field(hintText: String, single: Boolean = true) = EditText(this).apply {
            hint = hintText
            textSize = 18f
            setSingleLine(single)
            setPadding(16, 14, 16, 14)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "Alerta por Local"
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(25, 55, 90))
            setPadding(0, 0, 0, 10)
        })
        root.addView(TextView(this).apply {
            text = "O alarme dispara ao entrar no raio escolhido. Depois de parar, ele será rearmado quando você sair do raio e poderá tocar novamente ao voltar."
            textSize = 16f
            setTextColor(Color.DKGRAY)
        })

        root.addView(label("Endereço do local"))
        address = AutoCompleteTextView(this).apply {
            hint = "Digite rua, número, cidade..."
            textSize = 18f
            threshold = 3
            setSingleLine(true)
            setPadding(16, 14, 16, 14)
            setBackgroundColor(Color.WHITE)
            setAdapter(AddressSuggestionsAdapter())
            setOnItemClickListener { _, _, position, _ ->
                val selected = adapter.getItem(position)?.toString().orEmpty()
                setText(selected)
                setSelection(text.length)
            }
        }
        root.addView(address, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "Dica: digite pelo menos 3 letras e escolha uma sugestão da lista. Inclua cidade e estado para melhorar os resultados."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })

        root.addView(label("Mensagem que aparecerá no alerta"))
        message = field("Ex.: DESÇA AQUI!", false)
        message.minLines = 2
        root.addView(message, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(label("Raio de disparo (metros)"))
        radius = field("50")
        radius.inputType = InputType.TYPE_CLASS_NUMBER
        root.addView(radius)

        val save = Button(this).apply {
            text = "SALVAR E ATIVAR MONITORAMENTO"
            textSize = 19f
            isAllCaps = false
            setPadding(8, 18, 8, 18)
        }
        root.addView(save, LinearLayout.LayoutParams(-1, (76 * resources.displayMetrics.density).toInt()))

        val stop = Button(this).apply {
            text = "DESATIVAR MONITORAMENTO"
            textSize = 18f
            isAllCaps = false
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, (64 * resources.displayMetrics.density).toInt()))

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, 18, 0, 0)
        }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        val prefs = getSharedPreferences("alerta", Context.MODE_PRIVATE)
        address.setText(prefs.getString("address", ""), false)
        message.setText(prefs.getString("message", ""))
        radius.setText(prefs.getInt("radius", 50).toString())
        status.text = if (prefs.getBoolean("active", false)) {
            "Monitoramento configurado. Para funcionar com a tela fechada, permita localização o tempo todo e desative a otimização de bateria se o celular interromper o serviço."
        } else {
            "Monitoramento desligado."
        }

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
                    Toast.makeText(this, "Não encontrei esse endereço. Escolha uma sugestão ou inclua cidade e estado.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val place = result[0]
                val savedAddress = formatAddress(place).ifBlank { addr }
                address.setText(savedAddress, false)
                prefs.edit()
                    .putString("address", savedAddress)
                    .putString("message", msg)
                    .putInt("radius", r)
                    .putFloat("lat", place.latitude.toFloat())
                    .putFloat("lon", place.longitude.toFloat())
                    .putBoolean("active", true)
                    .putBoolean("inside", false)
                    .putBoolean("ringing", false)
                    .apply()

                val intent = Intent(this, LocationMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                status.text = "Monitoramento ativado para:\n$savedAddress\nRaio: $r metros."
                Toast.makeText(this, "Monitoramento ativado", Toast.LENGTH_SHORT).show()

                if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    status.append("\n\nIMPORTANTE: habilite Localização > Permitir o tempo todo nas configurações do aplicativo para monitorar com a tela fechada.")
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
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

    private fun formatAddress(place: Address): String {
        val lines = (0 until place.maxAddressLineIndex + 1).mapNotNull { place.getAddressLine(it) }
        return lines.joinToString(", ").ifBlank { place.featureName.orEmpty() }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private inner class AddressSuggestionsAdapter : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_dropdown_item_1line), Filterable {
        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val result = FilterResults()
                val query = constraint?.toString()?.trim().orEmpty()
                if (query.length < 3 || !Geocoder.isPresent()) {
                    result.values = emptyList<String>()
                    result.count = 0
                    return result
                }
                val suggestions = try {
                    val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val found = geocoder.getFromLocationName(query, 5)
                    found.orEmpty().map { formatAddress(it) }.filter { it.isNotBlank() }.distinct()
                } catch (_: Exception) {
                    emptyList()
                }
                result.values = suggestions
                result.count = suggestions.size
                return result
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                clear()
                @Suppress("UNCHECKED_CAST")
                val values = results?.values as? List<String> ?: emptyList()
                addAll(values)
                notifyDataSetChanged()
            }
        }
    }
}
