package com.example.omnimusic

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.content.ContentUris
import android.provider.MediaStore
import android.media.MediaPlayer
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import android.net.Uri

class MainActivity : Activity() {
    data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)
    private val songs = mutableListOf<Song>()
    private var player: MediaPlayer? = null
    private var current = -1
    private lateinit var list: LinearLayout
    private lateinit var now: TextView
    private val green = Color.rgb(118, 255, 81)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 16, 15)); setPadding(22, 28, 22, 12) }
        val heading = TextView(this).apply { text = "OMNI MUSIC"; textSize = 29f; setTextColor(green); setTypeface(null, Typeface.BOLD) }
        page.addView(heading)
        val sub = TextView(this).apply { text = "TU BIBLIOTECA  /  ARCHIVOS LOCALES"; textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, 8, 0, 22) }
        page.addView(sub)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        now = TextView(this).apply { text = "Sin reproducción"; setTextColor(Color.WHITE); textSize = 15f; setPadding(8, 14, 8, 12) }
        page.addView(now)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun control(label: String, action: () -> Unit) {
            val b = Button(this).apply { text = label; setTextColor(Color.BLACK); setBackgroundTintList(android.content.res.ColorStateList.valueOf(green)); setOnClickListener { action() } }
            controls.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        control("◀◀") { if (songs.isNotEmpty()) play((current - 1 + songs.size) % songs.size) }
        control("▶ / ❚❚") { val p = player; if (p == null && songs.isNotEmpty()) play(0) else if (p?.isPlaying == true) p.pause() else p?.start() }
        control("▶▶") { if (songs.isNotEmpty()) play((current + 1) % songs.size) }
        page.addView(controls)
        setContentView(page)
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) load() else requestPermissions(arrayOf(permission), 7)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) load()
        else Toast.makeText(this, "Se necesita acceso al audio para encontrar MP3", Toast.LENGTH_LONG).show()
    }
    private fun load() {
        songs.clear()
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.MIME_TYPE)
        contentResolver.query(base, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeCol) ?: ""
                if (mime != "audio/mpeg" && mime != "audio/mp3") continue
                val id = cursor.getLong(idCol)
                songs.add(Song(id, cursor.getString(titleCol) ?: "Sin título", cursor.getString(artistCol) ?: "Artista desconocido", ContentUris.withAppendedId(base, id)))
            }
        }
        list.removeAllViews()
        if (songs.isEmpty()) list.addView(TextView(this).apply { text = "No se encontraron MP3 en la biblioteca del teléfono."; setTextColor(Color.WHITE) })
        songs.forEachIndexed { index, song ->
            val item = TextView(this).apply {
                text = "◈  ${song.title}\n     ${song.artist}"
                textSize = 16f; setTextColor(Color.WHITE); setPadding(12, 16, 8, 16)
                setOnClickListener { play(index) }
            }
            list.addView(item)
            list.addView(View(this).apply { setBackgroundColor(Color.rgb(38, 56, 43)) }, LinearLayout.LayoutParams(-1, 1))
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
