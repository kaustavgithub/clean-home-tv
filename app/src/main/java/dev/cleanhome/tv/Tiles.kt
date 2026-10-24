package dev.cleanhome.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/**
 * An app tile that opens the app on OK: its TV banner, or icon + name if it has no banner.
 * In the Glass theme the focused tile glows around its edges; Apple TV tiles blur [glassBackdrop].
 */
fun makeAppTile(
    activity: Activity,
    parent: ViewGroup,
    app: AppEntry,
    onFocused: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onLaunchFailed: () -> Unit = {},
    glassBackdrop: () -> Bitmap? = { null },
): View {
    if (AppStore.tileStyle(activity) == TileStyle.APPLE_TV) {
        return makeAppleTile(activity, parent, app, onFocused, onLongPress, onLaunchFailed, glassBackdrop)
    }
    val tile = activity.layoutInflater.inflate(R.layout.tile, parent, false)
    val banner = tile.findViewById<ImageView>(R.id.banner)
    val radius = activity.resources.displayMetrics.density * 12
    val glass = AppStore.tileStyle(activity) == TileStyle.GLASS
    if (app.banner != null) {
        banner.setImageDrawable(app.banner)
    } else {
        banner.visibility = View.GONE
        tile.findViewById<View>(R.id.fallback).visibility = View.VISIBLE
        tile.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
        tile.findViewById<TextView>(R.id.label).text = app.label
    }
    tile.contentDescription = app.label

    if (glass) {
        // The glow spills past the tile, so round the banner itself instead of clipping the tile.
        banner.outlineProvider = roundedOutline(radius)
        banner.clipToOutline = true
        // The glow goes behind the tile (backgrounds may draw past a view's edges), the rim in front.
        val color = brandColor(app)
        val density = activity.resources.displayMetrics.density
        tile.background = LayerDrawable(
            arrayOf(EdgeGlowDrawable(color, radius, density, rimOnly = false), tile.background),
        )
        tile.foreground = EdgeGlowDrawable(color, radius, density, rimOnly = true)
    } else {
        tile.outlineProvider = roundedOutline(radius)
        tile.clipToOutline = true
    }

    tile.setOnFocusChangeListener { v, hasFocus ->
        if (glass) {
            v.translationZ = if (hasFocus) 1f else 0f // draw the glow over the neighbours
            val scale = if (hasFocus) 1.04f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
        } else {
            val scale = if (hasFocus) 1.12f else 1f
            v.animate().scaleX(scale).scaleY(scale).translationZ(if (hasFocus) 12f else 0f)
                .setDuration(150).start()
        }
        if (hasFocus) onFocused()
    }
    bindActions(activity, tile, app, onLongPress, onLaunchFailed)
    return tile
}

/**
 * Apple TV (liquid glass) style: a dark glass tile with the app's logo as a white glyph and its
 * name underneath. The focused tile turns the app's brand colour and glows.
 */
private fun makeAppleTile(
    activity: Activity,
    parent: ViewGroup,
    app: AppEntry,
    onFocused: () -> Unit,
    onLongPress: () -> Unit,
    onLaunchFailed: () -> Unit,
    glassBackdrop: () -> Bitmap?,
): View {
    val density = activity.resources.displayMetrics.density
    val tile = activity.layoutInflater.inflate(R.layout.tile_apple, parent, false)
    tile.findViewById<TextView>(R.id.label).text = app.label
    tile.contentDescription = app.label

    val logo = tile.findViewById<ImageView>(R.id.logo)
    val glyph = logoGlyph(app)
    if (glyph != null) {
        logo.setImageBitmap(glyph)
    } else {
        // The logo couldn't be lifted off its background: show the icon itself, rounded.
        logo.setImageDrawable(app.icon)
        logo.layoutParams = logo.layoutParams.apply { width = height }
        logo.outlineProvider = roundedOutline(8 * density)
        logo.clipToOutline = true
    }
    tile.background = GlassDrawable(
        tile, glassBackdrop, 18 * density, tint = TILE_TINT, accent = brandColor(app),
    )

    tile.setOnFocusChangeListener { v, hasFocus ->
        val scale = if (hasFocus) 1.06f else 1f
        v.translationZ = if (hasFocus) 1f else 0f // draw the glow over the neighbours
        v.animate().scaleX(scale).scaleY(scale).setDuration(180).start()
        if (hasFocus) onFocused()
    }
    bindActions(activity, tile, app, onLongPress, onLaunchFailed)
    return tile
}

/** Smoky glass for Apple TV tiles, a little darker than the dock around them. */
private val TILE_TINT = Color.argb(0x66, 0x1C, 0x1C, 0x22)

fun roundedOutline(radius: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) =
        outline.setRoundRect(0, 0, view.width, view.height, radius)
}

private fun bindActions(
    activity: Activity,
    tile: View,
    app: AppEntry,
    onLongPress: () -> Unit,
    onLaunchFailed: () -> Unit,
) {
    tile.setOnClickListener {
        try {
            activity.startActivity(app.launchIntent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
            onLaunchFailed()
        }
    }
    tile.setOnLongClickListener {
        onLongPress()
        true
    }
}
