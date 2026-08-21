package com.navplus.core.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import com.navplus.core.settings.VehicleType

/**
 * Builds the map marker bitmap for the user's vehicle.
 *
 * Both icons are composed into the same square canvas and sized to the same
 * visual footprint, so switching between them is a 1:1 swap — the zoom
 * expression in NavMapView applies unchanged.
 *
 * Every icon is drawn pointing north; heading comes from MapLibre's
 * icon-rotate, so nothing here needs to know the bearing.
 */
object VehicleIconFactory {

    val COLOR_SELF = Color.parseColor("#2563EB") // brand blue

    /** Square canvas both icons are composed into, in dp. */
    private const val BOX_DP   = 72f
    /** Arrow height inside the box, in dp. */
    private const val ARROW_DP = 44f
    /** Car height inside the box, in dp — longer than the arrow, since the car is narrow. */
    private const val CAR_DP   = 58f

    /** Decoded + trimmed source photo, kept across icon rebuilds. */
    @Volatile private var carSource: Bitmap? = null

    fun create(context: Context, type: VehicleType, density: Float): Bitmap {
        val s = BOX_DP * density
        val bitmap = Bitmap.createBitmap(s.toInt(), s.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        when (type) {
            VehicleType.ARROW  -> drawArrow(canvas, s, COLOR_SELF)
            VehicleType.MY_CAR -> drawCar(context, canvas, s)
        }
        return bitmap
    }

    // ── Arrow ────────────────────────────────────────────────────────────────

    /**
     * A rounded chevron: apex north, wings swept back, notched tail. Drawn as a
     * round-joined stroke over its own fill, which rounds every corner without
     * hand-authoring arcs.
     */
    private fun drawArrow(canvas: Canvas, s: Float, color: Int) {
        val cx = s / 2f
        val cy = s / 2f
        val h  = s * (ARROW_DP / BOX_DP)
        val w  = h * 0.76f
        val r  = h * 0.11f      // corner radius
        val border = h * 0.085f // white outline width

        // Inset by the corner radius so the stroke inflates back out to h × w.
        val path = Path().apply {
            moveTo(cx, cy - h / 2f + r)
            lineTo(cx + w / 2f - r * 0.7f, cy + h / 2f - r * 0.9f)
            lineTo(cx, cy + h * 0.17f)
            lineTo(cx - w / 2f + r * 0.7f, cy + h / 2f - r * 0.9f)
            close()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap  = Paint.Cap.ROUND
        }

        // Contact shadow, so the marker lifts off the map rather than sitting in it.
        canvas.save()
        canvas.translate(0f, h * 0.045f)
        paint.color = Color.argb(70, 0, 0, 0)
        paint.strokeWidth = (r + border) * 2f
        paint.maskFilter = BlurMaskFilter(h * 0.09f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        canvas.restore()

        // White outline — keeps the arrow readable on dark, satellite and traffic tiles.
        paint.color = Color.WHITE
        paint.strokeWidth = (r + border) * 2f
        canvas.drawPath(path, paint)

        // Body, brighter at the tip so the pointing end reads first.
        paint.shader = LinearGradient(
            cx, cy - h / 2f, cx, cy + h / 2f,
            lighten(color, 0.22f), color, Shader.TileMode.CLAMP,
        )
        paint.strokeWidth = r * 2f
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    private fun lighten(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color)   + (255 - Color.red(color))   * amount).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * amount).toInt(),
        (Color.blue(color)  + (255 - Color.blue(color))  * amount).toInt(),
    )

    // ── Car ──────────────────────────────────────────────────────────────────

    /** Scales the trimmed photo to CAR_DP and drops a blurred silhouette under it. */
    private fun drawCar(context: Context, canvas: Canvas, s: Float) {
        val src = carSource(context)
        if (src == null) {
            // Photo missing or undecodable — the arrow is always a valid marker.
            drawArrow(canvas, s, COLOR_SELF)
            return
        }

        val targetH = s * (CAR_DP / BOX_DP)
        val targetW = targetH * src.width / src.height
        val scaled = Bitmap.createScaledBitmap(src, targetW.toInt(), targetH.toInt(), true)

        val left = (s - scaled.width) / 2f
        val top  = (s - scaled.height) / 2f

        // Contact shadow — lifts the car off light tiles.
        val shadow = IntArray(2)
        val shadowMask = scaled.extractAlpha(blurPaint(targetH * 0.070f), shadow)
        canvas.drawBitmap(
            shadowMask,
            left + shadow[0],
            top + shadow[1] + targetH * 0.030f,
            Paint().apply { color = Color.argb(100, 0, 0, 0) },
        )

        // White rim — a black car would otherwise disappear into dark and satellite
        // tiles. Tight blur, drawn twice, so it reads as an outline rather than a glow.
        val rim = IntArray(2)
        val rimMask = scaled.extractAlpha(blurPaint(targetH * 0.028f), rim)
        val rimPaint = Paint().apply { color = Color.argb(190, 255, 255, 255) }
        repeat(2) {
            canvas.drawBitmap(rimMask, left + rim[0], top + rim[1], rimPaint)
        }

        canvas.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun blurPaint(radius: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    }

    private fun carSource(context: Context): Bitmap? {
        carSource?.let { return it }
        return synchronized(this) {
            carSource ?: runCatching {
                val raw = BitmapFactory.decodeResource(context.resources, R.drawable.ic_my_car)
                trimToAlpha(raw).also { carSource = it }
            }.getOrNull()
        }
    }

    /**
     * Crops transparent padding so CAR_DP describes the car itself, not the
     * export canvas it happens to sit in.
     */
    private fun trimToAlpha(src: Bitmap, threshold: Int = 8): Bitmap {
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        var left = w
        var right = -1
        var top = -1
        var bottom = -1

        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var rowLeft = -1
            var rowRight = -1
            for (x in 0 until w) {
                if ((row[x] ushr 24) > threshold) {
                    if (rowLeft < 0) rowLeft = x
                    rowRight = x
                }
            }
            if (rowRight >= 0) {
                if (top < 0) top = y
                bottom = y
                if (rowLeft < left) left = rowLeft
                if (rowRight > right) right = rowRight
            }
        }

        if (right < 0 || bottom < 0) return src
        val bounds = Rect(left, top, right + 1, bottom + 1)
        if (bounds.width() == w && bounds.height() == h) return src
        return Bitmap.createBitmap(src, bounds.left, bounds.top, bounds.width(), bounds.height())
    }
}
