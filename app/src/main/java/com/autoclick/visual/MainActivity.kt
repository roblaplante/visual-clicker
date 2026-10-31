package com.autoclick.visual

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var countView: TextView
    private lateinit var thresholdLabel: TextView
    private lateinit var thresholdBar: SeekBar
    private lateinit var intervalEdit: EditText
    private lateinit var cooldownEdit: EditText
    private lateinit var meatEdit: EditText
    private lateinit var compassEdit: EditText
    private lateinit var memEdit: EditText

    private val sp by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private val dir by lazy { File(filesDir, "templates").apply { mkdirs() } }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { saveTemplate(it) }
        }

    private val capture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, BotService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", data)
                ContextCompat.startForegroundService(this, i)
                moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
        }

        root.addView(TextView(this).apply { text = "Visual Clicker"; textSize = 24f })
        root.addView(label("Clique à votre place sur les images que vous lui indiquez."))

        root.addView(button("1. Activer le service d'accessibilité") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("2. Autoriser l'affichage par-dessus les autres apps") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        root.addView(button("3. (Facultatif) Ajouter une image à détecter en plus") { pickImage.launch("image/*") })
        countView = label("")
        root.addView(countView)
        root.addView(button("Supprimer mes images ajoutées") {
            dir.listFiles()?.forEach { it.delete() }
            refresh()
        })

        thresholdLabel = label("")
        root.addView(thresholdLabel)
        thresholdBar = SeekBar(this).apply {
            max = 40 // 55 % .. 95 %
            progress = sp.getInt("threshold", 80) - 55
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = refresh()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(thresholdBar)

        root.addView(label("Fréquence d'analyse (ms)"))
        intervalEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(sp.getLong("interval", 500L).toString())
        }
        root.addView(intervalEdit)

        root.addView(label("Délai minimum entre deux clics (ms)"))
        cooldownEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(sp.getLong("cooldown", 1000L).toString())
        }
        root.addView(cooldownEdit)

        root.addView(label("Viande minimum avant d'attaquer un pin (0 = ne pas vérifier)"))
        meatEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(sp.getInt("minMeat", 20).toString())
        }
        root.addView(meatEdit)

        root.addView(label("Boussole : attente après une victoire ou un secours (secondes)"))
        compassEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(sp.getInt("compassDelay", 3).toString())
        }
        root.addView(compassEdit)

        root.addView(label("Un pin déjà lancé n'est pas recliqué pendant (minutes)"))
        memEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(sp.getInt("pinMemory", 20).toString())
        }
        root.addView(memEdit)

        root.addView(button("4. Démarrer") { start() })
        root.addView(button("Voir le journal des actions") { showLog() })
        root.addView(button("Effacer le journal") { File(filesDir, "journal.txt").delete() })
        root.addView(
            label(
                "Un bouton flottant ▶ apparaît : touchez-le pour lancer / mettre en pause " +
                    "(glissez-le pour le déplacer). Pour tout arrêter, touchez la notification."
            )
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refresh()
    }

    private fun refresh() {
        val n = dir.listFiles()?.size ?: 0
        countView.text = "Boutons du jeu et pins déjà intégrés. $n image(s) supplémentaire(s) ajoutée(s)."
        thresholdLabel.text = "Seuil de confiance : ${55 + thresholdBar.progress} %"
    }

    private fun saveTemplate(uri: Uri) {
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        if (bmp == null) {
            Toast.makeText(this, "Image illisible", Toast.LENGTH_SHORT).show()
            return
        }
        FileOutputStream(File(dir, "${System.currentTimeMillis()}.png")).use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        refresh()
    }

    /** Les réglages sont gardés en mémoire à chaque sortie de l'écran et au démarrage. */
    private fun saveSettings() {
        if (!::memEdit.isInitialized) return
        sp.edit()
            .putInt("threshold", 55 + thresholdBar.progress)
            .putLong("interval", intervalEdit.text.toString().toLongOrNull() ?: 500L)
            .putLong("cooldown", cooldownEdit.text.toString().toLongOrNull() ?: 1000L)
            .putInt("minMeat", meatEdit.text.toString().toIntOrNull() ?: 20)
            .putInt("compassDelay", compassEdit.text.toString().toIntOrNull() ?: 3)
            .putInt("pinMemory", memEdit.text.toString().toIntOrNull() ?: 20)
            .apply()
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    private fun showLog() {
        val f = File(filesDir, "journal.txt")
        val text = if (f.exists()) f.readLines().takeLast(60).joinToString("\n") else "(journal vide)"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Journal (60 dernières lignes)")
            .setMessage(text)
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun start() {
        if (ClickAccessibilityService.instance == null) {
            Toast.makeText(this, "Activez d'abord le service d'accessibilité (étape 1)", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorisez d'abord l'affichage par-dessus (étape 2)", Toast.LENGTH_LONG).show()
            return
        }
        saveSettings()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        capture.launch(mpm.createScreenCaptureIntent())
    }
}
