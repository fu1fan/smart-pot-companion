package com.fu1fan.smartpot.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fu1fan.smartpot.R
import com.fu1fan.smartpot.protocol.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Leaf = Color(0xFF407A52)
private val BrightLeaf = Color(0xFF2E9254)
private val SoftLeaf = Color(0xFFE5F0E4)
private val Sand = Color(0xFFFFFAEC)
private val Ink = Color(0xFF1E241F)
private val Muted = Color(0xFF897D68)
private val CardBorder = Color(0xFFE8D6A5)
private val Sky = Color(0xFF48A9E6)
private val Sun = Color(0xFFFF9D28)
private val Violet = Color(0xFF8D78D9)
private val PixelSkyTop = Color(0xFFEAF7FF)
private val PixelSkyBottom = Color(0xFFFFF5DE)
private val PixelPanelFill = Color(0xFFFFFBEE)
private val PixelPanelEdge = Color(0xFFD8A851)
private val PixelPanelLight = Color(0xFFFFF7D8)
private val PixelWood = Color(0xFFB77931)
private val PixelWoodDark = Color(0xFF7A451C)
private val PixelSign = Color(0xFFFFE8B5)
private val PixelCream = Color(0xFFFFFCF1)
private val PixelGreenPanel = Color(0xFFF4FCE8)
private val PixelGreenEdge = Color(0xFF9FB85E)
private val PixelDisabled = Color(0xFFD9D3C3)
private val PixelDanger = Color(0xFFD14343)
private val WarmShadow = Color(0x332B1A08)
private val WarmLine = Color(0xFFDCC889)
private val WarmLeafSoft = Color(0xFFEAF4D7)

private object SmartPotTypeScale {
    val labelSmall = 11.sp
    val bodySmall = 12.sp
    val bodyMedium = 14.sp
    val titleMedium = 16.sp
    val titleLarge = 22.sp
    val headlineSmall = 24.sp
    val headlineMedium = 28.sp
    val headlineLarge = 32.sp
    val displaySmall = 36.sp
}

private val SmartPotTypography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, lineHeight = 64.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    displayMedium = TextStyle(fontSize = 45.sp, lineHeight = 52.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)

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

private data class DailyLightIntegral(
    val effectiveMinutes: Int,
    val totalLuxHours: Int,
    val ambientLuxHours: Int,
    val supplementalLuxHours: Int,
    val recommendedSupplementMinutes: Int,
    val targetLuxHours: Int,
    val completionPercent: Int,
)

private data class PlantCoreStatus(
    val text: String,
    val color: Color,
)

private enum class EnvironmentReminderType(val preferenceKey: String) {
    THIRSTY("environment_reminder_thirsty_confirmed_at"),
    DARK("environment_reminder_dark_confirmed_at"),
}

private const val EnvironmentReminderIntervalMs = 60L * 60L * 1000L

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SmartPotApp(viewModel: SmartPotViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val reminderPreferences = remember(context) {
        context.getSharedPreferences("smart_pot_environment_reminders", Context.MODE_PRIVATE)
    }
    val reminderType = when {
        state.snapshot?.online != true || state.snapshot?.telemetry == null -> null
        state.snapshot?.evaluated?.soilStatus == SoilStatus.TOO_DRY -> EnvironmentReminderType.THIRSTY
        state.snapshot?.evaluated?.lightStatus == LightStatus.DARK -> EnvironmentReminderType.DARK
        else -> null
    }
    var visibleReminder by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(reminderType, state.snapshot?.telemetry?.recordedAt) {
        if (reminderType == null) {
            visibleReminder = null
        } else {
            val lastConfirmedAt = reminderPreferences.getLong(reminderType.preferenceKey, 0L)
            if (System.currentTimeMillis() - lastConfirmedAt >= EnvironmentReminderIntervalMs) {
                visibleReminder = reminderType.name
            }
        }
    }
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Leaf, secondary = Color(0xFF7D9763), background = Sand, surface = Color.White),
        typography = SmartPotTypography,
    ) {
        Scaffold(
            containerColor = Sand,
            topBar = {
            },
            bottomBar = {
                if (!state.inviteRequired && state.potsLoaded && state.pots.isNotEmpty()) {
                    PixelBottomBar(tab) { tab = it }
                }
            },
            snackbarHost = {
                state.error?.let { error -> Snackbar(action = { PixelTextButton(onClick = viewModel::clearError) { Text("知道了") } }) { Text(error) } }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    state.inviteRequired -> InviteGateScreen(
                        error = state.error,
                        submitting = state.inviteSubmitting,
                        onRedeem = viewModel::redeemInvite,
                    )
                    state.loading && !state.potsLoaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    !state.potsLoaded -> ConnectionRetryScreen(state.error, viewModel::bootstrap)
                    state.pots.isEmpty() -> SetupScreen(state.species, viewModel::createPot, viewModel::redeemShare)
                    tab == 0 -> DashboardScreen(state, viewModel::updateSpecies, viewModel::refreshWeather)
                    tab == 1 -> CareScreen(
                        state,
                        viewModel::addCare,
                        viewModel::deleteCare,
                        viewModel::saveDiary,
                        viewModel::deleteDiary,
                        viewModel::speakDiary,
                        viewModel::stopDiarySpeech,
                    )
                    tab == 2 -> CompanionScreen(
                        state,
                        viewModel::sendChat,
                        viewModel::addMemory,
                        viewModel::deleteMemory,
                        viewModel::selectChatDay,
                        viewModel::addSchedule,
                        viewModel::toggleSchedule,
                        viewModel::startPomodoroTimer,
                        viewModel::pausePomodoroTimer,
                        viewModel::exitPomodoroTimer,
                    )
                    else -> ControlScreen(
                        state,
                        viewModel::control,
                        viewModel::createShare,
                        viewModel::saveUserProfile,
                    )
                }
            }
        }
        visibleReminder?.let { reminderName ->
            val activeReminder = runCatching { EnvironmentReminderType.valueOf(reminderName) }.getOrNull()
            if (activeReminder != null) {
                EnvironmentReminderDialog(
                    type = activeReminder,
                    userName = state.userName,
                    onConfirm = {
                        reminderPreferences.edit()
                            .putLong(activeReminder.preferenceKey, System.currentTimeMillis())
                            .apply()
                        visibleReminder = null
                    },
                )
            }
        }
    }
}

@Composable
private fun InviteGateScreen(
    error: String?,
    submitting: Boolean,
    onRedeem: (String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.home_page_background),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
        PixelPanel(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
                .fillMaxWidth(),
            fill = Color(0xFFFFFDF5),
            edge = CardBorder,
            showCornerBolts = false,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("加入共享盆栽", color = Ink, fontSize = SmartPotTypeScale.headlineSmall, fontWeight = FontWeight.Black)
                Text(
                    "输入主人提供的 6 位邀请码后，即可和 ESP 一起照顾小麦。",
                    color = Muted,
                    fontSize = SmartPotTypeScale.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                PixelTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter(Char::isDigit).take(6) },
                    label = "邀请码",
                    placeholder = "请输入 6 位邀请码",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !submitting,
                )
                error?.let {
                    Text(it, color = PixelDanger, fontSize = SmartPotTypeScale.bodySmall, textAlign = TextAlign.Center)
                }
                PixelButton(
                    onClick = { onRedeem(code) },
                    enabled = code.length == 6 && !submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (submitting) "正在验证..." else "验证并加入")
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
        PixelButton(onClick = retry) { Text("重新连接") }
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
        item { PixelTextField(device, { device = it }, label = "设备 ID", modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { PixelTextField(name, { name = it }, label = "盆栽昵称", modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item {
            Text("植物品种", fontWeight = FontWeight.SemiBold)
            species.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { plant ->
                        PixelButton(
                            selected = selected == plant.id,
                            onClick = { selected = plant.id },
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                        ) { Text(plant.chineseName, fontSize = SmartPotTypeScale.bodySmall) }
                    }
                }
            }
        }
        item { PixelButton(onClick = { create(device, name, selected) }, enabled = selected.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("完成绑定") } }
        item { HorizontalDivider(); Text("或加入别人分享的盆栽", fontWeight = FontWeight.Bold) }
        item {
            PixelTextField(share, { share = it }, label = "6 位分享码", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            PixelOutlinedButton(onClick = { redeem(share, "访客") }, modifier = Modifier.fillMaxWidth()) { Text("加入共享盆栽") }
        }
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
    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(
            Modifier.fillMaxWidth(0.88f).widthIn(max = 320.dp).wrapContentHeight(),
            fill = PixelCream,
            edge = PixelWoodDark,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            showCornerBolts = false,
        ) {
            Column(Modifier.fillMaxWidth().wrapContentHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("修改植物品种", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Black, color = Ink)
                PixelTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "搜索植物品种",
                    placeholder = "中文名、英文名",
                    singleLine = true,
                )
                if (filteredSpecies.isEmpty()) {
                    Text("没有找到匹配的植物品种", color = Muted, fontSize = SmartPotTypeScale.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).wrapContentHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredSpecies, key = { it.id }) { plant ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(plant.id) }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(plant.chineseName, fontWeight = FontWeight.SemiBold)
                                    Text(plant.scientificName, fontSize = SmartPotTypeScale.bodySmall, color = Color.Gray)
                                    Text(
                                        "湿度 ${plant.thresholds.soilMinPercent}-${plant.thresholds.soilMaxPercent}% · 光照 ${plant.thresholds.lightMinLux}-${plant.thresholds.lightMaxLux} lux",
                                        fontSize = SmartPotTypeScale.labelSmall,
                                        color = Color.Gray,
                                    )
                                }
                                if (plant.id == currentSpeciesId) Text("当前", color = Leaf, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                PixelTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun PixelBottomBar(selectedTab: Int, onSelect: (Int) -> Unit) {
    val items = listOf("首页" to "⌂", "养护" to "♧", "陪伴" to "♡", "控制" to "◎")
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = Color(0xFFFFFCF5),
        tonalElevation = 2.dp,
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onSelect(index) },
                icon = { Text(item.second, fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold) },
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
}

@Composable
private fun PixelNavGlyph(kind: String, selected: Boolean, modifier: Modifier = Modifier) {
    val dark = PixelWoodDark
    val fill = when (kind) {
        "home" -> Color(0xFF86D5FF)
        "care" -> Color(0xFFFFC94B)
        "heart" -> Color(0xFFFF7D88)
        else -> if (selected) Color(0xFFE8F7D0) else Color(0xFFD8D1C5)
    }
    Canvas(modifier) {
        val u = size.minDimension / 12f
        fun r(x: Int, y: Int, w: Int, h: Int, color: Color = fill) {
            drawRect(color, Offset(x * u, y * u), Size(w * u, h * u))
            drawRect(dark, Offset(x * u, y * u), Size(w * u, h * u), style = Stroke((u * 0.45f).coerceAtLeast(1f)))
        }
        when (kind) {
            "home" -> {
                r(3, 5, 6, 5)
                r(5, 7, 2, 3, PixelCream)
                val roof = Path().apply {
                    moveTo(2 * u, 6 * u)
                    lineTo(6 * u, 2 * u)
                    lineTo(10 * u, 6 * u)
                    close()
                }
                drawPath(roof, Color(0xFF6EC1F3))
                drawPath(roof, dark, style = Stroke((u * 0.55f).coerceAtLeast(1f)))
            }
            "care" -> {
                r(2, 6, 6, 3, fill)
                r(5, 4, 3, 2, fill)
                r(7, 5, 3, 2, fill)
                drawLine(dark, Offset(9 * u, 5 * u), Offset(11 * u, 4 * u), strokeWidth = (u * 0.7f).coerceAtLeast(1f))
                drawRect(Color(0xFF6EC1F3), Offset(10.5f * u, 3.2f * u), Size(0.9f * u, 0.9f * u))
                drawRect(Color(0xFF6EC1F3), Offset(11.2f * u, 4.6f * u), Size(0.9f * u, 0.9f * u))
            }
            "heart" -> {
                listOf(
                    3 to 3, 4 to 3, 7 to 3, 8 to 3,
                    2 to 4, 3 to 4, 4 to 4, 5 to 4, 6 to 4, 7 to 4, 8 to 4, 9 to 4,
                    2 to 5, 3 to 5, 4 to 5, 5 to 5, 6 to 5, 7 to 5, 8 to 5, 9 to 5,
                    3 to 6, 4 to 6, 5 to 6, 6 to 6, 7 to 6, 8 to 6,
                    4 to 7, 5 to 7, 6 to 7, 7 to 7,
                    5 to 8, 6 to 8,
                ).forEach { (x, y) -> drawRect(fill, Offset(x * u, y * u), Size(u, u)) }
                drawRect(dark, Offset(2 * u, 3 * u), Size(8 * u, 6 * u), style = Stroke((u * 0.45f).coerceAtLeast(1f)))
            }
            else -> {
                r(5, 2, 2, 8, fill)
                r(2, 5, 8, 2, fill)
                r(4, 4, 4, 4, PixelCream)
                drawCircle(fill, 1.1f * u, Offset(6 * u, 6 * u))
                drawCircle(dark, 1.1f * u, Offset(6 * u, 6 * u), style = Stroke((u * 0.45f).coerceAtLeast(1f)))
            }
        }
    }
}

@Composable
private fun PixelSkyDecor(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val grid = 18.dp.toPx()
        for (x in 0..(size.width / grid).toInt()) {
            for (y in 0..(size.height / grid).toInt()) {
                if ((x * 5 + y * 3) % 13 == 0) {
                    drawCircle(Color(0xFFFFF6D4).copy(alpha = 0.36f), 2.dp.toPx(), Offset(x * grid, y * grid))
                }
            }
        }
        fun cloud(x: Float, y: Float, scale: Float) {
            val unit = 8.dp.toPx() * scale
            val color = Color.White.copy(alpha = 0.72f)
            drawRect(color, Offset(x + unit * 1f, y + unit * 1f), Size(unit * 6f, unit * 2f))
            drawRect(color, Offset(x + unit * 2f, y), Size(unit * 2f, unit))
            drawRect(color, Offset(x + unit * 5f, y + unit * 0.5f), Size(unit * 2f, unit * 1.5f))
            drawRect(color.copy(alpha = 0.45f), Offset(x + unit * 3f, y + unit * 3f), Size(unit * 5f, unit))
        }
        cloud(size.width * 0.7f, 18.dp.toPx(), 0.72f)
        cloud(size.width * 0.45f, 116.dp.toPx(), 0.5f)
        cloud(-22.dp.toPx(), 208.dp.toPx(), 0.6f)
        listOf(
            Offset(size.width * 0.55f, 86.dp.toPx()),
            Offset(size.width * 0.9f, 96.dp.toPx()),
            Offset(size.width * 0.62f, 130.dp.toPx()),
        ).forEach { center ->
            drawLine(Color(0xFFFFF3A2), Offset(center.x - 5.dp.toPx(), center.y), Offset(center.x + 5.dp.toPx(), center.y), 2.dp.toPx())
            drawLine(Color(0xFFFFF3A2), Offset(center.x, center.y - 5.dp.toPx()), Offset(center.x, center.y + 5.dp.toPx()), 2.dp.toPx())
        }
    }
}

@Composable
private fun PixelNatureBackground(modifier: Modifier = Modifier, green: Boolean = true) {
    val top = if (green) Color(0xFFF6FBEA) else PixelSkyTop
    val bottom = if (green) Color(0xFFFFF9E6) else PixelSkyBottom
    Canvas(modifier.background(bottom)) {
        drawRect(top, Offset(0f, 0f), Size(size.width, size.height * 0.34f))
        val cell = 28.dp.toPx()
        for (x in 0..(size.width / cell).toInt() + 1) {
            for (y in 0..(size.height / cell).toInt() + 1) {
                if ((x + y) % 3 == 0) {
                    drawCircle(Color(0xFFDCEBC1).copy(alpha = 0.18f), 3.dp.toPx(), Offset(x * cell, y * cell + 8.dp.toPx()))
                }
            }
        }
        fun leaf(cx: Float, cy: Float, sx: Float, sy: Float, color: Color) {
            val path = Path().apply {
                moveTo(cx, cy - sy)
                quadraticBezierTo(cx + sx, cy, cx, cy + sy)
                quadraticBezierTo(cx - sx, cy, cx, cy - sy)
                close()
            }
            drawPath(path, color)
            drawLine(color.copy(alpha = 0.55f), Offset(cx, cy - sy * 0.75f), Offset(cx, cy + sy * 0.75f), 1.dp.toPx())
        }
        repeat(5) { i ->
            val x = if (i % 2 == 0) 18.dp.toPx() + i * 13.dp.toPx() else size.width - 38.dp.toPx() - i * 9.dp.toPx()
            val y = size.height - 170.dp.toPx() + i * 24.dp.toPx()
            rotate(if (i % 2 == 0) -28f else 28f, Offset(x, y)) {
                leaf(x, y, 13.dp.toPx(), 26.dp.toPx(), Color(0xFFB8D58A).copy(alpha = 0.62f))
            }
        }
        drawRect(Color(0xFFE9F3C9).copy(alpha = 0.56f), Offset(0f, size.height - 28.dp.toPx()), Size(size.width, 28.dp.toPx()))
    }
}

@Composable
private fun PixelPanel(
    modifier: Modifier = Modifier,
    fill: Color = PixelPanelFill,
    edge: Color = PixelPanelEdge,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    showCornerBolts: Boolean = true,
    fillContainer: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = 0.dp, y = 3.dp)
                .background(WarmShadow, RoundedCornerShape(10.dp)),
        )
        Box(
            (if (fillContainer) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .background(fill, RoundedCornerShape(8.dp))
                .border(1.dp, edge, RoundedCornerShape(8.dp))
                .padding(1.dp)
                .border(1.dp, Color.White.copy(alpha = 0.78f), RoundedCornerShape(7.dp))
                .padding(contentPadding),
        ) {
            PixelPanelTexture(fill)
            content()
            if (showCornerBolts) {
                PixelCornerBolts(Color(0xFFE3B566))
            }
        }
    }
}

@Composable
private fun BoxScope.PixelPanelTexture(fill: Color) {
    Canvas(Modifier.matchParentSize()) {
        val cell = 36.dp.toPx()
        val tint = when (fill) {
            PixelPanelFill -> Color(0xFFDDBF78)
            PixelGreenPanel -> Color(0xFFB8D98C)
            PixelCream -> Color(0xFFE8C982)
            else -> Color.White
        }.copy(alpha = 0.11f)
        for (x in 0..(size.width / cell).toInt() + 1) {
            for (y in 0..(size.height / cell).toInt() + 1) {
                if ((x * 2 + y * 3) % 4 == 0) {
                    drawCircle(tint, 2.2.dp.toPx(), Offset(x * cell + 8.dp.toPx(), y * cell + 12.dp.toPx()))
                }
            }
        }
        drawRect(Color.White.copy(alpha = 0.22f), Offset(0f, 0f), Size(size.width, 1.dp.toPx()))
    }
}

@Composable
private fun BoxScope.PixelCornerBolts(color: Color) {
    val bolt = Modifier.size(5.dp).background(color).border(1.dp, PixelWood)
    Box(bolt.align(Alignment.TopStart).offset(5.dp, 5.dp))
    Box(bolt.align(Alignment.TopEnd).offset((-5).dp, 5.dp))
    Box(bolt.align(Alignment.BottomStart).offset(5.dp, (-5).dp))
    Box(bolt.align(Alignment.BottomEnd).offset((-5).dp, (-5).dp))
}

@Composable
private fun PixelTitleSign(title: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Box(
        modifier
            .height(if (compact) 44.dp else 52.dp)
            .background(PixelSign, RoundedCornerShape(2.dp))
            .border(2.dp, PixelWood, RoundedCornerShape(2.dp))
            .padding(2.dp)
            .border(1.dp, Color(0xFFFFF2CD), RoundedCornerShape(1.dp))
            .padding(horizontal = if (compact) 12.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val plank = 8.dp.toPx()
            for (y in 0..(size.height / plank).toInt()) {
                val c = if (y % 2 == 0) Color(0xFFFFDDA4) else Color(0xFFFFEDC5)
                drawRect(c.copy(alpha = 0.42f), Offset(0f, y * plank), Size(size.width, 4.dp.toPx()))
            }
            listOf(0.18f, 0.42f, 0.74f).forEach { fx ->
                val y = size.height * fx
                drawRect(Color(0xFFB87932).copy(alpha = 0.28f), Offset(14.dp.toPx(), y), Size(size.width - 28.dp.toPx(), 1.dp.toPx()))
            }
            drawRect(WarmShadow, Offset(4.dp.toPx(), size.height - 1.dp.toPx()), Size(size.width - 8.dp.toPx(), 2.dp.toPx()))
        }
        Text(
            title,
            color = PixelWoodDark,
            fontSize = if (compact) SmartPotTypeScale.titleLarge else SmartPotTypeScale.headlineSmall,
            fontWeight = FontWeight.Black,
        )
        PixelCornerBolts(Color(0xFFE6AD60))
    }
}

@Composable
private fun PixelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    fill: Color = if (selected) Color(0xFF2F8F50) else BrightLeaf,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val container = if (enabled) fill else PixelDisabled
    val edge = if (enabled) Color(0xFF267240) else Color(0xFF9D998B)
    val contentColor = when {
        !enabled -> Color(0xFF6D725F)
        fill == PixelCream || fill == PixelPanelFill -> PixelWoodDark
        else -> Color.White
    }
    Box(
        modifier
            .offset(y = if (pressed && enabled) 2.dp else 0.dp)
            .drawBehind {
                if (enabled) {
                    val shadow = if (pressed) 1.dp.toPx() else 2.dp.toPx()
                    drawRoundRect(edge.copy(alpha = 0.35f), Offset(0f, shadow), Size(size.width, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx()))
                }
            }
            .background(container, RoundedCornerShape(10.dp))
            .border(1.dp, edge, RoundedCornerShape(10.dp))
            .padding(1.dp)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.42f else 0.2f), RoundedCornerShape(9.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(LocalTextStyle.current.copy(color = contentColor, fontWeight = FontWeight.Bold)) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun PixelOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    content: @Composable RowScope.() -> Unit,
) {
    PixelButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        fill = PixelCream,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
private fun PixelTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
    danger: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val edge = if (danger) PixelDanger else Leaf
    val contentColor = if (enabled) edge else PixelDisabled
    Box(
        modifier
            .offset(y = if (pressed && enabled) 1.dp else 0.dp)
            .drawBehind {
                if (enabled) {
                    val shadow = if (pressed) 0.5.dp.toPx() else 1.dp.toPx()
                    drawRoundRect(edge.copy(alpha = 0.14f), Offset(0f, shadow), Size(size.width, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()))
                }
            }
            .background(PixelCream.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .border(1.dp, edge.copy(alpha = if (enabled) 0.34f else 0.18f), RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(LocalTextStyle.current.copy(color = contentColor, fontWeight = FontWeight.Bold)) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { Text(it, color = PixelWoodDark, fontSize = SmartPotTypeScale.labelSmall, fontWeight = FontWeight.Bold) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = SmartPotTypeScale.bodyMedium),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (minLines > 1) (44 + minLines * 18).dp else 44.dp)
                        .background(if (enabled) Color(0xFFFFFDF4) else Color(0xFFE7E2D5), RoundedCornerShape(6.dp))
                        .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isBlank() && placeholder != null) {
                        Text(placeholder, color = Muted, fontSize = SmartPotTypeScale.bodyMedium)
                    }
                    innerTextField()
                }
            },
        )
        supportingText?.let { Text(it, color = Muted, fontSize = SmartPotTypeScale.labelSmall) }
    }
}

@Composable
private fun PixelSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(width = 54.dp, height = 30.dp)
            .background(if (checked) BrightLeaf else Color(0xFFE8E2CD), RoundedCornerShape(15.dp))
            .border(1.dp, if (checked) Leaf else CardBorder, RoundedCornerShape(15.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(if (enabled) PixelCream else PixelDisabled, RoundedCornerShape(12.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
        )
    }
}

@Composable
private fun PixelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    activeColor: Color = BrightLeaf,
    onValueChangeFinished: () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        fun updateFromX(x: Float) {
            val nextFraction = (x / widthPx).coerceIn(0f, 1f)
            onValueChange(valueRange.start + (valueRange.endInclusive - valueRange.start) * nextFraction)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .pointerInput(widthPx) {
                    detectTapGestures { offset ->
                        updateFromX(offset.x)
                        onValueChangeFinished()
                    }
                }
                .pointerInput(widthPx) {
                    detectDragGestures(
                        onDragEnd = onValueChangeFinished,
                        onDragCancel = onValueChangeFinished,
                    ) { change, _ ->
                        updateFromX(change.position.x)
                        change.consume()
                    }
                },
        ) {
            val trackH = 9.dp.toPx()
            val y = (size.height - trackH) / 2f
            drawRoundRect(Color(0xFFE9EEDC), Offset(0f, y), Size(size.width, trackH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()))
            drawRoundRect(activeColor, Offset(0f, y), Size(size.width * fraction, trackH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()))
            val knobX = (size.width * fraction).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx())
            drawRoundRect(PixelCream, Offset(knobX - 5.dp.toPx(), y - 6.dp.toPx()), Size(10.dp.toPx(), trackH + 12.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx(), 5.dp.toPx()))
            drawRoundRect(activeColor, Offset(knobX - 5.dp.toPx(), y - 6.dp.toPx()), Size(10.dp.toPx(), trackH + 12.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx(), 5.dp.toPx()), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun PixelProgressBar(progress: Float, modifier: Modifier = Modifier, fill: Color = BrightLeaf) {
    Box(
        modifier
            .height(9.dp)
            .background(Color(0xFFE9EEDC), RoundedCornerShape(8.dp)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(fill, RoundedCornerShape(8.dp)))
    }
}

@Composable
private fun PixelCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .background(if (checked) BrightLeaf else PixelCream, RoundedCornerShape(4.dp))
            .border(1.dp, if (checked) Leaf else CardBorder, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = Color.White, fontSize = SmartPotTypeScale.bodyMedium, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun PixelConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(
            Modifier.fillMaxWidth(0.82f).widthIn(max = 288.dp).wrapContentHeight(),
            fill = PixelCream,
            edge = PixelWoodDark,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
            showCornerBolts = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(title, fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Black, color = Ink)
                Text(text, color = Ink, fontSize = SmartPotTypeScale.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PixelTextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)) {
                        Text("取消", fontSize = SmartPotTypeScale.labelSmall)
                    }
                    Spacer(Modifier.width(6.dp))
                    PixelTextButton(onClick = onConfirm, danger = danger, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)) {
                        Text(confirmText, fontSize = SmartPotTypeScale.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentReminderDialog(
    type: EnvironmentReminderType,
    userName: String,
    onConfirm: () -> Unit,
) {
    val thirsty = type == EnvironmentReminderType.THIRSTY
    val ownerName = userName.trim().ifBlank { "主人" }
    Dialog(onDismissRequest = {}) {
        PixelPanel(
            Modifier.fillMaxWidth(0.86f).widthIn(max = 320.dp).wrapContentHeight(),
            fill = PixelCream,
            edge = if (thirsty) Color(0xFF78AFC9) else Color(0xFFE3B45F),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            showCornerBolts = false,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(66.dp)
                        .background(if (thirsty) Color(0xFFE7F6FF) else Color(0xFFFFF3D4), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    PixelMetricGlyph(
                        kind = if (thirsty) "water" else "sun",
                        color = if (thirsty) Sky else Sun,
                        modifier = Modifier.size(42.dp),
                    )
                }
                Text(
                    if (thirsty) "小麦有点口渴" else "小麦想晒晒太阳",
                    modifier = Modifier.fillMaxWidth(),
                    color = Ink,
                    fontSize = SmartPotTypeScale.titleLarge,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (thirsty) "${ownerName}记得喂我喝水哦！" else "室内光线有点暗，${ownerName}帮我补充一些温柔的光吧！",
                    modifier = Modifier.fillMaxWidth(),
                    color = Muted,
                    fontSize = SmartPotTypeScale.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                PixelButton(
                    onClick = onConfirm,
                    modifier = Modifier.width(132.dp),
                    contentPadding = PaddingValues(vertical = 9.dp),
                ) {
                    Text("我知道了", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    state: SmartPotUiState,
    updateSpecies: (String) -> Unit,
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
    val snap = state.snapshot
    val metrics = dashboardMetrics(state)
    var speciesDialog by rememberSaveable { mutableStateOf(false) }
    var healthDetailsVisible by rememberSaveable { mutableStateOf(false) }
    var showEco2 by rememberSaveable { mutableStateOf(false) }
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
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFFFAEA)),
    ) {
        Image(
            painter = painterResource(R.drawable.home_page_background),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
        ) {
            item {
                DashboardHero(
                    pot = pot,
                    userName = state.userName,
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
                    userName = state.userName,
                    soilStatus = snap?.evaluated?.soilStatus,
                    lightStatus = snap?.evaluated?.lightStatus,
                    thresholds = pot?.species?.thresholds,
                    affinity = snap?.affinity,
                    detailsVisible = healthDetailsVisible,
                    onToggleDetails = { healthDetailsVisible = !healthDetailsVisible },
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    DashboardMetricCard(
                        iconKind = "water",
                        iconColor = Sky,
                        title = "土壤湿度",
                        value = snap?.telemetry?.soilPercent?.toString() ?: "--",
                        unit = "%",
                        status = soilLabel(snap?.evaluated?.soilStatus),
                        modifier = Modifier.weight(1f),
                    )
                    DashboardMetricCard(
                        iconKind = "sun",
                        iconColor = Sun,
                        title = "室内光照",
                        value = snap?.telemetry?.lightLux?.let(::compactMetricValue) ?: "--",
                        unit = "lux",
                        status = lightLabel(snap?.evaluated?.lightStatus),
                        modifier = Modifier.weight(1f),
                    )
                    DashboardMetricCard(
                        iconKind = "air",
                        iconColor = BrightLeaf,
                        title = if (showEco2) "CO₂浓度" else "TVOC",
                        value = if (showEco2) {
                            snap?.telemetry?.eco2Ppm?.toString() ?: "--"
                        } else {
                            snap?.telemetry?.tvocPpb?.toString() ?: "--"
                        },
                        unit = if (showEco2) "ppm" else "ppb",
                        status = airQualityLabel(
                            showEco2 = showEco2,
                            value = if (showEco2) snap?.telemetry?.eco2Ppm else snap?.telemetry?.tvocPpb,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showEco2 = !showEco2 },
                    )
                }
            }
            item { TodayEnvironmentCard(state) }
            item { CompanionScoreCard(metrics) }
            item {
                TodayLightIntegralCard(
                    values = state.telemetry,
                    latest = snap?.telemetry,
                    timezone = pot?.timezone,
                    thresholds = pot?.species?.thresholds,
                )
            }
            item {
                DashboardAttentionCard(
                    snapshot = snap,
                    weather = state.careOverview?.weather,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                DashboardAdviceCard(
                    speciesCareAdvice(snap),
                )
            }
        }
    }
}

@Composable
private fun HomeReferenceBackground(modifier: Modifier = Modifier) {
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Color(0xFFFFFAEA), Offset.Zero, size)
            drawRect(Color(0xFFEAF7FA).copy(alpha = 0.9f), Offset(0f, 46.dp.toPx()), Size(size.width, 260.dp.toPx()))
            drawRect(Color(0xFFFFFAEA), Offset(0f, 300.dp.toPx()), Size(size.width, size.height - 300.dp.toPx()))
            fun softLeaf(cx: Float, cy: Float, scale: Float, angle: Float, color: Color) {
                rotate(angle, Offset(cx, cy)) {
                    val path = Path().apply {
                        moveTo(cx, cy - 24.dp.toPx() * scale)
                        quadraticBezierTo(cx + 16.dp.toPx() * scale, cy, cx, cy + 24.dp.toPx() * scale)
                        quadraticBezierTo(cx - 16.dp.toPx() * scale, cy, cx, cy - 24.dp.toPx() * scale)
                        close()
                    }
                    drawPath(path, color)
                }
            }
            repeat(10) { index ->
                softLeaf(
                    cx = if (index % 2 == 0) 12.dp.toPx() + index * 9.dp.toPx() else size.width - 18.dp.toPx() - index * 7.dp.toPx(),
                    cy = size.height - 84.dp.toPx() + (index % 4) * 18.dp.toPx(),
                    scale = 0.55f + (index % 3) * 0.1f,
                    angle = if (index % 2 == 0) -32f else 30f,
                    color = Color(0xFFAFCF87).copy(alpha = 0.48f),
                )
            }
        }
    }
}

@Composable
private fun DashboardHero(
    pot: PotProfile?,
    userName: String,
    online: Boolean,
    metrics: DashboardMetrics,
    canEditSpecies: Boolean,
    onEditSpecies: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(276.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.home_garden_background),
            contentDescription = "首页花园背景",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(238.dp),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.BottomCenter,
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 8.dp, top = 6.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PixelTitleSign("你好，${userName.ifBlank { "主人" }}", Modifier.width(194.dp), compact = true)
            Column(
                Modifier
                    .width(196.dp)
                    .clickable(enabled = canEditSpecies, onClick = onEditSpecies),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    pot?.let { "${it.species.chineseName} · ${it.species.scientificName}" } ?: "正在连接你的盆栽",
                    color = BrightLeaf,
                    fontSize = SmartPotTypeScale.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (online) "${pot?.displayName ?: "小麦"}今天也在等你哦~" else "设备离线，数据会在连接后自动更新",
                    color = if (online) Color(0xFF6F5B38) else Color(0xFF8B6736),
                    fontSize = SmartPotTypeScale.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(6.dp))
            PixelPanel(
                modifier = Modifier.width(148.dp).height(126.dp),
                fill = Color(0xFFFFFDF2),
                edge = CardBorder,
                contentPadding = PaddingValues(7.dp),
            ) {
                Column(Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "成长第 ${metrics.growthDays?.toString() ?: "--"} 天",
                        fontSize = SmartPotTypeScale.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text("我们一起的日子", fontSize = SmartPotTypeScale.labelSmall, color = Muted)
                    Text(
                        metrics.growthDays?.toString() ?: "--",
                        fontSize = SmartPotTypeScale.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF087D3C),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePixelMascot(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = (size.minDimension / 34f).coerceAtLeast(4f)
        val ox = size.width / 2f - 15f * u
        val oy = size.height / 2f - 10f * u
        fun rect(x: Float, y: Float, w: Float, h: Float, color: Color) {
            drawRect(color, Offset(ox + x * u, oy + y * u), Size(w * u, h * u))
        }
        fun outline(x: Float, y: Float, w: Float, h: Float, color: Color = PixelWoodDark) {
            drawRect(color, Offset(ox + x * u, oy + y * u), Size(w * u, h * u), style = Stroke((u * 0.45f).coerceAtLeast(1f)))
        }
        rect(7f, 20.5f, 18f, 2f, Color(0x803A2B19))
        rect(8f, 15f, 16f, 7f, Color(0xFFB96A22))
        rect(6f, 13f, 20f, 4f, Color(0xFFE29237))
        rect(7f, 13.5f, 18f, 1f, Color(0xFFFFB458))
        outline(6f, 13f, 20f, 4f)
        outline(8f, 15f, 16f, 7f)
        rect(11f, 5f, 10f, 11f, Color(0xFFFFF0CF))
        rect(9f, 8f, 14f, 8f, Color(0xFFFFF0CF))
        rect(10f, 7f, 2f, 2f, Color(0xFFFFD5BA))
        rect(20f, 7f, 2f, 2f, Color(0xFFFFD5BA))
        rect(9f, 5f, 3f, 4f, Color(0xFFFFD5BA))
        rect(20f, 5f, 3f, 4f, Color(0xFFFFD5BA))
        rect(10f, 4f, 2f, 2f, Color(0xFFFFF0CF))
        rect(20f, 4f, 2f, 2f, Color(0xFFFFF0CF))
        outline(9f, 8f, 14f, 8f)
        rect(12f, 10f, 2f, 3f, Color(0xFF181412))
        rect(19f, 10f, 2f, 3f, Color(0xFF181412))
        rect(12.4f, 10.3f, 0.8f, 0.8f, Color.White)
        rect(19.4f, 10.3f, 0.8f, 0.8f, Color.White)
        rect(14.2f, 13.2f, 1.1f, 0.7f, Color(0xFF241512))
        rect(17.0f, 13.2f, 1.1f, 0.7f, Color(0xFF241512))
        rect(12f, 14f, 2f, 1f, Color(0xFFFF9C9C))
        rect(19f, 14f, 2f, 1f, Color(0xFFFF9C9C))
        rect(14f, 2f, 2f, 6f, Color(0xFF4E8A24))
        rect(11f, 1f, 4f, 3f, Color(0xFF8BC742))
        rect(17f, 1f, 4f, 3f, Color(0xFF8BC742))
        rect(13f, -1f, 3f, 3f, Color(0xFFA9D85A))
        rect(16f, -1f, 3f, 3f, Color(0xFFA9D85A))
        rect(10f, 2f, 5f, 1f, Color(0xFF376C22))
        rect(18f, 2f, 5f, 1f, Color(0xFF376C22))
        rect(23f, 8f, 3f, 2f, Color(0xFFFF94A7))
        rect(24f, 7f, 1f, 4f, Color(0xFFFF94A7))
        rect(24f, 8f, 1f, 1f, Color(0xFFFFEB72))
        rect(25f, 12f, 2f, 2f, Color(0xFF7AC7EE))
        rect(26f, 14f, 1f, 2f, Color(0xFF7AC7EE))
        rect(5f, 18f, 2f, 3f, Color(0xFF6AB43E))
        rect(6f, 17f, 3f, 1f, Color(0xFF8FD05A))
        rect(3f, 2f, 1f, 1f, Color(0xFFFFE76B))
        rect(4f, 1f, 1f, 3f, Color(0xFFFFE76B))
        rect(2f, 2f, 3f, 1f, Color(0xFFFFE76B))
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
        filterQuality = FilterQuality.Medium,
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
    userName: String,
    soilStatus: SoilStatus?,
    lightStatus: LightStatus?,
    thresholds: PlantThresholds?,
    affinity: AffinityState?,
    detailsVisible: Boolean,
    onToggleDetails: () -> Unit,
) {
    val coreStatus = plantCoreStatus(
        userName = userName,
        online = online,
        soilStatus = soilStatus,
        lightStatus = lightStatus,
        interactionSuitability = metrics.interactionSuitability,
    )
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelPanelFill,
        edge = CardBorder,
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("植物健康值", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Black, color = Ink)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HealthGauge(metrics.healthPercent, Modifier.size(118.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        coreStatus.text,
                        fontSize = SmartPotTypeScale.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = coreStatus.color,
                        lineHeight = 25.sp,
                    )
                    PixelTextButton(onClick = onToggleDetails, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)) {
                        Text(if (detailsVisible) "收起详情 ︿" else "健康详情 ›", fontSize = SmartPotTypeScale.bodyMedium, color = Color(0xFF087D3C), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (detailsVisible) {
                HorizontalDivider(color = CardBorder)
                Text("湿度 40% · 光照 40% · 互动 20%", fontSize = SmartPotTypeScale.labelSmall, color = Muted)
                Text(
                    "湿度 ${suitabilityLabel(metrics.soilSuitability)} · 光照 ${suitabilityLabel(metrics.lightSuitability)} · 互动 ${suitabilityLabel(metrics.interactionSuitability)}",
                    fontSize = SmartPotTypeScale.labelSmall,
                    color = Leaf,
                )
                thresholds?.let {
                    Text(
                        "适宜范围：湿度 ${it.soilMinPercent}-${it.soilMaxPercent}% · 光照 ${it.lightMinLux}-${it.lightMaxLux} lux",
                        fontSize = SmartPotTypeScale.labelSmall,
                        color = Muted,
                    )
                }
                affinity?.let {
                    val normalized = PlantRules.normalizeAffinity(it)
                    Text("好感度 ${normalized.score}/${PlantRules.maxAffinityPoints} · ${affinityLabel(normalized.level)}", fontSize = SmartPotTypeScale.labelSmall, color = Muted)
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
            val stroke = 10.dp.toPx()
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                Color(0xFFE7EAD9),
                startAngle = 132f,
                sweepAngle = 276f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                BrightLeaf,
                startAngle = 132f,
                sweepAngle = 276f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(healthPercent?.toString() ?: "--", fontSize = SmartPotTypeScale.headlineLarge, fontWeight = FontWeight.Bold, color = Ink)
            Text("/100", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
        }
    }
}

@Composable
private fun DashboardMetricCard(
    iconKind: String,
    iconColor: Color,
    title: String,
    value: String,
    unit: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    PixelPanel(
        modifier.height(108.dp),
        fill = PixelPanelFill,
        edge = CardBorder,
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 10.dp),
        showCornerBolts = false,
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PixelMetricGlyph(iconKind, iconColor, Modifier.size(24.dp))
                Text(title, color = Ink, fontSize = SmartPotTypeScale.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = when {
                        value.length >= 6 -> SmartPotTypeScale.titleMedium
                        value.length == 5 -> SmartPotTypeScale.titleLarge
                        value.length == 4 -> SmartPotTypeScale.titleLarge
                        else -> SmartPotTypeScale.headlineMedium
                    },
                    fontWeight = FontWeight.Black,
                    color = Ink,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    fontSize = if (unit.length > 1) SmartPotTypeScale.labelSmall else SmartPotTypeScale.bodyMedium,
                    color = Ink,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(
                status,
                color = metricStatusColor(status),
                fontSize = if (status.length > 6) SmartPotTypeScale.labelSmall else SmartPotTypeScale.bodyMedium,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PixelMetricGlyph(kind: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = 1.5.dp.toPx()
        when (kind) {
            "water" -> {
                val drop = Path().apply {
                    moveTo(center.x, 1.5.dp.toPx())
                    cubicTo(
                        size.width * 0.78f,
                        size.height * 0.38f,
                        size.width * 0.83f,
                        size.height * 0.72f,
                        center.x,
                        size.height - 2.dp.toPx(),
                    )
                    cubicTo(
                        size.width * 0.17f,
                        size.height * 0.72f,
                        size.width * 0.22f,
                        size.height * 0.38f,
                        center.x,
                        1.5.dp.toPx(),
                    )
                    close()
                }
                drawPath(drop, color)
                drawCircle(Color.White.copy(alpha = 0.72f), 2.dp.toPx(), Offset(size.width * 0.42f, size.height * 0.48f))
            }
            "sun" -> {
                drawCircle(color, size.minDimension * 0.24f, center)
                repeat(8) { index ->
                    rotate(index * 45f, center) {
                        drawLine(
                            color,
                            Offset(center.x, 1.dp.toPx()),
                            Offset(center.x, 5.dp.toPx()),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            "air" -> {
                repeat(3) { index ->
                    val y = size.height * (0.28f + index * 0.23f)
                    val path = Path().apply {
                        moveTo(1.dp.toPx(), y)
                        cubicTo(
                            size.width * 0.28f,
                            y - 3.dp.toPx(),
                            size.width * 0.42f,
                            y + 3.dp.toPx(),
                            size.width * 0.62f,
                            y,
                        )
                        cubicTo(
                            size.width * 0.77f,
                            y - 2.dp.toPx(),
                            size.width * 0.88f,
                            y - 2.dp.toPx(),
                            size.width - 1.dp.toPx(),
                            y,
                        )
                    }
                    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
                }
            }
            else -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(2.dp.toPx(), 3.dp.toPx()),
                    size = Size(size.width - 4.dp.toPx(), size.height - 8.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx(), 7.dp.toPx()),
                )
                val tail = Path().apply {
                    moveTo(size.width * 0.35f, size.height - 6.dp.toPx())
                    lineTo(size.width * 0.29f, size.height - 1.5.dp.toPx())
                    lineTo(size.width * 0.52f, size.height - 6.dp.toPx())
                    close()
                }
                drawPath(tail, color)
                drawCircle(Color.White, 1.3.dp.toPx(), Offset(size.width * 0.40f, size.height * 0.48f))
                drawCircle(Color.White, 1.3.dp.toPx(), Offset(size.width * 0.60f, size.height * 0.48f))
            }
        }
    }
}

@Composable
private fun CompanionScoreCard(metrics: DashboardMetrics) {
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelPanelFill,
        edge = CardBorder,
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("主人陪伴评分", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Black, color = Ink)
                Text(starScoreText(metrics.companionStars), color = Color(0xFF087D3C), fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Black)
            }
            StarRating(metrics.companionStars)
            Text(
                "今日浇水 ${metrics.dailyWaterCount} 次 · 触摸 ${metrics.dailyTouchCount} 次 · 对话 ${metrics.dailyDialogCount} 次",
                fontSize = SmartPotTypeScale.bodyMedium,
                color = Ink,
            )
        }
    }
}

@Composable
private fun StarRating(stars: Float) {
    val filled = (stars + 0.5f).toInt().coerceIn(0, 5)
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(5) { index ->
            Text(if (index < filled) "★" else "☆", color = Sun, fontSize = SmartPotTypeScale.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TodayLightIntegralCard(
    values: List<DeviceTelemetry>,
    latest: DeviceTelemetry?,
    timezone: String?,
    thresholds: PlantThresholds?,
    modifier: Modifier = Modifier,
) {
    val integral = remember(values, latest, timezone, thresholds) {
        calculateDailyLightIntegral(values, latest, timezone, thresholds)
    }
    val completion = integral.completionPercent.coerceIn(0, 100)
    PixelPanel(
        modifier.fillMaxWidth(),
        fill = PixelPanelFill,
        edge = CardBorder,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(26.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(Color(0xFFFFB13B), radius = 6.dp.toPx(), center = center)
                    repeat(8) { index ->
                        rotate(index * 45f, center) {
                            drawLine(
                                color = Color(0xFFFF8B24),
                                start = Offset(center.x, 1.dp.toPx()),
                                end = Offset(center.x, 5.dp.toPx()),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Square,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("今日光积分", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Black, color = Ink)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .background(Color(0xFFF2F1EC), RoundedCornerShape(5.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text("每小时更新", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.width(126.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.matchParentSize()) {
                            val stroke = 12.dp.toPx()
                            val diameter = size.minDimension - stroke
                            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                            val arcSize = Size(diameter, diameter)
                            val ambientSweep = (integral.ambientLuxHours.toFloat() / integral.targetLuxHours * 360f).coerceIn(0f, 360f)
                            val supplementSweep = (integral.supplementalLuxHours.toFloat() / integral.targetLuxHours * 360f)
                                .coerceIn(0f, 360f - ambientSweep)
                            drawArc(Color(0xFFE7E3D8), -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                            if (ambientSweep > 0f) {
                                drawArc(Color(0xFF55B8EA), -90f, ambientSweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                            }
                            if (supplementSweep > 0f) {
                                drawArc(Color(0xFFFFB347), -90f + ambientSweep, supplementSweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$completion%", color = Ink, fontSize = SmartPotTypeScale.headlineSmall, fontWeight = FontWeight.Black)
                            Text("${integral.totalLuxHours}/${integral.targetLuxHours}", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        LightCompositionLegend(Color(0xFF55B8EA), "环境光实测")
                        LightCompositionLegend(Color(0xFFFFB347), "补光估算")
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .background(Color(0xFFF8F8F6), RoundedCornerShape(6.dp))
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LightIntegralRow("有效光照时长", formatCompactLightDuration(integral.effectiveMinutes), Ink)
                    LightIntegralRow("光照累积量", "${integral.totalLuxHours} lux·h", Ink)
                    LightIntegralRow(
                        "补光建议时长",
                        if (integral.recommendedSupplementMinutes == 0) "已达标" else "还需${formatCompactLightDuration(integral.recommendedSupplementMinutes)}",
                        Ink,
                    )
                    LightIntegralRow("光照完成度", "$completion%", Ink)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(16) { index ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .background(
                                        if ((index + 1) * 100 <= completion * 16) BrightLeaf else SoftLeaf,
                                        RoundedCornerShape(2.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LightIntegralRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = SmartPotTypeScale.labelSmall, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = SmartPotTypeScale.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LightCompositionLegend(color: Color, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(7.dp))
        Text(label, color = Muted, fontSize = SmartPotTypeScale.labelSmall)
    }
}

@Composable
private fun DashboardAdviceCard(lines: List<String>, modifier: Modifier = Modifier) {
    DashboardTextCard(
        title = "养护建议",
        lines = lines.filter(String::isNotBlank).distinct().take(3).ifEmpty { listOf("正在等待植物品种档案") },
        warning = false,
        modifier = modifier,
    )
}

@Composable
private fun DashboardAttentionCard(
    snapshot: PotSnapshot?,
    weather: CareWeather?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val attentionLines = currentAttentionLines(snapshot, weather)
    DashboardTextCard(
        title = "需要关注",
        lines = attentionLines,
        warning = snapshot?.online == false ||
            snapshot?.evaluated?.soilStatus in setOf(SoilStatus.TOO_DRY, SoilStatus.TOO_WET) ||
            snapshot?.evaluated?.lightStatus in setOf(LightStatus.DARK, LightStatus.TOO_STRONG),
        modifier = modifier,
        compact = compact,
        fillContainer = compact,
    )
}

private fun speciesCareAdvice(snapshot: PotSnapshot?): List<String> {
    val species = snapshot?.pot?.species ?: return emptyList()
    val thresholds = species.thresholds
    return listOf(
        "${species.chineseName}适宜土壤湿度 ${thresholds.soilMinPercent}-${thresholds.soilMaxPercent}%，浇水前先确认表土干湿。",
        "适宜室内光照 ${thresholds.lightMinLux}-${thresholds.lightMaxLux} lux，优先保持稳定的散射光环境。",
        species.knowledge,
    )
}

private fun currentAttentionLines(snapshot: PotSnapshot?, weather: CareWeather?): List<String> {
    if (snapshot == null) return listOf("正在等待室内环境和室外天气数据")
    if (!snapshot.online) return listOf("设备当前离线，暂时无法综合室内环境，请检查网络连接")

    if (snapshot.telemetry == null) return listOf("正在等待室内光照和土壤湿度数据")
    val evaluated = snapshot.evaluated ?: return listOf("正在分析当前室内环境")
    val weatherCondition = weather?.condition.orEmpty()
    val rainy = listOf("雨", "雪", "雷", "雾").any(weatherCondition::contains)
    val cloudy = rainy || weatherCondition.contains("阴") || weatherCondition.contains("云")
    val sunny = weatherCondition.contains("晴")
    val hot = (weather?.temperatureC ?: 0.0) >= 28.0
    val humid = (weather?.relativeHumidityPercent ?: 0) >= 75

    return buildList {
        when {
            evaluated.soilStatus == SoilStatus.TOO_WET && (rainy || humid) ->
                add("阴雨潮湿且盆土偏湿，建议停水通风。")
            evaluated.soilStatus == SoilStatus.TOO_DRY && (rainy || humid) ->
                add("室外潮湿但盆土偏干，建议少量补水。")
            evaluated.soilStatus == SoilStatus.TOO_DRY && hot ->
                add("天气较热且盆土偏干，建议早晚补水。")
            evaluated.soilStatus == SoilStatus.TOO_DRY && sunny ->
                add("天气晴朗且盆土偏干，建议适量补水。")
            evaluated.soilStatus == SoilStatus.TOO_DRY ->
                add("盆土偏干，建议检查后适量补水。")
            evaluated.soilStatus == SoilStatus.TOO_WET ->
                add("盆土偏湿，今天先停水并保持通风。")
        }
        when {
            evaluated.lightStatus == LightStatus.DARK && cloudy ->
                add("阴天室内缺光，建议开启补光灯。")
            evaluated.lightStatus == LightStatus.DARK && sunny ->
                add("室外晴朗但室内缺光，建议移近窗边。")
            evaluated.lightStatus == LightStatus.DARK ->
                add("室内缺光，建议移近窗边或开启补光。")
            evaluated.lightStatus == LightStatus.TOO_STRONG && (hot || sunny) ->
                add("天气晴热且室内光强，建议及时遮阴。")
            evaluated.lightStatus == LightStatus.TOO_STRONG ->
                add("室内光照过强，建议移到散射光处。")
        }
        if (isEmpty()) {
            when {
                rainy || humid -> add("天气较潮湿，建议保持通风并少浇水。")
                hot -> add("天气较热，建议留意盆土干燥变化。")
                else -> add("当前环境适宜，继续保持即可。")
            }
        }
    }.distinct().take(2)
}

@Composable
private fun DashboardTextCard(
    title: String,
    lines: List<String>,
    warning: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fillContainer: Boolean = false,
) {
    PixelPanel(
        modifier.fillMaxWidth(),
        fill = PixelPanelFill,
        edge = CardBorder,
        contentPadding = if (compact) PaddingValues(8.dp) else PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        showCornerBolts = false,
        fillContainer = fillContainer,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp)) {
            Text(title, fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Black, color = Ink)
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp)) {
                    Text(if (warning) "△" else "•", color = if (warning) Color(0xFFFF5A5F) else Leaf, fontWeight = FontWeight.Black)
                    Text(
                        line,
                        fontSize = if (compact) SmartPotTypeScale.labelSmall else SmartPotTypeScale.bodyMedium,
                        color = Ink,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CareScreen(
    state: SmartPotUiState,
    addCare: (CareType, String, String?) -> Unit,
    deleteCare: (String) -> Unit,
    saveDiary: (String, String, List<String>, String?, String?) -> Unit,
    deleteDiary: (PlantDiary) -> Unit,
    speakDiary: (PlantDiary) -> Unit,
    stopDiarySpeech: () -> Unit,
) {
    val context = LocalContext.current
    var note by rememberSaveable { mutableStateOf("") }
    var timelineExpanded by remember(state.selectedPotId) { mutableStateOf(false) }
    var diariesExpanded by rememberSaveable { mutableStateOf(false) }
    var addRecordVisible by rememberSaveable { mutableStateOf(false) }
    var recordImageDataUrl by remember { mutableStateOf<String?>(null) }
    var selectedCareType by remember { mutableStateOf<CareType?>(null) }
    var affinityImpactExpanded by rememberSaveable { mutableStateOf(false) }
    val careActions = listOf(CareType.WATER, CareType.FERTILIZE, CareType.PRUNE, CareType.REPOT, CareType.NEW_LEAF)
    val metrics = dashboardMetrics(state)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedShortcut by rememberSaveable { mutableStateOf("affinity") }
    fun scrollToSection(section: String, index: Int) {
        selectedShortcut = section
        scope.launch { listState.animateScrollToItem(index) }
    }
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.care_page_background),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp),
        ) {
            item {
                CarePageHeader()
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CareSectionShortcut(
                        icon = "heart",
                        label = "好感度",
                        selected = selectedShortcut == "affinity",
                        modifier = Modifier.weight(1f),
                        onClick = { scrollToSection("affinity", 2) },
                    )
                    CareSectionShortcut(
                        icon = "timeline",
                        label = "时间轴",
                        selected = selectedShortcut == "timeline",
                        modifier = Modifier.weight(1f),
                        onClick = { scrollToSection("timeline", 3) },
                    )
                    CareSectionShortcut(
                        icon = "diary",
                        label = "养护日记",
                        selected = selectedShortcut == "diary",
                        modifier = Modifier.weight(1f),
                        onClick = { scrollToSection("diary", if (addRecordVisible) 5 else 4) },
                    )
                }
            }
            item {
                CareAffinityHeader(
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
                    onAddRecord = {
                        addRecordVisible = !addRecordVisible
                        if (!addRecordVisible) {
                            selectedCareType = null
                            recordImageDataUrl = null
                            note = ""
                        }
                    },
                    onDeleteRecord = deleteCare,
                )
            }
            if (addRecordVisible) {
                item {
                    AddCareRecordCard(
                        note = note,
                        onNoteChange = { note = it },
                        imageDataUrl = recordImageDataUrl,
                        onImageChange = { recordImageDataUrl = it },
                        actions = careActions,
                        selectedType = selectedCareType,
                        onSelectType = { selectedCareType = it },
                        onConfirm = {
                            selectedCareType?.let { type ->
                                addCare(type, note, recordImageDataUrl)
                                note = ""
                                recordImageDataUrl = null
                                selectedCareType = null
                                addRecordVisible = false
                            }
                        },
                        onDismiss = {
                            note = ""
                            recordImageDataUrl = null
                            selectedCareType = null
                            addRecordVisible = false
                        },
                    )
                }
            }
            item {
                CareDiarySection(
                    state = state,
                    expanded = diariesExpanded,
                    onToggleExpanded = { diariesExpanded = !diariesExpanded },
                    saveDiary = saveDiary,
                    deleteDiary = deleteDiary,
                    speakDiary = speakDiary,
                    stopDiarySpeech = stopDiarySpeech,
                )
            }
        }
    }
}

@Composable
private fun CarePageHeader() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌁", color = Color(0xFF8FA86A), fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        Text("养护", color = Color(0xFF304A1D), fontSize = SmartPotTypeScale.headlineMedium, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(16.dp))
        Text("⌁", color = Color(0xFF8FA86A), fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CareSectionShortcut(
    icon: String,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val edge = if (selected) Color(0xFFA7BE72) else CardBorder
    Box(
        modifier
            .height(96.dp)
            .background(Color(0xFFFFFCF4), RoundedCornerShape(12.dp))
            .border(1.dp, edge, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CareShortcutIcon(icon, Modifier.size(44.dp))
            Text(label, color = Color(0xFF304A1D), fontSize = SmartPotTypeScale.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CareShortcutIcon(kind: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        when (kind) {
            "heart" -> {
                val heart = Path().apply {
                    moveTo(center.x, size.height * 0.82f)
                    cubicTo(size.width * 0.18f, size.height * 0.62f, size.width * 0.08f, size.height * 0.27f, size.width * 0.31f, size.height * 0.20f)
                    cubicTo(size.width * 0.43f, size.height * 0.16f, center.x, size.height * 0.25f, center.x, size.height * 0.31f)
                    cubicTo(center.x, size.height * 0.25f, size.width * 0.57f, size.height * 0.16f, size.width * 0.69f, size.height * 0.20f)
                    cubicTo(size.width * 0.92f, size.height * 0.27f, size.width * 0.82f, size.height * 0.62f, center.x, size.height * 0.82f)
                    close()
                }
                drawPath(heart, Color(0xFFFF8C86))
                drawPath(heart, Color(0xFFDA6964), style = Stroke(1.2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.66f), 3.dp.toPx(), Offset(size.width * 0.34f, size.height * 0.31f))
            }
            "timeline" -> {
                drawRoundRect(
                    Color(0xFF9BC77C),
                    topLeft = Offset(size.width * 0.14f, size.height * 0.16f),
                    size = Size(size.width * 0.65f, size.height * 0.59f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                )
                drawRect(Color(0xFFEAF5DC), Offset(size.width * 0.19f, size.height * 0.30f), Size(size.width * 0.55f, size.height * 0.38f))
                drawLine(Color(0xFF54854A), Offset(size.width * 0.29f, size.height * 0.08f), Offset(size.width * 0.29f, size.height * 0.27f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(Color(0xFF54854A), Offset(size.width * 0.62f, size.height * 0.08f), Offset(size.width * 0.62f, size.height * 0.27f), 2.dp.toPx(), StrokeCap.Round)
                drawCircle(Color(0xFFFFFCF4), size.width * 0.22f, Offset(size.width * 0.72f, size.height * 0.70f))
                drawCircle(Color(0xFF6A9660), size.width * 0.20f, Offset(size.width * 0.72f, size.height * 0.70f), style = Stroke(1.5.dp.toPx()))
                drawLine(Color(0xFF6A9660), Offset(size.width * 0.72f, size.height * 0.70f), Offset(size.width * 0.72f, size.height * 0.59f), 1.5.dp.toPx(), StrokeCap.Round)
                drawLine(Color(0xFF6A9660), Offset(size.width * 0.72f, size.height * 0.70f), Offset(size.width * 0.80f, size.height * 0.74f), 1.5.dp.toPx(), StrokeCap.Round)
            }
            else -> {
                drawRoundRect(
                    Color(0xFFA9D4F4),
                    topLeft = Offset(size.width * 0.22f, size.height * 0.10f),
                    size = Size(size.width * 0.60f, size.height * 0.76f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                )
                drawRoundRect(
                    Color(0xFFEAF7FF),
                    topLeft = Offset(size.width * 0.30f, size.height * 0.18f),
                    size = Size(size.width * 0.43f, size.height * 0.59f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
                repeat(3) { index ->
                    val y = size.height * (0.34f + index * 0.13f)
                    drawLine(Color(0xFF4D8EBB), Offset(size.width * 0.39f, y), Offset(size.width * 0.66f, y), 1.5.dp.toPx(), StrokeCap.Round)
                }
                repeat(4) { index ->
                    val y = size.height * (0.22f + index * 0.16f)
                    drawLine(Color(0xFF5F8CAC), Offset(size.width * 0.14f, y), Offset(size.width * 0.29f, y), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun CareAffinityHeader(
    state: SmartPotUiState,
    metrics: DashboardMetrics,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val affinity = PlantRules.normalizeAffinity(state.snapshot?.affinity ?: AffinityState())
    val level = affinityLevelNumber(affinity.score)
    val levelProgress = affinityLevelProgress(affinity.score)
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = Color.White,
        edge = CardBorder,
        contentPadding = PaddingValues(0.dp),
        showCornerBolts = false,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 174.dp)) {
            Image(
                painter = painterResource(R.drawable.care_affinity_background),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            )
            Box(Modifier.matchParentSize().background(Color(0xFFFFFCF1).copy(alpha = 0.54f)))
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("好感度等级", fontSize = SmartPotTypeScale.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("Lv. $level", fontSize = SmartPotTypeScale.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
                    PixelProgressBar(levelProgress, Modifier.fillMaxWidth())
                    Text(
                        if (level >= 30) {
                            "好感度已达到最高等级（最高等级为30级）"
                        } else {
                            "距离下一级还需 ${affinityPointsToNextLevel(affinity.score)} 点好感度（最高等级为30级）"
                        },
                        fontSize = SmartPotTypeScale.labelSmall,
                        color = Color(0xFF5C513D),
                    )
                    AffinityImpactContent(state, metrics, expanded, onToggle)
                }
                Spacer(Modifier.width(118.dp))
            }
        }
    }
}

@Composable
private fun AffinityImpactContent(
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
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 3.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("今日好感影响因素", fontSize = SmartPotTypeScale.labelSmall, fontWeight = FontWeight.Bold, color = Ink)
            Text(if (expanded) "︿" else "﹀", color = Leaf, fontWeight = FontWeight.Bold, fontSize = SmartPotTypeScale.bodySmall)
        }
        if (expanded) {
            Text(
                "加分：${positive.ifEmpty { listOf("暂无加分记录") }.joinToString(" · ")}",
                fontSize = SmartPotTypeScale.labelSmall,
                color = BrightLeaf,
            )
            Text(
                "扣分：${negative.ifEmpty { listOf("暂无扣分项") }.joinToString(" · ")}",
                fontSize = SmartPotTypeScale.labelSmall,
                color = if (negative.isEmpty()) Muted else Color(0xFFD45A52),
            )
        }
    }
}

@Composable
private fun AddCareRecordCard(
    note: String,
    onNoteChange: (String) -> Unit,
    imageDataUrl: String?,
    onImageChange: (String?) -> Unit,
    actions: List<CareType>,
    selectedType: CareType?,
    onSelectType: (CareType) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            encodeDiaryPhoto(context, uri)?.let(onImageChange)
        }
    }
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelCream,
        edge = CardBorder,
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("添加养护记录", fontWeight = FontWeight.Bold, color = Ink)
                PixelTextButton(onClick = onDismiss) { Text("关闭") }
            }
            PixelTextField(
                value = note,
                onValueChange = onNoteChange,
                label = "记录今天发生的事",
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("记录图片", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                PixelOutlinedButton(
                    onClick = { imagePicker.launch("image/*") },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(if (imageDataUrl == null) "选择图片" else "更换图片", fontSize = SmartPotTypeScale.labelSmall)
                }
            }
            imageDataUrl?.let { dataUrl ->
                Box(Modifier.size(width = 104.dp, height = 78.dp)) {
                    DiaryPhoto(dataUrl, Modifier.fillMaxSize())
                    PixelButton(
                        onClick = { onImageChange(null) },
                        modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                        fill = PixelDanger,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("×", color = Color.White, fontSize = SmartPotTypeScale.bodyMedium)
                    }
                }
            }
            actions.chunked(3).forEach { rowActions ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    rowActions.forEach { type ->
                        PixelButton(
                            selected = selectedType == type,
                            onClick = { onSelectType(type) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp),
                        ) {
                            CareTypeIcon(type, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(careLabel(type), fontSize = SmartPotTypeScale.bodySmall, maxLines = 1)
                        }
                    }
                    repeat(3 - rowActions.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(
                selectedType?.let { "已选择：${careLabel(it)}，可继续填写备注或添加图片。" } ?: "请先选择记录类型。",
                color = if (selectedType == null) Muted else Leaf,
                fontSize = SmartPotTypeScale.labelSmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PixelOutlinedButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                PixelButton(onClick = onConfirm, enabled = selectedType != null) { Text("确定添加") }
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
    onDeleteRecord: (String) -> Unit,
) {
    val events = growthTimeline(state)
    val visibleEvents = if (expanded) events else events.take(3)
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelCream,
        edge = CardBorder,
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("成长时间轴", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                PixelTextButton(onClick = onAddRecord, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("＋ 添加记录", fontSize = SmartPotTypeScale.bodySmall) }
            }
            if (visibleEvents.isEmpty()) {
                Text("还没有成长记录", color = Muted, fontSize = SmartPotTypeScale.bodySmall)
            } else {
                visibleEvents.forEachIndexed { index, event ->
                    SwipeToDeleteTimelineEvent(
                        event = event,
                        showConnector = index < visibleEvents.lastIndex,
                        onDelete = onDeleteRecord,
                    )
                }
            }
            if (events.size > 3) {
                HorizontalDivider(color = CardBorder)
                PixelTextButton(onClick = onToggleExpanded, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) "收起记录 ︿" else "查看全部记录 ›", fontSize = SmartPotTypeScale.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SwipeToDeleteTimelineEvent(
    event: GrowthTimelineEvent,
    showConnector: Boolean,
    onDelete: (String) -> Unit,
) {
    val careLogId = event.careLogId
    val density = LocalDensity.current
    val deleteWidth = 76.dp
    val deleteWidthPx = with(density) { deleteWidth.toPx() }
    var dragOffset by remember(careLogId) { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .background(Color(0xFFFBE4DF), RoundedCornerShape(8.dp)),
    ) {
        if (careLogId != null) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(deleteWidth)
                    .height(70.dp)
                    .background(
                        PixelDanger,
                        RoundedCornerShape(topStart = 0.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 0.dp),
                    )
                    .clickable {
                        dragOffset = 0f
                        onDelete(careLogId)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("删除", color = Color.White, fontSize = SmartPotTypeScale.bodySmall, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(dragOffset.roundToInt(), 0) }
                .background(PixelCream)
                .then(
                    if (careLogId == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(careLogId) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    dragOffset = if (dragOffset <= -deleteWidthPx / 2f) -deleteWidthPx else 0f
                                },
                                onDragCancel = { dragOffset = 0f },
                            ) { change, amount ->
                                dragOffset = (dragOffset + amount).coerceIn(-deleteWidthPx, 0f)
                                change.consume()
                            }
                        }
                    },
                )
                .heightIn(min = 70.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.width(32.dp).height(72.dp), contentAlignment = Alignment.TopCenter) {
                if (showConnector) {
                    Box(Modifier.padding(top = 27.dp).width(2.dp).height(52.dp).background(CardBorder))
                }
                CareTypeIcon(event.type, Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(start = 5.dp, top = 1.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(event.date, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = SmartPotTypeScale.bodyMedium)
                Text(event.title, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = SmartPotTypeScale.bodyMedium)
                if (event.detail.isNotBlank()) {
                    Text(event.detail, fontSize = SmartPotTypeScale.labelSmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            CareEventThumbnail(event, Modifier.padding(start = 8.dp).size(width = 68.dp, height = 58.dp))
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
    if (event.imageDataUrl != null) {
        DiaryPhoto(event.imageDataUrl, modifier)
    } else {
        Box(
            modifier
                .background(background, RoundedCornerShape(8.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CareTypeIcon(event.type, Modifier.size(34.dp))
        }
    }
}

@Composable
private fun CareTypeIcon(type: CareType, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = (size.minDimension * 0.09f).coerceAtLeast(1.5.dp.toPx())
        when (type) {
            CareType.WATER -> {
                val drop = Path().apply {
                    moveTo(center.x, size.height * 0.06f)
                    cubicTo(size.width * 0.78f, size.height * 0.37f, size.width * 0.82f, size.height * 0.72f, center.x, size.height * 0.94f)
                    cubicTo(size.width * 0.18f, size.height * 0.72f, size.width * 0.22f, size.height * 0.37f, center.x, size.height * 0.06f)
                    close()
                }
                drawPath(drop, Color(0xFF59B9EB))
                drawCircle(Color.White.copy(alpha = 0.75f), size.minDimension * 0.08f, Offset(size.width * 0.42f, size.height * 0.47f))
            }
            CareType.PRUNE -> {
                val dark = Color(0xFF59605D)
                drawCircle(dark, size.minDimension * 0.16f, Offset(size.width * 0.27f, size.height * 0.30f), style = Stroke(stroke))
                drawCircle(dark, size.minDimension * 0.16f, Offset(size.width * 0.27f, size.height * 0.70f), style = Stroke(stroke))
                drawLine(dark, Offset(size.width * 0.39f, size.height * 0.40f), Offset(size.width * 0.88f, size.height * 0.78f), stroke, StrokeCap.Round)
                drawLine(dark, Offset(size.width * 0.39f, size.height * 0.60f), Offset(size.width * 0.88f, size.height * 0.22f), stroke, StrokeCap.Round)
                drawCircle(Color(0xFF8F9692), size.minDimension * 0.07f, center)
            }
            CareType.NEW_LEAF -> {
                val green = Color(0xFF79B943)
                drawLine(Color(0xFF4F8E38), Offset(center.x, size.height * 0.92f), Offset(center.x, size.height * 0.38f), stroke * 0.7f, StrokeCap.Round)
                val leftLeaf = Path().apply {
                    moveTo(center.x, size.height * 0.57f)
                    quadraticBezierTo(size.width * 0.12f, size.height * 0.16f, size.width * 0.08f, size.height * 0.42f)
                    quadraticBezierTo(size.width * 0.18f, size.height * 0.72f, center.x, size.height * 0.57f)
                    close()
                }
                val rightLeaf = Path().apply {
                    moveTo(center.x, size.height * 0.49f)
                    quadraticBezierTo(size.width * 0.88f, size.height * 0.10f, size.width * 0.92f, size.height * 0.37f)
                    quadraticBezierTo(size.width * 0.82f, size.height * 0.66f, center.x, size.height * 0.49f)
                    close()
                }
                drawPath(leftLeaf, green)
                drawPath(rightLeaf, Color(0xFF94CB55))
            }
            CareType.REPOT -> {
                drawRect(Color(0xFF6FAE4D), Offset(size.width * 0.46f, size.height * 0.08f), Size(size.width * 0.08f, size.height * 0.40f))
                drawOval(Color(0xFF8DCB5B), Offset(size.width * 0.18f, size.height * 0.12f), Size(size.width * 0.33f, size.height * 0.28f))
                drawOval(Color(0xFF79B94C), Offset(size.width * 0.49f, size.height * 0.12f), Size(size.width * 0.33f, size.height * 0.28f))
                val pot = Path().apply {
                    moveTo(size.width * 0.20f, size.height * 0.48f)
                    lineTo(size.width * 0.80f, size.height * 0.48f)
                    lineTo(size.width * 0.69f, size.height * 0.90f)
                    lineTo(size.width * 0.31f, size.height * 0.90f)
                    close()
                }
                drawPath(pot, Color(0xFFC77B3E))
                drawRect(Color(0xFFE19B58), Offset(size.width * 0.15f, size.height * 0.43f), Size(size.width * 0.70f, size.height * 0.13f))
            }
            CareType.FERTILIZE -> {
                drawRoundRect(
                    Color(0xFFB99A66),
                    Offset(size.width * 0.20f, size.height * 0.16f),
                    Size(size.width * 0.60f, size.height * 0.70f),
                    androidx.compose.ui.geometry.CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                )
                drawCircle(Color(0xFF7DAE50), size.minDimension * 0.15f, center)
                drawLine(Color.White, Offset(center.x, size.height * 0.43f), Offset(center.x, size.height * 0.64f), stroke * 0.55f, StrokeCap.Round)
            }
            CareType.OTHER -> {
                drawRoundRect(
                    Color(0xFFA9D4F4),
                    Offset(size.width * 0.20f, size.height * 0.12f),
                    Size(size.width * 0.60f, size.height * 0.76f),
                    androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
                repeat(3) { index ->
                    val y = size.height * (0.34f + index * 0.16f)
                    drawLine(Color.White, Offset(size.width * 0.32f, y), Offset(size.width * 0.68f, y), stroke * 0.55f, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun CareDiarySection(
    state: SmartPotUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    saveDiary: (String, String, List<String>, String?, String?) -> Unit,
    deleteDiary: (PlantDiary) -> Unit,
    speakDiary: (PlantDiary) -> Unit,
    stopDiarySpeech: () -> Unit,
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
    var authorName by rememberSaveable { mutableStateOf("") }
    var mood by rememberSaveable { mutableStateOf<String?>(null) }
    var imageDataUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<PlantDiary?>(null) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && imageDataUrls.size < 3) {
            encodeDiaryPhoto(context, uri)?.let { encoded -> imageDataUrls = imageDataUrls + encoded }
        }
    }

    fun openEditor() {
        title = todayDiary?.title ?: "今天的小麦"
        content = todayDiary?.content ?: ""
        authorName = todayDiary?.authorName.orEmpty()
        mood = todayDiary?.moodEmoji
        imageDataUrls = todayDiary?.imageDataUrls.orEmpty()
        editorVisible = true
    }

    pendingDelete?.let { diary ->
        PixelConfirmDialog(
            title = "删除这篇日记？",
            text = "删除后无法恢复。小麦写的日记不会受到影响。",
            confirmText = "删除",
            onConfirm = {
                pendingDelete = null
                deleteDiary(diary)
            },
            onDismiss = { pendingDelete = null },
            danger = true,
        )
    }

    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelCream,
        edge = CardBorder,
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("养护日记", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                PixelTextButton(
                    onClick = { if (editorVisible) editorVisible = false else openEditor() },
                    contentPadding = PaddingValues(horizontal = 5.dp),
                ) { Text(if (editorVisible) "取消" else "＋ 写日记", fontSize = SmartPotTypeScale.bodySmall) }
            }
            if (editorVisible) {
                PixelPanel(
                    Modifier.fillMaxWidth(),
                    fill = Color(0xFFFFFDF0),
                    edge = CardBorder,
                    contentPadding = PaddingValues(10.dp),
                    showCornerBolts = false,
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        PixelTextField(
                            value = title,
                            onValueChange = { title = it.take(60) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "日记标题",
                            singleLine = true,
                        )
                        PixelTextField(
                            value = authorName,
                            onValueChange = { authorName = it.take(20) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "署名（可选）",
                            placeholder = "例如：小雨、植物主人",
                            supportingText = "留空时显示“用户”",
                            singleLine = true,
                        )
                        PixelTextField(
                            value = content,
                            onValueChange = { content = it.take(1000) },
                            modifier = Modifier.fillMaxWidth(),
                            label = "记录今天和小麦的故事",
                            minLines = 3,
                            maxLines = 6,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("开心", "新叶", "浇水", "晴天", "喜欢", "睡觉")) { tag ->
                                PixelButton(
                                    selected = mood == tag,
                                    onClick = { mood = tag.takeUnless { mood == tag } },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                                ) { Text(tag, fontSize = SmartPotTypeScale.labelSmall) }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("成长照片 ${imageDataUrls.size}/3", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                            PixelOutlinedButton(
                                onClick = { imagePicker.launch("image/*") },
                                enabled = imageDataUrls.size < 3,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) { Text("上传照片", fontSize = SmartPotTypeScale.labelSmall) }
                        }
                        if (imageDataUrls.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(imageDataUrls) { imageDataUrl ->
                                    Box {
                                        DiaryPhoto(imageDataUrl, Modifier.size(76.dp))
                                        PixelButton(
                                            onClick = { imageDataUrls = imageDataUrls - imageDataUrl },
                                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                                            fill = PixelDanger,
                                            contentPadding = PaddingValues(0.dp),
                                        ) { Text("×", color = Color.White, fontSize = SmartPotTypeScale.bodyMedium) }
                                    }
                                }
                            }
                        }
                        PixelButton(
                            onClick = {
                                saveDiary(title.trim(), content.trim(), imageDataUrls, mood, authorName.trim().ifBlank { null })
                                editorVisible = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = title.isNotBlank() && content.isNotBlank(),
                        ) { Text(if (todayDiary == null) "保存日记" else "更新今日日记") }
                    }
                }
            }
            if (visibleDiaries.isEmpty()) {
                Text("今天还没有日记，写一篇记录小麦的变化吧。", color = Muted, fontSize = SmartPotTypeScale.bodySmall)
            } else {
                visibleDiaries.forEachIndexed { index, diary ->
                    if (index > 0) HorizontalDivider(color = CardBorder)
                    CareDiaryEntry(
                        diary = diary,
                        weather = state.careOverview?.weather?.takeIf { it.date == diary.diaryDate },
                        onSpeak = { speakDiary(diary) },
                        onStopSpeaking = stopDiarySpeech,
                        onDelete = { pendingDelete = diary },
                    )
                }
            }
            if (diaries.size > 2) {
                HorizontalDivider(color = CardBorder)
                PixelTextButton(onClick = onToggleExpanded, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) "收起日记 ︿" else "查看全部日记 ›", fontSize = SmartPotTypeScale.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CareDiaryEntry(
    diary: PlantDiary,
    weather: CareWeather?,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(diary.id) { mutableStateOf(false) }
    val displayContent = diaryDisplayContent(diary)
    val canExpand = displayContent.length > 90 || displayContent.count { it == '\n' } >= 3
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(diary.diaryDate, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = SmartPotTypeScale.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                if (diary.author == DiaryAuthor.WHEAT) "小麦" else diary.authorName?.takeIf(String::isNotBlank) ?: "用户",
                color = Leaf,
                fontSize = SmartPotTypeScale.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(weather?.condition ?: diary.title, color = Muted, fontSize = SmartPotTypeScale.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            DiaryMoodIcon(diary, Modifier.size(22.dp))
            Text(
                "朗读",
                modifier = Modifier.clickable(onClick = onSpeak).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Leaf,
                fontSize = SmartPotTypeScale.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "停止",
                modifier = Modifier.clickable(onClick = onStopSpeaking).padding(horizontal = 6.dp, vertical = 2.dp),
                color = PixelDanger,
                fontSize = SmartPotTypeScale.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (diary.author == DiaryAuthor.USER) {
                PixelTextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    danger = true,
                ) { Text("删除", fontSize = SmartPotTypeScale.labelSmall) }
            }
        }
        Text(
            displayContent,
            fontSize = SmartPotTypeScale.bodySmall,
            color = Color(0xFF4D534E),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (canExpand) {
            Text(
                if (expanded) "收起" else "展开全文",
                modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 3.dp),
                color = Leaf,
                fontSize = SmartPotTypeScale.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (diary.author == DiaryAuthor.USER && diary.imageDataUrls.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(diary.imageDataUrls) { imageDataUrl -> DiaryPhoto(imageDataUrl, Modifier.size(88.dp)) }
            }
        }
    }
}

private enum class DiaryMoodKind {
    WATER,
    SPROUT,
    SUN,
    HEART,
    SLEEP,
    NOTE,
}

@Composable
private fun DiaryMoodIcon(diary: PlantDiary, modifier: Modifier = Modifier) {
    val kind = diaryMoodKind(diary)
    Box(
        modifier.padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val stroke = (size.minDimension * 0.1f).coerceAtLeast(1.5f)
            when (kind) {
                DiaryMoodKind.WATER -> {
                    val drop = Path().apply {
                        moveTo(w * 0.5f, h * 0.08f)
                        cubicTo(w * 0.42f, h * 0.28f, w * 0.2f, h * 0.5f, w * 0.2f, h * 0.67f)
                        cubicTo(w * 0.2f, h * 0.88f, w * 0.34f, h * 0.96f, w * 0.5f, h * 0.96f)
                        cubicTo(w * 0.66f, h * 0.96f, w * 0.8f, h * 0.88f, w * 0.8f, h * 0.67f)
                        cubicTo(w * 0.8f, h * 0.5f, w * 0.58f, h * 0.28f, w * 0.5f, h * 0.08f)
                        close()
                    }
                    drawPath(drop, Color(0xFF65BDF2))
                    drawCircle(Color(0xFFCDEEFF), w * 0.08f, Offset(w * 0.38f, h * 0.66f))
                }
                DiaryMoodKind.SPROUT -> {
                    drawLine(Color(0xFF579451), Offset(w * 0.5f, h * 0.9f), Offset(w * 0.5f, h * 0.42f), stroke)
                    val leftLeaf = Path().apply {
                        moveTo(w * 0.48f, h * 0.58f)
                        cubicTo(w * 0.12f, h * 0.58f, w * 0.12f, h * 0.2f, w * 0.18f, h * 0.18f)
                        cubicTo(w * 0.43f, h * 0.2f, w * 0.5f, h * 0.4f, w * 0.48f, h * 0.58f)
                        close()
                    }
                    val rightLeaf = Path().apply {
                        moveTo(w * 0.52f, h * 0.48f)
                        cubicTo(w * 0.58f, h * 0.2f, w * 0.86f, h * 0.14f, w * 0.9f, h * 0.18f)
                        cubicTo(w * 0.88f, h * 0.44f, w * 0.7f, h * 0.56f, w * 0.52f, h * 0.48f)
                        close()
                    }
                    drawPath(leftLeaf, Color(0xFF8BCB62))
                    drawPath(rightLeaf, Color(0xFF70B84B))
                }
                DiaryMoodKind.SUN -> {
                    drawCircle(Color(0xFFFFB83E), w * 0.25f, Offset(w * 0.5f, h * 0.5f))
                    repeat(8) { index ->
                        val angle = Math.toRadians(index * 45.0)
                        val x1 = w * 0.5f + kotlin.math.cos(angle).toFloat() * w * 0.34f
                        val y1 = h * 0.5f + kotlin.math.sin(angle).toFloat() * h * 0.34f
                        val x2 = w * 0.5f + kotlin.math.cos(angle).toFloat() * w * 0.46f
                        val y2 = h * 0.5f + kotlin.math.sin(angle).toFloat() * h * 0.46f
                        drawLine(Color(0xFFF39A2D), Offset(x1, y1), Offset(x2, y2), stroke)
                    }
                }
                DiaryMoodKind.HEART -> {
                    val heart = Path().apply {
                        moveTo(w * 0.5f, h * 0.9f)
                        cubicTo(w * 0.38f, h * 0.76f, w * 0.12f, h * 0.58f, w * 0.12f, h * 0.34f)
                        cubicTo(w * 0.12f, h * 0.08f, w * 0.42f, h * 0.08f, w * 0.5f, h * 0.3f)
                        cubicTo(w * 0.58f, h * 0.08f, w * 0.88f, h * 0.08f, w * 0.88f, h * 0.34f)
                        cubicTo(w * 0.88f, h * 0.58f, w * 0.62f, h * 0.76f, w * 0.5f, h * 0.9f)
                        close()
                    }
                    drawPath(heart, Color(0xFFF27C86))
                }
                DiaryMoodKind.SLEEP -> {
                    drawCircle(Color(0xFF7D82C8), w * 0.36f, Offset(w * 0.48f, h * 0.48f))
                    drawCircle(Color(0xFFFFFBEC), w * 0.36f, Offset(w * 0.64f, h * 0.36f))
                    drawCircle(Color(0xFFFFD65A), w * 0.07f, Offset(w * 0.25f, h * 0.2f))
                }
                DiaryMoodKind.NOTE -> {
                    drawRoundRect(
                        color = Color(0xFF8EC5A0),
                        topLeft = Offset(w * 0.2f, h * 0.12f),
                        size = Size(w * 0.64f, h * 0.76f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f),
                    )
                    drawLine(Color.White, Offset(w * 0.34f, h * 0.36f), Offset(w * 0.72f, h * 0.36f), stroke * 0.65f)
                    drawLine(Color.White, Offset(w * 0.34f, h * 0.52f), Offset(w * 0.72f, h * 0.52f), stroke * 0.65f)
                    drawLine(Color.White, Offset(w * 0.34f, h * 0.68f), Offset(w * 0.62f, h * 0.68f), stroke * 0.65f)
                    drawLine(Color(0xFF638D6D), Offset(w * 0.28f, h * 0.08f), Offset(w * 0.28f, h * 0.92f), stroke)
                }
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
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelCream,
        edge = CardBorder,
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("今日天气", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                Text("${weatherEmoji(weather?.condition)} ${weather?.condition ?: "等待数据"}", color = Leaf, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EnvironmentStat("温度", weather?.temperatureC?.let { "${it.roundToInt()}°C" } ?: "--", Modifier.weight(1f))
                VerticalDivider(Modifier.height(42.dp), color = CardBorder)
                EnvironmentStat("空气湿度", weather?.relativeHumidityPercent?.let { "$it%" } ?: "--", Modifier.weight(1f))
                VerticalDivider(Modifier.height(42.dp), color = CardBorder)
                EnvironmentStat("环境状态", environmentStatus, Modifier.weight(1f))
            }
            weather?.hint?.takeIf(String::isNotBlank)?.let { Text(it, color = Muted, fontSize = SmartPotTypeScale.labelSmall) }
            if (weather?.source == "OPEN_METEO") Text("实时天气 · Open-Meteo", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
        }
    }
}

@Composable
private fun EnvironmentStat(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Muted, fontSize = SmartPotTypeScale.labelSmall)
        Text(value, color = Ink, fontSize = SmartPotTypeScale.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun UserProfileDialog(
    initialName: String,
    initialUserId: String,
    initialAvatarDataUrl: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String?) -> Unit,
) {
    val context = LocalContext.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    var userId by remember(initialUserId) { mutableStateOf(initialUserId) }
    var avatarDataUrl by remember(initialAvatarDataUrl) { mutableStateOf(initialAvatarDataUrl) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) encodeAvatarImage(context, uri)?.let { avatarDataUrl = it }
    }

    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(
            modifier = Modifier.fillMaxWidth().widthIn(max = 350.dp).wrapContentHeight(),
            fill = Color(0xFFFFFBF0),
            edge = CardBorder,
            showCornerBolts = false,
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("用户资料", color = Ink, fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
                    PixelTextButton(onClick = onDismiss) { Text("关闭") }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CompanionChatAvatar(
                        fromUser = true,
                        avatarDataUrl = avatarDataUrl,
                        modifier = Modifier.size(78.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        PixelOutlinedButton(
                            onClick = { avatarPicker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (avatarDataUrl == null) "选择头像" else "更换头像")
                        }
                        if (avatarDataUrl != null) {
                            PixelTextButton(
                                onClick = { avatarDataUrl = null },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("移除头像", color = PixelDanger)
                            }
                        }
                    }
                }
                PixelTextField(
                    value = name,
                    onValueChange = { name = it.take(12) },
                    label = "昵称",
                    placeholder = "请输入昵称",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                PixelTextField(
                    value = userId,
                    onValueChange = { userId = it.take(32) },
                    label = "用户 ID",
                    placeholder = "设置便于识别的 ID",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text("昵称会用于首页问候；头像会显示在你与小麦的对话中。", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    PixelOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    PixelButton(
                        onClick = { onSave(name, userId, avatarDataUrl) },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlScreen(
    state: SmartPotUiState,
    control: (DeviceControlRequest) -> Unit,
    createShare: () -> Unit,
    saveUserProfile: (String, String, String?) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var projectionMode by rememberSaveable { mutableStateOf<String?>(null) }
    var lightExpanded by rememberSaveable { mutableStateOf(false) }
    var shareExpanded by rememberSaveable { mutableStateOf(false) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var userProfileVisible by rememberSaveable { mutableStateOf(false) }
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
    if (userProfileVisible) {
        UserProfileDialog(
            initialName = state.userName,
            initialUserId = state.userId,
            initialAvatarDataUrl = state.userAvatarDataUrl,
            onDismiss = { userProfileVisible = false },
            onSave = { name, userId, avatar ->
                saveUserProfile(name, userId, avatar)
                userProfileVisible = false
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.control_page_background),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp),
        ) {
            item {
                ControlPageHeader()
            }
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
                    onRemoteTouch = { control(DeviceControlRequest(DeviceCommandType.REMOTE_TOUCH)) },
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2.944f)) {
                        Image(
                            painter = painterResource(R.drawable.control_light_header),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        Row(
                            Modifier
                                .matchParentSize()
                                .clickable { lightExpanded = !lightExpanded }
                                .padding(start = 84.dp, end = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("植物补光", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                                Text(
                                    "当前：${if (lightStrip?.on == true) "灯带开" else "灯带关"} · ${if (manualMode) "APP 手动控制" else "ESP 自动控制"}\n标准 ${lightStrip?.lightMinLux ?: state.snapshot?.pot?.species?.thresholds?.lightMinLux ?: "--"}-${lightStrip?.lightMaxLux ?: state.snapshot?.pot?.species?.thresholds?.lightMaxLux ?: "--"} lux",
                                    color = Muted,
                                    fontSize = SmartPotTypeScale.labelSmall,
                                    maxLines = 2,
                                )
                            }
                            PixelSwitch(
                                checked = manualOn,
                                onCheckedChange = { checked ->
                                    manualOn = checked
                                    control(DeviceControlRequest(DeviceCommandType.SET_LIGHT_STRIP_CONTROL, lightStripManualMode = true, lightStripOn = checked))
                                },
                                enabled = manualMode,
                            )
                        }
                    }
                    if (lightExpanded) {
                        PixelPanel(
                            Modifier.fillMaxWidth(),
                            fill = Color(0xFFFFFDF5),
                            edge = CardBorder,
                            showCornerBolts = false,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PixelButton(
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
                        ) { Text(if (manualMode) "退出手动开关灯模式" else "进入手动开关灯模式", fontSize = SmartPotTypeScale.bodySmall) }
                        HorizontalDivider(color = CardBorder)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                                Text("一直灭灯时间段", fontWeight = FontWeight.SemiBold, color = Ink)
                                Text("仅命中该时段时禁止开灯，时段外仍可手动开灯", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                        }
                        PixelSwitch(checked = offPeriodEnabled, onCheckedChange = { offPeriodEnabled = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PixelTextField(
                            value = offStartText,
                            onValueChange = { offStartText = it.take(5) },
                            label = "开始 HH:mm",
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = offPeriodEnabled,
                        )
                        PixelTextField(
                            value = offEndText,
                            onValueChange = { offEndText = it.take(5) },
                            label = "结束 HH:mm",
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = offPeriodEnabled,
                        )
                    }
                    PixelButton(
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
                        ) { Text("保存灭灯时间段", fontSize = SmartPotTypeScale.bodySmall) }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlSliderCard(
                        kind = "brightness",
                        title = "亮度调节",
                        value = brightness,
                        accent = Color(0xFF5BA763),
                        modifier = Modifier.weight(1f),
                        onValueChange = { brightness = it },
                        onValueChangeFinished = { control(DeviceControlRequest(DeviceCommandType.SET_BRIGHTNESS, brightnessPercent = brightness.toInt())) },
                    )
                    ControlSliderCard(
                        kind = "volume",
                        title = "音量调节",
                        value = volume,
                        accent = Color(0xFFE69B32),
                        modifier = Modifier.weight(1f),
                        onValueChange = { volume = it },
                        onValueChangeFinished = { control(DeviceControlRequest(DeviceCommandType.SET_VOLUME, volumePercent = volume.toInt())) },
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2.926f)
                            .clickable { shareExpanded = !shareExpanded },
                    ) {
                        Image(
                            painter = painterResource(R.drawable.control_share_header),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        Column(
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 84.dp, end = 116.dp),
                        ) {
                            Text("双人共享", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                            Text("你和 ESP 一起照顾小麦", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                        }
                    }
                    if (shareExpanded) {
                        PixelPanel(
                            Modifier.fillMaxWidth(),
                            fill = Color(0xFFFFFDF5),
                            edge = CardBorder,
                            showCornerBolts = false,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                PixelButton(onClick = createShare, modifier = Modifier.fillMaxWidth()) { Text("生成临时分享码") }
                                state.shareCode?.let { Text("分享码 ${it.code}，有效至 ${it.expiresAt.take(16).replace('T', ' ')}", color = Leaf, fontWeight = FontWeight.SemiBold, fontSize = SmartPotTypeScale.labelSmall) }
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2.926f)
                            .clickable { settingsExpanded = !settingsExpanded },
                    ) {
                        Image(
                            painter = painterResource(R.drawable.control_settings_header),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        Column(
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 84.dp, end = 104.dp),
                        ) {
                            Text("更多设置", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                            Text("用户资料、屏幕休眠、设备重启", color = Muted, fontSize = SmartPotTypeScale.labelSmall, maxLines = 1)
                        }
                    }
                    if (settingsExpanded) {
                        PixelPanel(
                            Modifier.fillMaxWidth(),
                            fill = Color(0xFFFFFDF5),
                            edge = CardBorder,
                            showCornerBolts = false,
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                PixelButton(onClick = { userProfileVisible = true }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("用户", fontSize = SmartPotTypeScale.labelSmall) }
                                PixelOutlinedButton(onClick = { control(DeviceControlRequest(DeviceCommandType.SET_STANDBY, standby = true)) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("休眠屏幕", fontSize = SmartPotTypeScale.labelSmall) }
                                PixelOutlinedButton(onClick = { control(DeviceControlRequest(DeviceCommandType.RESTART)) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("重启设备", fontSize = SmartPotTypeScale.labelSmall) }
                            }
                        }
                    }
                }
            }
            state.lastCommand?.let { command ->
                item {
                    val commandMessage = when {
                        command.acknowledged -> "设备已确认：${command.ack?.status}"
                        state.snapshot?.online == true -> "设备在线，本次命令尚未确认，请重试"
                        else -> "设备当前离线，命令未发送"
                    }
                    Text(
                        commandMessage,
                        color = if (command.acknowledged) Leaf else Color(0xFFA56A00),
                        fontSize = SmartPotTypeScale.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlPageHeader() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌁", color = Color(0xFF8FA86A), fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        Text("控制", color = Color(0xFF304A1D), fontSize = SmartPotTypeScale.headlineMedium, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(16.dp))
        Text("⌁", color = Color(0xFF8FA86A), fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ControlDeviceStatusCard(state: SmartPotUiState) {
    val snapshot = state.snapshot
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2.08f)
            .clip(RoundedCornerShape(14.dp)),
    ) {
        Image(
            painter = painterResource(R.drawable.control_status_background),
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .scale(scaleX = 1.04f, scaleY = 1f),
            contentScale = ContentScale.Crop,
        )
        Column(
            Modifier
                .fillMaxWidth(0.53f)
                .align(Alignment.CenterStart)
                .padding(start = 24.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
                Text("设备状态", fontWeight = FontWeight.Bold, color = Ink)
                Text(if (snapshot?.online == true) "在线" else "离线", color = if (snapshot?.online == true) BrightLeaf else Color(0xFFE05252), fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (snapshot?.online == true) "ESP 已连接" else "等待 ESP 连接", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                Text("设备：${snapshot?.pot?.deviceId ?: "--"}", color = Muted, fontSize = SmartPotTypeScale.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onRemoteTouch: () -> Unit,
) {
    val emojis = listOf("heart", "smile", "happy", "thirsty", "dark", "weak", "wave", "star", "flower", "water", "sun", "sleep")
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = Color(0xFFFFFDF5),
        edge = CardBorder,
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("↗", color = BrightLeaf, fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
                Text("快捷投送", modifier = Modifier.padding(start = 7.dp), fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
                PixelOutlinedButton(
                    onClick = onRemoteTouch,
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    Text("隔空触摸", fontSize = SmartPotTypeScale.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlActionTile("text", "发送文字", "发送到 ESP 屏幕", mode == "text", Modifier.weight(1f)) { onModeChange("text") }
                ControlActionTile("emoji", "发送表情", "发送表情动画", mode == "emoji", Modifier.weight(1f)) { onModeChange("emoji") }
            }
            if (mode == "text") {
                PixelTextField(text, onTextChange, placeholder = "输入要投送的中文或英文短句", modifier = Modifier.fillMaxWidth(), minLines = 2)
                PixelButton(onClick = onSendText, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("投送到 ESP 屏幕") }
            }
            if (mode == "emoji") {
                emojis.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { emojiId ->
                            PixelOutlinedButton(
                                onClick = { onSendEmoji(emojiId) },
                                modifier = Modifier.weight(1f),
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
private fun ControlActionTile(kind: String, title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(78.dp)
            .background(if (selected) WarmLeafSoft else Color(0xFFFFFDF7), RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) PixelGreenEdge else Color(0xFFE4D8B5), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            ControlProjectionIcon(kind, Modifier.size(42.dp))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = SmartPotTypeScale.bodyMedium)
                Text(subtitle, color = Muted, fontSize = SmartPotTypeScale.labelSmall, maxLines = 1)
            }
            Text("›", color = Color(0xFFC99B52), fontSize = SmartPotTypeScale.titleLarge)
        }
    }
}

@Composable
private fun ControlProjectionIcon(kind: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        if (kind == "text") {
            drawCircle(Color(0xFF6BA85F), size.minDimension * 0.46f, center)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.18f, size.height * 0.25f),
                size = Size(size.width * 0.64f, size.height * 0.42f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f),
            )
            repeat(3) { index ->
                drawCircle(Color(0xFF6BA85F), size.width * 0.035f, Offset(size.width * (0.37f + index * 0.13f), size.height * 0.46f))
            }
            val tail = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.3f, size.height * 0.63f)
                lineTo(size.width * 0.2f, size.height * 0.78f)
                lineTo(size.width * 0.43f, size.height * 0.66f)
                close()
            }
            drawPath(tail, Color.White)
        } else {
            drawCircle(Color(0xFFF4C55D), size.minDimension * 0.46f, center)
            drawCircle(Color(0xFF5B412B), size.width * 0.045f, Offset(size.width * 0.37f, size.height * 0.42f))
            drawCircle(Color(0xFF5B412B), size.width * 0.045f, Offset(size.width * 0.63f, size.height * 0.42f))
            drawLine(
                color = Color(0xFF5B412B),
                start = Offset(size.width * 0.38f, size.height * 0.63f),
                end = Offset(size.width * 0.62f, size.height * 0.63f),
                strokeWidth = size.width * 0.04f,
            )
            drawCircle(Color(0xFFF08C77), size.width * 0.055f, Offset(size.width * 0.25f, size.height * 0.56f))
            drawCircle(Color(0xFFF08C77), size.width * 0.055f, Offset(size.width * 0.75f, size.height * 0.56f))
        }
    }
}

@Composable
private fun ControlSliderCard(
    kind: String,
    title: String,
    value: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    PixelPanel(
        modifier.height(168.dp),
        fill = Color(0xFFFFFDF5),
        edge = CardBorder,
        showCornerBolts = false,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControlAdjustIcon(kind = kind, high = true, color = accent, modifier = Modifier.size(24.dp))
                Text(title, modifier = Modifier.padding(start = 6.dp), color = Ink, fontSize = SmartPotTypeScale.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value.toInt().toString(), color = accent, fontSize = SmartPotTypeScale.headlineLarge, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Text("%", color = Ink, fontSize = SmartPotTypeScale.titleLarge, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
            }
            PixelSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..100f,
                activeColor = accent,
                onValueChangeFinished = onValueChangeFinished,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ControlAdjustIcon(kind = kind, high = false, color = Muted, modifier = Modifier.size(18.dp))
                ControlAdjustIcon(kind = kind, high = true, color = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ControlAdjustIcon(kind: String, high: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (kind == "brightness") {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * if (high) 0.22f else 0.17f
            drawCircle(color = color, radius = radius, center = center)
            val inner = size.minDimension * 0.34f
            val outer = size.minDimension * if (high) 0.49f else 0.43f
            repeat(8) { index ->
                val angle = Math.toRadians(index * 45.0)
                drawLine(
                    color = color,
                    start = Offset(center.x + kotlin.math.cos(angle).toFloat() * inner, center.y + kotlin.math.sin(angle).toFloat() * inner),
                    end = Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer),
                    strokeWidth = size.minDimension * 0.08f,
                )
            }
        } else {
            val midY = size.height / 2f
            val left = size.width * 0.12f
            val speakerRight = size.width * 0.48f
            drawRect(color, Offset(left, size.height * 0.36f), Size(size.width * 0.16f, size.height * 0.28f))
            val speaker = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.36f)
                lineTo(speakerRight, size.height * 0.18f)
                lineTo(speakerRight, size.height * 0.82f)
                lineTo(size.width * 0.28f, size.height * 0.64f)
                close()
            }
            drawPath(speaker, color)
            val waveCount = if (high) 2 else 1
            repeat(waveCount) { index ->
                val x = size.width * (0.62f + index * 0.16f)
                drawLine(color, Offset(x, midY - size.height * (0.13f + index * 0.05f)), Offset(x, midY + size.height * (0.13f + index * 0.05f)), strokeWidth = size.minDimension * 0.08f)
            }
        }
    }
}

@Composable
private fun ScheduleTable(
    schedules: List<ScheduleItem>,
    timezone: String,
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF1F6E5), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFFDCE7C4), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("还没有日程", color = Muted, fontSize = SmartPotTypeScale.bodySmall)
            }
        }
        rows.forEach { item ->
            val color = if (item.completed) Color(0xFF949B91) else Ink
            val decoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (item.completed) Color(0xFFF0F1E9) else Color(0xFFF0F6DE),
                        RoundedCornerShape(10.dp),
                    )
                    .border(1.dp, Color(0xFFDCE7C4), RoundedCornerShape(10.dp))
                    .toggleable(
                        value = item.completed,
                        role = Role.Checkbox,
                        onValueChange = { checked -> toggleSchedule(item, checked) },
                    )
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        scheduleCardTimeText(item, timezone),
                        fontSize = SmartPotTypeScale.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        textDecoration = decoration,
                    )
                    Text(
                        item.title,
                        color = color,
                        fontSize = SmartPotTypeScale.bodySmall,
                        fontWeight = FontWeight.Medium,
                        textDecoration = decoration,
                    )
                    Text(scheduleSourceLabel(item.source), color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                }
                PixelCheckbox(checked = item.completed)
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
    addSchedule: (String, Instant) -> Unit,
    toggleSchedule: (ScheduleItem, Boolean) -> Unit,
    startPomodoroTimer: () -> Unit,
    pausePomodoroTimer: () -> Unit,
    exitPomodoroTimer: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var memory by rememberSaveable { mutableStateOf("") }
    var scheduleTitle by rememberSaveable { mutableStateOf("") }
    var scheduleDueAtText by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduleFormVisible by rememberSaveable { mutableStateOf(false) }
    var chatExpanded by rememberSaveable { mutableStateOf(false) }
    var memoryExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingMemoryDelete by remember { mutableStateOf<UserMemory?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedShortcut by rememberSaveable { mutableStateOf("voice") }
    fun scrollToSection(section: String, index: Int) {
        selectedShortcut = section
        scope.launch { listState.animateScrollToItem(index) }
    }
    val zone = runCatching { ZoneId.of(state.snapshot?.pot?.timezone ?: "Asia/Shanghai") }
        .getOrDefault(ZoneId.of("Asia/Shanghai"))
    val today = LocalDate.now(zone).toString()
    pendingMemoryDelete?.let { memoryToDelete ->
        PixelConfirmDialog(
            title = "删除这条记忆？",
            text = memoryToDelete.content,
            confirmText = "删除",
            onConfirm = {
                    deleteMemory(memoryToDelete)
                    pendingMemoryDelete = null
            },
            onDismiss = { pendingMemoryDelete = null },
            danger = true,
        )
    }
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.companion_page_background),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp),
        ) {
            item {
                CompanionPageHeader()
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompanionSectionShortcut(
                        icon = "voice",
                        label = "语音唤醒",
                        selected = selectedShortcut == "voice",
                        modifier = Modifier.weight(1f),
                        onClick = { scrollToSection("voice", 2) },
                    )
                    CompanionSectionShortcut(
                        icon = "schedule",
                        label = "已设提醒",
                        selected = selectedShortcut == "schedule",
                        modifier = Modifier.weight(1f),
                        onClick = { scrollToSection("schedule", 4) },
                    )
                    CompanionSectionShortcut(
                        icon = "tomato",
                        label = "番茄钟",
                        selected = selectedShortcut == "tomato",
                        modifier = Modifier.weight(1f),
                        onClick = { scrollToSection("tomato", 5) },
                    )
                    CompanionSectionShortcut(
                        icon = "memory",
                        label = "记忆库",
                        selected = selectedShortcut == "memory",
                        modifier = Modifier.weight(1f),
                        onClick = { scrollToSection("memory", 3) },
                    )
                }
            }
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
                    dueAt = scheduleDueAtText?.let { value -> runCatching { Instant.parse(value) }.getOrNull() },
                    onDueAtChange = { scheduleDueAtText = it.toString() },
                    onAdd = {
                        val selectedDueAt = scheduleDueAtText?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
                        if (selectedDueAt?.isAfter(Instant.now()) == true) {
                            addSchedule(scheduleTitle, selectedDueAt)
                            scheduleTitle = ""
                            scheduleDueAtText = null
                            scheduleFormVisible = false
                        }
                    },
                    toggleSchedule = toggleSchedule,
                )
            }
            item {
                CompanionFocusCard(
                    state = state,
                    startPomodoroTimer = startPomodoroTimer,
                    pausePomodoroTimer = pausePomodoroTimer,
                    exitPomodoroTimer = exitPomodoroTimer,
                )
            }
        }
    }
}

@Composable
private fun CompanionPageHeader() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌁", color = Color(0xFF8FA86A), fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        Text("陪伴", color = Color(0xFF304A1D), fontSize = SmartPotTypeScale.headlineMedium, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(16.dp))
        Text("⌁", color = Color(0xFF8FA86A), fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CompanionSectionShortcut(
    icon: String,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val edge = if (selected) Color(0xFFA7BE72) else CardBorder
    Box(
        modifier
            .height(88.dp)
            .background(Color(0xFFFFFCF4).copy(alpha = 0.92f), RoundedCornerShape(10.dp))
            .border(1.dp, edge, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompanionShortcutIcon(icon, Modifier.size(38.dp))
            Text(label, color = Color(0xFF304A1D), fontSize = SmartPotTypeScale.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun CompanionShortcutIcon(kind: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        when (kind) {
            "voice" -> {
                drawRoundRect(
                    Color(0xFF6FC482),
                    topLeft = Offset(size.width * 0.36f, size.height * 0.10f),
                    size = Size(size.width * 0.28f, size.height * 0.48f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.14f),
                )
                drawArc(
                    color = Color(0xFF4B9A5E),
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.23f, size.height * 0.30f),
                    size = Size(size.width * 0.54f, size.height * 0.42f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawLine(Color(0xFF4B9A5E), Offset(size.width * 0.5f, size.height * 0.72f), Offset(size.width * 0.5f, size.height * 0.87f), stroke, StrokeCap.Round)
                drawLine(Color(0xFF4B9A5E), Offset(size.width * 0.35f, size.height * 0.87f), Offset(size.width * 0.65f, size.height * 0.87f), stroke, StrokeCap.Round)
            }
            "schedule" -> {
                drawRoundRect(
                    Color(0xFFA8D990),
                    topLeft = Offset(size.width * 0.12f, size.height * 0.16f),
                    size = Size(size.width * 0.76f, size.height * 0.70f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                )
                drawRect(Color(0xFFEFF8E6), Offset(size.width * 0.17f, size.height * 0.31f), Size(size.width * 0.66f, size.height * 0.48f))
                drawLine(Color(0xFF568A4F), Offset(size.width * 0.28f, size.height * 0.08f), Offset(size.width * 0.28f, size.height * 0.26f), stroke, StrokeCap.Round)
                drawLine(Color(0xFF568A4F), Offset(size.width * 0.70f, size.height * 0.08f), Offset(size.width * 0.70f, size.height * 0.26f), stroke, StrokeCap.Round)
                val check = Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.54f)
                    lineTo(size.width * 0.43f, size.height * 0.67f)
                    lineTo(size.width * 0.70f, size.height * 0.42f)
                }
                drawPath(check, Color(0xFF4F9A58), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            "tomato" -> {
                drawCircle(Color(0xFFF27958), size.width * 0.31f, Offset(size.width * 0.5f, size.height * 0.56f))
                drawCircle(Color(0xFFFF9C76), size.width * 0.08f, Offset(size.width * 0.40f, size.height * 0.45f))
                val leaf = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.29f)
                    lineTo(size.width * 0.34f, size.height * 0.22f)
                    lineTo(size.width * 0.41f, size.height * 0.38f)
                    lineTo(size.width * 0.25f, size.height * 0.37f)
                    lineTo(size.width * 0.43f, size.height * 0.49f)
                    lineTo(size.width * 0.5f, size.height * 0.29f)
                    lineTo(size.width * 0.58f, size.height * 0.48f)
                    lineTo(size.width * 0.75f, size.height * 0.36f)
                    lineTo(size.width * 0.58f, size.height * 0.37f)
                    lineTo(size.width * 0.66f, size.height * 0.22f)
                    close()
                }
                drawPath(leaf, Color(0xFF5A9B4D))
            }
            else -> {
                drawRoundRect(
                    Color(0xFF84B47A),
                    topLeft = Offset(size.width * 0.20f, size.height * 0.12f),
                    size = Size(size.width * 0.64f, size.height * 0.76f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                )
                drawRect(Color(0xFFEFF7E8), Offset(size.width * 0.31f, size.height * 0.20f), Size(size.width * 0.43f, size.height * 0.60f))
                repeat(3) { index ->
                    val y = size.height * (0.36f + index * 0.15f)
                    drawLine(Color(0xFF65935D), Offset(size.width * 0.39f, y), Offset(size.width * 0.67f, y), stroke * 0.65f)
                }
                repeat(3) { index ->
                    val y = size.height * (0.28f + index * 0.20f)
                    drawLine(Color(0xFF5A8B51), Offset(size.width * 0.13f, y), Offset(size.width * 0.29f, y), stroke, StrokeCap.Round)
                }
            }
        }
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
    val messageScrollState = rememberScrollState()
    val latestMessageId = messages.lastOrNull()?.id
    LaunchedEffect(expanded, state.selectedChatDate, latestMessageId) {
        if (expanded && latestMessageId != null) {
            repeat(2) { withFrameNanos { } }
            messageScrollState.scrollTo(messageScrollState.maxValue)
        }
    }
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelPanelFill,
        edge = PixelGreenEdge,
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    CompanionHeaderSproutIcon(Modifier.size(23.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("与小麦的对话", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                }
                Text(if (expanded) "⌃" else "⌄", color = Leaf, fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(state.chatDays, key = ChatDaySummary::date) { day ->
                        PixelButton(
                            selected = state.selectedChatDate == day.date,
                            onClick = { selectDay(day.date) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                        ) { Text(if (day.date == today) "今天" else day.date.takeLast(5), fontSize = SmartPotTypeScale.labelSmall) }
                    }
                }
                if (messages.isEmpty()) {
                    Text("这一天还没有对话记录", color = Muted, fontSize = SmartPotTypeScale.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(messageScrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        messages.forEach { message ->
                            CompanionChatBubble(
                                message = message,
                                zone = zone,
                                userAvatarDataUrl = state.userAvatarDataUrl,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = "输入你想说的话...",
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                PixelButton(onClick = onSend, enabled = input.isNotBlank(), modifier = Modifier.size(46.dp), contentPadding = PaddingValues(0.dp)) { Text("➤", fontSize = SmartPotTypeScale.titleMedium) }
            }
        }
    }
}

@Composable
private fun CompanionChatBubble(
    message: ChatMessage,
    zone: ZoneId,
    userAvatarDataUrl: String?,
) {
    val fromUser = message.role == ChatRole.USER
    val bubbleColor = if (fromUser) Color(0xFFDDECCB) else Color(0xFFFFFDF4)
    val bubbleEdge = if (fromUser) Color(0xFFC5DCA9) else CardBorder
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!fromUser) {
            CompanionChatAvatar(fromUser = false, modifier = Modifier.size(42.dp))
            Spacer(Modifier.width(5.dp))
            ChatBubbleTail(fromUser = false, fill = bubbleColor, edge = bubbleEdge)
        }
        Box(
            Modifier
                .widthIn(max = 250.dp)
                .background(bubbleColor, RoundedCornerShape(11.dp))
                .border(1.dp, bubbleEdge, RoundedCornerShape(11.dp)),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(message.content, color = Ink, fontSize = SmartPotTypeScale.bodySmall)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${chatSourceLabel(message)} · ${chatTimeText(message.createdAt, zone)}",
                    fontSize = SmartPotTypeScale.labelSmall,
                    color = Muted,
                )
            }
        }
        if (fromUser) {
            ChatBubbleTail(fromUser = true, fill = bubbleColor, edge = bubbleEdge)
            Spacer(Modifier.width(5.dp))
            CompanionChatAvatar(
                fromUser = true,
                avatarDataUrl = userAvatarDataUrl,
                modifier = Modifier.size(42.dp),
            )
        }
    }
}

@Composable
private fun ChatBubbleTail(fromUser: Boolean, fill: Color, edge: Color) {
    Canvas(Modifier.width(9.dp).height(18.dp).offset(y = 8.dp)) {
        val tail = Path().apply {
            if (fromUser) {
                moveTo(0f, 0f)
                lineTo(size.width, size.height * 0.5f)
                lineTo(0f, size.height * 0.72f)
            } else {
                moveTo(size.width, 0f)
                lineTo(0f, size.height * 0.5f)
                lineTo(size.width, size.height * 0.72f)
            }
            close()
        }
        drawPath(tail, fill)
        drawPath(tail, edge, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun CompanionHeaderSproutIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        drawLine(Color(0xFF6B9C52), Offset(size.width * 0.5f, size.height * 0.86f), Offset(size.width * 0.5f, size.height * 0.38f), stroke, StrokeCap.Round)
        drawOval(Color(0xFFA7C874), Offset(size.width * 0.05f, size.height * 0.18f), Size(size.width * 0.44f, size.height * 0.30f))
        drawOval(Color(0xFF86B75B), Offset(size.width * 0.51f, size.height * 0.08f), Size(size.width * 0.44f, size.height * 0.30f))
    }
}

@Composable
private fun CompanionChatAvatar(
    fromUser: Boolean,
    avatarDataUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    if (!fromUser) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(Color(0xFFFFF7E8), size.width * 0.48f, center)
                drawCircle(CardBorder, size.width * 0.48f, center, style = Stroke(1.dp.toPx()))
            }
            Image(
                painter = painterResource(R.drawable.wheat_chat_avatar),
                contentDescription = "小麦头像",
                modifier = Modifier.fillMaxSize().padding(1.dp),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            )
        }
        return
    }
    val avatarBitmap = remember(avatarDataUrl) {
        avatarDataUrl?.takeIf(String::isNotBlank)?.let(::decodeDiaryPhoto)
    }
    if (avatarBitmap != null) {
        Image(
            bitmap = avatarBitmap.asImageBitmap(),
            contentDescription = "用户头像",
            modifier = modifier
                .clip(CircleShape)
                .border(1.dp, CardBorder, CircleShape),
            contentScale = ContentScale.Crop,
        )
        return
    }
    Canvas(modifier) {
        val center = Offset(size.width * 0.5f, size.height * 0.56f)
        drawCircle(Color(0xFFFFF7E8), size.width * 0.46f, center)
        drawCircle(CardBorder, size.width * 0.46f, center, style = Stroke(1.dp.toPx()))
        drawCircle(Color(0xFFFFD6B6), size.width * 0.25f, Offset(center.x, center.y * 0.98f))
        drawArc(
            Color(0xFF6F4935),
            startAngle = 176f,
            sweepAngle = 188f,
            useCenter = true,
            topLeft = Offset(size.width * 0.21f, size.height * 0.15f),
            size = Size(size.width * 0.58f, size.height * 0.58f),
        )
        drawCircle(Color(0xFF5A4031), size.width * 0.025f, Offset(size.width * 0.43f, size.height * 0.55f))
        drawCircle(Color(0xFF5A4031), size.width * 0.025f, Offset(size.width * 0.57f, size.height * 0.55f))
        drawArc(
            Color(0xFFB66562),
            startAngle = 10f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(size.width * 0.43f, size.height * 0.56f),
            size = Size(size.width * 0.14f, size.height * 0.10f),
            style = Stroke(1.dp.toPx()),
        )
        drawOval(Color(0xFF9C705D), Offset(size.width * 0.28f, size.height * 0.73f), Size(size.width * 0.44f, size.height * 0.22f))
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
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelPanelFill,
        edge = PixelGreenEdge,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    CompanionShortcutIcon("memory", Modifier.size(42.dp))
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("专属记忆库", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                        Text("让小麦记住重要的事情", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                    }
                }
                Text(if (expanded) "⌃" else "›", color = Muted, fontSize = SmartPotTypeScale.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            if (expanded) {
                PixelTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = "例如：生日、考试时间、加班安排...",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                PixelButton(onClick = onRemember, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("让小麦记住") }
                if (memories.isNotEmpty()) {
                    Text("已记住", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                    memories.asReversed().forEach { item ->
                        Box(Modifier.background(Color(0xFFF8FAEC), RoundedCornerShape(8.dp)).border(1.dp, CardBorder, RoundedCornerShape(8.dp))) {
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.content, modifier = Modifier.weight(1f), fontSize = SmartPotTypeScale.labelSmall, color = Color(0xFF4D534E), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                PixelTextButton(onClick = { onDelete(item) }, contentPadding = PaddingValues(horizontal = 7.dp), danger = true) {
                                    Text("删除", color = Color(0xFFD14343), fontSize = SmartPotTypeScale.labelSmall)
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
    dueAt: Instant?,
    onDueAtChange: (Instant) -> Unit,
    onAdd: () -> Unit,
    toggleSchedule: (ScheduleItem, Boolean) -> Unit,
) {
    val items = state.schedule?.items.orEmpty()
    val timezone = state.snapshot?.pot?.timezone ?: "Asia/Shanghai"
    var pickerVisible by rememberSaveable { mutableStateOf(false) }
    if (pickerVisible) {
        ScheduleDateTimeWheelDialog(
            timezone = timezone,
            initialDueAt = dueAt,
            onDismiss = { pickerVisible = false },
            onConfirm = {
                onDueAtChange(it)
                pickerVisible = false
            },
        )
    }
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelPanelFill,
        edge = PixelGreenEdge,
        showCornerBolts = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CompanionShortcutIcon("schedule", Modifier.size(25.dp))
                Spacer(Modifier.width(6.dp))
                Text("日程安排", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
            }
            ScheduleTable(items, timezone, toggleSchedule)
            PixelTextButton(
                onClick = onToggleForm,
                modifier = Modifier.align(Alignment.End),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(if (formVisible) "收起" else "＋ 添加日程", fontSize = SmartPotTypeScale.bodySmall)
            }
            if (formVisible) {
                PixelTextField(title, onTitleChange, label = "任务名称", modifier = Modifier.fillMaxWidth(), singleLine = true)
                PixelOutlinedButton(
                    onClick = { pickerVisible = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("提醒时间", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
                        Text(scheduleSelectionText(dueAt, timezone), color = Ink, fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                PixelButton(
                    onClick = onAdd,
                    enabled = title.isNotBlank() && dueAt?.isAfter(Instant.now()) == true,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("添加并同步到 ESP") }
            }
        }
    }
}

@Composable
private fun ScheduleDateTimeWheelDialog(
    timezone: String,
    initialDueAt: Instant?,
    onDismiss: () -> Unit,
    onConfirm: (Instant) -> Unit,
) {
    val zone = remember(timezone) {
        runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
    }
    val initial = remember(zone, initialDueAt) {
        initialDueAt?.takeIf { it.isAfter(Instant.now()) }?.atZone(zone) ?: nextScheduleWheelTime(zone)
    }
    var month by rememberSaveable { mutableIntStateOf(initial.monthValue) }
    var day by rememberSaveable { mutableIntStateOf(initial.dayOfMonth) }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    val maxDay = remember(month, zone) { scheduleWheelMaxDay(month, zone) }
    LaunchedEffect(maxDay) {
        day = day.coerceAtMost(maxDay)
    }

    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(
            modifier = Modifier.fillMaxWidth(),
            fill = PixelCream,
            edge = PixelWoodDark,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("选择提醒时间", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ScheduleNumberWheel("月", month, 1..12) {
                        month = it
                        day = day.coerceAtMost(scheduleWheelMaxDay(month, zone))
                    }
                    ScheduleNumberWheel("日", day, 1..maxDay) { day = it }
                    ScheduleNumberWheel("时", hour, 0..23) { hour = it }
                    ScheduleNumberWheel("分", minute, 0..59) { minute = it }
                }
                Text(
                    scheduleWheelPreview(zone, month, day, hour, minute),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = Leaf,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PixelTextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    PixelTextButton(onClick = { onConfirm(resolveScheduleWheelInstant(zone, month, day, hour, minute)) }) {
                        Text("确定")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleNumberWheel(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    val displayedValues = remember(range.first, range.last) {
        range.map { "%02d".format(it) }.toTypedArray()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = SmartPotTypeScale.bodySmall)
        AndroidView(
            modifier = Modifier.width(62.dp).height(146.dp),
            factory = { context ->
                NumberPicker(context).apply {
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { picker ->
                picker.setOnValueChangedListener(null)
                picker.displayedValues = null
                picker.minValue = range.first
                picker.maxValue = range.last
                picker.displayedValues = displayedValues
                picker.wrapSelectorWheel = range.count() > 3
                picker.value = value.coerceIn(range)
                picker.setOnValueChangedListener { _, _, newValue -> onValueChange(newValue) }
            },
        )
    }
}

private fun nextScheduleWheelTime(zone: ZoneId): ZonedDateTime {
    var next = ZonedDateTime.now(zone).withSecond(0).withNano(0).plusMinutes(1)
    val remainder = next.minute % 5
    if (remainder != 0) next = next.plusMinutes((5 - remainder).toLong())
    return next
}

private fun scheduleWheelMaxDay(month: Int, zone: ZoneId): Int {
    val year = ZonedDateTime.now(zone).year
    return maxOf(YearMonth.of(year, month).lengthOfMonth(), YearMonth.of(year + 1, month).lengthOfMonth())
}

private fun resolveScheduleWheelInstant(
    zone: ZoneId,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    now: Instant = Instant.now(),
): Instant {
    val currentYear = now.atZone(zone).year
    fun candidate(year: Int): ZonedDateTime? {
        if (day > YearMonth.of(year, month).lengthOfMonth()) return null
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
    }
    for (year in currentYear..currentYear + 8) {
        val value = candidate(year) ?: continue
        if (value.toInstant().isAfter(now)) return value.toInstant()
    }
    error("Unable to resolve the selected schedule date")
}

private fun scheduleWheelPreview(zone: ZoneId, month: Int, day: Int, hour: Int, minute: Int): String {
    val dueAt = resolveScheduleWheelInstant(zone, month, day, hour, minute)
    return DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm").format(dueAt.atZone(zone))
}

private fun scheduleSelectionText(dueAt: Instant?, timezone: String): String {
    if (dueAt == null) return "选择月、日、时、分"
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
    return DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm").format(dueAt.atZone(zone))
}

@Composable
private fun CompanionFocusCard(
    state: SmartPotUiState,
    startPomodoroTimer: () -> Unit,
    pausePomodoroTimer: () -> Unit,
    exitPomodoroTimer: () -> Unit,
) {
    val today = state.careOverview?.focus ?: state.focusDaily.lastOrNull()
    val count = today?.pomodoroCount ?: 0
    val minutes = today?.focusMinutes ?: 0
    val target = (today?.targetPomodoroCount ?: 4).coerceAtLeast(1)
    val completion = today?.scheduleCompletionPercent ?: 0
    val sessionSeconds = 25 * 60
    val remainingSeconds = state.pomodoroRemainingSeconds
    val timerRunning = state.pomodoroTimerRunning
    PixelPanel(
        Modifier.fillMaxWidth(),
        fill = PixelPanelFill,
        edge = PixelGreenEdge,
        showCornerBolts = false,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CompanionShortcutIcon("tomato", Modifier.size(25.dp))
                Spacer(Modifier.width(6.dp))
                Text("今日番茄钟", fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
            }
            PomodoroDial(
                progress = 1f - remainingSeconds.toFloat() / sessionSeconds,
                timeText = "%02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                statusText = if (timerRunning) "专注中..." else if (remainingSeconds < sessionSeconds) "已暂停" else "准备专注",
                modifier = Modifier.size(168.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelButton(
                    onClick = {
                        if (timerRunning) {
                            pausePomodoroTimer()
                        } else {
                            startPomodoroTimer()
                        }
                    },
                    modifier = Modifier.width(112.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Text(
                        if (timerRunning) "暂停" else if (remainingSeconds < sessionSeconds) "继续" else "开始",
                        fontSize = SmartPotTypeScale.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (timerRunning || remainingSeconds < sessionSeconds) {
                    PixelTextButton(
                        onClick = exitPomodoroTimer,
                        danger = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("退出", fontSize = SmartPotTypeScale.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            HorizontalDivider(color = CardBorder)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$count 个 · $minutes min", color = BrightLeaf, fontSize = SmartPotTypeScale.titleMedium, fontWeight = FontWeight.Bold)
                Text("目标 $target 个番茄钟", color = Muted, fontSize = SmartPotTypeScale.labelSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("日程完成度", fontWeight = FontWeight.Bold, color = Ink)
                Text("$completion%", color = BrightLeaf, fontSize = SmartPotTypeScale.titleLarge, fontWeight = FontWeight.Bold)
            }
            PixelProgressBar((completion / 100f).coerceIn(0f, 1f), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PomodoroDial(
    progress: Float,
    timeText: String,
    statusText: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val arcTopLeft = Offset(inset, inset)
            drawArc(
                color = Color(0xFFDDEEC9),
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = Color(0xFF65A861),
                startAngle = 140f,
                sweepAngle = 260f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CompanionShortcutIcon("tomato", Modifier.size(28.dp))
            Text(timeText, color = Ink, fontSize = SmartPotTypeScale.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(statusText, color = Muted, fontSize = SmartPotTypeScale.labelSmall)
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
private fun airQualityLabel(showEco2: Boolean, value: Int?): String = when {
    value == null -> "等待数据"
    showEco2 && value <= 800 -> "空气清新"
    showEco2 && value <= 1200 -> "轻度偏高"
    showEco2 -> "通风不足"
    value <= 220 -> "空气清新"
    value <= 660 -> "轻度偏高"
    else -> "空气较差"
}
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
            modifier = modifier
                .background(Color(0xFFF8FAEC), RoundedCornerShape(8.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(8.dp)),
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

private fun encodeAvatarImage(context: Context, uri: Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 640 || bounds.outHeight / sampleSize > 640) sampleSize *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } ?: return@runCatching null
    val side = minOf(decoded.width, decoded.height)
    val cropped = Bitmap.createBitmap(
        decoded,
        (decoded.width - side) / 2,
        (decoded.height - side) / 2,
        side,
        side,
    )
    val scaled = Bitmap.createScaledBitmap(cropped, 320, 320, true)
    val bytes = ByteArrayOutputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
        output.toByteArray()
    }
    listOf(scaled, cropped, decoded)
        .distinctBy { System.identityHashCode(it) }
        .forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
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
    condition.contains("雨") -> "雨"
    condition.contains("云") || condition.contains("阴") -> "云"
    condition.contains("晴") -> "晴"
    else -> "天"
}

private data class GrowthTimelineEvent(
    val date: String,
    val title: String,
    val detail: String,
    val type: CareType,
    val imageDataUrl: String? = null,
    val careLogId: String? = null,
)

private fun growthTimeline(state: SmartPotUiState): List<GrowthTimelineEvent> {
    val pot = state.snapshot?.pot
    val created = pot?.createdAt?.take(10)?.let {
        GrowthTimelineEvent(it, "开始陪伴", pot.displayName, CareType.NEW_LEAF)
    }
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
            type = log.type,
            imageDataUrl = log.imageDataUrl,
            careLogId = log.id,
        )
    }
    return (listOfNotNull(created) + logs).sortedByDescending { it.date }
}

private fun diaryMoodKind(diary: PlantDiary): DiaryMoodKind {
    val content = listOfNotNull(diary.moodEmoji, diary.title, diary.content).joinToString(" ")
    return when {
        content.contains("水") || content.contains("湿") -> DiaryMoodKind.WATER
        content.contains("叶") || content.contains("芽") -> DiaryMoodKind.SPROUT
        content.contains("光") || content.contains("晒") || content.contains("晴") -> DiaryMoodKind.SUN
        content.contains("爱") || content.contains("喜欢") || content.contains("开心") -> DiaryMoodKind.HEART
        content.contains("睡") || content.contains("困") -> DiaryMoodKind.SLEEP
        else -> DiaryMoodKind.NOTE
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

private fun scheduleCardTimeText(item: ScheduleItem, timezone: String): String {
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
    val parsedDateTime = item.dueAt?.let { dueAt ->
        runCatching {
            DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
                .format(Instant.parse(dueAt).atZone(zone))
        }.getOrNull()
    }
    if (parsedDateTime != null) return parsedDateTime

    val legacyDateTime = Regex("(\\d{1,2})-(\\d{1,2})[/ ](\\d{1,2}:\\d{2})")
        .matchEntire(item.displayTime.trim())
    if (legacyDateTime != null) {
        val (month, day, time) = legacyDateTime.destructured
        return "${month.toInt()}月${day.toInt()}日 $time"
    }
    return item.displayTime.trim().takeIf { it.isNotEmpty() } ?: "待定"
}

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

private fun calculateDailyLightIntegral(
    values: List<DeviceTelemetry>,
    latest: DeviceTelemetry?,
    timezone: String?,
    thresholds: PlantThresholds?,
): DailyLightIntegral {
    val zone = zoneIdOf(timezone)
    val now = Instant.now()
    val today = now.atZone(zone).toLocalDate()
    val samples = telemetryWithLatest(values, latest)
        .mapNotNull { telemetry ->
            val instant = parseInstant(telemetry.recordedAt) ?: return@mapNotNull null
            if (instant.atZone(zone).toLocalDate() != today || instant.isAfter(now)) null else instant to telemetry
        }
        .distinctBy { (instant, telemetry) -> "${telemetry.deviceId}:${telemetry.sequence}:$instant" }
        .sortedBy { it.first }

    val lightMinLux = thresholds?.lightMinLux?.coerceAtLeast(1) ?: 400
    val effectiveThresholdLux = lightMinLux
    val targetLuxHours = thresholds?.dailyLightTargetLuxHours?.coerceAtLeast(1)
        ?: dailyLightTargetFromSensorLux(lightMinLux)
    val lampEquivalentLux = 500.0
    var effectiveSeconds = 0.0
    var totalLuxHours = 0.0
    var supplementalLuxHours = 0.0

    samples.forEachIndexed { index, (instant, telemetry) ->
        val nextInstant = samples.getOrNull(index + 1)?.first ?: now
        val intervalSeconds = ChronoUnit.MILLIS.between(instant, nextInstant).toDouble() / 1000.0
        val boundedSeconds = intervalSeconds.coerceIn(0.0, 5.0 * 60.0)
        if (boundedSeconds <= 0.0) return@forEachIndexed
        val lux = telemetry.lightLux.coerceAtLeast(0).toDouble()
        if (lux >= effectiveThresholdLux) effectiveSeconds += boundedSeconds
        val contribution = lux * boundedSeconds / 3600.0
        totalLuxHours += contribution
        if (telemetry.lightStripOn == true) {
            supplementalLuxHours += minOf(lux, lampEquivalentLux) * boundedSeconds / 3600.0
        }
    }

    val totalRounded = totalLuxHours.roundToInt().coerceAtLeast(0)
    val supplementalRounded = supplementalLuxHours.roundToInt().coerceIn(0, totalRounded)
    val remainingLuxHours = (targetLuxHours - totalLuxHours).coerceAtLeast(0.0)
    return DailyLightIntegral(
        effectiveMinutes = (effectiveSeconds / 60.0).roundToInt().coerceAtLeast(0),
        totalLuxHours = totalRounded,
        ambientLuxHours = (totalRounded - supplementalRounded).coerceAtLeast(0),
        supplementalLuxHours = supplementalRounded,
        recommendedSupplementMinutes = ceil(remainingLuxHours / lampEquivalentLux * 60.0).toInt().coerceAtLeast(0),
        targetLuxHours = targetLuxHours,
        completionPercent = (totalLuxHours / targetLuxHours * 100.0).roundToInt().coerceAtLeast(0),
    )
}

private fun dailyLightTargetFromSensorLux(lightMinLux: Int): Int = when {
    lightMinLux <= 300 -> 25_000
    lightMinLux <= 700 -> 40_000
    lightMinLux <= 1_700 -> 60_000
    else -> 80_000
}

private fun formatLightDuration(totalMinutes: Int): String {
    val minutes = totalMinutes.coerceAtLeast(0)
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}min"
        hours > 0 -> "${hours}h"
        else -> "${remainder}min"
    }
}

private fun formatCompactLightDuration(totalMinutes: Int): String {
    val minutes = totalMinutes.coerceAtLeast(0)
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${remainder}m"
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
    "适宜", "常伴", "不错", "散射光", "空气清新" -> BrightLeaf
    "等待数据", "等待互动" -> Muted
    else -> Color(0xFFD17B2F)
}

private fun plantCoreStatus(
    userName: String,
    online: Boolean,
    soilStatus: SoilStatus?,
    lightStatus: LightStatus?,
    interactionSuitability: Double,
): PlantCoreStatus {
    val ownerName = userName.trim().ifBlank { "主人" }
    return when {
    !online -> PlantCoreStatus(
        text = "小麦正在等${ownerName}回来，设备连接后我就告诉你现在的感受。",
        color = Color(0xFFD17B2F),
    )
    soilStatus == SoilStatus.TOO_DRY -> PlantCoreStatus(
        text = "小麦现在有点口渴，${ownerName}记得喂我喝水哦！",
        color = Color(0xFFD17B2F),
    )
    lightStatus == LightStatus.DARK -> PlantCoreStatus(
        text = "小麦现在需要光照哦，请${ownerName}把我移到光照更充足的地方吧！",
        color = Color(0xFFD17B2F),
    )
    soilStatus == SoilStatus.TOO_WET -> PlantCoreStatus(
        text = "小麦今天喝得有点饱，${ownerName}先让我透透气吧！",
        color = Color(0xFFD17B2F),
    )
    lightStatus == LightStatus.TOO_STRONG -> PlantCoreStatus(
        text = "小麦觉得阳光有点热，${ownerName}帮我挪到柔和的散射光里吧！",
        color = Color(0xFFD17B2F),
    )
    soilStatus == SoilStatus.SUITABLE &&
        lightStatus == LightStatus.DIFFUSE &&
        interactionSuitability >= 1.0 -> PlantCoreStatus(
        text = "小麦现在很开心！${ownerName}把水分和光照都照顾得刚刚好。",
        color = BrightLeaf,
    )
    else -> PlantCoreStatus(
        text = "小麦正在感受环境，${ownerName}稍等我一下哦！",
        color = Color(0xFFD17B2F),
    )
}
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
