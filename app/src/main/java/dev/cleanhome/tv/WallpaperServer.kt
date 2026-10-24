package dev.cleanhome.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.ConnectivityManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * A tiny web page on the local network, so a photo can be sent from a phone's browser.
 * It only runs while the "Wallpaper from phone" dialog is open, and only answers on [path],
 * a random secret that changes each time, so other devices on the network can't just guess it.
 */
class WallpaperServer(
    private val ctx: Context,
    /** The TV screen size in pixels; the phone page frames the photo to exactly this. */
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onSaved: () -> Unit,
) {
    private var server: ServerSocket? = null

    /** Secret URL path to show the user, e.g. "/3f9a…". */
    val path = "/" + ByteArray(12).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    /** Starts listening and returns the port. */
    fun start(): Int {
        val socket = ServerSocket().apply { reuseAddress = true }
        try {
            socket.bind(InetSocketAddress(8080))
        } catch (e: IOException) {
            socket.bind(InetSocketAddress(0))
        }
        server = socket
        // One request at a time: a phone only ever sends one photo, and this caps memory use.
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                try { handle(client) } catch (_: IOException) {}
            }
        }
        return socket.localPort
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    private fun handle(client: Socket) = client.use {
        client.soTimeout = 15_000
        val input = client.getInputStream().buffered()
        val requestLine = readLine(input) ?: return
        var contentLength = 0
        var headers = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (++headers > MAX_HEADERS) return
            val colon = header.indexOf(':')
            if (colon > 0 && header.substring(0, colon).trim().equals("Content-Length", true)) {
                contentLength = header.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }

        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)
        val target = parts.getOrNull(1)
        if (method == "GET" && target == path) {
            respond(client, 200, "text/html; charset=utf-8", page())
            return
        }
        if (method != "POST" || target != "$path/upload") {
            respond(client, 404, "text/plain", "Not found")
            return
        }
        if (contentLength <= 0 || contentLength > MAX_UPLOAD) {
            respond(client, 413, "text/plain", "Photo is too large (max 40 MB).")
            return
        }
        val bytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bytes, read, contentLength - read)
            if (n < 0) return
            read += n
        }
        if (save(bytes)) {
            respond(client, 200, "text/plain", "ok")
            onSaved()
        } else {
            respond(client, 400, "text/plain", "That file isn't a photo the TV can read.")
        }
    }

    private fun save(bytes: ByteArray): Boolean = try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, MAX_SIDE / max(w, h).toFloat())
            decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val tmp = File(ctx.filesDir, "wallpaper.tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(AppStore.wallpaperFile(ctx))
    } catch (e: Exception) {
        false
    } catch (e: OutOfMemoryError) {
        false
    }

    private fun page() = ctx.assets.open("upload.html").bufferedReader().use { it.readText() }
        .replace("__W__", screenWidth.toString())
        .replace("__H__", screenHeight.toString())

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            if (buf.size() >= MAX_LINE) return null
            buf.write(b)
        }
    }

    private fun respond(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Payload Too Large"
        }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    companion object {
        private const val MAX_UPLOAD = 40 * 1024 * 1024
        private const val MAX_SIDE = 2560f
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 64

        fun localIp(ctx: Context): String? {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val links = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return links.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }
}
