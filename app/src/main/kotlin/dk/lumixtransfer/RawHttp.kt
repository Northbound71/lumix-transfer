package dk.lumixtransfer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Minimal rå HTTP-klient til kameraets filserver. Kameraet er kræsent, så
 * forespørgslen sendes som almindelig tekst over en socket i flere varianter.
 */
object RawHttp {

    const val VARIANTS = 5

    class Response(
        val socket: Socket,
        val input: InputStream,
        val status: Int,
        val headers: Map<String, String>
    )

    /** Sender en rå HTTP GET og læser statuslinje og headers. */
    fun get(url: String, rangeStart: Long, variant: Int): Response {
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

    fun readLine(input: InputStream): String? {
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
    class ChunkedInput(private val src: InputStream) : InputStream() {
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
}
