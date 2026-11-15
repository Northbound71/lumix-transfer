package dk.lumixtransfer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/** En fil på kameraet (billede eller video). */
data class RemoteFile(
    val title: String,
    val url: String,
    val size: Long,
    val date: String,
    val dateMillis: Long,
    val mime: String
) {
    /** Bruges til at huske hvilke filer der allerede er hentet. */
    val key: String get() = "$title|$size|$date"

    val fileName: String
        get() {
            val base = (if (title.isBlank()) "lumix_$size" else title)
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val hasExt = base.substringAfterLast('.', "").length in 2..4
            if (hasExt) return base
            val urlName = url.substringBefore('?').substringAfterLast('/')
            val urlExt = if (urlName.contains('.')) urlName.substringAfterLast('.') else ""
            val ext = when {
                urlExt.length in 2..4 -> urlExt
                mime == "image/jpeg" -> "jpg"
                mime == "video/mp4" -> "mp4"
                mime == "video/quicktime" -> "mov"
                mime == "image/x-panasonic-rw2" -> "rw2"
                else -> "bin"
            }
            return "$base.$ext"
        }
}

class CameraClient(private val host: String, private val log: (String) -> Unit) {

    private var controlUrl = "http://$host:60606/Server0/CDS_control"
    private var ua = CP_USER_AGENT
    private var includePana = true

    /**
     * Beder kameraet om adgang. Returnerer kameraets svar, eller null hvis
     * kameraet ikke kunne nås overhovedet.
     */
    fun handshake(uuid: String): String? {
        val name = URLEncoder.encode("Lumix Transfer", "UTF-8")
        val url = "http://$host/cam.cgi?mode=accctrl&type=req_acc&value=$uuid&value2=$name"
        return try {
            val reply = httpGet(url, 3500)
            log("Kamera ($host) svarer: ${reply.replace("\n", " ").take(160)}")
            reply
        } catch (e: Exception) {
            log("Ingen kontakt til $host: ${e.message}")
            null
        }
    }

    class Approval(val ok: Boolean, val message: String)

    /**
     * Beder kameraet om adgang og venter, til kameraet har godkendt forbindelsen.
     * Kameraet svarer "ok_under_research..." mens det afventer, og "ok" når det er klar.
     */
    fun awaitApproval(
        uuid: String,
        onWaiting: () -> Unit,
        cancelled: () -> Boolean
    ): Approval {
        val name = URLEncoder.encode("Lumix Transfer", "UTF-8").replace("+", "%20")
        val url = "http://$host/cam.cgi?mode=accctrl&type=req_acc&value=$uuid&value2=$name"
        val deadline = System.currentTimeMillis() + 90_000
        var notified = false
        var lastWait = ""
        while (System.currentTimeMillis() < deadline && !cancelled()) {
            val raw = try {
                httpGet(url, 4000)
            } catch (e: Exception) {
                log("Ingen kontakt under godkendelse: ${e.message}")
                sleepMs(1000)
                continue
            }
            val line = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            val result = Regex("<result>([^<]*)</result>").find(raw)?.groupValues?.get(1) ?: ""
            val parts = line.split(",")
            val code = (if (result.isNotEmpty()) result else parts[0]).lowercase(Locale.US)
            val mode = parts.getOrNull(2)?.lowercase(Locale.US) ?: ""
            when (code) {
                "ok_under_research", "ok_under_research_no_msg" -> {
                    if (lastWait != code) {
                        log("Kameraet afventer godkendelse ($code)")
                        lastWait = code
                    }
                    if (!notified) {
                        notified = true
                        onWaiting()
                    }
                    sleepMs(1000)
                }
                "ok" -> {
                    log("Kameraet har godkendt forbindelsen (tilstand: ${mode.ifEmpty { "ukendt" }})")
                    return if (mode == "upload" || mode == "upload_bt" || mode == "transfer") {
                        Approval(
                            false,
                            "Kameraet står i '$mode'-tilstand. Slå kameraets Wi-Fi fra, og start " +
                                "forfra med Ny forbindelse → Fjernoptagelse og visning (Remote shooting & view)."
                        )
                    } else {
                        Approval(true, "")
                    }
                }
                "err_user_refused" -> return Approval(false, "Forbindelsen blev afvist på kameraet.")
                "err_others_requesting" -> return Approval(
                    false,
                    "Kameraet har en anden forbindelsesanmodning i gang. Slå kameraets Wi-Fi fra og til igen."
                )
                else -> return Approval(false, "Kameraet svarede uventet: ${line.take(80)}")
            }
        }
        return Approval(
            false,
            if (cancelled()) "Afbrudt." else
                "Kameraet godkendte ikke forbindelsen i tide. Tjek kameraets skærm, og prøv igen."
        )
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // ignorer
        }
    }

    /** Henter alle filer fra kameraet ved at gå rekursivt gennem mapperne. */
    fun listAll(onProgress: (Int) -> Unit): List<RemoteFile> {
        prepare()
        probe()
        val out = ArrayList<RemoteFile>()
        walk("0", out, onProgress, 0)
        return out
    }

    /** Sætter kameraet i afspilningstilstand og læser enhedsbeskrivelsen. */
    private fun prepare() {
        try {
            val r = httpGet("http://$host/cam.cgi?mode=camcmd&value=playmode", 4000)
            log("Skift til afspilning: ${r.replace("\n", " ").take(100)}")
        } catch (e: Exception) {
            log("Skift til afspilning fejlede: ${e.message}")
        }
        try {
            Thread.sleep(800)
        } catch (e: InterruptedException) {
            // ignorer
        }
        try {
            val ddd = httpGet("http://$host:60606/Server0/ddd", 4000, CP_USER_AGENT)
            log("Enhedsbeskrivelse hentet (${ddd.length} tegn)")
            val ctl = findControlUrl(ddd)
            if (ctl != null) {
                controlUrl = if (ctl.startsWith("http")) ctl else "http://$host:60606" +
                    (if (ctl.startsWith("/")) ctl else "/$ctl")
                log("Bruger kontrol-URL $controlUrl")
            }
        } catch (e: Exception) {
            log("Enhedsbeskrivelse kunne ikke hentes: ${e.message}")
            try {
                val found = search({ }, 2500, host)
                if (found != null) {
                    val loc = found.second
                    log("SSDP-placering: $loc")
                    val xml = httpGet(loc, 4000, CP_USER_AGENT)
                    val ctl = findControlUrl(xml)
                    if (ctl != null) {
                        val base = URL(URL(loc), ctl)
                        controlUrl = base.toString()
                        log("Bruger kontrol-URL $controlUrl")
                    }
                } else {
                    log("Kameraet svarede ikke på SSDP")
                }
            } catch (e2: Exception) {
                log("SSDP-opslag fejlede: ${e2.message}")
            }
        }
    }

    private fun findControlUrl(xml: String): String? {
        return try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            var type = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (localName(p.name)) {
                        "serviceType" -> type = p.nextText().trim()
                        "controlURL" -> {
                            val u = p.nextText().trim()
                            if (type.contains("ContentDirectory")) return u
                        }
                    }
                }
                ev = p.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Prøver flere varianter af første Browse-kald, til kameraet accepterer en af dem. */
    private fun probe() {
        val variants = listOf(
            Pair(CP_USER_AGENT, true),
            Pair(CP_USER_AGENT, false),
            Pair(DMS_USER_AGENT, true),
            Pair("LumixTransfer/1.0", true)
        )
        var lastError: Exception? = null
        for (round in 1..2) {
            for ((agent, pana) in variants) {
                ua = agent
                includePana = pana
                try {
                    browse("0", 0, 1)
                    log("Fillisten svarer (UA=$agent, pana=$pana)")
                    return
                } catch (e: IOException) {
                    lastError = e
                    log("Browse afvist (UA=$agent, pana=$pana): ${e.message}")
                }
            }
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                break
            }
        }
        throw lastError ?: IOException("Kameraet svarer ikke på filliste")
    }

    private fun walk(id: String, out: MutableList<RemoteFile>, onProgress: (Int) -> Unit, depth: Int) {
        if (depth > 8) return
        var start = 0
        while (true) {
            val page = browse(id, start, PAGE_SIZE)
            for (e in page.entries) {
                if (e.isContainer) {
                    walk(e.id, out, onProgress, depth + 1)
                } else if (e.file != null) {
                    out.add(e.file)
                    onProgress(out.size)
                }
            }
            start += page.returned
            if (page.returned == 0) break
            if (page.total in 1..start) break
            if (page.total == 0 && page.returned < PAGE_SIZE) break
        }
    }

    private class Entry(val isContainer: Boolean, val id: String, val file: RemoteFile?)
    private class Page(val entries: List<Entry>, val returned: Int, val total: Int)

    private fun browse(objectId: String, start: Int, count: Int): Page {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\" " +
            "xmlns:pana=\"urn:schemas-panasonic-com:pana\">" +
            "<ObjectID>$objectId</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
            "<Filter>*</Filter><StartingIndex>$start</StartingIndex>" +
            "<RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria>" +
            (if (includePana) "<pana:X_FromCP>LumixLink2.0</pana:X_FromCP>" else "") +
            "</u:Browse></s:Body></s:Envelope>"

        val soap = post(controlUrl, body)

        var result = ""
        var returned = 0
        var total = 0
        val p = Xml.newPullParser()
        p.setInput(StringReader(soap))
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (localName(p.name)) {
                    "Result" -> result = p.nextText()
                    "NumberReturned" -> returned = p.nextText().trim().toIntOrNull() ?: 0
                    "TotalMatches" -> total = p.nextText().trim().toIntOrNull() ?: 0
                }
            }
            ev = p.next()
        }
        val entries = parseDidl(result)
        // Hvis kameraet ikke oplyser NumberReturned, brug antal fundne poster.
        if (returned == 0 && entries.isNotEmpty()) returned = entries.size
        return Page(entries, returned, total)
    }

    private fun parseDidl(didl: String): List<Entry> {
        if (didl.isBlank()) return emptyList()
        val list = ArrayList<Entry>()
        val p = Xml.newPullParser()
        p.setInput(StringReader(didl))

        var inEntry = false
        var isContainer = false
        var id = ""
        var title = ""
        var date = ""
        var bestUrl = ""
        var bestSize = -1L
        var bestMime = ""
        var bestIsThumb = true

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (localName(p.name)) {
                    "container", "item" -> {
                        inEntry = true
                        isContainer = localName(p.name) == "container"
                        id = p.getAttributeValue(null, "id") ?: ""
                        title = ""
                        date = ""
                        bestUrl = ""
                        bestSize = -1L
                        bestMime = ""
                        bestIsThumb = true
                    }
                    "title" -> if (inEntry) title = p.nextText().trim()
                    "date" -> if (inEntry) date = p.nextText().trim()
                    "res" -> if (inEntry) {
                        val size = p.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L
                        val proto = p.getAttributeValue(null, "protocolInfo") ?: ""
                        val url = p.nextText().trim()
                        val isThumb = proto.contains("_TN") || proto.contains("_SM_ICO") ||
                            proto.contains("_LRG_ICO")
                        // Vælg den originale fil: ikke-miniature først, derefter størst.
                        val better = url.isNotEmpty() && (
                            bestUrl.isEmpty() ||
                                (bestIsThumb && !isThumb) ||
                                (bestIsThumb == isThumb && size > bestSize)
                            )
                        if (better) {
                            bestUrl = url
                            bestSize = size
                            bestIsThumb = isThumb
                            bestMime = proto.split(":").getOrNull(2) ?: ""
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val n = localName(p.name)
                if ((n == "container" || n == "item") && inEntry) {
                    inEntry = false
                    if (isContainer) {
                        list.add(Entry(true, id, null))
                    } else if (bestUrl.isNotEmpty()) {
                        list.add(
                            Entry(
                                false, id,
                                RemoteFile(
                                    title, bestUrl, maxOf(bestSize, 0L), date,
                                    parseDate(date), bestMime
                                )
                            )
                        )
                    }
                }
            }
            ev = p.next()
        }
        return list
    }

    private fun parseDate(s: String): Long {
        if (s.length < 19) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(s.take(19))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun localName(n: String?): String = (n ?: "").substringAfter(':')

    private fun httpGet(url: String, timeoutMs: Int, agent: String = "LumixTransfer/1.0"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", agent)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 20000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
        c.setRequestProperty("User-Agent", ua)
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val server = c.getHeaderField("Server") ?: ""
                throw IOException("HTTP $code ${server} ${text.replace("\n", " ").take(160)}".trim())
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val PAGE_SIZE = 50
        const val CP_USER_AGENT = "Panasonic MIL DLNA CP UPnP/1.0"
        const val DMS_USER_AGENT = "Android/1 UPnP/1.0 Panasonic-DM-SV/1"

        /** Leder efter kameraet på netværket via SSDP. Returnerer IP-adresse eller null. */
        fun discover(log: (String) -> Unit, timeoutMs: Int = 3000): String? {
            val found = search(log, timeoutMs, null)
            if (found != null) log("Fandt kamera via SSDP: ${found.first}")
            return found?.first
        }

        /** SSDP-søgning. Returnerer (IP, LOCATION-URL). Med onlyHost kun svar fra den adresse. */
        fun search(log: (String) -> Unit, timeoutMs: Int, onlyHost: String?): Pair<String, String>? {
            val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            val locationRe = Regex("(?i)location:\\s*(http://([^:/\\s]+)(:(\\d+))?[^\\s]*)")
            try {
                DatagramSocket().use { s ->
                    s.soTimeout = 700
                    repeat(2) { s.send(DatagramPacket(msg, msg.size, group, 1900)) }
                    val end = System.currentTimeMillis() + timeoutMs
                    val buf = ByteArray(2048)
                    while (System.currentTimeMillis() < end) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            s.receive(pkt)
                            val text = String(pkt.data, 0, pkt.length)
                            val m = locationRe.find(text) ?: continue
                            val location = m.groupValues[1]
                            val ip = m.groupValues[2]
                            val port = m.groupValues[4]
                            if (onlyHost != null) {
                                if (ip == onlyHost) return Pair(ip, location)
                                continue
                            }
                            val looksLikeLumix = port == "60606" ||
                                text.contains("panasonic", ignoreCase = true) ||
                                text.contains("lumix", ignoreCase = true)
                            if (looksLikeLumix) return Pair(ip, location)
                        } catch (e: SocketTimeoutException) {
                            // fortsæt indtil tiden er gået
                        }
                    }
                }
            } catch (e: Exception) {
                log("SSDP-søgning fejlede: ${e.message}")
            }
            return null
        }
    }
}
