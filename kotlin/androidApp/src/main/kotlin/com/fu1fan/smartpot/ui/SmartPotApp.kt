package com.fu1fan.smartpot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fu1fan.smartpot.protocol.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val Leaf = Color(0xFF407A52)
private val SoftLeaf = Color(0xFFE5F0E4)
private val Sand = Color(0xFFF5F8F1)

private data class DashboardMetrics(
    val growthDays: Int?,
    val healthPercent: Int?,
    val companionStars: Float,
    val dailyInteractions: Int,
    val dailyDialogCount: Int,
    val dailyTouchCount: Int,
    val soilSuitability: Double,
    val lightSuitability: Double,
    val interactionSuitability: Double,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SmartPotApp(viewModel: SmartPotViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Leaf, secondary = Color(0xFF7D9763), background = Sand, surface = Color.White)) {
        Scaffold(
            containerColor = Sand,
            topBar = { TopAppBar(title = { Text(state.snapshot?.pot?.displayName ?: "小麦智能盆栽", fontWeight = FontWeight.Bold) }, actions = { TextButton(onClick = viewModel::refresh) { Text("刷新") } }) },
            bottomBar = {
                NavigationBar {
                    listOf("面板" to "🌱", "养护" to "📅", "控制" to "🖥", "陪伴" to "💬").forEachIndexed { index, item ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(item.second) }, label = { Text(item.first) })
                    }
                }
            },
            snackbarHost = {
                state.error?.let { error -> Snackbar(action = { TextButton(onClick = viewModel::clearError) { Text("知道了") } }) { Text(error) } }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    state.loading && state.species.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.pots.isEmpty() -> SetupScreen(state.species, viewModel::createPot, viewModel::redeemShare)
                    tab == 0 -> DashboardScreen(state)
                    tab == 1 -> CareScreen(state, viewModel::addCare, viewModel::generateDiary, viewModel::recordPomodoro, viewModel::speakDiary)
                    tab == 2 -> ControlScreen(state, viewModel::control, viewModel::addSchedule, viewModel::toggleSchedule, viewModel::createShare, viewModel::redeemShare)
                    else -> CompanionScreen(state, viewModel::sendChat, viewModel::addMemory)
                }
            }
        }
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
private fun DashboardScreen(state: SmartPotUiState) {
    val snap = state.snapshot
    val metrics = dashboardMetrics(state)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(snap?.pot?.species?.chineseName ?: "等待设备", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(snap?.pot?.species?.scientificName.orEmpty(), color = Color.Gray)
                    Text("成长第 ${metrics.growthDays?.toString() ?: "--"} 天", color = Leaf, fontWeight = FontWeight.SemiBold)
                }
                AssistChip(onClick = {}, label = { Text(if (snap?.online == true) "● WiFi 在线" else "○ 设备离线") })
            }
        }
        item { PlantHealthCard(metrics) }
        item { CompanionScoreCard(metrics) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("土壤湿度", snap?.telemetry?.soilPercent?.let { "$it%" } ?: "--", soilLabel(snap?.evaluated?.soilStatus), snap?.pot?.species?.thresholds?.let { "标准 ${it.soilMinPercent}-${it.soilMaxPercent}%" }.orEmpty(), Modifier.weight(1f))
                MetricCard("环境光照", snap?.telemetry?.lightLux?.let { "$it lux" } ?: "--", lightLabel(snap?.evaluated?.lightStatus), snap?.pot?.species?.thresholds?.let { "标准 ${it.lightMinLux}-${it.lightMaxLux}" }.orEmpty(), Modifier.weight(1f))
            }
        }
        item { AdviceCard("位置与养护建议", listOfNotNull(snap?.evaluated?.soilAdvice, snap?.evaluated?.lightAdvice)) }
        item { Text("最近趋势", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); TelemetryChart(state.telemetry) }
        item {
            val affinity = snap?.affinity ?: AffinityState()
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("好感度 · ${affinityLabel(affinity.level)}", fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { affinity.score / 100f }, modifier = Modifier.fillMaxWidth()); Text("${affinity.score}/100 · 浇水、互动与合适光照会让关系升温", fontSize = 12.sp, color = Color.Gray) } }
        }
        if (!snap?.activeAlerts.isNullOrEmpty()) item { AdviceCard("需要关注", snap.activeAlerts.map { it.message }, Color(0xFFFFE2DD)) }
    }
}

@Composable
private fun PlantHealthCard(metrics: DashboardMetrics) {
    val healthText = metrics.healthPercent?.let { "$it%" } ?: "--"
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("植物健康值", fontWeight = FontWeight.Bold)
                    Text("湿度 40% · 光照 40% · 互动 20%", fontSize = 12.sp, color = Color.Gray)
                }
                Text(healthText, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Leaf)
            }
            LinearProgressIndicator(
                progress = { (metrics.healthPercent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = Color(0xFF2FA866),
                trackColor = SoftLeaf,
            )
            Text(
                "湿度适宜 ${suitabilityLabel(metrics.soilSuitability)} · 光照适宜 ${suitabilityLabel(metrics.lightSuitability)} · 互动适宜 ${suitabilityLabel(metrics.interactionSuitability)}",
                fontSize = 12.sp,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun CompanionScoreCard(metrics: DashboardMetrics) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("当日主人陪伴评分", fontWeight = FontWeight.Bold)
                Text(starScoreText(metrics.companionStars), color = Leaf, fontWeight = FontWeight.SemiBold)
            }
            StarRating(metrics.companionStars)
            Text(
                "今日对话 ${metrics.dailyDialogCount} 次 · 触摸 ${metrics.dailyTouchCount} 次 · 满 10 次为五星",
                fontSize = 12.sp,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun StarRating(stars: Float) {
    val filled = (stars + 0.5f).toInt().coerceIn(0, 5)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(5) { index ->
            Text(if (index < filled) "★" else "☆", color = Color(0xFFFFB000), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, status: String, threshold: String, modifier: Modifier) {
    ElevatedCard(modifier) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, color = Color.Gray); Text(value, fontSize = 27.sp, fontWeight = FontWeight.Bold); Text(status, color = Leaf, fontWeight = FontWeight.SemiBold); Text(threshold, fontSize = 12.sp, color = Color.Gray) } }
}

@Composable
private fun AdviceCard(title: String, lines: List<String>, color: Color = SoftLeaf) {
    Column(Modifier.fillMaxWidth().background(color, RoundedCornerShape(18.dp)).padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold); lines.forEach { Text("• $it", modifier = Modifier.padding(top = 5.dp)) } }
}

@Composable
private fun TelemetryChart(values: List<DeviceTelemetry>) {
    val points = values.takeLast(60)
    Canvas(Modifier.fillMaxWidth().height(150.dp).background(Color.White, RoundedCornerShape(18.dp)).padding(12.dp)) {
        if (points.size < 2) return@Canvas
        fun pathOf(get: (DeviceTelemetry) -> Float, max: Float): Path = Path().apply {
            points.forEachIndexed { index, item ->
                val x = size.width * index / (points.size - 1)
                val y = size.height - (get(item).coerceIn(0f, max) / max * size.height)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(pathOf({ it.soilPercent.toFloat() }, 100f), Leaf, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
        drawPath(pathOf({ it.lightPercent.toFloat() }, 100f), Color(0xFFF5B642), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawCircle(Leaf, 5f, Offset(8f, 8f)); drawCircle(Color(0xFFF5B642), 5f, Offset(80f, 8f))
    }
}

@Composable
private fun CareScreen(
    state: SmartPotUiState,
    addCare: (CareType, String) -> Unit,
    generateDiary: () -> Unit,
    recordPomodoro: () -> Unit,
    speakDiary: (PlantDiary) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    val careActions = listOf(CareType.WATER, CareType.FERTILIZE, CareType.PRUNE, CareType.REPOT, CareType.NEW_LEAF)
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
        item { Text("我与小麦一起成长", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { GrowthTimelineCard(state) }
        item { TodayCareDiaryWeatherCard(state, generateDiary, speakDiary) }
        item { FocusGrowthCard(state, recordPomodoro) }
        item {
            Text("养护日志", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                careActions.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { type ->
                            Button(onClick = { addCare(type, note); note = "" }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("${careEmoji(type)} ${careLabel(type)}") }
                        }
                    }
                }
            }
        }
        if (state.reminders.isNotEmpty()) item { AdviceCard("下一次养护", state.reminders.take(4).map { "${careLabel(it.type)} · ${it.dueAt.take(10)}" }) }
        item { Text("历史记录", fontWeight = FontWeight.Bold) }
        items(state.careLogs) { log -> ListItem(headlineContent = { Text(careLabel(log.type)) }, supportingContent = { Text("${log.occurredAt.take(16).replace('T', ' ')}  ${log.note}") }, leadingContent = { Text(careEmoji(log.type)) }) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("盆栽日记", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); OutlinedButton(onClick = generateDiary) { Text("生成今天日记") } } }
        items(state.diaries) { diary ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${diaryMoodEmoji(diary)} ${diary.diaryDate} · ${diary.title}", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { speakDiary(diary) }) { Text("ESP朗读") }
                    }
                    Text(diary.content)
                }
            }
        }
    }
}

@Composable
private fun GrowthTimelineCard(state: SmartPotUiState) {
    val events = growthTimeline(state)
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("成长时间轴", fontWeight = FontWeight.Bold)
            events.take(6).forEach { event ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text(event.emoji, fontSize = 20.sp)
                    Column {
                        Text("${event.date} · ${event.title}", fontWeight = FontWeight.SemiBold)
                        if (event.detail.isNotBlank()) Text(event.detail, fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCareDiaryWeatherCard(state: SmartPotUiState, generateDiary: () -> Unit, speakDiary: (PlantDiary) -> Unit) {
    val overview = state.careOverview
    val diary = state.diaries.firstOrNull { it.diaryDate == overview?.date } ?: state.diaries.firstOrNull()
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("今日天气与日记", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = generateDiary) { Text("写今天") }
            }
            overview?.weather?.let { weather ->
                Text("${weather.condition} · 平均光照 ${weather.averageLightLux?.toString() ?: "--"} lux · 最高 ${weather.maxLightLux?.toString() ?: "--"} lux", color = Leaf, fontWeight = FontWeight.SemiBold)
                Text(weather.hint, color = Color.Gray, fontSize = 12.sp)
            } ?: Text("等待今日天气", color = Color.Gray)
            diary?.let {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${diaryMoodEmoji(it)} ${it.title}", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { speakDiary(it) }) { Text("ESP朗读") }
                }
                Text(it.content)
            }
        }
    }
}

@Composable
private fun FocusGrowthCard(state: SmartPotUiState, recordPomodoro: () -> Unit) {
    val today = state.careOverview?.focus ?: state.focusDaily.lastOrNull()
    val completion = today?.scheduleCompletionPercent ?: 0
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("每日番茄钟", fontWeight = FontWeight.Bold)
                    Text("${today?.pomodoroCount ?: 0} 个 · ${today?.focusMinutes ?: 0} min", color = Color.Gray)
                }
                Text("$completion%", color = Leaf, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = { completion / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Leaf, trackColor = SoftLeaf)
            OutlinedButton(onClick = recordPomodoro, modifier = Modifier.fillMaxWidth()) { Text("补记 1 个番茄钟") }
            FocusTrendChart(state.focusDaily)
        }
    }
}

@Composable
private fun FocusTrendChart(values: List<DailyFocusSummary>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(150.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp)) {
            val points = values.takeLast(5)
            if (points.size < 2) return@Canvas
            fun pathOf(get: (DailyFocusSummary) -> Float, max: Float): Path = Path().apply {
                points.forEachIndexed { index, item ->
                    val x = size.width * index / (points.size - 1)
                    val y = size.height - (get(item).coerceIn(0f, max) / max * size.height)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            val maxMinutes = points.maxOf { it.focusMinutes }.coerceAtLeast(100).toFloat()
            drawPath(pathOf({ it.focusMinutes.toFloat() }, maxMinutes), Leaf, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            drawPath(pathOf({ it.scheduleCompletionPercent.toFloat() }, 100f), Color(0xFFF5B642), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            values.takeLast(5).forEach { Text(it.date.takeLast(5), fontSize = 11.sp, color = Color.Gray) }
        }
        Text("绿色=专注时间 · 黄色=日程完成度", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun ControlScreen(
    state: SmartPotUiState,
    control: (DeviceControlRequest) -> Unit,
    addSchedule: (String, String) -> Unit,
    toggleSchedule: (ScheduleItem, Boolean) -> Unit,
    createShare: () -> Unit,
    redeem: (String, String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var scheduleTitle by remember { mutableStateOf("") }
    var scheduleTime by remember { mutableStateOf("") }
    var brightness by remember { mutableFloatStateOf((state.snapshot?.deviceState?.brightnessPercent ?: 70).toFloat()) }
    var volume by remember { mutableFloatStateOf((state.snapshot?.deviceState?.volumePercent ?: 60).toFloat()) }
    var share by remember { mutableStateOf("") }
    val schedules = state.schedule?.items.orEmpty()

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item {
            Text("日程同步", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                scheduleTitle,
                { scheduleTitle = it.take(80) },
                label = { Text("新增日程") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    scheduleTime,
                    { scheduleTime = it.take(40) },
                    label = { Text("提醒时间") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        addSchedule(scheduleTitle, scheduleTime)
                        scheduleTitle = ""
                        scheduleTime = ""
                    },
                    enabled = scheduleTitle.isNotBlank(),
                ) { Text("添加") }
            }
        }
        if (schedules.isEmpty()) {
            item { Text("还没有同步日程", color = Color.Gray) }
        } else {
            items(schedules, key = { it.id }) { item ->
                ScheduleRow(item, onCheckedChange = { checked -> toggleSchedule(item, checked) })
            }
        }
        item {
            HorizontalDivider()
            Text("远程屏幕", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                text,
                { text = it.take(96) },
                label = { Text("短句（支持中文，最多 96 字符）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    control(DeviceControlRequest(DeviceCommandType.SHOW_CONTENT, text = text, durationSeconds = 30))
                    text = ""
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("同步到 ESP 屏幕") }
        }
        item {
            Text("内置表情", fontWeight = FontWeight.Bold)
            listOf("heart" to "❤️", "smile" to "😊", "happy" to "😄", "thirsty" to "🥤", "dark" to "🌙", "weak" to "🥺", "wave" to "👋", "star" to "⭐", "flower" to "🌸", "water" to "💧", "sun" to "☀️", "sleep" to "💤")
                .chunked(4)
                .forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { emoji ->
                            OutlinedButton(
                                onClick = { control(DeviceControlRequest(DeviceCommandType.SHOW_CONTENT, emojiId = emoji.first, durationSeconds = 2)) },
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            ) { Text(emoji.second, fontSize = 20.sp) }
                        }
                    }
                }
        }
        item { Text("屏幕亮度 ${brightness.toInt()}%"); Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0f..100f, onValueChangeFinished = { control(DeviceControlRequest(DeviceCommandType.SET_BRIGHTNESS, brightnessPercent = brightness.toInt())) }) }
        item { Text("回复音量 ${volume.toInt()}%"); Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..100f, onValueChangeFinished = { control(DeviceControlRequest(DeviceCommandType.SET_VOLUME, volumePercent = volume.toInt())) }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { control(DeviceControlRequest(DeviceCommandType.REMOTE_TOUCH)) }) { Text("隔空触摸 ❤️") }
                OutlinedButton(onClick = { control(DeviceControlRequest(DeviceCommandType.SET_STANDBY, standby = true)) }) { Text("休眠屏幕") }
                OutlinedButton(onClick = { control(DeviceControlRequest(DeviceCommandType.RESTART)) }) { Text("重启") }
            }
        }
        item { state.lastCommand?.let { Text(if (it.acknowledged) "设备已确认：${it.ack?.status}" else "命令已发送，设备暂未确认", color = if (it.acknowledged) Leaf else Color(0xFFA56A00)) } }
        item {
            HorizontalDivider()
            Text("双人共享", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(onClick = createShare) { Text("生成临时分享码") }
            state.shareCode?.let { Text("分享码 ${it.code}，有效至 ${it.expiresAt.take(16).replace('T', ' ')}", fontWeight = FontWeight.Bold) }
            OutlinedTextField(share, { share = it }, label = { Text("输入分享码") })
            OutlinedButton(onClick = { redeem(share, "共享伙伴") }) { Text("加入盆栽") }
        }
    }
}

@Composable
private fun ScheduleRow(item: ScheduleItem, onCheckedChange: (Boolean) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.completed, onCheckedChange = onCheckedChange)
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                val timeText = item.displayTime.ifBlank { item.dueAt?.take(16)?.replace('T', ' ') ?: "未设置提醒时间" }
                Text("${if (item.source == "ESP") "ESP 语音" else "手机"} · $timeText", fontSize = 12.sp, color = Color.Gray)
            }
            if (item.completed) Text("已完成", color = Leaf, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CompanionScreen(state: SmartPotUiState, send: (String) -> Unit, remember: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("和小麦聊聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("它会结合品种、实时状态和你的专属记忆回答。", color = Color.Gray) }
        items(state.messages) { message -> Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == ChatRole.USER) Arrangement.End else Arrangement.Start) { Surface(color = if (message.role == ChatRole.USER) Leaf else Color.White, shape = RoundedCornerShape(16.dp)) { Text(message.content, color = if (message.role == ChatRole.USER) Color.White else Color.DarkGray, modifier = Modifier.padding(12.dp).widthIn(max = 280.dp)) } } }
        item { OutlinedTextField(input, { input = it }, label = { Text("问问绿萝黄叶怎么办……") }, modifier = Modifier.fillMaxWidth()); Button(onClick = { if (input.isNotBlank()) { send(input); input = "" } }, modifier = Modifier.fillMaxWidth()) { Text("发送") } }
        item { HorizontalDivider(); Text("专属记忆库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); OutlinedTextField(memory, { memory = it }, label = { Text("生日、考试、加班时间或想被记住的事") }, modifier = Modifier.fillMaxWidth()); OutlinedButton(onClick = { if (memory.isNotBlank()) { remember(memory); memory = "" } }, modifier = Modifier.fillMaxWidth()) { Text("让小麦记住") } }
        items(state.memories) { item -> ListItem(headlineContent = { Text(item.content) }, leadingContent = { Text("🧠") }) }
    }
}

private fun soilLabel(value: SoilStatus?) = when (value) { SoilStatus.TOO_DRY -> "缺水"; SoilStatus.SUITABLE -> "适宜"; SoilStatus.TOO_WET -> "积水风险"; else -> "等待数据" }
private fun lightLabel(value: LightStatus?) = when (value) { LightStatus.DARK -> "阴暗"; LightStatus.DIFFUSE -> "散射光"; LightStatus.TOO_STRONG -> "强光"; else -> "等待数据" }
private fun careLabel(value: CareType) = when (value) { CareType.WATER -> "浇水"; CareType.FERTILIZE -> "施肥"; CareType.PRUNE -> "修剪"; CareType.REPOT -> "换盆"; CareType.NEW_LEAF -> "新叶"; CareType.OTHER -> "其他" }
private fun careEmoji(value: CareType) = when (value) { CareType.WATER -> "💧"; CareType.FERTILIZE -> "🌿"; CareType.PRUNE -> "✂"; CareType.REPOT -> "🪴"; CareType.NEW_LEAF -> "🌱"; CareType.OTHER -> "✓" }
private fun affinityLabel(value: AffinityLevel) = when (value) { AffinityLevel.STRANGER -> "初次见面"; AffinityLevel.FAMILIAR -> "渐渐熟悉"; AffinityLevel.CLOSE -> "亲密伙伴"; AffinityLevel.TRUSTED -> "彼此信赖"; AffinityLevel.BEST_FRIEND -> "最佳朋友" }

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
    val content = diary.content
    return when {
        content.contains("水") || content.contains("湿") -> "💧"
        content.contains("光") || content.contains("晒") -> "☀️"
        content.contains("叶") || content.contains("芽") -> "🌱"
        else -> "✨"
    }
}

private fun dashboardMetrics(state: SmartPotUiState): DashboardMetrics {
    val snap = state.snapshot
    val pot = snap?.pot
    val telemetry = snap?.telemetry
    val thresholds = pot?.species?.thresholds
    val zone = zoneIdOf(pot?.timezone)
    val today = LocalDate.now(zone)
    val history = telemetryWithLatest(state.telemetry, telemetry).filter { isSameLocalDate(it.recordedAt, today, zone) }
    val dailyTouchCount = dailyTouchCount(history, today, zone)
    val dailyDialogCount = state.messages.count { it.role == ChatRole.USER && isSameLocalDate(it.createdAt, today, zone) }
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
        soilSuitability = soilSuitability,
        lightSuitability = lightSuitability,
        interactionSuitability = PlantRules.interactionSuitability(dailyInteractions),
    )
}

private fun telemetryWithLatest(history: List<DeviceTelemetry>, latest: DeviceTelemetry?): List<DeviceTelemetry> {
    if (latest == null) return history
    return if (history.any { it.deviceId == latest.deviceId && it.sequence == latest.sequence }) history else history + latest
}

private fun dailyTouchCount(values: List<DeviceTelemetry>, today: LocalDate, zone: ZoneId): Int {
    val sorted = values.sortedBy { parseInstant(it.recordedAt) ?: Instant.EPOCH }
    if (sorted.isEmpty()) return 0
    val first = sorted.first()
    val bootedToday = parseInstant(first.recordedAt)
        ?.minusSeconds(first.uptimeSeconds)
        ?.atZone(zone)
        ?.toLocalDate() == today
    var total = if (bootedToday) first.touchCount.coerceAtLeast(0) else 0L
    var previous = first.touchCount
    sorted.drop(1).forEach { item ->
        val delta = item.touchCount - previous
        total += if (delta >= 0) delta else item.touchCount.coerceAtLeast(0)
        previous = item.touchCount
    }
    return total.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
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
