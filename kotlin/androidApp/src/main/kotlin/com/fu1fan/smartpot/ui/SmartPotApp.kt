package com.fu1fan.smartpot.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fu1fan.smartpot.R
import com.fu1fan.smartpot.protocol.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Leaf = Color(0xFF407A52)
private val BrightLeaf = Color(0xFF2E9254)
private val SoftLeaf = Color(0xFFE5F0E4)
private val Sand = Color(0xFFF8F9F5)
private val Ink = Color(0xFF1E241F)
private val Muted = Color(0xFF777D78)
private val CardBorder = Color(0xFFE9ECE6)
private val Sky = Color(0xFF2D9CDB)
private val Sun = Color(0xFFFFB000)
private val Violet = Color(0xFF8B5CF6)

private data class DashboardMetrics(
    val growthDays: Int?,
    val healthPercent: Int?,
    val companionStars: Float,
    val dailyInteractions: Int,
    val dailyDialogCount: Int,
    val dailyTouchCount: Int,
    val dailyWaterCount: Int,
    val soilSuitability: Double,
    val lightSuitability: Double,
    val interactionSuitability: Double,
)

private data class HourlyTelemetryPoint(
    val hour: ZonedDateTime,
    val soilPercent: Float?,
    val lightLux: Float?,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SmartPotApp(viewModel: SmartPotViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Leaf, secondary = Color(0xFF7D9763), background = Sand, surface = Color.White)) {
        Scaffold(
            containerColor = Sand,
            topBar = {
                if (tab in 1..3) {
                    CenterAlignedTopAppBar(
                        title = { Text(listOf("", "养护", "陪伴", "控制")[tab], fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Sand),
                    )
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 2.dp) {
                    listOf("首页" to "⌂", "养护" to "♧", "陪伴" to "♡", "控制" to "◎").forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Text(item.second, fontSize = 22.sp, fontWeight = FontWeight.Bold) },
                            label = { Text(item.first) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BrightLeaf,
                                selectedTextColor = BrightLeaf,
                                indicatorColor = SoftLeaf,
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted,
                            ),
                        )
                    }
                }
            },
            snackbarHost = {
                state.error?.let { error -> Snackbar(action = { TextButton(onClick = viewModel::clearError) { Text("知道了") } }) { Text(error) } }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    state.loading && !state.potsLoaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    !state.potsLoaded -> ConnectionRetryScreen(state.error, viewModel::bootstrap)
                    state.pots.isEmpty() -> SetupScreen(state.species, viewModel::createPot, viewModel::redeemShare)
                    tab == 0 -> DashboardScreen(state, viewModel::updateSpecies)
                    tab == 1 -> CareScreen(state, viewModel::addCare, viewModel::saveDiary, viewModel::speakDiary, viewModel::refreshWeather)
                    tab == 2 -> CompanionScreen(
                        state,
                        viewModel::sendChat,
                        viewModel::addMemory,
                        viewModel::deleteMemory,
                        viewModel::selectChatDay,
                        viewModel::addSchedule,
                        viewModel::toggleSchedule,
                        viewModel::recordPomodoro,
                        viewModel::removePomodoro,
                    )
                    else -> ControlScreen(state, viewModel::control, viewModel::createShare, viewModel::redeemShare)
                }
            }
        }
    }
}

@Composable
private fun ConnectionRetryScreen(error: String?, retry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("暂时无法连接盆栽", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(error ?: "请检查网络后重试", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Button(onClick = retry) { Text("重新连接") }
    }
}

@Composable
private fun SetupScreen(species: List<PlantSpecies>, create: (String, String, String) -> Unit, redeem: (String, String) -> Unit) {
    var device by remember { mutableStateOf("smartpot-p4-001") }
    var name by remember { mutableStateOf("我的绿植") }
    var selected by remember { mutableStateOf(species.firstOrNull()?.id.orEmpty()) }
    var share by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("绑定你的盆栽", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("设备上线后也会自动创建默认档案。") }
        item { OutlinedTextField(device, { device = it }, label = { Text("设备 ID") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(name, { name = it }, label = { Text("盆栽昵称") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Text("植物品种", fontWeight = FontWeight.SemiBold)
            species.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { plant -> FilterChip(selected == plant.id, { selected = plant.id }, { Text(plant.chineseName) }) } } }
        }
        item { Button(onClick = { create(device, name, selected) }, enabled = selected.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("完成绑定") } }
        item { HorizontalDivider(); Text("或加入别人分享的盆栽", fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(share, { share = it }, label = { Text("6 位分享码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()); OutlinedButton(onClick = { redeem(share, "访客") }, modifier = Modifier.fillMaxWidth()) { Text("加入共享盆栽") } }
    }
}

@Composable
private fun SpeciesPickerDialog(
    species: List<PlantSpecies>,
    currentSpeciesId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filteredSpecies = remember(species, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) species else species.filter { plant ->
            plant.chineseName.contains(keyword, ignoreCase = true) ||
                plant.scientificName.contains(keyword, ignoreCase = true) ||
                plant.id.contains(keyword, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("修改植物品种") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索植物品种") },
                    placeholder = { Text("中文名、英文名") },
                    singleLine = true,
                )
                if (filteredSpecies.isEmpty()) {
                    Text("没有找到匹配的植物品种", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filteredSpecies, key = { it.id }) { plant ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(plant.id) }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(plant.chineseName, fontWeight = FontWeight.SemiBold)
                                    Text(plant.scientificName, fontSize = 12.sp, color = Color.Gray)
                                    Text(
                                        "湿度 ${plant.thresholds.soilMinPercent}-${plant.thresholds.soilMaxPercent}% · 光照 ${plant.thresholds.lightMinLux}-${plant.thresholds.lightMaxLux} lux",
                                        fontSize = 11.sp,
                                        color = Color.Gray,
                                    )
                                }
                                if (plant.id == currentSpeciesId) Text("当前", color = Leaf, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun DashboardScreen(state: SmartPotUiState, updateSpecies: (String) -> Unit) {
    val snap = state.snapshot
    val metrics = dashboardMetrics(state)
    var speciesDialog by rememberSaveable { mutableStateOf(false) }
    var healthDetailsVisible by rememberSaveable { mutableStateOf(false) }
    val pot = snap?.pot
    if (speciesDialog && pot != null) {
        SpeciesPickerDialog(
            species = state.species,
            currentSpeciesId = pot.species.id,
            onDismiss = { speciesDialog = false },
            onSelect = { id ->
                speciesDialog = false
                updateSpecies(id)
            },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Sand).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 18.dp),
    ) {
        item {
            DashboardHero(
                pot = pot,
                online = snap?.online == true,
                metrics = metrics,
                canEditSpecies = pot != null && state.species.isNotEmpty(),
                onEditSpecies = { speciesDialog = true },
            )
        }
        item {
            PlantHealthCard(
                metrics = metrics,
                online = snap?.online == true,
                thresholds = pot?.species?.thresholds,
                affinity = snap?.affinity,
                detailsVisible = healthDetailsVisible,
                onToggleDetails = { healthDetailsVisible = !healthDetailsVisible },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardMetricCard(
                    icon = "●",
                    iconColor = Sky,
                    title = "土壤湿度",
                    value = snap?.telemetry?.soilPercent?.toString() ?: "--",
                    unit = "%",
                    status = soilLabel(snap?.evaluated?.soilStatus),
                    modifier = Modifier.weight(1f),
                )
                DashboardMetricCard(
                    icon = "✹",
                    iconColor = Sun,
                    title = "环境光照",
                    value = snap?.telemetry?.lightLux?.let(::compactMetricValue) ?: "--",
                    unit = "lux",
                    status = lightLabel(snap?.evaluated?.lightStatus),
                    modifier = Modifier.weight(1f),
                )
                DashboardMetricCard(
                    icon = "✿",
                    iconColor = Violet,
                    title = "互动次数",
                    value = metrics.dailyInteractions.toString(),
                    unit = "次",
                    status = interactionStatus(metrics.dailyInteractions),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { CompanionScoreCard(metrics) }
        item { TelemetryTrendCard(state.telemetry, snap?.telemetry, pot?.timezone) }
        item {
            DashboardAdviceCard(
                listOfNotNull(
                    snap?.evaluated?.soilAdvice,
                    snap?.evaluated?.lightAdvice,
                    snap?.pot?.species?.knowledge,
                ),
            )
        }
        item { DashboardAttentionCard(snap) }
    }
}

@Composable
private fun DashboardHero(
    pot: PotProfile?,
    online: Boolean,
    metrics: DashboardMetrics,
    canEditSpecies: Boolean,
    onEditSpecies: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = canEditSpecies, onClick = onEditSpecies),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("你好，主人 🌿", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    pot?.let { "${it.species.chineseName} · ${it.species.scientificName}" } ?: "正在连接你的盆栽",
                    color = Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (online) "${pot?.displayName ?: "小麦"}今天也在等你哦~" else "设备离线，数据会在连接后自动更新",
                    color = if (online) Leaf else Color(0xFFB06A3C),
                    fontSize = 12.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(168.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                modifier = Modifier.width(142.dp).fillMaxHeight(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, CardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "成长第 ${metrics.growthDays?.toString() ?: "--"} 天",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text("我们一起的日子", fontSize = 12.sp, color = Muted)
                    Text(
                        metrics.growthDays?.toString() ?: "--",
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrightLeaf,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                PlantMascot(metrics.healthPercent, Modifier.fillMaxSize())
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).offset(x = 8.dp, y = 4.dp),
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 2.dp,
                ) {
                    Text("♥", color = Color(0xFFFF6B68), fontSize = 19.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
        }
    }
}

@Composable
private fun DashboardAvatar(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.size(30.dp), shape = CircleShape, color = color, border = BorderStroke(2.dp, Color.White)) {
        Box(contentAlignment = Alignment.Center) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink) }
    }
}

@Composable
private fun PlantMascot(@Suppress("UNUSED_PARAMETER") healthPercent: Int?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mascot = remember { loadPlantMascot(context) }
    Image(
        bitmap = mascot,
        contentDescription = "小麦植物形象",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

private fun loadPlantMascot(context: Context) =
    BitmapFactory.decodeResource(context.resources, R.drawable.plant_cat_reference)
        .copy(Bitmap.Config.ARGB_8888, true)
        .also(::clearConnectedDarkBackground)
        .asImageBitmap()

private fun clearConnectedDarkBackground(bitmap: Bitmap) {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    val queue = IntArray(width * height)
    var head = 0
    var tail = 0
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    fun isBackground(index: Int): Boolean {
        val color = pixels[index]
        return (color ushr 24) != 0 &&
            ((color ushr 16) and 0xff) <= 58 &&
            ((color ushr 8) and 0xff) <= 58 &&
            (color and 0xff) <= 58
    }

    fun enqueue(index: Int) {
        if (index !in pixels.indices || !isBackground(index)) return
        pixels[index] = 0
        queue[tail++] = index
    }

    for (x in 0 until width) {
        enqueue(x)
        enqueue((height - 1) * width + x)
    }
    for (y in 0 until height) {
        enqueue(y * width)
        enqueue(y * width + width - 1)
    }
    while (head < tail) {
        val index = queue[head++]
        val x = index % width
        if (x > 0) enqueue(index - 1)
        if (x + 1 < width) enqueue(index + 1)
        if (index >= width) enqueue(index - width)
        if (index + width < pixels.size) enqueue(index + width)
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
}

@Composable
private fun PlantHealthCard(
    metrics: DashboardMetrics,
    online: Boolean,
    thresholds: PlantThresholds?,
    affinity: AffinityState?,
    detailsVisible: Boolean,
    onToggleDetails: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("植物健康值", fontWeight = FontWeight.Bold, color = Ink)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HealthGauge(metrics.healthPercent, Modifier.size(118.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(healthStatus(metrics.healthPercent, online), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BrightLeaf)
                    Text(healthHint(metrics.healthPercent, online), color = Muted, fontSize = 12.sp)
                    TextButton(onClick = onToggleDetails, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                        Text(if (detailsVisible) "收起详情 ︿" else "健康详情 ›", fontSize = 12.sp)
                    }
                }
            }
            if (detailsVisible) {
                HorizontalDivider(color = CardBorder)
                Text("湿度 40% · 光照 40% · 互动 20%", fontSize = 11.sp, color = Muted)
                Text(
                    "湿度 ${suitabilityLabel(metrics.soilSuitability)} · 光照 ${suitabilityLabel(metrics.lightSuitability)} · 互动 ${suitabilityLabel(metrics.interactionSuitability)}",
                    fontSize = 11.sp,
                    color = Leaf,
                )
                thresholds?.let {
                    Text(
                        "适宜范围：湿度 ${it.soilMinPercent}-${it.soilMaxPercent}% · 光照 ${it.lightMinLux}-${it.lightMaxLux} lux",
                        fontSize = 11.sp,
                        color = Muted,
                    )
                }
                affinity?.let {
                    val normalized = PlantRules.normalizeAffinity(it)
                    Text("好感度 ${normalized.score}/${PlantRules.maxAffinityPoints} · ${affinityLabel(normalized.level)}", fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun HealthGauge(healthPercent: Int?, modifier: Modifier = Modifier) {
    val progress = (healthPercent ?: 0).coerceIn(0, 100) / 100f
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 11.dp.toPx()
            val gaugeRect = Rect(Offset(inset, inset), Size(size.width - inset * 2f, size.height - inset * 2f))
            drawArc(SoftLeaf, -215f, 250f, false, gaugeRect.topLeft, gaugeRect.size, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
            drawArc(BrightLeaf, -215f, 250f * progress, false, gaugeRect.topLeft, gaugeRect.size, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(healthPercent?.toString() ?: "--", fontSize = 31.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("/100", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DashboardMetricCard(
    icon: String,
    iconColor: Color,
    title: String,
    value: String,
    unit: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.height(110.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 11.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(icon, color = iconColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(title, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = if (value.length >= 6) 20.sp else 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(3.dp))
                Text(unit, fontSize = 11.sp, color = Ink, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(status, color = metricStatusColor(status), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun CompanionScoreCard(metrics: DashboardMetrics) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("主人陪伴评分", fontWeight = FontWeight.Bold, color = Ink)
                Text(starScoreText(metrics.companionStars), color = BrightLeaf, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            StarRating(metrics.companionStars)
            Text(
                "今日浇水 ${metrics.dailyWaterCount} 次 · 触摸 ${metrics.dailyTouchCount} 次 · 对话 ${metrics.dailyDialogCount} 次",
                fontSize = 11.sp,
                color = Muted,
            )
        }
    }
}

@Composable
private fun StarRating(stars: Float) {
    val filled = (stars + 0.5f).toInt().coerceIn(0, 5)
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(5) { index ->
            Text(if (index < filled) "★" else "☆", color = Sun, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TelemetryTrendCard(values: List<DeviceTelemetry>, latest: DeviceTelemetry?, timezone: String?) {
    val points = remember(values, latest, timezone) { hourlyTelemetryPoints(values, latest, timezone) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("最近趋势", fontWeight = FontWeight.Bold, color = Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                TrendLegend(Sky, "湿度")
                TrendLegend(Sun, "光照")
            }
            Canvas(Modifier.fillMaxWidth().height(118.dp)) {
                val topPadding = 8.dp.toPx()
                val bottomPadding = 7.dp.toPx()
                val chartHeight = size.height - topPadding - bottomPadding
                val maxLight = points.mapNotNull { it.lightLux }.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                repeat(3) { index ->
                    val y = topPadding + chartHeight * index / 2f
                    drawLine(Color(0xFFF0F1ED), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                fun drawSeries(color: Color, valueOf: (HourlyTelemetryPoint) -> Float?, max: Float) {
                    val path = Path()
                    var drawing = false
                    points.forEachIndexed { index, point ->
                        val value = valueOf(point)
                        if (value == null) {
                            drawing = false
                        } else {
                            val x = if (points.size == 1) size.width / 2f else size.width * index / (points.size - 1)
                            val y = topPadding + chartHeight * (1f - (value / max).coerceIn(0f, 1f))
                            if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                            drawing = true
                            drawCircle(Color.White, 4.5.dp.toPx(), Offset(x, y))
                            drawCircle(color, 3.dp.toPx(), Offset(x, y))
                        }
                    }
                    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
                drawSeries(Sky, { it.soilPercent }, 100f)
                drawSeries(Sun, { it.lightLux }, maxLight)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                points.forEach { point ->
                    Text(
                        point.hour.format(DateTimeFormatter.ofPattern("HH:00")),
                        color = Muted,
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.width(17.dp).height(8.dp)) {
            drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color, 2.5.dp.toPx(), Offset(size.width / 2f, size.height / 2f))
        }
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun DashboardAdviceCard(lines: List<String>) {
    DashboardTextCard(
        title = "位置与养护建议",
        lines = lines.filter(String::isNotBlank).distinct().take(3).ifEmpty { listOf("正在等待实时数据生成养护建议") },
        warning = false,
    )
}

@Composable
private fun DashboardAttentionCard(snapshot: PotSnapshot?) {
    val attentionLines = buildList {
        if (snapshot != null && !snapshot.online) add("设备当前离线，请检查网络连接")
        addAll(snapshot?.activeAlerts.orEmpty().map { it.message })
    }.distinct().take(4)
    DashboardTextCard(
        title = "需要关注",
        lines = attentionLines.ifEmpty { listOf("各项指标正常，继续保持今天的养护节奏") },
        warning = attentionLines.isNotEmpty(),
    )
}

@Composable
private fun DashboardTextCard(title: String, lines: List<String>, warning: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Ink)
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (warning) "△" else "•", color = if (warning) Color(0xFFFF5A5F) else Leaf, fontWeight = FontWeight.Bold)
                    Text(line, fontSize = 12.sp, color = Color(0xFF4D534E), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AdviceCard(title: String, lines: List<String>, color: Color = SoftLeaf) {
    Column(Modifier.fillMaxWidth().background(color, RoundedCornerShape(18.dp)).padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold); lines.forEach { Text("• $it", modifier = Modifier.padding(top = 5.dp)) } }
}

@Composable
private fun CareScreen(
    state: SmartPotUiState,
    addCare: (CareType, String) -> Unit,
    saveDiary: (String, String, List<String>, String?) -> Unit,
    speakDiary: (PlantDiary) -> Unit,
    refreshWeather: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestWeatherLocation(context, refreshWeather)
    }
    LaunchedEffect(state.selectedPotId) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestWeatherLocation(context, refreshWeather)
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    var note by rememberSaveable { mutableStateOf("") }
    var timelineExpanded by rememberSaveable { mutableStateOf(false) }
    var diariesExpanded by rememberSaveable { mutableStateOf(false) }
    var addRecordVisible by rememberSaveable { mutableStateOf(false) }
    var affinityImpactExpanded by rememberSaveable { mutableStateOf(false) }
    val careActions = listOf(CareType.WATER, CareType.FERTILIZE, CareType.PRUNE, CareType.REPOT, CareType.NEW_LEAF)
    val metrics = dashboardMetrics(state)
    LazyColumn(
        Modifier.fillMaxSize().background(Sand).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
    ) {
        item { CareAffinityHeader(state, metrics) }
        item {
            AffinityImpactCard(
                state = state,
                metrics = metrics,
                expanded = affinityImpactExpanded,
                onToggle = { affinityImpactExpanded = !affinityImpactExpanded },
            )
        }
        item {
            GrowthTimelineCard(
                state = state,
                expanded = timelineExpanded,
                onToggleExpanded = { timelineExpanded = !timelineExpanded },
                onAddRecord = { addRecordVisible = !addRecordVisible },
            )
        }
        if (addRecordVisible) {
            item {
                AddCareRecordCard(
                    note = note,
                    onNoteChange = { note = it },
                    actions = careActions,
                    onAdd = { type ->
                        addCare(type, note)
                        note = ""
                        addRecordVisible = false
                    },
                    onDismiss = { addRecordVisible = false },
                )
            }
        }
        item {
            CareDiarySection(
                state = state,
                expanded = diariesExpanded,
                onToggleExpanded = { diariesExpanded = !diariesExpanded },
                saveDiary = saveDiary,
                speakDiary = speakDiary,
            )
        }
        item { TodayEnvironmentCard(state) }
    }
}

@Composable
private fun CareAffinityHeader(state: SmartPotUiState, metrics: DashboardMetrics) {
    val affinity = PlantRules.normalizeAffinity(state.snapshot?.affinity ?: AffinityState())
    val level = affinityLevelNumber(affinity.score)
    val levelProgress = affinityLevelProgress(affinity.score)
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("好感度等级", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Lv. $level / 30", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("🌿", fontSize = 17.sp)
                }
                LinearProgressIndicator(
                    progress = { levelProgress },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    color = BrightLeaf,
                    trackColor = SoftLeaf,
                )
                Text(
                    if (level >= 30) "好感度已达到最高等级" else "距离下一级还需 ${affinityPointsToNextLevel(affinity.score)} 点好感度",
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
            PlantMascot(metrics.healthPercent, Modifier.padding(start = 10.dp).size(width = 105.dp, height = 112.dp))
        }
    }
}

@Composable
private fun AffinityImpactCard(
    state: SmartPotUiState,
    metrics: DashboardMetrics,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val snapshot = state.snapshot
    val today = LocalDate.now(zoneIdOf(snapshot?.pot?.timezone))
    val positive = buildList {
        metrics.healthPercent?.let { health ->
            val points = when (health) { in 85..100 -> 4; in 70..84 -> 2; else -> 0 }
            if (points > 0) add("植物健康值 $health：+$points")
        }
        if (metrics.dailyDialogCount > 0) add("有效对话 +${metrics.dailyDialogCount.coerceAtMost(5)}")
        if (metrics.dailyTouchCount > 0) add("有效触摸 +${metrics.dailyTouchCount.coerceAtMost(3)}")
        if (metrics.dailyWaterCount > 0) add("成功浇水 +3")
        if (state.careLogs.any { it.type == CareType.REPOT && isSameLocalDate(it.occurredAt, today, zoneIdOf(snapshot?.pot?.timezone)) }) add("换盆记录 +3")
        if (state.careLogs.any { it.type == CareType.NEW_LEAF && isSameLocalDate(it.occurredAt, today, zoneIdOf(snapshot?.pot?.timezone)) }) add("长出新叶 +2")
        val completedSchedules = state.schedule?.items.orEmpty().count { it.completed && it.completedAt?.let { at -> isSameLocalDate(at, today, zoneIdOf(snapshot?.pot?.timezone)) } == true }
        if (completedSchedules > 0) add("完成日程 +${completedSchedules.coerceAtMost(3)}")
        val pomodoros = state.careOverview?.focus?.pomodoroCount ?: 0
        if (pomodoros > 0) add("完成番茄钟 +${pomodoros.coerceAtMost(4)}")
        if (state.diaries.any { it.author == DiaryAuthor.USER && it.diaryDate == today.toString() }) add("写养护日记 +1")
    }
    val negative = buildList {
        when (snapshot?.evaluated?.soilStatus) {
            SoilStatus.TOO_DRY -> add("缺水 -2")
            SoilStatus.TOO_WET -> add("土壤过湿 -2")
            else -> Unit
        }
        metrics.healthPercent?.let { health ->
            val points = when (health) { in 30..49 -> -2; in 0..29 -> -5; else -> 0 }
            if (points < 0) add("植物健康值 $health：$points")
        }
        when (snapshot?.evaluated?.lightStatus) {
            LightStatus.DARK -> add("缺光 -2")
            LightStatus.TOO_STRONG -> add("强光 -1")
            else -> Unit
        }
        if (snapshot != null && !snapshot.online) add("设备离线 -1")
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("今日好感影响因素", fontWeight = FontWeight.Bold, color = Ink)
                Text(if (expanded) "︿" else "﹀", color = Leaf, fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                Text(
                    "加分：${positive.ifEmpty { listOf("暂无加分记录") }.joinToString(" · ")}",
                    fontSize = 12.sp,
                    color = BrightLeaf,
                )
                Text(
                    "扣分：${negative.ifEmpty { listOf("暂无扣分项") }.joinToString(" · ")}",
                    fontSize = 12.sp,
                    color = if (negative.isEmpty()) Muted else Color(0xFFD45A52),
                )
            }
        }
    }
}

@Composable
private fun AddCareRecordCard(
    note: String,
    onNoteChange: (String) -> Unit,
    actions: List<CareType>,
    onAdd: (CareType) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("添加养护记录", fontWeight = FontWeight.Bold, color = Ink)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            OutlinedTextField(
                value = note,
                onValueChange = onNoteChange,
                label = { Text("记录今天发生的事") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            actions.chunked(3).forEach { rowActions ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    rowActions.forEach { type ->
                        OutlinedButton(
                            onClick = { onAdd(type) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp),
                        ) {
                            Text("${careEmoji(type)} ${careLabel(type)}", fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    repeat(3 - rowActions.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun GrowthTimelineCard(
    state: SmartPotUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAddRecord: () -> Unit,
) {
    val events = growthTimeline(state)
    val visibleEvents = if (expanded) events else events.take(3)
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("成长时间轴", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                TextButton(onClick = onAddRecord, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("＋ 添加记录", fontSize = 12.sp) }
            }
            if (visibleEvents.isEmpty()) {
                Text("还没有成长记录", color = Muted, fontSize = 12.sp)
            } else {
                visibleEvents.forEachIndexed { index, event ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 70.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.width(32.dp).height(72.dp), contentAlignment = Alignment.TopCenter) {
                            if (index < visibleEvents.lastIndex) {
                                Box(Modifier.padding(top = 27.dp).width(2.dp).height(52.dp).background(CardBorder))
                            }
                            Text(event.emoji, fontSize = 18.sp)
                        }
                        Column(Modifier.weight(1f).padding(start = 5.dp, top = 1.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(event.date, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 13.sp)
                            Text(event.title, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 13.sp)
                            if (event.detail.isNotBlank()) {
                                Text(event.detail, fontSize = 11.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        CareEventThumbnail(event, Modifier.padding(start = 8.dp).size(width = 68.dp, height = 58.dp))
                    }
                }
            }
            if (events.size > 3) {
                HorizontalDivider(color = CardBorder)
                TextButton(onClick = onToggleExpanded, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) "收起记录 ︿" else "查看全部记录 ›", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CareEventThumbnail(event: GrowthTimelineEvent, modifier: Modifier = Modifier) {
    val background = when {
        event.title.contains("换盆") -> Color(0xFFFFE4CC)
        event.title.contains("新叶") -> Color(0xFFDCEFD5)
        event.title.contains("浇水") -> Color(0xFFD9EFF8)
        else -> Color(0xFFF1F4EB)
    }
    Box(modifier.background(background, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Text(event.emoji, fontSize = 27.sp)
    }
}

@Composable
private fun CareDiarySection(
    state: SmartPotUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    saveDiary: (String, String, List<String>, String?) -> Unit,
    speakDiary: (PlantDiary) -> Unit,
) {
    val diaries = state.diaries.sortedWith(compareByDescending<PlantDiary> { it.diaryDate }.thenByDescending { it.createdAt })
    val visibleDiaries = if (expanded) diaries else diaries.take(2)
    val zone = runCatching { ZoneId.of(state.snapshot?.pot?.timezone ?: "Asia/Shanghai") }
        .getOrDefault(ZoneId.of("Asia/Shanghai"))
    val today = LocalDate.now(zone).toString()
    val todayDiary = diaries.firstOrNull { it.diaryDate == today && it.author == DiaryAuthor.USER }
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var mood by rememberSaveable { mutableStateOf<String?>(null) }
    var imageDataUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && imageDataUrls.size < 3) {
            encodeDiaryPhoto(context, uri)?.let { encoded -> imageDataUrls = imageDataUrls + encoded }
        }
    }

    fun openEditor() {
        title = todayDiary?.title ?: "今天的小麦"
        content = todayDiary?.content ?: ""
        mood = todayDiary?.moodEmoji
        imageDataUrls = todayDiary?.imageDataUrls.orEmpty()
        editorVisible = true
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("养护日记", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                TextButton(
                    onClick = { if (editorVisible) editorVisible = false else openEditor() },
                    contentPadding = PaddingValues(horizontal = 5.dp),
                ) { Text(if (editorVisible) "取消" else "＋ 写日记", fontSize = 12.sp) }
            }
            if (editorVisible) {
                Surface(color = Color(0xFFF7F9F5), shape = RoundedCornerShape(7.dp), border = BorderStroke(1.dp, CardBorder)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(60) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("日记标题") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it.take(1000) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("记录今天和小麦的故事") },
                            minLines = 3,
                            maxLines = 6,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("😊", "🌱", "💧", "☀️", "🥰", "😴")) { emoji ->
                                FilterChip(
                                    selected = mood == emoji,
                                    onClick = { mood = emoji.takeUnless { mood == emoji } },
                                    label = { Text(emoji, fontSize = 17.sp) },
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("成长照片 ${imageDataUrls.size}/3", color = Muted, fontSize = 11.sp)
                            OutlinedButton(
                                onClick = { imagePicker.launch("image/*") },
                                enabled = imageDataUrls.size < 3,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) { Text("上传照片", fontSize = 11.sp) }
                        }
                        if (imageDataUrls.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(imageDataUrls) { imageDataUrl ->
                                    Box {
                                        DiaryPhoto(imageDataUrl, Modifier.size(76.dp))
                                        FilledIconButton(
                                            onClick = { imageDataUrls = imageDataUrls - imageDataUrl },
                                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f)),
                                        ) { Text("×", color = Color.White, fontSize = 14.sp) }
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = {
                                saveDiary(title.trim(), content.trim(), imageDataUrls, mood)
                                editorVisible = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = title.isNotBlank() && content.isNotBlank(),
                            shape = RoundedCornerShape(6.dp),
                        ) { Text(if (todayDiary == null) "保存日记" else "更新今日日记") }
                    }
                }
            }
            if (visibleDiaries.isEmpty()) {
                Text("今天还没有日记，写一篇记录小麦的变化吧。", color = Muted, fontSize = 12.sp)
            } else {
                visibleDiaries.forEachIndexed { index, diary ->
                    if (index > 0) HorizontalDivider(color = CardBorder)
                    CareDiaryEntry(
                        diary = diary,
                        weather = state.careOverview?.weather?.takeIf { it.date == diary.diaryDate },
                        onSpeak = { speakDiary(diary) },
                    )
                }
            }
            if (diaries.size > 2) {
                HorizontalDivider(color = CardBorder)
                TextButton(onClick = onToggleExpanded, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) "收起日记 ︿" else "查看全部日记 ›", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CareDiaryEntry(diary: PlantDiary, weather: CareWeather?, onSpeak: () -> Unit) {
    var expanded by rememberSaveable(diary.id) { mutableStateOf(false) }
    val displayContent = diaryDisplayContent(diary)
    val canExpand = displayContent.length > 90 || displayContent.count { it == '\n' } >= 3
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(diary.diaryDate, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(if (diary.author == DiaryAuthor.WHEAT) "小麦" else "用户", color = Leaf, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(weather?.condition ?: diary.title, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(diaryMoodEmoji(diary), fontSize = 16.sp)
            TextButton(onClick = onSpeak, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("▷ ESP朗读", fontSize = 11.sp)
            }
        }
        Text(
            displayContent,
            fontSize = 12.sp,
            color = Color(0xFF4D534E),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (canExpand) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "收起" else "展开全文", fontSize = 11.sp)
            }
        }
        if (diary.author == DiaryAuthor.USER && diary.imageDataUrls.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(diary.imageDataUrls) { imageDataUrl -> DiaryPhoto(imageDataUrl, Modifier.size(88.dp)) }
            }
        }
    }
}

@Composable
private fun TodayEnvironmentCard(state: SmartPotUiState) {
    val weather = state.careOverview?.weather
    val evaluated = state.snapshot?.evaluated
    val environmentStatus = when {
        evaluated?.soilStatus == SoilStatus.SUITABLE && evaluated.lightStatus == LightStatus.DIFFUSE -> "良好"
        state.snapshot?.online == false -> "离线"
        else -> "需留意"
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("今日天气", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("${weatherEmoji(weather?.condition)} ${weather?.condition ?: "等待数据"}", color = Leaf, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EnvironmentStat("温度", weather?.temperatureC?.let { "${it.roundToInt()}°C" } ?: "--", Modifier.weight(1f))
                VerticalDivider(Modifier.height(42.dp), color = CardBorder)
                EnvironmentStat("空气湿度", weather?.relativeHumidityPercent?.let { "$it%" } ?: "--", Modifier.weight(1f))
                VerticalDivider(Modifier.height(42.dp), color = CardBorder)
                EnvironmentStat("环境状态", environmentStatus, Modifier.weight(1f))
            }
            weather?.hint?.takeIf(String::isNotBlank)?.let { Text(it, color = Muted, fontSize = 11.sp) }
            if (weather?.source == "OPEN_METEO") Text("实时天气 · Open-Meteo", color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun EnvironmentStat(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Muted, fontSize = 10.sp)
        Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ControlScreen(
    state: SmartPotUiState,
    control: (DeviceControlRequest) -> Unit,
    createShare: () -> Unit,
    redeem: (String, String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var projectionMode by rememberSaveable { mutableStateOf<String?>(null) }
    var lightExpanded by rememberSaveable { mutableStateOf(true) }
    var shareExpanded by rememberSaveable { mutableStateOf(false) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var share by rememberSaveable { mutableStateOf("") }
    val reportedBrightness = state.snapshot?.deviceState?.brightnessPercent ?: 70
    val reportedVolume = state.snapshot?.deviceState?.volumePercent ?: 60
    var brightness by remember(reportedBrightness) { mutableFloatStateOf(reportedBrightness.toFloat()) }
    var volume by remember(reportedVolume) { mutableFloatStateOf(reportedVolume.toFloat()) }
    val lightStrip = state.snapshot?.deviceState?.lightStrip
    var manualMode by remember(lightStrip?.manualMode) { mutableStateOf(lightStrip?.manualMode ?: false) }
    var manualOn by remember(lightStrip?.manualOn, lightStrip?.on) { mutableStateOf(lightStrip?.manualOn ?: lightStrip?.on ?: false) }
    var offPeriodEnabled by remember(lightStrip?.offPeriodEnabled) { mutableStateOf(lightStrip?.offPeriodEnabled ?: false) }
    var offStartText by remember(lightStrip?.offStartMinute) { mutableStateOf(minuteOfDayText(lightStrip?.offStartMinute ?: 23 * 60)) }
    var offEndText by remember(lightStrip?.offEndMinute) { mutableStateOf(minuteOfDayText(lightStrip?.offEndMinute ?: 7 * 60)) }
    val offStartMinute = parseMinuteOfDay(offStartText)
    val offEndMinute = parseMinuteOfDay(offEndText)
    val offPeriodValid = offStartMinute != null && offEndMinute != null && offStartMinute != offEndMinute

    LazyColumn(
        Modifier.fillMaxSize().background(Sand).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
    ) {
        item { ControlDeviceStatusCard(state) }
        item {
            ControlProjectionCard(
                mode = projectionMode,
                onModeChange = { projectionMode = if (projectionMode == it) null else it },
                text = text,
                onTextChange = { text = it.take(96) },
                onSendText = {
                    control(DeviceControlRequest(DeviceCommandType.SHOW_CONTENT, text = text, durationSeconds = 2))
                    text = ""
                },
                onSendEmoji = { emojiId -> control(DeviceControlRequest(DeviceCommandType.SHOW_CONTENT, emojiId = emojiId, durationSeconds = 2)) },
            )
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, CardBorder),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { lightExpanded = !lightExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("植物补光", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text(if (lightExpanded) "⌃" else "⌄", color = Leaf, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "当前：${if (lightStrip?.on == true) "灯带开" else "灯带关"} · ${if (manualMode) "APP 手动控制" else "ESP 自动控制"} · 标准 ${lightStrip?.lightMinLux ?: state.snapshot?.pot?.species?.thresholds?.lightMinLux ?: "--"}-${lightStrip?.lightMaxLux ?: state.snapshot?.pot?.species?.thresholds?.lightMaxLux ?: "--"} lux",
                        color = Muted,
                        fontSize = 10.sp,
                    )
                    if (lightExpanded) {
                        Button(
                            onClick = {
                                val enableManualMode = !manualMode
                                manualMode = enableManualMode
                                control(
                                    DeviceControlRequest(
                                        DeviceCommandType.SET_LIGHT_STRIP_CONTROL,
                                        lightStripManualMode = enableManualMode,
                                        lightStripOn = manualOn.takeIf { enableManualMode },
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                        ) { Text(if (manualMode) "退出手动开关灯模式" else "进入手动开关灯模式", fontSize = 12.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                                Text("手动开关灯", fontWeight = FontWeight.SemiBold, color = Ink)
                                Text("仅在 APP 手动控制时生效", color = Muted, fontSize = 10.sp)
                        }
                        Switch(
                            checked = manualOn,
                            onCheckedChange = { checked ->
                                manualOn = checked
                                control(DeviceControlRequest(DeviceCommandType.SET_LIGHT_STRIP_CONTROL, lightStripManualMode = true, lightStripOn = checked))
                            },
                            enabled = manualMode,
                        )
                    }
                        HorizontalDivider(color = CardBorder)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                                Text("一直灭灯时间段", fontWeight = FontWeight.SemiBold, color = Ink)
                                Text("仅命中该时段时禁止开灯，时段外仍可手动开灯", color = Muted, fontSize = 10.sp)
                        }
                        Switch(checked = offPeriodEnabled, onCheckedChange = { offPeriodEnabled = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            offStartText,
                            { offStartText = it.take(5) },
                            label = { Text("开始 HH:mm") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                                enabled = offPeriodEnabled,
                        )
                        OutlinedTextField(
                            offEndText,
                            { offEndText = it.take(5) },
                            label = { Text("结束 HH:mm") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                                enabled = offPeriodEnabled,
                        )
                    }
                    Button(
                        onClick = {
                            control(
                                DeviceControlRequest(
                                    DeviceCommandType.SET_LIGHT_STRIP_CONTROL,
                                    lightStripOffPeriodEnabled = offPeriodEnabled,
                                    lightStripOffStartMinute = offStartMinute,
                                    lightStripOffEndMinute = offEndMinute,
                                ),
                            )
                        },
                        enabled = !offPeriodEnabled || offPeriodValid,
                        modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                        ) { Text("保存灭灯时间段", fontSize = 12.sp) }
                    }
                }
            }
        }
        item {
            ControlSliderCard(
                icon = "☀",
                title = "亮度调节",
                value = brightness,
                onValueChange = { brightness = it },
                onValueChangeFinished = { control(DeviceControlRequest(DeviceCommandType.SET_BRIGHTNESS, brightnessPercent = brightness.toInt())) },
            )
        }
        item {
            ControlSliderCard(
                icon = "♫",
                title = "音量调节",
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { control(DeviceControlRequest(DeviceCommandType.SET_VOLUME, volumePercent = volume.toInt())) },
            )
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, CardBorder),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { shareExpanded = !shareExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("👩🏻‍🤝‍👨🏻", fontSize = 18.sp)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text("双人共享", fontWeight = FontWeight.Bold, color = Ink)
                            Text("你和 ESP 一起照顾小麦", color = Muted, fontSize = 10.sp)
                        }
                        Text(if (shareExpanded) "⌃" else "›", color = Muted, fontSize = 18.sp)
                    }
                    if (shareExpanded) {
                        HorizontalDivider(color = CardBorder)
                        Button(onClick = createShare, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) { Text("生成临时分享码") }
                        state.shareCode?.let { Text("分享码 ${it.code}，有效至 ${it.expiresAt.take(16).replace('T', ' ')}", color = Leaf, fontWeight = FontWeight.SemiBold, fontSize = 11.sp) }
                        OutlinedTextField(share, { share = it.take(12) }, label = { Text("输入分享码") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedButton(onClick = { redeem(share, "共享伙伴") }, enabled = share.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("加入盆栽") }
                    }
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, CardBorder),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { settingsExpanded = !settingsExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("▦", fontSize = 20.sp, color = Ink)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text("更多设置", fontWeight = FontWeight.Bold, color = Ink)
                            Text("触摸互动、屏幕休眠、设备重启", color = Muted, fontSize = 10.sp)
                        }
                        Text(if (settingsExpanded) "⌃" else "›", color = Muted, fontSize = 18.sp)
                    }
                    if (settingsExpanded) {
                        HorizontalDivider(color = CardBorder)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Button(onClick = { control(DeviceControlRequest(DeviceCommandType.REMOTE_TOUCH)) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("隔空触摸", fontSize = 11.sp) }
                            OutlinedButton(onClick = { control(DeviceControlRequest(DeviceCommandType.SET_STANDBY, standby = true)) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("休眠屏幕", fontSize = 11.sp) }
                            OutlinedButton(onClick = { control(DeviceControlRequest(DeviceCommandType.RESTART)) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("重启设备", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        state.lastCommand?.let { command ->
            item {
                Text(
                    if (command.acknowledged) "设备已确认：${command.ack?.status}" else "命令已发送，等待设备确认",
                    color = if (command.acknowledged) Leaf else Color(0xFFA56A00),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ControlDeviceStatusCard(state: SmartPotUiState) {
    val snapshot = state.snapshot
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("设备状态", fontWeight = FontWeight.Bold, color = Ink)
                Text(if (snapshot?.online == true) "在线" else "离线", color = if (snapshot?.online == true) BrightLeaf else Color(0xFFE05252), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (snapshot?.online == true) "ESP 已连接" else "等待 ESP 连接", color = Muted, fontSize = 11.sp)
                Text("设备：${snapshot?.pot?.deviceId ?: "--"}", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlantMascot(dashboardMetrics(state).healthPercent, Modifier.size(width = 112.dp, height = 112.dp))
        }
    }
}

@Composable
private fun ControlProjectionCard(
    mode: String?,
    onModeChange: (String) -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    onSendText: () -> Unit,
    onSendEmoji: (String) -> Unit,
) {
    val emojis = listOf("heart", "smile", "happy", "thirsty", "dark", "weak", "wave", "star", "flower", "water", "sun", "sleep")
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("屏幕投送", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlActionTile("▤", "发送文字", "发送到 ESP 屏幕", mode == "text", Modifier.weight(1f)) { onModeChange("text") }
                ControlActionTile("☺", "发送表情", "发送表情动画", mode == "emoji", Modifier.weight(1f)) { onModeChange("emoji") }
            }
            if (mode == "text") {
                OutlinedTextField(text, onTextChange, placeholder = { Text("输入要投送的中文或英文短句", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = onSendText, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp)) { Text("投送到 ESP 屏幕") }
            }
            if (mode == "emoji") {
                emojis.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { emojiId ->
                            OutlinedButton(
                                onClick = { onSendEmoji(emojiId) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(4.dp),
                            ) {
                                Image(
                                    painter = painterResource(emojiStickerResource(emojiId)),
                                    contentDescription = "投送表情 $emojiId",
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlActionTile(icon: String, title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(72.dp).clickable(onClick = onClick),
        color = if (selected) SoftLeaf else Color(0xFFF7F8F5),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, if (selected) Leaf.copy(alpha = 0.35f) else CardBorder),
    ) {
        Row(Modifier.padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = Leaf, fontSize = 21.sp)
            Column(Modifier.padding(start = 9.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(subtitle, color = Muted, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ControlSliderCard(icon: String, title: String, value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(icon, color = Leaf, fontSize = 18.sp)
                Text(title, modifier = Modifier.padding(start = 9.dp).weight(1f), color = Ink, fontWeight = FontWeight.SemiBold)
                Text("${value.toInt()}%", color = Ink, fontSize = 12.sp)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = 0f..100f, onValueChangeFinished = onValueChangeFinished)
        }
    }
}

@Composable
private fun ScheduleTable(
    schedules: List<ScheduleItem>,
    toggleSchedule: (ScheduleItem, Boolean) -> Unit,
) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Instant.now()
        }
    }
    val rows = schedules.filter { item ->
        !item.completed || item.completedAt?.let { completedAt ->
            runCatching { Instant.parse(completedAt).plusSeconds(120).isAfter(now) }.getOrDefault(true)
        } != false
    }.sortedWith(compareBy<ScheduleItem> { it.completed }.thenBy { it.dueAt ?: it.displayTime }.thenBy { it.createdAt })

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD5DAD2)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFFF0F4EE)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("time", modifier = Modifier.width(112.dp), fontWeight = FontWeight.Bold, color = Color(0xFF3E5544))
                Text("task", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Color(0xFF3E5544))
            }
            HorizontalDivider()
            if (rows.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("", modifier = Modifier.width(112.dp))
                    Text("", modifier = Modifier.weight(1f))
                }
            }
            rows.forEach { item ->
                val color = if (item.completed) Color(0xFF9AA09B) else Color(0xFF222622)
                val decoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        scheduleTimeText(item),
                        modifier = Modifier.width(112.dp),
                        fontSize = 13.sp,
                        color = color,
                        textDecoration = decoration,
                    )
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.title,
                            modifier = Modifier.weight(1f),
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = decoration,
                        )
                        Checkbox(
                            checked = item.completed,
                            onCheckedChange = { checked -> toggleSchedule(item, checked) },
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CompanionScreen(
    state: SmartPotUiState,
    send: (String) -> Unit,
    addMemory: (String) -> Unit,
    deleteMemory: (UserMemory) -> Unit,
    selectDay: (String) -> Unit,
    addSchedule: (String, String) -> Unit,
    toggleSchedule: (ScheduleItem, Boolean) -> Unit,
    recordPomodoro: () -> Unit,
    removePomodoro: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var memory by rememberSaveable { mutableStateOf("") }
    var scheduleTitle by rememberSaveable { mutableStateOf("") }
    var scheduleTime by rememberSaveable { mutableStateOf("") }
    var scheduleFormVisible by rememberSaveable { mutableStateOf(false) }
    var chatExpanded by rememberSaveable { mutableStateOf(true) }
    var memoryExpanded by rememberSaveable { mutableStateOf(true) }
    var pendingMemoryDelete by remember { mutableStateOf<UserMemory?>(null) }
    val zone = runCatching { ZoneId.of(state.snapshot?.pot?.timezone ?: "Asia/Shanghai") }
        .getOrDefault(ZoneId.of("Asia/Shanghai"))
    val today = LocalDate.now(zone).toString()
    pendingMemoryDelete?.let { memoryToDelete ->
        AlertDialog(
            onDismissRequest = { pendingMemoryDelete = null },
            title = { Text("删除这条记忆？") },
            text = { Text(memoryToDelete.content) },
            confirmButton = {
                TextButton(onClick = {
                    deleteMemory(memoryToDelete)
                    pendingMemoryDelete = null
                }) { Text("删除", color = Color(0xFFD14343)) }
            },
            dismissButton = { TextButton(onClick = { pendingMemoryDelete = null }) { Text("取消") } },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Sand).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
    ) {
        item {
            CompanionChatCard(
                state = state,
                today = today,
                zone = zone,
                input = input,
                onInputChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        send(input)
                        input = ""
                    }
                },
                selectDay = selectDay,
                expanded = chatExpanded,
                onToggleExpanded = { chatExpanded = !chatExpanded },
            )
        }
        item {
            CompanionMemoryCard(
                memories = state.memories,
                input = memory,
                onInputChange = { memory = it },
                onRemember = {
                    if (memory.isNotBlank()) {
                        addMemory(memory)
                        memory = ""
                    }
                },
                onDelete = { pendingMemoryDelete = it },
                expanded = memoryExpanded,
                onToggleExpanded = { memoryExpanded = !memoryExpanded },
            )
        }
        item {
            CompanionScheduleCard(
                state = state,
                formVisible = scheduleFormVisible,
                onToggleForm = { scheduleFormVisible = !scheduleFormVisible },
                title = scheduleTitle,
                onTitleChange = { scheduleTitle = it.take(80) },
                time = scheduleTime,
                onTimeChange = { scheduleTime = it.take(40) },
                onAdd = {
                    addSchedule(scheduleTitle, scheduleTime)
                    scheduleTitle = ""
                    scheduleTime = ""
                    scheduleFormVisible = false
                },
                toggleSchedule = toggleSchedule,
            )
        }
        item { CompanionFocusCard(state, recordPomodoro, removePomodoro) }
    }
}

@Composable
private fun CompanionChatCard(
    state: SmartPotUiState,
    today: String,
    zone: ZoneId,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    selectDay: (String) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val messages = state.messages
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("和小麦聊聊天", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("手机和 ESP 的对话会统一保存在这里", color = Muted, fontSize = 11.sp)
                }
                Text(if (expanded) "⌃" else "⌄", color = Leaf, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(state.chatDays, key = ChatDaySummary::date) { day ->
                        FilterChip(
                            selected = state.selectedChatDate == day.date,
                            onClick = { selectDay(day.date) },
                            label = { Text(if (day.date == today) "今天" else day.date.takeLast(5), fontSize = 11.sp) },
                        )
                    }
                }
                if (messages.isEmpty()) {
                    Text("这一天还没有对话记录", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        messages.forEach { message -> CompanionChatBubble(message, zone) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text("输入你想说的话...", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                FilledIconButton(onClick = onSend, enabled = input.isNotBlank()) { Text("➤", fontSize = 17.sp) }
            }
        }
    }
}

@Composable
private fun CompanionChatBubble(message: ChatMessage, zone: ZoneId) {
    val fromUser = message.role == ChatRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (fromUser) BrightLeaf else Color(0xFFF7F8F5),
            shape = RoundedCornerShape(8.dp),
            border = if (fromUser) null else BorderStroke(1.dp, CardBorder),
        ) {
            Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp).widthIn(max = 270.dp)) {
                Text(message.content, color = if (fromUser) Color.White else Ink, fontSize = 12.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${chatSourceLabel(message)} · ${chatTimeText(message.createdAt, zone)}",
                    fontSize = 9.sp,
                    color = if (fromUser) Color.White.copy(alpha = 0.78f) else Muted,
                )
            }
        }
    }
}

@Composable
private fun CompanionMemoryCard(
    memories: List<UserMemory>,
    input: String,
    onInputChange: (String) -> Unit,
    onRemember: () -> Unit,
    onDelete: (UserMemory) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("专属记忆库", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("让小麦记住重要的事情", color = Muted, fontSize = 11.sp)
                }
                Text(if (expanded) "⌃" else "⌄", color = Leaf, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text("例如：生日、考试时间、加班安排...", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Button(onClick = onRemember, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("让小麦记住") }
                if (memories.isNotEmpty()) {
                    Text("已记住", color = Muted, fontSize = 11.sp)
                    memories.asReversed().forEach { item ->
                        Surface(color = Color(0xFFF3F6ED), shape = RoundedCornerShape(6.dp)) {
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.content, modifier = Modifier.weight(1f), fontSize = 11.sp, color = Color(0xFF4D534E), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { onDelete(item) }, contentPadding = PaddingValues(horizontal = 7.dp)) {
                                    Text("删除", color = Color(0xFFD14343), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionScheduleCard(
    state: SmartPotUiState,
    formVisible: Boolean,
    onToggleForm: () -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    time: String,
    onTimeChange: (String) -> Unit,
    onAdd: () -> Unit,
    toggleSchedule: (ScheduleItem, Boolean) -> Unit,
) {
    val items = state.schedule?.items.orEmpty()
    val timezone = state.snapshot?.pot?.timezone ?: "Asia/Shanghai"
    val timeValid = parseScheduleDueAtInput(time, timezone) != null
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("主人关心（日程）", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                TextButton(onClick = onToggleForm, contentPadding = PaddingValues(horizontal = 5.dp)) { Text(if (formVisible) "收起" else "＋ 添加日程", fontSize = 12.sp) }
            }
            if (formVisible) {
                OutlinedTextField(title, onTitleChange, label = { Text("任务名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    time,
                    onTimeChange,
                    label = { Text("提醒时间 MM-dd/HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = time.isNotBlank() && !timeValid,
                )
                Button(onClick = onAdd, enabled = title.isNotBlank() && timeValid, modifier = Modifier.fillMaxWidth()) { Text("添加并同步到 ESP") }
                HorizontalDivider(color = CardBorder)
            }
            ScheduleTable(items, toggleSchedule)
        }
    }
}

@Composable
private fun CompanionFocusCard(state: SmartPotUiState, recordPomodoro: () -> Unit, removePomodoro: () -> Unit) {
    val today = state.careOverview?.focus ?: state.focusDaily.lastOrNull()
    val count = today?.pomodoroCount ?: 0
    val minutes = today?.focusMinutes ?: 0
    val target = (today?.targetPomodoroCount ?: 4).coerceAtLeast(1)
    val completion = today?.scheduleCompletionPercent ?: 0
    var confirmDecrease by rememberSaveable { mutableStateOf(false) }
    if (confirmDecrease) {
        AlertDialog(
            onDismissRequest = { confirmDecrease = false },
            title = { Text("减少一个番茄钟？") },
            text = { Text("将删除今天最近一次记录的 25 分钟专注。") },
            confirmButton = {
                TextButton(onClick = { removePomodoro(); confirmDecrease = false }) { Text("确认减少", color = Color(0xFFD14343)) }
            },
            dismissButton = { TextButton(onClick = { confirmDecrease = false }) { Text("取消") } },
        )
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("今日番茄钟", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                Row {
                    TextButton(onClick = { confirmDecrease = true }, enabled = count > 0, contentPadding = PaddingValues(horizontal = 7.dp)) {
                        Text("－ 减少", fontSize = 12.sp)
                    }
                    TextButton(onClick = recordPomodoro, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("＋ 记录", fontSize = 12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("$count 个", color = BrightLeaf, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("$minutes min", color = BrightLeaf, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { (count.toFloat() / target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(7.dp),
                color = BrightLeaf,
                trackColor = SoftLeaf,
            )
            Text("目标 $target 个番茄钟", color = Muted, fontSize = 10.sp)
            HorizontalDivider(color = CardBorder)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("日程完成度", fontWeight = FontWeight.Bold, color = Ink)
                Text("$completion%", color = BrightLeaf, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            CompanionCompletionChart(state.focusDaily)
        }
    }
}

@Composable
private fun CompanionCompletionChart(values: List<DailyFocusSummary>) {
    val points = values.takeLast(7)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(105.dp)) {
            if (points.size < 2) return@Canvas
            repeat(3) { index ->
                val y = size.height * index / 2f
                drawLine(Color(0xFFF0F1ED), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val path = Path().apply {
                points.forEachIndexed { index, item ->
                    val x = size.width * index / (points.size - 1)
                    val y = size.height * (1f - item.scheduleCompletionPercent.coerceIn(0, 100) / 100f)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(path, Leaf, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            points.forEachIndexed { index, item ->
                val x = size.width * index / (points.size - 1)
                val y = size.height * (1f - item.scheduleCompletionPercent.coerceIn(0, 100) / 100f)
                drawCircle(Color.White, 4.dp.toPx(), Offset(x, y))
                drawCircle(Leaf, 2.7.dp.toPx(), Offset(x, y))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            points.forEach { Text(it.date.takeLast(5), color = Muted, fontSize = 9.sp) }
        }
    }
}

private fun chatSourceLabel(message: ChatMessage): String = when {
    message.role == ChatRole.USER && message.source == "ESP" -> "你 · ESP 语音"
    message.role == ChatRole.USER -> "你 · 手机"
    message.source == "ESP" -> "小麦 · ESP"
    else -> "小麦 · 手机"
}

private fun chatTimeText(createdAt: String, zone: ZoneId): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.parse(createdAt).atZone(zone))
}.getOrDefault("--:--")

private fun soilLabel(value: SoilStatus?) = when (value) { SoilStatus.TOO_DRY -> "缺水"; SoilStatus.SUITABLE -> "适宜"; SoilStatus.TOO_WET -> "积水风险"; else -> "等待数据" }
private fun lightLabel(value: LightStatus?) = when (value) { LightStatus.DARK -> "阴暗"; LightStatus.DIFFUSE -> "散射光"; LightStatus.TOO_STRONG -> "强光"; else -> "等待数据" }
private fun minuteOfDayText(value: Int): String = "%02d:%02d".format((value / 60).coerceIn(0, 23), (value % 60).coerceIn(0, 59))
private fun parseMinuteOfDay(value: String): Int? {
    val parts = value.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
private fun careLabel(value: CareType) = when (value) { CareType.WATER -> "浇水"; CareType.FERTILIZE -> "施肥"; CareType.PRUNE -> "修剪"; CareType.REPOT -> "换盆"; CareType.NEW_LEAF -> "新叶"; CareType.OTHER -> "其他" }
private fun careEmoji(value: CareType) = when (value) { CareType.WATER -> "💧"; CareType.FERTILIZE -> "🌿"; CareType.PRUNE -> "✂"; CareType.REPOT -> "🪴"; CareType.NEW_LEAF -> "🌱"; CareType.OTHER -> "✓" }
private fun affinityLabel(value: AffinityLevel) = when (value) {
    AffinityLevel.STRANGER -> "初次相识"
    AffinityLevel.FAMILIAR -> "渐渐熟悉"
    AffinityLevel.CLOSE -> "亲密伙伴"
    AffinityLevel.TRUSTED -> "默契朋友"
    AffinityLevel.BEST_FRIEND -> "最佳朋友"
    AffinityLevel.LONG_TERM_COMPANION -> "长久相伴"
    AffinityLevel.SOULMATE -> "心灵伙伴"
}
private fun affinityLevelNumber(score: Int): Int = PlantRules.affinityLevelNumber(score)
private fun affinityLevelProgress(score: Int): Float = PlantRules.affinityLevelProgress(score)
private fun affinityPointsToNextLevel(score: Int): Int = PlantRules.affinityPointsToNextLevel(score)

private fun emojiStickerResource(id: String): Int = when (id) {
    "heart" -> R.drawable.emoji_sticker_heart
    "happy" -> R.drawable.emoji_sticker_happy
    "thirsty" -> R.drawable.emoji_sticker_thirsty
    "dark" -> R.drawable.emoji_sticker_dark
    "weak" -> R.drawable.emoji_sticker_weak
    "wave" -> R.drawable.emoji_sticker_wave
    "star" -> R.drawable.emoji_sticker_star
    "flower" -> R.drawable.emoji_sticker_flower
    "water" -> R.drawable.emoji_sticker_water
    "sun" -> R.drawable.emoji_sticker_sun
    "sleep" -> R.drawable.emoji_sticker_sleep
    else -> R.drawable.emoji_sticker_smile
}

@Composable
private fun DiaryPhoto(dataUrl: String, modifier: Modifier = Modifier) {
    val bitmap = remember(dataUrl) { decodeDiaryPhoto(dataUrl) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "日记照片",
            modifier = modifier.background(Color(0xFFF2F4EF), RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

private fun encodeDiaryPhoto(context: Context, uri: Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 1_280 || bounds.outHeight / sampleSize > 1_280) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return@runCatching null
    val scale = minOf(1f, 1_280f / maxOf(decoded.width, decoded.height))
    val outputBitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
    } else decoded
    val bytes = ByteArrayOutputStream().use { output ->
        outputBitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        output.toByteArray()
    }
    if (outputBitmap !== decoded) outputBitmap.recycle()
    decoded.recycle()
    "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
}.getOrNull()

private fun decodeDiaryPhoto(dataUrl: String): Bitmap? = runCatching {
    val encoded = dataUrl.substringAfter(',', missingDelimiterValue = "")
    if (encoded.isBlank()) return@runCatching null
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

private fun weatherEmoji(condition: String?): String = when {
    condition == null -> "◌"
    condition.contains("雨") -> "🌧"
    condition.contains("云") || condition.contains("阴") -> "☁"
    condition.contains("晴") -> "☀"
    else -> "⛅"
}

private data class GrowthTimelineEvent(val date: String, val title: String, val detail: String, val emoji: String)

private fun growthTimeline(state: SmartPotUiState): List<GrowthTimelineEvent> {
    val pot = state.snapshot?.pot
    val created = pot?.createdAt?.take(10)?.let { GrowthTimelineEvent(it, "开始陪伴", pot.displayName, "🌱") }
    val logs = state.careLogs.sortedBy { it.occurredAt }.map { log ->
        val firstRepot = state.careLogs.filter { it.type == CareType.REPOT }.minByOrNull { it.occurredAt }?.id == log.id
        GrowthTimelineEvent(
            date = log.occurredAt.take(10),
            title = when {
                log.type == CareType.REPOT && firstRepot -> "第一次换盆"
                log.type == CareType.NEW_LEAF -> "长出新叶"
                else -> careLabel(log.type)
            },
            detail = log.note.ifBlank { log.actorName },
            emoji = careEmoji(log.type),
        )
    }
    return (listOfNotNull(created) + logs).sortedByDescending { it.date }
}

private fun diaryMoodEmoji(diary: PlantDiary): String {
    diary.moodEmoji?.takeIf { it.isNotBlank() }?.let { return it }
    val content = diary.content
    return when {
        content.contains("水") || content.contains("湿") -> "💧"
        content.contains("光") || content.contains("晒") -> "☀️"
        content.contains("叶") || content.contains("芽") -> "🌱"
        else -> "✨"
    }
}

private fun diaryDisplayContent(diary: PlantDiary): String {
    val cleaned = diary.content
        .lines()
        .dropWhile { line ->
            val text = line.trim()
            text.isBlank() || text.contains(diary.diaryDate) || text == diary.title
        }
        .joinToString("\n")
        .trim()
    return cleaned.ifBlank { diary.content.trim() }
}

private fun scheduleTimeText(item: ScheduleItem): String =
    item.displayTime.ifBlank { item.dueAt?.take(16)?.replace('T', ' ') ?: "未设置提醒时间" }

private fun scheduleSourceLabel(source: String): String =
    if (source == "ESP") "ESP 语音" else "手机"

private fun dashboardMetrics(state: SmartPotUiState): DashboardMetrics {
    val snap = state.snapshot
    val pot = snap?.pot
    val telemetry = snap?.telemetry
    val thresholds = pot?.species?.thresholds
    val zone = zoneIdOf(pot?.timezone)
    val today = LocalDate.now(zone)
    val dailyTouchCount = snap?.dailyTouchCount ?: 0
    val dailyDialogCount = state.todayMessages.count { it.role == ChatRole.USER && isSameLocalDate(it.createdAt, today, zone) }
    val dailyWaterCount = state.careLogs.count { it.type == CareType.WATER && isSameLocalDate(it.occurredAt, today, zone) }
    val dailyInteractions = dailyDialogCount + dailyTouchCount
    val soilSuitability = telemetry?.let { current -> thresholds?.let { PlantRules.soilSuitability(current.soilPercent, it) } } ?: 0.0
    val lightSuitability = telemetry?.let { current -> thresholds?.let { PlantRules.lightSuitability(current.lightLux, it) } } ?: 0.0
    return DashboardMetrics(
        growthDays = growthDaysSince(pot?.createdAt, today, zone),
        healthPercent = telemetry?.let { current -> thresholds?.let { PlantRules.healthPercent(current, it, dailyInteractions) } },
        companionStars = PlantRules.companionStars(dailyInteractions),
        dailyInteractions = dailyInteractions,
        dailyDialogCount = dailyDialogCount,
        dailyTouchCount = dailyTouchCount,
        dailyWaterCount = dailyWaterCount,
        soilSuitability = soilSuitability,
        lightSuitability = lightSuitability,
        interactionSuitability = PlantRules.interactionSuitability(dailyInteractions),
    )
}

private fun hourlyTelemetryPoints(
    values: List<DeviceTelemetry>,
    latest: DeviceTelemetry?,
    timezone: String?,
): List<HourlyTelemetryPoint> {
    val zone = zoneIdOf(timezone)
    val currentHour = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.HOURS)
    val firstHour = currentHour.minusHours(5)
    val grouped = telemetryWithLatest(values, latest)
        .mapNotNull { item ->
            val hour = parseInstant(item.recordedAt)?.atZone(zone)?.truncatedTo(ChronoUnit.HOURS) ?: return@mapNotNull null
            if (hour.isBefore(firstHour) || hour.isAfter(currentHour)) null else hour to item
        }
        .groupBy({ it.first }, { it.second })
    return (0L..5L).map { offset ->
        val hour = firstHour.plusHours(offset)
        val hourly = grouped[hour].orEmpty()
        HourlyTelemetryPoint(
            hour = hour,
            soilPercent = if (hourly.isEmpty()) null else hourly.map { it.soilPercent }.average().toFloat(),
            lightLux = if (hourly.isEmpty()) null else hourly.map { it.lightLux }.average().toFloat(),
        )
    }
}

private fun requestWeatherLocation(context: Context, onLocation: (Double, Double) -> Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
    val latest = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
    latest?.let { onLocation(it.latitude, it.longitude) }
    val provider = providers.firstOrNull() ?: return
    runCatching {
        LocationManagerCompat.getCurrentLocation(
            manager,
            provider,
            CancellationSignal(),
            ContextCompat.getMainExecutor(context),
        ) { location -> location?.let { onLocation(it.latitude, it.longitude) } }
    }
}

private fun compactMetricValue(value: Long): String = when {
    value < 100_000L -> value.toString()
    value < 1_000_000L -> "${(value / 1_000.0).roundToInt()}k"
    else -> "${(value / 1_000_000.0).roundToInt()}m"
}

private fun interactionStatus(value: Int): String = when {
    value >= 15 -> "常伴"
    value >= 9 -> "不错"
    value > 0 -> "继续互动"
    else -> "等待互动"
}

private fun metricStatusColor(value: String): Color = when (value) {
    "适宜", "常伴", "不错", "散射光" -> BrightLeaf
    "等待数据", "等待互动" -> Muted
    else -> Color(0xFFD17B2F)
}

private fun healthStatus(value: Int?, online: Boolean): String = when {
    !online -> "等待设备上线"
    value == null -> "正在计算"
    value >= 85 -> "状态良好"
    value >= 70 -> "状态不错"
    value >= 50 -> "需要留意"
    else -> "需要照顾"
}

private fun healthHint(value: Int?, online: Boolean): String = when {
    !online -> "连接后会恢复实时评估"
    value == null -> "收到环境数据后自动更新"
    value >= 85 -> "继续保持哦！"
    value >= 70 -> "再陪陪它会更开心"
    value >= 50 -> "看看下方的养护建议"
    else -> "请优先处理需要关注的项目"
}

private fun telemetryWithLatest(history: List<DeviceTelemetry>, latest: DeviceTelemetry?): List<DeviceTelemetry> {
    if (latest == null) return history
    return if (history.any { it.deviceId == latest.deviceId && it.sequence == latest.sequence }) history else history + latest
}

private fun growthDaysSince(createdAt: String?, today: LocalDate, zone: ZoneId): Int? {
    val createdDate = createdAt?.let(::parseInstant)?.atZone(zone)?.toLocalDate() ?: return null
    return (ChronoUnit.DAYS.between(createdDate, today).toInt() + 1).coerceAtLeast(1)
}

private fun isSameLocalDate(value: String, today: LocalDate, zone: ZoneId): Boolean =
    parseInstant(value)?.atZone(zone)?.toLocalDate() == today

private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

private fun zoneIdOf(value: String?): ZoneId =
    runCatching { ZoneId.of(value ?: "Asia/Shanghai") }.getOrDefault(ZoneId.of("Asia/Shanghai"))

private fun suitabilityLabel(value: Double): String = "${(value * 100).roundToInt()}%"

private fun starScoreText(value: Float): String {
    val tenths = (value.coerceIn(0f, 5f) * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}/5" else "${tenths / 10}.${tenths % 10}/5"
}
