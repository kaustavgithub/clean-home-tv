package dev.cleanhome.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

class SettingsActivity : Activity() {

    private lateinit var apps: List<AppEntry>
    private lateinit var selected: MutableList<String>
    private lateinit var homeKeyButton: Button
    private lateinit var clockButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        showAsPanel(this)

        apps = AppStore.installed(this)
        selected = AppStore.selected(this)
        if (!AppStore.hasChosen(this)) AppStore.save(this, selected)

        val list = findViewById<ListView>(R.id.list)
        val adapter = AppAdapter(list)
        list.adapter = adapter
        list.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
        apps.forEachIndexed { i, app -> list.setItemChecked(i, app.pkg in selected) }
        list.setOnItemClickListener { _, _, position, _ ->
            val pkg = apps[position].pkg
            if (list.isItemChecked(position)) {
                if (pkg !in selected) selected.add(pkg)
            } else {
                selected.remove(pkg)
            }
            AppStore.save(this, selected)
            adapter.notifyDataSetChanged()
        }

        homeKeyButton = findViewById(R.id.btn_home_key)
        homeKeyButton.setOnClickListener { openAccessibilitySettings() }
        clockButton = findViewById(R.id.btn_clock)
        showClockPosition()
        clockButton.setOnClickListener {
            val positions = ClockPosition.values()
            val next = positions[(AppStore.clockPosition(this).ordinal + 1) % positions.size]
            AppStore.setClockPosition(this, next)
            showClockPosition()
        }
        val tiles = findViewById<Button>(R.id.btn_tiles)
        val showTiles = { tiles.text = "Theme: ${AppStore.tileStyle(this).label}" }
        showTiles()
        tiles.setOnClickListener {
            val styles = TileStyle.values()
            AppStore.setTileStyle(this, styles[(AppStore.tileStyle(this).ordinal + 1) % styles.size])
            showTiles()
        }
        findViewById<View>(R.id.btn_wallpaper).setOnClickListener { showWallpaperUpload() }
        val photoFit = findViewById<Button>(R.id.btn_photo_fit)
        val showPhotoFit = {
            photoFit.text = if (AppStore.wholePhoto(this)) "Photo: Whole" else "Photo: Fill"
        }
        showPhotoFit()
        photoFit.setOnClickListener {
            AppStore.setWholePhoto(this, !AppStore.wholePhoto(this))
            showPhotoFit()
        }
        findViewById<View>(R.id.btn_clear_wallpaper).setOnClickListener {
            AppStore.wallpaperFile(this).delete()
            Toast.makeText(this, "Wallpaper removed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        homeKeyButton.text =
            if (HomeKeyService.isEnabled(this)) "Home button: On ✓" else "Home button: Turn on"
    }

    private fun showClockPosition() {
        clockButton.text = "Clock: ${AppStore.clockPosition(this).label}"
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Open \"Clean Home\" and turn it on", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            openTvSettings(this)
        }
    }

    private fun showWallpaperUpload() {
        val ip = WallpaperServer.localIp(this)
        if (ip == null) {
            Toast.makeText(this, "The TV isn't connected to a network", Toast.LENGTH_LONG).show()
            return
        }
        var dialog: AlertDialog? = null
        val metrics = resources.displayMetrics
        val server = WallpaperServer(
            this,
            maxOf(metrics.widthPixels, metrics.heightPixels),
            minOf(metrics.widthPixels, metrics.heightPixels),
        ) {
            runOnUiThread {
                dialog?.dismiss()
                Toast.makeText(this, "Wallpaper updated", Toast.LENGTH_SHORT).show()
            }
        }
        val port = try {
            server.start()
        } catch (e: IOException) {
            Toast.makeText(this, "Couldn't start upload page", Toast.LENGTH_LONG).show()
            return
        }
        val address = "http://$ip:$port${server.path}"
        val density = resources.displayMetrics.density
        val qr = ImageView(this).apply {
            setImageBitmap(qrCode(address, (200 * density).toInt()))
            contentDescription = address
        }
        val text = TextView(this).apply {
            text = "Scan with your phone's camera (same Wi-Fi as the TV), or open this address in " +
                "its browser:\n$address\n\nThen choose a photo. Keep this window open until it's done."
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding((24 * density).toInt(), 0, 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(qr, LinearLayout.LayoutParams((200 * density).toInt(), (200 * density).toInt()))
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Wallpaper from phone")
            .setView(content)
            .setNegativeButton("Close", null)
            .setOnDismissListener { server.stop() }
            .show()
    }

    private inner class AppAdapter(private val list: ListView) : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.app_row, parent, false)
            val app = apps[position]
            row.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.label).text = app.label
            row.findViewById<CheckBox>(R.id.check).isChecked = list.isItemChecked(position)
            return row
        }
    }
}
