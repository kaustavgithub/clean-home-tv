package dev.cleanhome.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlin.math.sqrt

private const val SIZE = 192

private val glyphs = HashMap<String, Bitmap?>()
private val brandColors = HashMap<String, Int>()

/** [logoGlyph] for [app], worked out once per app. */
fun logoGlyph(app: AppEntry): Bitmap? =
    if (app.pkg in glyphs) glyphs[app.pkg] else logoGlyph(app.icon).also { glyphs[app.pkg] = it }

/** [brandColor] for [app], worked out once per app. */
fun brandColor(app: AppEntry): Int = brandColors.getOrPut(app.pkg) { brandColor(app.icon) }

/**
 * The app's logo as a white glyph, the way Apple TV's glass tiles show it: the icon's background
 * colour is knocked out and everything else turns white, trimmed to the logo's edges.
 * Null when the logo can't be separated cleanly (e.g. a photo-like icon).
 */
fun logoGlyph(icon: Drawable): Bitmap? {
    val pixels = render(icon)
    val background = backgroundColors(pixels) // null: the icon has no background, use its shape
    val out = IntArray(pixels.size)
    var solid = 0
    for (i in pixels.indices) {
        val c = pixels[i]
        val a = Color.alpha(c)
        if (a == 0) continue
        val strength = if (background == null) 1f
        else smoothstep(45f, 110f, background.minOf { distance(c, it) })
        val alpha = (a * strength).toInt()
        if (alpha > 128) solid++
        out[i] = Color.argb(alpha, 255, 255, 255)
    }
    val coverage = solid.toFloat() / pixels.size
    if (coverage < 0.02f || coverage > 0.8f) return null
    return trim(out)
}

/** The icon's brand colour, made bright enough to glow; soft white for grey icons. */
fun brandColor(icon: Drawable): Int {
    val hsv = FloatArray(3)
    var r = 0f; var g = 0f; var b = 0f; var total = 0f
    for (c in render(icon)) {
        if (Color.alpha(c) < 200) continue
        Color.colorToHSV(c, hsv)
        val w = hsv[1] * hsv[1] * hsv[2] + 0.001f
        r += Color.red(c) * w; g += Color.green(c) * w; b += Color.blue(c) * w; total += w
    }
    if (total == 0f) return Color.rgb(0xDD, 0xDD, 0xE6)
    Color.colorToHSV(Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt()), hsv)
    if (hsv[1] < 0.25f) return Color.rgb(0xDD, 0xDD, 0xE6)
    hsv[1] = hsv[1].coerceAtLeast(0.6f)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    return Color.HSVToColor(hsv)
}

private fun render(icon: Drawable): IntArray {
    val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    // Draw a copy so the drawable shown elsewhere keeps its bounds.
    (icon.constantState?.newDrawable() ?: icon).apply { setBounds(0, 0, SIZE, SIZE) }.draw(Canvas(bitmap))
    return IntArray(SIZE * SIZE).also { bitmap.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
}

/**
 * Colours that count as background: the edge colours when the icon is a filled square (they also
 * cover gradients), else the colour filling most of a shaped icon, else none.
 */
private fun backgroundColors(pixels: IntArray): List<Int>? {
    val edge = (0 until SIZE).flatMap {
        listOf(pixels[it], pixels[(SIZE - 1) * SIZE + it], pixels[it * SIZE], pixels[it * SIZE + SIZE - 1])
    }
    val solidEdge = edge.filter { Color.alpha(it) > 200 }
    if (solidEdge.size >= edge.size * 8 / 10) {
        // Every distinct edge colour, so gradients and corner shapes count as background too.
        val colors = solidEdge.distinctBy { (Color.red(it) shr 3 shl 10) or (Color.green(it) shr 3 shl 5) or (Color.blue(it) shr 3) }
        // Plenty to describe a gradient; more only slows things down.
        val step = maxOf(1, colors.size / 48)
        return colors.filterIndexed { i, _ -> i % step == 0 }
    }
    // Shaped icon (circle, squircle): is most of it one colour, with the logo on top?
    val counts = HashMap<Int, Int>()
    var opaque = 0
    for (c in pixels) {
        if (Color.alpha(c) < 200) continue
        opaque++
        val key = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
        counts[key] = (counts[key] ?: 0) + 1
    }
    val (key, count) = counts.maxByOrNull { it.value } ?: return null
    if (count < opaque * 35 / 100) return null
    return listOf(Color.rgb((key shr 8 and 0xF) * 17, (key shr 4 and 0xF) * 17, (key and 0xF) * 17))
}

private fun distance(a: Int, b: Int): Float {
    val dr = (Color.red(a) - Color.red(b)).toFloat()
    val dg = (Color.green(a) - Color.green(b)).toFloat()
    val db = (Color.blue(a) - Color.blue(b)).toFloat()
    return sqrt(dr * dr + dg * dg + db * db)
}

private fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** Crops [pixels] to the visible glyph, so every logo fills its slot the same way. */
private fun trim(pixels: IntArray): Bitmap {
    var left = SIZE; var top = SIZE; var right = -1; var bottom = -1
    for (y in 0 until SIZE) for (x in 0 until SIZE) {
        if (Color.alpha(pixels[y * SIZE + x]) > 40) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    val full = Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    return Bitmap.createBitmap(full, left, top, right - left + 1, bottom - top + 1)
}
