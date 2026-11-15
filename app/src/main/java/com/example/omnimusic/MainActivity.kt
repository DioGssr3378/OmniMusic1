package com.example.omnimusic

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*

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
    private val background = Color.rgb(9, 13, 12)
    private val card = Color.rgb(35, 39, 39)
    private val muted = Color.rgb(168, 178, 175)

    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int, radius: Float, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (border != null) setStroke(dp(1f), border)
        }

    // Ripple hexagonal translúcido, centrado en el punto de contacto.
    private fun hexRipple(): RippleDrawable {
        val mask = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            override fun draw(canvas: Canvas) {
                val b = bounds
                val cx = b.exactCenterX()
                val cy = b.exactCenterY()
                val r = minOf(b.width(), b.height()) * 0.47f
                val path = Path()
                for (i in 0 until 6) {
                    val angle = Math.toRadians((60 * i - 30).toDouble())
                    val x = cx + (r * kotlin.math.cos(angle)).toFloat()
                    val y = cy + (r * kotlin.math.sin(angle)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(105, 118, 255, 81)), null, mask)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        font = try { Typeface.createFromAsset(assets, "CrashLandingBB.ttf") }
        catch (_: Exception) { Typeface.DEFAULT }
        inter = try { Typeface.createFromAsset(assets, "Inter-Regular.otf") } catch (_: Exception) { Typeface.DEFAULT }
        interBold = try { Typeface.createFromAsset(assets, "Inter-SemiBold.otf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
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
        fun control(label: String, primary: Boolean = false, action: () -> Unit) {
            val b = TextView(this).apply {
                text = label
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (primary) Color.BLACK else green)
                background = shape(if (primary) green else card, 18f,
                    if (primary) null else Color.rgb(58, 72, 66))
                foreground = hexRipple()
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            val params = LinearLayout.LayoutParams(0, dp(58f), if (primary) 1.15f else 1f).apply {
                marginStart = dp(4f); marginEnd = dp(4f)
            }
            controls.addView(b, params)
        }
        control("◀◀") { if (songs.isNotEmpty()) play((current - 1 + songs.size) % songs.size) }
        control("▶ / ❚❚", primary = true) {
            val p = player
            if (p == null && songs.isNotEmpty()) play(0)
            else if (p?.isPlaying == true) p.pause()
            else p?.start()
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
                foreground = hexRipple()
                isClickable = true
                isFocusable = true
                setOnClickListener { play(index) }
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
    }

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
