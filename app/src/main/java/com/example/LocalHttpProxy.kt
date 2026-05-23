package com.example

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import java.io.IOException

class LocalHttpProxy(private val port: Int) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onBytesTransferred: (Long) -> Unit = {}
    var onLog: (String) -> Unit = {}

    fun start() {
        job = scope.launch {
            try {
                // Bind to localhost only to match python's tinyproxy config
                serverSocket = ServerSocket(port, 128, java.net.InetAddress.getByName("127.0.0.1")).apply {
                    reuseAddress = true
                }
                onLog("HTTP proxy system running at 127.0.0.1:$port")
                MinetManager.updateStats { it.copy(proxyActive = true) }

                while (isActive) {
                    val clientSocket = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        null
                    } ?: break

                    launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                onLog("Proxy Server initialization error: ${e.message}")
            } finally {
                MinetManager.updateStats { it.copy(proxyActive = false) }
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        job?.cancel()
        scope.cancel()
        onLog("HTTP proxy stopped.")
    }

    private suspend fun handleClient(client: Socket) {
        withContext(Dispatchers.IO) {
            try {
                client.soTimeout = 30000
                val reader = client.getInputStream()
                val writer = client.getOutputStream()

                val buf = ByteArray(16384)
                var bytesReadTotal = 0
                val headerBuffer = StringBuilder()

                // Read request headers
                while (true) {
                    val r = try {
                        reader.read(buf, 0, minOf(buf.size, 4096))
                    } catch (e: SocketTimeoutException) {
                        -1
                    }
                    if (r <= 0) break
                    bytesReadTotal += r
                    headerBuffer.append(String(buf, 0, r, Charsets.US_ASCII))
                    if (headerBuffer.contains("\r\n\r\n")) {
                        break
                    }
                    if (headerBuffer.length > 32768) return@withContext
                }

                val requestText = headerBuffer.toString()
                if (requestText.isEmpty()) {
                    try { client.close() } catch (e: Exception) {}
                    return@withContext
                }

                val firstLine = requestText.split("\r\n").firstOrNull() ?: ""
                val parts = firstLine.split(" ")
                if (parts.size < 2) {
                    try { client.close() } catch (e: Exception) {}
                    return@withContext
                }

                val method = parts[0].uppercase()
                val targetText = parts[1]

                var remoteHost = ""
                var remotePort = 80

                val upstream: Socket
                if (method == "CONNECT") {
                    // SOCKS/HTTP Tunneling CONNECT target.com:443
                    val targetParts = targetText.split(":")
                    remoteHost = targetParts[0]
                    remotePort = targetParts.getOrNull(1)?.toIntOrNull() ?: 443

                    onLog("Proxy connected [HTTPS]: $remoteHost:$remotePort")
                    try {
                        upstream = Socket(remoteHost, remotePort).apply {
                            soTimeout = 60000
                        }
                    } catch (e: Exception) {
                        onLog("Proxy connection error to $remoteHost:$remotePort - ${e.message}")
                        try {
                            writer.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                            writer.flush()
                        } catch (ex: Exception) {}
                        try { client.close() } catch (ex: Exception) {}
                        return@withContext
                    }

                    // Respond 200 Connection established
                    writer.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                    writer.flush()
                } else {
                    // Plain HTTP GET/POST/etc
                    val hostLine = requestText.split("\r\n").find { it.startsWith("Host:", ignoreCase = true) }
                    if (hostLine != null) {
                        val value = hostLine.substring(5).trim()
                        if (value.contains(":")) {
                            val hostParts = value.split(":")
                            remoteHost = hostParts[0]
                            remotePort = hostParts.getOrNull(1)?.toIntOrNull() ?: 80
                        } else {
                            remoteHost = value
                            remotePort = 80
                        }
                    } else {
                        val cleanUrl = if (targetText.startsWith("http://", ignoreCase = true)) {
                            targetText.substring(7)
                        } else targetText
                        val hostPart = cleanUrl.split("/").firstOrNull() ?: ""
                        if (hostPart.contains(":")) {
                            val hostParts = hostPart.split(":")
                            remoteHost = hostParts[0]
                            remotePort = hostParts.getOrNull(1)?.toIntOrNull() ?: 80
                        } else {
                            remoteHost = hostPart
                            remotePort = 80
                        }
                    }

                    if (remoteHost.isEmpty()) {
                        try { client.close() } catch (e: Exception) {}
                        return@withContext
                    }

                    onLog("Proxy connected [HTTP]: $method $remoteHost:$remotePort")
                    try {
                        upstream = Socket(remoteHost, remotePort).apply {
                            soTimeout = 60000
                        }
                    } catch (e: Exception) {
                        onLog("Proxy connection error to $remoteHost:$remotePort - ${e.message}")
                        try { client.close() } catch (ex: Exception) {}
                        return@withContext
                    }

                    // Forward client request
                    val requestBytes = requestText.toByteArray(Charsets.US_ASCII)
                    upstream.getOutputStream().write(requestBytes)
                    upstream.getOutputStream().flush()
                }

                // Bridge sockets
                try {
                    client.soTimeout = 0
                    upstream.soTimeout = 0

                    val upstreamReader = upstream.getInputStream()
                    val upstreamWriter = upstream.getOutputStream()

                    val job1 = launch {
                        pipeData(reader, upstreamWriter)
                    }
                    val job2 = launch {
                        pipeData(upstreamReader, writer)
                    }
                    joinAll(job1, job2)
                } finally {
                    try { upstream.close() } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                // Ignore socket close exceptions
            } finally {
                try { client.close() } catch (e: Exception) {}
            }
        }
    }

    private fun pipeData(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32768)
        try {
            while (true) {
                val r = input.read(buffer)
                if (r <= 0) break
                output.write(buffer, 0, r)
                output.flush()
                onBytesTransferred(r.toLong())
            }
        } catch (e: Exception) {
            // Socket closed/timeout
        } finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }
}
