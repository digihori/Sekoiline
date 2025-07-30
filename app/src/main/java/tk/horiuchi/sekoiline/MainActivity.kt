package tk.horiuchi.sekoiline

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var gameView: GameView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        supportActionBar?.title = "Sekoiline"
        setContentView(R.layout.activity_main)

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
        statusText = findViewById(R.id.statusText)

        setupButton(R.id.btn_accel) { gameView.onButtonPressed(GameInput.ACCELERATE) }
        setupButton(R.id.btn_brake) { gameView.onButtonPressed(GameInput.BRAKE) }
        setupButton(R.id.btn_left) { gameView.onButtonPressed(GameInput.LEFT) }
        setupButton(R.id.btn_right) { gameView.onButtonPressed(GameInput.RIGHT) }

        // 状態表示の更新をGameViewに通知させる（必要に応じて）
        gameView.setStatusUpdateListener { text ->
            runOnUiThread { statusText.text = text }
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

    // ゲームパッドのキー入力対応
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (gameView.onKeyDown(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (gameView.onKeyUp(keyCode)) true else super.onKeyUp(keyCode, event)
    }

}