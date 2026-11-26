package com.mi17.nbabackscreen

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 整个背屏边缘环绕得分流光灯效（OLED 护屏优化版）：
 * - 单次触发持续 1.8 秒，采用「快入 -> 环绕流光呼吸 -> 渐隐归零」包络；
 * - 峰值透明度严格控制在 65% 左右（蓝光系进一步降至 58%），流光位置持续旋转不滞留；
 * - 动画结束后完全隐藏停止绘制，不常亮、不耗电、几乎零烧屏风险。
 */
class ScoreGlowBorderView(context: Context) : View(context) {

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val outerRect = RectF()
    private val midRect = RectF()
    private val coreRect = RectF()
    private val shaderMatrix = Matrix()

    private var sweepShader: SweepGradient? = null
    private var activeColor: Int = 0xFFF58426.toInt()
    private var maxAlphaScale: Float = 0.68f
    private var currentAngle: Float = 0f
    private var envelopeAlpha: Float = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    fun trigger(teamColor: Int) {
        activeColor = teamColor
        val hsv = FloatArray(3)
        Color.colorToHSV(teamColor, hsv)
        // 纯蓝光色系进一步控制峰值透明度以保护蓝色子像素
        maxAlphaScale = if (hsv[0] in 195f..265f && hsv[1] > 0.25f) 0.58f else 0.68f

        rebuildShaderIfReady()
        animator?.cancel()
        visibility = VISIBLE

        val startAngle = (Math.random() * 360.0).toFloat()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1850L // 1.85 秒克制时长
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                // 平滑旋转 300°，让像素持续流动不集中在固定边角
                currentAngle = (startAngle + p * 300f) % 360f
                // 亮度包络：0~15% 快速淡入，15~68% 柔和呼吸，68~100% 平滑归零
                envelopeAlpha = when {
                    p < 0.15f -> p / 0.15f
                    p < 0.68f -> {
                        val breath = kotlin.math.sin(((p - 0.15f) / 0.53f) * Math.PI * 2.0).toFloat()
                        0.86f + 0.14f * breath
                    }
                    else -> ((1f - p) / 0.32f).coerceIn(0f, 1f)
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    envelopeAlpha = 0f
                    visibility = GONE
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaderIfReady()
    }

    private fun rebuildShaderIfReady() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val baseStroke = (minOf(w, h) * 0.036f).coerceIn(12f, 28f)
        outerPaint.strokeWidth = baseStroke * 1.45f
        midPaint.strokeWidth = baseStroke * 0.85f
        corePaint.strokeWidth = baseStroke * 0.38f

        val padOuter = outerPaint.strokeWidth / 2f + 2f
        val padMid = midPaint.strokeWidth / 2f + 3f
        val padCore = corePaint.strokeWidth / 2f + 4f

        outerRect.set(padOuter, padOuter, w - padOuter, h - padOuter)
        midRect.set(padMid, padMid, w - padMid, h - padMid)
        coreRect.set(padCore, padCore, w - padCore, h - padCore)

        val dim = withAlpha(activeColor, 48)
        val mid = withAlpha(activeColor, 185)
        val bright = blendWithSoftWhite(activeColor, 0.24f)

        // 双峰对称流光，整圈背屏同时呈现流动光带
        val colors = intArrayOf(
            dim, mid, bright, mid, dim,
            mid, bright, mid, dim
        )
        val positions = floatArrayOf(
            0.00f, 0.12f, 0.25f, 0.38f, 0.50f,
            0.62f, 0.75f, 0.88f, 1.00f
        )
        sweepShader = SweepGradient(w / 2f, h / 2f, colors, positions)
        outerPaint.shader = sweepShader
        midPaint.shader = sweepShader
        corePaint.shader = sweepShader
    }

    override fun onDraw(canvas: Canvas) {
        val shader = sweepShader ?: return
        if (envelopeAlpha <= 0.01f) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        shaderMatrix.setRotate(currentAngle, w / 2f, h / 2f)
        shader.setLocalMatrix(shaderMatrix)

        val cornerR = (minOf(w, h) * 0.11f).coerceIn(22f, 72f)
        val eff = envelopeAlpha * maxAlphaScale

        // 三层同心圆角光晕（全硬件加速、柔顺无锯齿）
        outerPaint.alpha = (eff * 78f).toInt().coerceIn(0, 255)
        midPaint.alpha = (eff * 155f).toInt().coerceIn(0, 255)
        corePaint.alpha = (eff * 240f).toInt().coerceIn(0, 255)

        canvas.drawRoundRect(outerRect, cornerR, cornerR, outerPaint)
        canvas.drawRoundRect(midRect, cornerR * 0.96f, cornerR * 0.96f, midPaint)
        canvas.drawRoundRect(coreRect, cornerR * 0.92f, cornerR * 0.92f, corePaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun blendWithSoftWhite(color: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(color) * inv + 225f * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * inv + 225f * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * inv + 225f * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
