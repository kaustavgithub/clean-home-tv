package dev.cleanhome.tv

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var grid: GridLayout
    private lateinit var dock: FrameLayout
    private lateinit var more: GridLayout
    private lateinit var scroll: ScrollView
    private lateinit var wallpaper: ImageView
    private lateinit var wallpaperBlur: ImageView
    private lateinit var empty: TextView
    private var wallpaperStamp: String? = null
    private var glassBackdrop: Bitmap? = null
    private var focusIndex = 0
    private var shownApps: List<String>? = null
    private var shownTileStyle: TileStyle? = null
    /** The tile being moved with the arrow keys, or null when not moving one. */
    private var movingIndex: Int? = null
    private var jiggle: ObjectAnimator? = null
    private var swallowKeyUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        dock = findViewById(R.id.dock)
        more = findViewById(R.id.more)
        scroll = findViewById(R.id.scroll)
        // The first page is exactly one screen tall, so the dock rests on the bottom of the screen
        // and the apps that don't fit in it wait below, out of sight until you scroll down.
        val page = findViewById<View>(R.id.page)
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (page.layoutParams.height != scroll.height) {
                page.layoutParams = page.layoutParams.apply { height = scroll.height }
            }
        }
        // Glass shows what's behind it, so it has to be redrawn as the tiles scroll.
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateGlass() }
        wallpaper = findViewById(R.id.wallpaper)
        wallpaperBlur = findViewById(R.id.wallpaper_blur)
        empty = findViewById(R.id.empty)

        findViewById<View>(R.id.btn_edit).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_all_apps).setOnClickListener {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }
        findViewById<View>(R.id.btn_settings).setOnClickListener { openTvSettings(this) }

        if (!AppStore.hasChosen(this)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadWallpaper()
        applyClockPosition()
        // Coming back to the home screen should be instant, so only rebuild when the choice changed.
        if (AppStore.selected(this) != shownApps || AppStore.tileStyle(this) != shownTileStyle) buildGrid()
    }

    // This is the home screen: Back only scrolls back up to the dock.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (scroll.scrollY > 0) {
            scroll.smoothScrollTo(0, 0)
            focusIndex = 0
            tileAt(0)?.requestFocus()
        }
    }

    private fun applyClockPosition() {
        val position = AppStore.clockPosition(this)
        val inCorner = position == ClockPosition.TOP_RIGHT || position == ClockPosition.TOP_LEFT
        val big = findViewById<LinearLayout>(R.id.clock_center)
        big.visibility = if (inCorner) View.INVISIBLE else View.VISIBLE
        big.gravity = Gravity.CENTER_VERTICAL or when (position) {
            ClockPosition.LEFT -> Gravity.START
            ClockPosition.RIGHT -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        val side = if (position == ClockPosition.TOP_LEFT) Gravity.START else Gravity.END
        val corner = findViewById<LinearLayout>(R.id.clock_corner)
        corner.visibility = if (inCorner) View.VISIBLE else View.GONE
        corner.gravity = side
        corner.layoutParams = (corner.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.TOP or side }

        // Keep the buttons out of the clock's way.
        val clockOnLeft = position == ClockPosition.LEFT || position == ClockPosition.TOP_LEFT
        val buttons = findViewById<View>(R.id.top_buttons)
        val params = buttons.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.TOP or (if (clockOnLeft) Gravity.END else Gravity.START)
        buttons.layoutParams = params
    }

    private fun loadWallpaper() {
        val file = AppStore.wallpaperFile(this)
        val whole = AppStore.wholePhoto(this)
        val stamp = if (file.exists()) "${file.lastModified()}/$whole" else ""
        if (stamp == wallpaperStamp) return
        wallpaperStamp = stamp
        thread {
            val bitmap = if (stamp.isEmpty()) null else BitmapFactory.decodeFile(file.path)
            // Whole photo: fill the bars around it with a blurred, dimmed copy of the same photo.
            val blur = if (bitmap != null && whole) {
                Bitmap.createScaledBitmap(bitmap, 48, maxOf(1, 48 * bitmap.height / bitmap.width), true)
            } else null
            val backdrop = glassBackdrop(this, bitmap, whole)
            runOnUiThread {
                glassBackdrop = backdrop
                HomeBackdrop.bitmap = backdrop
                invalidateGlass()
                if (bitmap == null) {
                    wallpaper.setImageDrawable(null)
                    wallpaperBlur.setImageDrawable(null)
                    return@runOnUiThread
                }
                wallpaper.scaleType = if (whole) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                wallpaper.setImageBitmap(bitmap)
                wallpaperBlur.setImageBitmap(blur)
                if (blur != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    wallpaperBlur.setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
                }
            }
        }
    }

    private fun invalidateGlass() {
        dock.background?.invalidateSelf()
        for (g in listOf(grid, more)) {
            for (i in 0 until g.childCount) g.getChildAt(i).background?.invalidateSelf()
        }
    }

    /** Apps per row, in the dock and below it: Apple TV tiles are narrower, so 6 fit. */
    private fun dockSize() = if (AppStore.tileStyle(this) == TileStyle.APPLE_TV) 6 else 5

    /** Tiles are numbered across the dock and the rows below it. */
    private fun tileAt(index: Int): View? =
        if (index < grid.childCount) grid.getChildAt(index) else more.getChildAt(index - grid.childCount)

    private fun buildGrid() {
        val selected = AppStore.selected(this)
        // Drop apps that were uninstalled since they were chosen.
        val apps = selected.mapNotNull { AppStore.entry(this, it) }
        if (apps.size != selected.size) AppStore.save(this, apps.map { it.pkg })
        shownApps = apps.map { it.pkg }
        shownTileStyle = AppStore.tileStyle(this)

        grid.removeAllViews()
        more.removeAllViews()
        // The first row of apps sits in a frosted dock, the rest in rows below it.
        val columns = dockSize()
        val inDock = apps.take(columns)
        val density = resources.displayMetrics.density
        // Banners stand on their own: no panel behind them on the home screen.
        dock.background = if (AppStore.tileStyle(this) == TileStyle.BANNERS) null
        else GlassDrawable(dock, { glassBackdrop }, 30 * density)
        val pad = (14 * density).toInt()
        dock.setPadding(pad, pad, pad, pad)
        dock.visibility = if (apps.isEmpty()) View.INVISIBLE else View.VISIBLE
        grid.columnCount = maxOf(1, inDock.size)
        more.columnCount = columns
        empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        apps.forEachIndexed { index, app ->
            (if (index < inDock.size) grid else more).addView(makeTile(app, index, inDock = index < inDock.size))
        }
        more.visibility = if (more.childCount > 0) View.VISIBLE else View.GONE

        val target = if (apps.isEmpty()) findViewById(R.id.btn_edit)
        else tileAt(focusIndex.coerceIn(0, apps.size - 1))
        target?.requestFocus()
    }

    private fun makeTile(app: AppEntry, index: Int, inDock: Boolean): View =
        makeAppTile(
            this, if (inDock) grid else more, app,
            onFocused = {
                focusIndex = index
                // Back on the dock: show the whole home screen again.
                if (inDock) scroll.smoothScrollTo(0, 0)
            },
            onLongPress = { showTileMenu(app, index) },
            onLaunchFailed = { buildGrid() },
            glassBackdrop = { glassBackdrop },
        )

    private fun showTileMenu(app: AppEntry, index: Int) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(arrayOf("Move", "Remove from home")) { _, which ->
                if (which == 0) {
                    startMoving(index, app)
                } else {
                    val pkgs = AppStore.selected(this)
                    pkgs.removeAt(index)
                    AppStore.save(this, pkgs)
                    buildGrid()
                }
            }
            .show()
    }

    // Move mode, like Apple TV: the tile jiggles, the arrows carry it, OK or Back puts it down.

    private fun startMoving(index: Int, app: AppEntry) {
        movingIndex = index
        startJiggle(index)
        Toast.makeText(this, "Use the arrows to move ${app.label}. Press OK when done.", Toast.LENGTH_LONG).show()
    }

    private fun startJiggle(index: Int) {
        jiggle?.cancel()
        val tile = tileAt(index) ?: return
        jiggle = ObjectAnimator.ofFloat(tile, View.ROTATION, -1.5f, 1.5f).apply {
            duration = 130
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopMoving() {
        jiggle?.cancel()
        jiggle = null
        movingIndex?.let { tileAt(it)?.rotation = 0f }
        movingIndex = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The OK that ends a move must not also open the app.
        if (swallowKeyUp && event.action == KeyEvent.ACTION_UP) {
            swallowKeyUp = false
            return true
        }
        val moving = movingIndex ?: return super.dispatchKeyEvent(event)
        // Up/down moves a whole row: into or out of the dock, or between the rows below it.
        val columns = dockSize()
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -columns
            KeyEvent.KEYCODE_DPAD_DOWN -> columns
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    stopMoving()
                    swallowKeyUp = true
                }
                return true
            }
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val pkgs = AppStore.selected(this)
        // Down from a row above a shorter last row lands at the end of it.
        val target = minOf(moving + step, pkgs.size - 1)
        if (target >= 0 && target != moving) {
            pkgs.add(target, pkgs.removeAt(moving))
            AppStore.save(this, pkgs)
            jiggle?.cancel()
            focusIndex = target
            movingIndex = target
            buildGrid()
            startJiggle(target)
        }
        return true
    }
}

fun openTvSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Settings not available", Toast.LENGTH_SHORT).show()
    }
}
