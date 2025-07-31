package tk.horiuchi.sekoiline

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.pow

enum class SteeringState {
    LEFT2, LEFT1, STRAIGHT, RIGHT1, RIGHT2
}

data class EnemyCar(
    var distance: Float,     // 自車からの相対距離（大きいほど奥）
    var laneOffset: Float,   // 左右位置（0 = 中央、負 = 左、正 = 右）
    var speed: Float         // 敵車の移動速度（自車との差）
)


class GameView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val drawThread = GameThread()
    private var statusUpdateListener: ((String) -> Unit)? = null
    private lateinit var carBitmaps: Map<SteeringState, Bitmap>
    private var backgroundBitmap: Bitmap? = null
    private var bgScrollX = 0f

    private var time = 30.0f
    private var speed = 120
    private var distance = 0

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

    private var enableEnemies = true // デバッグ用に敵出現のON/OFF切り替え
    private val enemyCars = mutableListOf<EnemyCar>()
    private val enemyCarBitmap = BitmapFactory.decodeResource(resources, R.drawable.car_enemy)
    private val maxEnemyDepth = 14f  // 適宜調整可（例：距離200で画面奥に達する）
    private val enemyPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        alpha = 255  // 常に不透明
    }

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
        val initialSegment = courseManager.getCurrentSegment(0)
        backgroundBitmap = initialSegment.background?.let {
            BitmapFactory.decodeResource(resources, it)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        drawThread.running = true
        drawThread.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        drawThread.running = false
        drawThread.joinSafely()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    inner class GameThread : Thread() {
        var running = false

        override fun run() {
            while (running) {
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    synchronized(holder) {
                        update()
                        drawGame(canvas)
                    }
                    holder.unlockCanvasAndPost(canvas)
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
    private var param2 = 5f
    private var lastBackgroundResId: Int = -1  // 直前の背景IDを保持
    private fun update() {
        if (roadTestMode) {
            // 単純にスクロールだけ進める
            val lineSpacing = height / param1
            val scrollSpeed = param2  // 固定値で見やすく
            roadScrollOffset += scrollSpeed
            roadScrollOffset %= lineSpacing
            //Log.d("GameView-test", "scrollSpeed=$scrollSpeed lineSpacing=$lineSpacing param=$param1")
            return  // 他のロジックはスキップ
        }
        distance += speed / 60
        time -= 1f / 60f
        if (time <= 0f) time = 0f


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
        val scrollSpeed = param2  // 小さくするとゆっくり
        roadScrollOffset += scrollSpeed
        val lineSpacing = height / param1  // drawRoad でも同じ spacing を使用している前提
        roadScrollOffset %= lineSpacing

        // ★ 背景画像を必要に応じて更新
        val bgResId = segment.background
        if (bgResId != null && bgResId != lastBackgroundResId) {
            Log.d("TunnelDebug", "背景切替: last=$lastBackgroundResId → new=$bgResId at distance=$distance")
            backgroundBitmap = BitmapFactory.decodeResource(resources, bgResId)
            lastBackgroundResId = bgResId
        }

        // 背景のスクロール速度も、コースカーブとステアリングの差に応じて変化
        bgScrollSpeed = currentCurve * oversteerFactor * 5f
        bgScrollX += bgScrollSpeed
        backgroundBitmap?.let {
            if (bgScrollX < 0) bgScrollX += it.width
            if (bgScrollX >= it.width) bgScrollX -= it.width
        }


        roadShift += (currentCurve * oversteerFactor - steeringInput) * 2f
        roadShift = roadShift.coerceIn(-maxShift, maxShift)

        steeringInput = when (steeringState) {
            SteeringState.LEFT2 -> -4.0f
            SteeringState.LEFT1 -> -2.0f
            SteeringState.STRAIGHT -> 0.0f
            SteeringState.RIGHT1 -> 2.0f
            SteeringState.RIGHT2 -> 4.0f
        }
        updateSteeringInput()

        updateEnemyCars()


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

        statusUpdateListener?.invoke("TIME: %.1f   SPEED: ${speed}km/h   DIST: %05d".format(time, distance))
    }

    private var frameCount = 0
    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawBackground(canvas)
        drawRoad(canvas)
        drawEnemyCars(canvas)

        frameCount++

        val bitmap = carBitmaps[steeringState] ?: return
        val scale = 1.5f  // ここでサイズ倍率を指定（1.0 = 等倍）

        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, false)

        val centerX = width / 2
        val bottomY = height - 20
        val left = centerX - scaledBitmap.width / 2
        val top = bottomY - scaledBitmap.height

        canvas.drawBitmap(scaledBitmap, left.toFloat(), top.toFloat(), null)

        if (enableEnemies && frameCount % 60 == 0) { // 約1秒ごとに出現（60fps前提）
            spawnEnemyCar()
        }

    }

    private fun drawBackground(canvas: Canvas) {
        //Log.d("TunnelDebug", "drawBackground() called. tunnelState=$tunnelState")
        //Log.d("TunnelDraw", "drawBackground called: tunnelState=$tunnelState fadeProgress=$tunnelFadeProgress")

        if (tunnelState != TunnelState.IN_TUNNEL) {
            backgroundBitmap?.let { bitmap ->
                //Log.d("TunnelFade", "Background bitmap is set: width=${bitmap.width}, height=${bitmap.height}")
                val bgHeight = height - height / 2
                val bmpW = bitmap.width
                val bmpH = bitmap.height
                val scrollX = ((bgScrollX % bmpW) + bmpW) % bmpW
                val intScrollX = scrollX.toInt()

                var drawX = 0
                var srcX = intScrollX

                while (drawX < width) {
                    val remainingSrc = bmpW - srcX
                    val drawWidth = minOf(remainingSrc, width - drawX)

                    val src = Rect(srcX, 0, srcX + drawWidth, bmpH)
                    val dst = Rect(drawX, 0, drawX + drawWidth, bgHeight)

                    val bgPaint = Paint().apply {
                        alpha = 255  // 完全に不透明にしておく
                    }
                    //Log.d("TunnelDraw", "背景描画実行: bgScrollX=$bgScrollX")
                    canvas.drawBitmap(bitmap, src, dst, bgPaint)

                    drawX += drawWidth
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
                    Log.d("TunnelFade", "ENTER_FADE_OUT: progress=$tunnelFadeProgress alpha=$a bgResId=$lastBackgroundResId")
                    a
                }
                TunnelState.EXIT_FADE_IN   -> {
                    val a = ((1.0f - tunnelFadeProgress) * 255).toInt().coerceIn(0, 255)
                    //val a = (tunnelFadeProgress * 255).toInt().coerceIn(0, 255)
                    Log.d("TunnelFade", "EXIT_FADE_IN: progress=$tunnelFadeProgress alpha=$a bgResId=$lastBackgroundResId")
                    a
                }
                TunnelState.IN_TUNNEL      -> {
                    Log.d("TunnelFade", "IN_TUNNEL: alpha=255 bgResId=$lastBackgroundResId")
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
        Log.d("EnemyTest", "spawnEnemyCar() called")
        val lane = listOf(-0.6f, -0.3f, 0.0f, 0.3f, 0.6f).random() // ランダムに左・中央・右
        val speed = 2f + (0..5).random() * 0.2f // 少し速度差をつける
        val newCar = EnemyCar(
            distance = maxEnemyDepth,     // 奥に登場（仮
            laneOffset = lane,
            speed = speed
        )
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

            val carWidth = lerp(220f, 32f, t)
            val carHeight = lerp(220f, 32f, t)
            Log.d("ScaleCheck", "t=$t, w=$carWidth, h=$carHeight")

            val left = laneCenter - carWidth / 2
            val top = baseY - carHeight
            val right = laneCenter + carWidth / 2
            val bottom = baseY

            //Log.d("EnemyDebug", "enemy: d=${enemy.distance} -> baseY=$baseY t=$t")
            //Log.d("EnemyTest", "drawEnemyCar() draw!")
            canvas.drawBitmap(enemyCarBitmap, null, RectF(left, top, right, bottom), enemyPaint)
        }
    }

    private fun updateEnemyCars() {
        val iterator = enemyCars.iterator()
        while (iterator.hasNext()) {
            val enemy = iterator.next()
            enemy.distance -= 0.1f
            //Log.d("EnemyDebug", "distance=${enemy.distance}")
            if (enemy.distance < -2) {
                iterator.remove() // 画面外に出たら削除
            }
        }
    }

    fun onButtonPressed(input: GameInput) {
        when (input) {
            GameInput.LEFT -> {
                steeringState = when (steeringState) {
                    SteeringState.RIGHT2 -> SteeringState.RIGHT1
                    SteeringState.RIGHT1 -> SteeringState.STRAIGHT
                    SteeringState.STRAIGHT -> SteeringState.LEFT1
                    SteeringState.LEFT1 -> SteeringState.LEFT2
                    else -> SteeringState.LEFT2
                }
                param2++
            }

            GameInput.RIGHT -> {
                steeringState = when (steeringState) {
                    SteeringState.LEFT2 -> SteeringState.LEFT1
                    SteeringState.LEFT1 -> SteeringState.STRAIGHT
                    SteeringState.STRAIGHT -> SteeringState.RIGHT1
                    SteeringState.RIGHT1 -> SteeringState.RIGHT2
                    else -> SteeringState.RIGHT2
                }
                param2--
            }

            GameInput.ACCELERATE -> {
                speed = minOf(speed + 10, 240)
                param1++
            }
            GameInput.BRAKE -> {
                speed = maxOf(speed - 10, 0)
                param1--
            }
        }
    }


    fun onButtonReleased() {
        //Log.w("GameView", "onButtonReleased")
        //targetSteering = 0f
    }

    fun onKeyDown(keyCode: Int): Boolean {
        Log.w("GameView", "onKeyDown: $keyCode")
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { onButtonPressed(GameInput.LEFT); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { onButtonPressed(GameInput.RIGHT); true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_UP -> { onButtonPressed(GameInput.ACCELERATE); true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_DOWN -> { onButtonPressed(GameInput.BRAKE); true }
            else -> false
        }
    }

    fun onKeyUp(keyCode: Int): Boolean {
        Log.w("GameView", "onKeyUp: $keyCode")
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

    fun setStatusUpdateListener(listener: (String) -> Unit) {
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
