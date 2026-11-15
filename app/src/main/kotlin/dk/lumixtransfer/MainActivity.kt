package dk.lumixtransfer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val logger: (String) -> Unit = { log(it) }

    // UI
    private lateinit var hostField: EditText
    private lateinit var connectBtn: Button
    private lateinit var selectAllBtn: Button
    private lateinit var selectNewBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var statusView: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var listView: ListView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val adapter = FileAdapter()

    // Tilstand
    private var files: List<RemoteFile> = emptyList()
    private val selected = HashSet<String>()
    private val doneKeys: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private var connecting = false
    private var downloading = false

    @Volatile
    private var cancelRequested = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var camClient: CameraClient? = null
    private var keepAliveExec: ScheduledExecutorService? = null

    @Volatile
    private var lastProgress = 0L
    private var lastKeepAlive = ""
    private val logBuffer = StringBuilder()

    private val prefs by lazy { getSharedPreferences("lumix", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        doneKeys.addAll(prefs.getStringSet("done", emptySet()) ?: emptySet())
        buildUi()
        updateButtons()
        log("Klar. Forbind telefonen til kameraets Wi-Fi, og tryk Forbind.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKeepAlive()
        releaseNetwork()
        worker.shutdownNow()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "Lumix Transfer"
            textSize = 22f
        })

        statusView = TextView(this).apply {
            text = "Ikke forbundet"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(statusView)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostField = EditText(this).apply {
            hint = "Kamera-IP (tom = automatisk)"
            setText(prefs.getString("host", "") ?: "")
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }
        row1.addView(hostField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectBtn = Button(this).apply {
            text = "Forbind"
            setOnClickListener { connect() }
        }
        row1.addView(connectBtn)
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectAllBtn = Button(this).apply {
            text = "Vælg alle"
            setOnClickListener {
                selected.clear()
                files.forEach { selected.add(it.key) }
                refreshList()
            }
        }
        selectNewBtn = Button(this).apply {
            text = "Vælg nye"
            setOnClickListener {
                selected.clear()
                files.filter { !doneKeys.contains(it.key) }.forEach { selected.add(it.key) }
                refreshList()
            }
        }
        row2.addView(selectAllBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(selectNewBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)

        downloadBtn = Button(this).apply {
            text = "Hent valgte"
            setOnClickListener { if (downloading) cancelRequested = true else startDownload() }
        }
        root.addView(downloadBtn)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        root.addView(progressBar)
        progressText = TextView(this).apply {
            textSize = 13f
            visibility = View.GONE
        }
        root.addView(progressText)

        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val key = files[position].key
                if (!selected.add(key)) selected.remove(key)
                refreshList()
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logRow.addView(TextView(this).apply {
            text = "Log"
            textSize = 12f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        logRow.addView(Button(this).apply {
            text = "Kopiér log"
            textSize = 12f
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Lumix Transfer log", synchronized(logBuffer) { logBuffer.toString() }))
                Toast.makeText(this@MainActivity, "Log kopieret", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(logRow)

        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))

        setContentView(root)
    }

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount(): Int = files.size
        override fun getItem(position: Int): Any = files[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val f = files[position]
            val cb = row.getChildAt(0) as CheckBox
            val tv = row.getChildAt(1) as TextView
            cb.isChecked = selected.contains(f.key)
            val done = if (doneKeys.contains(f.key)) "  ✓ hentet" else ""
            val date = f.date.replace('T', ' ').take(16)
            tv.text = "${f.fileName}\n$date · ${formatSize(f.size)}$done"
            return row
        }

        private fun buildRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(CheckBox(this@MainActivity).apply {
                isClickable = false
                isFocusable = false
            })
            row.addView(TextView(this@MainActivity).apply { textSize = 14f })
            return row
        }
    }

    private fun refreshList() {
        adapter.notifyDataSetChanged()
        updateButtons()
    }

    private fun updateButtons() {
        val busy = connecting
        connectBtn.isEnabled = !busy && !downloading
        hostField.isEnabled = !busy && !downloading
        selectAllBtn.isEnabled = files.isNotEmpty() && !downloading
        selectNewBtn.isEnabled = files.isNotEmpty() && !downloading
        downloadBtn.isEnabled = downloading || (selected.isNotEmpty() && !busy)
        downloadBtn.text = if (downloading) "Stop" else "Hent valgte (${selected.size})"
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    private fun log(msg: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
        val line = "$stamp $msg\n"
        synchronized(logBuffer) {
            logBuffer.append(line)
            if (logBuffer.length > 20000) logBuffer.delete(0, logBuffer.length - 15000)
        }
        runOnUiThread {
            if (::logView.isInitialized) {
                logView.text = synchronized(logBuffer) { logBuffer.toString() }
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "ukendt størrelse"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    // ---------------------------------------------------------- Forbindelse

    private fun startKeepAlive(cam: CameraClient) {
        stopKeepAlive()
        camClient = cam
        val exec = Executors.newSingleThreadScheduledExecutor()
        keepAliveExec = exec
        exec.scheduleWithFixedDelay({
            try {
                // Spring over mens der aktivt overføres data.
                if (SystemClock.uptimeMillis() - lastProgress > 3000) {
                    val r = cam.keepAlive()
                    if (r != lastKeepAlive) {
                        lastKeepAlive = r
                        log("Keep-alive: $r")
                    }
                }
            } catch (e: Exception) {
                // ignorer
            }
        }, 2, 4, TimeUnit.SECONDS)
    }

    private fun stopKeepAlive() {
        keepAliveExec?.shutdownNow()
        keepAliveExec = null
        camClient = null
    }

    private fun releaseNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // ignorer
            }
        }
        networkCallback = null
        try {
            cm.bindProcessToNetwork(null)
        } catch (e: Exception) {
            // ignorer
        }
    }

    private fun fail(message: String) {
        connecting = false
        setStatus(message)
        log(message)
        updateButtons()
    }

    private fun connect() {
        if (connecting || downloading) return
        connecting = true
        files = emptyList()
        selected.clear()
        refreshList()
        setStatus("Finder kameraets Wi-Fi …")

        stopKeepAlive()
        releaseNetwork()
        val typedHost = hostField.text.toString().trim()
        val cm = getSystemService(ConnectivityManager::class.java)

        // Bind appen til Wi-Fi, også når Wi-Fi'en (kameraet) ikke har internet.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile
            private var started = false

            override fun onAvailable(network: Network) {
                if (started) return
                started = true
                cm.bindProcessToNetwork(network)
                worker.execute { runSession(typedHost) }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    fail("Ingen Wi-Fi fundet. Forbind først telefonen til kameraets Wi-Fi i Indstillinger.")
                }
            }

            override fun onLost(network: Network) {
                log("Wi-Fi-forbindelsen blev mistet")
            }
        }
        networkCallback = cb
        updateButtons()
        try {
            cm.requestNetwork(request, cb, 10000)
        } catch (e: Exception) {
            fail("Kunne ikke bruge Wi-Fi: ${e.message}")
        }
    }

    private fun uuid(): String {
        val existing = prefs.getString("uuid2", null)
        if (existing != null) return existing
        val id = "4D454930-0100-1000-8001-" +
            UUID.randomUUID().toString().replace("-", "").take(12).uppercase(Locale.US)
        prefs.edit().putString("uuid2", id).apply()
        return id
    }

    private fun runSession(typedHost: String) {
        try {
            val id = uuid()
            val candidates = listOf(typedHost, prefs.getString("host", "") ?: "", "192.168.54.1")
                .filter { it.isNotEmpty() }
                .distinct()

            var host: String? = null
            var reply = ""
            for (c in candidates) {
                runOnUiThread { setStatus("Kontakter kameraet ($c) …") }
                val r = CameraClient(c, logger).handshake(id)
                if (r != null) {
                    host = c
                    reply = r
                    break
                }
            }

            if (host == null) {
                runOnUiThread { setStatus("Søger efter kameraet på netværket …") }
                val wifi = applicationContext.getSystemService(WifiManager::class.java)
                val lock = wifi.createMulticastLock("lumix-transfer")
                lock.setReferenceCounted(false)
                lock.acquire()
                val found = try {
                    CameraClient.discover(logger)
                } finally {
                    lock.release()
                }
                if (found != null) {
                    val r = CameraClient(found, logger).handshake(id)
                    if (r != null) {
                        host = found
                        reply = r
                    }
                }
            }

            if (host == null) {
                runOnUiThread {
                    fail("Kunne ikke finde kameraet. Tjek at kameraets Wi-Fi er slået til, og at telefonen er på kameraets netværk.")
                }
                return
            }

            val cameraHost: String = host
            runOnUiThread {
                setStatus("Forbinder til kameraet … tjek kameraets skærm, og godkend forbindelsen hvis den spørger")
            }
            val approval = CameraClient(cameraHost, logger).awaitApproval(id, {
                runOnUiThread { setStatus("Venter på at kameraet godkender forbindelsen … tjek kameraets skærm") }
            }, { false })
            if (!approval.ok) {
                runOnUiThread { fail(approval.message) }
                return
            }

            prefs.edit().putString("host", host).apply()
            val finalHost = host
            runOnUiThread {
                hostField.setText(finalHost)
                setStatus("Forbundet til $finalHost – henter filliste …")
            }

            val cam = CameraClient(finalHost, logger)
            val list = cam.listAll { n -> runOnUiThread { setStatus("Henter filliste … $n filer") } }
            startKeepAlive(cam)
            val sorted = list.sortedByDescending { it.dateMillis }
            log("Fandt ${sorted.size} filer")

            runOnUiThread {
                files = sorted
                connecting = false
                val fresh = sorted.count { !doneKeys.contains(it.key) }
                setStatus(
                    if (sorted.isEmpty())
                        "Forbundet, men ingen filer fundet. Tjek at kameraet er klar, og prøv Forbind igen."
                    else
                        "Forbundet til $finalHost · ${sorted.size} filer ($fresh nye)"
                )
                refreshList()
            }
        } catch (e: Exception) {
            log("Fejl: $e")
            runOnUiThread { fail("Fejl under forbindelse: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    // ---------------------------------------------------------- Download

    private fun startDownload() {
        val todo = files.filter { selected.contains(it.key) }
        if (todo.isEmpty() || downloading) return
        downloading = true
        cancelRequested = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressBar.progress = 0
        updateButtons()

        worker.execute {
            val dl = Downloader(applicationContext, logger)
            var ok = 0
            var failed = 0
            for ((i, f) in todo.withIndex()) {
                if (cancelRequested) break
                var lastUi = 0L
                log("Henter ${f.fileName} (${i + 1}/${todo.size})")
                val res = dl.download(f, { cancelRequested }, { got, total ->
                    val now = SystemClock.uptimeMillis()
                    lastProgress = now
                    if (now - lastUi > 250 || (total > 0 && got >= total)) {
                        lastUi = now
                        val frac = if (total > 0) (got.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
                        runOnUiThread {
                            progressBar.max = 1000 * todo.size
                            progressBar.progress = (1000 * i + (frac * 1000).toInt())
                            progressText.text = "Fil ${i + 1}/${todo.size}: ${f.fileName} · " +
                                "${formatSize(got)}" + if (total > 0) " af ${formatSize(total)}" else ""
                        }
                    }
                }, { camClient?.reestablish(uuid()) })
                lastProgress = SystemClock.uptimeMillis() - 2000
                try {
                    Thread.sleep(400)
                } catch (e: InterruptedException) {
                    // ignorer
                }
                if (res.ok) {
                    ok++
                    doneKeys.add(f.key)
                    prefs.edit().putStringSet("done", HashSet(doneKeys)).apply()
                    runOnUiThread {
                        selected.remove(f.key)
                        refreshList()
                    }
                } else if (res.message == "afbrudt") {
                    break
                } else {
                    failed++
                    log("Kunne ikke hente ${f.fileName}: ${res.message}")
                }
            }
            val stopped = cancelRequested
            runOnUiThread {
                downloading = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                val summary = buildString {
                    append("Færdig: $ok hentet")
                    if (failed > 0) append(", $failed fejlede")
                    if (stopped) append(" (stoppet)")
                    append(". Filerne ligger i Galleri → mappen Lumix.")
                }
                setStatus(summary)
                log(summary)
                refreshList()
            }
        }
    }
}
