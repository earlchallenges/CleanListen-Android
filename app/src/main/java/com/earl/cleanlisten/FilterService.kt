package com.earl.cleanlisten

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * Live filter, same idea as CleanListen on the Mac:
 *   other apps' audio --(playback capture)--> 5 s delay buffer --> speaker
 *                                   \--> Vosk speech-to-text (on the phone)
 * Swear words found by speech-to-text are muted in the buffer before they
 * reach the speaker. Nothing is saved: audio only lives in a ~15 s memory
 * ring that is constantly overwritten.
 *
 * The original app keeps playing on media volume, so we turn media volume to
 * 0 and play the cleaned copy on the accessibility volume (which Android only
 * separates from media while VolumeService is enabled). Playback capture is
 * taken before volume, so it still hears the app at 0.
 */
class FilterService : Service() {

    companion object {
        const val RATE = 48000
        const val ASR_RATE = 16000
        const val PAD = 0.12            // seconds of extra mute around a word
        const val RING_SEC = 16
        private const val CH_ID = "filter"
        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, FilterService::class.java)
                .putExtra("code", code).putExtra("data", data)
            ctx.startForegroundService(i)
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, FilterService::class.java))
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var model: Model? = null
    @Volatile private var alive = false
    private val threads = ArrayList<Thread>()

    // Stereo ring buffer indexed by absolute frame number.
    private val ringFrames = RATE * RING_SEC
    private val ring = ShortArray(ringFrames * 2)
    @Volatile private var written = 0L       // frames captured so far
    private class Chunk(val frame: Long, val pcm: ShortArray)
    private val asrQueue = ArrayBlockingQueue<Chunk>(400)
    @Volatile private var dropped = false

    // Mute spans in absolute frames; guarded by synchronized(spans).
    private val spans = ArrayList<LongArray>()

    private var savedMusicVol = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (alive || intent == null) return START_NOT_STICKY
        startAsForeground()
        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data: Intent = intent.getParcelableExtra("data") ?: run { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)
        alive = true
        State.running = true
        State.status = "Loading speech model…"
        StorageService.unpack(this, "model-en-us", "model",
            { m -> model = m; begin() },
            { e -> State.status = "Model error: ${e.message}"; stopSelf() })
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ID, "Filter running", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH_ID)
            .setContentTitle("CleanListen is filtering")
            .setContentText("Swear words are muted. Tap to open.")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun begin() {
        if (!alive) return
        val proj = projection ?: return
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val minIn = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        try {
            record = AudioRecord.Builder().setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minIn, RATE)) // ~0.25 s
                .setAudioPlaybackCaptureConfig(cfg).build()
        } catch (e: SecurityException) {
            State.status = "Microphone permission is needed"; stopSelf(); return
        }

        val outAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()
        val outFmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val minOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder().setAudioAttributes(outAttr).setAudioFormat(outFmt)
            .setBufferSizeInBytes(minOut * 2).setTransferMode(AudioTrack.MODE_STREAM).build()

        muteOriginal()
        record!!.startRecording()
        track!!.play()
        State.status = "Filtering — play something in any app"

        threads += Thread(::captureLoop, "capture")
        threads += Thread(::asrLoop, "asr")
        threads += Thread(::playLoop, "play")
        threads.forEach { it.start() }
    }

    /** Media volume -> 0; cleaned copy plays on accessibility volume. */
    private fun muteOriginal() {
        val am = getSystemService(AudioManager::class.java)
        savedMusicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (State.volumeServiceOn) {
            val aMax = am.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY)
            val target = maxOf(1, savedMusicVol * aMax / maxOf(1, musicMax))
            try { am.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, target, 0) } catch (_: Exception) {}
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
    }

    private fun restoreVolume() {
        if (savedMusicVol < 0) return
        val am = getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0)
        savedMusicVol = -1
    }

    private fun captureLoop() {
        val rec = record ?: return
        val buf = ShortArray(960 * 2)                // 20 ms stereo
        while (alive) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val frames = n / 2
            // into ring
            var pos = ((written % ringFrames) * 2).toInt()
            for (i in 0 until n) { ring[pos] = buf[i]; pos++; if (pos == ring.size) pos = 0 }
            // 48k stereo -> 16k mono for speech-to-text, and a loudness meter
            val mono = ShortArray(frames / 3)
            var peak = 0
            for (k in mono.indices) {
                var s = 0
                for (j in 0 until 3) { val f = (k * 3 + j) * 2; s += buf[f] + buf[f + 1] }
                val v = s / 6
                mono[k] = v.toShort(); if (abs(v) > peak) peak = abs(v)
            }
            State.level = State.level * 0.8f + 0.2f * (peak / 32768f)
            if (!asrQueue.offer(Chunk(written, mono))) dropped = true
            written += frames
        }
    }

    private fun asrLoop() {
        val list = Wordlist(assets.open("wordlist.txt").bufferedReader().readText(), State.tier)
        fun newRec() = Recognizer(model, ASR_RATE.toFloat()).apply { setWords(true); setPartialWords(true) }
        var rec = newRec()
        var startFrame = -1L                         // ring frame where the recognizer's time 0 is
        while (alive) {
            val c = asrQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (dropped) {                           // fell behind: restart so timing stays exact
                dropped = false; asrQueue.clear(); rec.close(); rec = newRec(); startFrame = -1; continue
            }
            if (startFrame < 0) startFrame = c.frame
            val final = rec.acceptWaveForm(c.pcm, c.pcm.size)
            val json = JSONObject(if (final) rec.result else rec.partialResult)
            val arr = json.optJSONArray(if (final) "result" else "partial_result") ?: continue
            val words = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Word(o.getString("word"), o.getDouble("start"), o.getDouble("end"))
            }
            if (final && words.isNotEmpty()) State.lastWords = words.joinToString(" ") { it.text }.takeLast(160)
            for ((s, e) in findMatches(list, words)) addSpan(startFrame, s, e)
        }
        rec.close()
    }

    private fun addSpan(startFrame: Long, s: Double, e: Double) {
        val a = startFrame + ((s - PAD) * RATE).toLong()
        val b = startFrame + ((e + PAD) * RATE).toLong()
        synchronized(spans) {
            for (sp in spans) if (a <= sp[1] && b >= sp[0]) {   // overlaps: merge
                sp[0] = min(sp[0], a); sp[1] = maxOf(sp[1], b); return
            }
            spans.add(longArrayOf(a, b))
            State.muted++
        }
    }

    private fun playLoop() {
        val tr = track ?: return
        val block = 960                               // 20 ms
        val out = ShortArray(block * 2)
        val delay = State.delaySec * RATE.toLong()
        var played = -1L
        var gain = 1f
        val step = 1f / (RATE * 0.008f)               // 8 ms fade in/out
        while (alive) {
            val w = written
            if (played < 0) { if (w >= delay) played = w - delay else { Thread.sleep(10); continue } }
            if (w - played < delay) { Thread.sleep(5); continue }
            if (w - played > delay + RATE * 2) played = w - delay   // fell behind: catch up
            val cur = synchronized(spans) {
                spans.removeAll { it[1] < played }
                spans.map { it.clone() }
            }
            var pos = ((played % ringFrames) * 2).toInt()
            for (i in 0 until block) {
                val f = played + i
                val mute = cur.any { f >= it[0] && f <= it[1] }
                gain = if (mute) maxOf(0f, gain - step) else min(1f, gain + step)
                out[i * 2] = (ring[pos] * gain).toInt().toShort()
                out[i * 2 + 1] = (ring[pos + 1] * gain).toInt().toShort()
                pos += 2; if (pos == ring.size) pos = 0
            }
            tr.write(out, 0, out.size)
            played += block
        }
    }

    override fun onDestroy() {
        alive = false
        threads.forEach { it.join(1000) }
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        try { track?.stop() } catch (_: Exception) {}
        track?.release()
        projection?.stop()
        model?.close()
        restoreVolume()
        State.running = false
        State.status = "Off"
        State.level = 0f
        super.onDestroy()
    }
}
