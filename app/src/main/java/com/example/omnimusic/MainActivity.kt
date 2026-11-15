package com.example.omnimusic

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot

class MainActivity : Activity() {
    data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)
    private val songs = mutableListOf<Song>()
    private var player: MediaPlayer? = null
    private var current = -1
    private lateinit var list: LinearLayout
    private lateinit var now: TextView
    private lateinit var font: Typeface
    private lateinit var inter: Typeface
    private lateinit var interBold: Typeface
    private val green = Color.rgb(118, 255, 81)
    private val appBackgroundColor = Color.rgb(9, 13, 12)
    private val card = Color.rgb(35, 39, 39)
    private val muted = Color.rgb(168, 178, 175)

    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int, radius: Float, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (border != null) setStroke(dp(1f), border)
        }

    private lateinit var playButton: TextView

    // Dibuja un hexágono que se expande desde el punto pulsado y se desvanece.
    private fun enableHexPulse(target: View, radiusDp: Float) {
        val overlay = object : View(this) {
            var progress = 1f
            var touchX = 0f
            var touchY = 0f
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.FILL }
            private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = green; style = Paint.Style.STROKE; strokeWidth = dp(1.5f).toFloat()
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (progress >= 1f) return
                val save = canvas.save()
                val clip = Path().apply {
                    addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()),
                        dp(radiusDp).toFloat(), dp(radiusDp).toFloat(), Path.Direction.CW)
                }
                canvas.clipPath(clip)
                val maxRadius = listOf(
                    hypot(touchX, touchY), hypot(width - touchX, touchY),
                    hypot(touchX, height - touchY), hypot(width - touchX, height - touchY)
                ).maxOrNull() ?: 0f
                val radius = maxRadius * (0.08f + progress * 1.15f)
                val hex = Path()
                for (i in 0 until 6) {
                    val angle = Math.toRadians((60 * i - 30).toDouble())
                    val x = touchX + (radius * cos(angle)).toFloat()
                    val y = touchY + (radius * sin(angle)).toFloat()
                    if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
                }
                hex.close()
                val fade = (1f - progress).coerceIn(0f, 1f)
                fill.alpha = (95f * fade).toInt()
                stroke.alpha = (175f * fade).toInt()
                canvas.drawPath(hex, fill)
                canvas.drawPath(hex, stroke)
                canvas.restoreToCount(save)
            }
        }
        // El overlay es el foreground del control: no bloquea sus clics.
        val layer = object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: Canvas) {
                overlay.layout(0, 0, bounds.width(), bounds.height())
                overlay.draw(canvas)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
        target.foreground = layer
        var animator: ValueAnimator? = null
        target.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                animator?.cancel()
                overlay.touchX = event.x
                overlay.touchY = event.y
                overlay.progress = 0f
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 340L
                    interpolator = DecelerateInterpolator(1.15f)
                    addUpdateListener {
                        overlay.progress = it.animatedValue as Float
                        target.invalidate()
                    }
                    start()
                }
                target.invalidate()
            }
            false
        }
    }

    private fun updatePlayButton() {
        if (::playButton.isInitialized) {
            val playing = player?.isPlaying == true
            playButton.text = if (playing) "Ⅱ" else "▶"
            playButton.contentDescription = if (playing) "Pausar" else "Reproducir"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = appBackgroundColor
        window.navigationBarColor = appBackgroundColor
        font = try { Typeface.createFromAsset(assets, "CrashLandingBB.ttf") }
        catch (_: Exception) { Typeface.DEFAULT }
        inter = try { Typeface.createFromAsset(assets, "Inter-Regular.otf") } catch (_: Exception) { Typeface.DEFAULT }
        interBold = try { Typeface.createFromAsset(assets, "Inter-SemiBold.otf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(appBackgroundColor)
            setPadding(dp(16f), dp(28f), dp(16f), dp(12f))
        }
        // Respect the system status/navigation bars, including Android edge-to-edge.
        page.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(16f), insets.systemWindowInsetTop + dp(28f), dp(16f),
                insets.systemWindowInsetBottom + dp(12f))
            insets
        }

        val heading = TextView(this).apply {
            text = "OMNI MUSIC"
            textSize = 36f
            typeface = font
            setTextColor(green)
            setShadowLayer(dp(7f).toFloat(), 0f, 0f, 0x5576FF51)
        }
        page.addView(heading)

        val sub = TextView(this).apply {
            text = "TU BIBLIOTECA  /  ARCHIVOS LOCALES"
            textSize = 17f
            typeface = font
            setTextColor(Color.rgb(174, 185, 180))
            setPadding(0, dp(2f), 0, dp(16f))
        }
        page.addView(sub)

        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        now = TextView(this).apply {
            text = "♫  SIN REPRODUCCIÓN"
            setTextColor(green)
            textSize = 17f
            typeface = inter
            setPadding(dp(10f), dp(12f), dp(10f), dp(12f))
            maxLines = 2
        }
        page.addView(now)

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        fun control(label: String, primary: Boolean = false, action: () -> Unit): TextView {
            val b = TextView(this).apply {
                text = label
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (primary) Color.BLACK else green)
                background = shape(if (primary) green else card, 18f,
                    if (primary) null else Color.rgb(58, 72, 66))
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
                enableHexPulse(this, 18f)
            }
            val params = LinearLayout.LayoutParams(0, dp(58f), if (primary) 1.15f else 1f).apply {
                marginStart = dp(4f); marginEnd = dp(4f)
            }
            controls.addView(b, params)
            return b
        }
        control("◀◀") { if (songs.isNotEmpty()) play((current - 1 + songs.size) % songs.size) }
        playButton = control("▶", primary = true) {
            val p = player
            if (p == null && songs.isNotEmpty()) play(0)
            else if (p?.isPlaying == true) p.pause()
            else p?.start()
            updatePlayButton()
        }
        control("▶▶") { if (songs.isNotEmpty()) play((current + 1) % songs.size) }
        page.addView(controls)
        setContentView(page)

        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) load()
        else requestPermissions(arrayOf(permission), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) load()
        else Toast.makeText(this, "Se necesita acceso al audio para encontrar MP3", Toast.LENGTH_LONG).show()
    }

    private fun load() {
        songs.clear()
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
        list.removeAllViews()
        if (songs.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No se encontraron MP3 en la biblioteca del teléfono."
                setTextColor(Color.WHITE)
                typeface = font
                textSize = 16f
            })
        }
        songs.forEachIndexed { index, song ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(12f), dp(12f), dp(12f))
                background = shape(card, 16f, Color.rgb(56, 66, 62))
                isClickable = true
                isFocusable = true
                setOnClickListener { play(index) }
                enableHexPulse(this, 16f)
            }
            val symbol = TextView(this).apply {
                text = "♫"
                textSize = 23f
                setTextColor(green)
                gravity = Gravity.CENTER
                setPadding(0, 0, dp(12f), 0)
            }
            item.addView(symbol)
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = song.title
                textSize = 16f
                typeface = interBold
                setTextColor(Color.WHITE)
                maxLines = 2
            })
            labels.addView(TextView(this).apply {
                text = if (song.artist.equals("<unknown>", true)) "Artista desconocido" else song.artist
                textSize = 13f
                typeface = inter
                setTextColor(muted)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            item.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8f)
            })
        }
    }

    private fun play(index: Int) {
        if (index !in songs.indices) return
        current = index
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(this@MainActivity, songs[index].uri)
            setOnCompletionListener { if (songs.isNotEmpty()) play((current + 1) % songs.size) }
            prepare()
            start()
        }
        now.text = "♫  ${songs[index].title} — ${songs[index].artist}"
        updatePlayButton()
    }

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
