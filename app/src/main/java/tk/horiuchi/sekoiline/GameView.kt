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

class GameView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val drawThread = GameThread()
    private var statusUpdateListener: ((String) -> Unit)? = null
    private lateinit var carBitmaps: Map<SteeringState, Bitmap>
    private var backgroundBitmap: Bitmap
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
        backgroundBitmap = BitmapFactory.decodeResource(resources, initialSegment.background)
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

    private var lastBackgroundResId: Int = -1  // 直前の背景IDを保持
    private fun update() {
        distance += speed / 60
        time -= 1f / 60f
        if (time <= 0f) time = 0f


        val segment = courseManager.getCurrentSegment(distance)
        currentCurve = segment.curve
        //Log.d("GameView", "distance = $distance, segment = $segment")

        // ★ 背景画像を必要に応じて更新
        if (segment.background != lastBackgroundResId) {
            backgroundBitmap = BitmapFactory.decodeResource(resources, segment.background)
            lastBackgroundResId = segment.background
        }

        // 背景のスクロール速度も、コースカーブとステアリングの差に応じて変化
        bgScrollSpeed = currentCurve * oversteerFactor * 5f
        bgScrollX += bgScrollSpeed
        if (bgScrollX < 0) bgScrollX += backgroundBitmap.width
        if (bgScrollX >= backgroundBitmap.width) bgScrollX -= backgroundBitmap.width


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

        statusUpdateListener?.invoke("TIME: %.1f   SPEED: ${speed}km/h   DIST: %05d".format(time, distance))
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawBackground(canvas)
        drawRoad(canvas)

        val bitmap = carBitmaps[steeringState] ?: return
        val centerX = width / 2
        val bottomY = height - 100
        val left = centerX - bitmap.width / 2
        val top = bottomY - bitmap.height
        canvas.drawBitmap(bitmap, left.toFloat(), top.toFloat(), null)
    }

    private fun drawBackground(canvas: Canvas) {
        val bgHeight = height / 3
        val bmpW = backgroundBitmap.width
        val bmpH = backgroundBitmap.height
        val scrollX = ((bgScrollX % bmpW) + bmpW) % bmpW
        val intScrollX = scrollX.toInt()

        var drawX = 0
        var srcX = intScrollX

        while (drawX < width) {
            val remainingSrc = bmpW - srcX
            val drawWidth = minOf(remainingSrc, width - drawX)

            val src = Rect(srcX, 0, srcX + drawWidth, bmpH)
            val dst = Rect(drawX, 0, drawX + drawWidth, bgHeight)

            canvas.drawBitmap(backgroundBitmap, src, dst, paint)

            drawX += drawWidth
            srcX = 0
        }
    }

    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    private fun drawRoad(canvas: Canvas) {
        val baseCenterX = width / 2f + roadShift
        val roadHeight = height / 2f
        val numLines = 30
        val lineSpacing = roadHeight / numLines

        for (i in 0 until numLines) {
            val t = i / numLines.toFloat()
            val baseY = height - i * lineSpacing
            val roadWidth = lerp(width * 0.9f, width * 0.2f, t)

            val curveAmount = currentCurve * (t.pow(2)) * maxShift * 0.5f

            val left = baseCenterX - roadWidth / 2 + curveAmount
            val right = baseCenterX + roadWidth / 2 + curveAmount
            val mid = (left + right) / 2

            canvas.drawLine(left, baseY, right, baseY, roadPaint)
            val scale = 1f + (1f - t) * 1.5f
            val lineLength = 6f * scale

            if (i % 2 == 0) canvas.drawLine(mid, baseY, mid, baseY + lineLength, centerLinePaint)
            canvas.drawLine(left + 2, baseY, left + 2, baseY + lineLength, edgeLinePaint)
            canvas.drawLine(right - 2, baseY, right - 2, baseY + lineLength, edgeLinePaint)
        }
    }

    private val roadPaint = Paint().apply { color = Color.GRAY; strokeWidth = 2f }
    private val centerLinePaint = Paint().apply { color = Color.YELLOW; strokeWidth = 6f; isAntiAlias = false }
    private val edgeLinePaint = Paint().apply { color = Color.WHITE; strokeWidth = 2f; isAntiAlias = false }

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

    fun onButtonPressedX(input: GameInput) {
        Log.w("GameView", "onButtonPressed: $input")
        when (input) {
            GameInput.LEFT -> targetSteering = -1f
            GameInput.RIGHT -> targetSteering = 1f
            GameInput.ACCELERATE -> speed = minOf(speed + 10, 240)
            GameInput.BRAKE -> speed = maxOf(speed - 10, 0)
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
            }

            GameInput.RIGHT -> {
                steeringState = when (steeringState) {
                    SteeringState.LEFT2 -> SteeringState.LEFT1
                    SteeringState.LEFT1 -> SteeringState.STRAIGHT
                    SteeringState.STRAIGHT -> SteeringState.RIGHT1
                    SteeringState.RIGHT1 -> SteeringState.RIGHT2
                    else -> SteeringState.RIGHT2
                }
            }

            GameInput.ACCELERATE -> speed = minOf(speed + 10, 240)
            GameInput.BRAKE -> speed = maxOf(speed - 10, 0)
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
