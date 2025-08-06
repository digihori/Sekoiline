package tk.horiuchi.sekoiline

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var gameView: GameView
    //private lateinit var statusText: TextView
    private lateinit var statusContainer: LinearLayout
    private val segMap = mutableMapOf<Char, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        supportActionBar?.title = "Sekoiline"
        setContentView(R.layout.activity_main)
        statusContainer = findViewById(R.id.statusContainer)

        // ステータスバーを隠す処理を安全に呼ぶ
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.overflowIcon?.setTint(Color.WHITE)

        gameView = findViewById(R.id.gameView)

        // 7セグ画像のリソース対応表
        segMap['0'] = R.drawable.seg7_0
        segMap['1'] = R.drawable.seg7_1
        segMap['2'] = R.drawable.seg7_2
        segMap['3'] = R.drawable.seg7_3
        segMap['4'] = R.drawable.seg7_4
        segMap['5'] = R.drawable.seg7_5
        segMap['6'] = R.drawable.seg7_6
        segMap['7'] = R.drawable.seg7_7
        segMap['8'] = R.drawable.seg7_8
        segMap['9'] = R.drawable.seg7_9
        segMap[' '] = R.drawable.seg7_blank

        // 上部表示のレイアウトを作成
        setupStatusLayout()
        // GameView から呼ばれる更新リスナーを設定
        gameView.setStatusUpdateListener { speedStr, timeStr, scoreStr ->
            update7Seg(speedStr, timeStr, scoreStr)
        }

        //setupButton(R.id.btn_accel) { gameView.onButtonPressed(GameInput.ACCELERATE) }
        //setupButton(R.id.btn_brake) { gameView.onButtonPressed(GameInput.BRAKE) }
        setupButton(R.id.btn_left) { gameView.onButtonPressed(GameInput.LEFT) }
        setupButton(R.id.btn_right) { gameView.onButtonPressed(GameInput.RIGHT) }
    }

    private lateinit var speedDigits: List<ImageView>
    private lateinit var timeDigits: List<ImageView>
    private lateinit var scoreDigits: List<ImageView>

    private fun setupStatusLayout() {
        statusContainer.removeAllViews()
        statusContainer.orientation = LinearLayout.VERTICAL
        statusContainer.gravity = Gravity.END

        // TIME
        val timeList = mutableListOf<ImageView>()
        statusContainer.addView(createStatusRow("TIME [sec]", 3, timeList))
        timeDigits = timeList

        // SPEED
        val spdList = mutableListOf<ImageView>()
        statusContainer.addView(createStatusRow("SPEED [km/h]", 3, spdList))
        speedDigits = spdList

        // SCORE
        val scoreList = mutableListOf<ImageView>()
        statusContainer.addView(createStatusRow("SCORE", 4, scoreList))
        scoreDigits = scoreList
    }

    private fun createStatusRow(
        label: String,
        digitCount: Int,
        outDigits: MutableList<ImageView>
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.BOTTOM
        }

        // ラベル
        val labelView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(labelView)

        // 7seg
        val rightLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val digits = createDigitViews(digitCount)
        outDigits.addAll(digits)
        digits.forEach { rightLayout.addView(it) }

        row.addView(rightLayout)
        return row
    }

    private fun createDigitViews(count: Int): List<ImageView> {
        return List(count) {
            ImageView(this).apply {
                setImageResource(segMap[' ']!!) // 初期はブランク
                layoutParams = LinearLayout.LayoutParams(100, 160) // サイズ調整
            }
        }
    }

    private fun update7Seg(speed: String, time: String, score: String) {
        // SPEED
        val sp = speed.padStart(3, ' ').takeLast(3)
        for (i in sp.indices) {
            speedDigits[i].setImageResource(segMap[sp[i]]!!)
        }
        // TIME
        val tm = time.padStart(3, ' ').takeLast(3)
        for (i in tm.indices) {
            timeDigits[i].setImageResource(segMap[tm[i]]!!)
        }
        // SCORE
        val scoreInt = score.toIntOrNull()?.coerceAtMost(9999) ?: 0
        val scoreStr = scoreInt.toString()
        val sc = scoreStr.padStart(4, ' ').takeLast(4)
        for (i in sc.indices) {
            scoreDigits[i].setImageResource(segMap[sc[i]]!!)
        }
    }

    private fun setupButton(id: Int, action: () -> Unit) {
        val btn = findViewById<ImageButton>(id)
        if (btn == null) {
            Log.e("GameView", "Button ID not found: $id")
            return
        }
        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> action()
                MotionEvent.ACTION_UP -> gameView.onButtonReleased()
            }
            false
        }
    }

    private fun hideSystemUI() {
        // フルスクリーン化
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                gameView.pauseGame()
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ゲームパッドのキー入力対応
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (gameView.onKeyDown(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (gameView.onKeyUp(keyCode)) true else super.onKeyUp(keyCode, event)
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message))
            .setPositiveButton(getString(R.string.about_ok)) { dialog, _ ->
                dialog.dismiss()
                gameView.resumeGame()  // ダイアログが閉じられたときにゲームを再開
            }
            .setNeutralButton(getString(R.string.about_hyperlink_name)) { _, _ ->
                val url = getString(R.string.about_hyperlink)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                gameView.resumeGame()
            }
            .setOnCancelListener {
                gameView.resumeGame()  // 戻るボタンなどでもゲームを再開
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        gameView.pauseGame() // 動きを止める
    }

    override fun onResume() {
        super.onResume()
        gameView.resumeGame() // 必要なら再開
    }

}