package com.roadpulse.auto.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.roadpulse.auto.alerts.OpenGatsoPoiType
import com.roadpulse.auto.traffic.RestroomFeeStatus
import com.roadpulse.auto.traffic.RoadFacility
import com.roadpulse.auto.traffic.RoadFacilityType
import com.roadpulse.auto.traffic.RoadInfrastructurePoint
import com.roadpulse.auto.traffic.RoadInfrastructureType
import com.roadpulse.auto.traffic.RoadSurfaceCondition
import com.roadpulse.auto.traffic.TrafficEventType
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** High-contrast, code-drawn map symbols shared by the phone and Android Auto surfaces. */
class MapMarkerIconFactory(
    context: Context,
) {
    private val density = context.resources.displayMetrics.density
    private val cache = mutableMapOf<String, BitmapDescriptor>()

    fun camera(
        type: OpenGatsoPoiType,
        speedLimitKph: Int?,
    ): BitmapDescriptor =
        icon(
            "camera:$type:$speedLimitKph",
        ) { canvas ->
            when (type) {
                OpenGatsoPoiType.RED_LIGHT_CAMERA -> drawTrafficSignal(canvas, cameraBadge = true)
                OpenGatsoPoiType.AVERAGE_SPEED_CAMERA -> {
                    drawCamera(canvas, PURPLE)
                    drawCameraSpeedBadge(canvas, "AVG")
                }
                OpenGatsoPoiType.RAIL_CROSSING_CAMERA -> drawRailCrossing(canvas, cameraBadge = true)
                else -> {
                    drawCamera(canvas, ORANGE)
                    speedLimitKph?.let { drawCameraSpeedBadge(canvas, it.toString()) }
                }
            }
        }

    fun infrastructure(point: RoadInfrastructurePoint): BitmapDescriptor =
        icon(
            "infrastructure:${point.type}:${point.title}",
        ) { canvas ->
            when (point.type) {
                RoadInfrastructureType.TRAFFIC_SIGNAL -> drawTrafficSignal(canvas)
                RoadInfrastructureType.STOP_SIGN -> drawStop(canvas)
                RoadInfrastructureType.GIVE_WAY_SIGN -> drawGiveWay(canvas)
                RoadInfrastructureType.PRIORITY_ROAD_SIGN -> drawPriorityRoad(canvas)
                RoadInfrastructureType.PRIORITY_AT_JUNCTION_SIGN ->
                    drawWarningTriangle(canvas, Symbol.PRIORITY_JUNCTION)
                RoadInfrastructureType.SPEED_LIMIT_SIGN ->
                    drawCircleSign(
                        canvas,
                        speedLimitIconText(point.title),
                        RED,
                    )
                RoadInfrastructureType.ROAD_RULE_START -> drawRuleBoundary(canvas, starts = true)
                RoadInfrastructureType.ROAD_RULE_END -> drawRuleBoundary(canvas, starts = false)
                RoadInfrastructureType.TRAFFIC_RESTRICTION -> drawNoEntry(canvas)
                RoadInfrastructureType.PEDESTRIAN_CROSSING -> drawPedestrianCrossing(canvas)
                RoadInfrastructureType.RAILWAY_CROSSING -> drawRailCrossing(canvas)
                RoadInfrastructureType.SCHOOL_ZONE -> drawWarningTriangle(canvas, Symbol.SCHOOL)
                RoadInfrastructureType.TRAFFIC_CALMING -> drawWarningTriangle(canvas, Symbol.BUMP)
                RoadInfrastructureType.TUNNEL -> drawBlueInformationSign(canvas, Symbol.TUNNEL)
                RoadInfrastructureType.BRIDGE -> drawBlueInformationSign(canvas, Symbol.BRIDGE)
                RoadInfrastructureType.DIMENSION_RESTRICTION -> drawCircleSign(canvas, "↔", RED)
                RoadInfrastructureType.TOLL -> drawBlueInformationSign(canvas, Symbol.TOLL)
                RoadInfrastructureType.STEEP_GRADE -> drawWarningTriangle(canvas, Symbol.GRADE)
                RoadInfrastructureType.SURFACE_HAZARD -> drawWarningTriangle(canvas, Symbol.ROUGH_ROAD)
                RoadInfrastructureType.MOTORWAY_JUNCTION -> drawBlueInformationSign(canvas, Symbol.EXIT)
                RoadInfrastructureType.OTHER_SIGN -> drawBlueInformationSign(canvas, Symbol.INFORMATION)
            }
        }

    private fun speedLimitIconText(title: String): String =
        when {
            title.contains("No fixed", ignoreCase = true) -> "Ø"
            title.contains("Walking", ignoreCase = true) -> "W"
            title.contains("Variable", ignoreCase = true) -> "V"
            else -> Regex("[0-9]{1,3}").find(title)?.value ?: "!"
        }

    fun trafficEvent(
        type: TrafficEventType,
        boundary: String,
    ): BitmapDescriptor =
        icon(
            "traffic:$type:$boundary",
        ) { canvas ->
            when (type) {
                TrafficEventType.QUEUE -> drawQueue(canvas)
                TrafficEventType.WARNING -> drawWarningTriangle(canvas, Symbol.EXCLAMATION)
                TrafficEventType.ROADWORK -> drawWarningTriangle(canvas, Symbol.ROADWORK)
                TrafficEventType.CLOSURE -> drawNoEntry(canvas)
            }
            drawBoundaryBadge(canvas, boundary)
        }

    fun facility(facility: RoadFacility): BitmapDescriptor =
        icon(
            "facility:${facility.type}:${facility.restroomFeeStatus}",
        ) { canvas ->
            when (facility.type) {
                RoadFacilityType.WEBCAM -> drawCamera(canvas, BLUE)
                RoadFacilityType.PARKING -> drawParking(canvas)
                RoadFacilityType.CHARGING -> drawCharging(canvas)
                RoadFacilityType.RESTROOM -> drawRestroom(canvas, facility.restroomFeeStatus)
            }
        }

    fun weatherWarning(): BitmapDescriptor =
        icon("weather:warning") { canvas ->
            drawWarningTriangle(canvas, Symbol.LIGHTNING)
        }

    fun roadWeather(condition: RoadSurfaceCondition): BitmapDescriptor =
        icon(
            "weather:$condition",
        ) { canvas ->
            when (condition) {
                RoadSurfaceCondition.BLACK_ICE,
                RoadSurfaceCondition.FREEZING_WETNESS,
                RoadSurfaceCondition.FROST,
                RoadSurfaceCondition.SNOW,
                -> drawWeatherTile(canvas, Symbol.SNOWFLAKE, if (condition == RoadSurfaceCondition.SNOW) BLUE else PURPLE)
                RoadSurfaceCondition.DAMP -> drawWeatherTile(canvas, Symbol.DROPLET, BLUE)
                RoadSurfaceCondition.DRY -> drawWeatherTile(canvas, Symbol.SUN, GREEN)
                RoadSurfaceCondition.UNKNOWN -> drawWeatherTile(canvas, Symbol.EXCLAMATION, GREY)
            }
        }

    private fun icon(
        key: String,
        draw: (Canvas) -> Unit,
    ): BitmapDescriptor =
        cache.getOrPut(key) {
            val size = (BASE_SIZE * density).roundToInt().coerceAtLeast(BASE_SIZE.toInt())
            val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(size / BASE_SIZE, size / BASE_SIZE)
            drawHalo(canvas)
            draw(canvas)
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }

    private fun drawHalo(canvas: Canvas) {
        paint(Paint.Style.FILL, Color.argb(190, 7, 18, 28)).also {
            canvas.drawCircle(C, C, 19f, it)
        }
    }

    private fun drawCircleSign(
        canvas: Canvas,
        text: String,
        ringColour: Int,
    ) {
        paint(Paint.Style.FILL, Color.WHITE).also { canvas.drawCircle(C, C, 15.5f, it) }
        paint(Paint.Style.STROKE, ringColour, 4f).also { canvas.drawCircle(C, C, 14f, it) }
        drawCentredText(canvas, text, C, C, if (text.length <= 2) 14f else 11f, BLACK)
    }

    private fun drawStop(canvas: Canvas) {
        val path = Path()
        for (index in 0 until 8) {
            val angle = Math.toRadians((22.5 + index * 45).toDouble())
            val x = C + cos(angle).toFloat() * 16f
            val y = C + sin(angle).toFloat() * 16f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint(Paint.Style.FILL, RED))
        canvas.drawPath(path, paint(Paint.Style.STROKE, Color.WHITE, 1.4f))
        drawCentredText(canvas, "STOP", C, C, 8.2f, Color.WHITE)
    }

    private fun drawGiveWay(canvas: Canvas) {
        val outer =
            Path().apply {
                moveTo(C, 36f)
                lineTo(3.5f, 7f)
                lineTo(36.5f, 7f)
                close()
            }
        canvas.drawPath(outer, paint(Paint.Style.FILL, RED))
        val inner =
            Path().apply {
                moveTo(C, 31.5f)
                lineTo(8f, 10f)
                lineTo(32f, 10f)
                close()
            }
        canvas.drawPath(inner, paint(Paint.Style.FILL, Color.WHITE))
    }

    private fun drawPriorityRoad(canvas: Canvas) {
        val outer =
            Path().apply {
                moveTo(C, 2.5f)
                lineTo(37.5f, C)
                lineTo(C, 37.5f)
                lineTo(2.5f, C)
                close()
            }
        canvas.drawPath(outer, paint(Paint.Style.FILL, Color.WHITE))
        canvas.drawPath(outer, paint(Paint.Style.STROKE, BLACK, 1.2f))
        val yellow =
            Path().apply {
                moveTo(C, 7f)
                lineTo(33f, C)
                lineTo(C, 33f)
                lineTo(7f, C)
                close()
            }
        canvas.drawPath(yellow, paint(Paint.Style.FILL, YELLOW))
        canvas.drawPath(yellow, paint(Paint.Style.STROKE, BLACK, .8f))
    }

    private fun drawTrafficSignal(
        canvas: Canvas,
        cameraBadge: Boolean = false,
    ) {
        canvas.drawRoundRect(RectF(13f, 3f, 27f, 36f), 4f, 4f, paint(Paint.Style.FILL, BLACK))
        canvas.drawCircle(C, 10f, 4.1f, paint(Paint.Style.FILL, Color.rgb(239, 68, 68)))
        canvas.drawCircle(C, 20f, 4.1f, paint(Paint.Style.FILL, Color.rgb(251, 191, 36)))
        canvas.drawCircle(C, 30f, 4.1f, paint(Paint.Style.FILL, Color.rgb(34, 197, 94)))
        if (cameraBadge) drawSmallCameraBadge(canvas)
    }

    private fun drawPedestrianCrossing(canvas: Canvas) {
        drawBlueSquare(canvas)
        val triangle =
            Path().apply {
                moveTo(C, 7f)
                lineTo(7f, 33f)
                lineTo(33f, 33f)
                close()
            }
        canvas.drawPath(triangle, paint(Paint.Style.FILL, Color.WHITE))
        val line = paint(Paint.Style.STROKE, BLACK, 2f)
        canvas.drawCircle(20f, 14f, 2.3f, paint(Paint.Style.FILL, BLACK))
        canvas.drawLine(20f, 16f, 18f, 23f, line)
        canvas.drawLine(18f, 19f, 14f, 22f, line)
        canvas.drawLine(19f, 23f, 14f, 29f, line)
        canvas.drawLine(19f, 23f, 25f, 29f, line)
    }

    private fun drawRailCrossing(
        canvas: Canvas,
        cameraBadge: Boolean = false,
    ) {
        val white = paint(Paint.Style.STROKE, Color.WHITE, 7f)
        val red = paint(Paint.Style.STROKE, RED, 3.5f)
        canvas.drawLine(7f, 7f, 33f, 33f, white)
        canvas.drawLine(33f, 7f, 7f, 33f, white)
        canvas.drawLine(7f, 7f, 33f, 33f, red)
        canvas.drawLine(33f, 7f, 7f, 33f, red)
        if (cameraBadge) drawSmallCameraBadge(canvas)
    }

    private fun drawWarningTriangle(
        canvas: Canvas,
        symbol: Symbol,
    ) {
        val outer =
            Path().apply {
                moveTo(C, 3f)
                lineTo(37f, 35f)
                lineTo(3f, 35f)
                close()
            }
        canvas.drawPath(outer, paint(Paint.Style.FILL, RED))
        val inner =
            Path().apply {
                moveTo(C, 8f)
                lineTo(32.5f, 32f)
                lineTo(7.5f, 32f)
                close()
            }
        canvas.drawPath(inner, paint(Paint.Style.FILL, Color.WHITE))
        drawSymbol(canvas, symbol, BLACK)
    }

    private fun drawBlueInformationSign(
        canvas: Canvas,
        symbol: Symbol,
    ) {
        drawBlueSquare(canvas)
        drawSymbol(canvas, symbol, Color.WHITE)
    }

    private fun drawBlueSquare(canvas: Canvas) {
        canvas.drawRoundRect(RectF(4f, 4f, 36f, 36f), 3f, 3f, paint(Paint.Style.FILL, BLUE))
        canvas.drawRoundRect(RectF(5.5f, 5.5f, 34.5f, 34.5f), 2f, 2f, paint(Paint.Style.STROKE, Color.WHITE, 1.4f))
    }

    private fun drawNoEntry(canvas: Canvas) {
        canvas.drawCircle(C, C, 16f, paint(Paint.Style.FILL, RED))
        canvas.drawRoundRect(RectF(7f, 16f, 33f, 24f), 2f, 2f, paint(Paint.Style.FILL, Color.WHITE))
    }

    private fun drawRuleBoundary(
        canvas: Canvas,
        starts: Boolean,
    ) {
        val colour = if (starts) GREEN else GREY
        canvas.drawRoundRect(RectF(4f, 4f, 36f, 36f), 5f, 5f, paint(Paint.Style.FILL, colour))
        val arrow =
            Path().apply {
                moveTo(8f, 17f)
                lineTo(24f, 17f)
                lineTo(24f, 11f)
                lineTo(33f, 20f)
                lineTo(24f, 29f)
                lineTo(24f, 23f)
                lineTo(8f, 23f)
                close()
            }
        canvas.drawPath(arrow, paint(Paint.Style.FILL, Color.WHITE))
        if (!starts) canvas.drawLine(8f, 32f, 32f, 8f, paint(Paint.Style.STROKE, RED, 4f))
    }

    private fun drawQueue(canvas: Canvas) {
        canvas.drawCircle(C, C, 17f, paint(Paint.Style.FILL, RED))
        val white = paint(Paint.Style.FILL, Color.WHITE)
        listOf(8f, 16f, 24f).forEachIndexed { index, top ->
            val left = 9f + (index % 2) * 3f
            canvas.drawRoundRect(RectF(left, top, left + 18f, top + 7f), 2f, 2f, white)
            canvas.drawCircle(left + 4f, top + 7f, 1.7f, paint(Paint.Style.FILL, BLACK))
            canvas.drawCircle(left + 14f, top + 7f, 1.7f, paint(Paint.Style.FILL, BLACK))
        }
    }

    private fun drawParking(canvas: Canvas) {
        drawBlueSquare(canvas)
        drawCentredText(canvas, "P", C, C, 24f, Color.WHITE)
    }

    private fun drawCharging(canvas: Canvas) {
        canvas.drawRoundRect(RectF(4f, 4f, 36f, 36f), 5f, 5f, paint(Paint.Style.FILL, GREEN))
        val bolt =
            Path().apply {
                moveTo(23f, 6f)
                lineTo(11f, 22f)
                lineTo(19f, 22f)
                lineTo(16f, 35f)
                lineTo(30f, 17f)
                lineTo(22f, 17f)
                close()
            }
        canvas.drawPath(bolt, paint(Paint.Style.FILL, Color.WHITE))
    }

    private fun drawRestroom(
        canvas: Canvas,
        feeStatus: RestroomFeeStatus,
    ) {
        drawBlueSquare(canvas)
        drawCentredText(canvas, "WC", 18f, C, 12f, Color.WHITE)
        val (badge, colour) =
            when (feeStatus) {
                RestroomFeeStatus.FREE -> "0" to GREEN
                RestroomFeeStatus.PAID -> "€" to RED
                RestroomFeeStatus.UNKNOWN -> "?" to GREY
            }
        canvas.drawCircle(32f, 31f, 7.5f, paint(Paint.Style.FILL, colour))
        canvas.drawCircle(32f, 31f, 7f, paint(Paint.Style.STROKE, Color.WHITE, 1.2f))
        drawCentredText(canvas, badge, 32f, 31f, 8f, Color.WHITE)
    }

    private fun drawCamera(
        canvas: Canvas,
        colour: Int,
    ) {
        canvas.drawCircle(C, C, 17f, paint(Paint.Style.FILL, colour))
        canvas.drawRoundRect(RectF(7f, 13f, 33f, 30f), 3f, 3f, paint(Paint.Style.FILL, Color.WHITE))
        canvas.drawRoundRect(RectF(11f, 9f, 20f, 14f), 2f, 2f, paint(Paint.Style.FILL, Color.WHITE))
        canvas.drawCircle(20f, 21.5f, 6f, paint(Paint.Style.FILL, colour))
        canvas.drawCircle(20f, 21.5f, 3f, paint(Paint.Style.FILL, Color.WHITE))
    }

    private fun drawSmallCameraBadge(canvas: Canvas) {
        canvas.drawCircle(31f, 31f, 7f, paint(Paint.Style.FILL, BLUE))
        canvas.drawRoundRect(RectF(26f, 28f, 35f, 34f), 1.2f, 1.2f, paint(Paint.Style.FILL, Color.WHITE))
        canvas.drawCircle(30.5f, 31f, 2f, paint(Paint.Style.FILL, BLUE))
    }

    private fun drawCameraSpeedBadge(
        canvas: Canvas,
        text: String,
    ) {
        canvas.drawCircle(32f, 31f, 8f, paint(Paint.Style.FILL, Color.WHITE))
        canvas.drawCircle(32f, 31f, 7f, paint(Paint.Style.STROKE, RED, 2f))
        drawCentredText(
            canvas,
            text,
            32f,
            31f,
            if (text.length <= 2) 7f else 5f,
            BLACK,
        )
    }

    private fun drawWeatherTile(
        canvas: Canvas,
        symbol: Symbol,
        colour: Int,
    ) {
        canvas.drawCircle(C, C, 17f, paint(Paint.Style.FILL, colour))
        drawSymbol(canvas, symbol, Color.WHITE)
    }

    private fun drawBoundaryBadge(
        canvas: Canvas,
        boundary: String,
    ) {
        canvas.drawCircle(33f, 33f, 6.2f, paint(Paint.Style.FILL, Color.WHITE))
        canvas.drawCircle(33f, 33f, 5.5f, paint(Paint.Style.STROKE, BLACK, 1.2f))
        drawCentredText(canvas, boundary, 33f, 33f, 6.5f, BLACK)
    }

    private fun drawSymbol(
        canvas: Canvas,
        symbol: Symbol,
        colour: Int,
    ) {
        val stroke = paint(Paint.Style.STROKE, colour, 2.4f)
        when (symbol) {
            Symbol.EXCLAMATION -> {
                canvas.drawLine(20f, 14f, 20f, 25f, stroke)
                canvas.drawCircle(20f, 29f, 1.6f, paint(Paint.Style.FILL, colour))
            }
            Symbol.PRIORITY_JUNCTION -> {
                canvas.drawLine(C, 12f, C, 30f, paint(Paint.Style.STROKE, colour, 4f))
                canvas.drawLine(C, 20f, 12f, 15f, stroke)
                canvas.drawLine(C, 20f, 28f, 15f, stroke)
            }
            Symbol.SCHOOL -> {
                canvas.drawCircle(16f, 17f, 2.2f, paint(Paint.Style.FILL, colour))
                canvas.drawCircle(24f, 15f, 2.2f, paint(Paint.Style.FILL, colour))
                canvas.drawLine(16f, 19f, 19f, 26f, stroke)
                canvas.drawLine(24f, 17f, 21f, 24f, stroke)
                canvas.drawLine(18f, 22f, 13f, 27f, stroke)
                canvas.drawLine(22f, 21f, 27f, 26f, stroke)
            }
            Symbol.BUMP -> canvas.drawArc(RectF(11f, 17f, 29f, 31f), 180f, 180f, false, stroke)
            Symbol.TUNNEL -> {
                canvas.drawArc(RectF(10f, 10f, 30f, 31f), 180f, -180f, false, stroke)
                canvas.drawLine(10f, 20f, 10f, 31f, stroke)
                canvas.drawLine(30f, 20f, 30f, 31f, stroke)
            }
            Symbol.BRIDGE -> {
                canvas.drawLine(8f, 27f, 32f, 27f, stroke)
                canvas.drawArc(RectF(10f, 15f, 30f, 31f), 180f, 180f, false, stroke)
                canvas.drawLine(8f, 20f, 32f, 20f, stroke)
            }
            Symbol.TOLL -> drawCentredText(canvas, "€", C, C, 21f, colour)
            Symbol.GRADE -> {
                canvas.drawLine(10f, 29f, 30f, 13f, paint(Paint.Style.STROKE, colour, 5f))
                drawCentredText(canvas, "%", 23f, 25f, 7f, colour)
            }
            Symbol.ROUGH_ROAD -> {
                val path =
                    Path().apply {
                        moveTo(9f, 23f)
                        cubicTo(13f, 14f, 17f, 31f, 21f, 21f)
                        cubicTo(25f, 12f, 28f, 28f, 32f, 20f)
                    }
                canvas.drawPath(path, stroke)
            }
            Symbol.INFORMATION -> drawCentredText(canvas, "i", C, C, 23f, colour)
            Symbol.LIGHTNING -> {
                val bolt =
                    Path().apply {
                        moveTo(23f, 10f)
                        lineTo(13f, 23f)
                        lineTo(20f, 23f)
                        lineTo(17f, 31f)
                        lineTo(28f, 18f)
                        lineTo(21f, 18f)
                        close()
                    }
                canvas.drawPath(bolt, paint(Paint.Style.FILL, colour))
            }
            Symbol.ROADWORK -> {
                canvas.drawCircle(17f, 16f, 2.4f, paint(Paint.Style.FILL, colour))
                canvas.drawLine(18f, 19f, 23f, 26f, stroke)
                canvas.drawLine(18f, 20f, 14f, 26f, stroke)
                canvas.drawLine(23f, 26f, 29f, 29f, stroke)
                canvas.drawLine(25f, 18f, 19f, 30f, stroke)
            }
            Symbol.SNOWFLAKE -> {
                for (angle in listOf(0.0, 60.0, 120.0)) {
                    val radians = Math.toRadians(angle)
                    val dx = cos(radians).toFloat() * 10f
                    val dy = sin(radians).toFloat() * 10f
                    canvas.drawLine(C - dx, C - dy, C + dx, C + dy, stroke)
                }
            }
            Symbol.DROPLET -> {
                val drop =
                    Path().apply {
                        moveTo(C, 8f)
                        cubicTo(16f, 15f, 11f, 21f, 12f, 27f)
                        cubicTo(13f, 36f, 27f, 36f, 28f, 27f)
                        cubicTo(29f, 21f, 24f, 15f, C, 8f)
                        close()
                    }
                canvas.drawPath(drop, paint(Paint.Style.FILL, colour))
            }
            Symbol.SUN -> {
                canvas.drawCircle(C, C, 6f, paint(Paint.Style.FILL, colour))
                for (angle in 0 until 360 step 45) {
                    val radians = Math.toRadians(angle.toDouble())
                    canvas.drawLine(
                        C + cos(radians).toFloat() * 9f,
                        C + sin(radians).toFloat() * 9f,
                        C + cos(radians).toFloat() * 14f,
                        C + sin(radians).toFloat() * 14f,
                        stroke,
                    )
                }
            }
            Symbol.EXIT -> {
                canvas.drawLine(14f, 30f, 14f, 18f, stroke)
                canvas.drawLine(14f, 18f, 27f, 11f, stroke)
                canvas.drawLine(27f, 11f, 20f, 12f, stroke)
                canvas.drawLine(27f, 11f, 26f, 18f, stroke)
            }
        }
    }

    private fun drawCentredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        size: Float,
        colour: Int,
    ) {
        val textPaint =
            paint(Paint.Style.FILL, colour).apply {
                textAlign = Paint.Align.CENTER
                textSize = size
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, centerX, baseline, textPaint)
    }

    private fun paint(
        style: Paint.Style,
        colour: Int,
        strokeWidth: Float = 1f,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style
        color = colour
        this.strokeWidth = strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private enum class Symbol {
        EXCLAMATION,
        PRIORITY_JUNCTION,
        SCHOOL,
        BUMP,
        TUNNEL,
        BRIDGE,
        TOLL,
        GRADE,
        ROUGH_ROAD,
        INFORMATION,
        LIGHTNING,
        ROADWORK,
        SNOWFLAKE,
        DROPLET,
        SUN,
        EXIT,
    }

    companion object {
        private const val BASE_SIZE = 40f
        private const val C = BASE_SIZE / 2f
        private val BLACK = Color.rgb(23, 30, 37)
        private val RED = Color.rgb(211, 47, 47)
        private val ORANGE = Color.rgb(245, 124, 0)
        private val YELLOW = Color.rgb(255, 193, 7)
        private val BLUE = Color.rgb(25, 118, 210)
        private val GREEN = Color.rgb(0, 137, 123)
        private val PURPLE = Color.rgb(123, 31, 162)
        private val GREY = Color.rgb(84, 110, 122)
    }
}
