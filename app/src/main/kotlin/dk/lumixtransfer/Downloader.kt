package dk.lumixtransfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Henter én fil ad gangen fra kameraet og gemmer den i telefonens galleri/Downloads. */
class Downloader(private val context: Context, private val log: (String) -> Unit) {

    class Result(val ok: Boolean, val message: String)

    fun download(
        f: RemoteFile,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit
    ): Result {
        val resolver = context.contentResolver
        val mime = f.mime.ifEmpty { "application/octet-stream" }

        val collection: Uri
        val folder: String
        when {
            mime.startsWith("image/") -> {
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                folder = "DCIM/Lumix"
            }
            mime.startsWith("video/") -> {
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                folder = "DCIM/Lumix"
            }
            else -> {
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                folder = "Download/Lumix"
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (f.dateMillis > 0) put(MediaStore.MediaColumns.DATE_TAKEN, f.dateMillis)
        }
        val uri = resolver.insert(collection, values)
            ?: return Result(false, "Kunne ikke oprette filen på telefonen")

        var written = 0L
        var success = false
        var message = ""

        try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                message = "Kunne ikke åbne filen til skrivning"
            } else {
                out.use { stream ->
                    var attempt = 0
                    while (!success && attempt < MAX_ATTEMPTS) {
                        if (cancelled()) {
                            message = "afbrudt"
                            break
                        }
                        attempt++
                        var conn: HttpURLConnection? = null
                        try {
                            conn = URL(f.url).openConnection() as HttpURLConnection
                            conn.connectTimeout = 8000
                            conn.readTimeout = 15000
                            conn.setRequestProperty("User-Agent", "LumixTransfer/1.0")
                            if (written > 0) conn.setRequestProperty("Range", "bytes=$written-")
                            val code = conn.responseCode
                            if (written > 0 && code != 206) {
                                message = "Kameraet kan ikke genoptage overførslen"
                                break
                            }
                            if (code != 200 && code != 206) throw IOException("HTTP $code")

                            val startOffset = written
                            val len = conn.contentLengthLong
                            val total = if (f.size > 0) f.size else if (len > 0) startOffset + len else 0L
                            val buf = ByteArray(64 * 1024)
                            var stopped = false
                            conn.inputStream.use { input ->
                                while (true) {
                                    if (cancelled()) {
                                        stopped = true
                                        break
                                    }
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    stream.write(buf, 0, n)
                                    written += n
                                    onProgress(written, total)
                                }
                            }
                            if (stopped) {
                                message = "afbrudt"
                                break
                            }
                            if (len > 0 && written - startOffset < len) {
                                throw IOException("Ufuldstændig overførsel")
                            }
                            success = true
                        } catch (e: IOException) {
                            message = e.message ?: "netværksfejl"
                            log("${f.fileName}: forsøg $attempt fejlede ($message)")
                            try {
                                Thread.sleep(1200)
                            } catch (ie: InterruptedException) {
                                break
                            }
                        } finally {
                            conn?.disconnect()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            success = false
            message = e.message ?: e.javaClass.simpleName
        }

        return if (success) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            Result(true, "")
        } else {
            try {
                resolver.delete(uri, null, null)
            } catch (e: Exception) {
                // ignorer
            }
            Result(false, message.ifEmpty { "ukendt fejl" })
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
    }
}
