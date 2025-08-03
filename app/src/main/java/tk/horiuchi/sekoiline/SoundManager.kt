package tk.horiuchi.sekoiline

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build

class SoundManager(context: Context) {

    private val soundPool: SoundPool
    private val soundMap = mutableMapOf<String, Int>()
    private val myContext: Context

    //private var enginePlayer: MediaPlayer? = null

    lateinit var soundPoolEngine: SoundPool
    var engineSoundId = 0
    var engineStreamId = 0

    init {
        myContext = context
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setAudioAttributes(audioAttributes)
            .setMaxStreams(8)
            .build()

        // 効果音読み込み
        soundMap["start_beep1"] = soundPool.load(context, R.raw.se_start_beep1, 1)
        soundMap["start_beep2"] = soundPool.load(context, R.raw.se_start_beep2, 1)
        soundMap["collision"] = soundPool.load(context, R.raw.se_collision, 1)
        soundMap["steer2"] = soundPool.load(context, R.raw.se_steer2, 1)
        soundMap["extend"] = soundPool.load(context, R.raw.se_extend, 1)
        soundMap["gameover"] = soundPool.load(context, R.raw.bgm_gameover, 1)

        // エンジン音 MediaPlayer
        //enginePlayer = MediaPlayer.create(context, R.raw.se_engine_loop).apply {
        //    isLooping = true
        //    setVolume(1.5f, 1.5f)
        //}
        initSound(context)
    }

    fun playSound(name: String, volume: Float = 1.0f) {
        soundMap[name]?.let {
            soundPool.play(it, volume, volume, 1, 0, 1.0f)
        }
    }

    fun initSound(context: Context) {
        soundPoolEngine = SoundPool.Builder()
            .setMaxStreams(4)
            .build()

        engineSoundId = soundPoolEngine.load(context, R.raw.se_engine_loop, 1)
    }

    fun playEngine() {
        // ループ再生（-1で無限ループ）
        engineStreamId = soundPoolEngine.play(engineSoundId, 1f, 1f, 1, -1, 1f)
    }

    fun setEnginePitch(pitch: Float) {
        // pitch: 0.5f〜2.0f の範囲で設定
        soundPoolEngine.setRate(engineStreamId, pitch.coerceIn(0.3f, 2.0f))
    }

    fun stopEngine() {
        soundPoolEngine.stop(engineStreamId)
    }

    fun release() {
        soundPool.release()
        soundPoolEngine.release()
        //enginePlayer?.release()
        //enginePlayer = null
    }
}
