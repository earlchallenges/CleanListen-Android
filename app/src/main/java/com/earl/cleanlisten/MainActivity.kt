package com.earl.cleanlisten

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var words: TextView
    private lateinit var meter: ProgressBar
    private lateinit var go: Button
    private lateinit var setupBox: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        col.addView(TextView(this).apply {
            text = "CleanListen"; textSize = 32f; setTypeface(null, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Mutes swear words in anything playing on this phone, about 5 seconds behind. Nothing is recorded or saved."
            textSize = 16f; setPadding(0, 8, 0, pad)
        })

        // One-time setup: the separate volume.
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(255, 243, 205)); setPadding(pad / 2, pad / 2, pad / 2, pad / 2) }
        setupBox.addView(TextView(this).apply {
            textSize = 16f
            text = "One-time setup: turn on \"CleanListen volume\".\n\n" +
                "Tap the button, find CleanListen under Downloaded apps, and switch it on. " +
                "If Android says it's a restricted setting: open Settings > Apps > CleanListen, tap the ⋮ menu (top right) > Allow restricted settings, then try again."
        })
        setupBox.addView(Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(setupBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = pad })

        // Strength
        col.addView(TextView(this).apply { text = "What to mute"; textSize = 18f; setTypeface(null, Typeface.BOLD) })
        val tiers = RadioGroup(this)
        listOf("Strongest words only", "Normal (recommended)", "Also mild words + taking the Lord's name in vain").forEachIndexed { i, label ->
            tiers.addView(RadioButton(this).apply { text = label; id = i + 1; textSize = 16f })
        }
        tiers.check(State.tier)
        tiers.setOnCheckedChangeListener { _, id -> State.tier = id }
        col.addView(tiers)

        go = Button(this).apply {
            textSize = 22f; setPadding(0, pad, 0, pad)
            setOnClickListener { if (State.running) FilterService.stop(this@MainActivity) else askToStart() }
        }
        col.addView(go, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad })

        status = TextView(this).apply { textSize = 18f; setPadding(0, pad, 0, 4) }
        col.addView(status)
        col.addView(TextView(this).apply { text = "Sound coming in:"; textSize = 14f })
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(meter)
        words = TextView(this).apply { textSize = 14f; setTextColor(Color.GRAY); setPadding(0, pad / 2, 0, 0) }
        col.addView(words)

        col.addView(TextView(this).apply { text = "CleanListen volume"; textSize = 18f; setTypeface(null, Typeface.BOLD); setPadding(0, pad, 0, 0) })
        val am = getSystemService(android.media.AudioManager::class.java)
        col.addView(SeekBar(this).apply {
            max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ACCESSIBILITY)
            progress = am.getStreamVolume(android.media.AudioManager.STREAM_ACCESSIBILITY)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) try { am.setStreamVolume(android.media.AudioManager.STREAM_ACCESSIBILITY, v, 0) } catch (_: Exception) {}
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        col.addView(TextView(this).apply {
            textSize = 14f; setPadding(0, pad, 0, 0)
            text = "Tips:\n• When Android asks what to share, pick \"Entire screen\". Only the sound is used; nothing is recorded.\n" +
                "• While it's on, the phone's normal media volume is set to 0 on purpose. It goes back when you tap Stop.\n" +
                "• If the meter doesn't move, that app blocks other apps from hearing it (some do)."
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); tick() }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    private fun tick() {
        setupBox.visibility = if (State.volumeServiceOn) android.view.View.GONE else android.view.View.VISIBLE
        go.text = if (State.running) "Stop" else "Start filtering"
        go.setBackgroundColor(if (State.running) Color.rgb(200, 60, 60) else Color.rgb(40, 140, 80))
        go.setTextColor(Color.WHITE)
        status.text = State.status + if (State.running) "\nWords muted: ${State.muted}" else ""
        meter.progress = (State.level * 300).toInt().coerceAtMost(100)
        words.text = if (State.running && State.lastWords.isNotEmpty()) "Hearing: …${State.lastWords}" else ""
        ui.postDelayed(::tick, 250)
    }

    private fun askToStart() {
        if (!State.volumeServiceOn) {
            Toast.makeText(this, "Do the one-time setup first (yellow box).", Toast.LENGTH_LONG).show(); return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 2)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askToStart()
        else Toast.makeText(this, "CleanListen needs the audio permission to hear other apps.", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK && data != null) {
            State.muted = 0; State.lastWords = ""
            FilterService.start(this, resultCode, data)
        }
    }
}
