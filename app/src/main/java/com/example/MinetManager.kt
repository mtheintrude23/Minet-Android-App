package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

enum class MiningStatus {
    STOPPED,
    DOWNLOADING,
    STARTING,
    RUNNING,
    ERROR
}

data class MiningStats(
    val email: String = "",
    val ip: String = "N/A",
    val remotePort: Int = 0,
    val serverAddr: String = "N/A",
    val heartbeatsOk: Int = 0,
    val heartbeatsError: Int = 0,
    val localProxyPort: Int = 8888,
    val proxyActive: Boolean = false,
    val tunnelActive: Boolean = false,
    val workerActive: Boolean = false,
    val frpcDownloadProgress: Float = 0f,
    val totalBytesTransferred: Long = 0,
    val errorDetail: String = ""
)

object MinetManager {
    private val _status = MutableStateFlow(MiningStatus.STOPPED)
    val status: StateFlow<MiningStatus> = _status.asStateFlow()

    private val _stats = MutableStateFlow(MiningStats())
    val stats: StateFlow<MiningStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val logList = CopyOnWriteArrayList<String>()
    private val logLock = Any()

    // Low cost atomic byte traffic accumulator to avoid UI redraw bottlenecks
    private val bytesCounter = AtomicLong(0)

    init {
        addLog("Hệ thống AFK Minet đã khởi tạo.")

        // Throttled traffic updater to keep Compose Main Thread safe
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.Default) {
            while (true) {
                try {
                    val accumulated = bytesCounter.getAndSet(0)
                    if (accumulated > 0) {
                        _stats.value = _stats.value.copy(
                            totalBytesTransferred = _stats.value.totalBytesTransferred + accumulated
                        )
                    }
                } catch (e: Exception) {}
                delay(500)
            }
        }
    }

    fun addBytes(bytes: Long) {
        bytesCounter.addAndGet(bytes)
    }

    fun setStatus(newStatus: MiningStatus) {
        _status.value = newStatus
    }

    fun updateStats(transform: (MiningStats) -> MiningStats) {
        _stats.value = transform(_stats.value)
    }

    fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$timestamp] $message"
        synchronized(logLock) {
            logList.add(formatted)
            while (logList.size > 200) {
                logList.removeAt(0)
            }
            _logs.value = logList.toList()
        }
    }

    fun clearLogs() {
        synchronized(logLock) {
            logList.clear()
            _logs.value = emptyList()
            addLog("Đã xóa nhật ký log.")
        }
    }
    
    fun resetStats() {
        _stats.value = MiningStats()
    }
}
