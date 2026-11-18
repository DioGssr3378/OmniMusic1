package com.example.omnimusic

import android.Manifest
import android.animation.ValueAnimator
import android.animation.AnimatorListenerAdapter
import android.animation.Animator
import android.media.audiofx.Visualizer
import android.content.Context
import android.content.res.ColorStateList
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.util.Locale
import kotlin.math.*

class MainActivity : Activity() {
    data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)
    private data class CardUi(val songIndex: Int, val container: LinearLayout, val shape: GradientDrawable, val artwork: AlbumArt)
    private val songs = mutableListOf<Song>()
    private val cards = mutableListOf<CardUi>()
    private val green = Color.rgb(118, 255, 81)
    private val appBackgroundColor = Color.rgb(9, 13, 12)
    private val cardColor = Color.rgb(35, 39, 39)
    private val muted = Color.rgb(168, 178, 175)
    private lateinit var font: Typeface
    private lateinit var inter: Typeface
    private lateinit var interBold: Typeface
    private lateinit var list: LinearLayout
    private lateinit var playButton: TextView
    private lateinit var playerTitle: TextView
    private lateinit var playerArtist: TextView
    private lateinit var playerArt: AlbumArt
    private lateinit var durationLabel: TextView
    private lateinit var progress: SegmentedProgress
    private lateinit var fullProgress: SegmentedProgress
    private lateinit var fullTitle: TextView
    private lateinit var fullArt: AlbumArt
    private lateinit var fullTime: TextView
    private lateinit var fullFavorite: PixelHeart
    private lateinit var fullPlay: ActionButton
    private lateinit var fullPanel: LinearLayout
    private lateinit var spectrum: Spectrum
    private lateinit var rootPage: LinearLayout
    private lateinit var folderList: LinearLayout
    private lateinit var folderHeader: TextView
    private lateinit var dock: LinearLayout
    private lateinit var scrollView: ScrollView
    private var expanded = false
    private var section = "Inicio"
    private val favorites by lazy { getSharedPreferences("omnimusic_favorites", Context.MODE_PRIVATE) }
    private var visualizer: Visualizer? = null
    private var player: MediaPlayer? = null
    private var current = -1
    private val handler = Handler(Looper.getMainLooper())
    private val artworkCache = mutableMapOf<Long, Bitmap?>()
    private lateinit var fallback: Bitmap

    private fun dp(n: Float) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun bg(color: Int, radius: Float, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1f), border)
    }
    private fun text(value: String, size: Float, color: Int, type: Typeface = inter) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color); typeface = type
        ellipsize = TextUtils.TruncateAt.END; maxLines = 1
    }

    // Mantiene el efecto hexagonal expansivo de la versión anterior.
    private fun hexPulse(target: View, radiusDp: Float) {
        val overlay = object : View(this) {
            var phase = 1f; var tx = 0f; var ty = 0f
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green }
            private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = green; style = Paint.Style.STROKE; strokeWidth = dp(1.5f).toFloat()
            }
            override fun onDraw(canvas: Canvas) {
                if (phase >= 1f) return
                val saved = canvas.save()
                canvas.clipPath(Path().apply {
                    addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()),
                        dp(radiusDp).toFloat(), dp(radiusDp).toFloat(), Path.Direction.CW)
                })
                val reach = listOf(hypot(tx, ty), hypot(width-tx, ty),
                    hypot(tx, height-ty), hypot(width-tx, height-ty)).maxOrNull() ?: 0f
                val r = reach * (0.08f + phase * 1.15f)
                val hex = Path()
                repeat(6) { i ->
                    val a = Math.toRadians((i * 60 - 30).toDouble())
                    val x = tx + (r * cos(a)).toFloat()
                    val y = ty + (r * sin(a)).toFloat()
                    if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
                }
                hex.close()
                fill.alpha = (95 * (1-phase)).toInt(); line.alpha = (175 * (1-phase)).toInt()
                canvas.drawPath(hex, fill); canvas.drawPath(hex, line)
                canvas.restoreToCount(saved)
            }
        }
        target.foreground = object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: Canvas) {
                overlay.layout(0, 0, bounds.width(), bounds.height()); overlay.draw(canvas)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        var anim: ValueAnimator? = null
        target.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                anim?.cancel(); overlay.tx = event.x; overlay.ty = event.y; overlay.phase = 0f
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 340; interpolator = DecelerateInterpolator(1.15f)
                    addUpdateListener { overlay.phase = it.animatedValue as Float; target.invalidate() }
                    start()
                }
            }
            false
        }
    }

    // Portada con esquinas animables y trazo verde que recorre la circunferencia.
    private inner class AlbumArt : View(this@MainActivity) {
        var bitmap: Bitmap? = null
            set(value) { field = value; invalidate() }
        var roundness = 0f
            set(value) { field = value; invalidate() }
        private var sweep = -1f
        private var sweepAnimator: ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun morph(selected: Boolean, animate: Boolean = true) {
            val goal = if (selected) 1f else 0f
            if (!animate) { roundness = goal; return }
            ValueAnimator.ofFloat(roundness, goal).apply {
                duration = 300; interpolator = DecelerateInterpolator()
                addUpdateListener { roundness = it.animatedValue as Float }
                start()
            }
            if (selected) {
                sweepAnimator?.cancel()
                sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 540; interpolator = DecelerateInterpolator()
                    addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else { sweepAnimator?.cancel(); sweep = -1f }
        }
        override fun onDraw(canvas: Canvas) {
            val bmp = bitmap ?: fallback
            val inset = dp(3f).toFloat()
            val rect = RectF(inset, inset, width - inset, height - inset)
            val radius = dp(12f) + (min(rect.width(), rect.height())/2 - dp(12f)) * roundness
            val save = canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
            val scale = max(rect.width()/bmp.width, rect.height()/bmp.height)
            val w = bmp.width * scale; val h = bmp.height * scale
            paint.color = Color.WHITE; paint.alpha = 255; paint.style = Paint.Style.FILL
            canvas.drawBitmap(bmp, null, RectF(rect.centerX()-w/2, rect.centerY()-h/2,
                rect.centerX()+w/2, rect.centerY()+h/2), paint)
            canvas.restoreToCount(save)
            if (sweep in 0f..1f) {
                paint.color = green; paint.alpha = (255 * (1f - sweep * .35f)).toInt()
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2.5f).toFloat()
                val circle = RectF(inset+dp(1f), inset+dp(1f), width-inset-dp(1f), height-inset-dp(1f))
                canvas.drawArc(circle, -90f + sweep*360f, 100f, false, paint)
                paint.style = Paint.Style.FILL; paint.alpha = 255
            }
        }
    }

    // Barra segmentada táctil con un pequeño spray de hexágonos SOLO en el frente del avance.
    private inner class SegmentedProgress : View(this@MainActivity) {
        var fraction = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            isClickable = true
            contentDescription = "Deslizar para avanzar o retroceder la canción"
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        val f = (e.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        fraction = f
                        val mp = player
                        if (mp != null) try {
                            mp.seekTo((mp.duration * f).toInt())
                            refreshProgress()
                        } catch (_: IllegalStateException) { }
                        true
                    }
                    else -> false
                }
            }
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val top = dp(6f).toFloat(); val bottom = h - dp(6f)
            val clip = Path().apply {
                addRoundRect(RectF(0f, top, w, bottom), dp(6f).toFloat(), dp(6f).toFloat(), Path.Direction.CW)
            }
            val save = canvas.save()
            canvas.clipPath(clip)
            p.style = Paint.Style.FILL
            p.color = Color.rgb(49, 65, 56)
            canvas.drawRect(0f, top, w, bottom, p)
            val tip = w * fraction
            p.color = green
            canvas.drawRect(0f, top, tip, bottom, p)

            // Las partículas nacen en la punta, se dispersan hacia atrás, y se apagan.
            // No forman una trama estática a lo largo de toda la barra.
            if (tip > dp(4f)) {
                val clock = android.os.SystemClock.uptimeMillis() / 1000f
                val cy = (top + bottom) / 2f
                for (i in 0 until 15) {
                    val age = (clock * 1.8f + i * 0.137f) % 1f
                    val x = tip + dp(3f) - age * dp(37f)
                    val drift = sin(i * 2.399f + clock * 0.65f) * dp(7f) * age
                    val y = cy + drift
                    if (x < 0f || x > w) continue
                    val radius = dp(2.9f) * (1f - age * 0.56f)
                    val hex = Path()
                    for (corner in 0 until 6) {
                        val angle = Math.toRadians((corner * 60 - 30).toDouble())
                        val px = x + radius * cos(angle).toFloat()
                        val py = y + radius * sin(angle).toFloat()
                        if (corner == 0) hex.moveTo(px, py) else hex.lineTo(px, py)
                    }
                    hex.close()
                    p.color = Color.argb((235 * (1f - age) * (1f - age)).toInt().coerceIn(0, 255), 202, 255, 175)
                    canvas.drawPath(hex, p)
                }
            }
            p.color = appBackgroundColor
            p.strokeWidth = dp(3f).toFloat()
            repeat(3) { i ->
                val x = w * (i + 1) / 4f
                canvas.drawLine(x + dp(3f), top, x - dp(3f), bottom, p)
            }
            canvas.restoreToCount(save)
            if (isShown && player?.isPlaying == true) postInvalidateDelayed(45L)
        }
    }

    // Analizador FFT real: columnas apiladas, ancladas ABAJO de la portada.
    // Ataque rápido y caída más lenta para acompañar los golpes sin parpadeos.
    private inner class Spectrum : View(this@MainActivity) {
        private val columns = 30
        private val display = FloatArray(columns)
        private val targets = FloatArray(columns)
        private val paint = Paint().apply { isAntiAlias = false }
        private var active = false
        private var autoPeak = 0.27f
        private var lastFrameMs = 0L

        fun setActive(value: Boolean) {
            active = value
            lastFrameMs = android.os.SystemClock.uptimeMillis()
            if (active) postInvalidateOnAnimation()
        }
        fun reset() {
            display.fill(0f); targets.fill(0f); autoPeak = .27f
            invalidate()
        }
        fun updateFft(fft: ByteArray) {
            if (!active || fft.size < 64) return
            // Cada par de bytes es una frecuencia compleja (real e imaginaria).
            val available = fft.size / 2 - 1
            val maxBin = min(available, 220)
            var peak = 0f
            val band = FloatArray(columns)
            for (i in 0 until columns) {
                val low = max(1, (2.0 * (maxBin / 2.0).pow(i.toDouble() / columns)).toInt())
                val high = min(maxBin, max(low + 1,
                    (2.0 * (maxBin / 2.0).pow((i + 1.0) / columns)).toInt()))
                var sum = 0f
                var count = 0
                for (bin in low until high) {
                    val re = fft[bin * 2].toInt().toFloat()
                    val im = fft[bin * 2 + 1].toInt().toFloat()
                    sum += sqrt(re * re + im * im)
                    count++
                }
                // Logaritmo comprime los golpes intensos y revela las frecuencias suaves.
                val mag = if (count > 0) sum / count else 0f
                val valLog = (ln(1f + mag * 2.2f) / ln(1f + 128f * 2.2f)).coerceIn(0f, 1f)
                band[i] = valLog
                peak = max(peak, valLog)
            }
            autoPeak = max(peak, autoPeak * .973f).coerceAtLeast(.18f)
            for (i in 0 until columns) {
                val boost = (band[i] / autoPeak).coerceIn(0f, 1.25f)
                targets[i] = ((boost - .055f).coerceAtLeast(0f) * 1.07f).coerceIn(0f, 1f)
            }
            postInvalidateOnAnimation()
        }
        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (lastFrameMs == 0L) 16f else (now - lastFrameMs).coerceIn(1L, 50L).toFloat()
            lastFrameMs = now
            val slot = width.toFloat() / columns
            val barWidth = slot * .73f
            val unit = dp(3.6f).toFloat().coerceAtLeast(2f)
            val gap = dp(1.7f).toFloat().coerceAtLeast(1f)
            val bottom = height - dp(4f).toFloat()
            val maxUnits = ((height * .94f) / (unit + gap)).toInt().coerceAtLeast(1)
            val playing = player?.isPlaying == true
            for (i in 0 until columns) {
                val goal = if (playing) targets[i] else 0f
                val factor = if (goal > display[i]) .54f else .085f
                val smoothing = 1f - (1f - factor).pow(dt / 16f)
                display[i] += (goal - display[i]) * smoothing
                val blocks = (display[i] * maxUnits).toInt().coerceIn(0, maxUnits)
                val left = i * slot + (slot - barWidth) / 2f
                for (j in 0 until blocks) {
                    val y = bottom - (j + 1) * (unit + gap)
                    val energy = j.toFloat() / maxUnits
                    paint.color = if (energy > .65f) Color.rgb(193, 255, 132)
                        else if (energy > .3f) green else Color.rgb(68, 211, 75)
                    paint.alpha = 225
                    canvas.drawRect(left, y, left + barWidth, y + unit, paint)
                }
            }
            if (active && isShown) postInvalidateOnAnimation()
        }
    }

    // Controles minimalistas. Solo el símbolo y el hexágono expansivo al pulsar.
    private inner class ActionButton(val symbol: String, val primary: Boolean = false) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var paused = true
        private var previous = "▶"
        private var transition = 1f
        private var animator: ValueAnimator? = null
        private var tapScale = 1f
        init { isClickable = true; isFocusable = true }
        fun animateAction(toPaused: Boolean, quick: Boolean = false) {
            if (!primary) { flash(); return }
            if (paused == toPaused) return
            animator?.cancel()
            previous = if (paused) "▶" else "Ⅱ"
            paused = toPaused
            transition = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (quick) 150 else 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { transition = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        fun flash() {
            animate().cancel()
            animate().scaleX(.84f).scaleY(.84f).setDuration(85).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(135).start()
            }.start()
        }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(if (primary) 34f else 29f).toFloat()
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.style = Paint.Style.FILL
            val baseline = cy - (paint.ascent() + paint.descent()) / 2f
            if (primary && transition < 1f) {
                val save = c.save()
                c.scale(1f - .16f * transition, 1f - .16f * transition, cx, cy)
                paint.color = green; paint.alpha = (255 * (1f - transition)).toInt()
                c.drawText(previous, cx, baseline, paint)
                c.restoreToCount(save)
            }
            val glyph = if (primary) (if (paused) "▶" else "Ⅱ") else symbol
            val incomingAlpha = if (primary) transition else 1f
            val save = c.save()
            if (primary) {
                val zoom = .84f + .16f * transition
                c.scale(zoom, zoom, cx, cy)
            }
            paint.color = green; paint.alpha = (255 * incomingAlpha).toInt()
            c.drawText(glyph, cx, baseline, paint)
            c.restoreToCount(save)
            paint.alpha = 255
        }
    }

    // Corazón pixel-art: silueta calculada como píxeles, borde vacío o relleno verde.
    private inner class PixelHeart : View(this@MainActivity) {
        private val shapePixels = arrayOf(
            "..###.....###..",
            ".#####...#####.",
            "###############",
            "###############",
            ".#############.",
            "..###########..",
            "...#########...",
            "....#######....",
            ".....#####.....",
            "......###......",
            ".......#......."
        )
        private val p = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
        var filled = false
            set(value) { field = value; invalidate() }
        init { isClickable = true; isFocusable = true }
        override fun onDraw(c: Canvas) {
            val rows = shapePixels.size
            val cols = shapePixels[0].length
            val cell = min(width.toFloat() / (cols + 2), height.toFloat() / (rows + 2))
            val left = (width - cols * cell) / 2f
            val top = (height - rows * cell) / 2f
            for (y in 0 until rows) for (x in 0 until cols) {
                if (shapePixels[y][x] != '#') continue
                val edge = (x == 0 || shapePixels[y][x - 1] != '#') ||
                    (x == cols - 1 || shapePixels[y][x + 1] != '#') ||
                    (y == 0 || shapePixels[y - 1][x] != '#') ||
                    (y == rows - 1 || shapePixels[y + 1][x] != '#')
                if (filled || edge) {
                    p.color = green
                    c.drawRect(left + x * cell, top + y * cell,
                        left + (x + 1) * cell, top + (y + 1) * cell, p)
                }
            }
        }
    }

    private fun isFavorite(id: Long) = favorites.getBoolean(id.toString(), false)
    private fun toggleFavorite() {
        val song = songs.getOrNull(current) ?: return
        favorites.edit().putBoolean(song.id.toString(), !isFavorite(song.id)).apply()
        updateFavorite()
        if (section == "Favoritos") showSection("Favoritos")
    }
    private fun updateFavorite() {
        if (::fullFavorite.isInitialized) fullFavorite.filled = songs.getOrNull(current)?.let { isFavorite(it.id) } == true
    }
    private fun refreshProgress() {
        val mp = player ?: return
        try {
            val duration = mp.duration.coerceAtLeast(0)
            val pos = mp.currentPosition.coerceAtLeast(0)
            val f = if (duration > 0) pos.toFloat() / duration else 0f
            progress.fraction = f
            if (::fullProgress.isInitialized) fullProgress.fraction = f
            durationLabel.text = "${time(pos)} / ${time(duration)}"
            if (::fullTime.isInitialized) fullTime.text = "${time(pos)} / ${time(duration)}"
        } catch (_: IllegalStateException) { }
    }
    private fun setupVisualizer() {
        visualizer?.release(); visualizer = null
        val mp = player ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            visualizer = Visualizer(mp.audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) { }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft != null && ::spectrum.isInitialized) {
                            val copy = fft.copyOf()
                            spectrum.post { spectrum.updateFft(copy) }
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (_: Exception) { visualizer?.release(); visualizer = null }
    }

    private fun cover(song: Song): Bitmap? {
        if (artworkCache.containsKey(song.id)) return artworkCache[song.id]
        val image = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, song.uri)
                retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } finally { retriever.release() }
        } catch (_: Exception) { null }
        artworkCache[song.id] = image
        return image
    }
    private fun time(ms: Int): String {
        val seconds = (ms.coerceAtLeast(0)/1000)
        return String.format(Locale.getDefault(), "%d:%02d", seconds/60, seconds%60)
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (::progress.isInitialized) refreshProgress()
            handler.postDelayed(this, 60L)
        }
    }
    private fun updatePlayButton() {
        if (::playButton.isInitialized) {
            val playing = player?.isPlaying == true
            playButton.text = if (playing) "Ⅱ" else "▶"
            playButton.contentDescription = if (playing) "Pausar" else "Reproducir"
            if (::fullPlay.isInitialized) fullPlay.animateAction(!playing)
        }
    }
    private fun togglePlayback() {
        val p = player
        if (p == null) { if (songs.isNotEmpty()) play(0); return }
        if (p.isPlaying) p.pause() else p.start()
        updatePlayButton()
    }
    private fun selectCard(index: Int) {
        cards.forEachIndexed { i, ui ->
            val selected = ui.songIndex == index
            val start = ui.shape.cornerRadius
            val end = dp(if (selected) 40f else 22f).toFloat()
            ValueAnimator.ofFloat(start, end).apply {
                duration = 300; interpolator = DecelerateInterpolator()
                addUpdateListener { ui.shape.cornerRadius = it.animatedValue as Float }
                start()
            }
            ui.artwork.morph(selected)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = appBackgroundColor; window.navigationBarColor = appBackgroundColor
        font = try { Typeface.createFromAsset(assets, "CrashLandingBB.ttf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        inter = try { Typeface.createFromAsset(assets, "Inter-Regular.otf") } catch (_: Exception) { Typeface.DEFAULT }
        interBold = try { Typeface.createFromAsset(assets, "Inter-SemiBold.otf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        fallback = assets.open("album_fallback.png").use { BitmapFactory.decodeStream(it) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.appBackgroundColor)
            setPadding(dp(16f), dp(28f), dp(16f), dp(12f))
        }
        page.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(16f), insets.systemWindowInsetTop + dp(28f), dp(16f),
                insets.systemWindowInsetBottom + dp(12f))
            insets
        }
        rootPage = page
        folderHeader = text("CARPETAS", 18f, muted, font)
        folderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = text("OMNI MUSIC", 36f, green, font).apply {
            setShadowLayer(dp(7f).toFloat(), 0f, 0f, 0x5576FF51)
        }
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val shuffle = text("⤨", 29f, green, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER; background = bg(cardColor, 16f)
            contentDescription = "Reproducir canción aleatoria"
            isClickable = true; isFocusable = true
            setOnClickListener { if (songs.isNotEmpty()) play(songs.indices.random()) }
        }
        top.addView(shuffle, LinearLayout.LayoutParams(dp(52f), dp(48f)))
        hexPulse(shuffle, 16f)
        page.addView(top)
        page.addView(text("TU BIBLIOTECA  /  ARCHIVOS LOCALES", 17f, muted, font).apply {
            setPadding(0, dp(2f), 0, dp(16f))
        })
        page.addView(folderHeader)
        val scroll = ScrollView(this).apply { clipToPadding = false }
        scrollView = scroll
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        page.addView(folderList)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = bg(Color.BLACK, 25f, Color.rgb(43, 64, 50))
            elevation = dp(9f).toFloat()
        }
        val info = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        playerArt = AlbumArt()
        info.addView(playerArt, LinearLayout.LayoutParams(dp(62f), dp(62f)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), 0, 0, 0) }
        playerTitle = text("Seleccioná una canción", 15f, Color.WHITE, interBold)
        playerArtist = text("OmniMusic", 12f, muted)
        titles.addView(playerTitle); titles.addView(playerArtist)
        info.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        dock.addView(info)
        progress = SegmentedProgress()
        dock.addView(progress, LinearLayout.LayoutParams(-1, dp(30f)).apply { topMargin = dp(8f) })
        durationLabel = text("0:00 / 0:00", 11f, muted).apply { gravity = Gravity.END }
        dock.addView(durationLabel)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun smallControl(label: String, primary: Boolean = false, action: () -> Unit): TextView {
            val button = text(label, 24f, green, Typeface.DEFAULT_BOLD).apply {
                gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            controls.addView(button, LinearLayout.LayoutParams(0, dp(52f), if (primary) 1.15f else 1f))
            hexPulse(button, 18f)
            return button
        }
        smallControl("◀◀") { if (songs.isNotEmpty()) play((current - 1 + songs.size) % songs.size) }
        playButton = smallControl("▶", true) { togglePlayback() }
        smallControl("▶▶") { if (songs.isNotEmpty()) play((current + 1) % songs.size) }
        dock.addView(controls)
        dock.setOnClickListener { openFullPlayer() }
        info.setOnClickListener { openFullPlayer() }
        playerTitle.setOnClickListener { openFullPlayer() }
        playerArtist.setOnClickListener { openFullPlayer() }
        page.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        fullPanel = buildFullPlayer()
        page.addView(fullPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        fullPanel.visibility = View.GONE
        setContentView(page)
        showSection("Inicio")
        handler.post(ticker)
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) load() else requestPermissions(arrayOf(permission), 7)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) load()
            else Toast.makeText(this, "Se necesita acceso al audio para encontrar MP3", Toast.LENGTH_LONG).show()
        } else if (requestCode == 8 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) setupVisualizer()
    }
    private fun load() {
        songs.clear(); cards.clear(); list.removeAllViews()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.MIME_TYPE)
        contentResolver.query(base, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeCol) ?: ""
                if (mime != "audio/mpeg" && mime != "audio/mp3") continue
                val id = cursor.getLong(idCol)
                songs.add(Song(id, cursor.getString(titleCol) ?: "Sin título",
                    cursor.getString(artistCol) ?: "Artista desconocido", ContentUris.withAppendedId(base, id)))
            }
        }
        showSection(section)
    }
    private fun showSection(which: String) {
        section = which
        if (!::list.isInitialized) return
        list.removeAllViews(); cards.clear(); folderList.removeAllViews()
        val home = which == "Inicio"
        folderHeader.visibility = if (home) View.VISIBLE else View.GONE
        folderList.visibility = if (home) View.VISIBLE else View.GONE
        if (home) {
            fun folder(icon: String, label: String, action: () -> Unit) {
                val row = text("$icon   $label", 21f, green, interBold).apply {
                    setPadding(dp(16f), dp(20f), dp(16f), dp(20f))
                    background = bg(cardColor, 20f, Color.rgb(56, 66, 62))
                    isClickable = true; setOnClickListener { action() }
                }
                folderList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })
                hexPulse(row, 20f)
            }
            folder("♡", "Favoritos") { showSection("Favoritos") }
            folder("▣", "Dispositivo") { showSection("Dispositivo") }
            folder("↻", "Caché · WIP") { Toast.makeText(this, "Caché: próximamente", Toast.LENGTH_SHORT).show() }
            return
        }
        val back = text("‹  $which", 21f, green, font).apply {
            setPadding(dp(8f), dp(8f), 0, dp(20f))
            setOnClickListener { showSection("Inicio") }
        }
        list.addView(back)
        val indices = songs.indices.filter { which != "Favoritos" || isFavorite(songs[it].id) }
        if (indices.isEmpty()) list.addView(text("No hay canciones en esta carpeta.", 15f, muted))
        indices.forEach { index ->
            val song = songs[index]
            val drawable = bg(cardColor, 22f, Color.rgb(56, 66, 62))
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(12f), dp(10f), dp(12f))
                background = drawable; isClickable = true; isFocusable = true
                setOnClickListener { play(index) }
            }
            val art = AlbumArt().apply { bitmap = cover(song) }
            item.addView(art, LinearLayout.LayoutParams(dp(62f), dp(62f)))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10f), 0, 0, 0)
            }
            labels.addView(text(song.title, 16f, Color.WHITE, interBold).apply { maxLines = 2 })
            labels.addView(text(if (song.artist.equals("<unknown>", true)) "Artista desconocido" else song.artist, 13f, muted))
            item.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
            cards.add(CardUi(index, item, drawable, art))
            if (index == current) { drawable.cornerRadius = dp(40f).toFloat(); art.morph(true, false) }
            hexPulse(item, 40f)
        }
    }
    private fun openFullPlayer() {
        if (current !in songs.indices) return
        expanded = true
        spectrum.setActive(true)
        fullPanel.visibility = View.VISIBLE
        dock.visibility = View.GONE
        folderList.visibility = View.GONE
        folderHeader.visibility = View.GONE
        scrollView.visibility = View.GONE
        fullPanel.alpha = 0f
        fullPanel.translationY = dp(55f).toFloat()
        fullPanel.animate().alpha(1f).translationY(0f).setDuration(260).start()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8)
        } else setupVisualizer()
    }
    private fun closeFullPlayer() {
        expanded = false
        spectrum.setActive(false)
        visualizer?.release(); visualizer = null
        fullPanel.visibility = View.GONE
        dock.visibility = View.VISIBLE
        scrollView.visibility = View.VISIBLE
        showSection(section)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (expanded) closeFullPlayer() else if (section != "Inicio") showSection("Inicio") else super.onBackPressed()
    }
    private fun buildFullPlayer(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = bg(Color.BLACK, 24f, Color.rgb(43, 64, 50))
        }
        val close = text("⌄  VOLVER", 16f, green, font).apply {
            gravity = Gravity.START; setOnClickListener { closeFullPlayer() }
        }
        panel.addView(close, LinearLayout.LayoutParams(-1, dp(40f)))
        val artBox = android.widget.FrameLayout(this)
        fullArt = AlbumArt()
        artBox.addView(fullArt, android.widget.FrameLayout.LayoutParams(-1, -1))
        // Superposición verdosa translúcida sobre la carátula.
        val tint = View(this).apply { setBackgroundColor(Color.argb(52, 65, 240, 62)) }
        artBox.addView(tint, android.widget.FrameLayout.LayoutParams(-1, -1))
        spectrum = Spectrum()
        // Ocupa toda la portada. Los rectángulos crecen desde el borde inferior.
        artBox.addView(spectrum, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        panel.addView(artBox, LinearLayout.LayoutParams(-1, 0, 1f))
        val info = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fullTitle = text("", 20f, Color.WHITE, interBold)
        info.addView(fullTitle, LinearLayout.LayoutParams(0, -2, 1f))
        fullFavorite = PixelHeart().apply {
            contentDescription = "Agregar o quitar de favoritos"
            setOnClickListener { toggleFavorite() }
        }
        info.addView(fullFavorite, LinearLayout.LayoutParams(dp(50f), dp(54f)))
        hexPulse(fullFavorite, 14f)
        panel.addView(info)
        fullProgress = SegmentedProgress()
        panel.addView(fullProgress, LinearLayout.LayoutParams(-1, dp(36f)))
        fullTime = text("0:00 / 0:00", 12f, muted).apply { gravity = Gravity.END }
        panel.addView(fullTime)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun add(symbol: String, primary: Boolean, action: (ActionButton) -> Unit): ActionButton {
            val b = ActionButton(symbol, primary)
            buttons.addView(b, LinearLayout.LayoutParams(0, dp(76f), 1f))
            b.setOnClickListener { action(b) }
            hexPulse(b, 18f)
            return b
        }
        add("◀◀", false) { b -> b.flash(); if (songs.isNotEmpty()) play((current - 1 + songs.size) % songs.size) }
        fullPlay = add("▶", true) { togglePlayback() }
        add("▶▶", false) { b -> b.flash(); if (songs.isNotEmpty()) play((current + 1) % songs.size) }
        panel.addView(buttons)
        return panel
    }
    private fun play(index: Int) {
        if (index !in songs.indices) return
        try {
            val next = MediaPlayer().apply {
                setDataSource(this@MainActivity, songs[index].uri)
                setOnCompletionListener { if (songs.isNotEmpty()) play((current+1)%songs.size) }
                prepare(); start()
            }
            visualizer?.release(); visualizer = null
            if (::spectrum.isInitialized) spectrum.reset()
            player?.release(); player = next; current = index
            val song = songs[index]
            playerTitle.text = song.title
            fullTitle.text = song.title
            fullArt.bitmap = cover(song)
            fullArt.morph(true)
            updateFavorite()
            playerArtist.text = if (song.artist.equals("<unknown>", true)) "Artista desconocido" else song.artist
            playerArt.bitmap = cover(song); playerArt.morph(true)
            selectCard(index)
            progress.fraction = 0f
            durationLabel.text = "0:00 / ${time(next.duration)}"
            updatePlayButton()
            if (expanded) setupVisualizer()
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo reproducir esta canción", Toast.LENGTH_SHORT).show()
            updatePlayButton()
        }
    }
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        visualizer?.release(); visualizer = null
        if (::spectrum.isInitialized) spectrum.setActive(false)
        player?.release(); player = null
        super.onDestroy()
    }
}
