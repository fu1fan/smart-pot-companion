package com.fu1fan.smartpot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fu1fan.smartpot.BuildConfig
import com.fu1fan.smartpot.data.SmartPotApi
import com.fu1fan.smartpot.protocol.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.round

data class SmartPotUiState(
    val loading: Boolean = true,
    val potsLoaded: Boolean = false,
    val species: List<PlantSpecies> = emptyList(),
    val pots: List<PotProfile> = emptyList(),
    val selectedPotId: String? = null,
    val snapshot: PotSnapshot? = null,
    val telemetry: List<DeviceTelemetry> = emptyList(),
    val careLogs: List<CareLog> = emptyList(),
    val reminders: List<CareReminder> = emptyList(),
    val careOverview: CareDayOverview? = null,
    val focusDaily: List<DailyFocusSummary> = emptyList(),
    val schedule: ScheduleSyncState? = null,
    val memories: List<UserMemory> = emptyList(),
    val chatDays: List<ChatDaySummary> = emptyList(),
    val selectedChatDate: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val todayMessages: List<ChatMessage> = emptyList(),
    val diaries: List<PlantDiary> = emptyList(),
    val lastCommand: CommandSubmission? = null,
    val shareCode: ShareCode? = null,
    val error: String? = null,
)

class SmartPotViewModel : ViewModel() {
    private var accessToken = BuildConfig.DEMO_TOKEN
    private val api = SmartPotApi(BuildConfig.DEFAULT_SERVER_URL) { accessToken }
    private val mutableState = MutableStateFlow(SmartPotUiState())
    val state: StateFlow<SmartPotUiState> = mutableState.asStateFlow()
    private var realtimeJob: Job? = null
    private var weatherLocation: Pair<Double, Double>? = null

    init { bootstrap() }

    fun bootstrap() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            val pots = runCatching { api.pots() }.getOrElse { error ->
                fail(error)
                return@launch
            }
            val speciesResult = runCatching { api.species() }
            mutableState.update { current ->
                current.copy(
                    species = speciesResult.getOrDefault(current.species),
                    pots = pots,
                    potsLoaded = true,
                    selectedPotId = current.selectedPotId?.takeIf { selected -> pots.any { it.id == selected } }
                        ?: pots.firstOrNull()?.id,
                    loading = false,
                    error = speciesResult.exceptionOrNull()?.message,
                )
            }
            mutableState.value.selectedPotId?.let(::selectPot)
        }
    }

    fun createPot(deviceId: String, name: String, speciesId: String) = launchAction {
        val pot = api.createPot(CreatePotRequest(deviceId.trim(), name.trim(), speciesId))
        mutableState.update { it.copy(pots = it.pots + pot, selectedPotId = pot.id) }
        selectPot(pot.id)
    }

    fun updateSpecies(speciesId: String) = withPot { id ->
        api.updatePot(id, UpdatePotRequest(speciesId = speciesId))
        val pots = api.pots()
        mutableState.update { it.copy(pots = pots) }
        refreshAll(id)
    }

    fun selectPot(id: String) {
        mutableState.update { it.copy(selectedPotId = id, loading = true) }
        realtimeJob?.cancel()
        viewModelScope.launch { refreshAll(id); startRealtime(id) }
    }

    fun refresh() { mutableState.value.selectedPotId?.let { id -> viewModelScope.launch { refreshAll(id) } } }

    private suspend fun refreshAll(id: String) = runCatching {
        val snapshot = api.snapshot(id)
        val telemetry = api.telemetry(id)
        val care = api.careLogs(id)
        val reminders = api.reminders(id)
        val careOverview = careOverview(id)
        val focusDaily = api.focusDaily(id)
        val schedule = api.schedule(id)
        val memories = api.memories(id)
        val chatDays = api.chatDays(id)
        val today = LocalDate.now(zoneId(snapshot.pot.timezone)).toString()
        val availableChatDays = (listOf(ChatDaySummary(today, 0)) + chatDays).distinctBy(ChatDaySummary::date)
        val selectedChatDate = mutableState.value.selectedChatDate
            ?.takeIf { selected -> availableChatDays.any { it.date == selected } }
            ?: today
        val messages = api.messages(id, selectedChatDate)
        val todayMessages = if (selectedChatDate == today) messages else api.messages(id, today)
        val diaries = api.diaries(id)
        mutableState.update {
            it.copy(
                snapshot = snapshot,
                telemetry = telemetry,
                careLogs = care,
                reminders = reminders,
                careOverview = careOverview,
                focusDaily = focusDaily,
                schedule = schedule,
                memories = memories,
                chatDays = availableChatDays,
                selectedChatDate = selectedChatDate,
                messages = messages,
                todayMessages = todayMessages,
                diaries = diaries,
                loading = false,
                error = null,
            )
        }
    }.onFailure { fail(it) }

    private fun startRealtime(id: String) {
        realtimeJob = viewModelScope.launch {
            while (isActive) {
                runCatching {
                    api.realtime(id).collect { event ->
                        when (event.type) {
                            RealtimeEventType.FOCUS, RealtimeEventType.DIARY, RealtimeEventType.SCHEDULE -> refreshAll(id)
                            RealtimeEventType.CHAT -> refreshChat(id)
                            RealtimeEventType.COMMAND_ACK -> Unit
                            else -> refreshSnapshot(id)
                        }
                    }
                }
                delay(3_000)
            }
        }
    }

    private suspend fun refreshSnapshot(id: String) = runCatching { api.snapshot(id) }
        .onSuccess { value -> mutableState.update { it.copy(snapshot = value, error = null) } }

    fun addCare(type: CareType, note: String) = withPot { id ->
        api.addCare(id, CreateCareLogRequest(type, note = note))
        mutableState.update { it.copy(careLogs = api.careLogs(id), reminders = api.reminders(id), careOverview = careOverview(id)) }
    }

    fun refreshWeather(latitude: Double, longitude: Double) = withPot { id ->
        val coarseLocation = round(latitude * 100.0) / 100.0 to round(longitude * 100.0) / 100.0
        weatherLocation = coarseLocation
        mutableState.update { it.copy(careOverview = careOverview(id), error = null) }
    }

    fun addMemory(text: String) = withPot { id -> mutableState.update { it.copy(memories = it.memories + api.addMemory(id, text)) } }

    fun deleteMemory(memory: UserMemory) = withPot { id ->
        api.deleteMemory(id, memory.id)
        mutableState.update { state -> state.copy(memories = state.memories.filterNot { it.id == memory.id }) }
    }

    fun sendChat(text: String) = withPot { id ->
        api.chat(id, text)
        val today = LocalDate.now(zoneId(mutableState.value.snapshot?.pot?.timezone)).toString()
        val days = api.chatDays(id)
        val messages = api.messages(id, today)
        mutableState.update {
            it.copy(
                chatDays = (listOf(ChatDaySummary(today, 0)) + days).distinctBy(ChatDaySummary::date),
                selectedChatDate = today,
                messages = messages,
                todayMessages = messages,
            )
        }
    }

    fun selectChatDay(date: String) = withPot { id ->
        val messages = api.messages(id, date)
        mutableState.update { it.copy(selectedChatDate = date, messages = messages) }
    }

    fun generateDiary() = withPot { id ->
        val diary = api.generateDiary(id)
        mutableState.update { it.copy(diaries = (listOf(diary) + it.diaries).distinctBy(PlantDiary::id)) }
    }

    fun saveDiary(title: String, content: String, imageDataUrls: List<String>, moodEmoji: String?) = withPot { id ->
        val diary = api.addDiary(id, CreateDiaryRequest(title, content, imageDataUrls, moodEmoji))
        mutableState.update { state -> state.copy(diaries = (listOf(diary) + state.diaries).distinctBy(PlantDiary::id)) }
    }

    fun recordPomodoro() = withPot { id ->
        api.addFocusSession(id)
        mutableState.update { it.copy(careOverview = careOverview(id), focusDaily = api.focusDaily(id)) }
    }

    fun removePomodoro() = withPot { id ->
        api.deleteLatestFocusSession(id)
        mutableState.update { it.copy(careOverview = careOverview(id), focusDaily = api.focusDaily(id)) }
    }

    fun addSchedule(title: String, dueAt: Instant) = withPot { id ->
        val timezone = mutableState.value.snapshot?.pot?.timezone ?: "Asia/Shanghai"
        api.addSchedule(
            id,
            CreateScheduleItemRequest(
                title = title,
                dueAt = dueAt.toString(),
                displayTime = scheduleDisplayTime(dueAt, timezone),
            ),
        )
        mutableState.update { it.copy(schedule = api.schedule(id), careOverview = careOverview(id), focusDaily = api.focusDaily(id)) }
    }

    fun toggleSchedule(item: ScheduleItem, completed: Boolean) = withPot { id ->
        api.updateSchedule(id, item.id, UpdateScheduleItemRequest(completed = completed))
        mutableState.update { it.copy(schedule = api.schedule(id), careOverview = careOverview(id), focusDaily = api.focusDaily(id)) }
    }

    fun speakDiary(diary: PlantDiary) = control(
        DeviceControlRequest(
            type = DeviceCommandType.SPEAK_TEXT,
            text = "${diary.title}。${diary.content}".take(96),
        ),
    )

    fun control(request: DeviceControlRequest) = withPot { id ->
        val result = api.control(id, request)
        mutableState.update { it.copy(lastCommand = result) }
    }

    fun createShare() = withPot { id -> mutableState.update { it.copy(shareCode = api.share(id)) } }

    fun redeemShare(code: String, actor: String) = launchAction {
        val session = api.redeem(code.trim(), actor.trim())
        accessToken = session.token
        mutableState.value = SmartPotUiState(loading = true, selectedPotId = session.potId)
        val species = api.species()
        val pots = api.pots()
        mutableState.update { it.copy(species = species, pots = pots, potsLoaded = true, loading = false) }
        selectPot(session.potId)
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    private fun withPot(action: suspend (String) -> Unit) {
        val id = mutableState.value.selectedPotId ?: return
        launchAction { action(id) }
    }

    private fun launchAction(action: suspend () -> Unit) = viewModelScope.launch {
        runCatching { action() }.onFailure(::fail)
    }

    private fun fail(error: Throwable) {
        mutableState.update { it.copy(loading = false, error = error.message ?: "网络请求失败") }
    }

    private suspend fun careOverview(id: String): CareDayOverview {
        val location = weatherLocation
        return api.careOverview(id, location?.first, location?.second)
    }

    private suspend fun refreshChat(id: String) {
        val today = LocalDate.now(zoneId(mutableState.value.snapshot?.pot?.timezone)).toString()
        val days = api.chatDays(id)
        val available = (listOf(ChatDaySummary(today, 0)) + days).distinctBy(ChatDaySummary::date)
        val selected = mutableState.value.selectedChatDate ?: today
        val messages = api.messages(id, selected)
        val todayMessages = if (selected == today) messages else api.messages(id, today)
        mutableState.update {
            it.copy(
                chatDays = available,
                selectedChatDate = selected,
                messages = messages,
                todayMessages = todayMessages,
                error = null,
            )
        }
    }

    private fun zoneId(timezone: String?): ZoneId =
        runCatching { ZoneId.of(timezone ?: "Asia/Shanghai") }.getOrDefault(ZoneId.of("Asia/Shanghai"))

    override fun onCleared() { api.close() }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SmartPotViewModel() as T
        }
    }
}

internal fun scheduleDisplayTime(dueAt: Instant, timezone: String): String {
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
    return DateTimeFormatter.ofPattern("MM-dd/HH:mm").format(dueAt.atZone(zone))
}
