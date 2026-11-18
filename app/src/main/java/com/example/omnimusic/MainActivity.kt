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
    private lateinit var fullFavorite: TextView
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

    // Barra táctil con hexágonos confinados al avance y cortes diagonales.
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
            val save = canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(RectF(0f, top, w, bottom), dp(6f).toFloat(), dp(6f).toFloat(), Path.Direction.CW) })
            p.style = Paint.Style.FILL
            p.color = Color.rgb(49, 65, 56); canvas.drawRect(0f, top, w, bottom, p)
            val fill = w * fraction
            p.color = green; canvas.drawRect(0f, top, fill, bottom, p)
            val r = dp(3.2f).toFloat()
            val step = dp(15f).toFloat()
            var x = step / 2
            while (x < fill) {
                val cy = (top + bottom) / 2
                val path = Path()
                repeat(6) { i ->
                    val a = Math.toRadians((i * 60 - 30).toDouble())
                    val px = x + r * cos(a).toFloat(); val py = cy + r * sin(a).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); p.color = Color.argb(110, 8, 74, 20); canvas.drawPath(path, p)
                x += step
            }
            p.color = appBackgroundColor; p.strokeWidth = dp(3f).toFloat()
            repeat(3) { i ->
                val pos = w * (i + 1) / 4f
                canvas.drawLine(pos + dp(3f), top, pos - dp(3f), bottom, p)
            }
            canvas.restoreToCount(save)
        }
    }

    // Espectro real: toma la forma de onda de la sesión de audio de MediaPlayer.
    private inner class Spectrum : View(this@MainActivity) {
        private val levels = FloatArray(24) { .08f }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun update(wave: ByteArray) {
            if (wave.isEmpty()) return
            for (i in levels.indices) {
                val from = i * wave.size / levels.size
                val to = ((i + 1) * wave.size / levels.size).coerceAtMost(wave.size)
                var peak = 0f
                for (j in from until to) peak = max(peak, abs(wave[j].toInt().toFloat()) / 128f)
                levels[i] = levels[i] * .45f + peak * .55f
            }
            postInvalidate()
        }
        override fun onDraw(c: Canvas) {
            val slot = width.toFloat() / levels.size
            val unit = dp(4f).toFloat(); val gap = dp(2f).toFloat()
            for (i in levels.indices) {
                val count = (1 + levels[i] * 11).toInt().coerceIn(1, 12)
                for (j in 0 until count) {
                    paint.color = if (j >= count - 2) green else Color.rgb(49, 160 + (j * 6).coerceAtMost(80), 51)
                    val y = height / 2f + count * (unit + gap) / 2f - (j + 1) * (unit + gap)
                    c.drawRoundRect(i * slot + dp(1f), y, (i + 1) * slot - dp(1f), y + unit, dp(1.5f).toFloat(), dp(1.5f).toFloat(), paint)
                }
            }
        }
    }

    // Botón sin contorno: la forma verde nace en el centro y se convierte en rombo.
    private inner class ActionButton(val symbol: String, val primary: Boolean = false) : View(this@MainActivity) {
        var paused = false
            set(value) { field = value; invalidate() }
        private var morph = 0f
        private var rotation = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var anim: ValueAnimator? = null
        init { isClickable = true; isFocusable = true }
        fun animateAction(toPaused: Boolean, quick: Boolean = false) {
            anim?.cancel()
            val target = if (toPaused) 1f else 0f
            anim = ValueAnimator.ofFloat(morph, target).apply {
                duration = if (quick) 260 else 470
                addUpdateListener {
                    morph = it.animatedValue as Float
                    rotation = morph * 45f
                    invalidate()
                }
                start()
            }
            paused = toPaused
        }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val size = min(width, height).toFloat() * .40f
            val radius = min(width, height) * (.04f + .42f * morph)
            if (morph > .001f) {
                val save = c.save()
                c.rotate(rotation, cx, cy)
                val s = size * (.2f + .8f * morph)
                paint.color = green; paint.style = Paint.Style.FILL
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, radius * (1f - morph * .85f), radius * (1f - morph * .85f), paint)
                c.restoreToCount(save)
            }
            paint.color = if (morph > .55f) Color.WHITE else green
            paint.textSize = dp(29f).toFloat(); paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER; paint.style = Paint.Style.FILL
            val glyph = if (primary) (if (paused) "▶" else "Ⅱ") else symbol
            c.drawText(glyph, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
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
        if (::fullFavorite.isInitialized) fullFavorite.text = if (songs.getOrNull(current)?.let { isFavorite(it.id) } == true) "♥" else "♡"
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
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {
                        if (waveform != null && ::spectrum.isInitialized) spectrum.post { spectrum.update(waveform) }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { }
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
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
        artBox.addView(spectrum, android.widget.FrameLayout.LayoutParams(-1, dp(140f), Gravity.CENTER))
        panel.addView(artBox, LinearLayout.LayoutParams(-1, 0, 1f))
        val info = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fullTitle = text("", 20f, Color.WHITE, interBold)
        info.addView(fullTitle, LinearLayout.LayoutParams(0, -2, 1f))
        fullFavorite = text("♡", 35f, green, Typeface.DEFAULT).apply {
            gravity = Gravity.CENTER; contentDescription = "Agregar o quitar de favoritos"
            isClickable = true; setOnClickListener { toggleFavorite() }
        }
        info.addView(fullFavorite, LinearLayout.LayoutParams(dp(50f), dp(54f)))
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
            return b
        }
        add("◀◀", false) { b -> b.animateAction(true, true); if (songs.isNotEmpty()) play((current - 1 + songs.size) % songs.size); b.postDelayed({ b.animateAction(false, true) }, 270) }
        fullPlay = add("▶", true) { togglePlayback() }
        add("▶▶", false) { b -> b.animateAction(true, true); if (songs.isNotEmpty()) play((current + 1) % songs.size); b.postDelayed({ b.animateAction(false, true) }, 270) }
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
        player?.release(); player = null
        super.onDestroy()
    }
}
