package com.v2ray.ang.ui.luna

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.min

class MoonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class State { OFF, CONNECTING, ON }

    private val cyan = 0xFF00F0FF.toInt()
    private val violet = 0xFFC400FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grayOff = 0xFF3A3F55.toInt()
    private val grayOffDim = 0xFF1E2238.toInt()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val craterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var state: State = State.OFF
    private var pulseScale = 1f
    private var connectingAngle = 0f
    private var glowAlpha = 0f

    private val pulseAnimator = ValueAnimator.ofFloat(1f, 1.05f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulseScale = it.animatedValue as Float
            invalidate()
        }
    }

    private val connectAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            connectingAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 600
        addUpdateListener {
            glowAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    fun setState(newState: State) {
        if (newState == state) return
        state = newState
        when (state) {
            State.OFF -> {
                pulseAnimator.cancel()
                connectAnimator.cancel()
                glowAnimator.reverse()
            }
            State.CONNECTING -> {
                if (!pulseAnimator.isRunning) pulseAnimator.start()
                if (!connectAnimator.isRunning) connectAnimator.start()
                glowAnimator.start()
            }
            State.ON -> {
                if (!pulseAnimator.isRunning) pulseAnimator.start()
                connectAnimator.cancel()
                glowAnimator.start()
            }
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.6f
        if (r <= 0f) return

        glowPaint.shader = RadialGradient(
            cx, cy, r * 2.1f,
            intArrayOf(
                ColorUtils.setAlphaComponent(cyan, 110),
                ColorUtils.setAlphaComponent(violet, 60),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseR = min(width, height) / 2f * 0.55f
        val r = baseR * pulseScale

        // outer glow (only when not OFF)
        if (glowAlpha > 0f) {
            glowPaint.alpha = (glowAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r * 1.9f, glowPaint)
        }

        // neon rings (when ON or CONNECTING)
        if (glowAlpha > 0f) {
            ringPaint.strokeWidth = 2f * resources.displayMetrics.density
            ringPaint.color = ColorUtils.setAlphaComponent(cyan, (glowAlpha * 90).toInt())
            canvas.drawCircle(cx, cy, r * 1.25f, ringPaint)
            ringPaint.color = ColorUtils.setAlphaComponent(violet, (glowAlpha * 60).toInt())
            canvas.drawCircle(cx, cy, r * 1.45f, ringPaint)
        }

        // moon body — rebuild shader to match current state every frame is cheap
        bodyPaint.shader = if (state == State.OFF) {
            RadialGradient(
                cx - baseR * 0.35f, cy - baseR * 0.35f, baseR * 1.6f,
                intArrayOf(grayOff, grayOffDim),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else {
            RadialGradient(
                cx - baseR * 0.35f, cy - baseR * 0.35f, baseR * 1.6f,
                intArrayOf(
                    ColorUtils.blendARGB(cyan, white, 0.6f),
                    cyan,
                    ColorUtils.blendARGB(cyan, violet, 0.6f),
                    violet,
                ),
                floatArrayOf(0f, 0.4f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, r, bodyPaint)

        // craters
        craterPaint.color = if (state == State.OFF) {
            ColorUtils.blendARGB(grayOff, Color.BLACK, 0.3f)
        } else {
            ColorUtils.setAlphaComponent(0xFF000000.toInt(), 60)
        }
        canvas.drawCircle(cx + r * 0.25f, cy - r * 0.15f, r * 0.12f, craterPaint)
        canvas.drawCircle(cx - r * 0.10f, cy + r * 0.30f, r * 0.09f, craterPaint)
        canvas.drawCircle(cx + r * 0.40f, cy + r * 0.40f, r * 0.06f, craterPaint)
        canvas.drawCircle(cx - r * 0.45f, cy - r * 0.05f, r * 0.07f, craterPaint)

        // connecting arc — rotating around the moon
        if (state == State.CONNECTING) {
            ringPaint.strokeWidth = 3f * resources.displayMetrics.density
            ringPaint.color = cyan
            val arcR = r * 1.6f
            val rect = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
            canvas.drawArc(rect, connectingAngle, 90f, false, ringPaint)
            ringPaint.color = ColorUtils.setAlphaComponent(violet, 180)
            canvas.drawArc(rect, connectingAngle + 180f, 60f, false, ringPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
        connectAnimator.cancel()
        glowAnimator.cancel()
    }
}
