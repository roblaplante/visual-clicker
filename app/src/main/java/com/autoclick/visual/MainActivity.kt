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
import android.widget.ImageView
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

    private var screen = 0 // 0 = menu, 1 = missions (réglages), 2 = farm (réglages), 3 = farm (5 ou 6 ?), 4 = hunt (5 ou 6 ?), 5 = hunt (réglages), 6 = missions (5 ou 6 ?), 7 = Daily, 8 = Marchand nomade, 9 = Épreuves Mystiques
    private var farmN = 5 // nombre de marches choisi pour le farm : chaque cas a ses propres réglages
    private var huntN = 5 // idem pour le hunt
    private lateinit var huntCountEdit: EditText
    private lateinit var huntWaitEdit: EditText
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
    private lateinit var bonusCheck: CheckBox
    private lateinit var mysteryCheck: CheckBox
    private lateinit var bonusFromEdit: EditText
    private lateinit var bonusToEdit: EditText
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
                if (screen == 2) {
                    saveSettings()
                    showFarmChoice()
                } else if (screen == 5) {
                    saveSettings()
                    showHuntChoice()
                } else if (screen == 1) {
                    saveSettings()
                    showIntelChoice()
                } else if (screen == 10) {
                    saveSettings()
                    showEpreuves()
                } else if (screen != 0) {
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

        root.addView(button("Missions de renseignements") { showIntelChoice() })
        root.addView(button("Farm") { showFarmChoice() })
        root.addView(button("Hunt (chasses de Terreur)") { showHuntChoice() })
        root.addView(button("Daily (tâches quotidiennes)") { showDaily() })
        root.addView(button("Marchand nomade") { showMarchand() })
        root.addView(button("Épreuves Mystiques") { showEpreuves() })

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
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        root.addView(TextView(this).apply {
            text = "v$version"
            textSize = 11f
            alpha = 0.45f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, pad * 2, 0, 0)
        })
    }

    // ---------- Réglages communs ----------

    private fun addCommonSettings(root: LinearLayout, withMarches: Boolean) {
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

        if (withMarches) {
            root.addView(label("Nombre total de marches (5 ou 6) : les chasses à tête de loup en ont besoin"))
            marchEdit = numberField("marchTotal", 5)
            root.addView(marchEdit)
        }
    }

    // ---------- Missions de renseignements ----------

    /** Première question des missions : 5 ou 6 marches (les chasses à tête de loup en ont besoin). */
    private fun showIntelChoice() {
        screen = 6
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(TextView(this).apply { text = "Missions de renseignements"; textSize = 22f })
        root.addView(label("Combien de marches avez-vous ?"))
        root.addView(button("5 marches") { sp.edit().putInt("marchTotal", 5).apply(); showIntel() })
        root.addView(button("6 marches") { sp.edit().putInt("marchTotal", 6).apply(); showIntel() })
        root.addView(
            label(
                "Avec 5 marches, les chasses à tête de loup sont ignorées quand elles sont toutes sorties ; " +
                    "tentes et épées croisées restent faites. Dernier choix : ${sp.getInt("marchTotal", 5)} marches."
            )
        )
    }

    private fun showIntel() {
        screen = 1
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(button("Changer le nombre de marches") {
            saveSettings()
            showIntelChoice()
        })
        root.addView(TextView(this).apply { text = "Missions de renseignements : ${sp.getInt("marchTotal", 5)} marches"; textSize = 22f })

        root.addView(button("(Facultatif) Ajouter une image à détecter en plus") { pickImage.launch("image/*") })
        countView = label("")
        root.addView(countView)
        root.addView(button("Supprimer mes images ajoutées") {
            dir.listFiles()?.forEach { it.delete() }
            refreshCount()
        })
        refreshCount()

        addCommonSettings(root, false)

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

    /** Première question du Farm : 5 ou 6 marches. Chaque cas garde ses propres réglages. */
    private fun showFarmChoice() {
        screen = 3
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(TextView(this).apply { text = "Farm"; textSize = 22f })
        root.addView(label("Combien de marches avez-vous ?"))
        root.addView(button("5 marches") { farmN = 5; showFarm() })
        root.addView(button("6 marches") { farmN = 6; showFarm() })
        root.addView(
            label(
                "Les réglages de récolte sont enregistrés séparément pour chaque cas. " +
                    "Dernier choix : ${sp.getInt("farmMarches", 5)} marches."
            )
        )
    }

    private fun farmKey(name: String) = "farm$farmN$name"

    /** Réglage du cas courant ; pour 5 marches, reprend l'ancien réglage commun s'il existe. */
    private fun farmInt(name: String, def: Int): Int =
        sp.getInt(farmKey(name), if (farmN == 5) sp.getInt("farm$name", def) else def)

    private fun farmField(name: String, def: Int, watcher: TextWatcher): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(farmInt(name, def).toString())
        addTextChangedListener(watcher)
    }

    private fun showFarm() {
        screen = 2
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(button("Changer le nombre de marches") {
            saveSettings()
            showFarmChoice()
        })
        root.addView(TextView(this).apply { text = "Farm : $farmN marches"; textSize = 22f })
        root.addView(
            label(
                "Ouvrez le jeu sur la carte du monde. L'appli touche la loupe, choisit les récoltes " +
                    "dans l'ordre : le grand bâtiment d'abord, puis les petites, et envoie les troupes."
            )
        )

        grandCheck = CheckBox(this).apply {
            text = "Grande récolte (bâtiment d'alliance, s'il est disponible)"
            isChecked = sp.getBoolean(farmKey("Grand"), if (farmN == 5) sp.getBoolean("farmGrand", true) else true)
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
        painEdit = farmField("Pain", 1, watcher)
        root.addView(painEdit)
        root.addView(label("Bois"))
        boisEdit = farmField("Bois", 1, watcher)
        root.addView(boisEdit)
        root.addView(label("Pierre"))
        pierreEdit = farmField("Pierre", 1, watcher)
        root.addView(pierreEdit)
        root.addView(label("Fer"))
        ferEdit = farmField("Fer", if (farmN == 6) 2 else 1, watcher)
        root.addView(ferEdit)
        grandCheck.setOnCheckedChangeListener { _, _ -> refreshFarmTotal() }
        root.addView(farmTotal)
        refreshFarmTotal()

        addCommonSettings(root, false)

        root.addView(button("Démarrer") { start("farm") })
    }

    private fun farmCount(e: EditText) = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, 6)

    private fun refreshFarmTotal() {
        if (screen != 2 || !::farmTotal.isInitialized) return
        val total = (if (grandCheck.isChecked) 1 else 0) +
            farmCount(painEdit) + farmCount(boisEdit) + farmCount(pierreEdit) + farmCount(ferEdit)
        farmTotal.text = if (total > farmN) {
            "Total : $total. Le grand couvre une récolte de son type (Scierie = bois, Moulin = pain, " +
                "Carrière = pierre, Mine = fer) : une petite de ce type en moins. $farmN marches au maximum."
        } else {
            "Total : $total récolte(s) sur $farmN marches (le grand couvre une récolte de son type)."
        }
    }

    // ---------- Daily (onglet « Quot. » du menu de gauche) ----------

    private fun showDaily() {
        screen = 7
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(TextView(this).apply { text = "Daily"; textSize = 22f })
        root.addView(
            label(
                "Principe : l'appli ouvre le menu de gauche (petite languette à triangle blanc) et touche l'onglet " +
                    "« Quot. ». Elle lit la liste des tâches telle qu'elle est à ce moment-là, prend la première " +
                    "tâche à faire (du haut vers le bas) et lance son scénario. À la fin, elle revient à la liste, " +
                    "regarde quelle est la suivante, et continue jusqu'à épuisement."
            )
        )
        root.addView(
            label(
                "Les tâches sans scénario sont ignorées ; certaines auront plus tard un sous-menu à activer à la demande. " +
                    "Les scénarios arrivent tâche après tâche (en préparation). Pour l'instant : « Récomp. En Ligne »."
            )
        )
        root.addView(label("Bonus de collecte (premier préambule)"))
        bonusCheck = CheckBox(this).apply {
            text = "Aller voir « Revenu de la Ville » même si l'icône du chariot est présente, seulement dans cette plage horaire (heure du téléphone). S'il reste moins de 2 h, le boost 24 h est racheté."
            isChecked = sp.getBoolean("bonusForce", true)
        }
        root.addView(bonusCheck)
        root.addView(label("De (heure, 0 à 23)"))
        bonusFromEdit = numberField("bonusFrom", 6)
        root.addView(bonusFromEdit)
        root.addView(label("À (heure, 0 à 23)"))
        bonusToEdit = numberField("bonusTo", 9)
        root.addView(bonusToEdit)
        root.addView(label("Ouvrez le jeu sur la ville (ou la carte), menu de gauche fermé, puis démarrez."))
        root.addView(button("Démarrer") { start("daily") })
    }

    // ---------- Marchand nomade (à la demande) ----------

    private fun showMarchand() {
        screen = 8
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(TextView(this).apply { text = "Marchand nomade"; textSize = 22f })
        root.addView(
            label(
                "Principe : l'appli ouvre le magasin (icône « Magasin » de la barre du bas), touche l'onglet " +
                    "« Marchand Nomade » et lit les 6 cases. Elle achète tout ce qui est payé en ressources, " +
                    "et les objets à 65 cristaux bleus ou moins (ou exactement 600). Elle n'achète jamais le reste."
            )
        )
        root.addView(
            label(
                "Quand les 6 cases sont bloquées par des objets trop chers, elle touche « Actualisation Gratuite » " +
                    "et recommence, jusqu'à ce que le bouton d'actualisation devienne orange (payant). " +
                    "Elle revient alors à la ville avec la flèche bleue et s'arrête. Cela peut prendre du temps : " +
                    "le bouton ⏸ arrête tout à tout moment."
            )
        )
        mysteryCheck = CheckBox(this).apply {
            text = "Magasin mystère"
            isChecked = sp.getBoolean("mysteryShop", false)
        }
        root.addView(mysteryCheck)
        root.addView(
            label(
                "Si cochée : une fois le Marchand nomade terminé (ici comme dans le Daily), l'appli regarde si l'onglet " +
                    "« Mystère » du bas a un point rouge. Si oui, elle l'ouvre, touche chaque case verte « Gratuit », puis " +
                    "touche « Actualisation Gratuite » (5 fois au plus) tant que ce bouton est vert. Elle ne touche jamais " +
                    "le bouton orange payant ni une case payante, puis sort par la flèche bleue."
            )
        )
        root.addView(label("Ouvrez le jeu sur la ville (ou directement dans le magasin), puis démarrez."))
        root.addView(button("Démarrer") { start("marchand") })
    }

    // ---------- Épreuves Mystiques (à la demande) ----------

    private val epGroups = listOf(
        Triple("Flèche Éclatante et Colisée, équipe 1 (en haut)", "epr_team0_", intArrayOf(60, 40, 0)),
        Triple("Flèche Éclatante et Colisée, équipe 2", "epr_team1_", intArrayOf(45, 15, 40)),
        Triple("Flèche Éclatante et Colisée, équipe 3", "epr_team2_", intArrayOf(50, 20, 30)),
        Triple("Forêt de la Vie (mercredi, jeudi)", "epr_foret_", intArrayOf(50, 10, 40)),
        Triple("Grotte de Cristal (mercredi, jeudi)", "epr_grotte_", intArrayOf(50, 15, 35)),
        Triple("Nexus du Savoir (vendredi, samedi)", "epr_nexus_", intArrayOf(55, 5, 40)),
        Triple("Forteresse en Fusion (vendredi, samedi)", "epr_fort_", intArrayOf(50, 10, 40))
    )
    private val epHeroKeys = arrayOf(
        arrayOf("spartiate", "bouffonne", "casquette"),
        arrayOf("archere", "blanc", "rousse"),
        arrayOf("pirate", "blonde", "guerriere")
    )
    private val epHeroClass = arrayOf(
        arrayOf("Infanterie", "Cavalerie", "Archer"),
        arrayOf("Archer", "Infanterie", "Cavalerie"),
        arrayOf("Archer", "Cavalerie", "Infanterie")
    )
    private val epHeroChecks = HashMap<String, CheckBox>()
    private val epEdits = ArrayList<EditText>()
    private lateinit var epResetEdit: EditText

    private fun showEpreuves() {
        screen = 9
        epEdits.clear()
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(TextView(this).apply { text = "Épreuves Mystiques"; textSize = 22f })
        root.addView(
            label(
                "L'appli ouvre le menu de gauche > Quot. > tâche « Épreuve Mystique », puis, île par île (la plus haute " +
                    "d'abord) : « ! » (formation à créer), Piller, Défi, équipes, Déployer, combats (Passer / Réessayer) " +
                    "jusqu'aux 5 tentatives du jour. Flèche Éclatante (dimanche) et Colisée (lundi, mardi) : 3 équipes " +
                    "de héros fixes. Les 4 autres épreuves : pas de héros, seulement le taux de troupes puis Déployer."
            )
        )
        root.addView(label("Heure de remise à zéro du jeu (heure de Paris). Le jour de la semaine, qui sert de contrôle, change à cette heure :"))
        epResetEdit = numberField("epResetHour", 1)
        root.addView(epResetEdit)
        root.addView(label("Taux de troupes : Infanterie / Cavalerie / Archer (le total doit faire 100) :"))
        for ((title, prefix, def) in epGroups) {
            root.addView(label(title))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 3) {
                val e = numberField(prefix + i, def[i])
                row.addView(e, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                epEdits.add(e)
            }
            root.addView(row)
        }
        root.addView(label("Flèche Éclatante (dimanche) et Colisée (lundi, mardi) : coche = on utilise l'équipe type (héros cochés dans le sous-menu), décoché = on prend les premiers héros de la liste (les plus forts du bon type) :"))
        val teamRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        epTeamTypeCheck = CheckBox(this).apply { isChecked = sp.getBoolean("epTeamType", true) }
        teamRow.addView(epTeamTypeCheck)
        teamRow.addView(button("Équipe type") {
            saveEpreuves()
            showEpTeam()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(teamRow)
        root.addView(label("Ouvrez le jeu sur la ville, puis démarrez."))
        root.addView(button("Démarrer") { start("epreuves") })
    }

    private lateinit var epTeamTypeCheck: CheckBox

    /** Sous-menu « Équipe type » : les 3 équipes de héros ; case cochée = exactement ce héros, décochée = le premier de la liste. */
    private fun showEpTeam() {
        screen = 10
        epHeroChecks.clear()
        val root = newRoot()
        root.addView(button("◀ Épreuves Mystiques") {
            saveEpTeam()
            showEpreuves()
        })
        root.addView(TextView(this).apply { text = "Équipe type"; textSize = 22f })
        root.addView(label("Case cochée = exactement ce héros (le bot s'arrête s'il ne le trouve pas). Case décochée = le premier héros de la liste (le plus fort du bon type). Ces choix ne servent que si la coche « Équipe type » du menu précédent est cochée :"))
        for (t in 0 until 3) {
            root.addView(label("Équipe ${t + 1}"))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (h in 0 until 3) {
                val key = epHeroKeys[t][h]
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
                val img = ImageView(this)
                try {
                    assets.open("epreuves/hero_$key.png").use { img.setImageBitmap(BitmapFactory.decodeStream(it)) }
                } catch (_: Exception) {
                }
                col.addView(img, LinearLayout.LayoutParams(pad * 6, pad * 4))
                val cb = CheckBox(this).apply {
                    text = epHeroClass[t][h]
                    isChecked = sp.getBoolean("epHeroExact_$key", true)
                }
                epHeroChecks[key] = cb
                col.addView(cb)
                row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(row)
        }
    }

    private fun saveEpTeam() {
        val e = sp.edit()
        for ((key, cb) in epHeroChecks) e.putBoolean("epHeroExact_$key", cb.isChecked)
        e.apply()
    }

    private fun saveEpreuves() {
        val e = sp.edit()
        e.putInt("epResetHour", (epResetEdit.text.toString().toIntOrNull() ?: 1).coerceIn(0, 23))
        e.putBoolean("epTeamType", epTeamTypeCheck.isChecked)
        for ((g, grp) in epGroups.withIndex()) {
            val v = IntArray(3) { (epEdits[g * 3 + it].text.toString().toIntOrNull() ?: -1) }
            if (v.all { it in 0..100 } && v.sum() == 100) {
                for (i in 0 until 3) e.putInt(grp.second + i, v[i])
            } else {
                Toast.makeText(this, "${grp.first} : le total doit faire 100, valeurs ignorées", Toast.LENGTH_LONG).show()
            }
        }
        e.apply()
    }

    // ---------- Hunt (chasses de Terreur) ----------

    private fun showHuntChoice() {
        screen = 4
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(TextView(this).apply { text = "Hunt"; textSize = 22f })
        root.addView(label("Combien de marches avez-vous ?"))
        root.addView(button("5 marches") { huntN = 5; showHunt() })
        root.addView(button("6 marches") { huntN = 6; showHunt() })
        root.addView(
            label(
                "L'appli compte les marches déjà sorties (panneau à gauche de la carte) et ne lance une chasse " +
                    "que s'il en reste une libre. Dernier choix : ${sp.getInt("huntMarches", 5)} marches."
            )
        )
    }

    private fun showHunt() {
        screen = 5
        val root = newRoot()
        root.addView(backToMenuButton())
        root.addView(button("Changer le nombre de marches") {
            saveSettings()
            showHuntChoice()
        })
        root.addView(TextView(this).apply { text = "Hunt : $huntN marches"; textSize = 22f })
        root.addView(
            label(
                "Ouvrez le jeu sur la carte du monde. Pour chaque chasse : loupe, Terreur, Rechercher, " +
                    "Ralliement, Appeler à un ralliement, Déployer."
            )
        )
        root.addView(label("Nombre de chasses à lancer"))
        huntCountEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(sp.getInt("hunt${huntN}Count", 3).toString())
        }
        root.addView(huntCountEdit)
        root.addView(
            label(
                "Quand elles sont terminées, un petit panneau flottant propose d'en relancer de 1 à 10 d'un seul toucher."
            )
        )
        root.addView(label("Si aucune marche n'est libre, attendre avant de revérifier (secondes)"))
        huntWaitEdit = numberField("huntWait", 150)
        root.addView(huntWaitEdit)
        root.addView(label("150 s = ralliement qui se remplit (1 min), arrivée de tous, puis marche aller et retour."))

        addCommonSettings(root, false)

        root.addView(button("Démarrer") { start("hunt") })
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
        if (screen == 7) {
            sp.edit()
                .putBoolean("bonusForce", bonusCheck.isChecked)
                .putInt("bonusFrom", (bonusFromEdit.text.toString().toIntOrNull() ?: 6).coerceIn(0, 23))
                .putInt("bonusTo", (bonusToEdit.text.toString().toIntOrNull() ?: 9).coerceIn(0, 23))
                .apply()
            return
        }
        if (screen == 9) {
            saveEpreuves()
            return
        }
        if (screen == 10) {
            saveEpTeam()
            return
        }
        if (screen == 8) {
            sp.edit().putBoolean("mysteryShop", mysteryCheck.isChecked).apply()
            return
        }
        if (screen == 0 || screen == 3 || screen == 4 || screen == 6 || screen == 7 || screen == 8) return
        val e = sp.edit()
        e.putInt("threshold", 55 + thresholdBar.progress)
        e.putLong("interval", intervalEdit.text.toString().toLongOrNull() ?: 500L)
        e.putLong("cooldown", cooldownEdit.text.toString().toLongOrNull() ?: 1000L)
        if (screen == 1) {
            e.putInt("minMeat", meatEdit.text.toString().toIntOrNull() ?: 20)
            e.putInt("compassDelay", compassEdit.text.toString().toIntOrNull() ?: 3)
            e.putInt("pinMemory", memEdit.text.toString().toIntOrNull() ?: 20)
            e.putInt("idleSec", idleEdit.text.toString().toIntOrNull() ?: 30)
            e.putInt("harvestMin", harvestEdit.text.toString().toIntOrNull() ?: 5)
        } else if (screen == 5) {
            e.putInt("huntMarches", huntN)
            e.putInt("hunt${huntN}Count", (huntCountEdit.text.toString().toIntOrNull() ?: 3).coerceIn(1, 99))
            e.putInt("huntWait", (huntWaitEdit.text.toString().toIntOrNull() ?: 150).coerceIn(10, 1800))
        } else {
            e.putInt("farmMarches", farmN)
            e.putBoolean(farmKey("Grand"), grandCheck.isChecked)
            e.putInt(farmKey("Pain"), farmCount(painEdit))
            e.putInt(farmKey("Bois"), farmCount(boisEdit))
            e.putInt(farmKey("Pierre"), farmCount(pierreEdit))
            e.putInt(farmKey("Fer"), farmCount(ferEdit))
        }
        e.apply()
    }

    private fun showLog() {
        val f = File(filesDir, "journal.txt")
        val text = if (f.exists()) f.readLines().takeLast(200).joinToString("\n") else "(journal vide)"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Journal (200 dernières lignes)")
            .setMessage(text)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Exporter") { _, _ -> exportLog() }
            .show()
    }

    /** Partage le journal complet (fichier texte) : messagerie, mail, Drive… */
    private fun exportLog() {
        try {
            val f = File(filesDir, "journal.txt")
            if (!f.exists()) { Toast.makeText(this, "Journal vide", Toast.LENGTH_SHORT).show(); return }
            val dir = File(cacheDir, "export").also { it.mkdirs() }
            val out = File(dir, "journal_visual_clicker.txt")
            f.copyTo(out, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Journal Visual Clicker")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Exporter le journal"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export impossible : ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        if (BotService.alive) {
            // La capture est déjà autorisée : on change de mode sans nouvelle demande.
            startService(Intent(this, BotService::class.java).putExtra("mode", mode))
            moveTaskToBack(true)
            return
        }
        pendingMode = mode
        val mpm = getSystemService(MediaProjectionManager::class.java)
        capture.launch(mpm.createScreenCaptureIntent())
    }
}
