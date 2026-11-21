package com.autoclick.visual

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Capture l'écran (MediaProjection), cherche les images modèles (template matching OpenCV)
 * et demande au service d'accessibilité de cliquer au centre de la meilleure correspondance.
 */
class BotService : Service() {

    companion object {
        const val ACTION_STOP = "com.autoclick.visual.STOP"
        private const val HARVEST = "08_recuperer_tout.png"
        private const val SCALE = 0.5 // on travaille en demi-résolution pour la vitesse

        /** Vrai tant que la capture d'écran est autorisée et active : un nouveau mode peut s'en servir sans nouvelle demande. */
        @Volatile
        var alive = false
    }

    private lateinit var wm: WindowManager
    private var thread: HandlerThread? = null
    private lateinit var handler: Handler
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var w = 0
    private var h = 0
    private var dpi = 0

    private var overlay: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val templates = mutableListOf<Mat>()
    private val templateNames = mutableListOf<String>()
    private var compass: Mat? = null
    private var lastMeat: Mat? = null
    private var compassArmedAt = 0L // instant du dernier « Victoire » / « Secourir » (0 = rien à attendre)
    private var compassDelayMs = 3000L
    private var mode = "intel" // « intel » (missions de renseignements) ou « farm »
    private var minMeat = 20
    private var marchTotal = 5 // nombre total de marches (5 ou 6), réglé dans l'appli
    private var panelHeader: Mat? = null
    private var popupTitle: Mat? = null // fenêtre « File de Marche » : plus aucune marche libre
    private var popupX: Mat? = null
    private var goldTpl: Mat? = null // tête dorée (pin doré à tête d'ogre) : se reconnaît par son dessin, pas par la couleur (les blocs d'or de la carte sont dorés aussi)
    private var hexTpl: Mat? = null // pin hexagonal du boss (tête de roi) : à ne jamais attaquer
    private var noMarchUntil = 0L
    private var farmBackPending = false
    private var farmCap = 6 // marches libres au moment du plan
    private var farmLaunched = 0 // récoltes réellement envoyées (Déployer)
    private var lastPanel: Mat? = null // bande du panneau des marches, pleine résolution (mode farm)
    private var panelY0 = 0
    private var grandRunning = false
    private var grandType: String? = null // ressource du grand bâtiment (PAIN / BOIS / PIERRE / FER)
    private var harvestMin = 5
    private var statChecks = 0
    private var idleMs = 30_000L
    private var lastIdleLog = 0L
    private var emptySince = 0L // début de la période où la carte des pins est vide (0 pin vu, coché ou lancé)
    private var emptyLast = 0L
    private var statCand = 0
    private var statDone = 0
    private var statSeen = 0
    private var pinMemoryMs = 20 * 60_000L
    private class PinSeen(val x: Double, val y: Double, val t: Long)
    private var pinClickAt = 0L // dernier clic sur un pin : on laisse le temps au flou et à la fenêtre beige de s'afficher
    private val pinsSeen = mutableListOf<PinSeen>() // pins déjà lancés : on ne les reclique pas tout de suite
    // Décalage du clic (en px d'un écran de 1080 de large) par rapport au centre de l'image reconnue :
    // sur « Victoire ! » on touche en dessous de la bannière (« Appuie n'importe où pour quitter »).
    // Boutons après lesquels on revient à la carte des pins par la boussole : troupes parties (ours), victoire, secours (tente).
    private val compassAfter = setOf("01_victoire.png", "03_deployer.png", "07_secourir.png")
    private val clickOffsets = mapOf(
        "01_victoire.png" to doubleArrayOf(0.0, 1000.0),
        "09_recompenses.png" to doubleArrayOf(0.0, 1000.0) // « Récompenses » : appuyer n'importe où ferme l'écran
    )
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var lastGray: Mat? = null
    private var lastRgb: Mat? = null
    private val gates = mutableListOf<Mat>() // titre de l'écran des pins (FR / EN)
    private var lastProcess = 0L
    private var lastClick = 0L

    @Volatile
    private var running = false
    private var threshold = 0.8
    private var interval = 500L
    private var cooldown = 1000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) {
            // Capture déjà autorisée : on change simplement de mode, sans nouvelle autorisation.
            intent?.getStringExtra("mode")?.let { newMode -> main.post { switchMode(newMode) } }
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this, 1, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        mode = intent?.getStringExtra("mode") ?: "intel"
        if (!OpenCVLoader.initLocal()) {
            toast("OpenCV : échec du chargement")
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        thread = HandlerThread("bot").also { it.start() }
        handler = Handler(thread!!.looper)

        loadConfig()
        log("démarrage ($mode) : seuil $threshold, boussole ${compassDelayMs / 1000} s, viande min $minMeat")

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, handler)

        setupDisplay()
        loadPanelHeader()
        loadExtras()
        if (mode == "farm") {
            loadFarm()
        } else if (mode == "hunt") {
            loadHunt()
        } else if (mode == "daily") {
            loadDaily()
        } else if (mode == "marchand") {
            loadMarchand()
        } else if (mode == "epreuves") {
            loadEpreuves()
        } else if (mode == "heal") {
            loadHeal()
        } else {
            loadTemplates()
            loadGate()
            loadCompass()
        }
        addOverlay()
        addMenuButton()
        handler.post(tick)
        alive = true
        return START_NOT_STICKY
    }

    /** Change de mode sur la capture existante : on libère les modèles de l'ancien mode et on charge ceux du nouveau. */
    private fun switchMode(newMode: String) {
        running = false
        handler.post {
            try {
                mode = newMode
                templates.forEach { it.release() }
                templates.clear()
                templateNames.clear()
                farm.values.forEach { it.release() }
                farm.clear()
                gates.forEach { it.release() }
                gates.clear()
                compass?.release()
                compass = null
                pinsSeen.clear()
                compassArmedAt = 0L
                noMarchUntil = 0L
                farmBackPending = false
                loadConfig()
                log("changement de mode ($mode) sans nouvelle autorisation de capture")
                when (mode) {
                    "farm" -> loadFarm()
                    "hunt" -> loadHunt()
                    "daily" -> loadDaily()
                    "marchand" -> loadMarchand()
                    "epreuves" -> loadEpreuves()
                    "heal" -> loadHeal()
                    else -> {
                        loadTemplates()
                        loadGate()
                        loadCompass()
                    }
                }
            } catch (e: Exception) {
                log("changement de mode : erreur ${e.message}")
            }
            main.post { showOverlays(true) }
        }
    }

    /** Affiche ou cache les boutons flottants (cachés pendant qu'on est dans le menu de l'appli). */
    private fun showOverlays(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        overlay?.let {
            it.visibility = v
            it.text = "▶"
            (it.background as GradientDrawable).setColor(0xCC2E7D32.toInt())
        }
        menuBtn?.visibility = v
    }

    // ---------- Config & modèles ----------

    private fun loadConfig() {
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        threshold = sp.getInt("threshold", 80) / 100.0
        interval = sp.getLong("interval", 500L).coerceAtLeast(100L)
        cooldown = sp.getLong("cooldown", 1000L).coerceAtLeast(0L)
        minMeat = sp.getInt("minMeat", 20)
        // Farm : le total vient du choix « 5 ou 6 marches » ; missions de renseignements : de leur réglage.
        marchTotal = (
            when (mode) {
                "farm" -> sp.getInt("farmMarches", 5)
                "hunt" -> sp.getInt("huntMarches", 5)
                else -> sp.getInt("marchTotal", 5)
            }
        ).coerceIn(1, 6)
        harvestMin = sp.getInt("harvestMin", 5).coerceIn(0, 50)
        idleMs = sp.getInt("idleSec", 30).coerceIn(0, 3600) * 1000L
        pinMemoryMs = sp.getInt("pinMemory", 20).coerceIn(0, 600) * 60_000L
        compassDelayMs = sp.getInt("compassDelay", 3).coerceIn(0, 60) * 1000L
    }

    private fun addTemplate(bmp: Bitmap, factor: Double, name: String) {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        bmp.recycle()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val small = Mat()
        Imgproc.resize(gray, small, Size(), factor, factor, Imgproc.INTER_AREA)
        gray.release()
        templates.add(small)
        templateNames.add(name)
    }

    /** Images intégrées (boutons du jeu, prises sur un écran de 1080 px) puis images ajoutées par l'utilisateur. */
    private fun loadTemplates() {
        try {
            assets.list("templates")?.sorted()?.forEach { name ->
                val bmp = assets.open("templates/$name").use { BitmapFactory.decodeStream(it) } ?: return@forEach
                addTemplate(bmp, SCALE * w / 1080.0, name)
            }
        } catch (_: Exception) {
        }
        File(filesDir, "templates").listFiles()?.sortedBy { it.name }?.forEach { f ->
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@forEach
            addTemplate(bmp, SCALE, "user_" + f.name)
        }
    }

    /**
     * Le titre de l'écran des pins (« Mission de renseignements » / « Intel Mission ») sert
     * d'interrupteur à la détection par couleur. Chaque image est livrée avec la largeur d'écran
     * sur laquelle elle a été prise (1080 px pour le français, 540 px pour l'anglais).
     */
    private fun loadGate() {
        for ((name, refWidth) in listOf("titre_mission_fr.png" to 1080.0, "titre_mission_en.png" to 540.0)) {
            try {
                val bmp = assets.open(name).use { BitmapFactory.decodeStream(it) } ?: continue
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)
                bmp.recycle()
                val gray = Mat()
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                rgba.release()
                val f = SCALE * w / refWidth
                val small = Mat()
                Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)
                gray.release()
                gates.add(small)
            } catch (_: Exception) {
            }
        }
    }

    /** En-tête « ▼ Marche » du panneau des marches (prise sur 1080 px) : sert à situer ses lignes. */
    private fun loadPanelHeader() {
        try {
            val bmp = assets.open("farm/panneau_entete.png").use { BitmapFactory.decodeStream(it) } ?: return
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            val f = SCALE * w / 1080.0
            val small = Mat()
            Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)
            gray.release()
            panelHeader = small
        } catch (_: Exception) {
        }
    }

    private fun loadAsset(path: String): Mat? {
        return try {
            val bmp = assets.open(path).use { BitmapFactory.decodeStream(it) } ?: return null
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            val f = SCALE * w / 1080.0
            val small = Mat()
            Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)
            gray.release()
            small
        } catch (_: Exception) {
            null
        }
    }

    private fun loadExtras() {
        popupTitle = loadAsset("file_marche_titre.png")
        popupX = loadAsset("file_marche_x.png")
        hexTpl = loadAsset("hexagone_boss.png")
        goldTpl = loadAsset("gold_head.png")
    }

    /**
     * Fenêtre « File de Marche » (plus de marche libre, proposition d'en débloquer) : on la ferme par sa croix et on
     * retient qu'aucune marche n'est libre pendant un moment. Renvoie true si elle était affichée.
     */
    private fun handleNoMarchPopup(screen: Mat, now: Long): Boolean {
        val t = popupTitle ?: return false
        val mt = matchBest(screen, t) ?: return false
        if (mt.maxVal < 0.8) return false
        val xt = popupX
        val mx = if (xt != null) matchBest(screen, xt) else null
        val cx: Float
        val cy: Float
        if (xt != null && mx != null && mx.maxVal >= 0.8) {
            cx = ((mx.maxLoc.x + xt.cols() / 2.0) / SCALE).toFloat()
            cy = ((mx.maxLoc.y + xt.rows() / 2.0) / SCALE).toFloat()
        } else {
            cx = (0.89 * w).toFloat()
            cy = ((mt.maxLoc.y + t.rows() / 2.0) / SCALE).toFloat()
        }
        noMarchUntil = now + 30_000
        lastClick = now
        ClickAccessibilityService.instance?.click(cx, cy)
        log("fenêtre « File de Marche » : plus de marche libre, fermée")
        return true
    }

    private fun backArrow() {
        ClickAccessibilityService.instance?.click((0.069 * w).toFloat(), (0.13 * w).toFloat())
    }

    /**
     * Nombre de marches sorties = lignes du panneau « Marche » : une ligne a un rond turquoise (ressource) à gauche
     * ou un bouton cyan (rappel / accélération) à droite. 0 si le panneau n'est pas visible (aucune marche sortie).
     */
    private fun usedMarches(screen: Mat, allowTeal: Boolean = false): Int {
        val ht = panelHeader ?: return 0
        val hm = matchBest(screen, ht) ?: return 0
        if (hm.maxVal < 0.78) return 0
        val panel = lastPanel ?: return 0
        val sc = w / 1080.0
        val headerY = (hm.maxLoc.y + ht.rows() / 2.0) / SCALE
        val rgb3 = Mat()
        Imgproc.cvtColor(panel, rgb3, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat()
        Imgproc.cvtColor(rgb3, hsv, Imgproc.COLOR_RGB2HSV)
        rgb3.release()
        var used = 0
        for (i in 0 until 6) {
            val cy = headerY + (82 + i * 91) * sc - panelY0
            val teal = fraction(hsv, 22 * sc, cy - 28 * sc, 62 * sc, 56 * sc, Scalar(80.0, 50.0, 70.0), Scalar(100.0, 255.0, 255.0))
            val cyan = fraction(hsv, 303 * sc, cy - 24 * sc, 51 * sc, 48 * sc, Scalar(84.0, 90.0, 170.0), Scalar(102.0, 255.0, 255.0))
            // Une fenêtre posée sur le panneau cache les boutons cyan : seulement alors, le rond turquoise sert aussi.
            if (cyan > 0.3 || (allowTeal && teal > 0.35)) used++ else break
        }
        hsv.release()
        return used
    }

    private fun fraction(hsv: Mat, x: Double, y: Double, rw: Double, rh: Double, lo: Scalar, hi: Scalar): Double {
        val rx = x.toInt()
        val ry = y.toInt()
        val iw = rw.toInt()
        val ih = rh.toInt()
        if (rx < 0 || ry < 0 || iw <= 0 || ih <= 0 || rx + iw > hsv.cols() || ry + ih > hsv.rows()) return 0.0
        val roi = Mat(hsv, Rect(rx, ry, iw, ih))
        val mask = Mat()
        Core.inRange(roi, lo, hi, mask)
        val f = Core.countNonZero(mask).toDouble() / (iw * ih)
        mask.release()
        return f
    }

    /** Bouton boussole de la carte du monde (retour à « Mission de renseignements »), pris sur 1080 px. */
    private fun loadCompass() {
        try {
            val bmp = assets.open("boussole.png").use { BitmapFactory.decodeStream(it) } ?: return
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            val f = SCALE * w / 1080.0
            val small = Mat()
            Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)
            gray.release()
            compass = small
        } catch (_: Exception) {
        }
    }

    // ---------- Capture ----------

    @Suppress("DEPRECATION")
    private fun readScreenSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        return r
    }

    private fun setupDisplay() {
        val (sw, sh, sd) = readScreenSize()
        w = sw; h = sh; dpi = sd
        val r = newReader()
        reader = r
        vd = projection!!.createVirtualDisplay(
            "visual-clicker", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    /** Gère la rotation de l'écran sans recréer la projection (interdit sur Android 14). */
    private fun checkResize() {
        val (sw, sh, sd) = readScreenSize()
        if (sw == w && sh == h) return
        w = sw; h = sh; dpi = sd
        val old = reader
        val r = newReader()
        reader = r
        vd?.resize(w, h, dpi)
        vd?.surface = r.surface
        old?.close()
        lastGray?.release()
        lastGray = null
        lastRgb?.release()
        lastRgb = null
        lastMeat?.release()
        lastMeat = null
    }

    private fun onFrame(rd: ImageReader) {
        val img = rd.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (!running || now - lastProcess < interval) return
            lastProcess = now
            lastGray?.release()
            lastRgb?.release()
            lastMeat?.release()
            val (gray, rgb, meat) = capture(img)
            lastGray = gray
            lastRgb = rgb
            lastMeat = meat
            detectAndClick(gray, rgb, meat)
        } finally {
            img.close()
        }
    }

    /** Renvoie (niveaux de gris, RGB) en demi-résolution. */
    private fun capture(img: Image): Triple<Mat, Mat, Mat> {
        val p = img.planes[0]
        val bmpW = p.rowStride / p.pixelStride
        val bmp = Bitmap.createBitmap(bmpW, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(p.buffer)
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        bmp.recycle()
        // Compteur de viande (en haut à droite), pris en pleine résolution pour la lecture du nombre.
        val mx0 = (0.828 * img.width).toInt()
        val mx1 = (0.935 * img.width).toInt()
        val my0 = (0.105 * img.width).toInt()
        val my1 = minOf((0.158 * img.width).toInt(), img.height)
        val meat = Mat(rgba, Rect(mx0, my0, mx1 - mx0, my1 - my0)).clone()
        run {
            val sc = img.width / 1080.0
            panelY0 = (380 * sc).toInt().coerceAtMost(img.height - 2)
            val py1 = minOf((1010 * sc).toInt(), img.height)
            val px1 = minOf((372 * sc).toInt(), img.width)
            lastPanel?.release()
            lastPanel = Mat(rgba, Rect(0, panelY0, px1, py1 - panelY0)).clone()
        }
        val cropped = rgba.submat(0, img.height, 0, img.width)
        val smallRgba = Mat()
        Imgproc.resize(cropped, smallRgba, Size(), SCALE, SCALE, Imgproc.INTER_AREA)
        cropped.release()
        rgba.release()
        val gray = Mat()
        Imgproc.cvtColor(smallRgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val rgb = Mat()
        Imgproc.cvtColor(smallRgba, rgb, Imgproc.COLOR_RGBA2RGB)
        smallRgba.release()
        return Triple(gray, rgb, meat)
    }

    // ---------- Détection & clic ----------

    private var lastTickError = ""

    private val tick = object : Runnable {
        override fun run() {
            try {
                checkResize()
                val now = SystemClock.uptimeMillis()
                // Écran statique : aucune nouvelle image n'arrive, on réutilise la dernière.
                if (running && now - lastProcess >= interval) {
                    val g = lastGray
                    val c = lastRgb
                    val m = lastMeat
                    if (g != null && c != null && m != null) {
                        lastProcess = now
                        detectAndClick(g, c, m)
                    }
                }
            } catch (e: Exception) {
                val m = e.javaClass.simpleName + " " + (e.message ?: "")
                if (m != lastTickError) { lastTickError = m; log("erreur interne : $m") }
            }
            handler.postDelayed(this, interval)
        }
    }

    private fun matchBest(screen: Mat, t: Mat): Core.MinMaxLocResult? {
        if (t.cols() > screen.cols() || t.rows() > screen.rows()) return null
        val res = Mat()
        Imgproc.matchTemplate(screen, t, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res)
        res.release()
        return mm
    }

    private fun detectAndClick(screen: Mat, rgb: Mat, meat: Mat) {
        if (mode == "farm") {
            detectFarm(screen, rgb)
            return
        }
        if (mode == "hunt") {
            detectHunt(screen, rgb)
            return
        }
        if (mode == "daily") {
            detectDaily(screen, rgb)
            return
        }
        if (mode == "marchand") {
            detectMarchandSolo(rgb)
            return
        }
        if (mode == "epreuves") {
            detectEpreuves(rgb)
            return
        }
        if (mode == "heal") {
            detectHeal(screen, rgb)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < cooldown) return
        if (handleNoMarchPopup(screen, now)) return
        // 1) boutons du jeu (intégrés) et images ajoutées par l'utilisateur
        for ((i, t) in templates.withIndex()) {
            if (templateNames[i] == HARVEST) continue // cliqué seulement selon le nombre de coches vertes
            val mm = matchBest(screen, t) ?: continue
            if (mm.maxVal >= threshold) {
                var cx = ((mm.maxLoc.x + t.cols() / 2.0) / SCALE).toFloat()
                var cy = ((mm.maxLoc.y + t.rows() / 2.0) / SCALE).toFloat()
                val off = clickOffsets[templateNames[i]]
                if (off != null) {
                    cx += (off[0] * w / 1080.0).toFloat()
                    cy += (off[1] * w / 1080.0).toFloat()
                }
                if (noMarchUntil > now && templateNames[i] == "03_deployer.png") {
                    // Plus de marche : on sort de l'écran de déploiement au lieu de redemander.
                    lastClick = now
                    backArrow()
                    compassArmedAt = now
                    log("plus de marche libre : retour depuis l'écran de déploiement")
                    return
                }
                if (templateNames[i] == "04_attaquer.png" && noMarchUntil > now) {
                    log("plus de marche libre : chasse ignorée, retour à la carte des pins")
                    backToPins(screen, now)
                    return
                }
                if (templateNames[i] == "04_attaquer.png") {
                    // « Attaquer » = chasse à tête de loup : elle exige une marche libre (tentes et épées croisées non).
                    val used = usedMarches(screen, true)
                    if (used >= marchTotal) {
                        log("marches $used/$marchTotal : aucune libre, chasse ignorée, retour à la carte des pins")
                        backToPins(screen, now)
                        return
                    }
                }
                if (templateNames[i] in compassAfter) {
                    compassArmedAt = now
                    log("boussole armée par ${templateNames[i]} (délai ${compassDelayMs / 1000} s)")
                }
                lastClick = now
                ClickAccessibilityService.instance?.click(cx, cy)
                log("clic ${templateNames[i]} (%.2f) en (%d, %d)".format(mm.maxVal, cx.toInt(), cy.toInt()))
                toast("Clic (%.2f)".format(mm.maxVal))
                return
            }
        }
        val onPinScreen = gates.any { g -> (matchBest(screen, g)?.maxVal ?: 0.0) >= 0.85 }
        // 2) après un départ de troupes, une victoire ou un secours : boussole pour revenir aux pins
        if (compassArmedAt > 0L) {
            val since = now - compassArmedAt
            if (onPinScreen) {
                log("déjà sur la carte des pins : boussole inutile")
                compassArmedAt = 0L
            } else if (since > compassDelayMs + 20_000) {
                log("boussole abandonnée (introuvable pendant ${(since / 1000)} s)")
                compassArmedAt = 0L
            } else if (since >= compassDelayMs) {
                val c = compass
                val mm = if (c != null) matchBest(screen, c) else null
                val score = mm?.maxVal ?: 0.0
                if (c != null && mm != null && score >= minOf(threshold, 0.75)) {
                    val cx = ((mm.maxLoc.x + c.cols() / 2.0) / SCALE).toFloat()
                    val cy = ((mm.maxLoc.y + c.rows() / 2.0) / SCALE).toFloat()
                    compassArmedAt = 0L
                    lastClick = now
                    ClickAccessibilityService.instance?.click(cx, cy)
                    log("clic boussole (%.2f) en (%d, %d)".format(score, cx.toInt(), cy.toInt()))
                    toast("Retour à la carte des pins")
                    return
                }
                if (since >= compassDelayMs + 6_000) {
                    // Repli : la boussole est toujours au même endroit de la carte du monde.
                    val fx = (0.921 * w).toFloat()
                    val fy = (0.732 * h).toFloat()
                    compassArmedAt = 0L
                    lastClick = now
                    ClickAccessibilityService.instance?.click(fx, fy)
                    log("clic boussole en position fixe (%d, %d) : meilleur score %.2f".format(fx.toInt(), fy.toInt(), score))
                    toast("Retour à la carte (position fixe)")
                    return
                }
            }
        }
        // 3) écran des pins (« Mission de renseignements » / « Intel Mission »)
        if (!onPinScreen) {
            // Inactif depuis un moment sur la carte du monde (ex. la carte des pins s'est fermée à
            // son actualisation) : on rouvre la carte des pins avec la boussole.
            if (idleMs > 0 && compassArmedAt == 0L && now - lastClick > idleMs) {
                val c = compass
                val mm = if (c != null) matchBest(screen, c) else null
                if (c != null && mm != null && mm.maxVal >= 0.85) {
                    val cx = ((mm.maxLoc.x + c.cols() / 2.0) / SCALE).toFloat()
                    val cy = ((mm.maxLoc.y + c.rows() / 2.0) / SCALE).toFloat()
                    lastClick = now
                    ClickAccessibilityService.instance?.click(cx, cy)
                    log("inactif ${idleMs / 1000} s sur la carte du monde : clic boussole")
                    toast("Reprise : retour à la carte des pins")
                }
            }
            return
        }
        val pin = findPin(rgb, screen)
        // Récolte : « Récupérer Tout » quand assez de pins ont leur coche verte (ou s'il n'y a plus rien à cliquer).
        if (harvestMin > 0 && statChecks >= 1 && (statChecks >= harvestMin || pin == null)) {
            val hi = templateNames.indexOf(HARVEST)
            val ht = if (hi >= 0) templates[hi] else null
            val hm = if (ht != null) matchBest(screen, ht) else null
            if (ht != null && hm != null && hm.maxVal >= threshold) {
                val cx = ((hm.maxLoc.x + ht.cols() / 2.0) / SCALE).toFloat()
                val cy = ((hm.maxLoc.y + ht.rows() / 2.0) / SCALE).toFloat()
                lastClick = now
                ClickAccessibilityService.instance?.click(cx, cy)
                log("récolte : $statChecks coches vertes (seuil $harvestMin) : clic Récupérer Tout")
                toast("Récupérer Tout ($statChecks pins)")
                return
            }
        }
        if (pin == null) {
            if (statCand == 0 && statDone == 0 && statSeen == 0) {
                // Carte vide : on confirme sur 10 s d'affilée (un flou de transition donne aussi 0 pin) avant d'arrêter.
                if (emptySince == 0L || now - emptyLast > 4_000L) emptySince = now
                emptyLast = now
                if (now - emptySince >= 10_000L) {
                    emptySince = 0L
                    log("carte des pins : 0 pins vus, 0 cochés, 0 déjà lancés : missions de renseignements terminées, arrêt")
                    pauseBot("Missions de renseignements terminées")
                    return
                }
            } else emptySince = 0L
            if (now - lastIdleLog > 20_000) {
                lastIdleLog = now
                log("carte des pins : $statCand pins vus, $statDone cochés, $statSeen déjà lancés : rien à cliquer")
            }
            return
        }
        emptySince = 0L
        if (now - pinClickAt < 4_500L) return // un pin vient d'être touché : flou, puis fenêtre beige ; un second toucher la fermerait
        if (!enoughMeat(meat)) return
        lastClick = now
        pinClickAt = now
        pinsSeen.removeAll { now - it.t > maxOf(pinMemoryMs, 25_000L) }
        pinsSeen.add(PinSeen(pin.first.toDouble(), pin.second.toDouble(), now))
        ClickAccessibilityService.instance?.click(pin.first, pin.second)
        log("clic pin en (%d, %d)".format(pin.first.toInt(), pin.second.toInt()))
        toast("Clic sur un pin")
    }

    /** Lit le compteur de viande ; pause le bot s'il est sous le minimum. Illisible = on continue. */
    private fun enoughMeat(meat: Mat): Boolean {
        if (minMeat <= 0) return true
        val v = readMeat(meat) ?: return true
        if (v >= minMeat) return true
        log("viande $v < $minMeat : pause")
        main.post { pauseBot("Plus assez de viande ($v < $minMeat) : pause") }
        return false
    }

    private fun readMeat(meat: Mat): Int? = ocrDigits(meat).toIntOrNull() // « 2 873 » -> 2873

    /** Lit les chiffres d'une zone de texte blanc sur fond sombre (ML Kit) ; chaîne vide si illisible. */
    private fun ocrDigits(src: Mat): String = ocrText(src).filter { it.isDigit() }

    /** Lit un texte blanc sur fond sombre (ML Kit) ; chaîne vide si illisible. */
    /** Ligne de texte reconnue : texte + centre vertical (en pixels de l'image source). */
    private class OcrLine(val text: String, val cy: Double, val cx: Double = 0.0)

    /** Texte ligne par ligne avec position. dark = texte sombre sur fond clair (fenêtres beiges du jeu). */
    private fun ocrLines(src: Mat, dark: Boolean, thr: Int = 0): List<OcrLine> {
        return try {
            val gray = Mat()
            Imgproc.cvtColor(src, gray, if (src.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            val big = Mat()
            Imgproc.resize(gray, big, Size(), 4.0, 4.0, Imgproc.INTER_CUBIC)
            val bin = Mat()
            Imgproc.threshold(big, bin, if (thr > 0) thr.toDouble() else if (dark) 150.0 else 170.0, 255.0, if (dark) Imgproc.THRESH_BINARY else Imgproc.THRESH_BINARY_INV)
            val padded = Mat()
            Core.copyMakeBorder(bin, padded, 20, 20, 20, 20, Core.BORDER_CONSTANT, Scalar(255.0))
            val bmp = Bitmap.createBitmap(padded.cols(), padded.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(padded, bmp)
            gray.release(); big.release(); bin.release(); padded.release()
            val res = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS)
            bmp.recycle()
            res.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                l.boundingBox?.let { OcrLine(l.text, ((it.top + it.bottom) / 2.0 - 20) / 4.0, ((it.left + it.right) / 2.0 - 20) / 4.0) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ocrText(src: Mat, dark: Boolean = false, thr: Int = 0): String {
        return try {
            val gray = Mat()
            Imgproc.cvtColor(src, gray, if (src.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            val big = Mat()
            Imgproc.resize(gray, big, Size(), 4.0, 4.0, Imgproc.INTER_CUBIC)
            val bin = Mat()
            if (dark) Imgproc.threshold(big, bin, if (thr > 0) thr.toDouble() else 150.0, 255.0, Imgproc.THRESH_BINARY) // texte sombre -> noir
            else Imgproc.threshold(big, bin, if (thr > 0) thr.toDouble() else 170.0, 255.0, Imgproc.THRESH_BINARY_INV) // texte blanc -> noir
            val padded = Mat()
            Core.copyMakeBorder(bin, padded, 20, 20, 20, 20, Core.BORDER_CONSTANT, Scalar(255.0))
            val bmp = Bitmap.createBitmap(padded.cols(), padded.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(padded, bmp)
            gray.release(); big.release(); bin.release(); padded.release()
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS).text
            bmp.recycle()
            text
        } catch (_: Exception) {
            ""
        }
    }

    /** Journal des actions (consultable dans l'appli) : aide à comprendre ce qui s'est passé. */
    private fun log(msg: String) {
        try {
            synchronized(BotService::class.java) {
                val f = File(filesDir, "journal.txt")
                val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date()) + " " + msg + "\n"
                f.appendText(line)
                if (f.length() > 60_000) f.writeText(f.readLines().takeLast(400).joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) {
        }
    }

    private fun pauseBot(msg: String) {
        running = false
        overlay?.let {
            it.text = "▶"
            (it.background as GradientDrawable).setColor(0xCC2E7D32.toInt())
        }
        toast(msg)
    }

    /** Retour à la carte des pins : boussole reconnue, sinon sa position fixe. */
    private fun backToPins(screen: Mat, now: Long) {
        val c = compass
        val mm = if (c != null) matchBest(screen, c) else null
        val x: Float
        val y: Float
        if (c != null && mm != null && mm.maxVal >= minOf(threshold, 0.75)) {
            x = ((mm.maxLoc.x + c.cols() / 2.0) / SCALE).toFloat()
            y = ((mm.maxLoc.y + c.rows() / 2.0) / SCALE).toFloat()
        } else {
            x = (0.921 * w).toFloat()
            y = (0.732 * h).toFloat()
        }
        lastClick = now
        ClickAccessibilityService.instance?.click(x, y)
        toast("Retour à la carte des pins")
    }

    // Plages HSV (OpenCV : H 0-179) des pins : bleu, mauve, or.
    private val pinRanges = listOf(
        Scalar(95.0, 100.0, 100.0) to Scalar(112.0, 255.0, 255.0),
        Scalar(118.0, 60.0, 90.0) to Scalar(140.0, 255.0, 255.0),
        Scalar(8.0, 80.0, 140.0) to Scalar(28.0, 255.0, 255.0)
    )

    /**
     * Cherche les pins (ours, épées, tente : même couleurs, même gabarit) et renvoie le plus haut.
     * Le pin hexagonal (tête de roi) est plus large et plus gros : écarté par les filtres de taille.
     */
    private fun findPin(rgb: Mat, screen: Mat): Pair<Float, Float>? {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val sw = rgb.cols().toDouble()
        // Pastilles « coche verte » = pin déjà fait (teinte jaune-vert, distincte du vert de la carte).
        val checkMask = Mat()
        Core.inRange(hsv, Scalar(44.0, 110.0, 160.0), Scalar(58.0, 255.0, 255.0), checkMask)
        val cLabels = Mat()
        val cStats = Mat()
        val cCents = Mat()
        val cn = Imgproc.connectedComponentsWithStats(checkMask, cLabels, cStats, cCents)
        val checks = ArrayList<DoubleArray>()
        for (j in 1 until cn) {
            val cw = cStats.get(j, Imgproc.CC_STAT_WIDTH)[0]
            val ch = cStats.get(j, Imgproc.CC_STAT_HEIGHT)[0]
            val ca = cStats.get(j, Imgproc.CC_STAT_AREA)[0]
            // Une coche : ~17x12 px (ratio 1,4, remplissage ~0,44). On écarte les liserés verts des boutons.
            if (ch < 0.012 * sw || cw / ch < 1.0 || cw / ch > 1.8) continue
            val fill = ca / (cw * ch)
            if (fill < 0.3 || fill > 0.7) continue
            checks.add(doubleArrayOf(cCents.get(j, 0)[0], cCents.get(j, 1)[0], ca))
        }
        checkMask.release(); cLabels.release(); cStats.release(); cCents.release()
        statChecks = checks.size
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        var bestX = 0.0
        var bestY = Double.MAX_VALUE
        statCand = 0; statDone = 0; statSeen = 0
        val visible = ArrayList<DoubleArray>()
        // Pin hexagonal du boss (tête de roi) : retrouvé par son dessin ; tout bloc doré qui le contient est ignoré.
        var hexX = -1.0
        var hexY = -1.0
        val ht = hexTpl
        val hmm = if (ht != null) matchBest(screen, ht) else null
        if (ht != null && hmm != null && hmm.maxVal >= 0.7) {
            hexX = hmm.maxLoc.x + ht.cols() / 2.0
            hexY = hmm.maxLoc.y + ht.rows() / 2.0
        }
        val goldSingles = ArrayList<DoubleArray>()
        for ((ri, range) in pinRanges.withIndex()) {
            val lo = range.first
            val hi = range.second
            val mask = Mat()
            Core.inRange(hsv, lo, hi, mask)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k5)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k3)
            val labels = Mat()
            val stats = Mat()
            val cents = Mat()
            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, cents)
            for (i in 1 until n) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0]
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0]
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0]
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]
                val a = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                // Le badge orange du personnage (en haut à gauche, toujours au même endroit) n'est pas un pin.
                if (x + w / 2.0 < 0.2 * sw && y + h / 2.0 > 0.222 * sw && y + h / 2.0 < 0.5 * sw) continue
                if (ri == 2 && (x <= 2 || x + w >= sw - 2)) continue // bloc d'or de la carte coupé par le bord de l'écran
                if (h < 0.11 * sw || h > 0.185 * sw) continue // le pin hexagonal (tête de roi) est bien plus haut
                if (ri == 2 && hexX >= 0 && hexX >= x && hexX <= x + w && hexY >= y && hexY <= y + h) continue // c'est le boss
                val pw = 0.099 * sw // largeur d'un pin
                val subs = ArrayList<DoubleArray>() // (x gauche, largeur) de chaque pin du bloc
                if (w >= 0.085 * sw && w <= 0.125 * sw && h / w >= 1.1 && h / w <= 1.8 && a <= 0.0105 * sw * sw) {
                    subs.add(doubleArrayOf(x, w))
                } else if (ri != 2 && w > 0.125 * sw && w <= 0.4 * sw && h / w < 1.05) { // (or : un bloc large est un amas d'or de la carte, jamais des pins)
                    // Plusieurs pins de même couleur qui se touchent forment un seul bloc large : on le découpe,
                    // sauf si une coche verte s'y trouve (pin terminé : « Récupérer Tout » le fera disparaître).
                    val inside = checks.any {
                        it[0] >= x - 0.2 * pw && it[0] <= x + w + 0.2 * pw && it[1] >= y - 0.1 * h && it[1] <= y + 0.6 * h
                    }
                    if (inside) continue
                    val k = (Math.round((w - pw) / (0.9 * pw)).toInt() + 1).coerceIn(2, 4)
                    for (j in 0 until k) subs.add(doubleArrayOf(x + j * (w - pw) / (k - 1), pw))
                } else {
                    continue
                }
                for (sub in subs) {
                    val sx = sub[0]
                    val spw = sub[1]
                    if (ri == 2) goldSingles.add(doubleArrayOf(sx + spw / 2, y + 0.4 * h))
                    statCand++
                    visible.add(doubleArrayOf((sx + spw / 2) / SCALE, (y + 0.4 * h) / SCALE))
                    val done = checks.any {
                        abs(it[0] - (sx + 0.81 * spw)) < 0.35 * spw &&
                            abs(it[1] - (y + 0.23 * spw)) < 0.35 * spw &&
                            it[2] >= 0.004 * spw * spw
                    }
                    if (done) { statDone++; continue }
                    val fx = (sx + spw / 2) / SCALE
                    val fy = (y + 0.4 * h) / SCALE
                    val tnow = SystemClock.uptimeMillis()
                    if (pinsSeen.any { tnow - it.t < maxOf(pinMemoryMs, 25_000L) && abs(it.x - fx) < 0.3 * spw / SCALE && abs(it.y - fy) < 0.3 * spw / SCALE }) {
                        statSeen++
                        continue
                    }
                    val cy = y + 0.4 * h
                    if (cy < bestY) {
                        bestY = cy
                        bestX = sx + spw / 2
                    }
                }
            }
            mask.release(); labels.release(); stats.release(); cents.release()
        }
        hsv.release(); k5.release(); k3.release()
        // Têtes dorées : reconnues par leur dessin (même collées à un bloc d'or, que la couleur ne sépare pas)
        val gt = goldTpl
        if (gt != null && gt.cols() <= screen.cols() && gt.rows() <= screen.rows()) {
            val res = Mat()
            Imgproc.matchTemplate(screen, gt, res, Imgproc.TM_CCOEFF_NORMED)
            val spw = 0.099 * sw
            for (peak in 0 until 6) {
                val mm = Core.minMaxLoc(res)
                if (mm.maxVal < 0.72) break
                val px = mm.maxLoc.x + gt.cols() / 2.0
                val py = mm.maxLoc.y + gt.rows() / 2.0
                Imgproc.rectangle(res, Point(mm.maxLoc.x - gt.cols(), mm.maxLoc.y - gt.rows()), Point(mm.maxLoc.x + gt.cols(), mm.maxLoc.y + gt.rows()), Scalar(-1.0), -1)
                if (goldSingles.any { abs(it[0] - px) < 0.5 * spw && abs(it[1] - py) < 0.8 * spw }) continue // déjà vue par la couleur
                val top = py - 0.53 * spw
                val sx = px - spw / 2
                statCand++
                val cyc = py + 0.1 * spw
                visible.add(doubleArrayOf(px / SCALE, cyc / SCALE))
                val done = checks.any {
                    abs(it[0] - (sx + 0.81 * spw)) < 0.35 * spw && abs(it[1] - (top + 0.23 * spw)) < 0.35 * spw && it[2] >= 0.004 * spw * spw
                }
                if (done) { statDone++; continue }
                val fx = px / SCALE
                val fy = cyc / SCALE
                val tnow = SystemClock.uptimeMillis()
                if (pinsSeen.any { tnow - it.t < maxOf(pinMemoryMs, 25_000L) && abs(it.x - fx) < 0.3 * spw / SCALE && abs(it.y - fy) < 0.3 * spw / SCALE }) {
                    statSeen++
                    continue
                }
                if (cyc < bestY) { bestY = cyc; bestX = px }
            }
            res.release()
        }
        // Un pin lancé qui a disparu (récolté) ne doit pas masquer un autre pin apparu près de lui.
        if (visible.isNotEmpty()) {
            val tn = SystemClock.uptimeMillis()
            val tol = 0.03 * sw / SCALE
            pinsSeen.removeAll { ps ->
                tn - ps.t > 15_000 && visible.none { v -> abs(v[0] - ps.x) < tol && abs(v[1] - ps.y) < tol }
            }
        }
        if (bestY == Double.MAX_VALUE) return null
        return Pair((bestX / SCALE).toFloat(), (bestY / SCALE).toFloat())
    }

    // ---------- Mode Farm ----------

    private val farm = HashMap<String, Mat>()
    private val farmTasks = mutableListOf<String>()
    private var farmIdx = 0
    private var tileClickedIdx = -1
    private var searchedAt = 0L
    private var taskStartedAt = 0L
    private var farmFinished = false
    private val farmKinds = listOf("PAIN", "BOIS", "PIERRE", "FER")
    private val farmTiles = mapOf("PAIN" to "tile_pain", "BOIS" to "tile_bois", "PIERRE" to "tile_pierre", "FER" to "tile_fer")

    /** Images du mode Farm (prises sur 1080 px) et plan de récolte choisi dans l'appli. */
    private fun loadFarm() {
        val names = listOf(
            "loupe", "tile_pain", "tile_bois", "tile_pierre", "tile_fer", "tile_terreur",
            "rechercher", "collecte_dialog", "demanteler", "squad_collecte", "deployer_farm",
            "panneau_entete", "panneau_pain", "panneau_bois", "panneau_pierre", "panneau_fer", "rappeler"
        )
        for (name in names) {
            try {
                val bmp = assets.open("farm/$name.png").use { BitmapFactory.decodeStream(it) } ?: continue
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)
                bmp.recycle()
                val gray = Mat()
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                rgba.release()
                val f = SCALE * w / 1080.0
                val small = Mat()
                Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)
                gray.release()
                farm[name] = small
            } catch (_: Exception) {
            }
        }
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        // Chaque cas (5 ou 6 marches) a ses réglages ; pour 5 marches, on reprend l'ancien réglage commun s'il existe.
        val n = marchTotal
        fun gi(name: String, def: Int) = sp.getInt("farm$n$name", if (n == 5) sp.getInt("farm$name", def) else def)
        farmGrand = sp.getBoolean("farm${n}Grand", if (n == 5) sp.getBoolean("farmGrand", true) else true)
        farmTarget["PAIN"] = gi("Pain", 1).coerceIn(0, 6)
        farmTarget["BOIS"] = gi("Bois", 1).coerceIn(0, 6)
        farmTarget["PIERRE"] = gi("Pierre", 1).coerceIn(0, 6)
        farmTarget["FER"] = gi("Fer", if (n == 6) 2 else 1).coerceIn(0, 6)
        farmCap = marchTotal
        resetGrandCheck()
        buildPlan(null)
    }

    private val farmTarget = HashMap<String, Int>()
    private var farmGrand = true
    private var planAdjusted = false
    private var planTries = 0

    // Vérification préalable du grand : Alliance > Territoire > Bâtiments Spéciaux
    private var grandChecked = false
    private var grandPresent = true // faux = aucun grand en place (on ne le cherche pas)
    private var knownGrandType: String? = null
    private var knownGrandRunning = false // « Collecte » vert vu sous le grand dans le Territoire
    private var grandCheckOk = false // la vérification a abouti : elle fait foi sur le panneau
    private var checkStartedAt = 0L
    private var checkTries = 0
    private var checkReads = 0
    private var checkWaits = 0
    private var checkBacks = 0
    private var checkReturning = false

    private fun resetGrandCheck() {
        grandChecked = false; grandPresent = true; knownGrandType = null; knownGrandRunning = false; grandCheckOk = false
        checkStartedAt = 0L; checkTries = 0; checkReads = 0; checkWaits = 0; checkBacks = 0; checkReturning = false
    }

    /** Plan de récolte = ce qu'on veut, moins ce que le panneau des marches montre déjà en cours. */
    private fun buildPlan(active: Map<String, Int>?) {
        farmTasks.clear()
        if (active == null) { grandRunning = knownGrandRunning; grandType = if (knownGrandRunning) knownGrandType else null }
        if (farmGrand && grandPresent && !grandRunning) farmTasks.add("GRAND")
        val gType = grandType ?: knownGrandType
        for (k in farmKinds) {
            // Le grand (en cours, ou qu'on va lancer) couvre une récolte de son type : pas besoin d'une petite en plus.
            val need = (farmTarget[k] ?: 0) - (active?.get(k) ?: 0) -
                (if ((grandRunning || (farmGrand && grandPresent)) && gType == k) 1 else 0)
            repeat(need.coerceIn(0, 6)) { farmTasks.add(k) }
        }
        farmIdx = 0
        farmLaunched = 0
        tileClickedIdx = -1
        searchedAt = 0L
        taskStartedAt = 0L
        farmFinished = false
        if (!farmGrand || !grandPresent || grandRunning) capPlan()
        log("farm : plan ${farmTasks.joinToString(", ")}" + if (active != null) " (déjà en cours : $active, grand en cours : $grandRunning)" else "")
    }

    /**
     * Lit le panneau « Marche n/m » en haut à gauche de la carte du monde : une ligne par marche, avec
     * l'icône de la ressource récoltée. Renvoie le nombre de récoltes en cours par type, ou null si le
     * panneau n'est pas visible (déplié).
     */
    private fun readRowSeconds(rowCenterY: Double, s: Double): Int? {
        val panel = lastPanel ?: return null
        val y0 = (rowCenterY + 2 * s - panelY0).toInt()
        val y1 = (rowCenterY + 34 * s - panelY0).toInt()
        val x0 = (95 * s).toInt()
        val x1 = minOf((292 * s).toInt(), panel.cols())
        if (y0 < 0 || y1 > panel.rows() || x1 <= x0) return null
        val d = ocrDigits(Mat(panel, Rect(x0, y0, x1 - x0, y1 - y0)))
        if (d.length != 6) return null
        return d.substring(0, 2).toInt() * 3600 + d.substring(2, 4).toInt() * 60 + d.substring(4, 6).toInt()
    }

    private var panelGrandRows = 0 // lignes du panneau reconnues comme le grand à la dernière lecture

    private fun readActive(screen: Mat, used: Int): Map<String, Int>? {
        val header = farmMatch(screen, "panneau_entete", 0.78) ?: return null
        val ht = farm["panneau_entete"]!!
        val s = w / 1080.0
        val headerY = (header.maxLoc.y + ht.rows() / 2.0) / SCALE
        val counts = HashMap<String, Int>()
        grandRunning = false
        grandType = null
        panelGrandRows = 0
        val panelHsv: Mat? = lastPanel?.let { pm ->
            val c3 = Mat()
            Imgproc.cvtColor(pm, c3, Imgproc.COLOR_RGBA2RGB)
            val hv = Mat()
            Imgproc.cvtColor(c3, hv, Imgproc.COLOR_RGB2HSV)
            c3.release()
            hv
        }
        for (i in 0 until minOf(used, 6)) { // seules les lignes occupées : une ligne vide montre la carte derrière
            val cy = (headerY + (82 + i * 91) * s) * SCALE // centre de la ligne, en demi-résolution
            val bx = (10 * s * SCALE).toInt()
            val bw = (100 * s * SCALE).toInt()
            val bh = (84 * s * SCALE).toInt()
            val by = (cy - bh / 2).toInt()
            if (by < 0 || by + bh > screen.rows() || bx + bw > screen.cols()) continue
            val band = Mat(screen, Rect(bx, by, bw, bh))
            var best = ""
            var bs = 0.0
            var second = 0.0
            for (k in farmKinds) {
                val t = farm["panneau_" + k.lowercase()] ?: continue
                if (t.cols() > band.cols() || t.rows() > band.rows()) continue
                val res = Mat()
                Imgproc.matchTemplate(band, t, res, Imgproc.TM_CCOEFF_NORMED)
                val v = Core.minMaxLoc(res).maxVal
                res.release()
                if (v > bs) { second = bs; bs = v; best = k } else if (v > second) { second = v }
            }
            val classified = bs >= 0.8 && bs - second >= 0.08
            // L'icône du grand porte une petite pastille blanche en bas à droite (même sous 7 h de collecte).
            val rowLocalY = headerY + (82 + i * 91) * s - panelY0
            val badge = panelHsv != null &&
                fraction(panelHsv, 66 * s, rowLocalY + 4 * s, 24 * s, 22 * s, Scalar(0.0, 0.0, 200.0), Scalar(179.0, 45.0, 255.0)) >= 0.04
            // Durée de collecte restante : plus de 7 h = c'est le grand bâtiment (son icône porte un petit
            // badge d'alliance, donc elle n'est pas toujours reconnue) ; sinon c'est une petite récolte.
            val secs = readRowSeconds(headerY + (82 + i * 91) * s, s)
            if (badge || (secs != null && secs > 7 * 3600)) {
                grandRunning = true
                panelGrandRows++
                if (bs >= 0.65 && bs - second >= 0.12) grandType = best // l'icône du grand porte un badge : reconnaissance plus souple
                log("farm : ligne ${i + 1} : c'est le grand (${if (badge) "pastille d'alliance" else "plus de 7 h"}, $grandType)")
            } else if (classified) {
                counts[best] = (counts[best] ?: 0) + 1
                log("farm : ligne ${i + 1} : $best (%.2f, suivant %.2f)".format(bs, second))
            } else {
                log("farm : ligne ${i + 1} : icône non reconnue (meilleur $best %.2f, suivant %.2f)".format(bs, second))
            }
        }
        panelHsv?.release()
        if (grandCheckOk) { // le « Collecte » vert du Territoire fait foi
            val kt = knownGrandType
            if (knownGrandRunning && panelGrandRows == 0 && kt != null && (counts[kt] ?: 0) > 0) {
                counts[kt] = counts[kt]!! - 1 // la ligne du grand n'avait pas été repérée : ce n'est pas une petite
                panelGrandRows = 1
                log("farm : une ligne $kt du panneau est le grand")
            }
            grandRunning = knownGrandRunning
            grandType = if (knownGrandRunning) knownGrandType else null
        }
        return counts
    }

    private fun farmMatch(screen: Mat, name: String, thr: Double = threshold): Core.MinMaxLocResult? {
        val t = farm[name] ?: return null
        val mm = matchBest(screen, t) ?: return null
        return if (mm.maxVal >= thr) mm else null
    }

    private fun farmCenter(name: String, mm: Core.MinMaxLocResult): Pair<Float, Float> {
        val t = farm[name]!!
        return Pair(
            ((mm.maxLoc.x + t.cols() / 2.0) / SCALE).toFloat(),
            ((mm.maxLoc.y + t.rows() / 2.0) / SCALE).toFloat()
        )
    }

    private fun farmClick(x: Float, y: Float, msg: String) {
        lastClick = SystemClock.uptimeMillis()
        ClickAccessibilityService.instance?.click(x, y)
        log(msg)
    }

    /** 6 marches au maximum (le grand compte pour une récolte de son type : on plafonne après l'avoir su). */
    private fun capPlan() {
        val pending = (farmCap - farmLaunched).coerceAtLeast(0) // marches encore libres
        while (farmTasks.size - farmIdx > pending) farmTasks.removeAt(farmTasks.size - 1)
    }

    private fun nextFarmTask() {
        log("farm : tâche ${farmIdx + 1}/${farmTasks.size} terminée")
        farmIdx++
        tileClickedIdx = -1
        searchedAt = 0L
        taskStartedAt = 0L
        capPlan()
    }

    /**
     * Enchaînement : loupe > liste de tuiles (grand / pain / bois / pierre / fer) > Rechercher >
     * fenêtre « Collecte » > (grand : écran de l'escouade > Collecte) > Déployer. Un grand déjà lancé
     * (bouton Collecte gris) : flèche retour en haut à gauche, tâche suivante.
     */
    private fun detectFarm(screen: Mat, rgb: Mat) {
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < cooldown) return
        if (farmBackPending) {
            farmBackPending = false
            lastClick = now
            backArrow()
            return
        }
        if (handleNoMarchPopup(screen, now)) {
            // Aucune marche libre : on arrête les récoltes et on ressort de l'écran de déploiement.
            farmIdx = farmTasks.size
            farmFinished = false
            farmBackPending = true
            log("farm : plus de marche libre, on arrête")
            return
        }
        if (checkGrand(screen, rgb, now)) return
        if (!planAdjusted && farmIdx == 0) {
            if (farmMatch(screen, "loupe", 0.85) != null || farmMatch(screen, "rechercher", 0.8) != null) {
                val used = usedMarches(screen)
                val active = readActive(screen, used)
                planTries++
                if (active != null) {
                    // Toutes les marches sorties doivent avoir été reconnues : sinon on relit (fond changeant derrière le panneau)
                    val seen = active.values.sum() + panelGrandRows
                    if (seen < used && planTries < 5) {
                        log("farm : panneau lu à moitié ($seen ligne(s) reconnue(s) sur $used), nouvelle lecture")
                        return
                    }
                    farmCap = (marchTotal - used).coerceAtLeast(0)
                    log("farm : marches $used/$marchTotal, $farmCap libre(s)")
                    buildPlan(active)
                    planAdjusted = true
                } else if (planTries >= 6) {
                    farmCap = marchTotal
                    log("farm : panneau des marches introuvable, plan complet")
                    buildPlan(null)
                    planAdjusted = true
                }
                return
            }
        }
        if (farmIdx >= farmTasks.size) {
            if (!farmFinished) {
                farmFinished = true
                log("farm terminé")
                main.post { pauseBot("Farm terminé : toutes les récoltes sont lancées") }
            }
            return
        }
        if (taskStartedAt == 0L) taskStartedAt = now
        val task = farmTasks[farmIdx]
        if (now - taskStartedAt > 120_000) {
            log("farm : tâche $task abandonnée (2 min sans résultat)")
            nextFarmTask()
            return
        }

        // 1) envoyer la récolte
        val md = farmMatch(screen, "deployer_farm")
        if (md != null) {
            val (x, y) = farmCenter("deployer_farm", md)
            farmClick(x, y, "farm : clic Déployer (%.2f) pour $task".format(md.maxVal))
            farmLaunched++
            nextFarmTask()
            return
        }
        // 2) écran de l'escouade du grand bâtiment : bouton bleu = on collecte, gris = déjà lancé
        if (task == "GRAND") {
            val ms = farmMatch(screen, "squad_collecte")
            if (ms != null) {
                if (grandType == null) readGrandType(rgb)?.let { applyGrandType(it) }
                val t = farm["squad_collecte"]!!
                val roi = Mat(rgb, Rect(ms.maxLoc.x.toInt(), ms.maxLoc.y.toInt(), t.cols(), t.rows()))
                val hsv = Mat()
                Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_RGB2HSV)
                val sat = Core.mean(hsv).`val`[1]
                hsv.release()
                if (sat < 90) {
                    lastClick = now
                    ClickAccessibilityService.instance?.click((0.069 * w).toFloat(), (0.13 * w).toFloat())
                    log("farm : grand déjà lancé (bouton gris, saturation %.0f) : flèche retour".format(sat))
                    nextFarmTask()
                } else {
                    val (x, y) = farmCenter("squad_collecte", ms)
                    farmClick(x, y, "farm : clic Collecte (escouade, saturation %.0f)".format(sat))
                }
                return
            }
        }
        // 2b) fenêtre du grand avec « Rappeler » : notre escouade y collecte déjà. On passe (sans rappeler !).
        if (task == "GRAND" && farmMatch(screen, "rappeler", 0.8) != null) {
            grandRunning = true
            val gt = grandType ?: knownGrandType ?: readGrandDialogType(rgb)
            log("farm : grand déjà en cours (bouton Rappeler, type ${gt ?: "inconnu"}) : on passe sans rappeler")
            if (gt != null && grandType == null) applyGrandType(gt)
            nextFarmTask()
            return
        }
        // 3) fenêtre « Collecte » sur la carte : celle du grand porte aussi un bouton « Démanteler »
        val mc = farmMatch(screen, "collecte_dialog")
        if (mc != null) {
            // Le grand : bouton « Démanteler » (chef d'alliance) ou, sinon, titre « Grand(e) … » lu à l'écran
            val grandDialog = farmMatch(screen, "demanteler", 0.8) != null ||
                readGrandDialogType(rgb) != null
            if ((task == "GRAND") == grandDialog) {
                val (x, y) = farmCenter("collecte_dialog", mc)
                searchedAt = 0L
                farmClick(x, y, "farm : clic Collecte (fenêtre %s)".format(if (grandDialog) "du grand" else "du petit"))
                return
            }
        }
        // 4) on attend le résultat de la recherche
        if (searchedAt > 0L && now - searchedAt < 9_000L) return
        // 5) liste de tuiles ouverte
        val mr = farmMatch(screen, "rechercher", 0.8)
        if (mr != null) {
            if (tileClickedIdx == farmIdx) {
                val (x, y) = farmCenter("rechercher", mr)
                searchedAt = now
                farmClick(x, y, "farm : clic Rechercher pour $task")
            } else {
                selectTile(screen, task, now)
            }
            return
        }
        // 6) carte du monde : la loupe ouvre la liste
        val ml = farmMatch(screen, "loupe", 0.85)
        if (ml != null) {
            val (x, y) = farmCenter("loupe", ml)
            farmClick(x, y, "farm : clic loupe")
        }
    }

    /** Repère la rangée de tuiles (pas fixe ~236 px) et touche celle de la tâche ; fait défiler si besoin. */
    private fun selectTile(screen: Mat, task: String, now: Long) {
        val pitch = 236.5 * w / 1080.0
        var sumX = 0.0
        var sumY = 0.0
        var n = 0
        for ((i, k) in farmKinds.withIndex()) {
            val name = farmTiles[k] ?: continue
            val t = farm[name] ?: continue
            val mm = matchBest(screen, t) ?: continue
            if (mm.maxVal >= 0.8) {
                sumX += (mm.maxLoc.x + t.cols() / 2.0) / SCALE - i * pitch
                sumY += (mm.maxLoc.y + t.rows() / 2.0) / SCALE
                n++
            }
        }
        if (n == 0) {
            // Aucune tuile de ressource visible mais « Terreur » oui : le grand est juste à sa droite,
            // donc Pain est deux cases plus loin (le grand est toujours à droite de Terreur).
            val mt0 = farmMatch(screen, "tile_terreur", 0.8)
            if (mt0 != null) {
                val t0 = farm["tile_terreur"]!!
                sumX = (mt0.maxLoc.x + t0.cols() / 2.0) / SCALE + 2 * pitch
                sumY = (mt0.maxLoc.y + t0.rows() / 2.0) / SCALE
                n = 1
            }
        }
        if (n == 0) {
            // Position de la liste inconnue : on la ramène vers le début.
            swipeRow(0.76 * h, 0.5 * w, now)
            log("farm : aucune tuile reconnue, retour au début de la liste")
            return
        }
        val painX = sumX / n
        val rowY = sumY / n
        if (task == "GRAND") {
            // Ordre de la liste : Bêtes, Terreur, [grand], Pain... : sans grand, Terreur est juste avant Pain.
            val mt = farmMatch(screen, "tile_terreur", 0.8)
            if (mt != null) {
                val tx = (mt.maxLoc.x + farm["tile_terreur"]!!.cols() / 2.0) / SCALE
                if (painX - tx < 1.5 * pitch) {
                    log("farm : pas de grand bâtiment disponible, on passe")
                    nextFarmTask()
                    return
                }
            }
        }
        val idx = farmKinds.indexOf(task) // -1 pour le grand : une case avant Pain
        val targetX = painX + idx * pitch
        if (targetX < 0.12 * w || targetX > 0.88 * w) {
            swipeRow(rowY, (0.5 * w - targetX).coerceIn(-0.6 * w, 0.6 * w), now)
            log("farm : défilement de la liste pour atteindre $task")
            return
        }
        tileClickedIdx = farmIdx
        farmClick(targetX.toFloat(), rowY.toFloat(), "farm : clic tuile $task en (%d, %d)".format(targetX.toInt(), rowY.toInt()))
    }

    /** Fait glisser la liste de [shift] px (positif = vers la droite) puis laisse le temps à l'animation. */
    /** Nom du bâtiment en haut de l'écran de l'escouade : Scierie = bois, Moulin = pain, Carrière = pierre, Mine = fer. */
    private class GrandScan(val names: Int, val present: Boolean, val running: Boolean, val type: String?)

    private fun isGrandName(t: String): Boolean {
        val l = t.lowercase()
        return ("grand" in l || "great" in l || "big" in l) && typeFromText(l) != null
    }

    /**
     * Liste « Bâtiments Spéciaux » du Territoire d'Alliance : quatre grands (Moulin, Scierie, Carrière, Mine de Fer).
     * Un grand en place affiche « Temps Restant » sous son nom. Celui que NOUS récoltons affiche en plus
     * « Collecte » en vert : c'est ce vert (détecté par couleur) qui dit « déjà en cours ». Sans minuteur : on l'ignore.
     */
    private fun scanGrandList(rgb: Mat): GrandScan {
        val sc = rgb.cols() / 1080.0
        val x0 = (290 * sc).toInt()
        val x1 = minOf((1000 * sc).toInt(), rgb.cols())
        val y0 = (300 * sc).toInt()
        val y1 = minOf((2250 * sc).toInt(), rgb.rows())
        if (x1 <= x0 || y1 <= y0) return GrandScan(0, false, false, null)
        val crop = Mat(rgb, Rect(x0, y0, x1 - x0, y1 - y0))
        val lines = ocrLines(crop, true)
        val hsv = Mat()
        Imgproc.cvtColor(crop, hsv, Imgproc.COLOR_RGB2HSV)
        val timeRx = Regex("\\d{1,2}:\\d{2}:\\d{2}")
        val names = lines.filter { isGrandName(it.text) }
        val times = lines.filter {
            val t = it.text.lowercase()
            ("restant" in t || "remaining" in t || timeRx.containsMatchIn(t)) && "recharg" !in t && "reload" !in t
        }
        var timerType: String? = null
        var timerFound = false
        var runType: String? = null
        var runFound = false
        for (n in names) {
            val type = typeFromText(n.text)
            if (times.any { it.cy - n.cy in 15 * sc..130 * sc }) { timerFound = true; if (timerType == null) timerType = type }
            // « Collecte » vert sous le minuteur (environ 130 px sous le nom)
            val gy0 = (n.cy + 90 * sc).toInt().coerceAtLeast(0)
            val gy1 = (n.cy + 175 * sc).toInt().coerceAtMost(crop.rows())
            val gx0 = (10 * sc).toInt()
            val gx1 = minOf((330 * sc).toInt(), crop.cols())
            if (gy1 - gy0 > 4 && gx1 > gx0) {
                val roi = Mat(hsv, Rect(gx0, gy0, gx1 - gx0, gy1 - gy0))
                val m = Mat()
                Core.inRange(roi, Scalar(40.0, 90.0, 90.0), Scalar(85.0, 255.0, 255.0), m)
                val frac = Core.countNonZero(m).toDouble() / (m.rows() * m.cols())
                m.release()
                if (frac >= 0.012) { runFound = true; if (runType == null) runType = type }
            }
        }
        hsv.release()
        return when {
            runFound -> GrandScan(names.size, true, true, runType)
            timerFound -> GrandScan(names.size, true, false, timerType)
            lines.any { "restant" in it.text.lowercase() || "remaining" in it.text.lowercase() } -> GrandScan(names.size, true, false, null)
            else -> GrandScan(names.size, false, false, null)
        }
    }

    private fun clickRef(x: Double, y: Double, msg: String) {
        lastClick = SystemClock.uptimeMillis()
        ClickAccessibilityService.instance?.click((x * w / 1080.0).toFloat(), (y * w / 1080.0).toFloat())
        log(msg)
    }

    /**
     * Avant de planifier : carte > bouton Alliance > Territoire > onglet Bâtiments Spéciaux, on lit quel grand est
     * en place (ou aucun), puis flèche retour jusqu'à la carte. Renvoie true tant que la vérification occupe l'écran.
     */
    private fun checkGrand(screen: Mat, rgb: Mat, now: Long): Boolean {
        if (grandChecked || !farmGrand) return false
        if (checkStartedAt == 0L) {
            checkStartedAt = now
            log("farm : vérification du grand (Alliance > Territoire > Bâtiments Spéciaux)")
        }
        val onMap = farmMatch(screen, "loupe", 0.85) != null
        if (!checkReturning && now - checkStartedAt > 60_000) {
            log("farm : vérification du grand trop longue, on la saute")
            checkReturning = true
        }
        if (checkReturning) {
            if (onMap) {
                grandChecked = true
                checkReturning = false
                log("farm : retour sur la carte, vérification terminée")
                return false
            }
            if (++checkBacks > 8) { grandChecked = true; log("farm : retour sur la carte introuvable, on continue"); return false }
            lastClick = now
            backArrow()
            return true
        }
        if (onMap) {
            if (++checkTries > 4) { checkReturning = true; log("farm : écran Alliance introuvable, on saute la vérification"); return true }
            clickRef(803.0, 2240.0, "farm : clic Alliance (barre du bas)")
            return true
        }
        val sc = rgb.cols() / 1080.0
        val x0 = (130 * sc).toInt()
        val x1 = minOf((760 * sc).toInt(), rgb.cols())
        val y0 = (90 * sc).toInt()
        val y1 = minOf((200 * sc).toInt(), rgb.rows())
        val title = if (x1 > x0 && y1 > y0) ocrText(Mat(rgb, Rect(x0, y0, x1 - x0, y1 - y0))).lowercase() else ""
        when {
            "territ" in title -> {
                val scan = scanGrandList(rgb)
                if (scan.names >= 2 || scan.present) {
                    if (!scan.present && ++checkReads < 2) return true // deuxième lecture pour confirmer « aucun grand »
                    grandPresent = scan.present
                    knownGrandType = scan.type
                    knownGrandRunning = scan.running
                    grandCheckOk = true
                    log(
                        when {
                            scan.running -> "farm : grand déjà en cours de récolte (Collecte vert) : ${scan.type ?: "type inconnu"}"
                            scan.present -> "farm : grand en place (minuteur, pas de Collecte vert) : ${scan.type ?: "type inconnu"} : à lancer"
                            else -> "farm : aucun minuteur : pas de grand en place, on l'ignore"
                        }
                    )
                    checkReturning = true
                    lastClick = now
                    backArrow()
                    return true
                }
                if (++checkTries > 6) { checkReturning = true; log("farm : liste des grands illisible, on saute la vérification"); return true }
                clickRef(765.0, 262.0, "farm : clic onglet Bâtiments Spéciaux")
            }
            "alliance" in title -> {
                if (++checkTries > 6) { checkReturning = true; log("farm : bouton Territoire introuvable, on saute la vérification"); return true }
                clickRef(285.0, 1300.0, "farm : clic Territoire")
            }
            else -> {
                if (++checkWaits > 10) { checkReturning = true; log("farm : écran inattendu pendant la vérification du grand") }
            }
        }
        return true
    }

    private fun typeFromText(raw: String): String? {
        val t = raw.lowercase()
        return when {
            "scier" in t || "sawmill" in t || "lumber" in t -> "BOIS"
            "moul" in t || "mill" in t || "farm" in t || "ferme" in t -> "PAIN"
            "carri" in t || "quarry" in t || "stone" in t -> "PIERRE"
            "mine" in t || "iron" in t || t.trim().endsWith("fer") -> "FER"
            else -> null
        }
    }

    private fun readGrandType(rgb: Mat): String? {
        val sc = rgb.cols() / 1080.0
        val x0 = (125 * sc).toInt()
        val x1 = minOf((720 * sc).toInt(), rgb.cols())
        val y0 = (100 * sc).toInt()
        val y1 = minOf((190 * sc).toInt(), rgb.rows())
        if (x1 <= x0 || y1 <= y0) return null
        return typeFromText(ocrText(Mat(rgb, Rect(x0, y0, x1 - x0, y1 - y0))))
    }

    /** Titre de la fenêtre du grand sur la carte (« Grande Scierie », « Grand Moulin », « Grande Carrière », « Grande Mine »). */
    private fun readGrandDialogType(rgb: Mat): String? {
        val sc = rgb.cols() / 1080.0
        val x0 = (240 * sc).toInt()
        val x1 = minOf((840 * sc).toInt(), rgb.cols())
        val y0 = (1480 * sc).toInt()
        val y1 = minOf((1700 * sc).toInt(), rgb.rows())
        if (x1 <= x0 || y1 <= y0) return null
        val t = ocrText(Mat(rgb, Rect(x0, y0, x1 - x0, y1 - y0)), true).lowercase()
        // seul le titre d'un GRAND bâtiment compte (« [ygd]Grande Carrière ») : un petit gisement n'a pas « grand »
        return if ("grand" in t || "great" in t) typeFromText(t) else null
    }

    /** Le grand couvre une récolte de son type : on retire une petite de ce type du reste du plan. */
    private fun applyGrandType(type: String) {
        grandType = type
        if (knownGrandType == type) { log("farm : le grand récolte du $type (déjà compté dans le plan)"); capPlan(); return }
        val idx = (farmIdx + 1 until farmTasks.size).firstOrNull { farmTasks[it] == type }
        if (idx != null) {
            farmTasks.removeAt(idx)
            log("farm : le grand récolte du $type : une petite récolte de moins")
        } else {
            log("farm : le grand récolte du $type")
        }
        capPlan()
    }

    private fun swipeRow(y: Double, shift: Double, now: Long) {
        val x1 = (0.5 * w - shift / 2).toFloat()
        val x2 = (0.5 * w + shift / 2).toFloat()
        ClickAccessibilityService.instance?.swipe(x1, y.toFloat(), x2, y.toFloat(), 350)
        lastClick = now + 600
    }

    // ---------- Mode Daily (onglet « Quot. » du menu de gauche) ----------

    private var dailyState = "OPEN" // OPEN = ouvrir menu + onglet, LIST = lire la liste, sinon : scénario de la tâche
    private var dailyStep = 0
    private var dailyStepAt = 0L
    private var dailyTries = 0
    private var dailyBtn = 0 // clics répétés sur un même bouton (évite de rester bloqué)
    private var dailyGrey = 0 // lectures consécutives du bouton grisé
    private var dailyActAt = 0L
    private var dailyIdleAt = 0L // dernier instant où un écran connu a été reconnu : au-delà de 10 s sans rien, on abandonne
    private var dailyFinished = false
    private val dailyAttempts = HashMap<String, Int>()
    private var lastListDump = ""
    private val dailyLaunchAt = HashMap<String, Long>()

    private fun resetDaily() {
        dailyState = "OPEN"; dailyStep = 0; dailyStepAt = 0L; dailyTries = 0; dailyFinished = false
        dailyAttempts.clear(); lastListDump = ""
    }

    private fun loadDaily() {
        for (path in listOf("daily/coffre_bulle.png", "daily/goutte.png", "daily/golem.png", "daily/golem2.png", "daily/bonus_collecte.png")) {
            val m = loadAsset(path) ?: continue
            farm[path.substringAfter('/').removeSuffix(".png")] = m
        }
        resetDaily()
        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        bonusForce = cfg.getBoolean("bonusForce", true)
        bonusFrom = cfg.getInt("bonusFrom", 6).coerceIn(0, 23)
        bonusTo = cfg.getInt("bonusTo", 9).coerceIn(0, 23)
        log("daily : démarrage (bonus de collecte : visite forcée ${if (bonusForce) "de $bonusFrom h à $bonusTo h" else "désactivée"})")
    }

    /** Texte d'une zone (coordonnées de référence 1080 px). */
    private fun ocrRef(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int, dark: Boolean = false): String {
        val sc = rgb.cols() / 1080.0
        val ax = (x0 * sc).toInt()
        val ay = (y0 * sc).toInt()
        val bx = minOf((x1 * sc).toInt(), rgb.cols())
        val by = minOf((y1 * sc).toInt(), rgb.rows())
        if (bx <= ax || by <= ay) return ""
        return ocrText(Mat(rgb, Rect(ax, ay, bx - ax, by - ay)), dark).lowercase()
    }

    /** Luminosité moyenne (V) d'une zone en coordonnées de référence. */
    private fun meanV(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val sc = rgb.cols() / 1080.0
        val ax = (x0 * sc).toInt()
        val ay = (y0 * sc).toInt()
        val bx = minOf((x1 * sc).toInt(), rgb.cols())
        val by = minOf((y1 * sc).toInt(), rgb.rows())
        if (bx <= ax || by <= ay) return 0.0
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(ax, ay, bx - ax, by - ay)), hsv, Imgproc.COLOR_RGB2HSV)
        val v = Core.mean(hsv).`val`[2]
        hsv.release()
        return v
    }

    /** Part de pixels « vert » (bouton coche) autour d'un point de référence. */
    private fun greenAt(rgb: Mat, x: Double, y: Double): Double {
        val sc = rgb.cols() / 1080.0
        val r = 22 * sc
        val x0 = (x * sc - r).toInt()
        val y0 = (y * sc - r).toInt()
        val sz = (2 * r).toInt()
        if (x0 < 0 || y0 < 0 || sz <= 0 || x0 + sz > rgb.cols() || y0 + sz > rgb.rows()) return 0.0
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(x0, y0, sz, sz)), hsv, Imgproc.COLOR_RGB2HSV)
        val m = Mat()
        Core.inRange(hsv, Scalar(55.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0), m)
        val f = Core.countNonZero(m).toDouble() / (sz * sz)
        hsv.release(); m.release()
        return f
    }

    private fun dailyFinish(msg: String) {
        dailyFinished = true
        log("daily : $msg")
        main.post { pauseBot(msg) }
    }

    private fun dailyTaskDone(name: String) {
        log("daily : $name terminée, retour à la liste")
        dailyState = "OPEN"
        dailyTries = 0
    }

    /**
     * Daily : on ouvre le menu de gauche (languette), onglet « Quot. », on lit la liste, on exécute la première
     * tâche prête qu'on sait faire, on revient à la liste, et ainsi de suite jusqu'à épuisement.
     */
    private fun detectDaily(screen: Mat, rgb: Mat) {
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < cooldown || dailyFinished) return

        // Écran de récompense du personnage (« Nouvelle Caisse Prête Dans ») : un clic dans le vide le ferme.
        if (dailyState == "ENLIGNE") {
            val reward = ocrRef(rgb, 250, 1840, 830, 1900)
            if ("caisse" in reward || "pr\u00eate" in reward || "prete" in reward) {
                dailyStep = 2
                dailyStepAt = now
                clickRef(655.0, 370.0, "daily : clic dans le vide pour fermer l'écran de récompense")
                return
            }
        }

        // Filet de sécurité : un écran « Récompenses » resté ouvert alors qu'aucune tâche n'est en cours (tâche abandonnée,
        // récompense arrivée en retard) : on appuie n'importe où pour le fermer avant de rouvrir le menu.
        if (dailyState == "OPEN" || dailyState == "LIST") {
            val rw = ocrRef(rgb, 200, 2150, 880, 2240)
            if ("appuie" in rw || "quitter" in rw) {
                dailyIdleAt = now
                tapRewards("daily : écran Récompenses resté ouvert : appui n'importe où")
                return
            }
        }

        when (dailyState) {
            "OPEN" -> {
                if (++dailyTries > 20) { dailyFinish("menu de gauche introuvable"); return }
                // Avant chaque tentative : l'icône « Ordres » (balance, en bas à droite) a-t-elle un point rouge ?
                val barText = ocrRef(rgb, 0, 2270, 1080, 2335)
                val cityVisible = listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in barText } >= 2
                // Tout premier préambule : bonus de vitesse de collecte (icône du chariot à droite de la flèche, en haut à gauche)
                if (cityVisible && (dailyAttempts["bonus"] ?: 0) < 2) {
                    val corr = bonusIconScore(screen)
                    val forced = corr >= 0.65 && bonusForce && bonusWindowNow()
                    if (corr < 0.65 || forced) {
                        dailyAttempts["bonus"] = (dailyAttempts["bonus"] ?: 0) + 1
                        dailyState = "BONUS"
                        dailyStep = 0
                        dailyBtn = 0
                        dailyGrey = 0
                        dailyActAt = now
                        dailyIdleAt = now
                        dailyStepAt = now
                        bonusBox = false
                        bonusBuys = 0
                        bonusAsk = 0
                        bonusBarAt = 0L
                        clickRef(70.0, 240.0, "daily : Bonus de collecte : ${if (forced) "icône présente mais visite forcée (plage $bonusFrom h-$bonusTo h)" else "icône absente"} (corrélation ${"%.2f".format(corr)}), ouverture de Revenu de la Ville")
                        return
                    }
                }
                // Banquet Gastronomique (Endurance du Chef) : même plage horaire que la visite forcée du bonus de collecte
                if (cityVisible && (dailyAttempts["banquet"] ?: 0) < 2 && bonusForce && bonusWindowNow()) {
                    dailyAttempts["banquet"] = (dailyAttempts["banquet"] ?: 0) + 1
                    dailyState = "BANQUET"
                    dailyStep = 0
                    dailyBtn = 0
                    dailyGrey = 0
                    dailyActAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    banqueTries = 0
                    banqueTries2 = 0
                    banquePlus = 0
                    clickRef(65.0, 150.0, "daily : Banquet Gastronomique : plage horaire ($bonusFrom h-$bonusTo h), ouverture du profil")
                    return
                }
                if (cityVisible && (dailyAttempts["ordres"] ?: 0) < 2 && redDotOnOrders(rgb)) {
                    dailyAttempts["ordres"] = (dailyAttempts["ordres"] ?: 0) + 1
                    dailyState = "ORDRES"
                    ordresChecked = false
                    ordresFest = false
                    ordresFestAt = 0L
                    dailyStep = 0
                    dailyBtn = 0
                    dailyActAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    clickRef(995.0, 1855.0, "daily : Ordres : point rouge, ouverture des ordres")
                    return
                }
                // Coffres d'alliance : chiffre rouge sur le bouton Alliance de la barre du bas
                if (cityVisible && (dailyAttempts["coffres"] ?: 0) < 2 && redBadgeOnAlliance(rgb)) {
                    dailyAttempts["coffres"] = (dailyAttempts["coffres"] ?: 0) + 1
                    dailyState = "COFFRES"
                    dailyStep = 0
                    dailyBtn = 0
                    dailyGrey = 0
                    dailyActAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    clickRef(803.0, 2240.0, "daily : Coffres d'alliance : chiffre rouge sur Alliance, ouverture")
                    return
                }
                // Missions quotidiennes : icône du parchemin en bas à gauche avec un chiffre dans un rond rouge
                if (cityVisible && (dailyAttempts["missions"] ?: 0) < 2 && redBadgeOnMissions(rgb)) {
                    dailyAttempts["missions"] = (dailyAttempts["missions"] ?: 0) + 1
                    dailyState = "MISSIONS"
                    dailyStep = 0
                    dailyBtn = 0
                    dailyActAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    clickRef(60.0, 2000.0, "daily : Missions quotidiennes : rond rouge, ouverture")
                    return
                }
                // VIP : point rouge à droite du badge « VIP » (en haut à droite de la ville)
                if (cityVisible && (dailyAttempts["vip"] ?: 0) < 2 && redDotAt(rgb, 1050.0, 185.0)) {
                    dailyAttempts["vip"] = (dailyAttempts["vip"] ?: 0) + 1
                    dailyState = "VIP"
                    dailyStep = 0
                    dailyBtn = 0
                    dailyGrey = 0
                    dailyActAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    clickRef(990.0, 200.0, "daily : VIP : point rouge sur le badge, ouverture")
                    return
                }
                // Pet Skill : point rouge sur la patte d'ours (en bas à droite de la ville)
                if (cityVisible && (dailyAttempts["pet"] ?: 0) < 2 && redDotAt(rgb, 1040.0, 1663.0)) {
                    dailyAttempts["pet"] = (dailyAttempts["pet"] ?: 0) + 1
                    dailyState = "PET"
                    dailyStep = 0
                    dailyBtn = 0
                    dailyGrey = 0
                    dailyActAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    petIdx = 0; petState = 0; petAt = 0L; petTries = 0; petReward = false; petUsed = 0
                    clickRef(995.0, 1710.0, "daily : Pet Skill : point rouge sur la patte d'ours, ouverture")
                    return
                }
                // Puis l'événement « Assaut Rebelle » (golem rouge en haut à gauche, avec son minuteur)
                val golem = if (cityVisible && (dailyAttempts["combat"] ?: 0) < 2) golemPoint(screen) else null
                if (golem != null) {
                    dailyAttempts["combat"] = (dailyAttempts["combat"] ?: 0) + 1
                    dailyState = "COMBAT"
                    dailyStep = 1
                    dailyBtn = 0
                    dailyActAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    farmClick(golem.first, golem.second, "daily : Combat : icône Assaut Rebelle, ouverture")
                    return
                }
                // Tout dernier préambule : boutiques (panier + gemmes en haut à droite avec un point rouge)
                if (cityVisible && (dailyAttempts["shop"] ?: 0) < 2 && redRect(rgb, 1050, 82, 1080, 114) >= 0.15) {
                    dailyAttempts["shop"] = (dailyAttempts["shop"] ?: 0) + 1
                    dailyState = "SHOP"
                    dailyStep = 0
                    dailyBtn = 0
                    dailyGrey = 0
                    dailyActAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    resetBq(now)
                    clickRef(975.0, 125.0, "daily : Boutiques : point rouge sur le panier, ouverture")
                    return
                }
                val tabs = ocrRef(rgb, 15, 615, 665, 695)
                val open = "ville" in tabs || "nature" in tabs || "quot" in tabs
                if (open) {
                    val q = meanV(rgb, 455, 625, 655, 685)
                    val v = meanV(rgb, 20, 625, 220, 685)
                    if (q > 150 && q > v + 40) {
                        dailyState = "LIST"
                        dailyTries = 0
                    } else {
                        clickRef(553.0, 655.0, "daily : clic onglet Quot.")
                    }
                } else {
                    // Menu fermé : la languette est visible quand la barre du bas du jeu l'est aussi.
                    if (cityVisible) clickRef(20.0, 1075.0, "daily : ouverture du menu (languette)")
                    else if (dailyTries % 5 == 0) {
                        // écran inconnu (reste d'une tâche abandonnée) : flèche retour en haut à gauche
                        lastClick = now
                        backArrow()
                        log("daily : écran inconnu, flèche retour")
                    }
                }
            }
            "LIST" -> {
                val sc = rgb.cols() / 1080.0
                val y0 = (695 * sc).toInt()
                val x0 = (15 * sc).toInt()
                val x1 = (665 * sc).toInt()
                val y1 = minOf((1455 * sc).toInt(), rgb.rows())
                val lines = ocrLines(Mat(rgb, Rect(x0, y0, x1 - x0, y1 - y0)), false)
                if (lines.isEmpty()) {
                    if (++dailyTries > 4) { dailyState = "OPEN"; dailyTries = 0 }
                    return
                }
                // diagnostic : ce que l'OCR lit dans la liste (une fois par contenu différent)
                val dump = lines.joinToString(" | ") { it.text.trim().replace("\n", " ") }.take(420)
                if (dump != lastListDump) { lastListDump = dump; log("daily : liste lue : $dump") }
                // Tâches connues, repérées par leur titre ; une ligne présente = à faire (le jeu masque les terminées).
                val cands = ArrayList<Pair<Double, String>>()
                var aventureCy = -1.0
                var contribCy = -1.0
                var creusetCy = -1.0
                for (l in lines) {
                    val t = l.text.lowercase()
                    if ("ligne" in t) cands.add(Pair(l.cy, "enligne"))
                    // « Aventure Animalière » apparaît deux fois (titre de section puis tâche) : on prend la plus basse
                    if ("aventure" in t && "animal" in t) aventureCy = maxOf(aventureCy, l.cy)
                    if ("provisions" in t) cands.add(Pair(l.cy, "provisions"))
                    if ("avanc" in t) cands.add(Pair(l.cy, "recrute")) // Recruter des Héros : Avancé
                    if ("epique" in t.replace('é', 'e') && "mystique" !in t) cands.add(Pair(l.cy, "epique")) // Recruter des Héros : Épique (gratuit)
                    if ("fontaine" in t) cands.add(Pair(l.cy, "fontaine")) // Collecte d'Essence d'Eau : Fontaine Vitale
                    if ("inactiv" in t) cands.add(Pair(l.cy, "conquete")) // Conquérir : Revenu d'inactivité
                    if ("creuset" in t || "creuseur" in t) creusetCy = maxOf(creusetCy, l.cy) // titre de section puis tâche : la plus basse
                    if ("triomphe" in t && "activit" in t) cands.add(Pair(l.cy, "triomphe")) // Triomphe d'activité quotidienne
                    // « Contrib. d'All. » : titre de section puis tâche (la ligne « Contrib 10/25 » n'a pas « all »)
                    // l'OCR lit parfois « All » comme « AII » (l/I) ou colle la ligne « Contrib 25/25 » : on accepte ces variantes
                    if ("contrib" in t && ("all" in t || "aii" in t || "ali" in t || "d'a" in t || "d’a" in t || '/' !in t)) contribCy = maxOf(contribCy, l.cy)
                }
                if (contribCy >= 0) cands.add(Pair(contribCy, "contrib"))
                if (creusetCy >= 0) cands.add(Pair(creusetCy, "creuset"))
                if (aventureCy >= 0) cands.add(Pair(aventureCy, "aventure"))
                cands.sortBy { it.first } // du haut vers le bas
                var launched = false
                for ((cy, key) in cands) {
                    // « Récomp. En Ligne » revient toutes les quelques minutes : on la reprend si la dernière visite date de plus de 30 s
                    if (key == "enligne" && (dailyAttempts[key] ?: 0) >= 2 && now - (dailyLaunchAt[key] ?: 0L) > 30_000L) dailyAttempts[key] = 0
                    if ((dailyAttempts[key] ?: 0) >= (if (key == "recrute" || key == "epique") 1 else 2)) { // un seul recrutement gratuit par passage
                        log("daily : $key visible mais déjà tentée ${dailyAttempts[key]} fois : on passe")
                        continue
                    }
                    val rowY = (y0 + cy) / sc // centre de la ligne en coordonnées de référence
                    dailyAttempts[key] = (dailyAttempts[key] ?: 0) + 1
                    dailyLaunchAt[key] = now
                    dailyState = key.uppercase()
                    if (key == "fontaine") resetFontaine()
                    dailyStep = 0
                    dailyBtn = 0
                    dailyGrey = 0
                    dailyTries = 0
                    dailyActAt = 0L
                    dropSeenAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    clickRef(335.0, rowY + 21, "daily : $key : clic sur le texte de la tâche") // le texte déclenche comme la coche
                    launched = true
                    break
                }
                if (!launched) {
                    if (contribCy < 0) lines.filter { "contr" in it.text.lowercase() || "all" in it.text.lowercase() }
                        .takeIf { it.isNotEmpty() }?.let { ls -> log("daily : lecture OCR près de Contrib : " + ls.joinToString(" | ") { it.text.trim() }) }
                    dailyFinish("Daily terminé : plus de tâche à faire")
                }
            }
            "ENLIGNE" -> {
                if (dailyStep == 2) {
                    // le clic dans le vide a été fait : si l'écran de récompense a disparu, c'est fini
                    dailyTaskDone("Récomp. En Ligne")
                    return
                }
                if (now - dailyStepAt > 25_000L) {
                    log("daily : Récomp. En Ligne abandonnée (25 s sans avancer)")
                    dailyState = "OPEN"
                    dailyTries = 0
                    return
                }
                // le coffre apparaît sur la carte (bulle avec la main) : on le touche
                val mb = farmMatch(screen, "coffre_bulle", 0.75)
                if (mb != null) {
                    val (x, y) = farmCenter("coffre_bulle", mb)
                    farmClick(x, y - (38 * w / 1080.0).toFloat(), "daily : clic sur le coffre")
                    dailyStep = 1
                }
            }
            "AVENTURE" -> detectAventure(rgb, now)
            "PROVISIONS" -> detectProvisions(rgb, now)
            "CONTRIB" -> detectContrib(rgb, now)
            "ORDRES" -> detectOrdres(rgb, now)
            "RECRUTE" -> detectRecrute(rgb, now)
            "EPIQUE" -> detectRecrute(rgb, now, true)
            "TRIOMPHE" -> detectTriomphe(rgb, now)
            "FONTAINE" -> detectFontaine(screen, rgb, now)
            "COMBAT" -> detectCombat(screen, rgb, now)
            "CONQUETE" -> detectConquete(rgb, now)
            "MISSIONS" -> detectMissions(rgb, now)
            "COFFRES" -> detectCoffres(rgb, now)
            "CREUSET" -> detectCreuset(rgb, now)
            "MARCHAND" -> detectMarchand(rgb, now)
            "VIP" -> detectVip(rgb, now)
            "BONUS" -> detectBonus(rgb, now)
            "BANQUET" -> detectBanquet(rgb, now)
            "PET" -> detectPet(rgb, now)
            "SHOP" -> detectBq(rgb, now)
        }
    }


    /**
     * Provisions de voyage : écran du Voyage > « + » du compteur de provisions en haut à droite > fenêtre >
     * « Récupérer » > croix rouge > flèche bleue en haut à gauche.
     */
    private fun detectProvisions(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Provisions de voyage abandonnée (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        // fenêtre des provisions (« Appuie n'importe où pour fermer »)
        // (reconnue par le bouton orange « 1000 gemmes » de la fenêtre, ou par son texte)
        val dlgOpen = colorFrac(rgb, 865.0, 1335.0, Scalar(10.0, 150.0, 180.0), Scalar(25.0, 255.0, 255.0)) >= 0.25 ||
            "fermer" in ocrRef(rgb, 200, 1680, 880, 1750)
        if (dlgOpen) {
            dailyIdleAt = now
            if (colorFrac(rgb, 862.0, 1090.0, Scalar(55.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0)) >= 0.3 && dailyBtn < 4) {
                dailyBtn++
                dailyStep = 2
                clickRef(862.0, 1090.0, "daily : Provisions : clic Récupérer")
            } else {
                dailyStep = 3
                clickRef(1040.0, 870.0, "daily : Provisions : fenêtre fermée par la croix")
            }
            return
        }
        // écran du Voyage (panneaux « Terres perdues » / « Voyage dans le royaume »)
        val signs = ocrRef(rgb, 40, 1000, 330, 1100) + " " + ocrRef(rgb, 780, 1110, 1060, 1200)
        if ("perdue" in signs || "royaume" in signs) {
            dailyIdleAt = now
            if (dailyStep >= 2) {
                lastClick = now
                backArrow()
                dailyTaskDone("Provisions de voyage")
            } else if (now - dailyActAt > 3_500L) {
                dailyActAt = now
                dailyStep = 1
                clickRef(935.0, 145.0, "daily : Provisions : clic sur le + du compteur")
            }
        }
    }


    /**
     * Contrib. d'All. : carte > bouton Alliance (barre du bas) > Tech > nœud « Marché Conclu » > bouton
     * « Contribuer » cliqué tant qu'il est actif (cyan) > bouton grisé = stop > croix > flèche (Technologie)
     * > flèche (Alliance).
     */
    private fun detectContrib(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Contrib. d'All. abandonnée (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        // fenêtre « Marché Conclu » (ligne « Nouvelle tentative de Contribution dans … » sous la fenêtre)
        // (reconnue par son bouton orange « Contribuer 10 gemmes » : la ligne « Nouvelle tentative » n'existe pas à 25/25)
        val orange = Scalar(10.0, 150.0, 180.0)
        val orangeHi = Scalar(25.0, 255.0, 255.0)
        val dlgOpen = colorFrac(rgb, 315.0, 1795.0, orange, orangeHi) >= 0.25 || ocrRef(rgb, 100, 1915, 980, 1975).let { "tentative" in it || "contribution" in it }
        if (dlgOpen) {
            dailyIdleAt = now
            if (dailyStep >= 5) {
                clickRef(960.0, 548.0, "daily : Contrib. d'All. : fenêtre fermée par la croix")
                return
            }
            dailyStep = 4
            val cyan = colorFrac(rgb, 765.0, 1790.0, Scalar(86.0, 90.0, 120.0), Scalar(105.0, 255.0, 255.0))
            if (cyan >= 0.25 && dailyBtn < 30) {
                dailyBtn++
                dailyGrey = 0
                clickRef(765.0, 1790.0, "daily : Contrib. d'All. : contribution $dailyBtn")
                lastClick = now - (cooldown - 300L).coerceAtLeast(0L) // on enchaîne les contributions plus vite
            } else if (++dailyGrey >= 2) {
                dailyStep = 5
                clickRef(960.0, 548.0, "daily : Contrib. d'All. : bouton grisé (stop) après $dailyBtn contribution(s), fermeture")
            }
            return
        }
        val title = ocrRef(rgb, 130, 90, 760, 200)
        if ("nolog" in title) { // Technologie de l'Alliance
            dailyIdleAt = now
            if (dailyStep >= 5) {
                lastClick = now
                backArrow()
                log("daily : Contrib. d'All. : flèche retour (Technologie)")
            } else if (now - dailyActAt > 3_500L) {
                dailyActAt = now
                dailyStep = maxOf(dailyStep, 3)
                clickRef(540.0, 1990.0, "daily : Contrib. d'All. : clic sur Marché Conclu")
            }
            return
        }
        if ("alliance" in title && "territ" !in title) { // écran Alliance
            dailyIdleAt = now
            if (dailyStep >= 5) {
                lastClick = now
                backArrow()
                dailyTaskDone("Contrib. d'All.")
            } else if (now - dailyActAt > 3_500L) {
                dailyActAt = now
                dailyStep = maxOf(dailyStep, 2)
                clickRef(878.0, 1500.0, "daily : Contrib. d'All. : clic Tech")
            }
            return
        }
        // carte / ville : barre du bas visible
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        val seen = listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in bar }
        if (seen >= 2) {
            dailyIdleAt = now
            if (dailyStep >= 5) {
                dailyTaskDone("Contrib. d'All.")
            } else if (dailyStep < 2 && now - dailyActAt > 3_500L) {
                dailyActAt = now
                dailyStep = 1
                clickRef(803.0, 2240.0, "daily : Contrib. d'All. : clic Alliance (barre du bas)")
            }
        }
    }


    /** Point rouge sur l'icône « Ordres » (balance) en bas à droite de la ville. */
    private fun redDotOnOrders(rgb: Mat): Boolean =
        colorFrac(rgb, 1040.0, 1805.0, Scalar(0.0, 150.0, 180.0), Scalar(8.0, 255.0, 255.0), 14) +
            colorFrac(rgb, 1040.0, 1805.0, Scalar(172.0, 150.0, 180.0), Scalar(179.0, 255.0, 255.0), 14) > 0.2

    /** Part de pixels clairs et peu colorés (bandeau « Actif » / « Se recharge » sur un livre). */
    private fun lightFrac(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val sc = rgb.cols() / 1080.0
        val ax = (x0 * sc).toInt()
        val ay = (y0 * sc).toInt()
        val bx = minOf((x1 * sc).toInt(), rgb.cols())
        val by = minOf((y1 * sc).toInt(), rgb.rows())
        if (bx <= ax || by <= ay) return 0.0
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(ax, ay, bx - ax, by - ay)), hsv, Imgproc.COLOR_RGB2HSV)
        val m = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 200.0), Scalar(179.0, 90.0, 255.0), m)
        val f = Core.countNonZero(m).toDouble() / (m.rows() * m.cols())
        hsv.release(); m.release()
        return f
    }

    // livres de l'écran « Ordre du Gouverneur » à activer, dans l'ordre : x, y du livre puis zone du bandeau
    private val ordresBooks = listOf(
        intArrayOf(330, 1680, 200, 1655, 470, 1715), // Journée de la Productivité
        intArrayOf(740, 790, 610, 760, 880, 820), // Travail Urgent
        intArrayOf(740, 1680, 610, 1655, 880, 1715) // Festivités (en dernier, seulement si les deux autres sont actifs)
    )
    private var ordresChecked = false // les deux livres ont-ils été vus disponibles en même temps ?
    private var ordresFest = false // Festivités autorisée (les deux autres sont « Actif »)
    private var ordresFestAt = 0L

    /** Le livre n°i porte-t-il un bandeau (« Actif » ou « Se recharge » : il vient d'être utilisé ou l'a été) ? */
    private fun ordresActive(rgb: Mat, i: Int): Boolean {
        val b = ordresBooks[i]
        return lightFrac(rgb, b[2], b[3], b[4], b[5]) >= 0.3
    }

    /**
     * Ordres : icône (balance) > « Ordre du Gouverneur » > on vérifie d'abord que les DEUX livres (Productivité et
     * Travail Urgent) sont disponibles en même temps (ni « Actif » ni « Se recharge ») ; si l'un ne l'est pas, on
     * n'active rien et on revient à la ville. Sinon, pour chaque livre : livre > « Aventure » > (récompenses) ;
     * puis flèche bleue pour revenir à la ville.
     */
    private fun detectOrdres(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Ordres abandonnés (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        // écran « Récompenses » : appuyer n'importe où
        val reward = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in reward || "quitter" in reward) {
            dailyIdleAt = now
            tapRewards("daily : Ordres : récompenses : appui n'importe où")
            return
        }
        // page d'un décret (« Effet du Décret ») : bouton Aventure
        val eff = ocrRef(rgb, 200, 1225, 830, 1275, true)
        if ("effet" in eff || "cret" in eff) {
            dailyIdleAt = now
            if (dailyBtn == 0) {
                dailyBtn = 1
                dailyStep++
                clickRef(540.0, 1635.0, "daily : Ordres : clic Aventure")
            } else {
                dailyBtn = 0
                lastClick = now
                backArrow()
                log("daily : Ordres : retour à la liste des ordres")
            }
            return
        }
        // liste « Ordre du Gouverneur »
        val title = ocrRef(rgb, 300, 430, 780, 490)
        if ("gouverneur" in title) {
            dailyIdleAt = now
            dailyBtn = 0
            if (dailyStep >= ordresBooks.size) {
                lastClick = now
                backArrow()
                log("daily : Ordres : tous les ordres vus, retour à la ville")
                return
            }
            if (!ordresChecked) {
                val f0 = lightFrac(rgb, ordresBooks[0][2], ordresBooks[0][3], ordresBooks[0][4], ordresBooks[0][5])
                val f1 = lightFrac(rgb, ordresBooks[1][2], ordresBooks[1][3], ordresBooks[1][4], ordresBooks[1][5])
                if (f0 >= 0.3 || f1 >= 0.3) {
                    dailyAttempts["ordres"] = 2
                    if (f0 >= 0.3 && f1 >= 0.3 && ordresActive(rgb, 0) && ordresActive(rgb, 1)) {
                        // les deux sont déjà actifs : on finit par Festivités
                        ordresChecked = true
                        ordresFest = true
                        dailyStep = 2
                        log("daily : Ordres : Productivité et Travail Urgent déjà utilisés, on passe à Festivités")
                        return
                    }
                    dailyStep = ordresBooks.size
                    lastClick = now
                    backArrow()
                    log("daily : Ordres : les deux ne sont pas disponibles en même temps (Productivité ${if (f0 >= 0.3) "indisponible" else "dispo"}, Travail Urgent ${if (f1 >= 0.3) "indisponible" else "dispo"}), rien activé")
                    return
                }
                ordresChecked = true
            }
            // Festivités en dernier : seulement si Productivité et Travail Urgent sont tous les deux « Actif »
            if (dailyStep == 2 && !ordresFest) {
                if (ordresFestAt == 0L) { ordresFestAt = now; return }
                if (now - ordresFestAt < 900L) return
                if (ordresActive(rgb, 0) && ordresActive(rgb, 1)) {
                    ordresFest = true
                    log("daily : Ordres : les deux sont utilisés (Actif / Se recharge), Festivités")
                } else {
                    log("daily : Ordres : les deux ne sont pas utilisés (Productivité ${ordresActive(rgb, 0)}, Travail Urgent ${ordresActive(rgb, 1)}), pas de Festivités")
                    dailyStep = ordresBooks.size
                    return
                }
            }
            val b = ordresBooks[dailyStep]
            if (lightFrac(rgb, b[2], b[3], b[4], b[5]) >= 0.3) {
                log("daily : Ordres : livre ${dailyStep + 1} déjà actif ou en recharge, on passe")
                dailyStep++
            } else if (now - dailyActAt > 3_000L) {
                dailyActAt = now
                clickRef(b[0].toDouble(), b[1].toDouble(), "daily : Ordres : ouverture du livre ${dailyStep + 1}")
            }
            return
        }
        // ville
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        val seen = listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in bar }
        if (seen >= 2) {
            dailyIdleAt = now
            if (dailyStep >= ordresBooks.size) {
                dailyTaskDone("Ordres")
            } else if (now - dailyActAt > 3_500L) {
                dailyActAt = now
                clickRef(995.0, 1855.0, "daily : Ordres : ouverture des ordres")
            }
        }
    }


    /**
     * Recruter des Héros : Avancé : écran du recrutement > « Recruter une fois Gratuit » (bouton vert) > parfois
     * une animation (on touche en haut de l'écran) > écran « Récompenses » : flèche bleue > si un recrutement
     * gratuit reste (bouton vert), on recommence, sinon flèche bleue pour revenir à la ville.
     */
    private fun detectRecrute(rgb: Mat, now: Long, epic: Boolean = false) {
        val nom = if (epic) "épique" else "avancé"
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Recrutement $nom abandonné (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        // écran « Récompenses » du recrutement : flèche bleue en haut à gauche
        val reward = ocrRef(rgb, 200, 1950, 880, 2250)
        if ("appuie" in reward || "quitter" in reward) {
            dailyIdleAt = now
            dailyStep = 2
            lastClick = now
            backArrow()
            log("daily : Recrutement : récompenses vues, flèche retour")
            return
        }
        // écran du recrutement (ligne « Prochain gratuit » de l'Épique, toujours présente)
        val recruit = ocrRef(rgb, 40, 2110, 560, 2165, true)
        // repère de secours sans OCR : les 3 gros boutons orange (Recruter 10 fois ×2, Épique ×1) ou le bouton vert
        val orangeLo = Scalar(10.0, 150.0, 180.0)
        val orangeHi = Scalar(25.0, 255.0, 255.0)
        val orangeBtns = listOf(Rect(590, 1640, 410, 95), Rect(70, 2180, 430, 110), Rect(590, 2180, 410, 110))
            .count { colorRect(rgb, it.x, it.y, it.x + it.width, it.y + it.height, orangeLo, orangeHi) >= 0.4 }
        val greenLo = Scalar(50.0, 90.0, 100.0)
        val greenHi = Scalar(90.0, 255.0, 255.0)
        // bouton vert « Recruter une fois Gratuit » : on mesure tout le bouton (le texte blanc au centre fausse un petit point)
        val greenAdv = colorRect(rgb, 90, 1640, 490, 1735, greenLo, greenHi)
        val greenEpic = colorRect(rgb, 90, 2185, 490, 2290, greenLo, greenHi)
        if ("prochain" in recruit || "gratuit" in recruit || orangeBtns >= 2 || greenAdv >= 0.3 || greenEpic >= 0.3) {
            dailyIdleAt = now
            val green = if (epic) greenEpic else greenAdv
            if (dailyStep == 0 && green >= 0.3 && dailyBtn < 1) {
                dailyBtn++
                dailyStep = 1
                clickRef(287.0, if (epic) 2235.0 else 1690.0, "daily : Recrutement $nom : clic Recruter une fois (gratuit) n°$dailyBtn")
            } else if (dailyStep == 0 && dailyBtn < 1 && (dailyActAt == 0L || now - dailyActAt < 2_500L)) {
                // bouton pas (encore) vert : l'écran finit de s'afficher, on le relit pendant 2,5 s avant de conclure
                if (dailyActAt == 0L) dailyActAt = now
            } else {
                dailyStep = 3
                lastClick = now
                backArrow()
                log("daily : Recrutement : pas de recrutement gratuit à faire (vert ${"%.2f".format(green)}), retour")
            }
            return
        }
        // ville : terminé
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        val seen = listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in bar }
        if (seen >= 2) {
            dailyIdleAt = now
            if (dailyStep >= 1) dailyTaskDone("Recruter des Héros : ${if (epic) "Épique" else "Avancé"}")
            return
        }
        // autre écran après un recrutement : animation du héros, on touche en haut de l'écran
        if (dailyStep >= 1 && dailyTries < 12) {
            dailyTries++
            dailyIdleAt = now
            clickRef(700.0, 330.0, "daily : Recrutement : animation, clic en haut de l'écran") // pas à gauche : le bouton ▶ y est
        }
    }


    /**
     * Triomphe d'activité quotidienne : écran « Triomphe d'Activité » > coffre individuel (point rouge) > écran
     * « Récompenses » (appui n'importe où) > flèche bleue en haut à gauche.
     */
    private fun detectTriomphe(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Triomphe d'activité abandonné (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        val reward = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in reward || "quitter" in reward) {
            dailyIdleAt = now
            dailyStep = 2
            tapRewards("daily : Triomphe : récompenses : appui n'importe où")
            return
        }
        val title = ocrRef(rgb, 140, 90, 760, 200)
        if ("activit" in title) {
            dailyIdleAt = now
            if (now - dailyActAt <= 3_000L) return // on laisse le temps à l'écran « Récompenses » d'apparaître
            val line = ocrRef(rgb, 100, 1360, 980, 1430)
            // coffre hebdomadaire du haut : « Récompenses récupérables » (au lieu de « Récomp. Actuelles »)
            val weekly = ("compens" in line || "cuper" in line) && "actuel" !in line
            val dot = colorFrac(rgb, 1040.0, 1618.0, Scalar(0.0, 150.0, 180.0), Scalar(8.0, 255.0, 255.0), 14) +
                colorFrac(rgb, 1040.0, 1618.0, Scalar(172.0, 150.0, 180.0), Scalar(179.0, 255.0, 255.0), 14)
            if (weekly && dailyBtn < 1) {
                dailyBtn++
                dailyActAt = now
                clickRef(540.0, 950.0, "daily : Triomphe : coffre du haut (récompenses récupérables)")
            } else if (dot > 0.2 && dailyGrey < 1) {
                dailyGrey++
                dailyActAt = now
                clickRef(180.0, 1730.0, "daily : Triomphe : clic sur le coffre individuel")
            } else {
                lastClick = now
                backArrow()
                dailyTaskDone("Triomphe d'activité quotidienne")
            }
        }
    }


    /**
     * Écrans de « Conquête » : récompenses (bandeau jaune), fenêtre « Revenu d'Inactivité » (Récupérer), écran Conquête
     * (Récupérer vert, ou flèche bleue s'il est grisé). Renvoie true si l'écran courant en fait partie.
     */
    private fun conqueteScreens(rgb: Mat, now: Long): Boolean {
        val green = Scalar(55.0, 100.0, 120.0)
        val greenHi = Scalar(85.0, 255.0, 255.0)
        val bottom = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in bottom || "quitter" in bottom) {
            dailyIdleAt = now
            tapRewards("daily : Conquête : récompenses (bandeau jaune)")
            return true
        }
        if (colorFrac(rgb, 540.0, 1635.0, green, greenHi) >= 0.25) {
            dailyIdleAt = now
            dailyStep = maxOf(dailyStep, 10)
            clickRef(540.0, 1635.0, "daily : Conquête : Revenu d'Inactivité : Récupérer")
            return true
        }
        if ("conqu" in bottom || "conqu" in ocrRef(rgb, 130, 90, 760, 200)) {
            dailyIdleAt = now
            dailyStep = maxOf(dailyStep, 10)
            if (dailyStep < 20 && colorRect(rgb, 825, 1768, 1030, 1838, green, greenHi) >= 0.08 && dailyBtn < 4) {
                dailyBtn++
                clickRef(925.0, 1800.0, "daily : Conquête : Récupérer")
            } else {
                dailyStep = 20
                lastClick = now
                backArrow()
                log("daily : Conquête : Récupérer grisé, flèche retour")
            }
            return true
        }
        return false
    }

    /** Tâche « Conquérir : Revenu d'inactivité » depuis la liste : même parcours que dans la collecte d'eau. */
    private fun detectConquete(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Conquête abandonnée (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        if (conqueteScreens(rgb, now)) return
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        if (listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in bar } >= 2) {
            dailyIdleAt = now
            if (dailyStep >= 20) dailyTaskDone("Conquérir : Revenu d'inactivité")
        }
    }

    /**
     * Fontaine Vitale : sur « Mon île », on touche chaque goutte d'eau (la plus basse d'abord) ; puis « Conquête » en bas
     * à gauche > « Récupérer » (bouton vert, s'il est actif) > fenêtre « Revenu d'Inactivité » > « Récupérer » > bandeau
     * « Récompenses » > bouton grisé : flèche bleue pour sortir (de Conquête, puis de l'île).
     * dailyStep : 1 = gouttes, 10 = Conquête ouverte, 20 = on sort.
     */
    private fun detectFontaine(screen: Mat, rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Fontaine Vitale abandonnée (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        if (dailyStep >= 20 && fontPhase > 0 && fontProsperite(rgb, now)) return
        if (conqueteScreens(rgb, now)) return
        // île « Mon île » : gouttes, puis Conquête, ou sortie
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        val labels = listOf("conqu", "héros", "heros", "magasin", "alliance").count { it in bar }
        val drops = findAllCenters(screen, "goutte", 0.75)
        val drop = drops.maxByOrNull { it.second }
        if (drop != null) dropSeenAt = now
        val title = ocrRef(rgb, 130, 90, 500, 200)
        val island = drop != null || (labels >= 2 && "ville" in bar && "monde" !in bar) || "mon" in title && "le" in title
        if (island) {
            dailyIdleAt = now
            if (dailyStep >= 20 && fontPhase == 0 && redDotAt(rgb, 1044.0, 203.0)) {
                // retour de Conquête : point rouge sur le trophée (Île de la Prospérité) : on l'ouvre
                fontPhase = 1
                fontPhaseAt = now
                fontWaitUntil = now + 1200
                clickRef(1005.0, 240.0, "daily : Fontaine Vitale : point rouge sur le trophée, ouverture de l'Île de la Prospérité")
            } else if (dailyStep >= 20) { // retour de Conquête (ou des îles amies) : on sort aussi de l'île
                lastClick = now
                backArrow()
                dailyTaskDone("Fontaine Vitale")
            } else if (drop != null && dailyBtn < 12 && dailyStep < 10) {
                dailyBtn++
                dailyGrey = 0
                dailyStep = 1
                farmClick(drop.first, drop.second, "daily : Fontaine Vitale : clic sur une goutte d'eau ($dailyBtn sur ${drops.size} vue(s))")
            } else if (dailyStep < 10 && now - dropSeenAt > 2_000L && (dailyStep >= 1 || now - dailyStepAt > 5_000L)) {
                // plus de goutte depuis 2 s : on touche l'icône des épées croisées (Conquête), en bas à gauche
                dailyStep = 10
                dailyBtn = 0
                clickRef(108.0, 2245.0, "daily : Fontaine Vitale : plus de goutte, clic sur Conquête (épées croisées)")
            }
            return
        }
        // île non reconnue : si les gouttes sont finies, on touche quand même Conquête (barre du bas lisible)
        if (dailyStep in 1..9 && now - dropSeenAt > 3_000L && "conqu" in bar) {
            dailyStep = 10
            dailyBtn = 0
            clickRef(108.0, 2245.0, "daily : Fontaine Vitale : île non reconnue, clic sur Conquête (épées croisées)")
            return
        }
        if (labels >= 2 && "monde" in bar && dailyStep >= 1) dailyTaskDone("Fontaine Vitale")
        else if (now - dailyStepAt > 6_000L && now - lastFontLog > 3_000L) {
            lastFontLog = now
            log("daily : Fontaine Vitale : écran non reconnu (barre lue « ${bar.replace('\n', ' ').take(60)} »)")
        }
    }

    private var dropSeenAt = 0L
    private var lastFontLog = 0L

    // Île de la Prospérité (trophée de « Mon île ») : 0 = pas commencé, 1 = liste attendue, 2 = île d'ami attendue, 3 = sur une île d'ami
    private var fontPhase = 0
    private var fontVisits = 0
    private var fontSwipes = 0
    private var fontArrivedAt = 0L
    private var fontBubbleSeenAt = 0L
    private var fontTaps = 0
    private var fontListClicks = 0
    private var fontExit = false
    private var fontWaitUntil = 0L
    private var fontPhaseAt = 0L

    private fun resetFontaine() {
        fontPhase = 0; fontVisits = 0; fontSwipes = 0; fontTaps = 0; fontListClicks = 0; fontExit = false; fontWaitUntil = 0L; fontPhaseAt = 0L
    }

    /** Ordonnées (référence 1080) des points rouges de la colonne des symboles, dans la liste de l'Île de la Prospérité, de haut en bas. */
    private fun listRedDots(rgb: Mat): List<Double> {
        val sc = rgb.cols() / 1080.0
        val x0 = (975 * sc).toInt()
        val y0 = (445 * sc).toInt()
        val x1 = minOf((1030 * sc).toInt(), rgb.cols())
        val y1 = minOf((2095 * sc).toInt(), rgb.rows())
        if (x1 <= x0 || y1 <= y0) return emptyList()
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(x0, y0, x1 - x0, y1 - y0)), hsv, Imgproc.COLOR_RGB2HSV)
        val m1 = Mat()
        val m2 = Mat()
        Core.inRange(hsv, Scalar(0.0, 150.0, 150.0), Scalar(8.0, 255.0, 255.0), m1)
        Core.inRange(hsv, Scalar(172.0, 150.0, 150.0), Scalar(179.0, 255.0, 255.0), m2)
        Core.bitwise_or(m1, m2, m1)
        val cs = Mat()
        Core.reduce(m1, cs, 1, Core.REDUCE_SUM, CvType.CV_32S)
        val arr = IntArray(m1.rows())
        cs.get(0, 0, arr)
        hsv.release(); m1.release(); m2.release(); cs.release()
        val out = ArrayList<Double>()
        var start = -1
        var last = -1
        fun close() {
            if (start >= 0 && (last - start + 1) / sc >= 10.0) out.add((y0 + (start + last) / 2.0) / sc)
            start = -1
        }
        for (i in arr.indices) {
            if (arr[i] >= 255 * 3) {
                if (start < 0) start = i
                last = i
            } else if (start >= 0 && i - last > 2) close()
        }
        close()
        return out
    }

    /**
     * Après Conquête, si « Mon île » montre un point rouge sur le trophée : liste « Île de la Prospérité » (premier point rouge
     * de haut en bas, sinon on fait défiler de 6 lignes) > île d'un ami (bulle d'eau, puis trophée s'il a encore son point
     * rouge, sinon flèche bleue) ; trois îles amies en tout. Renvoie true tant que l'écran courant fait partie de ce parcours.
     */
    private fun fontProsperite(rgb: Mat, now: Long): Boolean {
        if (now < fontWaitUntil) return true // on laisse l'écran suivant s'afficher
        val title = ocrRef(rgb, 130, 90, 760, 200)
        var list = "prosp" in title
        var friend = false
        if (!list && "le de" in title) {
            val tabs = ocrRef(rgb, 0, 215, 1080, 320)
            if ("royaume" in tabs || "alliance" in tabs) list = true else friend = true
        }
        if (!list && !friend && "royaume" in ocrRef(rgb, 0, 215, 1080, 320)) list = true
        if (list) {
            dailyIdleAt = now
            if (fontPhase >= 2) fontPhase = 1
            if (fontVisits >= 6 || fontListClicks >= 10) {
                fontExit = true
                lastClick = now
                backArrow()
                fontWaitUntil = now + 1000
                log("daily : Fontaine Vitale : trop de visites, sortie de la liste")
                return true
            }
            val ys = listRedDots(rgb)
            if (ys.isNotEmpty()) {
                fontListClicks++
                fontSwipes = 0
                fontPhase = 2
                fontPhaseAt = now
                fontWaitUntil = now + 1500
                clickRef(968.0, ys[0] + 33.0, "daily : Fontaine Vitale : liste, premier point rouge (clic sur le symbole, n° $fontListClicks)")
            } else if (fontSwipes < 6) {
                fontSwipes++
                fontWaitUntil = now + 1500
                val sc = w / 1080.0
                ClickAccessibilityService.instance?.swipe((540 * sc).toFloat(), (1850 * sc).toFloat(), (540 * sc).toFloat(), (650 * sc).toFloat(), 1100)
                lastClick = now + 800
                log("daily : Fontaine Vitale : pas de point rouge visible, la liste défile ($fontSwipes)")
            } else {
                fontExit = true
                lastClick = now
                backArrow()
                fontWaitUntil = now + 1000
                log("daily : Fontaine Vitale : plus aucun point rouge dans la liste, flèche retour")
            }
            return true
        }
        if (friend) {
            dailyIdleAt = now
            if (fontPhase == 2) {
                fontPhase = 3
                fontVisits++
                fontTaps = 0
                fontArrivedAt = now
                fontBubbleSeenAt = 0L
                fontWaitUntil = now + 1500
                log("daily : Fontaine Vitale : île d'ami n° $fontVisits")
            }
            if (fontExit) { // on a quitté la liste : retour sur « Mon île »
                lastClick = now
                backArrow()
                fontWaitUntil = now + 1000
                return true
            }
            val bubble = colorRect(rgb, 490, 500, 600, 610, Scalar(0.0, 0.0, 225.0), Scalar(179.0, 40.0, 255.0)) >= 0.25
            if (bubble && fontTaps == 0 && fontBubbleSeenAt == 0L) {
                // bulle repérée : on lui laisse un instant pour s'afficher complètement avant de toucher
                fontBubbleSeenAt = now
                fontWaitUntil = now + 600
                return true
            }
            if (!bubble && fontTaps == 0 && now - fontArrivedAt < 5_000L) return true // l'île finit de s'afficher : on attend la bulle
            if (bubble && fontTaps < 3) {
                fontTaps++
                fontWaitUntil = now + 1800
                clickRef(544.0, 559.0, "daily : Fontaine Vitale : île d'ami n° $fontVisits, clic sur la bulle d'eau ($fontTaps)")
                return true
            }
            if (redDotAt(rgb, 1044.0, 203.0) && fontVisits < 6) {
                fontPhase = 1
                fontPhaseAt = now
                fontWaitUntil = now + 1200
                clickRef(1005.0, 240.0, "daily : Fontaine Vitale : point rouge sur le trophée, retour à la liste")
            } else {
                lastClick = now
                backArrow()
                fontWaitUntil = now + 1000
                log("daily : Fontaine Vitale : plus de point rouge sur le trophée, flèche retour vers Mon île")
            }
            return true
        }
        // fenêtre de récompenses éventuelle (bandeau « appuie n'importe où »)
        val reward = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in reward || "quitter" in reward) {
            dailyIdleAt = now
            tapRewards("daily : Fontaine Vitale : récompenses : appui n'importe où")
            return true
        }
        // retour sur « Mon île » : le parcours est fini, la suite se fait dans l'écran de l'île
        if ("mon" in title && "le" in title && (fontPhase == 3 || fontExit || now - fontPhaseAt > 5_000L)) return false
        return true // écran en transition : on attend (10 s sans rien reconnu : abandon)
    }

    /**
     * Combat (Assaut Rebelle) : icône du golem en haut à gauche > fenêtre « Défi Rapide » > écran « Victoire ! »
     * > « Réclamer les Récompenses ». Vérifié avant chaque tentative, comme les Ordres.
     */
    private fun detectCombat(screen: Mat, rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Combat abandonné (10 s sans écran reconnu)")
            lastClick = now
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        // écran « Victoire ! » : bouton Réclamer les Récompenses
        val vic = ocrRef(rgb, 400, 920, 680, 975, true)
        if ("compenses" in vic) {
            dailyIdleAt = now
            dailyStep = 3
            clickRef(540.0, 1620.0, "daily : Combat : Réclamer les Récompenses")
            return
        }
        // fenêtre « Assaut Rebelle » : Défi Rapide
        val defi = ocrRef(rgb, 560, 1640, 950, 1750)
        if ("rapide" in defi) {
            dailyIdleAt = now
            if (dailyBtn < 3) {
                dailyBtn++
                dailyStep = 2
                clickRef(755.0, 1695.0, "daily : Combat : Défi Rapide")
            }
            return
        }
        val reward = ocrRef(rgb, 200, 2030, 880, 2100)
        if (dailyStep >= 3 && ("appuie" in reward || "quitter" in reward)) {
            dailyIdleAt = now
            tapRewards("daily : Combat : appui n'importe où")
            return
        }
        // ville : terminé, ou on rouvre l'icône si la fenêtre n'est pas apparue
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        val seen = listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in bar }
        if (seen >= 2) {
            dailyIdleAt = now
            if (dailyStep >= 3) {
                dailyTaskDone("Combat")
            } else if (dailyStep <= 1 && now - dailyActAt > 3_500L) {
                val golem = golemPoint(screen)
                if (golem != null) {
                    dailyActAt = now
                    farmClick(golem.first, golem.second, "daily : Combat : nouveau clic sur l'icône")
                }
            }
        }
    }

    /**
     * Icône « Assaut Rebelle » (golem rouge en haut à gauche). Le premier gabarit contenait un bout de décor derrière la carte
     * (score 0,76 seulement quand le décor change) : le second est coupé dans la carte même (marteau, tête, yeux), sans le
     * minuteur ni le décor. Renvoie le point à toucher (centre de la carte), ou null.
     */
    private fun golemPoint(screen: Mat): Pair<Float, Float>? {
        farmMatch(screen, "golem2", 0.8)?.let { return farmCenter("golem2", it) }
        farmMatch(screen, "golem", 0.8)?.let {
            val (gx, gy) = farmCenter("golem", it)
            return Pair(gx, gy + (60 * w / 1080.0).toFloat())
        }
        return null
    }

    /** Centres (pleine résolution) de toutes les correspondances d'une image, au-dessus du seuil. */
    private fun findAllCenters(screen: Mat, name: String, thr: Double): List<Pair<Float, Float>> {
        val t = farm[name] ?: return emptyList()
        if (t.cols() > screen.cols() || t.rows() > screen.rows()) return emptyList()
        val res = Mat()
        Imgproc.matchTemplate(screen, t, res, Imgproc.TM_CCOEFF_NORMED)
        val out = ArrayList<Pair<Float, Float>>()
        repeat(6) {
            val mm = Core.minMaxLoc(res)
            if (mm.maxVal < thr) return@repeat
            out.add(
                Pair(
                    ((mm.maxLoc.x + t.cols() / 2.0) / SCALE).toFloat(),
                    ((mm.maxLoc.y + t.rows() / 2.0) / SCALE).toFloat()
                )
            )
            // on efface le voisinage pour trouver la correspondance suivante
            Imgproc.rectangle(
                res,
                org.opencv.core.Point(mm.maxLoc.x - t.cols(), mm.maxLoc.y - t.rows()),
                org.opencv.core.Point(mm.maxLoc.x + t.cols(), mm.maxLoc.y + t.rows()),
                Scalar(-1.0), -1
            )
        }
        res.release()
        return out
    }


    /** Rond rouge avec un chiffre sur l'icône des missions (parchemin, en bas à gauche de la ville). */
    private fun redBadgeOnMissions(rgb: Mat): Boolean {
        val a = colorRect(rgb, 85, 1950, 111, 1976, Scalar(0.0, 150.0, 150.0), Scalar(8.0, 255.0, 255.0))
        val b = colorRect(rgb, 85, 1950, 111, 1976, Scalar(172.0, 150.0, 150.0), Scalar(179.0, 255.0, 255.0))
        return a + b > 0.25
    }

    /**
     * Missions quotidiennes (préambule) : icône du parchemin > fenêtre « Quotidien » > « Récupérer Tout » (vert) >
     * écrans « Récompenses » (appui n'importe où, parfois deux) > croix en haut à droite de la fenêtre.
     */
    private fun detectMissions(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Missions quotidiennes abandonnées (10 s sans écran reconnu)")
            lastClick = now
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        val bottom = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in bottom || "quitter" in bottom) {
            dailyIdleAt = now
            dailyStep = maxOf(dailyStep, 1)
            tapRewards("daily : Missions quotidiennes : récompenses : appui n'importe où")
            return
        }
        val tabs = ocrRef(rgb, 180, 1920, 900, 1990, true)
        if ("otidien" in tabs || "quotid" in tabs || "expans" in tabs) {
            dailyIdleAt = now
            val green = colorRect(rgb, 360, 1745, 725, 1845, Scalar(55.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0))
            if (green >= 0.2 && dailyBtn < 3) {
                dailyBtn++
                dailyStep = 1
                clickRef(540.0, 1795.0, "daily : Missions quotidiennes : Récupérer Tout")
            } else {
                dailyStep = 2
                clickRef(1033.0, 440.0, "daily : Missions quotidiennes : fermeture par la croix")
            }
            return
        }
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        if (listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in bar } >= 2) {
            dailyIdleAt = now
            if (dailyStep >= 2) dailyTaskDone("Missions quotidiennes")
            else if (dailyStep == 0 && now - dailyActAt > 3_500L) {
                dailyActAt = now
                clickRef(60.0, 2000.0, "daily : Missions quotidiennes : nouvelle ouverture")
            }
        }
    }


    /** Chiffre rouge sur le bouton « Alliance » de la barre du bas (ville). */
    private fun redBadgeOnAlliance(rgb: Mat): Boolean {
        val a = colorRect(rgb, 820, 2182, 866, 2222, Scalar(0.0, 150.0, 150.0), Scalar(8.0, 255.0, 255.0))
        val b = colorRect(rgb, 820, 2182, 866, 2222, Scalar(172.0, 150.0, 150.0), Scalar(179.0, 255.0, 255.0))
        return a + b > 0.12
    }

    /**
     * Coffres d'alliance (préambule) : Alliance > Caisses > onglet « Cadeau d'Alliance » (Récupérer Tout, récompenses, coffre
     * du haut seulement s'il a une bulle rouge) > onglet « Caisse de Butin » (Récupérer Tout, récompenses) > croix > ville.
     * Si le coffre du haut a été touché sans bulle (fenêtre « Caisse d'Honneur ») : clic dans le vide en haut pour revenir.
     */
    private fun detectCoffres(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Coffres d'alliance abandonnés (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        val green = Scalar(55.0, 100.0, 120.0)
        val greenHi = Scalar(85.0, 255.0, 255.0)
        // écran « Récompenses »
        val bottom = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in bottom || "quitter" in bottom) {
            dailyIdleAt = now
            tapRewards("daily : Coffres : récompenses : appui n'importe où")
            return
        }
        // fenêtre « Récompense de Caisse d'Honneur » (coffre touché sans bulle rouge) : clic dans le vide en haut
        if ("honneur" in ocrRef(rgb, 190, 600, 890, 670)) {
            dailyIdleAt = now
            clickRef(575.0, 330.0, "daily : Coffres : fenêtre Caisse d'Honneur, demi-tour par clic en haut")
            return
        }
        val title = ocrRef(rgb, 130, 90, 500, 200)
        if ("caisse" in title) {
            dailyIdleAt = now
            val vl = meanV(rgb, 60, 640, 520, 730)
            val vr = meanV(rgb, 560, 640, 1030, 730)
            val cadeauSel = vr > vl + 12
            if (dailyStep >= 5) { // on a tout fait : croix
                clickRef(1023.0, 142.0, "daily : Coffres : fermeture par la croix")
                return
            }
            if (cadeauSel) {
                if (dailyStep < 3 && colorRect(rgb, 790, 2150, 1020, 2260, green, greenHi) >= 0.2 && dailyBtn < 3) {
                    dailyBtn++
                    clickRef(900.0, 2200.0, "daily : Coffres : Cadeau d'Alliance : Récupérer Tout")
                } else if (dailyStep < 3 && dailyGrey < 1 && redDotAt(rgb, 680.0, 320.0)) {
                    dailyGrey++ // un seul essai : la bulle rouge dit qu'il y a un coffre à ouvrir
                    clickRef(540.0, 400.0, "daily : Coffres : coffre du haut (bulle rouge)")
                } else if (dailyStep < 3 || dailyStep == 3) {
                    dailyStep = 3
                    dailyBtn = 0
                    clickRef(285.0, 680.0, "daily : Coffres : onglet Caisse de Butin")
                }
            } else {
                dailyStep = maxOf(dailyStep, 3)
                if (colorRect(rgb, 330, 2180, 750, 2290, green, greenHi) >= 0.2 && dailyBtn < 3) {
                    dailyBtn++
                    clickRef(540.0, 2235.0, "daily : Coffres : Caisse de Butin : Récupérer Tout")
                } else {
                    dailyStep = 5
                    clickRef(1023.0, 142.0, "daily : Coffres : Récupérer Tout grisé, fermeture par la croix")
                }
            }
            return
        }
        // écran Alliance
        if ("alliance" in title) {
            dailyIdleAt = now
            if (dailyStep >= 5) {
                lastClick = now
                backArrow()
                coffresDone()
            } else if (now - dailyActAt > 3_000L) {
                dailyActAt = now
                clickRef(790.0, 1100.0, "daily : Coffres : clic Caisses")
            }
            return
        }
        // ville
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        if (listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in bar } >= 2) {
            dailyIdleAt = now
            if (dailyStep >= 5) coffresDone()
            else if (dailyStep == 0 && now - dailyActAt > 3_500L) {
                dailyActAt = now
                clickRef(803.0, 2240.0, "daily : Coffres : nouveau clic Alliance")
            }
        }
    }

    /** Coffres terminés : une seule visite par passage (le chiffre rouge d'Alliance peut rester pour d'autres raisons). */
    private fun coffresDone() {
        dailyAttempts["coffres"] = 2
        dailyTaskDone("Coffres d'alliance")
    }

    /** Pastille rouge autour d'un point de référence. */
    private fun redDotAt(rgb: Mat, x: Double, y: Double): Boolean =
        colorFrac(rgb, x, y, Scalar(0.0, 150.0, 150.0), Scalar(8.0, 255.0, 255.0), 16) +
            colorFrac(rgb, x, y, Scalar(172.0, 150.0, 150.0), Scalar(179.0, 255.0, 255.0), 16) > 0.2


    /**
     * Creuset en or véritable : écran du creuset > « Raffiner » touché 6 fois (aucun écran entre deux touches), ou moins si le
     * bouton devient gris (« Restant du jour : 0 ») > flèche bleue en haut à gauche.
     */
    private fun detectCreuset(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Creuset abandonné (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        val title = ocrRef(rgb, 140, 100, 700, 190)
        if ("creuset" in title || "ritable" in title) {
            dailyIdleAt = now
            val cyan = colorRect(rgb, 327, 1940, 752, 2055, Scalar(86.0, 90.0, 120.0), Scalar(105.0, 255.0, 255.0))
            if (cyan >= 0.3 && dailyBtn < 6) {
                dailyBtn++
                dailyStep = 1
                clickRef(540.0, 1995.0, "daily : Creuset : Raffiner ($dailyBtn/6)")
            } else if (dailyBtn >= 6 || ++dailyGrey >= 2) {
                lastClick = now
                backArrow()
                log("daily : Creuset : fin (touches $dailyBtn, bouton ${if (cyan >= 0.3) "cyan" else "gris"}), flèche retour")
                dailyStep = 5
                dailyTaskDone("Creuset en or véritable")
            }
        }
    }


    // ---------- Magasin nomade ----------

    private val shopCols = intArrayOf(45, 392, 740)       // bord gauche des cartes (référence 1080 px)
    private val shopRows = intArrayOf(1035, 1468)         // haut de la bande du prix
    private val shopCx = intArrayOf(193, 540, 887)        // centre de la bande du prix (toucher le prix ouvre « Acheter »)
    private val shopCy = intArrayOf(1070, 1503)
    private var shopSig = ""
    private var shopWaitUntil = 0L
    private var shopBuys = 0
    private var shopOpened = -1
    private var shopExpect: Pair<Char, Int>? = null
    private var shopFail = 0
    private var shopOpenTries = 0
    private var shopLastSlot = -1
    private var shopDlgW = 0.0
    private var shopCheckDone = false
    private val shopVerify = HashSet<Int>()
    private var shopDlgFirst = 0L
    private var shopDlgRetry = 0
    private var shopDlgOcr = ""
    private var shopLastSig = ""
    private var shopSameCnt = 0
    private var shopUnknownAt = 0L
    private var shopSkipSig = Array(6) { "" }
    private var shopReads: List<Pair<Char, Int>> = emptyList()

    private fun resetShop() {
        shopSig = ""; shopWaitUntil = 0L; shopBuys = 0; shopOpened = -1; shopUnknownAt = 0L
        shopExpect = null; shopFail = 0; shopOpenTries = 0; shopLastSlot = -1; shopLastSig = ""; shopSameCnt = 0
        shopCheckDone = false; shopVerify.clear(); shopDlgFirst = 0L; shopDlgRetry = 0
        shopSkipSig = Array(6) { "" }
        shopReads = emptyList()
    }

    private fun digitsOf(s: String): Int {
        val d = s.filter { it.isDigit() }
        return if (d.isEmpty() || d.length > 7) -1 else d.toInt()
    }

    /**
     * Prix d'une case du magasin : ('g', valeur) = en cristaux bleus (valeur -1 si illisible), ('r', 0) = en ressources
     * (pain, bois, pierre, fer : il y a un nombre mais pas de cristal bleu), ('?', -1) = case vide / en transition.
     */
    private fun shopRead(rgb: Mat, slot: Int): Pair<Char, Int> {
        val x0 = shopCols[slot % 3]
        val y0 = shopRows[slot / 3]
        val sc = rgb.cols() / 1080.0
        val ax = (x0 * sc).toInt()
        val ay = (y0 * sc).toInt()
        val bw = (295 * sc).toInt()
        val bh = (75 * sc).toInt()
        if (ax + bw > rgb.cols() || ay + bh > rgb.rows()) return Pair('?', -1)
        val strip = Mat(rgb, Rect(ax, ay, bw, bh))
        val hsv = Mat()
        Imgproc.cvtColor(strip, hsv, Imgproc.COLOR_RGB2HSV)
        val m = Mat()
        Core.inRange(hsv, Scalar(98.0, 150.0, 170.0), Scalar(122.0, 255.0, 255.0), m)
        val blue = Core.countNonZero(m)
        var xb = -1
        if (blue > 0.012 * bw * bh) {
            val cs = Mat()
            Core.reduce(m, cs, 0, Core.REDUCE_SUM, CvType.CV_32S)
            val arr = IntArray(bw)
            cs.get(0, 0, arr)
            for (i in bw - 1 downTo 0) if (arr[i] >= 255 * 3) { xb = i; break }
            cs.release()
        }
        hsv.release(); m.release()
        if (xb >= 0) {
            val sx = minOf(xb + 4, bw - 12)
            val num = Mat(strip, Rect(sx, 0, bw - sx, bh))
            var v = -1
            for (dark in listOf(true, false)) {
                v = digitsOf(ocrText(num, dark))
                if (v >= 0) break
            }
            return Pair('g', v)
        }
        val g = Mat()
        Imgproc.cvtColor(strip, g, Imgproc.COLOR_RGB2GRAY)
        val sig = (Core.mean(g).`val`[0] * 10).toInt() // signature de contenu : change quand l'objet de la case change
        val dk = Mat()
        Imgproc.threshold(g, dk, 110.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val darkFrac = Core.countNonZero(dk).toDouble() / (bw * bh)
        g.release(); dk.release()
        if (darkFrac >= 0.04) return Pair('r', sig)
        for (dark in listOf(true, false)) {
            if (digitsOf(ocrText(strip, dark)) >= 0) return Pair('r', sig)
        }
        return Pair('?', -1)
    }

    /**
     * Magasin nomade : on achète tout sauf ce qui coûte plus de 65 cristaux bleus (les objets payés en ressources sont
     * toujours achetés). Quand les 6 cases sont bloquées par des objets à plus de 65 cristaux, on touche « Actualisation
     * Gratuite » (bouton vert en haut à droite) et on recommence, jusqu'à ce que ce bouton devienne orange (payant).
     */
    private fun detectMarchand(rgb: Mat, now: Long) {
        if (dailyStep == 6) { detectMysteryShop(rgb, now); return }
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Marchand nomade abandonné (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        // Fenêtre « Acheter » (bouton orange au centre, prix lu une seconde fois)
        val dlg = colorRect(rgb, 330, 1435, 750, 1535, Scalar(8.0, 150.0, 180.0), Scalar(25.0, 255.0, 255.0)) >= 0.35
        if (dlg) {
            dailyIdleAt = now
            shopOpenTries = 0
            // on laisse la fenêtre finir de s'afficher avant de lire le prix
            if (shopDlgFirst == 0L) { shopDlgFirst = now; return }
            if (now - shopDlgFirst < 350L) return
            shopDlgFirst = 0L
            val dr = shopDialogRead(rgb)
            if (dr.first == 'g' && dr.second < 0 && shopDlgRetry < 4) {
                // prix illisible (animation, reflet) : la fenêtre reste ouverte, on relit un peu plus tard
                shopDlgRetry++
                shopDlgFirst = now - 150L
                return
            }
            shopDlgRetry = 0
            val exp = shopExpect
            // Achat seulement si la fenêtre confirme ce qu'on attendait : ressources, ou cristaux lus et <= 65.
            // Prix illisible, prix > 65, fenêtre inattendue ou type différent : on ferme sans acheter.
            val verifying = shopOpened in shopVerify // contrôle avant actualisation : la fenêtre a le dernier mot
            val ok = exp != null && when (dr.first) {
                'r' -> exp.first == 'r'
                'g' -> exp.first == 'g' && if (verifying) {
                    dr.second in 1..65 // contrôle avant actualisation : seulement un « petit » prix confirmé par la fenêtre
                } else {
                    shopOk(dr.second) && shopOk(exp.second) && (dr.second > 65) == (exp.second > 65)
                }
                else -> false
            }
            if (!ok) {
                // prix simplement illisible : on ne bloque pas la case, elle sera retentée (3 touches au plus)
                if (shopOpened in 0..5 && dr.second >= 0) shopSkipSig[shopOpened] = shopSigOf(shopOpened)
                if (!verifying) shopFail++ // un contrôle qui confirme « trop cher » n'est pas un échec
                val why = "attendu ${exp?.let { shopDesc(it) } ?: "rien"}, lu ${shopDesc(dr)}, largeur ${shopDlgW.toInt()}, texte [$shopDlgOcr]"
                clickRef(983.0, 882.0, "daily : Marchand : fenêtre Acheter refusée ($why), fermée sans acheter")
                shopOpened = -1
                shopExpect = null
                shopWaitUntil = now + 600
                shopSig = ""
                return
            }
            shopBuys++
            shopCheckDone = false
            shopVerify.clear()
            clickRef(540.0, 1485.0, "daily : Marchand : achat ${if (dr.first == 'g') "${dr.second} cristaux" else "en ressources"}${if (verifying) " (lecture de la case corrigée par la fenêtre)" else ""}, largeur ${shopDlgW.toInt()} (n° $shopBuys)")
            shopOpened = -1
            shopExpect = null
            shopWaitUntil = now + 800
            shopSig = ""
            return
        }
        // Écran du magasin : onglet « Marchand Nomade » en bas à gauche
        var tab = ocrRef(rgb, 0, 2215, 300, 2335, true)
        if ("march" !in tab && "nomade" !in tab) tab = ocrRef(rgb, 0, 2215, 300, 2335, false)
        if ("march" !in tab && "nomade" !in tab) return
        dailyIdleAt = now
        if (dailyStep == 0) {
            // première fois dans le magasin : on touche l'onglet « Marchand Nomade » (en bas à gauche)
            dailyStep = 1
            shopWaitUntil = now + 800
            clickRef(150.0, 2275.0, "daily : Marchand nomade : clic sur l'onglet du bas")
            return
        }
        if (now < shopWaitUntil) return
        shopExpect = null
        shopOpened = -1

        val reads = (0..5).map { shopRead(rgb, it) }
        shopReads = reads
        val sig = reads.joinToString("|") { "${it.first}${it.second}" }
        if (sig != shopSig) { shopSig = sig; return } // on attend deux lectures identiques (fin des animations)

        fun blocked(i: Int) = (reads[i].first == 'g' && reads[i].second > 65 && reads[i].second != 600) ||
            (shopSkipSig[i].isNotEmpty() && shopSkipSig[i] == shopSigOf(i))
        val buy = (0..5).firstOrNull { i ->
            val skipped = shopSkipSig[i].isNotEmpty() && shopSkipSig[i] == shopSigOf(i)
            !skipped && (reads[i].first == 'r' ||
                (reads[i].first == 'g' && (shopOk(reads[i].second) || i in shopVerify)))
        }
        if (shopFail >= 8) { shopFinish("trop de fenêtres Acheter refusées : arrêt par sécurité"); return }
        if (buy != null) {
            if (shopBuys >= 400) { shopFinish("400 achats atteints"); return }
            val bsig = "${reads[buy].first}${reads[buy].second}"
            // même case, même contenu, touchée 3 fois sans changement : l'achat ne se fait pas, on l'ignore
            if (buy == shopLastSlot && bsig == shopLastSig) shopSameCnt++ else { shopLastSlot = buy; shopLastSig = bsig; shopSameCnt = 1 }
            if (shopSameCnt > 3) {
                shopSkipSig[buy] = shopSigOf(buy)
                log("daily : Marchand : case ${buy + 1} inchangée après 3 touches, ignorée")
                shopSameCnt = 0
                return
            }
            shopUnknownAt = 0L
            shopOpened = buy
            shopExpect = reads[buy]
            if (reads[buy].first == 'r') {
                // objet payé en ressources : l'achat se fait directement en touchant le prix (pas de fenêtre)
                shopBuys++
                shopCheckDone = false
                shopVerify.clear()
                shopWaitUntil = now + 800
                shopSig = ""
                clickRef(shopCx[buy % 3].toDouble(), shopCy[buy / 3].toDouble(),
                    "daily : Marchand : case ${buy + 1} (ressources), achat direct (n° $shopBuys)")
                return
            }
            if (++shopOpenTries > 6) { shopFinish("la fenêtre Acheter ne s'ouvre pas : arrêt par sécurité"); return }
            shopWaitUntil = now + 1600
            clickRef(shopCx[buy % 3].toDouble(), shopCy[buy / 3].toDouble(),
                "daily : Marchand : case ${buy + 1} (${shopDesc(reads[buy])}), ouverture")
            return
        }
        if ((0..5).all { blocked(it) }) {
            shopUnknownAt = 0L
            val green = colorRect(rgb, 760, 440, 1030, 520, Scalar(40.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0))
            val orange = colorRect(rgb, 760, 440, 1030, 520, Scalar(8.0, 150.0, 180.0), Scalar(25.0, 255.0, 255.0))
            if (orange >= 0.3 && green < 0.1) {
                shopEnd("6 cases bloquées et bouton d'actualisation orange (payant) : fin")
            } else if (green >= 0.25) {
                if (!shopCheckDone) {
                    // Petit contrôle avant d'actualiser : on efface les cases « ignorées » et on fait confirmer par la
                    // fenêtre Acheter le prix des objets à cristaux lus entre 66 et 199 (une lecture ratée de « 63 », par exemple).
                    shopCheckDone = true
                    shopSkipSig = Array(6) { "" }
                    shopVerify.clear()
                    for (i in 0..5) if (reads[i].first == 'g' && reads[i].second in 66..199) shopVerify.add(i)
                    shopSig = ""
                    shopWaitUntil = now + 500
                    log("daily : Marchand : contrôle avant actualisation (${reads.joinToString(" ") { shopDesc(it) }}), cases à confirmer par la fenêtre : ${shopVerify.map { it + 1 }}")
                    return
                }
                if (dailyBtn >= 40) { shopEnd("40 actualisations atteintes"); return }
                dailyBtn++
                shopSig = ""
                shopSkipSig = Array(6) { "" }
                shopCheckDone = false
                shopVerify.clear()
                shopWaitUntil = now + 1500
                clickRef(895.0, 475.0, "daily : Marchand : 6 cases bloquées (${reads.joinToString(" ") { shopDesc(it) }}), actualisation gratuite n° $dailyBtn")
            } else if (now - dailyStepAt > 3_000L) {
                log("daily : Marchand : bouton d'actualisation non reconnu (vert $green, orange $orange)")
                dailyStepAt = now
            }
            return
        }
        // Cases illisibles ou en transition : on laisse un peu de temps, sinon on s'arrête sans actualiser
        if (shopUnknownAt == 0L) shopUnknownAt = now
        else if (now - shopUnknownAt > 8_000L) {
            shopFinish("cases illisibles (${reads.joinToString(" ") { shopDesc(it) }}) : arrêt sans actualiser")
        }
    }

    // ---------- Magasin mystère (option du menu « Marchand nomade », après la fin du Marchand nomade) ----------

    private var myState = 0
    private var myRefresh = 0
    private var myBuys = 0
    private var myStartAt = 0L
    private var myWaitUntil = 0L
    private var mySig = ""
    private var myUnknownAt = 0L
    private var myTabTries = 0
    private var myDotSince = 0L
    private val myClicks = HashMap<Int, Int>()
    private val mySkip = HashSet<Int>()
    private val myTops = intArrayOf(735, 1168, 1600)   // haut des cartes (référence 1080 px)
    private val myCy = intArrayOf(1070, 1503, 1935)    // centre de la bande « Gratuit »

    /** Fin normale du Marchand nomade : si « Magasin mystère » est coché, on passe par l'onglet Mystère avant de sortir. */
    private fun shopEnd(msg: String) {
        if (mode != "marchand" || !getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("mysteryShop", false)) { shopFinish(msg); return }
        val now = SystemClock.uptimeMillis()
        log("daily : Marchand nomade : $msg ; magasin mystère coché : on regarde l'onglet Mystère")
        dailyStep = 6
        myState = 0; myRefresh = 0; myBuys = 0; myStartAt = now; myWaitUntil = 0L; mySig = ""
        myUnknownAt = 0L; myTabTries = 0; myDotSince = 0L
        myClicks.clear(); mySkip.clear()
        dailyIdleAt = now
    }

    private fun redDot(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Double =
        maxOf(
            colorRect(rgb, x0, y0, x1, y1, Scalar(0.0, 170.0, 180.0), Scalar(10.0, 255.0, 255.0)),
            colorRect(rgb, x0, y0, x1, y1, Scalar(170.0, 170.0, 180.0), Scalar(179.0, 255.0, 255.0))
        )

    /**
     * Onglet Mystère : point rouge sur l'onglet > ouverture > cartes vertes « Gratuit » touchées au centre de leur bande >
     * « Actualisation Gratuite » (bouton vert, 5 fois au plus) > sortie dès que plus rien de gratuit (bouton orange ou limite).
     * Jamais de case payante (prix en pièces mystère) ni de bouton orange.
     */
    private fun detectMysteryShop(rgb: Mat, now: Long) {
        dailyIdleAt = now
        if (now - myStartAt > 120_000L) { shopFinish("magasin mystère : plus de 2 minutes, arrêt par sécurité"); return }
        if (now < myWaitUntil) return
        when (myState) {
            0 -> {
                // point rouge en haut à droite de l'onglet « Mystère » (deuxième onglet du bas)
                val red = redDot(rgb, 555, 2215, 615, 2265)
                if (red < 0.04) {
                    if (myDotSince == 0L) myDotSince = now
                    if (now - myDotSince > 1_500L) shopFinish("magasin mystère : pas de point rouge sur l'onglet Mystère, sortie (rouge ${"%.2f".format(red)})")
                    return
                }
                myState = 1
                myWaitUntil = now + 1_500L
                clickRef(455.0, 2275.0, "marchand : point rouge sur l'onglet Mystère, ouverture")
            }
            1 -> {
                // l'onglet sélectionné est clair (crème) : on attend de le voir ainsi
                val cream = colorRect(rgb, 320, 2290, 590, 2330, Scalar(10.0, 10.0, 225.0), Scalar(30.0, 90.0, 255.0))
                if (cream >= 0.5) { myState = 2; mySig = ""; return }
                if (++myTabTries > 4) { shopFinish("magasin mystère : l'onglet ne s'ouvre pas, sortie"); return }
                myWaitUntil = now + 1_200L
                clickRef(455.0, 2275.0, "marchand : onglet Mystère (nouvel essai $myTabTries)")
            }
            else -> {
                val frees = (0..8).filter { i ->
                    val x0 = shopCols[i % 3]
                    val y0 = myTops[i / 3]
                    colorRect(rgb, x0 + 10, y0 + 40, x0 + 285, y0 + 370, Scalar(50.0, 30.0, 100.0), Scalar(80.0, 255.0, 255.0)) >= 0.2
                }
                val green = colorRect(rgb, 760, 510, 1030, 600, Scalar(40.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0))
                val orange = colorRect(rgb, 760, 510, 1030, 600, Scalar(8.0, 150.0, 180.0), Scalar(25.0, 255.0, 255.0))
                val sig = "${frees.joinToString(",")}|${(green * 10).toInt()}|${(orange * 10).toInt()}"
                if (sig != mySig) { mySig = sig; return } // deux lectures identiques (fin des animations)
                val free = frees.firstOrNull { it !in mySkip }
                if (free != null) {
                    myUnknownAt = 0L
                    val n = (myClicks[free] ?: 0) + 1
                    myClicks[free] = n
                    if (n > 3) {
                        mySkip.add(free)
                        log("marchand : mystère : case ${free + 1} inchangée après 3 touches, ignorée")
                        return
                    }
                    if (++myBuys > 40) { shopFinish("magasin mystère : 40 achats atteints, arrêt par sécurité"); return }
                    mySig = ""
                    myWaitUntil = now + 1_200L
                    clickRef(shopCx[free % 3].toDouble(), myCy[free / 3].toDouble(), "marchand : mystère : case ${free + 1} « Gratuit » (n° $myBuys)")
                    return
                }
                if (green >= 0.25 && orange < 0.1) {
                    myUnknownAt = 0L
                    if (myRefresh >= 5) { shopFinish("magasin mystère : 5 actualisations faites, plus rien de gratuit : fin"); return }
                    myRefresh++
                    mySkip.clear(); myClicks.clear()
                    mySig = ""
                    myWaitUntil = now + 2_000L
                    clickRef(895.0, 552.0, "marchand : mystère : plus de case gratuite, actualisation gratuite n° $myRefresh")
                    return
                }
                if (orange >= 0.3 && green < 0.1) { shopFinish("magasin mystère : plus de case gratuite, bouton d'actualisation orange (payant) : fin"); return }
                if (myUnknownAt == 0L) myUnknownAt = now
                else if (now - myUnknownAt > 8_000L) shopFinish("magasin mystère : écran illisible (vert ${"%.2f".format(green)}, orange ${"%.2f".format(orange)}), sortie")
            }
        }
    }

    /** Prix lu dans le bouton orange de la fenêtre « Acheter » : ('g', valeur ou -1) cristaux, ('r', 0) ressources. */
    private fun shopDialogRead(rgb: Mat): Pair<Char, Int> {
        val sc = rgb.cols() / 1080.0
        val ax = (330 * sc).toInt()
        val ay = (1435 * sc).toInt()
        val bw = (420 * sc).toInt()
        val bh = (100 * sc).toInt()
        if (ax + bw > rgb.cols() || ay + bh > rgb.rows()) return Pair('?', -1)
        val btn = Mat(rgb, Rect(ax, ay, bw, bh))
        val hsv = Mat()
        Imgproc.cvtColor(btn, hsv, Imgproc.COLOR_RGB2HSV)
        val m = Mat()
        Core.inRange(hsv, Scalar(98.0, 150.0, 170.0), Scalar(122.0, 255.0, 255.0), m)
        val blue = Core.countNonZero(m)
        var xb = -1
        if (blue > 0.02 * bw * bh) {
            val cs = Mat()
            Core.reduce(m, cs, 0, Core.REDUCE_SUM, CvType.CV_32S)
            val arr = IntArray(bw)
            cs.get(0, 0, arr)
            for (i in bw - 1 downTo 0) if (arr[i] >= 255 * 3) { xb = i; break }
            cs.release()
        }
        hsv.release(); m.release()
        if (xb < 0) return Pair('r', 0)
        val sx = minOf(xb + 4, bw - 12)
        val num = Mat(btn, Rect(sx, 0, bw - sx, bh))
        var v = -1
        val raws = ArrayList<String>()
        // texte blanc sur fond orange : seuil haut d'abord (le fond orange est proche de 170 en niveaux de gris)
        for ((dark, thr) in listOf(false to 225, false to 200, false to 170, true to 150)) {
            val raw = ocrText(num, dark, thr)
            raws.add(raw.replace("\n", " ").trim())
            v = digitsOf(raw)
            if (v >= 0) break
        }
        shopDlgOcr = raws.joinToString("/")
        // Contrôle par la largeur du texte blanc (référence 1080 px) : 2 chiffres ≈ 45–60 px, 3 chiffres ≈ 76–95 px.
        // Un chiffre perdu par la lecture (« 120 » lu « 20 ») fait donc refuser le prix au lieu de l'accepter.
        val white = Mat()
        Core.inRange(num, Scalar(225.0, 225.0, 225.0), Scalar(255.0, 255.0, 255.0), white)
        val cs2 = Mat()
        Core.reduce(white, cs2, 0, Core.REDUCE_SUM, CvType.CV_32S)
        val arr2 = IntArray(white.cols())
        cs2.get(0, 0, arr2)
        var first = -1
        var last = -1
        for (i in arr2.indices) if (arr2[i] >= 255 * 2) { if (first < 0) first = i; last = i }
        white.release(); cs2.release()
        shopDlgW = if (first >= 0) (last - first + 1) / sc else 0.0
        if (v >= 0) {
            val nd = v.toString().length
            if (shopDlgW <= 0.0 || (shopDlgW >= 68 && nd <= 2) || (shopDlgW <= 64 && nd >= 3)) v = -1
        }
        return Pair('g', v)
    }

    /** Prix en cristaux autorisé : 65 ou moins, ou exactement 600 (accélérations d'une heure). */
    private fun shopOk(v: Int) = v in 1..65 || v == 600

    private fun shopDesc(r: Pair<Char, Int>) = when (r.first) {
        'g' -> if (r.second >= 0) "${r.second} cristaux" else "cristaux ?"
        'r' -> "ressources"
        else -> "vide"
    }

    private fun shopSigOf(i: Int): String = shopReads.getOrNull(i)?.let { "${it.first}${it.second}" } ?: ""

    private fun shopFinish(msg: String) {
        log("daily : Marchand nomade : $msg")
        dailyAttempts["marchand"] = 2 // fini pour ce passage, même si le point rouge reste (autres onglets)
        lastClick = SystemClock.uptimeMillis()
        backArrow()
        dailyStep = 5
        if (mode == "marchand") {
            marchandFinished = true
            main.post { pauseBot("Marchand nomade terminé") }
            return
        }
        dailyTaskDone("Marchand nomade")
    }


    // ---------- Boutiques coffre + weekly (dernier préambule : point rouge sur le panier en haut à droite) ----------

    private var bqStartAt = 0L
    private var bqWaitUntil = 0L
    private var bqPageKey = -1
    private var bqPageSince = 0L
    private var bqChestTries = 0
    private var bqSwipes = 0
    private var bqSame = 0
    private var bqBottom = false
    private var bqSig: DoubleArray? = null
    private var bqLeft = 0
    private var bqRight = 0
    private var bqVisits = 0
    private var bqHebdoStep = 0 // Offre Hebdo : 0 = bouton vert « Gratuit », 1 = cristal bleu de la barre de progression, 2 = fait
    private var bqBarSwipes = 0
    private var bqCurSig: DoubleArray? = null
    private val bqIgnore = ArrayList<DoubleArray>()
    private val bqStuck = HashSet<Int>()
    private val bqBtnClicks = HashMap<Int, Int>()

    private fun resetBq(now: Long) {
        bqHebdoStep = 0; bqBarSwipes = 0
        bqStartAt = now
        bqWaitUntil = now + 1800L
        bqPageKey = -1
        bqPageSince = now
        bqChestTries = 0
        bqSwipes = 0
        bqSame = 0
        bqBottom = false
        bqSig = null
        bqLeft = 0
        bqRight = 0
        bqVisits = 0
        bqCurSig = null
        bqIgnore.clear()
        bqStuck.clear()
        bqBtnClicks.clear()
    }

    /** Pastille rouge (les deux plages de teinte) : part de pixels rouges dans un rectangle. */
    private fun redRect(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Double =
        colorRect(rgb, x0, y0, x1, y1, Scalar(0.0, 150.0, 150.0), Scalar(8.0, 255.0, 255.0)) +
            colorRect(rgb, x0, y0, x1, y1, Scalar(172.0, 150.0, 150.0), Scalar(179.0, 255.0, 255.0))

    /** Onglet sélectionné (fond blanc en haut de la barre d'onglets) : (x début, x fin) en référence 1080, ou null. */
    private fun bqSelectedTab(rgb: Mat): Pair<Int, Int>? {
        val sc = rgb.cols() / 1080.0
        val ay = (233 * sc).toInt()
        val by = (241 * sc).toInt()
        if (by <= ay || by > rgb.rows()) return null
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(0, ay, rgb.cols(), by - ay)), hsv, Imgproc.COLOR_RGB2HSV)
        val m = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 225.0), Scalar(179.0, 50.0, 255.0), m)
        val cs = Mat()
        Core.reduce(m, cs, 0, Core.REDUCE_SUM, CvType.CV_32S)
        val arr = IntArray(m.cols())
        cs.get(0, 0, arr)
        val need = m.rows() * 255 / 2
        hsv.release(); m.release(); cs.release()
        var best: Pair<Int, Int>? = null
        var start = -1
        var last = -1
        val gap = (30 * sc).toInt()
        fun close() {
            if (start >= 0) {
                val a = (start / sc).toInt()
                val b = (last / sc).toInt()
                val cur = best
                val okWidth = b - a >= 200 || ((b >= 1000 || a <= 40) && b - a >= 100) // onglet coupé au bord de l'écran
                if (okWidth && (cur == null || b - a > cur.second - cur.first)) best = Pair(a, b)
            }
            start = -1
        }
        for (x in arr.indices) {
            if (arr[x] >= need) {
                if (start < 0) start = x
                last = x
            } else if (start >= 0 && x - last > gap) close()
        }
        close()
        return best
    }

    /** Points rouges des onglets (en haut à droite de chaque onglet) : abscisses de référence, hors pastilles « ! » des bords. */
    private fun bqTabDots(rgb: Mat): List<Int> {
        val sc = rgb.cols() / 1080.0
        val ay = (238 * sc).toInt()
        val by = (258 * sc).toInt()
        if (by <= ay || by > rgb.rows()) return emptyList()
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(0, ay, rgb.cols(), by - ay)), hsv, Imgproc.COLOR_RGB2HSV)
        val m1 = Mat()
        val m2 = Mat()
        Core.inRange(hsv, Scalar(0.0, 150.0, 150.0), Scalar(8.0, 255.0, 255.0), m1)
        Core.inRange(hsv, Scalar(172.0, 150.0, 150.0), Scalar(179.0, 255.0, 255.0), m2)
        Core.bitwise_or(m1, m2, m1)
        val cs = Mat()
        Core.reduce(m1, cs, 0, Core.REDUCE_SUM, CvType.CV_32S)
        val arr = IntArray(m1.cols())
        cs.get(0, 0, arr)
        hsv.release(); m1.release(); m2.release(); cs.release()
        val out = ArrayList<Int>()
        var start = -1
        var last = -1
        fun close() {
            if (start >= 0) {
                val wRef = (last - start + 1) / sc
                val cx = ((start + last) / 2.0 / sc).toInt()
                if (wRef in 10.0..34.0 && cx in 110..990) out.add(cx)
            }
            start = -1
        }
        for (x in arr.indices) {
            if (arr[x] >= 255 * 4) {
                if (start < 0) start = x
                last = x
            } else close()
        }
        close()
        return out
    }

    /** Signature (couleurs moyennes) de l'icône d'un onglet : sert à ne pas revenir sur un onglet qui n'a rien donné. */
    private fun bqTabSig(rgb: Mat, cx: Int): DoubleArray? {
        val sc = rgb.cols() / 1080.0
        val x0 = ((cx - 45) * sc).toInt()
        val x1 = ((cx + 45) * sc).toInt()
        val y0 = (262 * sc).toInt()
        val y1 = (332 * sc).toInt()
        if (x0 < 0 || x1 > rgb.cols() || y1 > rgb.rows() || x1 <= x0 || y1 <= y0) return null
        val ym = (y0 + y1) / 2
        val a = Core.mean(Mat(rgb, Rect(x0, y0, x1 - x0, ym - y0))).`val`
        val b = Core.mean(Mat(rgb, Rect(x0, ym, x1 - x0, y1 - ym))).`val`
        return doubleArrayOf(a[0], a[1], a[2], b[0], b[1], b[2])
    }

    private fun bqSameSig(a: DoubleArray, b: DoubleArray): Boolean {
        var d = 0.0
        for (i in a.indices) d += Math.abs(a[i] - b[i])
        return d < 30.0
    }

    /** Signature de la page (aperçu 16×32 en gris) : sert à voir si le glissement a fait bouger la page (sinon : bas atteint). */
    private fun bqContentSig(rgb: Mat): DoubleArray {
        val sc = rgb.cols() / 1080.0
        val ax = (60 * sc).toInt()
        val ay = (380 * sc).toInt()
        val bx = minOf((1020 * sc).toInt(), rgb.cols())
        val by = minOf((2300 * sc).toInt(), rgb.rows())
        val gray = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(ax, ay, bx - ax, by - ay)), gray, Imgproc.COLOR_RGB2GRAY)
        val small = Mat()
        Imgproc.resize(gray, small, Size(16.0, 32.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val bytes = ByteArray(16 * 32)
        small.get(0, 0, bytes)
        gray.release(); small.release()
        return DoubleArray(bytes.size) { (bytes[it].toInt() and 0xFF).toDouble() }
    }

    private fun bqSigDiff(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0
        for (i in a.indices) d += Math.abs(a[i] - b[i])
        return d / a.size
    }

    /** Larges boutons verts (« Récupérer ») de la page : rectangles (x0, y0, x1, y1) en référence, de haut en bas. */
    private fun bqGreens(rgb: Mat): List<IntArray> {
        val sc = rgb.cols() / 1080.0
        val ax = (60 * sc).toInt()
        val ay = (380 * sc).toInt()
        val bx = minOf((1020 * sc).toInt(), rgb.cols())
        val by = minOf((2300 * sc).toInt(), rgb.rows())
        if (bx <= ax || by <= ay) return emptyList()
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(ax, ay, bx - ax, by - ay)), hsv, Imgproc.COLOR_RGB2HSV)
        val m = Mat()
        Core.inRange(hsv, Scalar(40.0, 120.0, 110.0), Scalar(82.0, 255.0, 255.0), m)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(maxOf(3.0, 25 * sc), maxOf(3.0, 25 * sc)))
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, k)
        val cnts = ArrayList<MatOfPoint>()
        Imgproc.findContours(m, cnts, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val out = ArrayList<IntArray>()
        for (c in cnts) {
            val r = Imgproc.boundingRect(c)
            val wr = r.width / sc
            val hr = r.height / sc
            if (wr >= 300 && hr in 80.0..170.0 && wr / hr >= 2.2) {
                val fill = Core.countNonZero(Mat(m, r)).toDouble() / (r.width * r.height)
                if (fill >= 0.6) out.add(intArrayOf(((r.x + ax) / sc).toInt(), ((r.y + ay) / sc).toInt(), ((r.x + r.width + ax) / sc).toInt(), ((r.y + r.height + ay) / sc).toInt()))
            }
            c.release()
        }
        hsv.release(); m.release()
        return out.sortedBy { it[1] }
    }

    /** Petite pastille rouge ronde (point rouge d'un coffre) dans une zone : centre en référence, ou null. */
    private fun bqRedBlob(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Pair<Double, Double>? {
        val sc = rgb.cols() / 1080.0
        val ax = (x0 * sc).toInt()
        val ay = (y0 * sc).toInt()
        val bx = minOf((x1 * sc).toInt(), rgb.cols())
        val by = minOf((y1 * sc).toInt(), rgb.rows())
        if (bx <= ax || by <= ay) return null
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(ax, ay, bx - ax, by - ay)), hsv, Imgproc.COLOR_RGB2HSV)
        val m1 = Mat()
        val m2 = Mat()
        Core.inRange(hsv, Scalar(0.0, 150.0, 150.0), Scalar(8.0, 255.0, 255.0), m1)
        Core.inRange(hsv, Scalar(172.0, 150.0, 150.0), Scalar(179.0, 255.0, 255.0), m2)
        Core.bitwise_or(m1, m2, m1)
        val cnts = ArrayList<MatOfPoint>()
        Imgproc.findContours(m1, cnts, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var best: Pair<Double, Double>? = null
        for (c in cnts) {
            val r = Imgproc.boundingRect(c)
            val wr = r.width / sc
            val hr = r.height / sc
            if (wr in 12.0..40.0 && hr in 12.0..40.0 && wr / hr in 0.7..1.4) {
                val fill = Core.countNonZero(Mat(m1, r)).toDouble() / (r.width * r.height)
                val cy = (r.y + r.height / 2.0 + ay) / sc
                if (fill >= 0.6 && (best == null || cy < best.second)) best = Pair((r.x + r.width / 2.0 + ax) / sc, cy)
            }
            c.release()
        }
        hsv.release(); m1.release(); m2.release()
        return best
    }

    /**
     * Mini coffre à récupérer (« Récupérable » ou « Gratuit ») : sa place change d'un onglet à l'autre, donc on lit le texte
     * de l'en-tête de la page (position du mot) ; à défaut, on cherche son point rouge en haut à droite. Renvoie le point à toucher.
     */
    private fun bqChest(rgb: Mat): Pair<Double, Double>? {
        val sc = rgb.cols() / 1080.0
        val x0 = (20 * sc).toInt()
        val y0 = (380 * sc).toInt()
        val x1 = minOf((1075 * sc).toInt(), rgb.cols())
        val y1 = minOf((820 * sc).toInt(), rgb.rows())
        if (x1 > x0 && y1 > y0) {
            val crop = Mat(rgb, Rect(x0, y0, x1 - x0, y1 - y0))
            for (thr in intArrayOf(225, 200)) {
                for (l in ocrLines(crop, false, thr)) {
                    val t = l.text.lowercase()
                    if ("cup" in t || "rabl" in t || "grat" in t || "ratu" in t) {
                        val lx = (x0 + l.cx) / sc
                        val ly = (y0 + l.cy) / sc
                        log("daily : Boutiques : mot «${t.trim().take(16)}» repéré en (${lx.toInt()}, ${ly.toInt()})")
                        return Pair(lx, ly - 58.0)
                    }
                }
            }
        }
        val d = bqRedBlob(rgb, 760, 380, 1075, 700)
        if (d != null) {
            log("daily : Boutiques : point rouge de coffre repéré en (${d.first.toInt()}, ${d.second.toInt()})")
            return Pair(d.first - 52.0, d.second + 33.0)
        }
        return null
    }

    /** Fait coulisser la barre d'onglets du haut (y ≈ 300) : vers la droite = on voit les onglets de gauche. Part de x = 350 (hors de nos boutons). */
    private fun bqSwipeBar(rgb: Mat, toRight: Boolean, dist: Int) {
        lastClick = SystemClock.uptimeMillis()
        val sc = rgb.cols() / 1080.0
        val x0 = if (toRight) 400 else 800
        val x1 = if (toRight) 400 + dist else 800 - dist
        ClickAccessibilityService.instance?.swipe((x0 * sc).toFloat(), (300 * sc).toFloat(), (x1.coerceIn(20, 1060) * sc).toFloat(), (300 * sc).toFloat(), 300)
    }

    private fun bqExit(msg: String, now: Long) {
        lastClick = now
        backArrow()
        dailyAttempts["shop"] = 2
        log("daily : Boutiques : $msg")
        dailyTaskDone("Boutiques coffre + weekly")
    }

    /**
     * Boutiques : panier (point rouge) > onglets à point rouge de gauche à droite > mini coffre (« Récupérable » / « Gratuit », puis
     * « Récupéré(es) » : appui n'importe où) et boutons verts « Récupérer » (on glisse vers le bas jusqu'au dernier) > pastille « ! »
     * au bord de la barre d'onglets pour retrouver les points rouges oubliés > flèche bleue. Jamais de bouton orange (en €).
     */
    private fun detectBq(rgb: Mat, now: Long) {
        if (now < bqWaitUntil) return
        if (now - bqStartAt > 180_000L) { bqExit("trop long (3 min), sortie", now); return }
        val rw = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in rw || "quitter" in rw) {
            dailyIdleAt = now
            tapRewards("daily : Boutiques : récompense : appui n'importe où")
            bqWaitUntil = now + 700L
            return
        }
        val sel = bqSelectedTab(rgb)
        if (sel == null) {
            if (now - dailyIdleAt > 10_000L) {
                log("daily : Boutiques abandonnées (10 s sans écran reconnu)")
                lastClick = now
                backArrow()
                dailyState = "OPEN"
                dailyTries = 0
            }
            return
        }
        dailyIdleAt = now
        val dots = bqTabDots(rgb)
        val key = sel.first / 40
        if (key != bqPageKey) {
            bqPageKey = key
            bqPageSince = now
            bqChestTries = 0
            bqSwipes = 0
            bqSame = 0
            bqBottom = false
            bqSig = null
            bqBtnClicks.clear()
        }
        val selMin = sel.first + minOf(150, (sel.second - sel.first) / 2)
        var selDot = dots.any { it >= selMin && it <= sel.second + 12 }
        if (selDot && key in bqStuck) selDot = false
        if (selDot && now - bqPageSince > 25_000L) {
            bqStuck.add(key)
            bqCurSig?.let { bqIgnore.add(it) }
            log("daily : Boutiques : onglet resté avec son point rouge après 25 s, on passe")
            selDot = false
        }
        if (selDot) {
            // 0. Offre Hebdo (une fois par semaine) : bouton vert « Gratuit » à droite, puis cristal bleu de la barre de progression à gauche
            if (bqHebdoStep < 2 && bqSwipes == 0) {
                val head = ocrRef(rgb, 40, 385, 700, 470) + " " + ocrRefWhite(rgb, 40, 385, 700, 470)
                if ("hebdo" in head || "hebd" in head) {
                    if (bqHebdoStep == 0) {
                        val g = colorRect(rgb, 745, 1145, 950, 1235, Scalar(50.0, 90.0, 100.0), Scalar(90.0, 255.0, 255.0))
                        bqHebdoStep = 1
                        if (g >= 0.4) {
                            clickRef(845.0, 1190.0, "daily : Boutiques : Offre Hebdo : bouton vert « Gratuit »")
                            bqWaitUntil = now + 2000L
                        } else {
                            log("daily : Boutiques : Offre Hebdo : pas de bouton vert (déjà pris), on passe au cristal bleu")
                        }
                        return
                    }
                    bqHebdoStep = 2
                    clickRef(237.0, 718.0, "daily : Boutiques : Offre Hebdo : cristal bleu de la barre de progression")
                    bqWaitUntil = now + 2000L
                    return
                }
            }
            // 1. mini coffre (en haut de page seulement)
            if (bqSwipes == 0 && bqChestTries < 3) {
                val c = bqChest(rgb)
                if (c != null) {
                    bqChestTries++
                    clickRef(c.first, c.second, "daily : Boutiques : mini coffre touché")
                    bqWaitUntil = now + 1700L
                    return
                }
            }
            // 2. boutons verts « Récupérer »
            for (g in bqGreens(rgb)) {
                val bk = g[1] / 80
                if ((bqBtnClicks[bk] ?: 0) >= 3) continue
                val t = ocrRefWhite(rgb, g[0], g[1], g[2], g[3])
                bqBtnClicks[bk] = (bqBtnClicks[bk] ?: 0) + 1
                if ("cup" in t || "rer" in t) {
                    clickRef(((g[0] + g[2]) / 2).toDouble(), ((g[1] + g[3]) / 2).toDouble(), "daily : Boutiques : bouton vert « Récupérer » touché")
                    bqWaitUntil = now + 1300L
                    return
                } else {
                    bqBtnClicks[bk] = 3
                    log("daily : Boutiques : bouton vert sans « Récupérer » (texte «${t.trim().take(20)}»), ignoré")
                }
            }
            // 3. glisser vers le bas jusqu'au bout de la page
            if (!bqBottom && bqSwipes < 8) {
                val sig = bqContentSig(rgb)
                val prev = bqSig
                bqSig = sig
                if (prev != null && bqSwipes > 0 && bqSigDiff(prev, sig) < 2.5) {
                    bqSame++
                    if (bqSame >= 2) {
                        bqBottom = true
                    } else {
                        bqWaitUntil = now + 1200L // la page a-t-elle fini de défiler ? on relit avant de conclure
                        return
                    }
                } else {
                    bqSame = 0
                    bqSwipes++
                    lastClick = now
                    val sc = rgb.cols() / 1080.0
                    ClickAccessibilityService.instance?.swipe((1010 * sc).toFloat(), (1900 * sc).toFloat(), (1010 * sc).toFloat(), (900 * sc).toFloat(), 900)
                    log("daily : Boutiques : glissement vers le bas ($bqSwipes)")
                    bqWaitUntil = now + 2500L
                    return
                }
            }
            bqStuck.add(key)
            bqCurSig?.let { bqIgnore.add(it) }
            log("daily : Boutiques : bas de page atteint, point rouge toujours là, on passe")
        }
        // Onglet suivant à point rouge (le plus à gauche)
        val cand = dots.filter { it < selMin || it > sel.second + 12 }.sorted().firstOrNull { x ->
            val sg = bqTabSig(rgb, maxOf(30, x - 90))
            sg == null || bqIgnore.none { bqSameSig(it, sg) }
        }
        if (cand != null && maxOf(30, cand - 90) < 290 && bqBarSwipes < 6) {
            // l'onglet est sous nos boutons ☰ ▶ (en haut à gauche) : on fait coulisser la barre d'onglets vers la droite
            bqBarSwipes++
            bqSwipeBar(rgb, true, 330)
            log("daily : Boutiques : onglet à point rouge sous nos boutons, la barre d'onglets coulisse ($bqBarSwipes)")
            bqWaitUntil = now + 1500L
            return
        }
        if (cand != null && bqVisits < 14) {
            bqVisits++
            bqCurSig = bqTabSig(rgb, maxOf(30, cand - 90))
            clickRef(maxOf(30, cand - 90).toDouble(), 300.0, "daily : Boutiques : onglet à point rouge ($bqVisits)")
            bqWaitUntil = now + 1300L
            return
        }
        // Pastilles « ! » : onglets cachés avec un point rouge
        // (on ne touche pas la pastille : à gauche elle est sous nos boutons ; on fait coulisser la barre à la place)
        if (redRect(rgb, 985, 250, 1055, 300) >= 0.2 && bqRight < 5) {
            bqRight++
            if (bqRight % 2 == 1) {
                // toucher l'onglet du bord droit : le jeu le recentre et les onglets suivants apparaissent (méthode qui avait marché)
                clickRef(1018.0, 300.0, "daily : Boutiques : pastille « ! » à droite, on touche l'onglet du bord ($bqRight)")
            } else {
                bqSwipeBar(rgb, false, 600)
                log("daily : Boutiques : pastille « ! » à droite, la barre d'onglets coulisse vers la gauche ($bqRight)")
            }
            bqWaitUntil = now + 1500L
            return
        }
        if (redRect(rgb, 25, 250, 100, 300) >= 0.2 && bqLeft < 4) {
            bqLeft++
            bqSwipeBar(rgb, true, 600)
            log("daily : Boutiques : pastille « ! » à gauche, la barre d'onglets coulisse vers la droite ($bqLeft)")
            bqWaitUntil = now + 1500L
            return
        }
        bqExit("plus de point rouge, sortie (flèche bleue)", now)
    }

    // ---------- Pet Skill (préambule : patte d'ours avec point rouge) ----------

    private var petIdx = 0
    private var petDiagAt = 0L
    private var petState = 0 // 0 = toucher l'icône, 1 = lire le panneau et « Utiliser », 2 = attendre le résultat
    private var petAt = 0L
    private var petTries = 0
    private var petReward = false
    private var petUsed = 0
    private val petCols = intArrayOf(420, 640, 860, 420)
    private val petRows = intArrayOf(715, 715, 715, 935)
    private val petNames = arrayOf("", "titan", "odeur", "roi") // lynx, bison (Poignée du Titan), lionne (Maîtrise des odeurs), lion (Cadeau du Roi)
    private val petLabels = arrayOf("lynx", "bison", "lionne", "lion")

    private fun petNext() {
        petIdx++; petState = 0; petTries = 0; petReward = false
    }

    /**
     * Pet Skill : patte d'ours (point rouge) > « Compétence de l'Animal » : lynx, bison, lionne, lion (jamais le loup) : on touche
     * l'icône, on lit le panneau du bas (bouton « Utiliser » vert = disponible ; « Actif » ou « Se recharge » = on saute), on
     * touche « Utiliser », on ferme l'écran « Récompenses » s'il vient ; puis croix en haut à droite.
     */
    private fun detectPet(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Pet Skill abandonné (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        val rw = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in rw || "quitter" in rw) {
            dailyIdleAt = now
            petReward = true
            tapRewards("daily : Pet Skill : récompenses : appui n'importe où")
            return
        }
        val head = ocrRefWhite(rgb, 280, 445, 800, 520)
        var win = "animal" in head || "comp" in head
        if (!win) {
            val cage = ocrRefWhite(rgb, 830, 2080, 1070, 2150)
            win = "cage" in cage || "bête" in cage || "bete" in cage
        }
        // 3e moyen : bandeau beige du haut de la fenêtre (texte illisible, par exemple animation d'ouverture)
        val tan = colorRect(rgb, 60, 450, 980, 515, Scalar(5.0, 40.0, 190.0), Scalar(22.0, 120.0, 248.0))
        if (!win) win = tan >= 0.5
        if (!win) {
            if (now - dailyIdleAt > 4_000L && now - petDiagAt > 4_000L) {
                petDiagAt = now
                log("daily : Pet Skill : fenêtre non reconnue (titre «${head.trim().take(25)}», bandeau beige ${(tan * 100).toInt()} %)")
            }
            return
        }
        dailyIdleAt = now
        if (petIdx >= petCols.size) {
            dailyAttempts["pet"] = 2
            clickRef(1003.0, 480.0, "daily : Pet Skill : $petUsed compétence(s) utilisée(s), fermeture (croix)")
            dailyTaskDone("Pet Skill")
            return
        }
        when (petState) {
            0 -> {
                petState = 1
                petAt = now
                clickRef(petCols[petIdx].toDouble(), petRows[petIdx].toDouble(), "daily : Pet Skill : ${petLabels[petIdx]} touché")
            }
            1 -> {
                if (now - petAt < 600L) return
                val title = ocrRef(rgb, 95, 1435, 800, 1495, true)
                if (title.isBlank() || "lectionne" in title) { // panneau pas encore mis à jour
                    if (now - petAt < 2_000L) return
                    if (petTries < 1) { petTries++; petState = 0; return }
                    log("daily : Pet Skill : ${petLabels[petIdx]} : panneau illisible, on saute")
                    petNext()
                    return
                }
                val nameOk = petNames[petIdx].isEmpty() || petNames[petIdx] in title
                if (!nameOk) {
                    if (petTries < 1) { petTries++; petState = 0; return }
                    log("daily : Pet Skill : ${petLabels[petIdx]} : nom inattendu («${title.trim().take(30)}»), on saute")
                    petNext()
                    return
                }
                val green = colorRect(rgb, 340, 1830, 740, 1935, Scalar(40.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0)) >= 0.5
                if (green) {
                    petUsed++
                    petState = 2
                    petAt = now
                    petReward = false
                    clickRef(540.0, 1883.0, "daily : Pet Skill : ${petLabels[petIdx]} : Utiliser («${title.trim().take(30)}»)")
                } else {
                    log("daily : Pet Skill : ${petLabels[petIdx]} : déjà active ou en recharge, on saute")
                    petNext()
                }
            }
            else -> {
                if (petReward || now - petAt > 2_800L) petNext()
            }
        }
    }

    // ---------- Banquet Gastronomique (préambule du Daily, même plage horaire que le bonus de collecte) ----------

    private var banqueTries = 0
    private var banqueTries2 = 0
    private var banquePlus = 0

    /**
     * Ville > avatar en haut à gauche > « Profil du Chef » > « + » à côté de l'endurance > fenêtre « Obtenir plus » :
     * si le bouton vert « Récupérer » du Banquet Gastronomique (première ligne) ou de l'Étreinte Réconfortante (deuxième)
     * est là, on le touche (pas d'écran de récompense) ; sinon (minuteur gris) rien > croix de la fenêtre > flèche bleue du profil > ville.
     */
    private fun detectBanquet(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Banquet Gastronomique abandonné (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyAttempts["banquet"] = 2
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        // fenêtre « Obtenir plus » : bandeau d'en-tête beige ; profil : bouton « + » turquoise sous l'endurance
        val window = colorRect(rgb, 60, 440, 960, 500, Scalar(10.0, 20.0, 150.0), Scalar(30.0, 90.0, 255.0)) >= 0.5
        val profile = !window && colorRect(rgb, 320, 2060, 385, 2120, Scalar(85.0, 120.0, 170.0), Scalar(105.0, 255.0, 255.0)) >= 0.3
        when {
            window -> {
                dailyIdleAt = now
                if (dailyStep >= 2) return // croix déjà touchée : la fenêtre se ferme
                val green = colorRect(rgb, 740, 825, 1000, 925, Scalar(40.0, 120.0, 120.0), Scalar(85.0, 255.0, 255.0))
                if (green >= 0.4 && banqueTries < 2) {
                    banqueTries++
                    dailyStepAt = now
                    clickRef(868.0, 873.0, "daily : Banquet Gastronomique : Récupérer (essai $banqueTries)")
                    return
                }
                // deuxième ligne : « Étreinte Réconfortante » (même bouton vert quand elle est prête)
                val green2 = colorRect(rgb, 740, 1060, 1000, 1160, Scalar(40.0, 120.0, 120.0), Scalar(85.0, 255.0, 255.0))
                if (green2 >= 0.4 && banqueTries2 < 2) {
                    banqueTries2++
                    dailyStepAt = now
                    clickRef(868.0, 1108.0, "daily : Banquet Gastronomique : Étreinte Réconfortante, Récupérer (essai $banqueTries2)")
                    return
                }
                if (now - dailyStepAt < 2_000L) return // fenêtre qui s'anime / bouton qui se grise : on relit
                log("daily : Banquet Gastronomique : ${if (banqueTries + banqueTries2 > 0) "récupéré (banquet $banqueTries, étreinte $banqueTries2)" else "rien à récupérer (minuteurs gris)"}, fermeture")
                dailyStep = 2
                dailyStepAt = now
                clickRef(1002.0, 467.0, "daily : Banquet Gastronomique : fermeture de la fenêtre (croix)")
            }
            profile -> {
                dailyIdleAt = now
                if (dailyStep >= 2) {
                    if (now - dailyStepAt < 800L) return
                    lastClick = now
                    backArrow()
                    dailyAttempts["banquet"] = 2
                    dailyTaskDone("Banquet Gastronomique")
                    return
                }
                if (dailyStep == 1 && now - dailyStepAt < 2_500L) return // le « + » vient d'être touché : la fenêtre arrive
                if (++banquePlus > 3) {
                    log("daily : Banquet Gastronomique : la fenêtre ne s'ouvre pas, abandon")
                    lastClick = now
                    backArrow()
                    dailyAttempts["banquet"] = 2
                    dailyTaskDone("Banquet Gastronomique")
                    return
                }
                dailyStep = 1
                dailyStepAt = now
                clickRef(350.0, 2090.0, "daily : Banquet Gastronomique : clic sur le « + » de l'endurance")
            }
            else -> {}
        }
    }

    // ---------- Bonus de collecte (premier préambule du Daily) ----------

    private var bonusBox = false
    private var bonusBuys = 0
    private var bonusForce = true
    private var bonusFrom = 6
    private var bonusTo = 9

    /** Heure du téléphone dans la plage réglée (de bonusFrom h 00 à bonusTo h 00, minuit franchi si De > À). */
    private fun bonusWindowNow(): Boolean {
        val c = java.util.Calendar.getInstance()
        val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val a = bonusFrom * 60
        val b = bonusTo * 60
        return if (a <= b) m in a..b else (m >= a || m <= b)
    }
    private var bonusAsk = 0
    private var bonusBarAt = 0L
    private var bonusConfAt = 0L

    /** Corrélation de l'icône « chariot » (bonus de collecte actif) dans la rangée d'icônes à droite de la flèche, en haut à gauche. 1.0 si on ne peut pas mesurer. */
    private fun bonusIconScore(screen: Mat): Double {
        val t = farm["bonus_collecte"] ?: return 1.0
        val f = screen.cols() / 1080.0
        val x0 = (90 * f).toInt()
        val y0 = (222 * f).toInt()
        val x1 = minOf((720 * f).toInt(), screen.cols())
        val y1 = minOf((300 * f).toInt(), screen.rows())
        if (x1 - x0 < t.cols() || y1 - y0 < t.rows()) return 1.0
        val sub = Mat(screen, Rect(x0, y0, x1 - x0, y1 - y0))
        return matchBest(sub, t)?.maxVal ?: 1.0
    }

    /** Texte blanc (titres, prix sur bouton orange) : seuils hauts d'abord. */
    private fun ocrRefWhite(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int): String {
        val sc = rgb.cols() / 1080.0
        val ax = (x0 * sc).toInt()
        val ay = (y0 * sc).toInt()
        val bx = minOf((x1 * sc).toInt(), rgb.cols())
        val by = minOf((y1 * sc).toInt(), rgb.rows())
        if (bx <= ax || by <= ay) return ""
        val crop = Mat(rgb, Rect(ax, ay, bx - ax, by - ay))
        var t = ocrText(crop, false, 225)
        if (t.isBlank()) t = ocrText(crop, false, 200)
        return t.lowercase()
    }

    /** Temps restant affiché sur la barre verte (« 23:59:57 », « 1:45:10 », « 59:30 », ou « 1j … »), en heures ; null si illisible. */
    private fun bonusBarHours(rgb: Mat): Double? {
        val raw = ocrRefWhite(rgb, 380, 982, 820, 1028)
        val t = raw.replace("\n", " ").replace(" ", "").replace('o', '0').replace('O', '0').replace('l', '1').replace('i', '1')
        if (t.isEmpty()) return null
        if (Regex("\\d\\s*[jd]").containsMatchIn(t)) return 24.0 // un jour ou plus
        val m3 = Regex("(\\d{1,3})[:.;,](\\d{2})[:.;,](\\d{2})").find(t)
        if (m3 != null) return m3.groupValues[1].toInt() + m3.groupValues[2].toInt() / 60.0
        val m2 = Regex("(\\d{1,2})[:.;,](\\d{2})").find(t)
        if (m2 != null) return 0.0 // « MM:SS » : moins d'une heure
        val d = t.filter { it.isDigit() }
        return when (d.length) {
            6 -> d.substring(0, 2).toInt() + d.substring(2, 4).toInt() / 60.0
            5 -> d.substring(0, 1).toInt() + d.substring(1, 3).toInt() / 60.0
            else -> null
        }
    }

    private fun bonusDone(msg: String) {
        log("daily : Bonus de collecte : $msg")
        lastClick = SystemClock.uptimeMillis()
        backArrow()
        dailyAttempts["bonus"] = 2
        dailyTaskDone("Bonus de collecte")
    }

    /**
     * Bonus de collecte : flèche en haut à gauche > « Revenu de la Ville » > onglet Expansion > ligne « Vitesse de Collecte »
     * (si elle montre un temps et sa barre verte : déjà actif, on ne fait rien) > « Utiliser » du boost 24 h > « Obtenir plus »
     * > « Acheter 600 » > fenêtre « Acheter » : case « Ne plus afficher pour aujourd'hui », puis gros bouton jaune 600 >
     * le boost s'active tout seul > flèche bleue.
     */
    private fun detectBonus(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Bonus de collecte abandonné (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        val orangeLo = Scalar(10.0, 140.0, 190.0)
        val orangeHi = Scalar(32.0, 255.0, 255.0)
        // 0) fenêtre « Confirmer » : « Activer un autre bonus de ce type remplacera ton bonus actuel. Activer ? »
        //    Annuler (orange, à gauche) / Confirmer (turquoise, à droite) : on confirme.
        if (colorRect(rgb, 575, 1365, 945, 1470, Scalar(80.0, 100.0, 150.0), Scalar(105.0, 255.0, 255.0)) >= 0.5 &&
            colorRect(rgb, 140, 1365, 505, 1470, Scalar(5.0, 150.0, 190.0), Scalar(20.0, 255.0, 255.0)) >= 0.5 &&
            colorRect(rgb, 100, 925, 950, 985, Scalar(10.0, 20.0, 150.0), Scalar(30.0, 90.0, 255.0)) >= 0.5
        ) {
            dailyIdleAt = now
            if (bonusConfAt == 0L) bonusConfAt = now
            val msg = ocrRef(rgb, 340, 1090, 950, 1260, true)
            val known = "bonus" in msg || "remplac" in msg || "activer" in msg
            if (!known && now - bonusConfAt < 3_000L) return // texte pas encore lisible : on relit
            bonusConfAt = 0L
            dailyActAt = now
            clickRef(757.0, 1417.0, "daily : Bonus de collecte : fenêtre « Confirmer » (remplace le bonus actuel), Confirmer")
            return
        }
        bonusConfAt = 0L
        // 1) fenêtre « Acheter » (confirmation) : gros bouton jaune au centre
        if (colorRect(rgb, 335, 1385, 745, 1485, orangeLo, orangeHi) >= 0.4) {
            dailyIdleAt = now
            val msg = ocrRef(rgb, 150, 1075, 930, 1195, true)
            val price = ocrRefWhite(rgb, 500, 1395, 720, 1475)
            val digits = (msg + " " + price).filter { it.isDigit() || it == ' ' }
            val is600 = "600" in price.filter { it.isDigit() } || "600" in msg.filter { it.isDigit() }
            if (!is600 && now - dailyStepAt < 4_000L) return // prix pas encore lisible : on relit
            if (!is600) {
                log("daily : Bonus de collecte : prix 600 non confirmé dans la fenêtre Acheter (lu «${digits.trim()}»), fermeture")
                clickRef(955.0, 922.0, "daily : Bonus de collecte : fenêtre fermée sans acheter")
                dailyAttempts["bonus"] = 2
                dailyState = "OPEN"
                return
            }
            if (!bonusBox) {
                bonusBox = true
                dailyActAt = now
                clickRef(275.0, 1318.0, "daily : Bonus de collecte : case « Ne plus afficher pour aujourd'hui »")
                return
            }
            if (bonusBuys > 0 && now - dailyActAt < 3_500L) return // achat fait : la fenêtre peut rester un instant, on ne rachète pas
            if (now - dailyActAt < 500L) return
            if (bonusBuys >= 2) return
            bonusBuys++
            dailyActAt = now
            clickRef(540.0, 1435.0, "daily : Bonus de collecte : achat 600 gemmes (n° $bonusBuys)")
            return
        }
        // 2) fenêtre « Obtenir plus » : bouton « Acheter 600 »
        if (colorRect(rgb, 745, 795, 995, 880, orangeLo, orangeHi) >= 0.35) {
            dailyIdleAt = now
            val name = ocrRef(rgb, 255, 565, 880, 625, true)
            val price = ocrRefWhite(rgb, 800, 840, 945, 885).filter { it.isDigit() }
            if (price.isNotEmpty() && price != "600") {
                log("daily : Bonus de collecte : prix lu $price (600 attendu), fenêtre fermée sans acheter")
                clickRef(1003.0, 470.0, "daily : Bonus de collecte : fenêtre Obtenir plus fermée")
                dailyAttempts["bonus"] = 2
                return
            }
            if (price != "600" && now - dailyStepAt < 4_000L) return
            if ("24" !in name && now - dailyStepAt < 4_000L) return
            if (bonusAsk > 0 && now - dailyActAt < 3_500L) return // déjà touché : on laisse la fenêtre suivante s'afficher
            if (bonusAsk >= 2) { bonusDone("Acheter touché 2 fois sans effet, abandon"); return }
            bonusAsk++
            dailyActAt = now
            clickRef(870.0, 837.0, "daily : Bonus de collecte : Acheter (Obtenir plus)")
            dailyStepAt = now
            return
        }
        // 3) écran « Revenu de la Ville »
        val title = ocrRef(rgb, 130, 90, 760, 200)
        if ("revenu" in title || "ville" in title) {
            dailyIdleAt = now
            val expSel = colorRect(rgb, 600, 215, 1000, 235, Scalar(0.0, 0.0, 200.0), Scalar(179.0, 60.0, 255.0)) >= 0.6
            if (!expSel) {
                if (dailyBtn++ < 4) clickRef(797.0, 262.0, "daily : Bonus de collecte : onglet Expansion")
                return
            }
            val bar = colorRect(rgb, 260, 985, 930, 1025, Scalar(40.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0)) >= 0.4
            val use24 = colorRect(rgb, 775, 1370, 1000, 1455, Scalar(80.0, 100.0, 150.0), Scalar(105.0, 255.0, 255.0)) >= 0.3
            if (bar) {
                if (bonusAsk > 0 || bonusBuys > 0) { bonusDone("achat fait, boost actif (barre verte), flèche retour"); return }
                val hrs = bonusBarHours(rgb)
                if (hrs == null) {
                    if (bonusBarAt == 0L) bonusBarAt = now
                    if (now - bonusBarAt < 3_000L) return // temps pas encore lisible : on relit
                    bonusDone("temps de la barre verte illisible : on ne fait rien, flèche retour")
                    return
                }
                if (hrs >= 2.0) { bonusDone("boost actif, encore ${"%.1f".format(hrs)} h (2 h ou plus) : on ne fait rien, flèche retour"); return }
                if (dailyGrey == 0 && now - dailyActAt > 1_500L) log("daily : Bonus de collecte : il reste ${"%.1f".format(hrs)} h (moins de 2 h), on achète quand même")
            }
            if (use24) {
                if (bonusBuys > 0 && now - dailyActAt < 3_000L) return // achat fait : on attend l'activation
                val row = ocrRefWhite(rgb, 255, 1340, 760, 1490)
                if ("24" !in row && now - dailyStepAt < 4_000L) return
                if (dailyGrey++ >= 3) { bonusDone("« Utiliser » 24 h touché 3 fois sans effet, abandon"); return }
                dailyActAt = now
                clickRef(885.0, 1415.0, "daily : Bonus de collecte : Utiliser (boost 24 h)")
                dailyStepAt = now
                return
            }
            // ligne repliée : on la touche pour afficher les boosts
            val row = ocrRef(rgb, 240, 850, 940, 905, true)
            if ("vitesse" in row || "collecte" in row) {
                if (dailyTries++ < 4) clickRef(500.0, 935.0, "daily : Bonus de collecte : ligne Vitesse de Collecte")
                else bonusDone("ligne Vitesse de Collecte sans effet, abandon")
            }
        }
    }

    /**
     * VIP : écran VIP > coffre en haut à droite (s'il a son point rouge) > écran « Récompenses » (appui dans le vide) >
     * « Récupérer » vert du Pack Quotidien Gratuit > écran « Récompenses » (bandeau doré) > flèche bleue en haut à gauche.
     */
    private fun detectVip(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : VIP abandonné (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        // Écrans « Récompenses » : « Appuie pour continuer » (EXP VIP) ou « Appuie n'importe où pour quitter » (pack)
        val rw = ocrRef(rgb, 200, 2150, 880, 2260)
        if ("continuer" in rw) {
            dailyIdleAt = now
            dailyActAt = now
            clickRef(660.0, 520.0, "daily : VIP : écran Récompenses (EXP VIP), appui dans le vide")
            return
        }
        if ("appuie" in rw || "quitter" in rw) {
            dailyIdleAt = now
            dailyActAt = now
            tapRewards("daily : VIP : écran Récompenses, appui sur le bandeau")
            return
        }
        // Écran VIP (titre « VIP » en haut à gauche, ou « Niveau Actuel »)
        val title = ocrRef(rgb, 120, 95, 330, 185)
        val head = if ("vip" in title) title else ocrRef(rgb, 320, 270, 760, 330, true)
        if ("vip" !in title && "niveau" !in head) return
        dailyIdleAt = now
        if (now - dailyActAt < 1_500L) return // on laisse l'écran de récompenses apparaître
        if (dailyBtn < 2 && redDotAt(rgb, 982.0, 462.0)) {
            dailyBtn++
            dailyActAt = now
            clickRef(938.0, 505.0, "daily : VIP : coffre (point rouge)")
            return
        }
        if (dailyGrey < 2 && colorFrac(rgb, 870.0, 1655.0, Scalar(55.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0), 30) >= 0.3) {
            dailyGrey++
            dailyActAt = now
            clickRef(870.0, 1655.0, "daily : VIP : Récupérer (Pack Quotidien Gratuit)")
            return
        }
        dailyAttempts["vip"] = 2
        lastClick = now
        backArrow()
        log("daily : VIP : terminé (coffre $dailyBtn, récupérer $dailyGrey), flèche retour")
        dailyStep = 5
        dailyTaskDone("VIP")
    }


    // ---------- Marchand nomade à la demande (menu « Marchand nomade ») ----------

    private var marchandFinished = false
    private var soloNavAt = 0L
    private var soloNavTries = 0
    private var soloUnknownAt = 0L
    private var soloBackTries = 0

    private fun resetMarchandSolo() {
        resetShop()
        dailyAttempts.clear()
        dailyState = "MARCHAND"; dailyStep = 0; dailyBtn = 0; dailyGrey = 0
        dailyIdleAt = SystemClock.uptimeMillis()
        marchandFinished = false
        soloNavAt = 0L; soloNavTries = 0; soloUnknownAt = 0L; soloBackTries = 0
    }

    private fun loadMarchand() {
        resetMarchandSolo()
        log("marchand nomade : démarrage (à la demande)")
    }

    /** Ouvre le magasin depuis la ville (icône « Magasin », sans exiger de point rouge) puis lance la routine d'achats. */
    private fun detectMarchandSolo(rgb: Mat) {
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < minOf(cooldown, 450L) || marchandFinished) return
        val dlg = colorRect(rgb, 330, 1435, 750, 1535, Scalar(8.0, 150.0, 180.0), Scalar(25.0, 255.0, 255.0)) >= 0.35
        var tab = ocrRef(rgb, 0, 2215, 300, 2335, true)
        if ("march" !in tab && "nomade" !in tab) tab = ocrRef(rgb, 0, 2215, 300, 2335, false)
        val inShop = dlg || "march" in tab || "nomade" in tab || dailyStep == 6
        if (inShop) {
            soloUnknownAt = 0L
            soloBackTries = 0
            dailyIdleAt = now
            detectMarchand(rgb, now)
            return
        }
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        val city = "magasin" in bar && listOf("conqu", "héros", "heros", "alliance", "monde", "sac").count { it in bar } >= 1
        if (city) {
            soloUnknownAt = 0L
            if (now - soloNavAt > 4_000L) {
                if (++soloNavTries > 5) {
                    marchandFinished = true
                    log("marchand nomade : impossible d'ouvrir le magasin (5 essais)")
                    main.post { pauseBot("Marchand nomade : magasin introuvable") }
                    return
                }
                soloNavAt = now
                resetShop()
                dailyStep = 0
                dailyIdleAt = now
                clickRef(630.0, 2255.0, "marchand nomade : ouverture du magasin (icône Magasin)")
            }
            return
        }
        // écran inconnu : on laisse 10 s, puis on essaie la flèche de retour
        if (soloUnknownAt == 0L) soloUnknownAt = now
        if (now - soloUnknownAt > 10_000L) {
            soloUnknownAt = now
            if (++soloBackTries > 4) {
                marchandFinished = true
                log("marchand nomade : écran inconnu, arrêt")
                main.post { pauseBot("Marchand nomade : écran inconnu") }
                return
            }
            lastClick = now
            backArrow()
            log("marchand nomade : écran inconnu, flèche retour")
        }
    }

    // ---------- Soigner les troupes (menu « Soigner les troupes », à la demande) ----------

    private var healFinished = false
    private var healUnknownAt = 0L
    private var healBackTries = 0
    private var healNoIconSince = 0L
    private var healIconTries = 0
    private var healIconAt = 0L
    private var healPopupAt = 0L
    private var healIdleSince = 0L
    private var healTypeTries = 0
    private var healKbWaitUntil = 0L    // le clavier met un moment à s'ouvrir : on ne touche à rien avant
    private var healSettleUntil = 0L    // après « OK » : le clavier se ferme et la valeur se met à jour
    private var healMeasured = false
    private var healSmallSince = 0L     // il ne reste que quelques minutes : la prochaine aide des alliés comblera
    private var healBackLeft = 0
    private var healTypeIdx = 0
    private var healWant = ""
    private var healKbClicks = 0
    private var healSoignerAt = 0L
    private var healSoignerTries = 0
    private var healNAtClick = -1
    private var healAideAt = 0L
    private var healAideTries = 0
    private var healHealSince = 0L
    private var healBatches = 0
    private var healLastN = -1
    private var healZeroSeen = 0
    // Recherche du bon montant : « montant de départ », puis par centaines, puis par dizaines (voir healLearn).
    private var healAmt = 500
    private var healStep = 100
    private var healStable = 0 // lots réussis de suite au montant trouvé
    private var healOkMax = -1
    private var healBadMin = Int.MAX_VALUE
    private var healUsed = 0
    private var healBatchBad = false
    private var healAccelTries = 0
    private var healAccelAt = 0L
    private var healWinAt = 0L
    private var healWinTries = 0
    private var healChkClicked = false
    private val healGreenCk = Scalar(55.0, 100.0, 100.0)
    // Calcul du montant exact : durée annoncée sur « Soigner », temps retiré par les alliés, proportionnalité.
    private var healT0 = -1          // durée du lot (s), lue sur le bouton « Soigner » avant le clic
    private var healSampT = -1       // dernier temps restant lu pendant la guérison (s)
    private var healSampAt = 0L
    private var healCalc = -1        // montant calculé à la fin de l'aide des alliés (-1 : pas de mesure)
    private var healRow = 0 // ligne de troupes pilotée (0 = celle du haut) : celle qui a vraiment des blessés à soigner
    private var healFieldBelief = -1 // valeur que nous avons tapée dans le champ (le jeu la garde d'un lot à l'autre)

    private val healGreenLo = Scalar(55.0, 120.0, 100.0)
    private val healGreenHi = Scalar(80.0, 255.0, 255.0)
    private val healWhiteLo = Scalar(0.0, 0.0, 225.0)
    private val healWhiteHi = Scalar(180.0, 50.0, 255.0)
    private val healTealLo = Scalar(85.0, 120.0, 150.0)
    private val healTealHi = Scalar(105.0, 255.0, 255.0)
    private val healOrangeLo = Scalar(8.0, 150.0, 180.0)
    private val healOrangeHi = Scalar(25.0, 255.0, 255.0)
    private val healDarkLo = Scalar(0.0, 0.0, 0.0)
    private val healDarkHi = Scalar(180.0, 255.0, 70.0)
    private val healBarLo = Scalar(0.0, 0.0, 235.0)
    private val healBarHi = Scalar(180.0, 30.0, 255.0)

    // Pavé numérique du clavier (coordonnées de référence 1080 px).
    private val healKeys = mapOf(
        '1' to Pair(110.0, 1620.0), '2' to Pair(325.0, 1620.0), '3' to Pair(540.0, 1620.0),
        '4' to Pair(110.0, 1785.0), '5' to Pair(325.0, 1785.0), '6' to Pair(540.0, 1785.0),
        '7' to Pair(110.0, 1945.0), '8' to Pair(325.0, 1945.0), '9' to Pair(540.0, 1945.0),
        '0' to Pair(325.0, 2110.0)
    )

    private fun resetHeal() {
        healFinished = false; healUnknownAt = 0L; healBackTries = 0; healNoIconSince = 0L; healIconTries = 0; healIconAt = 0L
        healPopupAt = 0L; healIdleSince = 0L; healTypeTries = 0; healKbWaitUntil = 0L; healSettleUntil = 0L; healMeasured = false; healSmallSince = 0L; healBackLeft = 0; healTypeIdx = 0; healWant = ""; healKbClicks = 0
        healSoignerAt = 0L; healSoignerTries = 0; healNAtClick = -1; healAideAt = 0L; healAideTries = 0; healHealSince = 0L
        healBatches = 0; healLastN = -1; healZeroSeen = 0
        healAmt = getSharedPreferences("cfg", MODE_PRIVATE).getInt("healQty", 500).coerceIn(10, 173_400)
        healStep = 100; healStable = 0; healOkMax = -1; healBadMin = Int.MAX_VALUE; healUsed = 0; healBatchBad = false
        healAccelTries = 0; healAccelAt = 0L; healWinAt = 0L; healWinTries = 0; healChkClicked = false
        healT0 = -1; healSampT = -1; healSampAt = 0L; healCalc = -1; healFieldBelief = -1; healRow = 0
    }

    private fun loadHeal() {
        loadAsset("heal/icone.png")?.let { farm["heal_icone"] = it }
        resetHeal()
        log("soigner : démarrage (à la demande), montant de départ $healAmt")
    }

    private fun healStop(msg: String) {
        healFinished = true
        log("soigner : $msg")
        main.post { pauseBot("Soigner les troupes : $msg") }
    }

    /** Croix verte (gabarit) confirmée par la couleur : un carré vert avec une croix blanche. */
    private fun healIconPoint(screen: Mat, rgb: Mat): Pair<Float, Float>? {
        val t = farm["heal_icone"] ?: return null
        val mm = farmMatch(screen, "heal_icone", 0.8) ?: return null
        val x = mm.maxLoc.x.toInt()
        val y = mm.maxLoc.y.toInt()
        if (x < 0 || y < 0 || x + t.cols() > rgb.cols() || y + t.rows() > rgb.rows()) return null
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(x, y, t.cols(), t.rows())), hsv, Imgproc.COLOR_RGB2HSV)
        val g = Mat()
        val wm = Mat()
        Core.inRange(hsv, healGreenLo, healGreenHi, g)
        Core.inRange(hsv, healWhiteLo, healWhiteHi, wm)
        val area = (t.cols() * t.rows()).toDouble()
        val gf = Core.countNonZero(g) / area
        val wf = Core.countNonZero(wm) / area
        hsv.release(); g.release(); wm.release()
        if (gf < 0.5 || wf < 0.12) return null
        return farmCenter("heal_icone", mm)
    }

    /** Nombre de blessés restants : « Gravement Blessé(s) 2 430/173 400 » -> 2430 ; null si illisible (bandeau de message par-dessus). */
    private fun healRemaining(rgb: Mat): Int? {
        val t = ocrRef(rgb, 235, 815, 720, 885, true)
        val i = t.indexOf('/')
        if (i < 0) return null
        val d = t.substring(0, i).filter { it.isDigit() }
        return if (d.isEmpty() || d.length > 7) null else d.toIntOrNull()
    }

    /** Quantité affichée dans le champ à droite de la jauge (500, 462…). */
    private fun healField(rgb: Mat, row: Int = 0): Int? {
        val dy = 213 * row
        val d = ocrRefWhite(rgb, 735, 1000 + dy, 895, 1055 + dy).filter { it.isDigit() }
        return if (d.isEmpty() || d.length > 7) null else d.toIntOrNull()
    }

    private fun detectHeal(screen: Mat, rgb: Mat) {
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < minOf(cooldown, 450L) || healFinished) return

        // 1) clavier numérique ouvert (barre blanche de saisie + touches sombres)
        if (colorRect(rgb, 0, 1560, 1080, 2150, healDarkLo, healDarkHi) >= 0.6 &&
            colorRect(rgb, 0, 1390, 700, 1530, healBarLo, healBarHi) >= 0.8
        ) {
            healUnknownAt = 0L
            healKeyboard()
            return
        }

        // 1 bis) fenêtres des accélérateurs (« Vitesse de soins des soldats ») : sélection, puis confirmation
        val winOrange = colorRect(rgb, 115, 1490, 525, 1610, healOrangeLo, healOrangeHi)
        val w7 = colorRect(rgb, 333, 1650, 748, 1765, healTealLo, healTealHi) >= 0.5 && winOrange >= 0.5
        val w8 = !w7 && winOrange < 0.2 && colorRect(rgb, 330, 1680, 750, 1795, healTealLo, healTealHi) >= 0.6
        if (w7 || w8) {
            val t = ocrRefWhite(rgb, 60, 600, 1020, 740)
            if ("vites" in t || "soins" in t) {
                healAccelWindow(rgb, now, w7)
                return
            }
        }

        // 2) fenêtre « Soigner les Blessés » : Terminer (orange) au milieu et Soigner / Aide (turquoise) à droite
        if (colorRect(rgb, 325, 1775, 600, 1880, healOrangeLo, healOrangeHi) >= 0.5 &&
            colorRect(rgb, 640, 1775, 915, 1880, healTealLo, healTealHi) >= 0.4
        ) {
            var title = now - healPopupAt <= 15_000L
            if (!title) {
                val t = ocrRef(rgb, 240, 680, 840, 760, true)
                title = "bless" in t || "soigner" in t || "wounded" in t || "heal" in t
            }
            if (title) {
                healPopup(rgb, now)
                return
            }
        }

        // 3) ville : croix verte des blessés (en bas à droite)
        val icon = healIconPoint(screen, rgb)
        if (icon != null) {
            healNoIconSince = 0L; healUnknownAt = 0L; healBackTries = 0
            if (now - healIconAt >= 3_000L) {
                if (++healIconTries > 5) { healStop("la croix verte ne s'ouvre pas (5 essais), arrêt"); return }
                healIconAt = now
                farmClick(icon.first, icon.second, "soigner : croix verte touchée (essai $healIconTries)")
            }
            return
        }
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        val city = "magasin" in bar && listOf("conqu", "héros", "heros", "alliance", "monde", "sac").count { it in bar } >= 1
        if (city) {
            healUnknownAt = 0L; healBackTries = 0
            if (healNoIconSince == 0L) healNoIconSince = now
            if (now - healNoIconSince >= 4_000L) {
                healStop(if (healBatches > 0 || healLastN == 0) "tous les blessés sont soignés" else "aucun blessé à soigner (croix verte absente)")
            }
            return
        }

        // écran inconnu : on laisse 10 s, puis on essaie la flèche de retour
        if (healUnknownAt == 0L) healUnknownAt = now
        if (now - healUnknownAt > 10_000L) {
            healUnknownAt = now
            if (++healBackTries > 4) { healStop("écran inconnu, arrêt"); return }
            lastClick = now
            backArrow()
            log("soigner : écran inconnu, flèche retour")
        }
    }

    private fun healPopup(rgb: Mat, now: Long) {
        healPopupAt = now
        healUnknownAt = 0L; healBackTries = 0; healNoIconSince = 0L; healIconTries = 0
        val leftTeal = colorRect(rgb, 120, 1775, 285, 1880, healTealLo, healTealHi)
        val leftOrange = colorRect(rgb, 120, 1775, 285, 1880, healOrangeLo, healOrangeHi)
        if (leftOrange >= 0.3) {
            // guérison en cours : « Annuler soins » (orange) à gauche, « Terminer » (gemmes) au milieu : jamais touchés.
            // Le bouton de droite est « Aide » ; une fois l'aide des alliés arrivée, s'il reste du temps, il devient « Accélér. » (même place).
            healIdleSince = 0L
            if (healHealSince == 0L) {
                healHealSince = now; healAideTries = 0; healSoignerTries = 0; healTypeTries = 0; healMeasured = false; healSmallSince = 0L
                healAccelTries = 0; healWinTries = 0; healChkClicked = false; healSampAt = 0L; healSampT = -1; healCalc = -1
            }
            if (healAideTries < 1) {
                healAideTries++
                healAideAt = now
                clickRef(775.0, 1825.0, "soigner : guérison en cours : « Aide »")
                return
            }
            // Dès 3 s après « Aide », on suit le temps restant : quand il ne baisse plus plus vite que le temps qui passe,
            // les alliés ont fini d'aider. S'il reste du temps (même quelques minutes), le lot n'a pas été soigné du premier coup :
            // le montant était trop élevé. On mesure ce que l'aide a retiré (pour calculer le bon montant), puis on accélère
            // tout de suite (« Accélér. », même place que « Aide ») pour revenir à « Soigner » sans attendre.
            val sinceAide = now - healAideAt
            if (!healMeasured) {
                // Une seule lecture, tout de suite après « Aide » (l'aide automatique des alliés est déjà appliquée) : on accélère aussitôt.
                if (sinceAide < 800L) return
                val rem = healParseTime(ocrRefWhite(rgb, 600, 1668, 845, 1718))
                if (rem == null && sinceAide < 3_000L) return // illisible : on réessaie un instant
                if (rem != null) {
                    if (healT0 <= 0) healT0 = rem + ((now - healSoignerAt) / 1000.0).toInt()
                    val removed = healT0 - (now - healSoignerAt) / 1000.0 - rem
                    if (removed > 0 && healUsed > 0) {
                        healCalc = maxOf(10, (healUsed * maxOf(0.0, removed - 3.0) / healT0).toInt()) // au plus juste (3 s de marge pour la lecture)
                        log("soigner : temps restant $rem s juste après l'aide : durée du lot ${healT0} s, retiré ${removed.toInt()} s : montant calculé $healCalc")
                    }
                } else {
                    log("soigner : temps restant illisible : accélération sans mesure")
                }
                healSampAt = 0L
                healMeasured = true
                healBatchBad = true
            }
            if (now - healAccelAt < 3_500L) return
            if (++healAccelTries > 3) { healStop("la guérison n'avance plus (plus d'accélérateurs ?), arrêt"); return }
            healAccelAt = now
            clickRef(775.0, 1825.0, "soigner : il reste du temps après l'aide des alliés : bouton de droite « Accélér. » (essai $healAccelTries)")
            return
        }
        if (leftTeal < 0.3) return // bouton de gauche masqué (animation) : on attend
        if (healHealSince != 0L) {
            healHealSince = 0L
            healBatches++
            val bad = healBatchBad
            healBatchBad = false
            log("soigner : lot $healBatches terminé ($healUsed soldats${if (bad) ", accélérateurs utilisés" else ", aide des alliés suffisante"})")
            healLearn(healUsed, bad)
        }
        if (healIdleSince == 0L) healIdleSince = now
        val n = healRemaining(rgb)
        if (n == null && now - healIdleSince < 3_000L) return // bandeau de message sur le compteur : on laisse passer
        if (n != null) {
            healLastN = n
            if (healNAtClick in 0..Int.MAX_VALUE && n < healNAtClick) healSoignerTries = 0 // ça avance
        }
        if (n == 0) {
            if (++healZeroSeen >= 2) {
                clickRef(897.0, 718.0, "soigner : plus aucun blessé : fenêtre fermée")
                healStop("tous les blessés sont soignés")
            }
            return
        }
        healZeroSeen = 0
        val want = if (n != null) minOf(healAmt, n) else healAmt
        if (now < healSettleUntil || now < healKbWaitUntil) return // clavier en train de s'ouvrir ou de se fermer : on laisse l'écran se stabiliser
        // Plusieurs lignes (infanterie, cavalerie, archers) : seule la ligne qui a des blessés compte (les lignes vides restent à 0 et le temps
        // du lot ne dépend que d'elle). On pilote donc la ligne dont le champ est le plus grand, pas forcément celle du haut.
        val rows = (0..2).map { healField(rgb, it) }
        val own = rows[healRow]
        if (!(healFieldBelief > 0 && (own == null || healFieldSane(own, healFieldBelief)))) {
            var best = healRow
            var bestV = own ?: -1
            for (r in 0..2) { val v = rows[r] ?: continue; if (v > bestV) { best = r; bestV = v } }
            if (best != healRow) {
                log("soigner : ligne pilotée : ${best + 1} (champs lus ${rows.joinToString(" | ") { it?.toString() ?: "-" }})")
                healRow = best; healFieldBelief = -1
            }
        }
        val ocrField = rows[healRow]
        // Le jeu garde la quantité d'un lot à l'autre : on se fie à ce que nous y avons tapé ; la lecture peut perdre un chiffre (« 560 » lu « 60 »).
        val known = healFieldBelief > 0
        val field: Int? = when {
            ocrField == null -> if (known) healFieldBelief else null
            known && healFieldSane(ocrField, healFieldBelief) -> healFieldBelief
            else -> ocrField
        }
        if (healTypeTries < 3 && field != null && field != want) {
            healTypeTries++
            healKbWaitUntil = now + 2_500L
            healWant = want.toString()
            val cur = field.toString()
            if (known && field == healFieldBelief) {
                // valeur certaine : on n'efface et ne retape que la fin qui change
                var k = 0
                while (k < cur.length && k < healWant.length && cur[k] == healWant[k]) k++
                healBackLeft = cur.length - k
                healTypeIdx = k
            } else {
                healBackLeft = cur.length + 1
                healTypeIdx = 0
            }
            healFieldBelief = want
            healKbClicks = 0
            clickRef(812.0, 1030.0 + 213.0 * healRow, "soigner : quantité $field -> $want : champ touché (ligne ${healRow + 1})")
            return
        }
        if (field != null && field == want) healFieldBelief = want
        if (now - healSoignerAt < 3_000L) return
        if (++healSoignerTries > 3) { healStop("« Soigner » sans effet (ressources insuffisantes ?), arrêt"); return }
        healSoignerAt = now
        healNAtClick = n ?: -1
        healUsed = field ?: want
        val dur = ocrRefWhite(rgb, 700, 1815, 915, 1885).replace("\n", " ").trim()
        healT0 = healParseTime(dur) ?: -1
        // Garde-fou : la durée du lot est proportionnelle au nombre de soldats réellement soignés (≈ 3,004 s chacun). Si elle est
        // bien plus courte que le champ le laisse croire (champ plafonné, mauvaise ligne), on se fie à la durée.
        if (healT0 > 0) {
            val eff = (healT0 / 3.004).toInt()
            if (eff < healUsed * 0.8) {
                log("soigner : le champ dit $healUsed mais la durée correspond à $eff soldats : on retient $eff")
                healUsed = maxOf(1, eff)
            }
        }
        clickRef(775.0, 1850.0, "soigner : lot ${healBatches + 1} : « Soigner » (ligne ${healRow + 1} : $healUsed, champs ${rows.joinToString(" | ") { it?.toString() ?: "-" }}, durée « $dur », restants ${n ?: "?"}, montant visé $healAmt)")
    }

    /** Lecture du champ plausible : identique, ou avec un chiffre perdu (« 60 » pour 560). */
    private fun healFieldSane(ocr: Int, belief: Int): Boolean {
        val a = ocr.toString()
        val b = belief.toString()
        if (a == b) return true
        if (a.length != b.length - 1) return false
        var i = 0
        for (c in b) if (i < a.length && a[i] == c) i++
        return i == a.length
    }

    /** « 01:11:48 » -> 4308 s ; « 28:53 » -> 1733 s ; null si illisible. */
    private fun healParseTime(raw: String): Int? {
        val t = raw.replace("\n", " ").replace(" ", "").replace('o', '0').replace('O', '0').replace('l', '1').replace('i', '1')
        Regex("(\\d{1,3})[:.;,](\\d{2})[:.;,](\\d{2})").find(t)?.let { return it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 + it.groupValues[3].toInt() }
        Regex("(\\d{1,2})[:.;,](\\d{2})").find(t)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        return null
    }

    /**
     * Recherche du bon montant, celui que l'aide des alliés suffit à soigner (il dépend des joueurs en ligne).
     * Départ : le montant du menu. Un lot sans accélérateurs : on monte (par centaines). Un lot qui en a demandé : on
     * redescend au dernier bon montant, puis on affine par dizaines. Si les conditions changent (moins d'alliés en ligne),
     * on repart par centaines.
     */
    private fun healLearn(used: Int, bad: Boolean) {
        if (used <= 0) return
        if (bad) {
            if (used <= healOkMax) { healOkMax = -1; healStep = 100 }
            healBadMin = minOf(healBadMin, used)
            if (healCalc in 10 until used) {
                // montant calculé d'après ce que l'aide des alliés a retiré : point de départ, puis on remonte par dizaines
                // tant que l'aide suffit, sans jamais retenter un montant qui a échoué (le nombre d'alliés en ligne varie un peu)
                healAmt = maxOf(10, minOf(healCalc, healBadMin - 1))
                healOkMax = -1
                healStable = 0
                healStep = 10
                log("soigner : $used soldats : trop pour l'aide des alliés ; montant calculé $healCalc, reprise à $healAmt")
            } else {
                healAmt = if (healOkMax in 1 until used) healOkMax else maxOf(10, used - healStep)
                log("soigner : $used soldats : trop pour l'aide des alliés ; prochain montant $healAmt (pas $healStep)")
            }
            healCalc = -1
        } else {
            if (used >= healBadMin) { healBadMin = Int.MAX_VALUE; healStep = 100 }
            healOkMax = maxOf(healOkMax, used)
            if (used >= healAmt) {
                if (healBadMin == Int.MAX_VALUE) {
                    val cand = healAmt + healStep
                    if (cand <= 173_400) healAmt = cand
                } else {
                    // on connaît une borne haute : on coupe l'écart en deux (par 5) jusqu'à quelques soldats près, puis on reste là
                    val gap = healBadMin - used
                    if (gap <= 5) {
                        if (++healStable >= 6) { healBadMin = Int.MAX_VALUE; healStep = 10; healStable = 0; healAmt = used + 10 } // on retente plus haut de temps en temps
                    } else {
                        healAmt = minOf(used + maxOf(5, (gap / 2) / 5 * 5), healBadMin - 1)
                    }
                }
            }
            log("soigner : $used soldats : suffisant ; prochain montant $healAmt (pas $healStep, bon jusqu'à ${healOkMax}, trop à partir de ${if (healBadMin == Int.MAX_VALUE) "?" else healBadMin.toString()})")
        }
    }

    /** Fenêtres « Vitesse de soins des soldats » : sélection (Utilisation rapide), puis confirmation (case « Ne plus afficher », Utiliser). Jamais « Terminer » (gemmes). */
    private fun healAccelWindow(rgb: Mat, now: Long, selection: Boolean) {
        healPopupAt = now
        healUnknownAt = 0L
        healBatchBad = true
        if (now - healWinAt < 1_500L) return
        if (selection) {
            if (++healWinTries > 4) { healStop("accélérateurs : « Utilisation rapide » sans effet (plus d'accélérateurs ?), arrêt"); return }
            healWinAt = now
            clickRef(540.0, 1708.0, "soigner : accélérateurs : « Utilisation rapide » (essai $healWinTries)")
            return
        }
        val chk = colorRect(rgb, 255, 1598, 298, 1642, healGreenCk, healGreenHi)
        healWinAt = now
        if (chk < 0.10 && !healChkClicked) {
            healChkClicked = true
            clickRef(275.0, 1620.0, "soigner : accélérateurs : case « Ne plus afficher pour aujourd'hui » cochée")
            return
        }
        clickRef(540.0, 1738.0, "soigner : accélérateurs : confirmation « Utiliser »")
    }

    /** Efface l'ancienne valeur (retour arrière), tape la quantité voulue au pavé numérique, puis OK. Une touche par passage. */
    private fun healKeyboard() {
        healKbWaitUntil = 0L // le clavier est ouvert
        if (++healKbClicks > 25 || healWant.isEmpty()) {
            healSettleUntil = SystemClock.uptimeMillis() + 1_200L
            clickRef(975.0, 1460.0, "soigner : clavier : OK")
            return
        }
        if (healBackLeft > 0) {
            healBackLeft--
            clickRef(1005.0, 1950.0, "soigner : clavier : effacer")
            return
        }
        if (healTypeIdx < healWant.length) {
            val c = healWant[healTypeIdx++]
            val p = healKeys[c] ?: return
            clickRef(p.first, p.second, "soigner : clavier : $c")
            return
        }
        healSettleUntil = SystemClock.uptimeMillis() + 1_200L
        clickRef(975.0, 1460.0, "soigner : clavier : OK (quantité $healWant)")
    }

    // ---------- Épreuves Mystiques (menu « Épreuves Mystiques », à la demande) ----------

    private class EpIsland(
        val id: String, val label: String, val keys: List<String>, val days: List<Int>,
        val lab: IntArray, val cx: Double, val cy: Double, val teams: Boolean, val def: IntArray
    )

    // Îles de la carte « Épreuve Mystique » : positions fixes (coordonnées de référence 1080 px).
    private val epIslands = listOf(
        EpIsland("fleche", "Flèche Éclatante", listOf("flèche", "fleche", "clatante", "radieuse"), listOf(java.util.Calendar.SUNDAY),
            intArrayOf(330, 1180, 710, 1330), 515.0, 1111.0, true, intArrayOf(0, 0, 0)),
        EpIsland("colisee", "Colisée", listOf("colis"), listOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY),
            intArrayOf(320, 1740, 680, 1880), 491.0, 1556.0, true, intArrayOf(0, 0, 0)),
        EpIsland("grotte", "Grotte de Cristal", listOf("grotte", "cristal"), listOf(java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY),
            intArrayOf(30, 875, 430, 1035), 200.0, 760.0, false, intArrayOf(50, 15, 35)),
        EpIsland("foret", "Forêt de la Vie", listOf("forêt", "foret", "vie"), listOf(java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY),
            intArrayOf(10, 1380, 360, 1540), 129.0, 1182.0, false, intArrayOf(50, 10, 40)),
        EpIsland("nexus", "Nexus du Savoir", listOf("nexus", "connaiss", "savoir"), listOf(java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY),
            intArrayOf(640, 915, 1045, 1060), 830.0, 807.0, false, intArrayOf(55, 5, 40)),
        EpIsland("fort", "Forteresse en Fusion", listOf("fusion", "forteresse", "fort en"), listOf(java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY),
            intArrayOf(650, 1360, 1050, 1535), 889.0, 1217.0, false, intArrayOf(50, 10, 40))
    )

    // Les 3 équipes (Flèche Éclatante et Colisée) : héros dans l'ordre d'assignation, et taux par défaut.
    private val epTeamHeroes = arrayOf(
        arrayOf("spartiate", "bouffonne", "casquette"),
        arrayOf("archere", "blanc", "rousse"),
        arrayOf("pirate", "blonde", "guerriere")
    )
    private val epTeamDef = arrayOf(intArrayOf(60, 40, 0), intArrayOf(45, 15, 40), intArrayOf(50, 20, 30))

    private var epFinished = false
    private var epClickedAt = 0L
    private var epIdleAt = 0L
    private var epBackTries = 0
    private var epListTries = 0
    private var epTabAt = 0L
    private var epWaitUntil = 0L
    private var epCurSquad = -1
    private val epRatioDone = BooleanArray(3)
    private val epCleared = BooleanArray(3) // héros déjà présents retirés à la première arrivée sur l'écran de formation de l'équipe
    private var epClearTries = 0
    private val epDone = HashSet<String>()
    private var epCurIsland: EpIsland? = null
    private var epMapScanAt = 0L
    private var epMapOpen: List<EpIsland> = emptyList()
    private var epSelK = -1
    private var epSelClicked = false
    private var epSelSwipes = 0
    private var epAssignTries = 0
    private var epTauxFails = 0
    private var epTauxStuck = 0
    private var epLastVals = ""
    private var epFights = 0
    private var epPassAt = 0L
    private var epVictoryAt = 0L
    private var epCheckTries = 0
    private var epDeployAt = 0L
    private var epConfigClickAt = 0L
    private var epUnexpectedLogged = false
    private val epTpl = HashMap<String, Mat>()
    private var epBangTpl: Mat? = null

    private fun resetEpreuves() {
        epFinished = false; epClickedAt = 0L; epIdleAt = SystemClock.uptimeMillis(); epBackTries = 0; epListTries = 0; epTabAt = 0L
        epWaitUntil = 0L; epCurSquad = -1; epRatioDone.fill(false); epCleared.fill(false); epClearTries = 0; epDone.clear(); epCurIsland = null; epMapScanAt = 0L
        epMapOpen = emptyList(); epSelK = -1; epSelClicked = false; epSelSwipes = 0; epAssignTries = 0
        epTauxFails = 0; epTauxStuck = 0; epLastVals = ""; epFights = 0; epPassAt = 0L; epVictoryAt = 0L; epCheckTries = 0
        epDeployAt = 0L; epConfigClickAt = 0L; epUnexpectedLogged = false
    }

    private fun loadEpreuves() {
        resetEpreuves()
        log("épreuves mystiques : démarrage (à la demande)")
    }

    private fun epStop(msg: String) {
        epFinished = true
        log("épreuves mystiques : $msg")
        main.post { pauseBot("Épreuves Mystiques : $msg") }
    }

    /** Jour de la semaine DU JEU : il change à l'heure de remise à zéro (1 h par défaut, réglable dans le menu). */
    private fun epToday(): Int {
        val reset = getSharedPreferences("cfg", MODE_PRIVATE).getInt("epResetHour", 1).coerceIn(0, 23)
        val c = java.util.Calendar.getInstance()
        c.add(java.util.Calendar.HOUR_OF_DAY, -reset)
        return c.get(java.util.Calendar.DAY_OF_WEEK)
    }

    /** Taux (Infanterie, Cavalerie, Archer) à régler pour l'équipe i (Flèche / Colisée) ou pour une épreuve à taux seul. */
    private fun epTarget(isl: EpIsland, squad: Int): IntArray {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val d = if (isl.teams) epTeamDef[squad.coerceIn(0, 2)] else isl.def
        val k = if (isl.teams) "epr_team${squad.coerceIn(0, 2)}_" else "epr_${isl.id}_"
        return IntArray(3) { p.getInt(k + it, d[it]).coerceIn(0, 100) }
    }

    private fun epLoadGray(path: String): Mat? {
        return try {
            val bmp = assets.open(path).use { BitmapFactory.decodeStream(it) } ?: return null
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            bmp.recycle()
            val g = Mat()
            Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            g
        } catch (_: Exception) {
            null
        }
    }

    /** Zone de l'écran ramenée à l'échelle de référence (1080 px de large). */
    private fun epRef(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Mat {
        val sc = rgb.cols() / 1080.0
        val ax = (x0 * sc).toInt()
        val ay = (y0 * sc).toInt()
        val bx = minOf((x1 * sc).toInt(), rgb.cols())
        val by = minOf((y1 * sc).toInt(), rgb.rows())
        val sub = Mat(rgb, Rect(ax, ay, bx - ax, by - ay))
        if (abs(sc - 1.0) < 0.01) return sub.clone()
        val out = Mat()
        Imgproc.resize(sub, out, Size(), 1.0 / sc, 1.0 / sc, Imgproc.INTER_AREA)
        return out
    }

    /** Saturation moyenne d'une zone : carte de héros colorée (élevée) ou emplacement vide « + » gris (faible). */
    private fun epSat(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val r = epRef(rgb, x0, y0, x1, y1)
        val hsv = Mat()
        Imgproc.cvtColor(r, hsv, Imgproc.COLOR_RGB2HSV)
        val s = Core.mean(hsv).`val`[1]
        r.release(); hsv.release()
        return s
    }

    /** Luminosité moyenne d'une zone : carte de héros (claire) ou emplacement vide (brun sombre). */
    private fun epBright(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val r = epRef(rgb, x0, y0, x1, y1)
        val hsv = Mat()
        Imgproc.cvtColor(r, hsv, Imgproc.COLOR_RGB2HSV)
        val v = Core.mean(hsv).`val`[2]
        r.release(); hsv.release()
        return v
    }

    private var epLastScore = 0.0

    private fun epClick(x: Double, y: Double) {
        ClickAccessibilityService.instance?.click((x * w / 1080.0).toFloat(), (y * w / 1080.0).toFloat())
    }

    /** Portrait d'un héros dans la liste « Sélectionner des Héros » (comparaison d'image) : centre en référence, ou null. */
    private fun epFindHero(rgb: Mat, key: String): Pair<Double, Double>? {
        val t = epTpl[key] ?: epLoadGray("epreuves/hero_$key.png")?.also { epTpl[key] = it } ?: return null
        val reg = epRef(rgb, 100, 1130, 980, 1625)
        val g = Mat()
        Imgproc.cvtColor(reg, g, Imgproc.COLOR_RGB2GRAY)
        reg.release()
        val mm = matchBest(g, t)
        g.release()
        epLastScore = mm?.maxVal ?: 0.0
        if (mm == null || mm.maxVal < 0.80) return null
        return Pair(100 + mm.maxLoc.x + t.cols() / 2.0, 1130 + mm.maxLoc.y + t.rows() / 2.0 - 7.0)
    }

    /** Points d'exclamation jaunes (formation à créer) sur la carte : centres en référence, du plus haut au plus bas. */
    private fun epBangs(rgb: Mat): List<Pair<Double, Double>> {
        val tpl = epBangTpl ?: epLoadGray("epreuves/bang_mask.png")?.also { epBangTpl = it } ?: return emptyList()
        val reg = epRef(rgb, 0, 280, 1080, 2000)
        val hsv = Mat()
        Imgproc.cvtColor(reg, hsv, Imgproc.COLOR_RGB2HSV)
        reg.release()
        val m = Mat()
        Core.inRange(hsv, Scalar(17.0, 150.0, 190.0), Scalar(34.0, 255.0, 255.0), m)
        hsv.release()
        if (m.cols() < tpl.cols() || m.rows() < tpl.rows()) { m.release(); return emptyList() }
        val res = Mat()
        Imgproc.matchTemplate(m, tpl, res, Imgproc.TM_CCOEFF_NORMED)
        m.release()
        val out = ArrayList<Pair<Double, Double>>()
        for (i in 0 until 4) {
            val mm = Core.minMaxLoc(res)
            if (mm.maxVal < 0.70) break
            out.add(Pair(mm.maxLoc.x + tpl.cols() / 2.0, 280 + mm.maxLoc.y + tpl.rows() / 2.0))
            Imgproc.circle(res, org.opencv.core.Point(mm.maxLoc.x, mm.maxLoc.y), 40, Scalar(-1.0), -1)
        }
        res.release()
        return out.sortedBy { it.second }
    }

    private val epCyanLo = Scalar(85.0, 100.0, 170.0)
    private val epCyanHi = Scalar(105.0, 255.0, 255.0)
    private var epPillageLogAt = 0L
    private val epGreenLo = Scalar(40.0, 100.0, 100.0)
    private val epGreenHi = Scalar(85.0, 255.0, 255.0)
    private val epBadgeLo = Scalar(0.0, 170.0, 200.0)
    private val epBadgeHi = Scalar(16.0, 255.0, 255.0)
    private val epOrangeLo = Scalar(5.0, 170.0, 200.0)
    private val epOrangeHi = Scalar(20.0, 255.0, 255.0)

    private fun epIslandOf(text: String): EpIsland? = epIslands.firstOrNull { i -> i.keys.any { it in text } }

    /**
     * Parcours complet : menu de gauche > onglet Quot. > tâche « Épreuve Mystique » > carte > île (« ! » = formation à créer,
     * Piller, Défi) > Config. d'Escouade (3 équipes pour Flèche Éclatante et Colisée) > Déployer > combats (Passer /
     * Réessayer) jusqu'aux 5 tentatives du jour > île suivante. Le jour de la semaine sert de contrôle de sécurité.
     */
    private fun detectEpreuves(rgb: Mat) {
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < minOf(cooldown, 600L) || epFinished || now < epWaitUntil) return

        // 1) écrans de résultat de combat (le fond flouté peut encore montrer d'autres textes : on les teste d'abord)
        val ribbon = ocrRef(rgb, 150, 740, 930, 850)
        if ("victoire" in ribbon) {
            epIdleAt = now
            if (epVictoryAt == 0L) { epVictoryAt = now; epCheckTries = 0 }
            // L'écran de victoire s'anime à son apparition : on attend qu'il soit stable (bouton « Suivant » affiché depuis 1,5 s)
            // avant de lire la case, sinon on la lit « vide » alors qu'elle est déjà cochée et on la décoche en cliquant.
            // On se fie à l'état lu (vert = cochée) et non à un drapeau : une case déjà cochée n'est jamais retouchée.
            if (epCheckTries < 2 && now - epVictoryAt >= 1_500L && colorRect(rgb, 570, 1500, 1000, 1590, epCyanLo, epCyanHi) >= 0.35) {
                val green = colorRect(rgb, 262, 1690, 318, 1745, epGreenLo, epGreenHi)
                if (green < 0.10) {
                    epCheckTries++
                    clickRef(290.0, 1718.0, "épreuves : case « Défier Auto. La Prochaine Étape » à cocher (essai $epCheckTries, vert ${"%.2f".format(green)})")
                    epWaitUntil = now + 1_500L
                    return
                }
            }
            if (now - epVictoryAt > 6_000L) {
                epVictoryAt = now
                clickRef(785.0, 1545.0, "épreuves : victoire, bouton « suivant » touché (l'enchaînement auto n'est pas parti)")
            }
            return
        }
        epVictoryAt = 0L
        if ("faite" in ribbon) {
            epIdleAt = now
            if (colorRect(rgb, 570, 1710, 1000, 1800, epCyanLo, epCyanHi) >= 0.35) {
                epFights++
                if (epFights > 250) { epStop("plus de 250 combats, arrêt de sécurité"); return }
                clickRef(785.0, 1755.0, "épreuves : défaite, « Réessayer » (combat $epFights)")
                epWaitUntil = now + 1500L
            } else {
                epCurIsland?.let { epDone.add(it.id); log("épreuves : ${it.label} : plus de tentatives aujourd'hui") }
                clickRef(230.0, 370.0, "épreuves : défaite, plus de tentatives : fermeture de la fenêtre")
                epWaitUntil = now + 1500L
            }
            return
        }

        // 2) fenêtre « Récompenses de Pillage »
        // Le vert seul ne suffit pas : l'herbe de la ville (au-dessus de la Tour à Canon) en fait ~55 % et déclenchait un clic sur la tour.
        // On confirme donc par le texte « Récupérer » du bouton.
        if (colorRect(rgb, 330, 1735, 750, 1815, epGreenLo, epGreenHi) >= 0.5) {
            val btn = ocrRefWhite(rgb, 330, 1735, 750, 1815)
            if ("cup" in btn || "récu" in btn || "recu" in btn) {
                epIdleAt = now
                clickRef(540.0, 1775.0, "épreuves : Récompenses de Pillage : Récupérer")
                epWaitUntil = now + 1500L
                return
            }
            if (now - epPillageLogAt > 20_000L) {
                epPillageLogAt = now
                log("épreuves : zone verte sans « Récupérer » (lu : « ${btn.trim().replace("\n", " ")} ») : ignorée")
            }
        }

        // 3) fenêtres (testées avant le titre : leur fond flouté montre encore le titre de l'écran dessous) : Sélectionner des Héros, Taux
        val pop = ocrRef(rgb, 240, 430, 840, 520)
        if ("lectionner" in pop || "héros" in pop || "heros" in pop) {
            epIdleAt = now
            epHandleHeroes(rgb, now)
            return
        }
        val minusOn = intArrayOf(1045, 1265, 1485).count { colorRect(rgb, 270, it - 30, 340, it + 30, epCyanLo, epCyanHi) >= 0.5 }
        if (minusOn >= 2 && colorRect(rgb, 330, 1680, 750, 1760, epCyanLo, epCyanHi) >= 0.5) {
            epIdleAt = now
            epHandleTaux(rgb, now)
            return
        }

        // 4) titre de l'écran (barre du haut) : Config. d'Escouade, carte, île / formation
        val title = ocrRef(rgb, 100, 100, 720, 190)
        if ("config" in title || "escouade" in title) {
            epIdleAt = now
            epHandleConfig(rgb, now)
            return
        }
        if ("mystique" in title || ("preuve" in title && "classement" !in title)) {
            epIdleAt = now
            epHandleMap(rgb, now)
            return
        }
        val isl = epIslandOf(title)
        if (isl != null) {
            epIdleAt = now
            val bar = ocrRef(rgb, 30, 2030, 340, 2090)
            if ("taux" in bar || "troupes" in bar) epHandleFormation(rgb, now, isl) else epHandleIsland(rgb, now, isl)
            return
        }

        // 5) combat en cours : bouton « Passer » en haut à droite
        val pass = ocrRef(rgb, 800, 290, 1070, 390)
        if ("passer" in pass) {
            epIdleAt = now
            if (now - epPassAt > 2_500L) {
                epPassAt = now
                clickRef(940.0, 340.0, "épreuves : combat, « Passer »")
            }
            return
        }

        // 6) écran Récompenses / ville / menu Quot.
        val rw = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in rw || "quitter" in rw) {
            epIdleAt = now
            tapRewards("épreuves mystiques : écran Récompenses : appui n'importe où")
            return
        }
        val tabs = ocrRef(rgb, 15, 615, 665, 695)
        if ("ville" in tabs || "nature" in tabs || "quot" in tabs) {
            epIdleAt = now
            epBackTries = 0
            if (now - epClickedAt < 5_000L) return
            val q = meanV(rgb, 455, 625, 655, 685)
            val v = meanV(rgb, 20, 625, 220, 685)
            if (!(q > 150 && q > v + 40)) {
                clickRef(553.0, 655.0, "épreuves mystiques : clic onglet Quot.")
                return
            }
            val sc = rgb.cols() / 1080.0
            val y0 = (695 * sc).toInt()
            val x0 = (15 * sc).toInt()
            val x1 = (665 * sc).toInt()
            val y1 = minOf((1455 * sc).toInt(), rgb.rows())
            val lines = ocrLines(Mat(rgb, Rect(x0, y0, x1 - x0, y1 - y0)), false)
            if (lines.isEmpty()) return
            val names = listOf("colis", "forêt de la vie", "foret de la vie", "nexus", "fort en fusion", "forteresse", "grotte",
                "flèche éclatante", "fleche eclatante", "flèche radieuse")
            val titleLine = lines.firstOrNull { "mystique" in it.text.lowercase() }
            val nameLine = lines.firstOrNull { l -> names.any { it in l.text.lowercase() } }
            val clickY = when {
                titleLine != null -> (y0 + titleLine.cy) / sc + 79.0 // la ligne de la tâche est sous le titre de section
                nameLine != null -> (y0 + nameLine.cy) / sc + 21.0
                else -> -1.0
            }
            if (clickY < 0) {
                if (++epListTries > 6) epStop("« Épreuve Mystique » introuvable dans la liste Quot. (rien à faire ?)")
                return
            }
            epClickedAt = now
            clickRef(335.0, clickY, "épreuves mystiques : clic sur le texte de la tâche (y ${clickY.toInt()})")
            return
        }
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        val city = listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in bar } >= 2
        if (city) {
            epIdleAt = now
            if (now - epTabAt > 2_500L && now - epClickedAt > 5_000L) {
                epTabAt = now
                clickRef(20.0, 1075.0, "épreuves mystiques : ouverture du menu (languette)")
            }
            return
        }
        if (now - epIdleAt > 15_000L) {
            epIdleAt = now
            if (++epBackTries > 4) { epStop("écran inconnu, arrêt"); return }
            lastClick = now
            backArrow()
            log("épreuves mystiques : écran inconnu, flèche retour")
        }
    }

    /** Carte « Épreuve Mystique » : choisit la prochaine île ouverte et attendue ce jour-là (le « ! » d'abord, du haut vers le bas). */
    private fun epHandleMap(rgb: Mat, now: Long) {
        val today = epToday()
        if (now - epMapScanAt > 8_000L || epMapOpen.isEmpty()) {
            epMapOpen = epIslands.filter { isl ->
                val t = ocrRef(rgb, isl.lab[0], isl.lab[1], isl.lab[2], isl.lab[3])
                isl.keys.any { it in t } && "ouvre" !in t
            }
            epMapScanAt = now
            log("épreuves : îles ouvertes sur la carte : ${epMapOpen.joinToString { it.label }.ifEmpty { "aucune" }}")
        }
        val expected = epIslands.filter { today in it.days }
        val unexpected = epMapOpen.filter { it !in expected }
        if (unexpected.isNotEmpty() && !epUnexpectedLogged) {
            epUnexpectedLogged = true
            log("épreuves : ATTENTION, île ouverte hors du calendrier de ce jour (${unexpected.joinToString { it.label }}) : ignorée")
        }
        val cand = epMapOpen.filter { it in expected && it.id !in epDone }.sortedBy { it.cy }
        if (cand.isEmpty()) {
            if (epMapOpen.isEmpty() && epDone.isEmpty()) {
                if (++epListTries > 3) epStop("aucune île ouverte reconnue sur la carte (jour du jeu : ${expected.joinToString { it.label }})")
                epMapScanAt = 0L
                epWaitUntil = now + 1500L
                return
            }
            backArrow()
            epStop("terminé : toutes les épreuves du jour sont faites (${epDone.joinToString { it }})")
            return
        }
        val isl = cand.first()
        epCurIsland = isl
        epCurSquad = -1
        epRatioDone.fill(false); epCleared.fill(false); epClearTries = 0
        val bang = epBangs(rgb).minByOrNull { abs(it.first - isl.cx) + abs(it.second - isl.cy) }
            ?.takeIf { abs(it.first - isl.cx) + abs(it.second - isl.cy) < 360 }
        if (bang != null) {
            clickRef(bang.first, bang.second, "épreuves : ${isl.label} : clic sur le « ! » (formation à créer)")
        } else {
            clickRef(isl.cx, isl.cy, "épreuves : ${isl.label} : clic sur l'île (pas de « ! »)")
        }
        epMapScanAt = 0L
        epWaitUntil = now + 2_000L
    }

    /** Écran d'une île : Piller (premier jour) puis Défi ; Défi grisé = plus de tentatives. */
    private fun epHandleIsland(rgb: Mat, now: Long, isl: EpIsland) {
        epCurIsland = isl
        val bottom = ocrRef(rgb, 150, 2170, 930, 2320)
        if ("piller" in bottom || "pille" in bottom) {
            clickRef(540.0, 2243.0, "épreuves : ${isl.label} : Piller")
            epWaitUntil = now + 2_000L
            return
        }
        if (colorRect(rgb, 320, 2185, 760, 2305, epCyanLo, epCyanHi) >= 0.35) {
            clickRef(540.0, 2245.0, "épreuves : ${isl.label} : Défi")
            epWaitUntil = now + 2_200L
            return
        }
        if ("défi" in bottom || "defi" in bottom || "tentative" in bottom || "actualisation" in bottom) {
            epDone.add(isl.id)
            log("épreuves : ${isl.label} : Défi grisé (plus de tentatives) : retour")
            lastClick = now
            backArrow()
            epWaitUntil = now + 1_500L
        }
    }

    /** Config. d'Escouade : crée les équipes vides dans l'ordre, puis Déployer. */
    private fun epHandleConfig(rgb: Mat, now: Long) {
        val isl = epCurIsland
        if (isl != null && !isl.teams) {
            epStop("« Config. d'Escouade » inattendu pour ${isl.label} (épreuve à taux seul : écran à construire)")
            return
        }
        val tileY = intArrayOf(440, 958, 1475)
        val btnY = doubleArrayOf(617.0, 1133.0, 1650.0)
        val filled = BooleanArray(3) { epSat(rgb, 270, tileY[it] - 45, 365, tileY[it] + 45) > 70.0 }
        val first = filled.indexOfFirst { !it }
        if (first >= 0) {
            if (epCurSquad == first && now - epConfigClickAt < 4_000L) return
            epCurSquad = first
            epRatioDone[first] = false
            epCleared[first] = false; epClearTries = 0
            epSelK = -1
            epConfigClickAt = now
            clickRef(540.0, btnY[first], "épreuves : équipe ${first + 1} vide : Modifier la Formation")
            epWaitUntil = now + 1_800L
            return
        }
        if (colorRect(rgb, 560, 2160, 1010, 2260, epCyanLo, epCyanHi) >= 0.35) {
            epDeployAt = now
            clickRef(783.0, 2210.0, "épreuves : 3 équipes prêtes : Déployer")
            epWaitUntil = now + 2_500L
            epCurSquad = -1
        } else if (epDeployAt == 0L || now - epDeployAt > 8_000L) {
            epDeployAt = now
            log("épreuves : 3 équipes prêtes mais « Déployer » n'est pas actif : attente")
        }
    }

    /**
     * Écran de formation. Flèche Éclatante / Colisée : cartes de héros en haut (3 héros à placer, puis Taux, puis retour à la
     * config). Autres épreuves : pas de héros (« Impossible de déployer des Héros… »), seulement Taux puis Déployer.
     */
    private fun epHandleFormation(rgb: Mat, now: Long, isl: EpIsland) {
        if (!isl.teams) {
            epCurSquad = 0
            if (!epRatioDone[0]) {
                epTauxFails = 0; epTauxStuck = 0; epLastVals = ""
                clickRef(495.0, 2200.0, "épreuves : ${isl.label} : bouton « Taux »")
                epWaitUntil = now + 1_500L
                return
            }
            if (colorRect(rgb, 610, 2190, 1040, 2290, epCyanLo, epCyanHi) >= 0.35) {
                epDeployAt = now
                clickRef(822.0, 2240.0, "épreuves : ${isl.label} : taux réglés, Déployer")
                epWaitUntil = now + 2_500L
            } else if (now - epDeployAt > 8_000L) {
                epDeployAt = now
                log("épreuves : ${isl.label} : « Déployer » n'est pas actif : attente")
            }
            return
        }
        val cx = doubleArrayOf(257.0, 540.0, 823.0)
        // carte pleine = pastille orange « − » (retirer) en haut à droite de la carte ; emplacement vide : aucune pastille
        val bx = doubleArrayOf(359.0, 642.0, 925.0)
        val f = (0 until 3).count { colorRect(rgb, bx[it].toInt() - 30, 325, bx[it].toInt() + 30, 380, epBadgeLo, epBadgeHi) >= 0.3 }
        if (epCurSquad < 0) { epStop("écran de formation sans équipe en cours"); return }
        // première arrivée : des héros peuvent déjà être en place (ancienne formation) : on les retire un par un avec la pastille orange « − »
        if (!epCleared[epCurSquad]) {
            val full = (0 until 3).firstOrNull { colorRect(rgb, bx[it].toInt() - 30, 325, bx[it].toInt() + 30, 380, epBadgeLo, epBadgeHi) >= 0.3 }
            if (full == null) {
                epCleared[epCurSquad] = true
            } else {
                if (++epClearTries > 9) { epStop("impossible de retirer les héros déjà en place (équipe ${epCurSquad + 1})"); return }
                clickRef(bx[full] - 2, 355.0, "épreuves : équipe ${epCurSquad + 1} : héros déjà en place, retrait du héros ${full + 1} (« − » orange)")
                epWaitUntil = now + 1_000L
                return
            }
        }
        if (f < 3) {
            clickRef(cx[f], 535.0, "épreuves : équipe ${epCurSquad + 1} : « + » du héros ${f + 1}")
            epWaitUntil = now + 1_600L
            return
        }
        if (epCurSquad < 0) { epStop("écran de formation sans équipe en cours"); return }
        if (!epRatioDone[epCurSquad]) {
            epTauxFails = 0; epTauxStuck = 0; epLastVals = ""
            clickRef(495.0, 2200.0, "épreuves : équipe ${epCurSquad + 1} : héros en place, bouton « Taux »")
            epWaitUntil = now + 1_500L
            return
        }
        clickRef(822.0, 2240.0, "épreuves : équipe ${epCurSquad + 1} terminée : Modifier la Formation (retour à la config)")
        epWaitUntil = now + 1_800L
    }

    /** Fenêtre « Sélectionner des Héros » : assigne les 3 héros de l'équipe (reconnus par leur portrait), puis ferme. */
    private fun epHandleHeroes(rgb: Mat, now: Long) {
        val team = epCurSquad
        if (team < 0) { epStop("fenêtre des héros sans équipe en cours"); return }
        val sx = intArrayOf(245, 540, 833)
        val k = (0 until 3).count { epSat(rgb, sx[it] - 60, 700, sx[it] + 60, 850) > 60.0 }
        if (k >= 3) {
            clickRef(965.0, 475.0, "épreuves : équipe ${team + 1} : 3 héros assignés, fermeture de la fenêtre")
            epWaitUntil = now + 1_300L
            epSelK = -1
            return
        }
        if (k != epSelK) { epSelK = k; epSelClicked = false; epSelSwipes = 0; epAssignTries = 0 }
        val key = epTeamHeroes[team][k]
        if (!epSelClicked) {
            val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
            val exact = cfg.getBoolean("epTeamType", true) && cfg.getBoolean("epHeroExact_$key", true)
            if (!exact) {
                log("épreuves : équipe ${team + 1} : héros ${k + 1} : équipe type décochée ou héros non exact (menu), on prend le premier de la liste")
                epSelClicked = true
            }
        }
        if (!epSelClicked) {
            val p = epFindHero(rgb, key)
            if (p != null) {
                clickRef(p.first, p.second, "épreuves : équipe ${team + 1} : héros ${k + 1} ($key) touché")
                epSelClicked = true
                epWaitUntil = now + 700L
                return
            }
            if (epSelSwipes < 1) {
                epSelSwipes++
                lastClick = now
                ClickAccessibilityService.instance?.swipe((540 * w / 1080.0).toFloat(), (1560 * w / 1080.0).toFloat(), (540 * w / 1080.0).toFloat(), (1250 * w / 1080.0).toFloat(), 600)
                log("épreuves : héros $key introuvable (meilleure ressemblance ${(epLastScore * 100).toInt()} %, il en faut 80) : défilement de la liste")
                epWaitUntil = now + 1_000L
                return
            }
            epStop("héros exact « $key » introuvable (ressemblance ${(epLastScore * 100).toInt()} %, il en faut 80) : décoche-le dans le menu pour prendre le premier de la liste")
            return
        }
        if (++epAssignTries > 4) { epStop("« Assigner » sans effet pour le héros ${k + 1} de l'équipe ${team + 1}"); return }
        clickRef(770.0, 1755.0, "épreuves : équipe ${team + 1} : Assigner (héros ${k + 1})")
        epSelClicked = false
        epWaitUntil = now + 1_500L
    }

    /** Fenêtre « Taux » : lit les 3 pourcentages et corrige avec − / + (1 % par appui) : on baisse d'abord ce qui dépasse. */
    private fun epHandleTaux(rgb: Mat, now: Long) {
        val isl = epCurIsland ?: run { epStop("fenêtre Taux sans épreuve en cours"); return }
        val squad = if (isl.teams) epCurSquad else 0
        if (squad < 0) { epStop("fenêtre Taux sans équipe en cours"); return }
        val tgt = epTarget(isl, squad)
        val ry = intArrayOf(1047, 1267, 1487)
        // chiffres BLANCS cerclés de sombre sur case grise : on lit le blanc (seuils hauts), zone resserrée pour exclure le liseré de la case
        val raw = Array(3) { ocrRefWhite(rgb, 850, ry[it] - 22, 940, ry[it] + 22) }
        val v = IntArray(3) {
            val s = raw[it].replace('o', '0').replace('O', '0').replace('l', '1').replace('i', '1').filter { c -> c.isDigit() }
            // un « 0 » seul n'est pas lu par l'OCR : le bouton « − » est alors grisé (plus rien à retirer) => 0 %
            s.toIntOrNull()?.takeIf { n -> n in 0..100 }
                ?: if (colorRect(rgb, 270, ry[it] - 30, 340, ry[it] + 30, epCyanLo, epCyanHi) < 0.1) 0 else -1
        }
        if (v.any { it < 0 }) {
            if (epTauxFails == 0) log("épreuves : Taux : lecture brute ${raw.joinToString(" | ") { it.trim().replace("\n", " ") }}")
            if (++epTauxFails > 12) epStop("pourcentages illisibles dans la fenêtre Taux")
            epWaitUntil = now + 300L
            return
        }
        epTauxFails = 0
        val diff = IntArray(3) { v[it] - tgt[it] }
        if (diff.all { it == 0 }) {
            epRatioDone[squad] = true
            clickRef(540.0, 1720.0, "épreuves : taux ${v.joinToString("/")} atteints : Confirmer")
            epWaitUntil = now + 1_500L
            return
        }
        val key = v.joinToString("/")
        if (key == epLastVals) epTauxStuck++ else { epTauxStuck = 0; epLastVals = key }
        if (epTauxStuck > 5) { epStop("taux bloqués à $key (cible ${tgt.joinToString("/")})"); return }
        val minus = diff.any { it > 0 }
        val i = if (minus) diff.indices.maxByOrNull { diff[it] }!! else diff.indices.minByOrNull { diff[it] }!!
        val n = minOf(abs(diff[i]), 5)
        val x = if (minus) 304.0 else 768.0
        val yy = doubleArrayOf(1045.0, 1265.0, 1485.0)[i]
        for (c in 0 until n) {
            epClick(x, yy)
            Thread.sleep(140)
        }
        lastClick = SystemClock.uptimeMillis()
        log("épreuves : taux ${v.joinToString("/")} -> cible ${tgt.joinToString("/")} : ligne ${i + 1} ${if (minus) "−" else "+"}$n")
        epWaitUntil = now + 450L
    }

    private var rewardTaps = 0

    /**
     * Écran « Récompenses » : on touche d'abord le bandeau jaune (c'est là que vos essais ont marché), puis, si l'écran
     * reste, on alterne avec d'autres points (haut de l'écran, bas de l'écran) : sur les gros lots, un toucher trop tôt
     * ou sur une icône peut ne pas fermer l'écran.
     */
    private fun tapRewards(msg: String) {
        val pts = listOf(540.0 to 730.0, 540.0 to 420.0, 540.0 to 1900.0)
        val (x, y) = pts[rewardTaps % pts.size]
        rewardTaps++
        clickRef(x, y, "$msg (point ${(rewardTaps - 1) % pts.size + 1})")
    }

    /** Part de pixels d'une couleur HSV dans un rectangle (coordonnées de référence 1080 px). */
    private fun colorRect(rgb: Mat, x0: Int, y0: Int, x1: Int, y1: Int, lo: Scalar, hi: Scalar): Double {
        val sc = rgb.cols() / 1080.0
        val ax = (x0 * sc).toInt()
        val ay = (y0 * sc).toInt()
        val bx = minOf((x1 * sc).toInt(), rgb.cols())
        val by = minOf((y1 * sc).toInt(), rgb.rows())
        if (bx <= ax || by <= ay) return 0.0
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(ax, ay, bx - ax, by - ay)), hsv, Imgproc.COLOR_RGB2HSV)
        val m = Mat()
        Core.inRange(hsv, lo, hi, m)
        val f = Core.countNonZero(m).toDouble() / (m.rows() * m.cols())
        hsv.release(); m.release()
        return f
    }

    /** Part de pixels d'une couleur HSV autour d'un point de référence. */
    private fun colorFrac(rgb: Mat, x: Double, y: Double, lo: Scalar, hi: Scalar, r0: Int = 22): Double {
        val sc = rgb.cols() / 1080.0
        val r = r0 * sc
        val x0 = (x * sc - r).toInt()
        val y0 = (y * sc - r).toInt()
        val sz = (2 * r).toInt()
        if (x0 < 0 || y0 < 0 || sz <= 0 || x0 + sz > rgb.cols() || y0 + sz > rgb.rows()) return 0.0
        val hsv = Mat()
        Imgproc.cvtColor(Mat(rgb, Rect(x0, y0, sz, sz)), hsv, Imgproc.COLOR_RGB2HSV)
        val m = Mat()
        Core.inRange(hsv, lo, hi, m)
        val f = Core.countNonZero(m).toDouble() / (sz * sz)
        hsv.release(); m.release()
        return f
    }

    /** Point rouge de notification sur le coffre « Trésor d'Allié » (en bas à droite). */
    private fun redDotOnTreasure(rgb: Mat): Boolean =
        colorFrac(rgb, 1000.0, 2168.0, Scalar(0.0, 150.0, 180.0), Scalar(8.0, 255.0, 255.0), 14) +
            colorFrac(rgb, 1000.0, 2168.0, Scalar(172.0, 150.0, 180.0), Scalar(179.0, 255.0, 255.0), 14) > 0.15

    /**
     * Aventure Animalière, guidée par l'écran : « Récupérer Tout » (coffres cochés) ou « Tout chasser » (tentatives),
     * écran « Récompenses » (appui n'importe où), puis, s'il y a un point rouge sur le coffre en bas à droite :
     * Trésor d'Allié > Récupérer Tout > récompenses > clic dans le vide ; sinon flèche bleue en haut à gauche.
     */
    private fun detectAventure(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Aventure Animalière abandonnée (10 s sans écran reconnu)")
            lastClick = now
            backArrow()
            dailyState = "OPEN"
            dailyTries = 0
            return
        }
        // écran « Récompenses » : appuyer n'importe où
        val reward = ocrRef(rgb, 200, 2150, 880, 2240)
        if ("appuie" in reward || "quitter" in reward) {
            dailyIdleAt = now
            tapRewards("daily : récompenses : appui n'importe où")
            return
        }
        // fenêtre « Trésor d'Allié » onglet « Mes partages » : bouton « Partager » (avec point rouge) puis croix en haut à droite
        val tabs = ocrRef(rgb, 100, 665, 980, 740, true)
        if (("mes part" in tabs || "partages" in tabs) && "restant" !in ocrRef(rgb, 200, 785, 880, 855, true)) {
            dailyIdleAt = now
            val cyan = colorFrac(rgb, 860.0, 870.0, Scalar(86.0, 90.0, 120.0), Scalar(105.0, 255.0, 255.0))
            val dot = colorFrac(rgb, 968.0, 822.0, Scalar(0.0, 150.0, 180.0), Scalar(8.0, 255.0, 255.0), 8) +
                colorFrac(rgb, 968.0, 822.0, Scalar(172.0, 150.0, 180.0), Scalar(179.0, 255.0, 255.0), 8)
            if (cyan >= 0.2 && dot >= 0.3 && dailyBtn < 3) {
                dailyBtn++
                clickRef(860.0, 870.0, "daily : Trésor d'Allié : Partager")
            } else {
                clickRef(1000.0, 583.0, "daily : Trésor d'Allié : fermeture par la croix")
            }
            return
        }
        // fenêtre « Trésor d'Allié » : Récupérer Tout s'il y en a, sinon clic dans le vide pour la fermer
        val tres = ocrRef(rgb, 200, 785, 880, 855, true)
        if ("clamer" in tres || "restant" in tres) {
            dailyIdleAt = now
            if (colorFrac(rgb, 540.0, 1830.0, Scalar(55.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0)) >= 0.2) {
                clickRef(540.0, 1830.0, "daily : Trésor d'Allié : Récupérer Tout")
            } else {
                clickRef(575.0, 370.0, "daily : Trésor d'Allié : clic dans le vide pour fermer")
            }
            return
        }
        // écran de l'Aventure Animalière
        val hdr = ocrRef(rgb, 150, 245, 930, 325)
        if ("tentative" in hdr) {
            dailyIdleAt = now
            val green = colorFrac(rgb, 540.0, 2040.0, Scalar(55.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0))
            val cyan = colorFrac(rgb, 540.0, 2040.0, Scalar(86.0, 60.0, 120.0), Scalar(105.0, 255.0, 255.0))
            when {
                green >= 0.25 && dailyBtn < 8 -> { dailyBtn++; clickRef(540.0, 2040.0, "daily : Aventure Animalière : Récupérer Tout") }
                cyan >= 0.25 && dailyBtn < 8 -> { dailyBtn++; clickRef(540.0, 2040.0, "daily : Aventure Animalière : Tout chasser") }
                redDotOnTreasure(rgb) && dailyStep < 3 -> { // 3 ouvertures au plus : évite de tourner en boucle
                    dailyStep++
                    clickRef(945.0, 2240.0, "daily : point rouge sur le coffre : ouverture du Trésor d'Allié")
                }
                else -> {
                    lastClick = now
                    backArrow()
                    dailyTaskDone("Aventure Animalière")
                }
            }
        }
    }

    // ---------- Mode Hunt (chasses de Terreur par ralliement) ----------

    private var huntTarget = 3
    private var huntDone = 0
    private var huntAsked = false
    private var huntSearchedAt = 0L
    private var huntStartedAt = 0L
    private var huntFails = 0
    private var huntBackPending = false
    private var huntLevelClicks = 0
    private var huntNoFreeUntil = 0L // aucune marche libre : on ne revérifie qu'à cet instant
    private var huntWaitMs = 150_000L // ralliement (1 min) + arrivées + marche aller-retour
    private var moreView: View? = null

    private fun loadHunt() {
        val sets = listOf(
            "farm/loupe.png", "farm/tile_terreur.png", "farm/rechercher.png",
            "hunt/ralliement_btn.png", "hunt/appeler_btn.png", "hunt/deployer_rally.png", "hunt/rejoindre_auto.png"
        )
        for (path in sets) {
            val m = loadAsset(path) ?: continue
            farm[path.substringAfter('/').removeSuffix(".png")] = m
        }
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        huntTarget = sp.getInt("hunt${marchTotal}Count", 3).coerceIn(1, 99)
        huntWaitMs = sp.getInt("huntWait", 150).coerceIn(10, 1800) * 1000L
        huntNoFreeUntil = 0L
        huntDone = 0
        huntAsked = false
        huntSearchedAt = 0L
        huntStartedAt = 0L
        huntFails = 0
        log("hunt : $huntTarget chasse(s), $marchTotal marches")
    }

    /** Aucune marche libre : on attend avant de revérifier (le ralliement se remplit en 1 min, puis aller-retour). */
    private fun huntWait(used: Int, now: Long) {
        huntNoFreeUntil = now + huntWaitMs
        huntStartedAt = 0L
        log("hunt : marches $used/$marchTotal, aucune libre : nouvelle vérification dans ${huntWaitMs / 1000} s")
    }

    /**
     * Une chasse : loupe > tuile Terreur > (niveau max) > Rechercher > « Ralliement » > « Appeler à un
     * ralliement » > Déployer. Avant chaque chasse on compte les marches sorties (panneau à gauche).
     */
    private fun detectHunt(screen: Mat, rgb: Mat) {
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < cooldown) return
        if (huntBackPending) {
            huntBackPending = false
            lastClick = now
            backArrow()
            return
        }
        if (handleNoMarchPopup(screen, now)) {
            huntBackPending = true // on ressort de l'écran de déploiement, puis on attend une marche libre
            huntStartedAt = 0L
            return
        }
        if (huntDone >= huntTarget) {
            if (!huntAsked) {
                huntAsked = true
                log("hunt terminé : $huntDone chasse(s)")
                main.post { showMorePanel() }
            }
            return
        }
        if (now < huntNoFreeUntil) return // attente en cours : aucune marche libre
        if (huntStartedAt == 0L) huntStartedAt = now
        if (now - huntStartedAt > 150_000) {
            huntFails++
            huntStartedAt = 0L
            log("hunt : tentative abandonnée (2 min 30 sans avancer), échec $huntFails")
            if (huntFails >= 3) main.post { pauseBot("Hunt bloqué : voir le journal") }
            return
        }

        // 1) envoyer les troupes
        val md = farmMatch(screen, "deployer_rally", 0.8)
        if (md != null) {
            val (x, y) = farmCenter("deployer_rally", md)
            huntDone++
            huntFails = 0
            huntStartedAt = 0L
            huntSearchedAt = 0L
            huntLevelClicks = 0
            huntTileClicks = 0
            farmClick(x, y, "hunt : clic Déployer, chasse $huntDone/$huntTarget lancée")
            return
        }
        // 2) fenêtre « Appeler à un Ralliement »
        val ma = farmMatch(screen, "appeler_btn", 0.8)
        if (ma != null) {
            val (x, y) = farmCenter("appeler_btn", ma)
            farmClick(x, y, "hunt : clic Appeler à un Ralliement")
            return
        }
        // 3) fenêtre du Titan : bouton orange « Ralliement »
        val mr = farmMatch(screen, "ralliement_btn", 0.8)
        if (mr != null) {
            val (x, y) = farmCenter("ralliement_btn", mr)
            huntSearchedAt = 0L
            farmClick(x, y, "hunt : clic Ralliement")
            return
        }
        // 4) on attend le résultat de la recherche
        if (huntSearchedAt > 0L && now - huntSearchedAt < 9_000L) return
        // 5) liste de tuiles ouverte
        val mk = farmMatch(screen, "rechercher", 0.8)
        if (mk != null) {
            val used = usedMarches(screen)
            if (used >= marchTotal) {
                huntWait(used, now)
                return
            }
            val mt = farmMatch(screen, "tile_terreur", 0.8)
            // Terreur est sélectionnée si « Rejoindre Auto. » est visible (mais son icône change quand il tourne,
            // avec un minuteur) ou si les quatre coins orange de sélection entourent la tuile.
            val selected = farmMatch(screen, "rejoindre_auto", 0.8) != null ||
                (mt != null && farmCenter("tile_terreur", mt).let { (cx, cy) -> tileSelected(rgb, cx, cy) }) ||
                huntTileClicks >= 3
            if (!selected) {
                if (mt != null) {
                    val (x, y) = farmCenter("tile_terreur", mt)
                    huntTileClicks++
                    farmClick(x, y, "hunt : clic tuile Terreur")
                } else {
                    swipeRow(0.76 * h, 0.6 * w, now)
                    log("hunt : tuile Terreur introuvable, défilement vers le début")
                }
                return
            }
            // Niveau maximum : si le bouton « + » du curseur est encore actif (cyan), on le touche.
            val (rx, ry) = farmCenter("rechercher", mk)
            val sc = w / 1080.0
            val px = 730 * sc
            val py = ry - 242 * sc
            if (huntLevelClicks < 10 && cyanAt(rgb, px * SCALE, py * SCALE, 24 * sc * SCALE)) {
                huntLevelClicks++
                farmClick(px.toFloat(), py.toFloat(), "hunt : niveau + ($huntLevelClicks)")
                return
            }
            huntSearchedAt = now
            huntTileClicks = 0
            farmClick(rx, ry, "hunt : clic Rechercher")
            return
        }
        // 6) carte du monde : on ouvre la liste si une marche est libre
        val ml = farmMatch(screen, "loupe", 0.85)
        if (ml != null) {
            val used = usedMarches(screen)
            if (used >= marchTotal) {
                huntWait(used, now)
                return
            }
            val (x, y) = farmCenter("loupe", ml)
            farmClick(x, y, "hunt : clic loupe (marches $used/$marchTotal)")
        }
    }

    private var huntTileClicks = 0

    /** Une tuile sélectionnée porte quatre coins orange (cadre) autour d'elle. cx, cy : centre de la tuile en pleine résolution. */
    private fun tileSelected(rgb: Mat, cx: Float, cy: Float): Boolean {
        val sc = w / 1080.0
        val r = 24 * sc * SCALE
        var hits = 0
        for ((dx, dy) in listOf(-115 to -92, 115 to -92, -115 to 95, 115 to 95)) {
            val px = (cx + dx * sc) * SCALE
            val py = (cy + dy * sc) * SCALE
            val x0 = (px - r).toInt()
            val y0 = (py - r).toInt()
            val sz = (2 * r).toInt()
            if (x0 < 0 || y0 < 0 || sz <= 0 || x0 + sz > rgb.cols() || y0 + sz > rgb.rows()) continue
            val roi = Mat(rgb, Rect(x0, y0, sz, sz))
            val hsv = Mat()
            Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_RGB2HSV)
            val mask = Mat()
            Core.inRange(hsv, Scalar(12.0, 150.0, 180.0), Scalar(30.0, 255.0, 255.0), mask)
            val f = Core.countNonZero(mask).toDouble() / (sz * sz)
            hsv.release(); mask.release()
            if (f >= 0.05) hits++
        }
        return hits >= 3
    }

    /** Vrai si la zone (centre cx, cy ; demi-côté r, en demi-résolution) est plutôt cyan (bouton actif). */
    private fun cyanAt(rgb: Mat, cx: Double, cy: Double, r: Double): Boolean {
        val x0 = (cx - r).toInt()
        val y0 = (cy - r).toInt()
        val sz = (2 * r).toInt()
        if (x0 < 0 || y0 < 0 || sz <= 0 || x0 + sz > rgb.cols() || y0 + sz > rgb.rows()) return false
        val roi = Mat(rgb, Rect(x0, y0, sz, sz))
        val hsv = Mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_RGB2HSV)
        val mask = Mat()
        Core.inRange(hsv, Scalar(84.0, 90.0, 170.0), Scalar(102.0, 255.0, 255.0), mask)
        val f = Core.countNonZero(mask).toDouble() / (sz * sz)
        hsv.release(); mask.release()
        return f > 0.3
    }

    /** Panneau flottant en fin de série : 1 à 10 chasses de plus d'un seul toucher, ou arrêt. */
    private fun showMorePanel() {
        if (moreView != null) return
        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xEE263238.toInt())
                cornerRadius = 16 * d
            }
        }
        box.addView(TextView(this).apply {
            text = "Chasses terminées ($huntDone). En relancer :"
            setTextColor(Color.WHITE)
            textSize = 15f
        })
        for (row in 0 until 2) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 1..5) {
                val n = row * 5 + k
                line.addView(Button(this).apply {
                    text = n.toString()
                    setOnClickListener {
                        huntTarget = huntDone + n
                        huntAsked = false
                        huntStartedAt = 0L
                        removeMorePanel()
                        log("hunt : $n chasse(s) de plus demandée(s), total $huntTarget")
                    }
                }, LinearLayout.LayoutParams((52 * d).toInt(), (48 * d).toInt()))
            }
            box.addView(line)
        }
        box.addView(Button(this).apply {
            text = "Arrêter"
            setOnClickListener {
                removeMorePanel()
                pauseBot("Hunt arrêté")
            }
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        try {
            wm.addView(box, lp)
            moreView = box
        } catch (_: Exception) {
        }
    }

    private fun removeMorePanel() {
        try { moreView?.let { wm.removeView(it) } } catch (_: Exception) {}
        moreView = null
    }

    // ---------- Bouton flottant ----------

    private fun addOverlay() {
        val size = (44 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC2E7D32.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = size + (6 * resources.displayMetrics.density).toInt() // en haut à gauche, à droite du bouton ☰
            y = 150 // même hauteur que ☰ : ne masque ni les pins ni le panneau des marches
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggle(tv)
            }
            true
        }
        wm.addView(tv, lp)
        overlay = tv
        overlayParams = lp
    }

    private var menuBtn: TextView? = null

    /** Petit bouton ☰ en haut de l'écran : arrête le bot et revient au menu de l'application. */
    private fun addMenuButton() {
        val size = (44 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "☰"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC37474F.toInt())
            }
            setOnClickListener { openMenu() }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 150
        }
        wm.addView(tv, lp)
        menuBtn = tv
    }

    /** Retour au menu : on met en pause et on cache les boutons, mais la capture reste autorisée (pas de nouvelle demande). */
    private fun openMenu() {
        running = false
        removeMorePanel()
        showOverlays(false)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun toggle(tv: TextView) {
        running = !running
        if (running) {
            lastClick = SystemClock.uptimeMillis()
            if (mode == "farm") {
                if (farmIdx >= farmTasks.size) { planAdjusted = false; planTries = 0; resetGrandCheck(); buildPlan(null) }
                taskStartedAt = 0L
                searchedAt = 0L
            }
            if (mode == "daily" && dailyFinished) resetDaily()
            if (mode == "marchand" && marchandFinished) resetMarchandSolo()
            if (mode == "epreuves" && epFinished) resetEpreuves()
            if (mode == "heal" && healFinished) resetHeal()
        }
        tv.text = if (running) "⏸" else "▶"
        (tv.background as GradientDrawable)
            .setColor(if (running) 0xCCC62828.toInt() else 0xCC2E7D32.toInt())
    }

    // ---------- Divers ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("bot", "Auto-clic", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, BotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "bot")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Visual Clicker actif")
            .setContentText("Touchez cette notification pour arrêter")
            .setContentIntent(stop)
            .setOngoing(true)
            .build()
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        alive = false
        running = false
        thread?.let {
            handler.removeCallbacksAndMessages(null)
        }
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        overlay = null
        try { menuBtn?.let { wm.removeView(it) } } catch (_: Exception) {}
        menuBtn = null
        removeMorePanel()
        vd?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        templates.forEach { it.release() }
        templates.clear()
        lastGray?.release()
        lastRgb?.release()
        lastPanel?.release()
        panelHeader?.release()
        popupTitle?.release()
        popupX?.release()
        hexTpl?.release()
        goldTpl?.release()
        farm.values.forEach { it.release() }
        farm.clear()
        gates.forEach { it.release() }
        compass?.release()
        lastMeat?.release()
        gates.clear()
        super.onDestroy()
    }
}
