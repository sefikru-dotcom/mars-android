package ru.mars.mobile

import android.media.AudioManager
import android.media.MediaPlayer
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.MusicTrack
import ru.mars.network.repository.MusicRepository
import java.net.HttpURLConnection
import java.net.URL

class MusicActivity : ComponentActivity() {
    private lateinit var repository: MusicRepository; private lateinit var results: LinearLayout; private lateinit var title: TextView; private lateinit var time: TextView; private lateinit var seek: SeekBar; private lateinit var playButton: Button; private var state = MusicPlayerState(); private var tracks = mutableListOf<MusicTrack>(); private var index = -1; private var player: MediaPlayer? = null; private var job: Job? = null
    private val ticker = object : Runnable { override fun run() { player?.let { runCatching { seek.progress = it.currentPosition; time.text = "${fmt(it.currentPosition)} / ${fmt(it.duration)}" } }; seek.postDelayed(this, 500) } }
    override fun onCreate(b: Bundle?) { super.onCreate(b); repository = MusicRepository(BASE_URL); setContentView(buildView()); seek.post(ticker); refreshStatus(); loadHome() }
    override fun onDestroy() { seek.removeCallbacks(ticker); player?.release(); job?.cancel(); super.onDestroy() }
    private fun buildView(): View { val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(12)); setBackgroundColor(BACKGROUND) }; val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }; header.addView(TextView(this).apply { text = "Mars Music"; textSize = 25f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0,-2,1f)); header.addView(Button(this).apply { text="Назад"; setOnClickListener { finish() } }); root.addView(header)
        val search = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }; val input=EditText(this).apply { hint="Найти трек или исполнителя"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(MUTED) }; search.addView(input,LinearLayout.LayoutParams(0,dp(54),1f)); search.addView(Button(this).apply { text="Найти"; setOnClickListener { search(input.text.toString()) } }); root.addView(search); results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; root.addView(ScrollView(this).apply { addView(results); isFillViewport=true },LinearLayout.LayoutParams(-1,0,1f))
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(10),dp(12),0); background=bg(SURFACE) }; title=TextView(this).apply { text="Ничего не играет"; textSize=16f; setTextColor(Color.WHITE) }; panel.addView(title); time=TextView(this).apply { text="00:00 / 00:00"; setTextColor(MUTED) }; panel.addView(time); seek=SeekBar(this).apply { max=1; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(b:SeekBar?,p:Int,u:Boolean){ if(u) player?.seekTo(p) }; override fun onStartTrackingTouch(b:SeekBar?){}; override fun onStopTrackingTouch(b:SeekBar?){} }) }; panel.addView(seek); val controls=LinearLayout(this).apply { gravity=Gravity.CENTER }; controls.addView(Button(this).apply { text="⏮"; setOnClickListener{ previous() } }); playButton=Button(this).apply { text="▶"; setOnClickListener{ if(player?.isPlaying==true) pause() else player?.let{ it.start(); syncPlay(true) } } }; controls.addView(playButton); controls.addView(Button(this).apply { text="⏭"; setOnClickListener{ next() } }); panel.addView(controls); val vol=SeekBar(this).apply { max=100; progress=80; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(b:SeekBar?,p:Int,u:Boolean){ if(u){ player?.setVolume(p/100f,p/100f); command{ repository.setVolume(p/100.0) } } }; override fun onStartTrackingTouch(b:SeekBar?){}; override fun onStopTrackingTouch(b:SeekBar?){} }) }; panel.addView(TextView(this).apply { text="Громкость"; setTextColor(MUTED) }); panel.addView(vol); root.addView(panel); return root }
    private fun loadHome(){ job=lifecycleScope.launch { val found=withContext(Dispatchers.IO){runCatching{fetch("$BASE_URL/api/charts")}.getOrNull() ?: runCatching{fetch("$BASE_URL/api/home")}.getOrNull()}; found?.let{ showTracks(parseTracks(it)) } } }
    private fun search(q:String){ if(q.isBlank())return; job?.cancel(); job=lifecycleScope.launch { runCatching{repository.search(q)}.onSuccess{showTracks(it)}.onFailure{title.text="Поиск недоступен"} } }
    private fun showTracks(items:List<MusicTrack>){ tracks.clear(); tracks.addAll(items); results.removeAllViews(); if(items.isEmpty()){ results.addView(TextView(this).apply{text="Треки не найдены";setTextColor(MUTED);setPadding(0,dp(18),0,dp(18))});return }; items.forEachIndexed{ i,t-> val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(9),0,dp(9))}; row.addView(TextView(this).apply{text="${t.title}\n${t.artist} · ${t.duration}";textSize=16f;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(0,-2,1f)); row.addView(Button(this).apply{text="▶";setOnClickListener{playTrack(i)}});results.addView(row) } }
    private fun playTrack(i:Int){ if(i !in tracks.indices)return; index=i; val t=tracks[i]; player?.release(); player=MediaPlayer().apply{setAudioStreamType(AudioManager.STREAM_MUSIC);        setOnPreparedListener { seek.max = it.duration; it.start(); playButton.text = "⏸"; title.text = "${t.title} — ${t.artist}" }
        setOnCompletionListener { next() }
        setOnErrorListener { _, _, _ ->
            title.text = "Поток недоступен"
            true
        }
        try {
            setDataSource("$BASE_URL/api/stream/${t.id}")
            prepareAsync()
        } catch (e: Exception) {
            title.text = "Поток недоступен"
        }
    }; command{repository.play()} }
    private fun pause(){player?.pause();playButton.text="▶";syncPlay(false)}; private fun next(){if(tracks.isNotEmpty())playTrack((index+1)%tracks.size) else command{repository.next()}}; private fun previous(){if(tracks.isNotEmpty())playTrack((index-1+tracks.size)%tracks.size) else command{repository.previous()}}
    private fun syncPlay(playing:Boolean){command{if(playing)repository.play() else repository.pause()}}; private fun refreshStatus(){job=lifecycleScope.launch{runCatching{repository.getStatus()}.onSuccess{state=it}}}; private fun command(a:suspend()->Unit){lifecycleScope.launch{runCatching{a()}}}
    private fun fetch(url:String):JSONObject{val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=8000;c.readTimeout=10000;return c.inputStream.bufferedReader().use{JSONObject(it.readText())}.also{c.disconnect()}}
    private fun parseTracks(o:JSONObject):List<MusicTrack>{val a=o.optJSONArray("tracks")?:o.optJSONArray("results")?:o.optJSONObject("data")?.optJSONArray("tracks")?:JSONArray();return (0 until a.length()).mapNotNull{val x=a.optJSONObject(it)?:return@mapNotNull null;MusicTrack(x.optString("id",x.optString("track_id")),x.optString("title","Без названия"),x.optString("artist","Неизвестный артист"),x.optString("duration","--:--"),x.optString("thumbnail").takeIf{it.isNotBlank()})}.filter{it.id.isNotBlank()} }
    private fun fmt(ms:Int)=String.format("%02d:%02d",ms/60000,(ms/1000)%60); private fun bg(c:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(14).toFloat()}; private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    companion object{private const val BASE_URL="https://marscore.ru/music";private const val BACKGROUND=0xff10131a.toInt();private const val SURFACE=0xff1b202b.toInt();private const val MUTED=0xffaab2c0.toInt()}
}
