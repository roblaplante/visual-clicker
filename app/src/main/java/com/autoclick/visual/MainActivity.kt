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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

/**
 * Menu de l'application : « Missions de renseignements » et « Farm », chacun avec ses réglages.
 * Les réglages sont gardés en mémoire à chaque sortie d'écran et au démarrage.
 */
class MainActivity : AppCompatActivity() {

    private var screen = 0 // 0 = menu, 1 = missions de renseignements, 2 = farm
    private var pendingMode = "intel"

    // Réglages communs aux deux modes
    private lateinit var countView: TextView
    private lateinit var thresholdLabel: TextView
    private lateinit var thresholdBar: SeekBar
    private lateinit var intervalEdit: EditText
    private lateinit var cooldownEdit: EditText
    private lateinit var marchEdit: EditText

    // Missions de renseignements
    private lateinit var meatEdit: EditText
    private lateinit var compassEdit: EditText
    private lateinit var memEdit: EditText
    private lateinit var idleEdit: EditText
    private lateinit var harvestEdit: EditText

    // Farm
    private lateinit var grandCheck: CheckBox
    private lateinit var painEdit: EditText
    private lateinit var boisEdit: EditText
    private lateinit var pierreEdit: EditText
    private lateinit var ferEdit: EditText
    private lateinit var farmTotal: TextView

    private val sp by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private val dir by lazy { File(filesDir, "templates").apply { mkdirs() } }
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

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
                    .putExtra("mode", pendingMode)
                ContextCompat.startForegroundService(this, i)
                moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen != 0) {
                    saveSettings()
                    showMenu()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        showMenu()
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    // ---------- Fabrique de vues ----------

    private fun newRoot(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        return root
    }

    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun numberField(key: String, def: Int): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(sp.getInt(key, def).toString())
    }

    private fun backToMenuButton(): View = button("◀ Menu") {
        saveSettings()
        showMenu()
    }

    // ---------- Menu ----------

    private fun showMenu() {
        screen = 0
        val root = newRoot()
        root.addView(TextView(this).apply { text = "Visual Clicker"; textSize = 26f })
        root.addView(label("Choisissez ce que l'application doit faire à votre place."))

        root.addView(button("Missions de renseignements") { showIntel() })
        root.addView(button("Farm") { showFarm() })

        root.addView(label("Autorisations (à faire une seule fois)"))
        root.addView(button("Activer le service d'accessibilité") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Autoriser l'affichage par-dessus les autres apps") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        root.addView(label("Journal des actions"))
        root.addView(button("Voir le journal des actions") { showLog() })
        root.addView(button("Effacer le journal") { File(filesDir, "journal.txt").delete() })
        root.addView(
            label(
                "Une fois démarré, un bouton flottant ▶ apparaît : touchez-le pour lancer / mettre en pause " +
                    "(glissez-le pour le déplacer). Le bouton ☰ en haut revient à ce menu. " +
                    "Pour tout arrêter, touchez la notification."
            )
        )
    }

    // ---------- Réglages communs ----------

    private fun addCommonSettings(root: LinearLayout) {
        thresholdLabel = label("")
        root.addView(thresholdLabel)
        thresholdBar = SeekBar(this).apply {
            max = 40 // 55 % .. 95 %
            progress = sp.getInt("threshold", 80) - 55
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    thresholdLabel.text = "Seuil de confiance : ${55 + p} %"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(thresholdBar)
        thresholdLabel.text = "Seuil de confiance : ${55 + thresholdBar.progress} %"

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

        root.addView(label("Nombre total de marches (5 ou 6) : les chasses à tête de loup et les récoltes en ont besoin"))
        marchEdit = numberField("marchTotal", 5)
        root.addView(marchEdit)
    }

    // ---------- Missions de renseignements ----------

    private fun showIntel() {
        screen = 1
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(TextView(this).apply { text = "Missions de renseignements"; textSize = 22f })

        root.addView(button("(Facultatif) Ajouter une image à détecter en plus") { pickImage.launch("image/*") })
        countView = label("")
        root.addView(countView)
        root.addView(button("Supprimer mes images ajoutées") {
            dir.listFiles()?.forEach { it.delete() }
            refreshCount()
        })
        refreshCount()

        addCommonSettings(root)

        root.addView(label("Viande minimum avant d'attaquer un pin (0 = ne pas vérifier)"))
        meatEdit = numberField("minMeat", 20)
        root.addView(meatEdit)

        root.addView(label("Boussole : attente après un départ de troupes, une victoire ou un secours (secondes)"))
        compassEdit = numberField("compassDelay", 3)
        root.addView(compassEdit)

        root.addView(label("Un pin déjà lancé n'est pas recliqué pendant (minutes)"))
        memEdit = numberField("pinMemory", 20)
        root.addView(memEdit)

        root.addView(label("Inactif sur la carte du monde pendant (secondes) : rouvrir la carte des pins (0 = non)"))
        idleEdit = numberField("idleSec", 30)
        root.addView(idleEdit)

        root.addView(label("Récupérer Tout quand ce nombre de pins ont une coche verte (0 = jamais, 1 = dès le premier)"))
        harvestEdit = numberField("harvestMin", 5)
        root.addView(harvestEdit)

        root.addView(button("Démarrer") { start("intel") })
    }

    // ---------- Farm ----------

    private fun showFarm() {
        screen = 2
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(TextView(this).apply { text = "Farm"; textSize = 22f })
        root.addView(
            label(
                "Ouvrez le jeu sur la carte du monde. L'appli touche la loupe, choisit les récoltes " +
                    "dans l'ordre : le grand bâtiment d'abord, puis les petites, et envoie les troupes."
            )
        )

        grandCheck = CheckBox(this).apply {
            text = "Grande récolte (bâtiment d'alliance, s'il est disponible)"
            isChecked = sp.getBoolean("farmGrand", true)
        }
        root.addView(grandCheck)

        farmTotal = label("")
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshFarmTotal()
        }
        root.addView(label("Petites récoltes : nombre de marches par ressource"))
        root.addView(label("Pain"))
        painEdit = numberField("farmPain", 0).also { it.addTextChangedListener(watcher) }
        root.addView(painEdit)
        root.addView(label("Bois"))
        boisEdit = numberField("farmBois", 1).also { it.addTextChangedListener(watcher) }
        root.addView(boisEdit)
        root.addView(label("Pierre"))
        pierreEdit = numberField("farmPierre", 2).also { it.addTextChangedListener(watcher) }
        root.addView(pierreEdit)
        root.addView(label("Fer"))
        ferEdit = numberField("farmFer", 2).also { it.addTextChangedListener(watcher) }
        root.addView(ferEdit)
        grandCheck.setOnCheckedChangeListener { _, _ -> refreshFarmTotal() }
        root.addView(farmTotal)
        refreshFarmTotal()

        addCommonSettings(root)

        root.addView(button("Démarrer") { start("farm") })
    }

    private fun farmCount(e: EditText) = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, 6)

    private fun refreshFarmTotal() {
        if (screen != 2 || !::farmTotal.isInitialized) return
        val total = (if (grandCheck.isChecked) 1 else 0) +
            farmCount(painEdit) + farmCount(boisEdit) + farmCount(pierreEdit) + farmCount(ferEdit)
        farmTotal.text = if (total > 6) {
            "Total : $total. Le grand couvre une récolte de son type (Scierie = bois, Moulin = pain, " +
                "Carrière = pierre, Mine = fer) : une petite de ce type en moins. 6 marches au maximum."
        } else {
            "Total : $total récolte(s) sur 6 marches (le grand couvre une récolte de son type)."
        }
    }

    // ---------- Divers ----------

    private fun refreshCount() {
        val n = dir.listFiles()?.size ?: 0
        countView.text = "Boutons du jeu et pins déjà intégrés. $n image(s) supplémentaire(s) ajoutée(s)."
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
        if (screen == 1) refreshCount()
    }

    /** Les réglages de l'écran affiché sont gardés en mémoire à chaque sortie et au démarrage. */
    private fun saveSettings() {
        if (screen == 0) return
        val e = sp.edit()
        e.putInt("threshold", 55 + thresholdBar.progress)
        e.putLong("interval", intervalEdit.text.toString().toLongOrNull() ?: 500L)
        e.putLong("cooldown", cooldownEdit.text.toString().toLongOrNull() ?: 1000L)
        e.putInt("marchTotal", (marchEdit.text.toString().toIntOrNull() ?: 5).coerceIn(1, 6))
        if (screen == 1) {
            e.putInt("minMeat", meatEdit.text.toString().toIntOrNull() ?: 20)
            e.putInt("compassDelay", compassEdit.text.toString().toIntOrNull() ?: 3)
            e.putInt("pinMemory", memEdit.text.toString().toIntOrNull() ?: 20)
            e.putInt("idleSec", idleEdit.text.toString().toIntOrNull() ?: 30)
            e.putInt("harvestMin", harvestEdit.text.toString().toIntOrNull() ?: 5)
        } else {
            e.putBoolean("farmGrand", grandCheck.isChecked)
            e.putInt("farmPain", farmCount(painEdit))
            e.putInt("farmBois", farmCount(boisEdit))
            e.putInt("farmPierre", farmCount(pierreEdit))
            e.putInt("farmFer", farmCount(ferEdit))
        }
        e.apply()
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

    private fun start(mode: String) {
        if (ClickAccessibilityService.instance == null) {
            Toast.makeText(this, "Activez d'abord le service d'accessibilité (menu)", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorisez d'abord l'affichage par-dessus (menu)", Toast.LENGTH_LONG).show()
            return
        }
        saveSettings()
        pendingMode = mode
        val mpm = getSystemService(MediaProjectionManager::class.java)
        capture.launch(mpm.createScreenCaptureIntent())
    }
}
