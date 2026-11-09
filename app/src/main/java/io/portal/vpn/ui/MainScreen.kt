package io.portal.vpn.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.portal.vpn.AppInterfaceMode
import io.portal.vpn.AppSettings
import io.portal.vpn.HapticFeedbackManager
import io.portal.vpn.IpCheckResult
import io.portal.vpn.PingTargetService
import io.portal.vpn.ServiceCheckResult
import io.portal.vpn.ServiceRadarManager
import io.portal.vpn.TrafficStatsData
import io.portal.vpn.UpdateInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isConnected: Boolean,
    selectedRoute: String,
    availableRoutes: List<String>,
    pingsMap: Map<String, Int>,
    isPinging: Boolean,
    selectedPingTarget: PingTargetService,
    trafficData: TrafficStatsData,
    speedHistory: List<Float>,
    ipCheckResult: IpCheckResult,
    settings: AppSettings,
    interfaceMode: AppInterfaceMode = AppInterfaceMode.SINGLE_PORTAL,
    showRouteBottomSheet: Boolean,
    radarResults: Map<String, ServiceCheckResult>,
    isRadarScanning: Boolean,
    onToggleConnection: () -> Unit,
    onSelectRoute: (String) -> Unit,
    onImportSubscription: (String) -> Unit,
    onOpenQrScanner: () -> Unit,
    onDismissBottomSheet: () -> Unit,
    onOpenBottomSheet: () -> Unit,
    onPingAllServers: (PingTargetService) -> Unit,
    onRefreshIpCheck: () -> Unit,
    onSettingsChanged: () -> Unit,
    onOpenSplitTunneling: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onStartRadarScan: () -> Unit,
    speedTestResult: ServiceRadarManager.SpeedTestResult? = null,
    isTestingSpeed: Boolean = false,
    speedProgressMbps: Double = 0.0,
    onStartSpeedTest: () -> Unit = {},
    updateInfo: UpdateInfo? = null,
    isDownloadingUpdate: Boolean = false,
    updateDownloadProgress: Int = 0,
    currentVersion: String = "1.0.5",
    isCheckingUpdate: Boolean = false,
    onCheckUpdate: () -> Unit = {},
    onStartUpdateDownload: () -> Unit = {},
    onDismissUpdateDialog: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isSinglePortalMode = (interfaceMode == AppInterfaceMode.SINGLE_PORTAL)
    var selectedTab by remember { mutableStateOf(NavigationTab.HOME) }
    var showAddPortalDialog by remember { mutableStateOf(false) }
    var showSingleModeSettingsSheet by remember { mutableStateOf(false) }

    if (updateInfo != null && updateInfo.hasUpdate) {
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingUpdate) onDismissUpdateDialog()
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🚀",
                        fontSize = 20.sp
                    )
                    Column {
                        Text(
                            text = "Обновление Portal VPN",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Версия v${updateInfo.latestVersion} • ${String.format(java.util.Locale.US, "%.1f", updateInfo.apkSizeMb)} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isDownloadingUpdate) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (updateDownloadProgress >= 100) "Запуск установщика..." else "Скачивание обновления...",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "$updateDownloadProgress%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { (updateDownloadProgress.coerceIn(0, 100)) / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                                val downloadedMb = (updateInfo.apkSizeMb * updateDownloadProgress / 100.0)
                                Text(
                                    text = String.format(java.util.Locale.US, "Загружено: %.1f MB из %.1f MB", downloadedMb, updateInfo.apkSizeMb),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    Text(
                        text = "Что нового:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp, max = 220.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        ) {
                            Text(
                                text = updateInfo.changelog.ifBlank { "Улучшения стабильности и скорости работы." },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onStartUpdateDownload,
                    enabled = !isDownloadingUpdate
                ) {
                    if (isDownloadingUpdate) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Text(if (updateDownloadProgress >= 100) "Установка..." else "Загрузка ($updateDownloadProgress%)...")
                        }
                    } else {
                        Text("Установить")
                    }
                }
            },
            dismissButton = {
                if (!isDownloadingUpdate) {
                    TextButton(onClick = onDismissUpdateDialog) {
                        Text("Позже")
                    }
                }
            }
        )
    }

    if (showAddPortalDialog) {
        AddPortalDialog(
            initialUrl = settings.lastSubscriptionUrl,
            onDismiss = { showAddPortalDialog = false },
            onImport = { url ->
                onImportSubscription(url)
            },
            onOpenQrScanner = {
                showAddPortalDialog = false
                onOpenQrScanner()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "PORTAL",
                                fontWeight = FontWeight.Black,
                                fontSize = 19.sp,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "VPN",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = "Dual-Core • Квантовый туннель",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                },
                actions = {
                    if (isSinglePortalMode) {
                        IconButton(onClick = { showSingleModeSettingsSheet = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Настройки",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isConnected) Color(0xFF00F5A0).copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                1.dp,
                                if (isConnected) Color(0xFF00F5A0).copy(alpha = 0.5f)
                                else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) Color(0xFF00F5A0) else Color(0xFFFF4B6E))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isConnected) "В ПОРТАЛЕ" else "ОФФЛАЙН",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) Color(0xFF00F5A0) else Color.Gray
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (!isSinglePortalMode) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationTab.entries.forEach { tab ->
                        val isSelected = (tab == selectedTab)
                        val icon = when (tab) {
                            NavigationTab.HOME -> Icons.Default.Home
                            NavigationTab.PORTALS -> Icons.Default.Public
                            NavigationTab.RADAR -> Icons.Default.Radar
                            NavigationTab.SETTINGS -> Icons.Default.Settings
                        }
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            icon = { Icon(icon, contentDescription = tab.title) },
                            label = { Text(tab.title, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                unselectedIconColor = Color.Gray,
                                unselectedTextColor = Color.Gray
                            )
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (if (isSinglePortalMode) NavigationTab.HOME else selectedTab) {
                NavigationTab.HOME -> {
                    HomeTabContent(
                        isConnected = isConnected,
                        selectedRoute = selectedRoute,
                        availableRoutes = availableRoutes,
                        pingsMap = pingsMap,
                        selectedPingTarget = selectedPingTarget,
                        trafficData = trafficData,
                        speedHistory = speedHistory,
                        isSinglePortalMode = isSinglePortalMode,
                        settings = settings,
                        onSettingsChanged = onSettingsChanged,
                        onToggleConnection = {
                            if (availableRoutes.isEmpty()) {
                                showAddPortalDialog = true
                            } else {
                                onToggleConnection()
                            }
                        },
                        onOpenBottomSheet = onOpenBottomSheet,
                        onImportSubscription = onImportSubscription,
                        onOpenQrScanner = onOpenQrScanner
                    )
                }
                NavigationTab.PORTALS -> {
                    ServersScreen(
                        selectedRoute = selectedRoute,
                        availableRoutes = availableRoutes,
                        pingsMap = pingsMap,
                        isPinging = isPinging,
                        settings = settings,
                        selectedPingTarget = selectedPingTarget,
                        onSelectRoute = onSelectRoute,
                        onPingAll = onPingAllServers,
                        onToggleFavorite = onToggleFavorite,
                        onAddPortalClick = { showAddPortalDialog = true }
                    )
                }
                NavigationTab.RADAR -> {
                    RadarScreen(
                        isConnected = isConnected,
                        radarResults = radarResults,
                        isScanning = isRadarScanning,
                        onStartScan = onStartRadarScan,
                        speedTestResult = speedTestResult,
                        isTestingSpeed = isTestingSpeed,
                        speedProgressMbps = speedProgressMbps,
                        onStartSpeedTest = onStartSpeedTest
                    )
                }
                NavigationTab.SETTINGS -> {
                    SettingsScreen(
                        settings = settings,
                        onSettingsChanged = onSettingsChanged,
                        onOpenSplitTunneling = onOpenSplitTunneling,
                        onRefreshSubscription = { onImportSubscription(settings.lastSubscriptionUrl) },
                        onOpenQrScanner = onOpenQrScanner,
                        currentVersion = currentVersion,
                        isCheckingUpdate = isCheckingUpdate,
                        onCheckUpdate = onCheckUpdate
                    )
                }
            }

            if (showRouteBottomSheet) {
                val sheetState = rememberModalBottomSheetState()
                var sheetSortByPing by remember { mutableStateOf(false) }

                ModalBottomSheet(
                    onDismissRequest = onDismissBottomSheet,
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Точка телепортации",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Пинг до приложения сквозь портал:",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            IconButton(
                                onClick = {
                                    onDismissBottomSheet()
                                    showAddPortalDialog = true
                                }
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Добавить портал",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Target Service Chips row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PingTargetService.entries.forEach { service ->
                                val isSelected = (service == selectedPingTarget)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable { onPingAllServers(service) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = service.iconEmoji, fontSize = 13.sp)
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = service.title,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Sort switch & Refresh ping button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "По пингу",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Switch(
                                    checked = sheetSortByPing,
                                    onCheckedChange = { sheetSortByPing = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                                )
                            }

                            TextButton(
                                onClick = { onPingAllServers(selectedPingTarget) },
                                enabled = !isPinging && availableRoutes.isNotEmpty()
                            ) {
                                if (isPinging) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Тест (${selectedPingTarget.title})",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (availableRoutes.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Список порталов пуст", color = Color.Gray)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        onDismissBottomSheet()
                                        showAddPortalDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Добавить портал", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            val routesSnapshot = availableRoutes.toList()
                            val pingsSnapshot = pingsMap.toMap()
                            val sortedRoutes = remember(routesSnapshot, sheetSortByPing, pingsSnapshot) {
                                if (sheetSortByPing) {
                                    routesSnapshot.sortedWith(
                                        compareBy {
                                            val p = pingsSnapshot[it] ?: 9999
                                            if (p <= 0) 9999 else p
                                        }
                                    )
                                } else {
                                    routesSnapshot
                                }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 350.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(sortedRoutes) { route ->
                                    val isSelected = (route == selectedRoute)
                                    val ping = pingsSnapshot[route] ?: -1
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                HapticFeedbackManager.nodeSelected(context)
                                                onSelectRoute(route)
                                                onDismissBottomSheet()
                                            }
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                else Color.Transparent
                                            )
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = route,
                                            fontSize = 15.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        PingBadge(pingMs = ping)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    onDismissBottomSheet()
                                    showAddPortalDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Добавить портал / сменить подписку", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }

            if (isSinglePortalMode && showSingleModeSettingsSheet) {
                val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showSingleModeSettingsSheet = false },
                    sheetState = settingsSheetState,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.9f)
                    ) {
                        SettingsScreen(
                            settings = settings,
                            onSettingsChanged = onSettingsChanged,
                            onOpenSplitTunneling = onOpenSplitTunneling,
                            onRefreshSubscription = { onImportSubscription(settings.lastSubscriptionUrl) },
                            onOpenQrScanner = onOpenQrScanner,
                            currentVersion = currentVersion,
                            isCheckingUpdate = isCheckingUpdate,
                            onCheckUpdate = onCheckUpdate
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated Sci-Fi Portal Gateway Button.
 * Replaces the conventional power button with an authentic, living Dimensional Portal.
 * Clean, high-contrast, theme-aware and visually captivating on both dark and light modes.
 */
@Composable
fun PortalGatewayButton(
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "portalVortex")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnim"
    )

    val ringRotationCw by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cwRotation"
    )

    val ringRotationCcw by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ccwRotation"
    )

    val coreGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val isDark = MaterialTheme.colorScheme.surface == Color(0xFF10141F)
    val connectedGreen = Color(0xFF00F5A0)
    val connectedCyan = Color(0xFF00E5FF)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = if (isConnected) connectedCyan else Color(0xFFA855F7)
    val auraColor = if (isConnected) connectedGreen else primaryColor
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(
        modifier = modifier
            .size(204.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                HapticFeedbackManager.portalClick(context)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        // 1. Outermost Hyperspace Aura (Pulsing Glow)
        Box(
            modifier = Modifier
                .size((196 * (if (isConnected) pulseScale else 1f)).dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            auraColor.copy(alpha = if (isConnected) (if (isDark) 0.35f else 0.22f) else (if (isDark) 0.18f else 0.12f)),
                            secondaryColor.copy(alpha = if (isConnected) 0.14f else 0.05f),
                            Color.Transparent
                        )
                    )
                )
        )

        // 2. Outer Stargate Ring (Clockwise Rotation)
        Box(
            modifier = Modifier
                .size(174.dp)
                .rotate(ringRotationCw)
                .border(
                    width = 2.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            if (isConnected) connectedGreen else primaryColor,
                            secondaryColor.copy(alpha = 0.25f),
                            if (isConnected) connectedCyan else primaryColor.copy(alpha = 0.85f),
                            secondaryColor.copy(alpha = 0.25f),
                            if (isConnected) connectedGreen else primaryColor
                        )
                    ),
                    shape = CircleShape
                )
        )

        // 3. Middle Quantum Acceleration Ring (Counter-Clockwise Rotation)
        Box(
            modifier = Modifier
                .size(152.dp)
                .rotate(ringRotationCcw)
                .border(
                    width = 1.5.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            secondaryColor.copy(alpha = 0.85f),
                            Color.Transparent,
                            (if (isConnected) connectedGreen else primaryColor).copy(alpha = 0.85f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // 4. Central Event Horizon (Living Portal Core)
        val coreGradient = if (isConnected) {
            Brush.radialGradient(
                listOf(
                    Color(0xFFE0FFF4),
                    Color(0xFF00F5A0),
                    Color(0xFF00C994),
                    Color(0xFF00897B),
                    Color(0xFF004D40)
                )
            )
        } else {
            if (isDark) {
                Brush.radialGradient(
                    listOf(
                        primaryColor.copy(alpha = 0.35f * coreGlowAlpha),
                        Color(0xFF1E2438),
                        Color(0xFF141926),
                        Color(0xFF0E121C)
                    )
                )
            } else {
                Brush.radialGradient(
                    listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFFF8FAFC),
                        Color(0xFFE2E8F0),
                        primaryColor.copy(alpha = 0.15f)
                    )
                )
            }
        }

        val coreBorder = if (isConnected) {
            Brush.sweepGradient(
                listOf(Color.White, connectedGreen, connectedCyan, Color.White)
            )
        } else {
            Brush.sweepGradient(
                listOf(primaryColor, primaryColor.copy(alpha = 0.4f), secondaryColor, primaryColor)
            )
        }

        Box(
            modifier = Modifier
                .size(128.dp)
                .shadow(
                    elevation = if (isConnected) 20.dp else 10.dp,
                    shape = CircleShape,
                    spotColor = auraColor
                )
                .clip(CircleShape)
                .background(coreGradient)
                .border(
                    width = 2.dp,
                    brush = coreBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) Color.Black.copy(alpha = 0.16f)
                            else primaryColor.copy(alpha = if (isDark) 0.18f else 0.12f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isConnected) {
                        Text(
                            text = "🌀",
                            fontSize = 28.sp,
                            modifier = Modifier.rotate(ringRotationCw * 2.2f)
                        )
                    } else {
                        Text(
                            text = "⚛️",
                            fontSize = 26.sp,
                            modifier = Modifier.rotate(ringRotationCw * 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (isConnected) "В ПОРТАЛЕ" else "ВОЙТИ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = if (isConnected) {
                        Color.White
                    } else {
                        if (isDark) Color.White else Color(0xFF0F172A)
                    }
                )

                Text(
                    text = if (isConnected) "ТУННЕЛЬ 100%" else "В ПОРТАЛ",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    color = if (isConnected) {
                        Color(0xFFE0F7FA)
                    } else {
                        primaryColor
                    }
                )
            }
        }
    }
}

@Composable
fun HomeTabContent(
    isConnected: Boolean,
    selectedRoute: String,
    availableRoutes: List<String>,
    pingsMap: Map<String, Int>,
    selectedPingTarget: PingTargetService,
    trafficData: TrafficStatsData,
    speedHistory: List<Float>,
    isSinglePortalMode: Boolean = false,
    settings: AppSettings? = null,
    onSettingsChanged: (() -> Unit)? = null,
    onToggleConnection: () -> Unit,
    onOpenBottomSheet: () -> Unit,
    onImportSubscription: (String) -> Unit,
    onOpenQrScanner: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Sci-Fi Living Portal Gateway Button
        PortalGatewayButton(
            isConnected = isConnected,
            onClick = onToggleConnection,
            modifier = Modifier.padding(vertical = 10.dp)
        )

        // Sci-Fi Status Title
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val statusTitle = when {
                isConnected -> "ТЕЛЕПОРТИРОВАН"
                availableRoutes.isEmpty() -> "ПОРТАЛ НЕ НАСТРОЕН"
                else -> "В РЕАЛЬНОМ МИРЕ"
            }
            val statusSubtitle = when {
                isConnected -> "В портале • Квантовый туннель активен"
                availableRoutes.isEmpty() -> "Добавьте сеть порталов для квантового перехода"
                else -> "Нажмите на портал, чтобы войти в квантовый мир"
            }

            Text(
                text = statusTitle,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                fontSize = 19.sp,
                color = if (isConnected) Color(0xFF00F5A0) else if (availableRoutes.isEmpty()) Color(0xFFF59E0B) else Color(0xFF94A3B8)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusSubtitle,
                fontSize = 12.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }

        // Destination Portal Card OR Add Portal Onboarding Card
        if (availableRoutes.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Добавить портал",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Вставьте ссылку на подписку Portal Connect или узел (vless://, vmess://), чтобы активировать порталы.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    var inputSubUrl by remember { mutableStateOf("") }
                    val clipboardManager = LocalClipboardManager.current

                    OutlinedTextField(
                        value = inputSubUrl,
                        onValueChange = { inputSubUrl = it },
                        placeholder = { Text("https://portal-connect.io/...", color = Color.Gray, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val clip = clipboardManager.getText()?.text
                                        if (!clip.isNullOrBlank()) {
                                            inputSubUrl = clip.trim()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Вставить", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = onOpenQrScanner) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = "QR", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (inputSubUrl.isNotBlank()) {
                                onImportSubscription(inputSubUrl.trim())
                            }
                        },
                        enabled = inputSubUrl.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Активировать порталы", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 14.sp)
                    }
                }
            }
        } else {
            if (isSinglePortalMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "⚡️ АВТОПИЛОТ",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFF00E5FF)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isConnected) "Квантовый туннель • Скоростное соединение" else "Оптимальный узел • Авто-подбор",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isConnected) "В квантовом портале" else "Готов к погружению",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isConnected) Color(0xFF00F5A0) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val ping = pingsMap[selectedRoute] ?: -1
                            PingBadge(pingMs = ping)
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenBottomSheet() },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Точка телепортации",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• ${selectedPingTarget.iconEmoji} ${selectedPingTarget.title}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = selectedRoute.ifBlank { "Выберите портал" },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val ping = pingsMap[selectedRoute] ?: -1
                        PingBadge(pingMs = ping)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "Выбрать портал",
                            tint = Color.Gray
                        )
                    }
                }
            }
        }

        // Quantum Flow & Traffic Stats Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Входящий поток",
                            tint = Color(0xFF00F5A0),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Column {
                            Text(
                                text = trafficData.formattedDownloadSpeed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(text = "Входящий", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Upload,
                            contentDescription = "Исходящий поток",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Column {
                            Text(
                                text = trafficData.formattedUploadSpeed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(text = "Исходящий", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = trafficData.formattedTotalTraffic,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(text = "Сквозь портал", fontSize = 10.sp, color = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                SpeedChart(
                    speedHistory = speedHistory,
                    lineColor = if (isConnected) Color(0xFF00F5A0) else MaterialTheme.colorScheme.primary
                )
            }
        }

        // Quantum Shield Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) Color(0xFF00F5A0).copy(alpha = 0.15f)
                            else Color(0xFFFF4B6E).copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = "Квантовый щит",
                        tint = if (isConnected) Color(0xFF00F5A0) else Color(0xFFFF4B6E),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isConnected) "Квантовый щит активен" else "Защита не активна",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isConnected)
                            "Все сетевые соединения зашифрованы и изолированы внутри портала"
                        else
                            "Прямое соединение без шифрования. Войдите в портал для квантовой защиты",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun AddPortalDialog(
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    onOpenQrScanner: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var urlText by remember { mutableStateOf(initialUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Добавить портал", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column {
                Text(
                    text = "Вставьте ссылку на подписку Portal Connect или узел (vless://, vmess://, trojan://):",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    placeholder = { Text("https://portal-connect.io/...", fontSize = 13.sp, color = Color.Gray) },
                    singleLine = true,
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    urlText = clip.trim()
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Вставить", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = onOpenQrScanner) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "QR-сканер", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (urlText.isNotBlank()) {
                        onImport(urlText.trim())
                        onDismiss()
                    }
                },
                enabled = urlText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Подключить", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = Color.Gray)
            }
        }
    )
}
