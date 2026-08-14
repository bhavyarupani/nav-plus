package com.roadpulse.auto.signage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.roadpulse.auto.R

/**
 * Draws a [SignboardGuidance] into a bitmap for the mobile active-navigation panel and the
 * Android Auto junction-image slot (`RoutingInfo.Builder.setJunctionImage`). Every element comes
 * directly from the guidance data passed in - this never renders placeholder or example text,
 * and returns `null` whenever the fallback level says a signboard isn't reliable enough to show
 * (see [SignboardFallbackEngine]).
 */
object SignboardRenderer {
    fun render(
        context: Context,
        guidance: SignboardGuidance,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        if (guidance.fallbackLevel != SignboardFallbackLevel.FULL_SIGNBOARD &&
            guidance.fallbackLevel != SignboardFallbackLevel.JUNCTION_VIEW
        ) {
            return null
        }
        val junction = guidance.junction ?: return null
        val panel = junction.panels.firstOrNull() ?: return null
        if (panel.type == SignboardType.NONE) return null

        val backgroundColorRes =
            when (panel.type) {
                SignboardType.AUTOBAHN_BLUE -> R.color.rp_role_signboard_autobahn_blue
                SignboardType.DIRECTION_YELLOW, SignboardType.MIXED -> R.color.rp_role_signboard_direction_yellow
                SignboardType.NONE -> return null
            }
        val backgroundColor = ContextCompat.getColor(context, backgroundColorRes)
        val textColor = if (panel.type == SignboardType.AUTOBAHN_BLUE) Color.WHITE else Color.BLACK

        val bitmap = createBitmap(widthPx, heightPx)
        val canvas = Canvas(bitmap)
        val density = widthPx / BASE_WIDTH_PX

        canvas.drawColor(backgroundColor)
        val borderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = textColor
                strokeWidth = 3f * density
            }
        canvas.drawRect(
            borderPaint.strokeWidth / 2,
            borderPaint.strokeWidth / 2,
            widthPx - borderPaint.strokeWidth / 2,
            heightPx - borderPaint.strokeWidth / 2,
            borderPaint,
        )

        var y = 28f * density
        val headingPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = 22f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        panel.exitNumber?.let {
            canvas.drawText("Ausfahrt $it", 16f * density, y, headingPaint)
            y += 30f * density
        }
        panel.roadRef?.takeIf { panel.exitNumber == null }?.let {
            canvas.drawText(it, 16f * density, y, headingPaint)
            y += 30f * density
        }

        val destinationPaint =
            Paint(headingPaint).apply {
                textSize = 18f * density
                typeface = Typeface.DEFAULT
            }
        panel.destinations
            .filter { it.laneIndices.isEmpty() }
            .take(MAX_DESTINATION_LINES)
            .forEach { destination ->
                canvas.drawText(truncateDestination(destination.text), 16f * density, y, destinationPaint)
                y += 24f * density
            }

        if (panel.type == SignboardType.MIXED && panel.insetRoadRef != null) {
            drawInsetPanel(context, canvas, panel.insetRoadRef, widthPx, density)
        }

        junction.laneGuidance?.let { lanes -> drawLaneStrip(context, canvas, lanes, widthPx, heightPx, density) }
        return bitmap
    }

    private fun drawInsetPanel(
        context: Context,
        canvas: Canvas,
        insetRef: String,
        widthPx: Int,
        density: Float,
    ) {
        val insetPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.rp_role_signboard_autobahn_blue)
            }
        val insetRect = RectF(widthPx - 90f * density, 12f * density, widthPx - 12f * density, 44f * density)
        canvas.drawRoundRect(insetRect, 4f * density, 4f * density, insetPaint)
        val insetTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 16f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
        canvas.drawText(insetRef, insetRect.centerX(), insetRect.centerY() + 6f * density, insetTextPaint)
    }

    private fun drawLaneStrip(
        context: Context,
        canvas: Canvas,
        laneGuidance: LaneGuidance,
        widthPx: Int,
        heightPx: Int,
        density: Float,
    ) {
        val laneCount = laneGuidance.lanes.size
        if (laneCount == 0) return
        val stripTop = heightPx - 70f * density
        val stripBottom = heightPx - 8f * density
        val laneWidth = widthPx / laneCount.toFloat()
        val recommendedColor = ContextCompat.getColor(context, R.color.rp_role_signboard_lane_recommended)
        val mutedColor = ContextCompat.getColor(context, R.color.rp_role_signboard_lane_muted)
        laneGuidance.lanes.forEachIndexed { index, lane ->
            val left = index * laneWidth
            val color =
                when (lane.state) {
                    LaneState.RECOMMENDED, LaneState.EXIT_ONLY, LaneState.ADDED -> recommendedColor
                    LaneState.PERMITTED -> Color.LTGRAY
                    LaneState.NOT_RECOMMENDED, LaneState.ENDING, LaneState.MERGING, LaneState.UNKNOWN -> mutedColor
                }
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.FILL
                }
            canvas.drawRect(left + 4f * density, stripTop, left + laneWidth - 4f * density, stripBottom, paint)
            drawLaneArrow(
                canvas,
                lane.shapes.firstOrNull() ?: LaneShape.UNKNOWN,
                left + laneWidth / 2f,
                stripTop + (stripBottom - stripTop) / 2f,
                density,
            )
        }
    }

    private fun drawLaneArrow(
        canvas: Canvas,
        shape: LaneShape,
        cx: Float,
        cy: Float,
        density: Float,
    ) {
        val length = 14f * density
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = 3f * density
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        val angleDegrees =
            when (shape) {
                LaneShape.STRAIGHT -> -90f
                LaneShape.SLIGHT_LEFT -> -135f
                LaneShape.SLIGHT_RIGHT -> -45f
                LaneShape.NORMAL_LEFT, LaneShape.SHARP_LEFT -> 180f
                LaneShape.NORMAL_RIGHT, LaneShape.SHARP_RIGHT -> 0f
                LaneShape.U_TURN_LEFT, LaneShape.U_TURN_RIGHT -> 90f
                LaneShape.UNKNOWN -> -90f
            }
        val radians = Math.toRadians(angleDegrees.toDouble())
        val dx = (Math.cos(radians) * length).toFloat()
        val dy = (Math.sin(radians) * length).toFloat()
        canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint)
    }

    internal fun truncateDestination(text: String): String =
        if (text.length > MAX_DESTINATION_CHARS) text.take(MAX_DESTINATION_CHARS - 1) + "…" else text

    private const val BASE_WIDTH_PX = 320f
    private const val MAX_DESTINATION_LINES = 3
    private const val MAX_DESTINATION_CHARS = 24
}
