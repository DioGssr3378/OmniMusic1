package com.example.omnimusic

import android.Manifest
import android.animation.ValueAnimator
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
    private data class CardUi(val container: LinearLayout, val shape: GradientDrawable, val artwork: AlbumArt)
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

    // Cuatro segmentos con cortes diagonales; el relleno es continuo.
    private inner class SegmentedProgress : View(this@MainActivity) {
        var fraction = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val top = dp(4f).toFloat(); val bottom = h - dp(4f)
            val clip = Path().apply { addRoundRect(RectF(0f, top, w, bottom), dp(5f).toFloat(), dp(5f).toFloat(), Path.Direction.CW) }
            val save = canvas.save(); canvas.clipPath(clip)
            p.color = Color.rgb(49, 65, 56); canvas.drawRect(0f, top, w, bottom, p)
            p.color = green; canvas.drawRect(0f, top, w*fraction, bottom, p)
            p.color = Color.rgb(9, 13, 12); p.strokeWidth = dp(3f).toFloat()
            repeat(3) { i ->
                val x = w * (i+1)/4f
                canvas.drawLine(x + dp(3f), top, x - dp(3f), bottom, p)
            }
            canvas.restoreToCount(save)
        }
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
            val p = player
            if (p != null) {
                try {
                    val duration = p.duration.coerceAtLeast(0)
                    progress.fraction = if (duration > 0) p.currentPosition.toFloat()/duration else 0f
                    durationLabel.text = "${time(p.currentPosition)} / ${time(duration)}"
                } catch (_: IllegalStateException) { }
            }
            handler.postDelayed(this, 60L)
        }
    }
    private fun updatePlayButton() {
        if (::playButton.isInitialized) {
            val playing = player?.isPlaying == true
            playButton.text = if (playing) "Ⅱ" else "▶"
            playButton.contentDescription = if (playing) "Pausar" else "Reproducir"
        }
    }
    private fun selectCard(index: Int) {
        cards.forEachIndexed { i, ui ->
            val selected = i == index
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
            setBackgroundColor(appBackgroundColor)
            setPadding(dp(16f), dp(28f), dp(16f), dp(12f))
        }
        page.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(16f), insets.systemWindowInsetTop + dp(28f), dp(16f),
                insets.systemWindowInsetBottom + dp(12f))
            insets
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = text("OMNI MUSIC", 36f, green, font).apply {
            setShadowLayer(dp(7f).toFloat(), 0f, 0f, 0x5576FF51)
        }
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val shuffle = text("⤨", 29f, green, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER; background = bg(cardColor, 16f, Color.rgb(56, 70, 63))
            contentDescription = "Reproducir canción aleatoria"
            isClickable = true; isFocusable = true
            setOnClickListener { if (songs.isNotEmpty()) play(songs.indices.random()) else Toast.makeText(this@MainActivity, "No hay canciones", Toast.LENGTH_SHORT).show() }
        }
        top.addView(shuffle, LinearLayout.LayoutParams(dp(52f), dp(48f)))
        hexPulse(shuffle, 16f)
        page.addView(top)
        page.addView(text("TU BIBLIOTECA  /  ARCHIVOS LOCALES", 17f, muted, font).apply {
            setPadding(0, dp(2f), 0, dp(16f))
        })
        val scroll = ScrollView(this).apply { clipToPadding = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Panel inferior negro, redondeado y separado de los bordes.
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = bg(Color.BLACK, 25f, Color.rgb(43, 64, 50))
            elevation = dp(9f).toFloat()
        }
        val info = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        playerArt = AlbumArt().apply { bitmap = null }
        info.addView(playerArt, LinearLayout.LayoutParams(dp(62f), dp(62f)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10f), 0, 0, 0)
        }
        playerTitle = text("Seleccioná una canción", 15f, Color.WHITE, interBold)
        playerArtist = text("OmniMusic", 12f, muted, inter)
        titles.addView(playerTitle); titles.addView(playerArtist)
        info.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        dock.addView(info)
        progress = SegmentedProgress()
        dock.addView(progress, LinearLayout.LayoutParams(-1, dp(22f)).apply { topMargin = dp(8f) })
        durationLabel = text("0:00 / 0:00", 11f, muted).apply { gravity = Gravity.END }
        dock.addView(durationLabel)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun control(label: String, primary: Boolean = false, action: () -> Unit): TextView {
            val button = text(label, 24f, if (primary) Color.BLACK else green, Typeface.DEFAULT_BOLD).apply {
                gravity = Gravity.CENTER
                background = bg(if (primary) green else cardColor, 18f,
                    if (primary) null else Color.rgb(58, 72, 66))
                isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            controls.addView(button, LinearLayout.LayoutParams(0, dp(52f), if (primary) 1.15f else 1f).apply {
                marginStart = dp(4f); marginEnd = dp(4f)
            })
            hexPulse(button, 18f)
            return button
        }
        control("◀◀") { if (songs.isNotEmpty()) play((current-1+songs.size)%songs.size) }
        playButton = control("▶", true) {
            val p = player
            if (p == null && songs.isNotEmpty()) play(0)
            else if (p?.isPlaying == true) p.pause()
            else p?.start()
            updatePlayButton()
        }
        control("▶▶") { if (songs.isNotEmpty()) play((current+1)%songs.size) }
        dock.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        page.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        setContentView(page)
        handler.post(ticker)
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) load() else requestPermissions(arrayOf(permission), 7)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) load()
        else Toast.makeText(this, "Se necesita acceso al audio para encontrar MP3", Toast.LENGTH_LONG).show()
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
        if (songs.isEmpty()) list.addView(text("No se encontraron MP3.", 15f, Color.WHITE))
        songs.forEachIndexed { index, song ->
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
            cards.add(CardUi(item, drawable, art))
            hexPulse(item, 40f)
        }
    }
    private fun play(index: Int) {
        if (index !in songs.indices) return
        try {
            val next = MediaPlayer().apply {
                setDataSource(this@MainActivity, songs[index].uri)
                setOnCompletionListener { if (songs.isNotEmpty()) play((current+1)%songs.size) }
                prepare(); start()
            }
            player?.release(); player = next; current = index
            val song = songs[index]
            playerTitle.text = song.title
            playerArtist.text = if (song.artist.equals("<unknown>", true)) "Artista desconocido" else song.artist
            playerArt.bitmap = cover(song); playerArt.morph(true)
            selectCard(index)
            progress.fraction = 0f
            durationLabel.text = "0:00 / ${time(next.duration)}"
            updatePlayButton()
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo reproducir esta canción", Toast.LENGTH_SHORT).show()
            updatePlayButton()
        }
    }
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        player?.release(); player = null
        super.onDestroy()
    }
}
