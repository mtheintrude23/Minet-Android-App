package com.example

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CyberBackground),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    MinetDashboardScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinetDashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Local persistence using SharedPreferences
    val prefs = remember { context.getSharedPreferences("minet_prefs", Context.MODE_PRIVATE) }
    var emailInput by remember { mutableStateOf(prefs.getString("saved_email", "") ?: "") }
    var proxyInput by remember { mutableStateOf(prefs.getString("saved_proxy", "") ?: "") }
    
    // Global mining states
    val status by MinetManager.status.collectAsStateWithLifecycle()
    val stats by MinetManager.stats.collectAsStateWithLifecycle()
    val logs by MinetManager.logs.collectAsStateWithLifecycle()
    
    // Notification permission launcher
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    
    val reqPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Đã cấp quyền thông báo!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Thiếu quyền thông báo, dịch vụ AFK có thể không hiển thị đúng.", Toast.LENGTH_LONG).show()
        }
    }

    // Trigger permission requests elegantly
    LaunchedEffect(status) {
        if (status == MiningStatus.STARTING && !hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reqPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Main layout
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(CyberBackground, Color(0xFF030712))
                )
            )
            .padding(horizontal = 16.dp)
    ) {
        // App Header
        AppHeader(status = status)
        
        // Scrollable Control & Details Section
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            // Setup & Input Card
            item {
                InputCard(
                    email = emailInput,
                    onEmailChange = { emailInput = it },
                    proxy = proxyInput,
                    onProxyChange = { proxyInput = it },
                    isEnabled = status == MiningStatus.STOPPED || status == MiningStatus.ERROR
                )
            }
            
            // Console Controls (START / STOP Button)
            item {
                ActionControlsCard(
                    status = status,
                    onStart = {
                        val cleanEmail = emailInput.trim()
                        if (cleanEmail.isEmpty()) {
                            Toast.makeText(context, "Vui lòng nhập Email Minet!", Toast.LENGTH_SHORT).show()
                        } else {
                            // Persist settings
                            prefs.edit()
                                .putString("saved_email", cleanEmail)
                                .putString("saved_proxy", proxyInput.trim())
                                .apply()
                            
                            // Start Foreground Service
                            MinetManager.resetStats()
                            val serviceIntent = Intent(context, MinetService::class.java).apply {
                                action = MinetService.ACTION_START
                                putExtra(MinetService.EXTRA_EMAIL, cleanEmail)
                                putExtra(MinetService.EXTRA_PROXY, proxyInput.trim())
                            }
                            ContextCompat.startForegroundService(context, serviceIntent)
                        }
                    },
                    onStop = {
                        // Stop Foreground Service
                        val serviceIntent = Intent(context, MinetService::class.java).apply {
                            action = MinetService.ACTION_STOP
                        }
                        context.startService(serviceIntent)
                    }
                )
            }

            // Stats Panel
            item {
                StatsGridCard(stats = stats, status = status)
            }

            // Realtime Terminal Logs
            item {
                TerminalLogsCard(
                    logs = logs,
                    onClearLogs = { MinetManager.clearLogs() },
                    onCopyLogs = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Minet AFK Logs", logs.joinToString("\n"))
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Đã sao chép log vào khay nhớ tạm!", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // AFK Optimization Guide
            item {
                AfkOptimizationGuideCard()
            }
        }
    }
}

@Composable
fun AppHeader(status: MiningStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
    val glowProgress by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BadgeGlow"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "AFK MINET",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                color = CyberPrimary
            )
            Text(
                text = "Hệ thống treo máy chia sẻ proxy v1.0",
                style = MaterialTheme.typography.labelSmall,
                color = CyberGray
            )
        }

        // Animated Health Badge
        val (stateColor, text) = when (status) {
            MiningStatus.RUNNING -> Pair(CyberPrimary, "ACTIVE")
            MiningStatus.STARTING -> Pair(CyberSecondary, "STARTING")
            MiningStatus.DOWNLOADING -> Pair(CyberSecondary, "DL FRPC")
            MiningStatus.ERROR -> Pair(CyberTertiary, "ERR")
            else -> Pair(CyberGray, "IDLE")
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(stateColor.copy(alpha = 0.15f))
                .border(
                    1.dp,
                    stateColor.copy(alpha = if (status == MiningStatus.RUNNING) glowProgress else 0.5f),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(stateColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = stateColor
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputCard(
    email: String,
    onEmailChange: (String) -> Unit,
    proxy: String,
    onProxyChange: (String) -> Unit,
    isEnabled: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyberCardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberSurface.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "1. CẤU HÌNH THÔNG TIN",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = CyberSecondary
            )

            // Email Field
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                enabled = isEnabled,
                label = { Text("Email tài khoản Minet.vn") },
                placeholder = { Text("Nhập Email đăng ký") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = "Email",
                        tint = if (isEnabled) CyberSecondary else CyberGray
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("email_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberPrimary,
                    unfocusedBorderColor = CyberCardBorder,
                    disabledBorderColor = CyberCardBorder.copy(alpha = 0.5f),
                    focusedLabelColor = CyberPrimary,
                    unfocusedLabelColor = CyberGray
                )
            )

            // Special user SOCKS5 proxy field (Mirroring `config.json`'s API proxy field)
            OutlinedTextField(
                value = proxy,
                onValueChange = onProxyChange,
                enabled = isEnabled,
                label = { Text("Proxy API kết nối (Tùy chọn)") },
                placeholder = { Text("Ví dụ: socks5://127.0.0.1:1080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Proxy API",
                        tint = if (isEnabled) CyberSecondary else CyberGray
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("proxy_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberPrimary,
                    unfocusedBorderColor = CyberCardBorder,
                    disabledBorderColor = CyberCardBorder.copy(alpha = 0.5f),
                    focusedLabelColor = CyberPrimary,
                    unfocusedLabelColor = CyberGray
                )
            )
            
            Text(
                text = "* Lưu ý: Proxy trên chỉ dùng để gửi các yêu cầu API (heartbeat/update-ip) vượt tường lửa nếu cần. Tunnel chính vẫn sẽ chạy qua FRPC.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = CyberGray
            )
        }
    }
}

@Composable
fun ActionControlsCard(
    status: MiningStatus,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyberCardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberSurface.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val isRunning = status == MiningStatus.RUNNING || status == MiningStatus.STARTING || status == MiningStatus.DOWNLOADING
            
            // START Button
            Button(
                onClick = onStart,
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberPrimary,
                    contentColor = Color.Black,
                    disabledContainerColor = CyberGray.copy(alpha = 0.2f),
                    disabledContentColor = CyberGray
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("start_button"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Start")
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "BẮT ĐẦU AFK",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // STOP Button
            Button(
                onClick = onStop,
                enabled = isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberTertiary,
                    contentColor = Color.Black,
                    disabledContainerColor = CyberGray.copy(alpha = 0.2f),
                    disabledContentColor = CyberGray
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("stop_button"),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Stop")
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "DỪNG LẠI",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun DownloadingProgressCard(progress: Float) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyberSecondary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberSurface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ĐANG TẢI FRPC BINARY...",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = CyberSecondary
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = CyberSecondary
                )
            }
            LinearProgressIndicator(
                progress = progress,
                color = CyberSecondary,
                trackColor = CyberGray.copy(alpha = 0.2f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
fun StatsGridCard(stats: MiningStats, status: MiningStatus) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyberCardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberSurface.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "2. THÔNG SỐ HOẠT ĐỘNG",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = CyberSecondary
            )

            // Stats items row 1
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatItem(
                    label = "IP Công cộng",
                    value = stats.ip,
                    icon = Icons.Default.Info,
                    color = CyberSecondary,
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "Cổng Đường Truyền",
                    value = if (stats.remotePort > 0) stats.remotePort.toString() else "Chờ bộ rơ-le...",
                    icon = Icons.Default.Share,
                    color = CyberPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            // Stats items row 2
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatItem(
                    label = "Rơ-le Nhịp Tim OK",
                    value = stats.heartbeatsOk.toString(),
                    icon = Icons.Default.Check,
                    color = CyberPrimary,
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "Rơ-le Nhịp Tim hỏng",
                    value = stats.heartbeatsError.toString(),
                    icon = Icons.Default.Warning,
                    color = if (stats.heartbeatsError > 0) CyberTertiary else CyberGray,
                    modifier = Modifier.weight(1f)
                )
            }

            // Connection state badges
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StateIndicatorBadge(label = "Proxy HTTP Local", active = stats.proxyActive, modifier = Modifier.weight(1f))
                StateIndicatorBadge(label = "FRPC Tunnel", active = stats.tunnelActive, modifier = Modifier.weight(1f))
                StateIndicatorBadge(label = "Worker Rơ-le", active = stats.workerActive, modifier = Modifier.weight(1f))
            }
            
            // Speed / Traffic info
            Divider(color = CyberCardBorder, thickness = 1.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Dữ liệu truyền dẫn:",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
                )
                val formatter = DecimalFormat("#.##")
                val formattedBytes = when {
                    stats.totalBytesTransferred >= 1024 * 1024 * 1024 -> "${formatter.format(stats.totalBytesTransferred / (1024.0 * 1024.0 * 1024.0))} GB"
                    stats.totalBytesTransferred >= 1024 * 1024 -> "${formatter.format(stats.totalBytesTransferred / (1024.0 * 1024.0))} MB"
                    stats.totalBytesTransferred >= 1024 -> "${formatter.format(stats.totalBytesTransferred / 1024.0)} KB"
                    else -> "${stats.totalBytesTransferred} Bytes"
                }
                Text(
                    text = formattedBytes,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    color = CyberPrimary
                )
            }
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CyberSurface)
            .border(1.dp, CyberCardBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(14.dp))
                Text(text = label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = CyberGray)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun StateIndicatorBadge(label: String, active: Boolean, modifier: Modifier = Modifier) {
    val activeColor = if (active) CyberPrimary else CyberGray.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(activeColor.copy(alpha = 0.1f))
            .border(1.dp, activeColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = if (active) CyberPrimary else CyberGray,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun TerminalLogsCard(
    logs: List<String>,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit
) {
    val lazyListState = rememberLazyListState()
    
    // Automatically scrolling to the last line on new logs without blocking UI threads
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            try {
                lazyListState.scrollToItem(logs.size - 1)
            } catch (e: Exception) {}
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .border(1.dp, CyberCardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CyberTerminalBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar for logger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberSurface)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = "Terminal", tint = CyberPrimary, modifier = Modifier.size(16.dp))
                    Text(
                        text = "CONSOLE MONITOR (MONOSPACE)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = CyberPrimary
                    )
                }

                // Log control buttons
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onCopyLogs, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Copy log", tint = CyberSecondary, modifier = Modifier.size(14.dp))
                    }
                    IconButton(onClick = onClearLogs, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear log", tint = CyberTertiary, modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Realtime logger text list
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Không có nhật ký log trống.",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = CyberGray
                    )
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = if (log.contains("LỖI", ignoreCase = true) || log.contains("fail", ignoreCase = true)) {
                                CyberTertiary
                            } else if (log.contains("thành công", ignoreCase = true) || log.contains("CONNECTED", ignoreCase = true) || log.contains("OK", ignoreCase = true)) {
                                CyberPrimary
                            } else {
                                CyberTerminalText
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AfkOptimizationGuideCard() {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyberCardBorder, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = CyberSurface.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Star, contentDescription = "Guide", tint = CyberPrimary, modifier = Modifier.size(16.dp))
                    Text(
                        text = "HƯỚNG DẪN AFK KHÔNG BỊ DISCONNECT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = CyberOnSurface
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = CyberGray,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Divider(color = CyberCardBorder)
                    
                    Text(
                        text = "Hệ thống Android có cơ chế quản lý pin cực kỳ nghiêm ngặt và sẽ giết sạch ứng dụng chạy ngầm sau 5-10 phút khi bạn tắt màn hình. Để tối ưu hóa treo 24/7:",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberOnSurface.copy(alpha = 0.8f)
                    )

                    GuidelineStep(
                        step = "1",
                        title = "BẬT CHẾ ĐỘ CHỐNG NGỦ (WAKE LOCK)",
                        desc = "Khi bắt đầu treo, hệ thống đã tự động kích hoạt WakeLock để CPU hoạt động liên tục."
                    )

                    GuidelineStep(
                        step = "2",
                        title = "MỞ KHÔNG HẠN CHẾ PIN CHO APP",
                        desc = "Truy cập Cài đặt hệ thống > Ứng dụng > Tìm ứng dụng 'AFK Minet' > Pin (Battery) > Thiết lập thành 'Không hạn chế' (Unrestricted) thay vì để 'Tối ưu hóa' (Optimized)."
                    )

                    GuidelineStep(
                        step = "3",
                        title = "CHO PHÉP HOẠT ĐỘNG KHÔNG GIỚI HẠN NỀN",
                        desc = "Đảm bảo quyền chạy dưới nền của ứng dụng được kích hoạt. Hãy khóa ứng dụng vào cửa sổ đa nhiệm để tránh bị bấm tắt nhầm."
                    )

                    GuidelineStep(
                        step = "4",
                        title = "TREO CẮM SẠC NƠI THOÁNG MÁT",
                        desc = "Lượng truyền tải data qua proxy làm việc liên tục có thể sinh nhiệt nhẹ. Hãy giữ điện thoại mát mẻ ở khu vực thông thoáng."
                    )
                }
            }
        }
    }
}

@Composable
fun GuidelineStep(step: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(CyberSecondary.copy(alpha = 0.15f))
                .border(1.dp, CyberSecondary.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = CyberSecondary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = CyberSecondary
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = CyberGray
            )
        }
    }
}
