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
import android.view.WindowManager
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
        private const val SCALE = 0.5 // on travaille en demi-résolution pour la vitesse
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
    private var waitCompassUntil = 0L
    private var minMeat = 20
    // Décalage du clic (en px d'un écran de 1080 de large) par rapport au centre de l'image reconnue :
    // sur « Victoire ! » on touche en dessous de la bannière (« Appuie n'importe où pour quitter »).
    // Boutons après lesquels on revient à la carte des pins par la boussole : fin d'un combat ou secours (tente).
    private val compassAfter = setOf("01_victoire.png", "07_secourir.png")
    private val clickOffsets = mapOf("01_victoire.png" to doubleArrayOf(0.0, 1000.0))
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
        if (projection != null) return START_NOT_STICKY

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
        if (!OpenCVLoader.initLocal()) {
            toast("OpenCV : échec du chargement")
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        thread = HandlerThread("bot").also { it.start() }
        handler = Handler(thread!!.looper)

        loadConfig()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, handler)

        setupDisplay()
        loadTemplates()
        loadGate()
        loadCompass()
        addOverlay()
        handler.post(tick)
        return START_NOT_STICKY
    }

    // ---------- Config & modèles ----------

    private fun loadConfig() {
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        threshold = sp.getInt("threshold", 80) / 100.0
        interval = sp.getLong("interval", 500L).coerceAtLeast(100L)
        cooldown = sp.getLong("cooldown", 1000L).coerceAtLeast(0L)
        minMeat = sp.getInt("minMeat", 20)
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
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < cooldown) return
        // 1) boutons du jeu (intégrés) et images ajoutées par l'utilisateur
        for ((i, t) in templates.withIndex()) {
            val mm = matchBest(screen, t) ?: continue
            if (mm.maxVal >= threshold) {
                var cx = ((mm.maxLoc.x + t.cols() / 2.0) / SCALE).toFloat()
                var cy = ((mm.maxLoc.y + t.rows() / 2.0) / SCALE).toFloat()
                val off = clickOffsets[templateNames[i]]
                if (off != null) {
                    cx += (off[0] * w / 1080.0).toFloat()
                    cy += (off[1] * w / 1080.0).toFloat()
                }
                if (templateNames[i] in compassAfter) waitCompassUntil = now + 30_000
                lastClick = now
                ClickAccessibilityService.instance?.click(cx, cy)
                toast("Clic (%.2f)".format(mm.maxVal))
                return
            }
        }
        // 2) après une victoire : boussole de la carte du monde pour revenir aux pins
        if (waitCompassUntil > now) {
            val c = compass
            val mm = if (c != null) matchBest(screen, c) else null
            if (c != null && mm != null && mm.maxVal >= threshold) {
                val cx = ((mm.maxLoc.x + c.cols() / 2.0) / SCALE).toFloat()
                val cy = ((mm.maxLoc.y + c.rows() / 2.0) / SCALE).toFloat()
                waitCompassUntil = 0L
                lastClick = now
                ClickAccessibilityService.instance?.click(cx, cy)
                toast("Retour à la carte des pins")
                return
            }
        } else {
            waitCompassUntil = 0L
        }
        // 3) écran des pins (« Mission de renseignements » / « Intel Mission »)
        if (gates.isEmpty()) return
        var onPinScreen = false
        for (g in gates) {
            val mm = matchBest(screen, g) ?: continue
            if (mm.maxVal >= 0.85) { onPinScreen = true; break }
        }
        if (!onPinScreen) return
        val pin = findPin(rgb) ?: return
        if (!enoughMeat(meat)) return
        lastClick = now
        ClickAccessibilityService.instance?.click(pin.first, pin.second)
        toast("Clic sur un pin")
    }

    /** Lit le compteur de viande ; pause le bot s'il est sous le minimum. Illisible = on continue. */
    private fun enoughMeat(meat: Mat): Boolean {
        if (minMeat <= 0) return true
        val v = readMeat(meat) ?: return true
        if (v >= minMeat) return true
        main.post { pauseBot("Plus assez de viande ($v < $minMeat) : pause") }
        return false
    }

    private fun readMeat(meat: Mat): Int? {
        return try {
            val gray = Mat()
            Imgproc.cvtColor(meat, gray, Imgproc.COLOR_RGBA2GRAY)
            val big = Mat()
            Imgproc.resize(gray, big, Size(), 4.0, 4.0, Imgproc.INTER_CUBIC)
            val bin = Mat()
            Imgproc.threshold(big, bin, 170.0, 255.0, Imgproc.THRESH_BINARY_INV) // texte blanc -> noir
            val padded = Mat()
            Core.copyMakeBorder(bin, padded, 20, 20, 20, 20, Core.BORDER_CONSTANT, Scalar(255.0))
            val bmp = Bitmap.createBitmap(padded.cols(), padded.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(padded, bmp)
            gray.release(); big.release(); bin.release(); padded.release()
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS).text
            bmp.recycle()
            Regex("\\d+").findAll(text).map { it.value }.maxByOrNull { it.length }?.toIntOrNull()
        } catch (_: Exception) {
            null
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
    private fun findPin(rgb: Mat): Pair<Float, Float>? {
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
            checks.add(
                doubleArrayOf(
                    cCents.get(j, 0)[0], cCents.get(j, 1)[0],
                    cStats.get(j, Imgproc.CC_STAT_AREA)[0]
                )
            )
        }
        checkMask.release(); cLabels.release(); cStats.release(); cCents.release()
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        var bestX = 0.0
        var bestY = Double.MAX_VALUE
        for ((lo, hi) in pinRanges) {
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
                if (w < 0.085 * sw || w > 0.125 * sw || h < 0.11 * sw || h > 0.185 * sw) continue
                if (h / w < 1.1 || h / w > 1.8 || a > 0.0105 * sw * sw) continue
                val done = checks.any {
                    abs(it[0] - (x + 0.81 * w)) < 0.35 * w &&
                        abs(it[1] - (y + 0.23 * w)) < 0.35 * w &&
                        it[2] >= 0.004 * w * w
                }
                if (done) continue
                val cy = y + 0.4 * h
                if (cy < bestY) {
                    bestY = cy
                    bestX = x + w / 2
                }
            }
            mask.release(); labels.release(); stats.release(); cents.release()
        }
        hsv.release(); k5.release(); k3.release()
        if (bestY == Double.MAX_VALUE) return null
        return Pair((bestX / SCALE).toFloat(), (bestY / SCALE).toFloat())
    }

    // ---------- Bouton flottant ----------

    private fun addOverlay() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "▶"
            textSize = 22f
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
            x = 0
            y = 300
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

    private fun toggle(tv: TextView) {
        running = !running
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
        running = false
        thread?.let {
            handler.removeCallbacksAndMessages(null)
        }
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        overlay = null
        vd?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        templates.forEach { it.release() }
        templates.clear()
        lastGray?.release()
        lastRgb?.release()
        gates.forEach { it.release() }
        compass?.release()
        lastMeat?.release()
        gates.clear()
        super.onDestroy()
    }
}
