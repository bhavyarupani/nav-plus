package com.roadpulse.auto.driving

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The current-speed / speed-limit circle pair from the active-navigation screen: a plain speed
 * readout that turns red when [SpeedComplianceLevel.OVER_LIMIT], next to a German-style circular
 * speed-limit sign that gains a pulsing amber halo and a "Check speed" label when
 * [SpeedComplianceAdvisor.shouldShowCheckSpeed] is true. The two triggers are independent and
 * never combined into one signal - see the doc comments on [SpeedComplianceAdvisor] for why.
 */
class SpeedComplianceRingView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private var speedKph: Int? = null
        private var limitKph: Int? = null
        private var isOverLimit: Boolean = false
        private var showCheckSpeed: Boolean = false
        private var pulseFraction: Float = 0f

        private val pulseAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = PULSE_DURATION_MILLIS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    pulseFraction = it.animatedValue as Float
                    invalidate()
                }
            }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        private val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT
            }

        fun render(
            speedKph: Int?,
            limitKph: Int?,
            isOverLimit: Boolean,
            showCheckSpeed: Boolean,
        ) {
            this.speedKph = speedKph
            this.limitKph = limitKph
            this.isOverLimit = isOverLimit
            this.showCheckSpeed = showCheckSpeed
            if (showCheckSpeed && !pulseAnimator.isRunning) {
                pulseAnimator.start()
            } else if (!showCheckSpeed && pulseAnimator.isRunning) {
                pulseAnimator.cancel()
                pulseFraction = 0f
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            pulseAnimator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val circleRadius = 34f * density
            // Circles sit in the top portion of the view; the bottom LABEL_RESERVE_DP always
            // stays free so the "Check speed" label is never clipped by the view's own bounds.
            val circleAreaHeight = height - LABEL_RESERVE_DP * density
            val centerY = circleAreaHeight / 2f
            val speedCenterX = circleRadius + 4f * density
            val limitCenterX = width - circleRadius - 4f * density

            drawSpeedCircle(canvas, speedCenterX, centerY, circleRadius, density)
            drawLimitCircle(canvas, limitCenterX, centerY, circleRadius, density)

            if (showCheckSpeed) {
                labelPaint.color = CHECK_SPEED_COLOR
                labelPaint.textSize = 12f * density
                canvas.drawText(
                    "Check speed",
                    limitCenterX,
                    circleAreaHeight + 16f * density,
                    labelPaint,
                )
            }
        }

        private fun drawSpeedCircle(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            radius: Float,
            density: Float,
        ) {
            val color = if (isOverLimit) OVER_LIMIT_COLOR else NORMAL_RING_COLOR
            fillPaint.color = BACKGROUND_FILL_COLOR
            canvas.drawCircle(cx, cy, radius, fillPaint)
            ringPaint.color = color
            ringPaint.strokeWidth = 3f * density
            canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)

            textPaint.color = if (isOverLimit) OVER_LIMIT_COLOR else Color.WHITE
            textPaint.textSize = 20f * density
            canvas.drawText(speedKph?.toString() ?: "--", cx, cy + 4f * density, textPaint)
            labelPaint.color = Color.LTGRAY
            labelPaint.textSize = 9f * density
            canvas.drawText("km/h", cx, cy + 18f * density, labelPaint)
        }

        private fun drawLimitCircle(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            radius: Float,
            density: Float,
        ) {
            if (showCheckSpeed) {
                val haloAlpha = (60 + pulseFraction * 120).toInt().coerceIn(0, 255)
                fillPaint.color = Color.argb(haloAlpha, 255, 176, 32)
                canvas.drawCircle(cx, cy, radius + 10f * density * (0.6f + pulseFraction * 0.4f), fillPaint)
            }
            fillPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, radius, fillPaint)
            ringPaint.color = SPEED_SIGN_RED
            ringPaint.strokeWidth = 6f * density
            canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)

            textPaint.color = Color.BLACK
            textPaint.textSize = 20f * density
            canvas.drawText(limitKph?.toString() ?: "--", cx, cy + 7f * density, textPaint)
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val density = resources.displayMetrics.density
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                (VIEW_HEIGHT_DP * density).toInt(),
            )
        }

        companion object {
            private const val PULSE_DURATION_MILLIS = 900L
            private const val VIEW_HEIGHT_DP = 100f
            private const val LABEL_RESERVE_DP = 22f
            private val NORMAL_RING_COLOR = Color.rgb(226, 233, 242)
            private val OVER_LIMIT_COLOR = Color.rgb(225, 29, 46)
            private val SPEED_SIGN_RED = Color.rgb(225, 29, 46)
            private val CHECK_SPEED_COLOR = Color.rgb(255, 176, 32)
            private val BACKGROUND_FILL_COLOR = Color.rgb(13, 19, 34)
        }
    }
