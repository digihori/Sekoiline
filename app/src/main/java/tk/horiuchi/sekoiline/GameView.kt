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
    private val backgroundCache = mutableMapOf<Int, Bitmap>()

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

    private var enableEnemies = true    // デバッグ用に敵出現のON/OFF切り替え
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

    private fun getBackground(resId: Int): Bitmap {
        return backgroundCache.getOrPut(resId) {
            BitmapFactory.decodeResource(resources, resId)
        }
    }

    inner class GameThread : Thread() {
        var running = false
        private var lastTime = System.nanoTime() // 前フレームの時刻
        private val targetFrameTime = 1_000_000_000L / 30L // 30fps → 約33,333,333ns

        override fun run() {
            while (running) {
                if (!isPaused) {
                    val frameStart = System.nanoTime()
                    val deltaTime = (frameStart - lastTime) / 1_000_000_000f // 秒
                    lastTime = frameStart

                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        try {
                            synchronized(holder) {
                                update(deltaTime)
                                drawGame(canvas)
                            }
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                    // 経過時間を測定して動的にスリープ
                    val frameEnd = System.nanoTime()
                    val elapsedNs = frameEnd - frameStart
                    val sleepNs = targetFrameTime - elapsedNs

                    if (sleepNs > 0) {
                        try {
                            sleep(sleepNs / 1_000_000L)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maxShift = w * 0.3f
        precomputeRoadLines()

        val segment = courseManager.getCurrentSegment(0f)
        segment.background?.let { prepareMergedBackground(it) }
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
    //private var frameCounter = 0
    private var startSoundPlayed = false
    private fun update(deltaTime: Float) {
        //Log.d("ThreadCheck", "GameThread id=${Thread.currentThread().id}")
        val tUpdateStart = System.nanoTime() // ★update計測開始
        //Log.d("StateChange", "previousState=$previousState -> gameState=$gameState")
        if (previousState != gameState) {
            when (gameState) {
                GameState.START_READY -> {
                    if (!startSoundPlayed) {
                        startSoundPlayed = true
                        Log.d("StartSound", "Start Sound Play!!!")
                        Thread {
                            Thread.sleep(200)
                            soundManager.playSound("start_beep1")
                            Thread.sleep(1000)
                            soundManager.playSound("start_beep1")
                            Thread.sleep(1000)
                            soundManager.playSound("start_beep1")
                            Thread.sleep(1000)
                            soundManager.playSound("start_beep2")
                        }.start()
                    }
                }
                GameState.PLAYING -> {
                    soundManager.playEngine()
                }
                GameState.GAME_OVER -> {
                    startSoundPlayed = false
                    soundManager.stopEngine()
                    soundManager.playSound("gameover")
                }
            }
            previousState = gameState
        }

        // 前の状態を記録
        when (gameState) {
            GameState.START_READY -> {
                // スタート演出時間を進める
                speed = 0f
                stateTimer += deltaTime * 0.9f// 少し早めに
                updateStatusIfChanged()

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
                    val scrollSpeed = (speed / MAX_SPEED) * param2 * 2f // 固定値で見やすく
                    roadScrollOffset += scrollSpeed
                    roadScrollOffset %= lineSpacing
                    return  // 他のロジックはスキップ
                }
                distance += speed * deltaTime
                score = distance / 10
                time -= deltaTime
                if (time <= 0f) time = 0f

                // EXTEND表示タイマー進行
                if (isExtendActive) {
                    extendTimer += deltaTime
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
                val normalizedSpeed = speed / MAX_SPEED  // 0.0 ～ 1.0 に正規化
                easedSpeed = sqrt(normalizedSpeed)  // 前半急激、後半なめらか
                val scrollSpeed = easedSpeed * param2 * 2f
                //val scrollSpeed = (speed / MAX_SPEED) * param2  // 小さくするとゆっくり
                roadScrollOffset += scrollSpeed
                val lineSpacing = height / param1  // drawRoad でも同じ spacing を使用している前提
                roadScrollOffset %= lineSpacing

                // ★ 背景画像を必要に応じて更新
                val bgResId = segment.background
                if (bgResId != null && bgResId != lastBackgroundResId) {
                    prepareMergedBackground(bgResId)  // 軽量化背景読み込み
                    lastBackgroundResId = bgResId
                }

                // 背景のスクロール速度も、コースカーブとステアリングの差に応じて変化
                if (frameCount % 2 == 0) { // 更新頻度を1/2にする
                    if (speed >= 60) {
                        bgScrollSpeed = currentCurve * oversteerFactor * 5f

                        // 合成済み背景の1枚幅（3分割の1枚分）
                        val singleWidth = mergedWidth / 3

                        // スクロールオフセット更新
                        bgOffsetX += bgScrollSpeed.toInt()

                        // 剰余演算の代わりに条件分岐でループ
                        if (bgOffsetX < 0) bgOffsetX += singleWidth
                        if (bgOffsetX >= singleWidth) bgOffsetX -= singleWidth
                    } else {
                        bgScrollSpeed = 0f
                    }
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
                    SteeringState.LEFT2 -> -7.0f
                    SteeringState.LEFT1 -> -3.0f
                    SteeringState.STRAIGHT -> 0.0f
                    SteeringState.RIGHT1 -> 3.0f
                    SteeringState.RIGHT2 -> 7.0f
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
                    speed = maxOf(0f, speed - DECELERATION_COLLISION * 2f)
                } else if (isCollidingWithEdge) {
                    speed = maxOf(0f, speed - DECELERATION_EDGE * 2f)
                } else {
                    // 通常加速
                    if (speed < MAX_SPEED) {
                        val speedRatio = speed / MAX_SPEED
                        val accelFactor = 2.0f - speedRatio
                        speed = minOf(MAX_SPEED, speed + ACCELERATION * accelFactor * 2f)
                    }
                }

                // ゲームオーバー条件チェック
                if (time <= 0f) {
                    gameState = GameState.GAME_OVER
                    stateTimer = 0f
                }
                updateStatusIfChanged()
            }

            GameState.GAME_OVER -> {
                stateTimer += 1f / 30f

                // 減速
                if (speed > 0f) {
                    speed = maxOf(0f, speed - 8f)
                }

                // 背景スクロール停止
                bgScrollSpeed = 0f

                // 画面フェードやGAME OVER文字は drawGame() 側で描画
                invalidate()

                updateStatusIfChanged()
                return
            }
        }


        frameCount++
        val elapsedUpdateMs = (System.nanoTime() - tUpdateStart) / 1_000_000.0
        Log.d("PerfUpdate", String.format("update: %.3f ms", elapsedUpdateMs))
    }

    private var lastSpeed = -1
    private var lastTime = -1
    private var lastScore = -1

    private fun updateStatusIfChanged() {
        val s = speed.toInt()
        val t = time.toInt()
        val sc = score.toInt()
        if (s != lastSpeed || t != lastTime || sc != lastScore) {
            lastSpeed = s
            lastTime = t
            lastScore = sc
            statusUpdateListener?.invoke(s.toString(), t.toString(), sc.toString())
        }
    }

    private var frameCount = 0
    private fun drawGame(canvas: Canvas) {
        val tFrameStart = System.nanoTime() // ★フレーム開始時間
        canvas.drawColor(Color.BLACK)
        drawBackground(canvas)
        drawRoad(canvas)
        drawEnemyCars(canvas)

        frameCount++

        val scaledBitmap = getScaledCarBitmap(steeringState)

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

        // ★描画終了 → 計測
        val elapsedFrameMs = (System.nanoTime() - tFrameStart) / 1_000_000.0
        //Log.d("PerfFrame", String.format("drawGame total: %.3f ms", elapsedFrameMs))
    }

    //private val scaledCarBitmaps = mutableMapOf<SteeringState, Bitmap>()

    private fun getScaledCarBitmap(state: SteeringState): Bitmap {
        val bmp = carBitmaps[state]!!

        // 一番手前の道路幅（t=0）
        val roadWidth = width * 0.95f // drawRoad() の計算と合わせる

        // 道路幅の40%を自車幅にする（好みに応じて0.35〜0.45で調整可）
        val desiredWidth = (roadWidth * 0.25f).toInt()

        // アスペクト比を維持
        val aspectRatio = bmp.height.toFloat() / bmp.width.toFloat()
        val desiredHeight = (desiredWidth * aspectRatio).toInt()

        return Bitmap.createScaledBitmap(bmp, desiredWidth, desiredHeight, false)
    }

    fun restartGame() {
        //Log.d("restartGame", "restartGame Called!!!")
        // スレッドが止まってたら再開
        if (!drawThread.running) {
            drawThread.running = true
            drawThread.start()
        }

        // スクロール・カーブ系のリセット
        bgScrollX = 0f
        bgScrollSpeed = 0f
        roadScrollOffset = 0f
        roadShift = 0f
        currentCurve = 0f
        steeringState = SteeringState.STRAIGHT
        steeringInput = 0f
        targetSteering = 0f

        // 背景の再準備（初期セグメントを読み込み）
        val initialSegment = courseManager.getCurrentSegment(0f)
        lastBackgroundResId = -1
        initialSegment.background?.let {
            prepareMergedBackground(it)
            lastBackgroundResId = it
        }

        // 敵車をクリア
        enemyCars.clear()
        isCollidingWithEnemy = 0
        isCollidingWithEdge = false

        // ゲーム進行用変数リセット
        time = LIMIT_TIME
        distance = 0f
        speed = 0f
        enemyCars.clear()
        isCollidingWithEnemy = 0
        isCollidingWithEdge = false
        stateTimer = 0f
        score = 0f

        // トンネル関連リセット
        tunnelState = TunnelState.NONE
        tunnelFadeProgress = 0f
        isExtendActive = false
        extendTimer = 0f

        // 前回状態リセット
        previousState = null
        gameState = GameState.START_READY
        //startSoundPlayed = false

        // 初回描画を強制
        forceDrawOnce()
    }

    private fun forceDrawOnce() {
        val canvas = holder.lockCanvas()
        if (canvas != null) {
            try {
                synchronized(holder) {
                    drawGame(canvas) // 現在の状態を描画
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }


    private var mergedBackground: Bitmap? = null
    private var mergedWidth = 0
    private var mergedHeight = 0

    private fun prepareMergedBackground(resId: Int) {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val original = BitmapFactory.decodeResource(resources, resId, opts)

        // 表示高さ（画面の半分）
        val destHeight = height / 2f
        val aspectRatio = original.width.toFloat() / original.height.toFloat()
        val destWidth = (destHeight * aspectRatio).toInt()

        // 縮小1枚
        val scaled = Bitmap.createScaledBitmap(original, destWidth, destHeight.toInt(), false)
        original.recycle()

        // 横3枚をまとめたビットマップを生成
        mergedWidth = destWidth * 3
        mergedHeight = destHeight.toInt()
        mergedBackground = Bitmap.createBitmap(mergedWidth, mergedHeight, Bitmap.Config.RGB_565)

        val canvas = Canvas(mergedBackground!!)
        for (i in 0 until 3) {
            canvas.drawBitmap(scaled, (i * destWidth).toFloat(), 0f, null)
        }
        scaled.recycle()
    }

    private val bgPaint = Paint().apply { isFilterBitmap = false }
    private val bgSrcRect = Rect()
    private val bgDstRect = RectF()
    private var bgOffsetX = 0

    private fun drawBackground(canvas: Canvas) {
        val tStart = System.nanoTime()

        val bitmap = mergedBackground ?: return

        // オフセット計算（剰余を使わない）
        bgOffsetX += bgScrollSpeed.toInt()
        if (bgOffsetX < 0) bgOffsetX += mergedWidth / 3
        if (bgOffsetX >= mergedWidth / 3) bgOffsetX -= mergedWidth / 3

        // 1回の転送だけで描画
        val singleWidth = mergedWidth / 3
        bgSrcRect.set(bgOffsetX, 0, bgOffsetX + singleWidth, mergedHeight)
        bgDstRect.set(0f, 0f, width.toFloat(), mergedHeight.toFloat())
        canvas.drawBitmap(bitmap, bgSrcRect, bgDstRect, bgPaint)

        // トンネル演出
        if (tunnelState == TunnelState.ENTER_FADE_OUT ||
            tunnelState == TunnelState.EXIT_FADE_IN ||
            tunnelState == TunnelState.IN_TUNNEL) {

            val alpha = when (tunnelState) {
                TunnelState.ENTER_FADE_OUT -> (tunnelFadeProgress * 255).toInt().coerceIn(0, 255)
                TunnelState.EXIT_FADE_IN   -> ((1.0f - tunnelFadeProgress) * 255).toInt().coerceIn(0, 255)
                TunnelState.IN_TUNNEL      -> 255
                else -> 0
            }
            paint.color = Color.argb(alpha, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        val elapsedMs = (System.nanoTime() - tStart) / 1_000_000.0
        //Log.d("PerfBG", String.format("drawBackground: %.3f ms", elapsedMs))
    }


    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    // RoadLine の構造体を拡張
    private data class RoadLine(
        val baseY: Float,         // スクロール基準Y
        val fixedY: Float,        // 縁石固定Y
        val roadWidth: Float,     // 道路幅
        val t: Float,             // 奥行き比率
        val centerLineLength: Float, // 中央線長（キャッシュ）
        val edgeLineLength: Float    // 縁石長（キャッシュ）
    )

    private val roadLines = mutableListOf<RoadLine>()
    private var precomputedLineSpacing = 0f

    // precomputeRoadLines() 修正版
    private fun precomputeRoadLines() {
        roadLines.clear()

        val roadBottomY = height.toFloat()
        precomputedLineSpacing = height / param1
        val numLines = (height / precomputedLineSpacing / 2).toInt()

        for (i in 0 until numLines) {
            val t = i / numLines.toFloat()
            val baseY = roadBottomY - i * precomputedLineSpacing
            val fixedY = height - i * precomputedLineSpacing
            val roadWidth = lerp(width * 0.95f, width * 0.2f, t)

            // ここで固定値を事前計算
            val centerLineLength = lerp(20f, 4f, t)
            val edgeLineLength = 14f // 固定なら直接値でも可

            roadLines.add(
                RoadLine(baseY, fixedY, roadWidth, t, centerLineLength, edgeLineLength)
            )
        }
    }

    // 軽量化 drawRoad()
    private fun drawRoad(canvas: Canvas) {
        val tStart = System.nanoTime()

        // 道路の色設定
        if (tunnelState == TunnelState.IN_TUNNEL) {
            roadPaint.color = Color.rgb(40, 40, 40)
            centerLinePaint.color = Color.rgb(100, 100, 0)
            edgeLinePaint.color = Color.DKGRAY
        } else {
            roadPaint.color = Color.DKGRAY
            centerLinePaint.color = Color.YELLOW
            edgeLinePaint.color = Color.LTGRAY
        }

        val baseCenterX = width / 2f + roadShift
        val scrollOffset = roadScrollOffset % precomputedLineSpacing

        for (line in roadLines) {
            // スクロール反映
            val baseY = line.baseY + scrollOffset

            // カーブ補正のみ計算
            val curveAmount = currentCurve * (line.t * line.t) * maxShift * 0.5f
            val left = baseCenterX - line.roadWidth / 2 + curveAmount
            val right = baseCenterX + line.roadWidth / 2 + curveAmount
            val mid = (left + right) / 2

            // 道路本体
            canvas.drawLine(left, baseY, right, baseY, roadPaint)

            // 中央線
            canvas.drawLine(mid, baseY, mid, baseY + line.centerLineLength, centerLinePaint)

            // 縁石
            canvas.drawLine(left + 2, line.fixedY, left + 2, line.fixedY + line.edgeLineLength, edgeLinePaint)
            canvas.drawLine(right - 2, line.fixedY, right - 2, line.fixedY + line.edgeLineLength, edgeLinePaint)
        }

        val elapsedMs = (System.nanoTime() - tStart) / 1_000_000.0
        //Log.d("PerfRoad", String.format("drawRoad: %.3f ms", elapsedMs))
    }


    private val roadPaint = Paint().apply { color = Color.DKGRAY; strokeWidth = 10f }
    private val centerLinePaint = Paint().apply { color = Color.YELLOW; strokeWidth = 30f; isAntiAlias = false }
    private val edgeLinePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 30f; isAntiAlias = false }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t
    }
    inline fun fastLerp(a: Float, b: Float, t: Float) = a + (b - a) * t


    private fun updateSteeringInput() {
        val steeringSpeed = 0.1f // 1フレームごとの変化量（調整可）

        if (steeringInput < targetSteering) {
            steeringInput = minOf(steeringInput + steeringSpeed, targetSteering)
        } else if (steeringInput > targetSteering) {
            steeringInput = maxOf(steeringInput - steeringSpeed, targetSteering)
        }
    }

    private var nextLaneOffsets = floatArrayOf(-0.6f, -0.3f, 0.0f, 0.3f, 0.6f)
    private val rng = java.util.Random()
    private fun spawnEnemyCar() {
        if (speed < 80f) return
        if (tunnelState != TunnelState.NONE) return

        //Log.d("EnemyTest", "spawnEnemyCar() called")
        //val lane = listOf(-0.6f, -0.3f, 0.0f, 0.3f, 0.6f).random() // ランダムに左・中央・右
        val lane = nextLaneOffsets[rng.nextInt(nextLaneOffsets.size)]
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
        val tStart = System.nanoTime()
        //Log.d("EnemyTest", "drawEnemyCar() called")
        val roadBottomY = height.toFloat()
        val lineSpacing = height / param1
        val numLines = (height / lineSpacing / 2).toInt()
        val baseCenterX = width / 2f + roadShift

        val snapshot = enemyCars.toList()
        for (enemy in snapshot) {
            if (enemy.distance > maxEnemyDepth || enemy.distance < -2f) continue

            val t = enemy.distance / maxEnemyDepth.toFloat() // 0〜1の範囲
            val baseY = roadBottomY - enemy.distance * lineSpacing

            val roadWidth = fastLerp(width * 0.95f, width * 0.2f, t)
            val curveOffset = currentCurve * (t * t) * maxShift * 0.5f
            val roadLeft = baseCenterX - roadWidth / 2 + curveOffset
            val roadRight = baseCenterX + roadWidth / 2 + curveOffset

            val laneCenter = (roadLeft + roadRight) / 2f + (roadWidth / 2f) * enemy.laneOffset
            enemy.laneCenter = laneCenter

            val carWidth = fastLerp(220f, 32f, t)
            val carHeight = fastLerp(220f, 32f, t)
            //Log.d("ScaleCheck", "t=$t, w=$carWidth, h=$carHeight")

            val left = laneCenter - carWidth / 2
            val top = baseY - carHeight
            val right = laneCenter + carWidth / 2
            val bottom = baseY

            //Log.d("EnemyDebug", "enemy: d=${enemy.distance} -> baseY=$baseY t=$t")
            //Log.d("EnemyTest", "drawEnemyCar() draw!")
            canvas.drawBitmap(enemyCarBitmap, null, RectF(left, top, right, bottom), enemyPaint)
        }
        val elapsedMs = (System.nanoTime() - tStart) / 1_000_000.0
        //Log.d("PerfEnemy", String.format("drawEnemyCars: %.3f ms", elapsedMs))
    }

    private var enemyLaneChangeCounter = 0
    private var enemySpawnCooldown = 0
    private var nextEnemySpawnInterval = 60
    private val removeList = ArrayList<EnemyCar>(10)
    private val ENEMY_LANE_SMOOTHING = 0.02f

    private fun updateEnemyCars() {
        enemySpawnCooldown++
        removeList.clear()
        //val toRemove = mutableListOf<EnemyCar>()

        if (enableEnemies && enemySpawnCooldown >= nextEnemySpawnInterval) {
            spawnEnemyCar()
            enemySpawnCooldown = 0
            nextEnemySpawnInterval = (30..60).random() // 次の出現までのフレーム数をランダムで設定（1〜2.5秒）
        }

        // カウンター更新
        enemyLaneChangeCounter++
        val shouldUpdateTarget = enemyLaneChangeCounter % 60 == 0  // 約1秒ごと（60フレーム）

        for (enemy in enemyCars) {
            // ランダムに目標laneOffsetを更新（-0.9 〜 +0.9 の範囲）
            if (shouldUpdateTarget) {
                enemy.targetLaneOffset = (Math.random().toFloat() * 0.8f) - 0.4f
            }

            // 現在のlaneOffsetをtargetに滑らかに近づける
            //val smoothing = 0.02f
            enemy.laneOffset += (enemy.targetLaneOffset - enemy.laneOffset) * ENEMY_LANE_SMOOTHING

            // distanceを減らして接近させる
            // スピードがしきい値以下なら奥に逃げる
            // 衝突中の敵車の速度が落ちたら解除して逃げるのはupdateEnemyCarStates()で
            val threshold = 0.5f
            if (!enemy.isStoppedDueToCollision) {
                if (easedSpeed < threshold) {
                    enemy.distance += 0.3f * 2f //* retreatSpeed
                    // 奥に行きすぎたら削除
                    if (enemy.distance >= maxEnemyDepth) {
                        enemy.distance = maxEnemyDepth
                        removeList.add(enemy)
                    }
                } else {
                    // 通常の接近
                    enemy.distance -= 0.1f * easedSpeed * 2f
                    if (enemy.distance < -2f) {
                        removeList.add(enemy)
                    }
                }
            }
        }

        // 削除対象の敵車を削除
        enemyCars.removeAll(removeList)
    }

    private fun checkCollisions() {
        val thresholdDistance = 2.5f  // 衝突判定のZ距離（手前すぎると間に合わないのでやや余裕）
        val nearEnemies = enemyCars.filter { enemy ->
            // 奥行きが近い敵だけ判定
            enemy.distance in 0f..(thresholdDistance + 1f)
        }

        for (enemy in nearEnemies) {
            if (enemy.isStoppedDueToCollision) continue  // すでに衝突で止まってる

            val collisionZOffset = 2f
            val dz = enemy.distance - collisionZOffset
            if (dz < 0f || dz > thresholdDistance) continue  // 範囲外の敵車は無視

            val t = dz / maxEnemyDepth
            val carWidth = fastLerp(140f, 32f, t)

            val playerX = width / 2f  // 自車は常に画面中央に描画
            val enemyX = enemy.laneCenter   // drawEnemyCars() で使ってるのと同じ計算式をここでも使う

            val dx = enemyX - playerX
            val collisionMargin = carWidth * 0.8f  // 少し小さめのマージン

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
