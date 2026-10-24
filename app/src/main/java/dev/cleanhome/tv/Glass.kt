package dev.cleanhome.tv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout

/**
 * Frosted-glass tile background: a blurred copy of the home screen behind the tile, a soft white
 * sheen and a thin bright edge. It brightens while the tile has focus; with an [accent] it turns
 * that colour instead and glows around its edges.
 */
class GlassDrawable(
    private val view: View,
    /** The blurred home screen, screen-shaped (see [glassBackdrop]); null draws plain frosted white. */
    private val backdrop: () -> Bitmap?,
    private val radius: Float,
    /** Colour laid over the blur, e.g. to smoke the glass. */
    tint: Int = Color.TRANSPARENT,
    private val accent: Int? = null,
) : Drawable() {

    private val density = view.resources.displayMetrics.density
    private val clip = Path()
    private val rect = RectF()
    private val image = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val tintPaint = Paint().apply { color = tint }
    private val accentPaint = Paint().apply {
        color = accent?.let { Color.argb(0xA6, Color.red(it), Color.green(it), Color.blue(it)) } ?: 0
    }
    private val glowSize = 16 * density
    private var glow: Bitmap? = null
    private var focused = false

    init {
        // The blur shows whatever is behind the tile, so redraw when the tile moves.
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidateSelf() }
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        glow = null
        updateShaders()
    }

    private fun updateShaders() {
        val b = bounds
        val top = if (focused) 0x59 else if (accent != null) 0x26 else 0x33
        val bottom = if (focused) 0x26 else if (accent != null) 0x08 else 0x0D
        sheen.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            Color.argb(top, 255, 255, 255), Color.argb(bottom, 255, 255, 255), Shader.TileMode.CLAMP,
        )
        val rim = if (focused && accent != null) blend(accent, Color.WHITE, 0.5f) else Color.WHITE
        edge.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            withAlpha(rim, 0x80), withAlpha(rim, 0x1A), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        // Tiles sit in parents that don't clip children, so the glow can spill past the tile.
        if (focused && accent != null) {
            val bitmap = glow ?: glowBitmap(accent).also { glow = it }
            canvas.drawBitmap(bitmap, bounds.left - glowSize, bounds.top - glowSize, null)
        }
        canvas.save()
        canvas.clipPath(clip)
        backdrop()?.let { bitmap ->
            // Line the blurred screen up with where this tile sits on it.
            var x = 0
            var y = 0
            var v: View = view
            while (true) {
                x += v.left
                y += v.top
                val parent = v.parent as? View ?: break
                x -= parent.scrollX
                y -= parent.scrollY
                v = parent
            }
            canvas.drawBitmap(bitmap, null, Rect(-x, -y, v.width - x, v.height - y), image)
        }
        canvas.drawRect(rect, if (focused && accent != null) accentPaint else tintPaint)
        canvas.drawRect(rect, sheen)
        canvas.restore()
        val inset = edge.strokeWidth / 2
        canvas.drawRoundRect(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            radius - inset, radius - inset, edge,
        )
    }

    private fun glowBitmap(color: Int): Bitmap {
        val g = glowSize
        val bitmap = Bitmap.createBitmap(
            (bounds.width() + 2 * g).toInt(), (bounds.height() + 2 * g).toInt(), Bitmap.Config.ARGB_8888,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = withAlpha(color, 0xB3)
            maskFilter = BlurMaskFilter(g * 0.6f, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bitmap).drawRoundRect(g, g, g + bounds.width(), g + bounds.height(), radius, radius, paint)
        return bitmap
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_focused in state
        if (now == focused) return false
        focused = now
        updateShaders()
        return true
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private fun withAlpha(color: Int, alpha: Int) =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

private fun blend(color: Int, with: Int, amount: Float) = Color.rgb(
    (Color.red(color) + (Color.red(with) - Color.red(color)) * amount).toInt(),
    (Color.green(color) + (Color.green(with) - Color.green(color)) * amount).toInt(),
    (Color.blue(color) + (Color.blue(with) - Color.blue(color)) * amount).toInt(),
)

/**
 * Glass theme selection effect: a soft glow in [glowColor] spilling out around the tile's edges, or
 * (with [rimOnly]) the bright rim on top of it. Nothing is drawn until the tile has focus.
 */
class EdgeGlowDrawable(
    private val glowColor: Int,
    private val radius: Float,
    density: Float,
    private val rimOnly: Boolean,
) : Drawable() {

    private val glowSize = 18 * density
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = blend(glowColor, Color.WHITE, 0.45f)
    }
    private var glow: Bitmap? = null
    private var focused = false

    override fun onBoundsChange(bounds: Rect) {
        glow = null
    }

    override fun draw(canvas: Canvas) {
        if (!focused || bounds.isEmpty) return
        if (!rimOnly) {
            val bitmap = glow ?: glowBitmap().also { glow = it }
            canvas.drawBitmap(bitmap, bounds.left - glowSize, bounds.top - glowSize, null)
            return
        }
        val inset = rim.strokeWidth / 2
        canvas.drawRoundRect(
            bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset,
            radius - inset, radius - inset, rim,
        )
    }

    // OUTER blur: the glow only shows outside the tile, never over the banner.
    private fun glowBitmap(): Bitmap {
        val g = glowSize
        val bitmap = Bitmap.createBitmap(
            (bounds.width() + 2 * g).toInt(), (bounds.height() + 2 * g).toInt(), Bitmap.Config.ARGB_8888,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(glowColor, 0xF2)
            maskFilter = BlurMaskFilter(g * 0.7f, BlurMaskFilter.Blur.OUTER)
        }
        Canvas(bitmap).drawRoundRect(g, g, g + bounds.width(), g + bounds.height(), radius, radius, paint)
        return bitmap
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_focused in state
        if (now == focused) return false
        focused = now
        return true
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The home screen's current glass backdrop, so panels opened over it can use the same glass. */
object HomeBackdrop {
    var bitmap: Bitmap? = null
}

/**
 * Background for the Edit and All apps panels: glass, except in the Banners theme, which keeps to
 * plain solid colours and gets a dark sheet of [plainAlpha] instead.
 */
fun panelBackground(view: View, radius: Float, tint: Int, plainAlpha: Int): Drawable =
    if (AppStore.tileStyle(view.context) == TileStyle.BANNERS) {
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.argb(plainAlpha, 0x12, 0x12, 0x14))
        }
    } else {
        GlassDrawable(view, { HomeBackdrop.bitmap }, radius, tint)
    }

/** Shows the activity's "panel" as a sheet over the home screen, in line with the dock. */
fun showAsPanel(activity: Activity) {
    val root = activity.findViewById<View>(R.id.panel_root)
    val panel = activity.findViewById<View>(R.id.panel)
    val density = activity.resources.displayMetrics.density
    root.setBackgroundColor(Color.argb(0x59, 0, 0, 0))
    panel.background = panelBackground(
        panel, 30 * density, tint = Color.argb(0xB3, 0x14, 0x14, 0x18), plainAlpha = 0xFF,
    )
    panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
        val side = (32 * density).toInt() // wide enough to cover the dock behind it
        setMargins(side, (40 * density).toInt(), side, side)
    }
    // Keep scrolled content inside the rounded sheet.
    panel.outlineProvider = roundedOutline(30 * density)
    panel.clipToOutline = true
    val pad = (28 * density).toInt()
    panel.setPadding(pad, (24 * density).toInt(), pad, panel.paddingBottom)
}

/**
 * A small, heavily blurred picture of the home screen behind the tiles: the wallpaper laid out
 * exactly as [MainActivity] shows it, with the same dark scrims. Glass tiles stretch it back to
 * screen size, which smooths it further. Safe to call off the main thread.
 */
fun glassBackdrop(ctx: Context, wallpaper: Bitmap?, wholePhoto: Boolean): Bitmap {
    val metrics = ctx.resources.displayMetrics
    val screenW = maxOf(metrics.widthPixels, metrics.heightPixels)
    val screenH = minOf(metrics.widthPixels, metrics.heightPixels)
    val w = 192
    val h = maxOf(1, w * screenH / screenW)
    val scale = w.toFloat() / screenW
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    if (wallpaper == null) {
        ctx.getDrawable(R.drawable.default_wallpaper)!!.apply { setBounds(0, 0, w, h) }.draw(canvas)
    } else {
        canvas.drawBitmap(wallpaper, null, fit(wallpaper, w, h, crop = true), paint)
        if (wholePhoto) {
            canvas.drawColor(0x73000000)
            canvas.drawBitmap(wallpaper, null, fit(wallpaper, w, h, crop = false), paint)
        }
    }
    val dp = metrics.density * scale
    ctx.getDrawable(R.drawable.scrim_top)!!.apply { setBounds(0, 0, w, (160 * dp).toInt()) }.draw(canvas)
    ctx.getDrawable(R.drawable.scrim_bottom)!!.apply { setBounds(0, h - (300 * dp).toInt(), w, h) }.draw(canvas)
    boxBlur(out, radius = 3, passes = 3)
    return out
}

/** Where [bitmap] lands in a [w]×[h] box when cropped to fill it, or fitted whole inside it. */
private fun fit(bitmap: Bitmap, w: Int, h: Int, crop: Boolean): Rect {
    val sx = w.toFloat() / bitmap.width
    val sy = h.toFloat() / bitmap.height
    val s = if (crop) maxOf(sx, sy) else minOf(sx, sy)
    val dw = (bitmap.width * s).toInt()
    val dh = (bitmap.height * s).toInt()
    return Rect((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
}

/** Repeated box blur (close to a Gaussian after a few passes), in place. Fine for small bitmaps. */
private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
    val w = bitmap.width
    val h = bitmap.height
    var src = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    var dst = IntArray(w * h)
    repeat(passes * 2) { pass ->
        val horizontal = pass % 2 == 0
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until length) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val j = (i + k).coerceIn(0, length - 1)
                    val c = src[if (horizontal) line * w + j else j * w + line]
                    a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                val n = radius * 2 + 1
                dst[if (horizontal) line * w + i else i * w + line] =
                    ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        src = dst.also { dst = src }
    }
    bitmap.setPixels(src, 0, w, 0, 0, w, h)
}
