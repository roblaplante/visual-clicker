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
    private var statCand = 0
    private var statDone = 0
    private var statSeen = 0
    private var pinMemoryMs = 20 * 60_000L
    private class PinSeen(val x: Double, val y: Double, val t: Long)
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
            } catch (_: Exception) {
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
            if (now - lastIdleLog > 20_000) {
                lastIdleLog = now
                log("carte des pins : $statCand pins vus, $statDone cochés, $statSeen déjà lancés : rien à cliquer")
            }
            return
        }
        if (!enoughMeat(meat)) return
        lastClick = now
        pinsSeen.removeAll { now - it.t > pinMemoryMs }
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
    private class OcrLine(val text: String, val cy: Double)

    /** Texte ligne par ligne avec position. dark = texte sombre sur fond clair (fenêtres beiges du jeu). */
    private fun ocrLines(src: Mat, dark: Boolean): List<OcrLine> {
        return try {
            val gray = Mat()
            Imgproc.cvtColor(src, gray, if (src.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            val big = Mat()
            Imgproc.resize(gray, big, Size(), 4.0, 4.0, Imgproc.INTER_CUBIC)
            val bin = Mat()
            Imgproc.threshold(big, bin, if (dark) 150.0 else 170.0, 255.0, if (dark) Imgproc.THRESH_BINARY else Imgproc.THRESH_BINARY_INV)
            val padded = Mat()
            Core.copyMakeBorder(bin, padded, 20, 20, 20, 20, Core.BORDER_CONSTANT, Scalar(255.0))
            val bmp = Bitmap.createBitmap(padded.cols(), padded.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(padded, bmp)
            gray.release(); big.release(); bin.release(); padded.release()
            val res = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS)
            bmp.recycle()
            res.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                l.boundingBox?.let { OcrLine(l.text, ((it.top + it.bottom) / 2.0 - 20) / 4.0) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ocrText(src: Mat, dark: Boolean = false): String {
        return try {
            val gray = Mat()
            Imgproc.cvtColor(src, gray, if (src.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            val big = Mat()
            Imgproc.resize(gray, big, Size(), 4.0, 4.0, Imgproc.INTER_CUBIC)
            val bin = Mat()
            if (dark) Imgproc.threshold(big, bin, 150.0, 255.0, Imgproc.THRESH_BINARY) // texte sombre -> noir
            else Imgproc.threshold(big, bin, 170.0, 255.0, Imgproc.THRESH_BINARY_INV) // texte blanc -> noir
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
                if (f.length() > 30_000) f.writeText(f.readLines().takeLast(150).joinToString("\n", postfix = "\n"))
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
                if (h < 0.11 * sw || h > 0.185 * sw) continue // le pin hexagonal (tête de roi) est bien plus haut
                if (ri == 2 && hexX >= 0 && hexX >= x && hexX <= x + w && hexY >= y && hexY <= y + h) continue // c'est le boss
                val pw = 0.099 * sw // largeur d'un pin
                val subs = ArrayList<DoubleArray>() // (x gauche, largeur) de chaque pin du bloc
                if (w >= 0.085 * sw && w <= 0.125 * sw && h / w >= 1.1 && h / w <= 1.8 && a <= 0.0105 * sw * sw) {
                    subs.add(doubleArrayOf(x, w))
                } else if (w > 0.125 * sw && w <= 0.4 * sw && h / w < 1.05) {
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
                    if (pinsSeen.any { tnow - it.t < pinMemoryMs && abs(it.x - fx) < 0.3 * spw / SCALE && abs(it.y - fy) < 0.3 * spw / SCALE }) {
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

    private fun resetDaily() {
        dailyState = "OPEN"; dailyStep = 0; dailyStepAt = 0L; dailyTries = 0; dailyFinished = false
        dailyAttempts.clear()
    }

    private fun loadDaily() {
        for (path in listOf("daily/coffre_bulle.png", "daily/goutte.png", "daily/golem.png")) {
            val m = loadAsset(path) ?: continue
            farm[path.substringAfter('/').removeSuffix(".png")] = m
        }
        resetDaily()
        log("daily : démarrage")
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
                if (cityVisible && (dailyAttempts["ordres"] ?: 0) < 2 && redDotOnOrders(rgb)) {
                    dailyAttempts["ordres"] = (dailyAttempts["ordres"] ?: 0) + 1
                    dailyState = "ORDRES"
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
                // Puis l'événement « Assaut Rebelle » (golem rouge en haut à gauche, avec son minuteur)
                val golem = if (cityVisible && (dailyAttempts["combat"] ?: 0) < 2) farmMatch(screen, "golem", 0.8) else null
                if (golem != null) {
                    dailyAttempts["combat"] = (dailyAttempts["combat"] ?: 0) + 1
                    dailyState = "COMBAT"
                    dailyStep = 1
                    dailyBtn = 0
                    dailyActAt = now
                    dailyIdleAt = now
                    dailyStepAt = now
                    val (gx, gy) = farmCenter("golem", golem)
                    farmClick(gx, gy + (60 * w / 1080.0).toFloat(), "daily : Combat : icône Assaut Rebelle, ouverture")
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
                    if ("fontaine" in t) cands.add(Pair(l.cy, "fontaine")) // Collecte d'Essence d'Eau : Fontaine Vitale
                    if ("inactiv" in t) cands.add(Pair(l.cy, "conquete")) // Conquérir : Revenu d'inactivité
                    if ("creuset" in t || "creuseur" in t) creusetCy = maxOf(creusetCy, l.cy) // titre de section puis tâche : la plus basse
                    if ("triomphe" in t && "activit" in t) cands.add(Pair(l.cy, "triomphe")) // Triomphe d'activité quotidienne
                    // « Contrib. d'All. » : titre de section puis tâche (la ligne « Contrib 10/25 » n'a pas « all »)
                    if ("contrib" in t && "all" in t) contribCy = maxOf(contribCy, l.cy)
                }
                if (contribCy >= 0) cands.add(Pair(contribCy, "contrib"))
                if (creusetCy >= 0) cands.add(Pair(creusetCy, "creuset"))
                if (aventureCy >= 0) cands.add(Pair(aventureCy, "aventure"))
                cands.sortBy { it.first } // du haut vers le bas
                var launched = false
                for ((cy, key) in cands) {
                    if ((dailyAttempts[key] ?: 0) >= (if (key == "recrute") 1 else 2)) continue // un seul recrutement gratuit par passage
                    val rowY = (y0 + cy) / sc // centre de la ligne en coordonnées de référence
                    dailyAttempts[key] = (dailyAttempts[key] ?: 0) + 1
                    dailyState = key.uppercase()
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
                if (!launched) dailyFinish("Daily terminé : plus de tâche à faire")
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
            "TRIOMPHE" -> detectTriomphe(rgb, now)
            "FONTAINE" -> detectFontaine(screen, rgb, now)
            "COMBAT" -> detectCombat(screen, rgb, now)
            "CONQUETE" -> detectConquete(rgb, now)
            "MISSIONS" -> detectMissions(rgb, now)
            "COFFRES" -> detectCoffres(rgb, now)
            "CREUSET" -> detectCreuset(rgb, now)
            "MARCHAND" -> detectMarchand(rgb, now)
            "VIP" -> detectVip(rgb, now)
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
        intArrayOf(740, 1680, 610, 1655, 880, 1715) // Festivités
    )

    /**
     * Ordres : icône (balance) > « Ordre du Gouverneur » > pour chaque livre (Productivité, Travail Urgent,
     * Festivités) sans bandeau « Actif » / « Se recharge » : livre > « Aventure » > (récompenses) ; puis flèche
     * bleue pour revenir à la ville.
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
    private fun detectRecrute(rgb: Mat, now: Long) {
        if (now - dailyIdleAt > 10_000L) {
            log("daily : Recrutement avancé abandonné (10 s sans écran reconnu)")
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
        if ("prochain" in recruit || "gratuit" in recruit) {
            dailyIdleAt = now
            if (dailyStep == 0 && colorFrac(rgb, 287.0, 1690.0, Scalar(55.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0)) >= 0.3 && dailyBtn < 1) {
                dailyBtn++
                dailyStep = 1
                clickRef(287.0, 1690.0, "daily : Recrutement avancé : clic Recruter une fois (gratuit) n°$dailyBtn")
            } else {
                dailyStep = 3
                lastClick = now
                backArrow()
                log("daily : Recrutement : un seul recrutement par passage, retour")
            }
            return
        }
        // ville : terminé
        val bar = ocrRef(rgb, 0, 2270, 1080, 2335)
        val seen = listOf("conqu", "héros", "heros", "magasin", "alliance", "monde").count { it in bar }
        if (seen >= 2) {
            dailyIdleAt = now
            if (dailyStep >= 1) dailyTaskDone("Recruter des Héros : Avancé")
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
            if (dailyStep >= 20) { // retour de Conquête : on sort aussi de l'île
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
                val golem = farmMatch(screen, "golem", 0.8)
                if (golem != null) {
                    dailyActAt = now
                    val (gx, gy) = farmCenter("golem", golem)
                    farmClick(gx, gy + (60 * w / 1080.0).toFloat(), "daily : Combat : nouveau clic sur l'icône")
                }
            }
        }
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
    private var shopCheckDone = false
    private val shopVerify = HashSet<Int>()
    private var shopDlgFirst = 0L
    private var shopLastSig = ""
    private var shopSameCnt = 0
    private var shopUnknownAt = 0L
    private var shopSkipSig = Array(6) { "" }
    private var shopReads: List<Pair<Char, Int>> = emptyList()

    private fun resetShop() {
        shopSig = ""; shopWaitUntil = 0L; shopBuys = 0; shopOpened = -1; shopUnknownAt = 0L
        shopExpect = null; shopFail = 0; shopOpenTries = 0; shopLastSlot = -1; shopLastSig = ""; shopSameCnt = 0
        shopCheckDone = false; shopVerify.clear(); shopDlgFirst = 0L
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
            if (now - shopDlgFirst < 450L) return
            shopDlgFirst = 0L
            val dr = shopDialogRead(rgb)
            val exp = shopExpect
            // Achat seulement si la fenêtre confirme ce qu'on attendait : ressources, ou cristaux lus et <= 65.
            // Prix illisible, prix > 65, fenêtre inattendue ou type différent : on ferme sans acheter.
            val verifying = shopOpened in shopVerify // contrôle avant actualisation : la fenêtre a le dernier mot
            val ok = exp != null && when (dr.first) {
                'r' -> exp.first == 'r'
                'g' -> shopOk(dr.second) && exp.first == 'g' && (shopOk(exp.second) || verifying)
                else -> false
            }
            if (!ok) {
                if (shopOpened in 0..5) shopSkipSig[shopOpened] = shopSigOf(shopOpened)
                if (!verifying) shopFail++ // un contrôle qui confirme « trop cher » n'est pas un échec
                val why = "attendu ${exp?.let { shopDesc(it) } ?: "rien"}, lu ${shopDesc(dr)}"
                clickRef(983.0, 882.0, "daily : Marchand : fenêtre Acheter refusée ($why), fermée sans acheter")
                shopOpened = -1
                shopExpect = null
                shopWaitUntil = now + 900
                shopSig = ""
                return
            }
            shopBuys++
            shopCheckDone = false
            shopVerify.clear()
            clickRef(540.0, 1485.0, "daily : Marchand : achat ${if (dr.first == 'g') "${dr.second} cristaux" else "en ressources"}${if (verifying) " (lecture de la case corrigée par la fenêtre)" else ""} (n° $shopBuys)")
            shopOpened = -1
            shopExpect = null
            shopWaitUntil = now + 1600
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
            shopWaitUntil = now + 1200
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
        if (shopFail >= 6) { shopFinish("trop de fenêtres Acheter refusées : arrêt par sécurité"); return }
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
                shopWaitUntil = now + 1600
                shopSig = ""
                clickRef(shopCx[buy % 3].toDouble(), shopCy[buy / 3].toDouble(),
                    "daily : Marchand : case ${buy + 1} (ressources), achat direct (n° $shopBuys)")
                return
            }
            if (++shopOpenTries > 6) { shopFinish("la fenêtre Acheter ne s'ouvre pas : arrêt par sécurité"); return }
            shopWaitUntil = now + 2500
            clickRef(shopCx[buy % 3].toDouble(), shopCy[buy / 3].toDouble(),
                "daily : Marchand : case ${buy + 1} (${shopDesc(reads[buy])}), ouverture")
            return
        }
        if ((0..5).all { blocked(it) }) {
            shopUnknownAt = 0L
            val green = colorRect(rgb, 760, 440, 1030, 520, Scalar(40.0, 100.0, 120.0), Scalar(85.0, 255.0, 255.0))
            val orange = colorRect(rgb, 760, 440, 1030, 520, Scalar(8.0, 150.0, 180.0), Scalar(25.0, 255.0, 255.0))
            if (orange >= 0.3 && green < 0.1) {
                shopFinish("6 cases bloquées et bouton d'actualisation orange (payant) : fin")
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
                if (dailyBtn >= 40) { shopFinish("40 actualisations atteintes"); return }
                dailyBtn++
                shopSig = ""
                shopSkipSig = Array(6) { "" }
                shopCheckDone = false
                shopVerify.clear()
                shopWaitUntil = now + 2200
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
        for (dark in listOf(false, true)) {
            v = digitsOf(ocrText(num, dark))
            if (v >= 0) break
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
        if (now - lastClick < cooldown || marchandFinished) return
        val dlg = colorRect(rgb, 330, 1435, 750, 1535, Scalar(8.0, 150.0, 180.0), Scalar(25.0, 255.0, 255.0)) >= 0.35
        var tab = ocrRef(rgb, 0, 2215, 300, 2335, true)
        if ("march" !in tab && "nomade" !in tab) tab = ocrRef(rgb, 0, 2215, 300, 2335, false)
        val inShop = dlg || "march" in tab || "nomade" in tab
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
        farm.values.forEach { it.release() }
        farm.clear()
        gates.forEach { it.release() }
        compass?.release()
        lastMeat?.release()
        gates.clear()
        super.onDestroy()
    }
}
