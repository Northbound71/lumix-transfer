package dk.lumixtransfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque

/**
 * Henter miniaturebilleder fra kameraet ét ad gangen (kameraet tåler ikke mange
 * samtidige forbindelser) og gemmer dem i hukommelsen. De senest viste rækker hentes først.
 */
class ThumbnailLoader(
    private val log: (String) -> Unit,
    private val onLoaded: () -> Unit
) {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val lock = Object()
    private val queue = ArrayDeque<RemoteFile>()
    private val queued = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private var thread: Thread? = null
    private var failures = 0
    private var loggedOk = false
    private var preferredVariant = 0

    @Volatile
    var paused = false
        set(value) {
            field = value
            synchronized(lock) { lock.notifyAll() }
        }

    @Volatile
    private var running = true

    /** Returnerer miniaturebilledet hvis det findes, ellers sættes det i kø. */
    fun get(f: RemoteFile): Bitmap? {
        cache.get(f.key)?.let { return it }
        if (f.thumbUrl.isEmpty()) return null
        synchronized(lock) {
            val failed = failedAt[f.key]
            if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) return null
            if (queued.contains(f.key)) return null
            queue.addFirst(f)
            queued.add(f.key)
            while (queue.size > MAX_QUEUE) {
                val dropped = queue.removeLast()
                queued.remove(dropped.key)
            }
            if (thread == null) {
                thread = Thread({ loop() }, "thumbnails").apply {
                    isDaemon = true
                    start()
                }
            }
            lock.notifyAll()
        }
        return null
    }

    fun clearQueue() {
        synchronized(lock) {
            queue.clear()
            queued.clear()
        }
    }

    fun shutdown() {
        running = false
        synchronized(lock) { lock.notifyAll() }
    }

    private fun loop() {
        while (running) {
            val f: RemoteFile
            synchronized(lock) {
                while (running && (queue.isEmpty() || paused)) {
                    try {
                        lock.wait(500)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
                if (!running) return
                f = queue.removeFirst()
                queued.remove(f.key)
            }
            try {
                val bmp = fetch(f)
                if (bmp != null) {
                    cache.put(f.key, bmp)
                    failures = 0
                    onLoaded()
                } else {
                    markFailed(f, "kunne ikke afkodes")
                }
            } catch (e: Exception) {
                markFailed(f, "${e.javaClass.simpleName}: ${e.message}")
            }
            try {
                Thread.sleep(150)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun markFailed(f: RemoteFile, reason: String) {
        synchronized(lock) { failedAt[f.key] = System.currentTimeMillis() }
        failures++
        if (failures <= 3) log("Miniature fejlede (${f.fileName}, ${f.thumbUrl}): $reason")
    }

    private fun fetch(f: RemoteFile): Bitmap? {
        var lastError: IOException? = null
        for (i in 0 until 3) {
            val variant = intArrayOf(preferredVariant, 1, 0)[i]
            var resp: RawHttp.Response? = null
            try {
                resp = RawHttp.get(f.thumbUrl, 0, variant)
                if (resp.status != 200) throw IOException("HTTP ${resp.status}")
                val chunked = resp.headers["transfer-encoding"]
                    ?.contains("chunked", ignoreCase = true) == true
                val len = resp.headers["content-length"]?.toLongOrNull() ?: -1L
                val body = if (chunked) RawHttp.ChunkedInput(resp.input) else resp.input
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (total < MAX_BYTES) {
                    val toRead = if (len >= 0 && !chunked)
                        minOf(buf.size.toLong(), len - total).toInt() else buf.size
                    if (toRead <= 0) break
                    val n = body.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                if (len >= 0 && !chunked && total < len) throw IOException("Afkortet miniature")
                preferredVariant = variant
                val bytes = out.toByteArray()
                if (!loggedOk) {
                    loggedOk = true
                    log("Første miniature hentet (${bytes.size} bytes, variant $variant)")
                }
                return decode(bytes)
            } catch (e: IOException) {
                lastError = e
                try {
                    Thread.sleep(300)
                } catch (ie: InterruptedException) {
                    throw e
                }
            } finally {
                try {
                    resp?.socket?.close()
                } catch (e: Exception) {
                    // ignorer
                }
            }
        }
        throw lastError ?: IOException("ukendt fejl")
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        while (shortSide > 0 && shortSide / (sample * 2) >= TARGET_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    companion object {
        private const val MAX_QUEUE = 60
        private const val MAX_BYTES = 3L * 1024 * 1024
        private const val RETRY_AFTER_MS = 8000L
        private const val TARGET_PX = 192
    }
}
