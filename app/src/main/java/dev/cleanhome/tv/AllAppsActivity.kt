package dev.cleanhome.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.Toast

/** Every installed app, for opening the ones that aren't on the home screen. */
class AllAppsActivity : Activity() {

    private lateinit var grid: GridLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)
        showAsPanel(this)
        grid = findViewById(R.id.grid)
        val tiles = AppStore.installed(this).map { app ->
            makeAppTile(this, grid, app, onLongPress = { showMenu(app) }, glassBackdrop = { HomeBackdrop.bitmap })
        }
        // As many columns as fit the screen (or the glass panel), keeping room for the focused tile
        // to grow, so no tile is cut off at the edge.
        val panel = findViewById<View>(R.id.panel)
        panel.post {
            val lp = tiles.firstOrNull()?.layoutParams as? ViewGroup.MarginLayoutParams ?: return@post
            val tileWidth = lp.width + lp.leftMargin + lp.rightMargin
            val room = panel.width - panel.paddingLeft - panel.paddingRight - (24 * resources.displayMetrics.density).toInt()
            grid.columnCount = (room / tileWidth).coerceIn(1, 6)
            tiles.forEach(grid::addView)
            grid.getChildAt(0)?.requestFocus()
        }
    }

    private fun showMenu(app: AppEntry) {
        val pkgs = AppStore.selected(this)
        val onHome = app.pkg in pkgs
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(arrayOf(if (onHome) "Remove from home" else "Add to home")) { _, _ ->
                if (onHome) pkgs.remove(app.pkg) else pkgs.add(app.pkg)
                AppStore.save(this, pkgs)
                val msg = if (onHome) "Removed from home" else "Added to home"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
