package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class MinetService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockLock = Any()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var proxyServer: LocalHttpProxy? = null
    private var frpcProcess: Process? = null
    private var heartbeatJob: Job? = null

    companion object {
        const val NOTIFICATION_ID = 2026
        const val CHANNEL_ID = "minet_afk_channel"
        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val EXTRA_EMAIL = "extra_email"
        const val EXTRA_PROXY = "extra_proxy"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServiceForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val email = intent?.getStringExtra(EXTRA_EMAIL) ?: ""
        val proxyStr = intent?.getStringExtra(EXTRA_PROXY) ?: ""

        if (email.isEmpty()) {
            MinetManager.addLog("Lỗi: Không tìm thấy Email cấu hình.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (MinetManager.status.value != MiningStatus.RUNNING && MinetManager.status.value != MiningStatus.STARTING) {
            startAFKMining(email, proxyStr)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopAFKMining()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startServiceForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AFK Minet Đang Treo Máy")
            .setContentText("Worker và Tunnel đang chia sẻ băng thông ngầm...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } catch (ex: Exception) {
                    // Fallback to standard startForeground if dataSync FGS gets rejected or restricted
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            MinetManager.addLog("Cảnh báo: Không thể khởi động Dịch vụ chạy ngầm dạng FOREGROUND: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "AFK Minet Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun acquireWakeLock() {
        synchronized(wakeLockLock) {
            try {
                if (wakeLock == null) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MinetAFK::WakeLock").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                    MinetManager.addLog("Đã bật Chế độ chống ngủ (WakeLock) để AFK 24/7.")
                }
            } catch (e: Exception) {
                MinetManager.addLog("Cơ chế Chống ngủ (WakeLock) gặp lỗi: ${e.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockLock) {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    MinetManager.addLog("Đã tắt chế độ chống ngủ (WakeLock).")
                }
            } catch (e: Exception) {
                MinetManager.addLog("Lỗi tắt chế độ chống ngủ (WakeLock): ${e.message}")
            } finally {
                wakeLock = null
            }
        }
    }

    private fun startAFKMining(email: String, proxyStr: String) {
        serviceScope.launch {
            try {
                acquireWakeLock()
                MinetManager.setStatus(MiningStatus.STARTING)
                MinetManager.addLog("Bắt đầu cấu hình khai thác AFK...")

                // Update settings in state
                MinetManager.updateStats { it.copy(email = email) }

                // 1) Fetch setup script
                val client = buildOkHttpClient(proxyStr)
                val ip = fetchIp(client)
                MinetManager.addLog("IP Công cộng phát hiện: $ip")
                MinetManager.updateStats { it.copy(ip = ip) }

                MinetManager.addLog("Mã hóa tải cấu hình từ Minet Dashboard...")
                val rawScript = fetchSetupScript(client, email, ip)
                val files = extractEmbedded(rawScript, this@MinetService)
                if (files.isEmpty()) {
                    throw Exception("Không tìm thấy tệp tin cấu hình nhúng. Vui lòng kiểm tra lại email.")
                }

                // 2) Write configs
                var tunTomlFile: File? = null
                files.forEach { (path, data) ->
                    if (path.endsWith(".toml")) {
                        val file = File(filesDir, "tun.toml")
                        file.writeBytes(data)
                        tunTomlFile = file
                        MinetManager.addLog("Đã ghi cấu hình đường truyền: ${file.name}")
                    }
                }

                if (tunTomlFile == null || !tunTomlFile!!.exists()) {
                    throw Exception("Không tìm thấy cấu hình tun.toml hợp lệ.")
                }

                // Parse tun.toml
                val tunText = tunTomlFile!!.readText(Charsets.UTF_8)
                val remotePort = parseRegexValue(tunText, """remotePort\s*=\s*(\d+)""").toIntOrNull() ?: 0
                val serverAddr = parseRegexValue(tunText, """serverAddr\s*=\s*"([^"]+)"""")
                val serverPort = parseRegexValue(tunText, """serverPort\s*=\s*(\d+)""").toIntOrNull() ?: 0
                val localPort = parseRegexValue(tunText, """localPort\s*=\s*(\d+)""").toIntOrNull() ?: 8888

                MinetManager.addLog("Phân tích cấu hình: Server: $serverAddr:$serverPort | Remote Port: $remotePort | Local Port: $localPort")
                MinetManager.updateStats { it.copy(
                    remotePort = remotePort,
                    serverAddr = serverAddr,
                    localProxyPort = localPort
                ) }

                // 3) Resolve frpc binary from APK nativeLibraryDir
                val frpcFile = File(applicationInfo.nativeLibraryDir, "libfrpc.so")
                if (!frpcFile.exists()) {
                    throw Exception("Lỗi hệ thống: Không tìm thấy nhân mạng libfrpc.so (W^X module).")
                }
                MinetManager.updateStats { it.copy(frpcDownloadProgress = 1.0f) }

                // 4) Start proxy server
                MinetManager.setStatus(MiningStatus.STARTING)
                proxyServer?.stop()
                proxyServer = LocalHttpProxy(localPort).apply {
                    onLog = { msg -> MinetManager.addLog("[Proxy] $msg") }
                    onBytesTransferred = { bytes ->
                        MinetManager.addBytes(bytes)
                    }
                    start()
                }

                // Wait 1s for proxy to bind
                delay(1000)

                // 5) Start frpc process
                MinetManager.addLog("Đang khởi chạy đường truyền FRPC Tunnel...")
                val processBuilder = ProcessBuilder(frpcFile.absolutePath, "-c", tunTomlFile!!.absolutePath)
                processBuilder.directory(filesDir)
                processBuilder.redirectErrorStream(true) // Merge stderr into stdout to prevent pipe buffer lockups
                frpcProcess = processBuilder.start()

                // Read frpc log in coroutine safely
                serviceScope.launch(Dispatchers.IO) {
                    val reader = frpcProcess!!.inputStream.bufferedReader()
                    try {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            MinetManager.addLog("[Tunnel] $line")
                            if (line!!.contains("login to server success", ignoreCase = true)) {
                                MinetManager.updateStats { it.copy(tunnelActive = true) }
                                MinetManager.addLog("Tạo đường truyền tunnel THÀNH CÔNG! Đã kết nối với máy chủ Minet.")
                            }
                        }
                    } catch (e: Exception) {}
                }

                // Check process status
                delay(2000)
                if (frpcProcess != null && !isProcessAlive(frpcProcess!!)) {
                    val errText = frpcProcess?.errorStream?.bufferedReader()?.readText() ?: ""
                    throw Exception("FRPC chết đột ngột: $errText")
                }

                // 6) Heartbeat loop
                MinetManager.setStatus(MiningStatus.RUNNING)
                MinetManager.updateStats { it.copy(workerActive = true) }
                startHeartbeat(client, email, remotePort)

            } catch (e: Exception) {
                MinetManager.setStatus(MiningStatus.ERROR)
                MinetManager.addLog("LỖI KHỞI CHẠY AFK: ${e.message}")
                MinetManager.updateStats { it.copy(errorDetail = e.message ?: "Unknown error") }
                stopAFKMining()
            }
        }
    }

    private fun stopAFKMining() {
        MinetManager.addLog("Đang dừng toàn bộ dịch vụ treo máy...")
        MinetManager.updateStats { it.copy(workerActive = false, tunnelActive = false) }

        heartbeatJob?.cancel()
        heartbeatJob = null

        proxyServer?.stop()
        proxyServer = null

        frpcProcess?.destroy()
        frpcProcess = null

        MinetManager.setStatus(MiningStatus.STOPPED)
        MinetManager.addLog("Hệ thống Treo máy (AFK) đã dừng hoàn toàn.")
        releaseWakeLock()
    }

    private fun startHeartbeat(client: OkHttpClient, email: String, remotePort: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            val emailEnc = java.net.URLEncoder.encode(email, "UTF-8")
            val portEnc = android.util.Base64.encodeToString(remotePort.toString().toByteArray(), android.util.Base64.NO_WRAP)
            val portEncUrl = java.net.URLEncoder.encode(portEnc, "UTF-8")

            // First IP update
            try {
                val ip = MinetManager.stats.value.ip
                updateIpCall(client, email, portEnc, ip)
                MinetManager.addLog("Đã đồng bộ IP và Port thành công.")
            } catch (e: Exception) {
                MinetManager.addLog("Cảnh báo: Không thể đồng bộ IP lúc khởi động: ${e.message}")
            }

            while (isActive) {
                try {
                    // Challenge
                    val chUrl = "https://dashboard.minet.vn/api/minecoin/challenge?email=$emailEnc&port=$portEncUrl"
                    val request = Request.Builder().url(chUrl).build()
                    val responseText = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Challenge HTTP ${response.code}")
                        response.body?.string()?.trim() ?: ""
                    }

                    if (responseText.isNotEmpty()) {
                        val token = android.util.Base64.decode(responseText, android.util.Base64.DEFAULT)
                        val respStr = android.util.Base64.encodeToString(token, android.util.Base64.NO_WRAP)

                        // Verify
                        verifyCall(client, email, portEnc, respStr)
                        MinetManager.updateStats { it.copy(heartbeatsOk = it.heartbeatsOk + 1) }
                        MinetManager.addLog("Nhịp đập rơ-le (Heartbeat) thành công! (+1)")
                    } else {
                        MinetManager.addLog("Thử lại: Nhận nhịp tim rỗng.")
                    }
                } catch (e: Exception) {
                    MinetManager.updateStats { it.copy(heartbeatsError = it.heartbeatsError + 1) }
                    MinetManager.addLog("Nhịp đập rơ-le thất bại: ${e.message}")
                    
                    // Periodically try to re-fetch IP just in case network changed
                    try {
                        val currentIp = fetchIp(client)
                        MinetManager.updateStats { it.copy(ip = currentIp) }
                        updateIpCall(client, email, portEnc, currentIp)
                        MinetManager.addLog("Đã tự động cập nhật lại IP: $currentIp")
                    } catch (ex: Exception) {}
                }

                // Sleep for 30s in 1s increments to respond quickly to cancellation
                for (i in 0 until 30) {
                    if (!isActive) break
                    delay(1000)
                }
            }
        }
    }

    private fun updateIpCall(client: OkHttpClient, email: String, portEnc: String, ip: String) {
        val json = JSONObject().apply {
            put("email", email)
            put("port", portEnc)
            put("ip", ip)
        }
        val mediaType = "application/json".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://dashboard.minet.vn/api/minecoin/update-ip")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    private fun verifyCall(client: OkHttpClient, email: String, portEnc: String, responseStr: String) {
        val json = JSONObject().apply {
            put("email", email)
            put("port", portEnc)
            put("response", responseStr)
        }
        val mediaType = "application/json".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://dashboard.minet.vn/api/minecoin/verify")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    private fun fetchIp(client: OkHttpClient): String {
        val request = Request.Builder().url("https://api.ipify.org").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string()?.trim() ?: "N/A"
        }
    }

    private fun fetchSetupScript(client: OkHttpClient, email: String, ip: String): String {
        val emailEnc = java.net.URLEncoder.encode(email, "UTF-8")
        val ipEnc = java.net.URLEncoder.encode(ip, "UTF-8")
        val url = "https://dashboard.minet.vn/api/minecoin/setup?email=$emailEnc&ip=$ipEnc&mode=dashboard"
        
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val raw = response.body?.string() ?: ""
            
            // Handle raw base64 setup script without catastrophic backtracking or StackOverflow errors on long strings
            val isBase64 = raw.isNotEmpty() && raw.all { it == '=' || it.isWhitespace() || it.isLetterOrDigit() || it == '+' || it == '/' }
            if (isBase64) {
                try {
                    val cleaned = raw.replace("\\s".toRegex(), "")
                    String(android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) {
                    raw
                }
            } else {
                raw
            }
        }
    }

    private fun buildOkHttpClient(proxyStr: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)

        if (proxyStr.isNotEmpty()) {
            try {
                // Parse e.g. socks5://127.0.0.1:1080 or http://127.0.0.1:8080
                val rx = Regex("""^(?:socks5h?|socks4|http|https)://(?:([^:@]+):([^@]+)@)?([^:]+):(\d+)""", RegexOption.IGNORE_CASE)
                val m = rx.find(proxyStr)
                if (m != null) {
                    val host = m.groupValues[3]
                    val port = m.groupValues[4].toInt()
                    // Create Proxy object
                    val proxyType = if (proxyStr.startsWith("socks", ignoreCase = true)) {
                        Proxy.Type.SOCKS
                    } else {
                        Proxy.Type.HTTP
                    }
                    val proxy = Proxy(proxyType, InetSocketAddress(host, port))
                    builder.proxy(proxy)
                    MinetManager.addLog("Đã cấu hình Proxy API: $proxy")
                }
            } catch (e: Exception) {
                MinetManager.addLog("Cảnh báo: Sai định dạng Proxy API - ${e.message}")
            }
        }
        return builder.build()
    }

    private fun extractEmbedded(setupScript: String, context: Context): Map<String, ByteArray> {
        val unwrapped = unwrapOuter(setupScript)
        val out = mutableMapOf<String, ByteArray>()
        val b64Rx = Regex("""printf\s+"%[bs]"\s+"([A-Za-z0-9+/=\\n\s]+?)"\s*\|\s*base64\s+-d\s*>\s*([^ \n]+)""", RegexOption.MULTILINE)
        b64Rx.findAll(unwrapped).forEach { match ->
            val b64 = match.groupValues[1]
            val pathRaw = match.groupValues[2].trim().trim('"')
            try {
                val cleanedB64 = b64.replace("\\n", "").replace("\\s".toRegex(), "")
                val decoded = android.util.Base64.decode(cleanedB64, android.util.Base64.DEFAULT)
                out[pathRaw] = decoded
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return out
    }

    private fun unwrapOuter(script: String, maxDepth: Int = 5): String {
        var s = script
        val unwrapRx = Regex("""printf\s+"%[bs]"\s+"([A-Za-z0-9+/=\\n\s]+?)"\s*\|\s*base64\s+-d\s*\|\s*sh""")
        for (i in 0 until maxDepth) {
            val m = unwrapRx.find(s) ?: return s
            try {
                val b64 = m.groupValues[1]
                val cleanedB64 = b64.replace("\\n", "").replace("\\s".toRegex(), "")
                val decoded = String(android.util.Base64.decode(cleanedB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                if (!decoded.contains("printf") && !decoded.contains("base64")) {
                    return s
                }
                s = decoded
            } catch (e: Exception) {
                return s
            }
        }
        return s
    }

    private fun parseRegexValue(text: String, pattern: String): String {
        val rx = Regex(pattern)
        val match = rx.find(text)
        return match?.groupValues?.getOrNull(1) ?: ""
    }

    private fun isProcessAlive(p: Process): Boolean {
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }
}
