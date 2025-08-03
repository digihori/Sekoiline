package tk.horiuchi.sekoiline

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

enum class SteeringState {
    LEFT2, LEFT1, STRAIGHT, RIGHT1, RIGHT2
}

data class EnemyCar(
    var distance: Float,     // 自車からの相対距離（大きいほど奥）
    var laneOffset: Float,   // 左右位置（0 = 中央、負 = 左、正 = 右）
    var speed: Float,         // 敵車の移動速度（自車との差）
    var targetLaneOffset: Float = laneOffset,
    var isStoppedDueToCollision: Boolean = false,
    var laneCenter: Float = 0f,
    var carWidth: Float = 0f
)


class GameView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val drawThread = GameThread()
    private var statusUpdateListener: ((String, String, String) -> Unit)? = null
    private lateinit var carBitmaps: Map<SteeringState, Bitmap>
    private var backgroundBitmap: Bitmap? = null
    private var bgScrollX = 0f

    private var time = 30.0f
    private var distance = 0f
    private var score = 0f
    private var speed = 0f
    private val MAX_SPEED = 300f
    private val ACCELERATION = 0.5f      // 通常加速(km/h)
    private val DECELERATION_COLLISION = 3.0f  // 衝突減速(km/h)
    private val DECELERATION_EDGE = 2.0f       // 縁石減速(km/h)
    private var isCollidingWithEnemy = 0
    private var isCollidingWithEdge = false
    private var easedSpeed = 1f

    private val courseManager = CourseManager()
    private var currentCurve = 0f
    private val oversteerFactor = 1.2f

    var steeringInput = 0f
    private var steeringState = SteeringState.STRAIGHT
    private var maxShift: Float = 0f
    private var bgScrollSpeed = 0f
    private var roadShift = 0f
    private var targetSteering = 0f

    private var roadScrollOffset = 0f

    private var enableEnemies = false    // デバッグ用に敵出現のON/OFF切り替え
    private val enemyCars = mutableListOf<EnemyCar>()
    private val enemyCarBitmap = BitmapFactory.decodeResource(resources, R.drawable.car_enemy)
    private val maxEnemyDepth = 14f  // 適宜調整可（例：距離200で画面奥に達する）
    private val enemyPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        alpha = 255  // 常に不透明
    }

    enum class GameState {
        START_READY,   // スタート演出中
        PLAYING,       // 通常プレイ中
        GAME_OVER      // ゲームオーバー演出中
    }
    private var gameState = GameState.START_READY
    private var stateTimer = 0f
    private var countdownText = ""

    private val LIMIT_TIME = 30f
    private val EXTEND_TIME = 15f
    private var extendTimer = 0f
    private var isExtendActive = false

    private lateinit var soundManager: SoundManager

    @Volatile var isPaused = false // ポーズ状態フラグ

    init {
        //Log.w("GameView", "init")
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        carBitmaps = mapOf(
            SteeringState.LEFT2 to BitmapFactory.decodeResource(resources, R.drawable.car_left2),
            SteeringState.LEFT1 to BitmapFactory.decodeResource(resources, R.drawable.car_left1),
            SteeringState.STRAIGHT to BitmapFactory.decodeResource(resources, R.drawable.car_normal),
            SteeringState.RIGHT1 to BitmapFactory.decodeResource(resources, R.drawable.car_right1),
            SteeringState.RIGHT2 to BitmapFactory.decodeResource(resources, R.drawable.car_right2),
        )
        val initialSegment = courseManager.getCurrentSegment(0f)
        backgroundBitmap = initialSegment.background?.let {
            BitmapFactory.decodeResource(resources, it)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        soundManager = SoundManager(context)
        drawThread.running = true
        drawThread.start()
        restartGame()

        // スタートカウント
        Thread {
            Thread.sleep(400)
            soundManager.playSound("start_beep1")
            Thread.sleep(1000)
            soundManager.playSound("start_beep1")
            Thread.sleep(1000)
            soundManager.playSound("start_beep1")
            Thread.sleep(1000)
            soundManager.playSound("start_beep2")
        }.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        drawThread.running = false
        drawThread.joinSafely()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    fun pauseGame() {
        isPaused = true
        soundManager.stopEngine()
    }

    fun resumeGame() {
        isPaused = false
        if (gameState != GameState.GAME_OVER) {
            soundManager.playEngine()
        }
    }

    inner class GameThread : Thread() {
        var running = false

        override fun run() {
            while (running) {
                if (!isPaused) {
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        try {
                            synchronized(holder) {
                                update()
                                drawGame(canvas)
                            }
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                }

                try {
                    sleep(16)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maxShift = w * 0.3f
    }

    enum class TunnelState {
        NONE,               // 通常走行中
        ENTER_FADE_OUT,     // トンネル突入フェードアウト開始
        IN_TUNNEL,          // フェードアウト後、完全にトンネル内
        EXIT_FADE_IN,       // トンネル脱出フェードイン中
        DONE                // フェード完了（次セグメントに遷移済み）
    }
    private var tunnelState = TunnelState.NONE
    private var tunnelFadeProgress = 0f // 0.0 ～ 1.0 の間でフェード制御

    private val roadTestMode = false
    private var param1 = 30f
    private var param2 = 8f
    private var lastBackgroundResId: Int = -1  // 直前の背景IDを保持
    //private var lastGameState: GameState? = null
    private var previousState: GameState? = null
    private fun update() {
        // 前の状態を記録
        when (gameState) {
            GameState.START_READY -> {
                // スタート演出時間を進める
                speed = 0f
                stateTimer += 1f / 50f
                statusUpdateListener?.invoke(
                    speed.toInt().toString(),
                    time.toInt().toString(),
                    score.toInt().toString()
                )

                countdownText = when {
                    stateTimer < 1f -> "3"
                    stateTimer < 2f -> "2"
                    stateTimer < 3f -> "1"
                    stateTimer < 4f -> "GO!"
                    else -> {
                        gameState = GameState.PLAYING
                        stateTimer = 0f
                        ""
                    }
                }
                if (stateTimer > 3.5f) { // 4秒後に開始
                    gameState = GameState.PLAYING
                    stateTimer = 0f
                }
                return // スタート演出中はここで終了
            }

            GameState.PLAYING -> {
                // ここに既存の update() の内容を移動（元の処理）
                if (roadTestMode) {
                    // 単純にスクロールだけ進める
                    val lineSpacing = height / param1
                    val scrollSpeed = (speed / MAX_SPEED) * param2  // 固定値で見やすく
                    roadScrollOffset += scrollSpeed
                    roadScrollOffset %= lineSpacing
                    return  // 他のロジックはスキップ
                }
                distance += speed / 60
                score = distance / 10
                time -= 1f / 60f
                if (time <= 0f) time = 0f

                // EXTEND表示タイマー進行
                if (isExtendActive) {
                    extendTimer += 1f / 60f
                    if (extendTimer > 3f) { // 3秒経過で非表示
                        isExtendActive = false
                    }
                }

                val segment = courseManager.getCurrentSegment(distance)
                // 現在のセグメントのカーブ値を取得（ターゲット値）
                val targetCurve = segment.curve
                // 緩和係数（0.0〜1.0、小さいほど滑らか）
                val smoothingFactor = 0.05f
                // 緩やかにcurrentCurveを追従させる
                currentCurve += (targetCurve - currentCurve) * smoothingFactor
                // 小さな値はゼロとみなす
                if (kotlin.math.abs(currentCurve) < 0.01f) {
                    currentCurve = 0f
                }
                //Log.d("GameView", "distance = $distance, segment = $segment")

                // スクロール速度 = speed に比例（適宜調整）
                //val scrollSpeed = speed / param2  // 小さくするとゆっくり
                val normalizedSpeed = speed / MAX_SPEED  // 0.0 ～ 1.0 に正規化
                easedSpeed = sqrt(normalizedSpeed)  // 前半急激、後半なめらか
                val scrollSpeed = easedSpeed * param2
                //val scrollSpeed = (speed / MAX_SPEED) * param2  // 小さくするとゆっくり
                roadScrollOffset += scrollSpeed
                val lineSpacing = height / param1  // drawRoad でも同じ spacing を使用している前提
                roadScrollOffset %= lineSpacing

                // ★ 背景画像を必要に応じて更新
                val bgResId = segment.background
                if (bgResId != null && bgResId != lastBackgroundResId) {
                    //Log.d("TunnelDebug", "背景切替: last=$lastBackgroundResId → new=$bgResId at distance=$distance")
                    backgroundBitmap = BitmapFactory.decodeResource(resources, bgResId)
                    lastBackgroundResId = bgResId
                }

                // 背景のスクロール速度も、コースカーブとステアリングの差に応じて変化
                if (speed >= 60) {
                    bgScrollSpeed = currentCurve * oversteerFactor * 5f
                    bgScrollX += bgScrollSpeed
                    backgroundBitmap?.let {
                        if (bgScrollX < 0) bgScrollX += it.width
                        if (bgScrollX >= it.width) bgScrollX -= it.width
                    }
                } else {
                    bgScrollSpeed = 0f
                }

                roadShift += (currentCurve * oversteerFactor - steeringInput) * 2f
                roadShift = roadShift.coerceIn(-maxShift, maxShift)
                val touchingLeftCurb = roadShift <= -maxShift
                val touchingRightCurb = roadShift >= maxShift
                var previousCollisionWithWdge = isCollidingWithEdge
                if (touchingLeftCurb || touchingRightCurb) {
                    isCollidingWithEdge = true  // 縁石に接触中
                    if (previousCollisionWithWdge != isCollidingWithEdge) {
                        soundManager.playSound("collision")
                    }
                } else {
                    isCollidingWithEdge = false
                }

                steeringInput = when (steeringState) {
                    SteeringState.LEFT2 -> -4.0f
                    SteeringState.LEFT1 -> -2.0f
                    SteeringState.STRAIGHT -> 0.0f
                    SteeringState.RIGHT1 -> 2.0f
                    SteeringState.RIGHT2 -> 4.0f
                }
                updateSteeringInput()

                updateEnemyCars()
                checkCollisions()
                updateEnemyCarStates()

                val isCurrentlyTunnel = courseManager.isInTunnel(distance)  // ← CourseManager に実装予定

                //Log.d("TunnelDebug", "update: tunnelState=$tunnelState fadeProgress=$tunnelFadeProgress dist=$distance")
                when (tunnelState) {
                    TunnelState.NONE -> {
                        if (isCurrentlyTunnel) {
                            tunnelState = TunnelState.ENTER_FADE_OUT
                            tunnelFadeProgress = 0f
                            //Log.d("TunnelState", "状態遷移: NONE → ENTER_FADE_OUT")
                        }
                    }

                    TunnelState.ENTER_FADE_OUT -> {
                        tunnelFadeProgress += 0.02f
                        //Log.d("TunnelDebug", "ENTER_FADE_OUT進行: fadeProgress=$tunnelFadeProgress")
                        if (tunnelFadeProgress >= 1.0f) {
                            tunnelState = TunnelState.IN_TUNNEL
                            //Log.d("TunnelState", "状態遷移: ENTER_FADE_OUT → IN_TUNNEL")
                            // トンネル突入でEXTEND
                            time += EXTEND_TIME
                            isExtendActive = true
                            extendTimer = 0f
                            soundManager.playSound("extend")
                        }
                    }

                    TunnelState.IN_TUNNEL -> {
                        if (!isCurrentlyTunnel) {
                            tunnelState = TunnelState.EXIT_FADE_IN
                            tunnelFadeProgress = 0f
                            //Log.d("TunnelState", "状態遷移: IN_TUNNEL → EXIT_FADE_IN")
                        }
                    }

                    TunnelState.EXIT_FADE_IN -> {
                        tunnelFadeProgress += 0.02f
                        if (tunnelFadeProgress >= 1.0f) {
                            tunnelState = TunnelState.DONE
                            //Log.d("TunnelState", "状態遷移: EXIT_FADE_IN → DONE")
                        }
                    }

                    TunnelState.DONE -> {
                        tunnelState = TunnelState.NONE
                        //Log.d("TunnelState", "状態遷移: DONE → NONE")
                    }
                }
                invalidate()

                if (gameState == GameState.PLAYING) {
                    val speedRatio = speed / MAX_SPEED
                    soundManager.setEnginePitch(speedRatio)
                }
                // 衝突 or 縁石接触時の減速
                if (isCollidingWithEnemy > 0) {
                    speed = maxOf(0f, speed - DECELERATION_COLLISION)
                } else if (isCollidingWithEdge) {
                    speed = maxOf(0f, speed - DECELERATION_EDGE)
                } else {
                    // 通常加速
                    if (speed < MAX_SPEED) {
                        val speedRatio = speed / MAX_SPEED
                        val accelFactor = 2.0f - speedRatio
                        speed = minOf(MAX_SPEED, speed + ACCELERATION * accelFactor)
                    }
                }

                // ゲームオーバー条件チェック
                if (time <= 0f) {
                    gameState = GameState.GAME_OVER
                    stateTimer = 0f
                }
                statusUpdateListener?.invoke(
                    speed.toInt().toString(),
                    time.toInt().toString(),
                    score.toInt().toString()
                )
            }

            GameState.GAME_OVER -> {
                stateTimer += 1f / 60f

                // ゆっくり減速
                if (speed > 0f) {
                    speed = maxOf(0f, speed - 2f)
                }

                // 背景スクロール停止
                bgScrollSpeed = 0f

                // 画面フェードやGAME OVER文字は drawGame() 側で描画
                invalidate()

                // 3秒後に再スタート待ち
                if (stateTimer > 3f) {
                    // ここでボタン押下を待って START_READY に戻す
                }
                statusUpdateListener?.invoke(
                    speed.toInt().toString(),
                    time.toInt().toString(),
                    score.toInt().toString()
                )
                return
            }
        }
        // ここで遷移検知してサウンド再生
        if (previousState != gameState && gameState == GameState.PLAYING) {
            soundManager.playEngine()
        }
        if (previousState != gameState && gameState == GameState.GAME_OVER) {
            soundManager.stopEngine()
            soundManager.playSound("gameover")
        }
        //lastGameState = gameState
        previousState = gameState

    }

    private var frameCount = 0
    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawBackground(canvas)
        drawRoad(canvas)
        drawEnemyCars(canvas)

        frameCount++

        val bitmap = carBitmaps[steeringState] ?: return
        val scale = 0.7f  // ここでサイズ倍率を指定（1.0 = 等倍）

        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, false)

        val centerX = width / 2
        val bottomY = height - 20
        val left = centerX - scaledBitmap.width / 2
        val top = bottomY - scaledBitmap.height

        canvas.drawBitmap(scaledBitmap, left.toFloat(), top.toFloat(), null)

        // カウントダウン描画
        if (gameState == GameState.START_READY && countdownText.isNotEmpty()) {
            val paint = Paint().apply {
                color = if (countdownText == "GO!") Color.GREEN else Color.RED
                textSize = 200f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            canvas.drawText(countdownText, width / 2f, height / 2f, paint)
        }

        if (gameState == GameState.GAME_OVER) {
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 120f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            //canvas.drawText("GAME OVER", width / 2f, height / 2f, paint)
            if (stateTimer < 3f) {
                // 0.5秒ごとにON/OFF切り替え
                if (((stateTimer * 2).toInt() % 2) == 0) {
                    canvas.drawText("GAME OVER", width / 2f, height / 2f, paint)
                }
            } else {
                // 常時表示
                canvas.drawText("GAME OVER", width / 2f, height / 2f, paint)
            }
        }

        // EXTEND表示
        if (isExtendActive) {
            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 80f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            // 0.5秒間隔で点滅
            if (((extendTimer * 2).toInt() % 2) == 0) {
                canvas.drawText("EXTEND +${EXTEND_TIME.toInt()} sec", width / 2f, height / 3f, paint)
            }
        }

    }

    fun restartGame() {
        // スレッドが動いてなければ再開
        if (!drawThread.running) {
            drawThread.running = true
            drawThread.start()
        }

        // 変数リセット
        time = LIMIT_TIME
        distance = 0f
        speed = 0f
        enemyCars.clear()
        isCollidingWithEnemy = 0
        isCollidingWithEdge = false
        stateTimer = 0f

        gameState = GameState.START_READY
    }

    private fun drawBackground(canvas: Canvas) {
        //Log.d("TunnelDebug", "drawBackground() called. tunnelState=$tunnelState")
        //Log.d("TunnelDraw", "drawBackground called: tunnelState=$tunnelState fadeProgress=$tunnelFadeProgress")

        if (tunnelState != TunnelState.IN_TUNNEL) {
            backgroundBitmap?.let { bitmap ->
                val bmpW = bitmap.width
                val bmpH = bitmap.height

                // 表示高さを指定（例：画面の上半分）
                val destHeight = height / 2f
                val aspectRatio = bmpW.toFloat() / bmpH.toFloat()
                val destWidth = destHeight * aspectRatio

                // 水平スクロール位置の計算（ループ対応）
                val scrollX = ((bgScrollX % bmpW) + bmpW) % bmpW
                val intScrollX = scrollX.toInt()

                val bgPaint = Paint().apply {
                    alpha = 255
                    isFilterBitmap = true  // 拡大縮小時に滑らかに表示
                }

                var drawX = 0f
                var srcX = intScrollX

                while (drawX < width) {
                    val remainingSrc = bmpW - srcX
                    val drawSrcWidth = minOf(remainingSrc, bmpW)
                    val drawDestWidth = destWidth * (drawSrcWidth.toFloat() / bmpW)

                    val src = Rect(srcX, 0, srcX + drawSrcWidth, bmpH)
                    val dst = RectF(
                        drawX,
                        0f,  // 上寄せする場合
                        drawX + drawDestWidth,
                        destHeight
                    )
                    canvas.drawBitmap(bitmap, src, dst, bgPaint)

                    drawX += drawDestWidth
                    srcX = 0
                }
            } ?: run {
                // ここは保険
                //Log.d("TunnelFade", "Background bitmap is NULL at progress=$tunnelFadeProgress")
                //Log.d("TunnelDraw", "黒塗り実行(保険): alpha=$alpha (state=$tunnelState)")
                paint.color = Color.BLACK
                canvas.drawRect(0f, 0f, width.toFloat(), (height / 2).toFloat(), paint)
            }

        }
        // フェード演出またはトンネル内の黒塗り
        if (tunnelState == TunnelState.ENTER_FADE_OUT ||
            tunnelState == TunnelState.EXIT_FADE_IN ||
            tunnelState == TunnelState.IN_TUNNEL) {

            val alpha = when (tunnelState) {
                TunnelState.ENTER_FADE_OUT -> {
                    val a = (tunnelFadeProgress * 255).toInt().coerceIn(0, 255)
                    //Log.d("TunnelFade", "ENTER_FADE_OUT: progress=$tunnelFadeProgress alpha=$a bgResId=$lastBackgroundResId")
                    a
                }
                TunnelState.EXIT_FADE_IN   -> {
                    val a = ((1.0f - tunnelFadeProgress) * 255).toInt().coerceIn(0, 255)
                    //val a = (tunnelFadeProgress * 255).toInt().coerceIn(0, 255)
                    //Log.d("TunnelFade", "EXIT_FADE_IN: progress=$tunnelFadeProgress alpha=$a bgResId=$lastBackgroundResId")
                    a
                }
                TunnelState.IN_TUNNEL      -> {
                    //Log.d("TunnelFade", "IN_TUNNEL: alpha=255 bgResId=$lastBackgroundResId")
                    255
                } // 完全に黒で塗りつぶす
                else -> 0
            }

            //Log.d("TunnelDraw", "黒塗り実行: alpha=$alpha (state=$tunnelState)")
            paint.color = Color.argb(alpha, 0, 0, 0)
            //Log.d("TunnelFade", "BLACK!: alpha=$alpha")
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

    }

    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    private fun drawRoad(canvas: Canvas) {
        if (tunnelState == TunnelState.IN_TUNNEL /*|| tunnelState == TunnelState.EXIT_FADE_IN*/) {
            roadPaint.color = Color.rgb(40, 40, 40) // 暗いグレー
            centerLinePaint.color = Color.rgb(100, 100, 0) // 暗い黄色
            edgeLinePaint.color = Color.DKGRAY // 薄暗いグレー
        } else {
            roadPaint.color = Color.DKGRAY
            centerLinePaint.color = Color.YELLOW
            edgeLinePaint.color = Color.LTGRAY
        }

        val baseCenterX = width / 2f + roadShift
        val roadBottomY = height.toFloat()
        val lineSpacing = height / param1
        val numLines = (height / lineSpacing / 2).toInt()

        for (i in 0 until numLines) {
            val t = i / numLines.toFloat()
            val baseY = roadBottomY - i * lineSpacing + (roadScrollOffset % lineSpacing)
            // フチ用の固定Y座標（スクロールしない）
            val fixedY = height - i * lineSpacing

            val roadWidth = lerp(width * 0.95f, width * 0.2f, t)
            val curveAmount = currentCurve * (t.pow(2)) * maxShift * 0.5f

            val left = baseCenterX - roadWidth / 2 + curveAmount
            val right = baseCenterX + roadWidth / 2 + curveAmount
            val mid = (left + right) / 2

            canvas.drawLine(left, baseY, right, baseY, roadPaint)

            val scale = 1f + (1f - t) * 1.5f
            val lineLength = 6f * scale

            val centerLineLength = lerp(20f, 4f, t)  // 可変（動きを出す）
            val edgeLineLength = 14f                 // 固定（動かない）

            canvas.drawLine(mid, baseY, mid, baseY + centerLineLength, centerLinePaint)
            canvas.drawLine(left + 2, fixedY, left + 2, fixedY + edgeLineLength, edgeLinePaint)
            canvas.drawLine(right - 2, fixedY, right - 2, fixedY + edgeLineLength, edgeLinePaint)
        }
    }


    private val roadPaint = Paint().apply { color = Color.DKGRAY; strokeWidth = 10f }
    private val centerLinePaint = Paint().apply { color = Color.YELLOW; strokeWidth = 30f; isAntiAlias = false }
    private val edgeLinePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 30f; isAntiAlias = false }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t
    }

    private fun updateSteeringInput() {
        val steeringSpeed = 0.1f // 1フレームごとの変化量（調整可）

        if (steeringInput < targetSteering) {
            steeringInput = minOf(steeringInput + steeringSpeed, targetSteering)
        } else if (steeringInput > targetSteering) {
            steeringInput = maxOf(steeringInput - steeringSpeed, targetSteering)
        }
    }


    private fun spawnEnemyCar() {
        if (speed < 80f) return
        if (tunnelState != TunnelState.NONE) return

        //Log.d("EnemyTest", "spawnEnemyCar() called")
        val lane = listOf(-0.6f, -0.3f, 0.0f, 0.3f, 0.6f).random() // ランダムに左・中央・右
        val speed = 2f + (0..5).random() * 0.2f // 少し速度差をつける
        val newCar = EnemyCar(
            distance = maxEnemyDepth,     // 奥に登場（仮
            laneOffset = lane,
            speed = speed //* easedSpeed
        )
        //Log.d("EnemyDebug", "speed:${newCar.speed} easedSpeed={$easedSpeed}")
        enemyCars.add(newCar)
    }

    private fun drawEnemyCars(canvas: Canvas) {
        //Log.d("EnemyTest", "drawEnemyCar() called")
        val roadBottomY = height.toFloat()
        val lineSpacing = height / param1
        val numLines = (height / lineSpacing / 2).toInt()
        val baseCenterX = width / 2f + roadShift

        for (enemy in enemyCars) {
            val t = enemy.distance / maxEnemyDepth.toFloat() // 0〜1の範囲
            val baseY = roadBottomY - enemy.distance * lineSpacing

            val roadWidth = lerp(width * 0.95f, width * 0.2f, t)
            val curveOffset = currentCurve * (t * t) * maxShift * 0.5f
            val roadLeft = baseCenterX - roadWidth / 2 + curveOffset
            val roadRight = baseCenterX + roadWidth / 2 + curveOffset

            val laneCenter = (roadLeft + roadRight) / 2f + (roadWidth / 2f) * enemy.laneOffset
            enemy.laneCenter = laneCenter

            val carWidth = lerp(220f, 32f, t)
            val carHeight = lerp(220f, 32f, t)
            //Log.d("ScaleCheck", "t=$t, w=$carWidth, h=$carHeight")

            val left = laneCenter - carWidth / 2
            val top = baseY - carHeight
            val right = laneCenter + carWidth / 2
            val bottom = baseY

            //Log.d("EnemyDebug", "enemy: d=${enemy.distance} -> baseY=$baseY t=$t")
            //Log.d("EnemyTest", "drawEnemyCar() draw!")
            canvas.drawBitmap(enemyCarBitmap, null, RectF(left, top, right, bottom), enemyPaint)
        }
    }

    private var enemyLaneChangeCounter = 0
    private var enemySpawnCooldown = 0
    private var nextEnemySpawnInterval = 60

    private fun updateEnemyCars() {
        enemySpawnCooldown++
        val toRemove = mutableListOf<EnemyCar>()

        if (enableEnemies && enemySpawnCooldown >= nextEnemySpawnInterval) {
            spawnEnemyCar()
            enemySpawnCooldown = 0
            nextEnemySpawnInterval = (30..180).random() // 次の出現までのフレーム数をランダムで設定（1〜2.5秒）
        }

        // カウンター更新
        enemyLaneChangeCounter++
        val shouldUpdateTarget = enemyLaneChangeCounter % 120 == 0  // 約1秒ごと（60フレーム）

        for (enemy in enemyCars) {
            // ランダムに目標laneOffsetを更新（-0.9 〜 +0.9 の範囲）
            if (shouldUpdateTarget) {
                enemy.targetLaneOffset = (Math.random().toFloat() * 0.8f) - 0.4f
            }

            // 現在のlaneOffsetをtargetに滑らかに近づける
            val smoothing = 0.02f
            enemy.laneOffset += (enemy.targetLaneOffset - enemy.laneOffset) * smoothing

            // distanceを減らして接近させる
            // スピードがしきい値以下なら奥に逃げる
            // 衝突中の敵車の速度が落ちたら解除して逃げるのはupdateEnemyCarStates()で
            val threshold = 0.5f
            if (!enemy.isStoppedDueToCollision) {
                if (easedSpeed < threshold) {
                    enemy.distance += 0.3f //* retreatSpeed
                    // 奥に行きすぎたら削除
                    if (enemy.distance >= maxEnemyDepth) {
                        enemy.distance = maxEnemyDepth
                        toRemove.add(enemy)
                    }
                } else {
                    // 通常の接近
                    enemy.distance -= 0.1f * easedSpeed
                    if (enemy.distance < -2f) {
                        toRemove.add(enemy)
                    }
                }
            }
        }

        // 削除対象の敵車を削除
        enemyCars.removeAll(toRemove)
    }

    private fun checkCollisions() {
        val thresholdDistance = 2.5f  // 衝突判定のZ距離（手前すぎると間に合わないのでやや余裕）

        for (enemy in enemyCars) {
            if (enemy.isStoppedDueToCollision) continue  // すでに衝突で止まってる

            val collisionZOffset = 2f
            val dz = enemy.distance - collisionZOffset
            if (dz < 0f || dz > thresholdDistance) continue  // 範囲外の敵車は無視

            val t = dz / maxEnemyDepth
            val carWidth = lerp(140f, 32f, t)

            val playerX = width / 2f  // 自車は常に画面中央に描画
            val enemyX = enemy.laneCenter   // drawEnemyCars() で使ってるのと同じ計算式をここでも使う

            val dx = enemyX - playerX
            val collisionMargin = carWidth * 0.9f  // 少し小さめのマージン

            if (abs(dx) < collisionMargin) {
                // 衝突判定成功！
                enemy.isStoppedDueToCollision = true
                isCollidingWithEnemy++
                enemy.carWidth = collisionMargin
                Log.d("Collision", "enemy at distance=${enemy.distance} STOPPED due to collision")
                soundManager.playSound("collision")
            }
        }
    }

    private fun updateEnemyCarStates() {
        for (enemy in enemyCars) {
            if (!enemy.isStoppedDueToCollision) continue  // 衝突してない敵はスキップ

            // 自車の現在の画面X座標を取得
            val playerX = width / 2

            // この敵車の画面上X座標を取得（描画用に使ってるやつ）
            val enemyX = enemy.laneCenter  // または drawEnemyCars 時に enemy.screenX = laneCenter と記録しておく

            if (abs(playerX - enemyX) > enemy.carWidth || speed < 50) {
                enemy.isStoppedDueToCollision = false
                if (isCollidingWithEnemy > 0) isCollidingWithEnemy--
                enemy.carWidth = 0f
                Log.d("Collision", "enemy resumed: playerX=$playerX, enemyX=$enemyX")
            }
        }
    }


    fun onButtonPressed(input: GameInput) {
        if (gameState == GameState.GAME_OVER && stateTimer > 3f) {
            restartGame()
            return
        }
        when (input) {
            GameInput.LEFT -> {
                val prevState = steeringState
                steeringState = when (steeringState) {
                    SteeringState.RIGHT2 -> SteeringState.RIGHT1
                    SteeringState.RIGHT1 -> SteeringState.STRAIGHT
                    SteeringState.STRAIGHT -> SteeringState.LEFT1
                    SteeringState.LEFT1 -> SteeringState.LEFT2
                    else -> SteeringState.LEFT2
                }
                //param2++
                if (steeringState == SteeringState.LEFT2 && prevState != SteeringState.LEFT2) {
                    soundManager.playSound("steer2")
                }
            }

            GameInput.RIGHT -> {
                val prevState = steeringState
                steeringState = when (steeringState) {
                    SteeringState.LEFT2 -> SteeringState.LEFT1
                    SteeringState.LEFT1 -> SteeringState.STRAIGHT
                    SteeringState.STRAIGHT -> SteeringState.RIGHT1
                    SteeringState.RIGHT1 -> SteeringState.RIGHT2
                    else -> SteeringState.RIGHT2
                }
                //param2--
                if (steeringState == SteeringState.RIGHT2 && prevState != SteeringState.RIGHT2) {
                    soundManager.playSound("steer2")
                }
            }

            GameInput.ACCELERATE -> {
                //speed = minOf(speed + 10f, 240f)
                //param1++
            }
            GameInput.BRAKE -> {
                //speed = maxOf(speed - 10f, 0f)
                //param1--
            }
        }
    }


    fun onButtonReleased() {
        //Log.w("GameView", "onButtonReleased")
        //targetSteering = 0f
    }

    fun onKeyDown(keyCode: Int): Boolean {
        //Log.w("GameView", "onKeyDown: $keyCode")
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { onButtonPressed(GameInput.LEFT); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { onButtonPressed(GameInput.RIGHT); true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_UP -> { onButtonPressed(GameInput.ACCELERATE); true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_DOWN -> { onButtonPressed(GameInput.BRAKE); true }
            else -> false
        }
    }

    fun onKeyUp(keyCode: Int): Boolean {
        //Log.w("GameView", "onKeyUp: $keyCode")
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> { onButtonReleased(); true }
            else -> false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (gameState == GameState.GAME_OVER) {
                restartGame()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setStatusUpdateListener(listener: (String, String, String) -> Unit) {
        statusUpdateListener = listener
    }

    private fun Thread.joinSafely() {
        try {
            join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
}
