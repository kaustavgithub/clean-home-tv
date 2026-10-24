package dev.cleanhome.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import java.io.File

data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable,
    val banner: Drawable?,
    val launchIntent: Intent,
)

enum class ClockPosition(val label: String) {
    TOP_RIGHT("Top right"), TOP_LEFT("Top left"), LEFT("Left"), CENTER("Center"), RIGHT("Right"),
}

/** The home screen theme: how the app tiles look. */
enum class TileStyle(val label: String) {
    BANNERS("Banners"), GLASS("Glass"), APPLE_TV("Apple TV"),
}

/** The user's chosen apps (in display order) plus helpers for listing installed apps. */
object AppStore {
    private const val PREFS = "clean_home"
    private const val KEY_APPS = "apps"
    private const val KEY_CLOCK = "clock"
    private const val KEY_WHOLE_PHOTO = "whole_photo"
    private const val KEY_TILE_STYLE = "tile_style"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasChosen(ctx: Context) = prefs(ctx).contains(KEY_APPS)

    fun selected(ctx: Context): MutableList<String> =
        prefs(ctx).getString(KEY_APPS, "")!!.split(',').filter { it.isNotBlank() }.toMutableList()

    fun save(ctx: Context, pkgs: List<String>) {
        prefs(ctx).edit().putString(KEY_APPS, pkgs.joinToString(",")).apply()
    }

    fun clockPosition(ctx: Context): ClockPosition =
        prefs(ctx).getString(KEY_CLOCK, null)
            ?.let { name -> ClockPosition.values().find { it.name == name } }
            ?: ClockPosition.CENTER

    fun setClockPosition(ctx: Context, position: ClockPosition) {
        prefs(ctx).edit().putString(KEY_CLOCK, position.name).apply()
    }

    /** Show the whole wallpaper photo (true) or crop it to fill the screen (false). */
    fun wholePhoto(ctx: Context) = prefs(ctx).getBoolean(KEY_WHOLE_PHOTO, true)

    fun setWholePhoto(ctx: Context, whole: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WHOLE_PHOTO, whole).apply()
    }

    fun tileStyle(ctx: Context): TileStyle =
        prefs(ctx).getString(KEY_TILE_STYLE, null)
            ?.let { name -> TileStyle.values().find { it.name == name } }
            ?: TileStyle.BANNERS

    fun setTileStyle(ctx: Context, style: TileStyle) {
        prefs(ctx).edit().putString(KEY_TILE_STYLE, style.name).apply()
    }

    fun wallpaperFile(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    private val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)

    /** Every launchable app, preferring its TV (leanback) entry point. Sorted by name. */
    fun installed(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, Pair<ResolveInfo, String>>()
        for (category in categories) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                found.putIfAbsent(ri.activityInfo.packageName, ri to category)
            }
        }
        found.remove(ctx.packageName)
        return found.values.map { (ri, category) -> toEntry(ctx, ri, category) }
            .sortedBy { it.label.lowercase() }
    }

    /** Looks up a single app; null if it's no longer installed. Much cheaper than [installed]. */
    fun entry(ctx: Context, pkg: String): AppEntry? {
        for (category in categories) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg)
            val ri = ctx.packageManager.queryIntentActivities(query, 0).firstOrNull() ?: continue
            return toEntry(ctx, ri, category)
        }
        return null
    }

    private fun toEntry(ctx: Context, ri: ResolveInfo, category: String): AppEntry {
        val pm = ctx.packageManager
        val ai = ri.activityInfo
        return AppEntry(
            pkg = ai.packageName,
            label = ri.loadLabel(pm).toString(),
            icon = ri.loadIcon(pm),
            banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm),
            launchIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setComponent(ComponentName(ai.packageName, ai.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }
}
