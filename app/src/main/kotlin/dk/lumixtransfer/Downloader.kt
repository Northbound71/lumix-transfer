package dk.lumixtransfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Henter én fil ad gangen fra kameraet og gemmer den i telefonens galleri/Downloads.
 *
 * Kameraets filserver er kræsen, så forespørgslen sendes som rå HTTP over en socket
 * (som Panasonics egen app, der bruger libcurl), og flere header-varianter prøves.
 */
class Downloader(private val context: Context, private val log: (String) -> Unit) {

    class Result(val ok: Boolean, val message: String)

    private class Response(
        val socket: Socket,
        val input: InputStream,
        val status: Int,
        val headers: Map<String, String>
    )

    /** Den header-variant der sidst virkede; prøves først næste gang. */
    private var preferred = 0
    private var loggedVariant = -1

    fun download(
        f: RemoteFile,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
        recover: () -> Unit = {}
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

        log("URL: ${f.url}")

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
                        val variant = (preferred + attempt) % VARIANTS
                        attempt++
                        if (attempt == 3 || attempt == 6) {
                            log("Genopretter forbindelsen til kameraet …")
                            try {
                                recover()
                            } catch (e: Exception) {
                                log("Genopretning fejlede: ${e.message}")
                            }
                        }
                        var resp: Response? = null
                        try {
                            resp = openGet(f.url, written, variant)
                            if (variant != loggedVariant || resp.status != 200) {
                                log(
                                    "Variant $variant: HTTP ${resp.status}, " +
                                        "længde=${resp.headers["content-length"] ?: "?"}, " +
                                        "type=${resp.headers["content-type"] ?: "?"}, " +
                                        "server=${resp.headers["server"] ?: "?"}"
                                )
                                loggedVariant = variant
                            }
                            if (written > 0 && resp.status != 206) {
                                message = "Kameraet kan ikke genoptage overførslen"
                                break
                            }
                            if (resp.status != 200 && resp.status != 206) {
                                throw IOException("HTTP ${resp.status}")
                            }

                            val startOffset = written
                            val len = resp.headers["content-length"]?.toLongOrNull() ?: -1L
                            val chunked = resp.headers["transfer-encoding"]
                                ?.contains("chunked", ignoreCase = true) == true
                            val total = if (f.size > 0) f.size else if (len > 0) startOffset + len else 0L

                            var stopped = false
                            val buf = ByteArray(64 * 1024)
                            var bodyRead = 0L
                            val body: InputStream = if (chunked) ChunkedInput(resp.input) else resp.input
                            while (true) {
                                if (cancelled()) {
                                    stopped = true
                                    break
                                }
                                val toRead = if (len >= 0 && !chunked)
                                    minOf(buf.size.toLong(), len - bodyRead).toInt() else buf.size
                                if (toRead <= 0) break
                                val n = body.read(buf, 0, toRead)
                                if (n < 0) break
                                stream.write(buf, 0, n)
                                written += n
                                bodyRead += n
                                onProgress(written, total)
                            }
                            if (stopped) {
                                message = "afbrudt"
                                break
                            }
                            if (len >= 0 && !chunked && bodyRead < len) {
                                throw IOException("Ufuldstændig overførsel ($bodyRead af $len)")
                            }
                            if (len < 0 && !chunked && f.size > 0 && written < f.size) {
                                throw IOException("Ufuldstændig overførsel ($written af ${f.size})")
                            }
                            log("${f.fileName}: hentet $written bytes (forventet ${if (f.size > 0) f.size.toString() else "?"})")
                            preferred = variant
                            success = true
                        } catch (e: IOException) {
                            message = e.message ?: e.javaClass.simpleName
                            log("${f.fileName}: forsøg $attempt (variant $variant) fejlede: " +
                                "${e.javaClass.simpleName}: $message")
                            try {
                                Thread.sleep(1000)
                            } catch (ie: InterruptedException) {
                                break
                            }
                        } finally {
                            try {
                                resp?.socket?.close()
                            } catch (e: Exception) {
                                // ignorer
                            }
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

    /** Sender en rå HTTP GET og læser statuslinje og headers. */
    private fun openGet(url: String, rangeStart: Long, variant: Int): Response {
        val u = URL(url)
        val port = if (u.port > 0) u.port else 80
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(u.host, port), 8000)
            socket.soTimeout = 15000
            socket.tcpNoDelay = true
            val path = if (u.file.isNullOrEmpty()) "/" else u.file

            val req = StringBuilder()
            if (variant == 4) {
                req.append("GET $path HTTP/1.0\r\n")
                req.append("Host: ${u.host}:$port\r\n")
            } else {
                req.append("GET $path HTTP/1.1\r\n")
                req.append("Host: ${u.host}:$port\r\n")
                req.append("User-Agent: ${CameraClient.CP_USER_AGENT}\r\n")
                req.append("Accept: */*\r\n")
                when (variant) {
                    0 -> req.append("transferMode.dlna.org: Interactive\r\n")
                    2 -> {
                        req.append("transferMode.dlna.org: Streaming\r\n")
                        req.append("getcontentFeatures.dlna.org: 1\r\n")
                    }
                    3 -> {
                        req.append("transferMode.dlna.org: Interactive\r\n")
                        req.append("Connection: close\r\n")
                    }
                }
            }
            if (rangeStart > 0) req.append("Range: bytes=$rangeStart-\r\n")
            req.append("\r\n")

            val os = socket.getOutputStream()
            os.write(req.toString().toByteArray(Charsets.US_ASCII))
            os.flush()

            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val statusLine = readLine(input) ?: throw IOException("Intet svar fra kameraet")
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Ugyldigt svar: ${statusLine.take(60)}")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("Svar afbrudt i headers")
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            return Response(socket, input, status, headers)
        } catch (e: IOException) {
            try {
                socket.close()
            } catch (e2: Exception) {
                // ignorer
            }
            throw e
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (sb.size() < 8192) {
            val b = input.read()
            if (b < 0) return if (sb.size() == 0) null else sb.toString("ISO-8859-1").trimEnd('\r')
            if (b == '\n'.code) return sb.toString("ISO-8859-1").trimEnd('\r')
            sb.write(b)
        }
        throw IOException("For lang headerlinje")
    }

    /** Minimal afkodning af "Transfer-Encoding: chunked". */
    private inner class ChunkedInput(private val src: InputStream) : InputStream() {
        private var remaining = 0L
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (finished) return -1
            if (remaining == 0L) {
                val line = readLine(src) ?: return -1
                val size = line.substringBefore(';').trim().toLongOrNull(16)
                    ?: throw IOException("Ugyldig chunk-størrelse")
                if (size == 0L) {
                    finished = true
                    return -1
                }
                remaining = size
            }
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw IOException("Svar afbrudt midt i chunk")
            remaining -= n
            if (remaining == 0L) readLine(src) // afsluttende CRLF
            return n
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
        private const val VARIANTS = 5
    }
}
