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

data class SmartPotUiState(
    val loading: Boolean = true,
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
    val messages: List<ChatMessage> = emptyList(),
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

    init { bootstrap() }

    fun bootstrap() = launchAction {
        val species = api.species()
        val pots = api.pots()
        mutableState.update { it.copy(species = species, pots = pots, selectedPotId = it.selectedPotId ?: pots.firstOrNull()?.id, loading = false) }
        mutableState.value.selectedPotId?.let { selectPot(it) }
    }

    fun createPot(deviceId: String, name: String, speciesId: String) = launchAction {
        val pot = api.createPot(CreatePotRequest(deviceId.trim(), name.trim(), speciesId))
        mutableState.update { it.copy(pots = it.pots + pot, selectedPotId = pot.id) }
        selectPot(pot.id)
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
        val careOverview = api.careOverview(id)
        val focusDaily = api.focusDaily(id)
        val schedule = api.schedule(id)
        val memories = api.memories(id)
        val messages = api.messages(id)
        val diaries = api.diaries(id)
        mutableState.update { it.copy(snapshot = snapshot, telemetry = telemetry, careLogs = care, reminders = reminders, careOverview = careOverview, focusDaily = focusDaily, schedule = schedule, memories = memories, messages = messages, diaries = diaries, loading = false, error = null) }
    }.onFailure { fail(it) }

    private fun startRealtime(id: String) {
        realtimeJob = viewModelScope.launch {
            while (isActive) {
                runCatching { api.realtime(id).collect { event -> if (event.type == RealtimeEventType.FOCUS || event.type == RealtimeEventType.DIARY || event.type == RealtimeEventType.SCHEDULE) refreshAll(id) else if (event.type != RealtimeEventType.COMMAND_ACK) refreshSnapshot(id) } }
                delay(3_000)
            }
        }
    }

    private suspend fun refreshSnapshot(id: String) = runCatching { api.snapshot(id) }
        .onSuccess { value -> mutableState.update { it.copy(snapshot = value, error = null) } }

    fun addCare(type: CareType, note: String) = withPot { id ->
        api.addCare(id, CreateCareLogRequest(type, note = note))
        mutableState.update { it.copy(careLogs = api.careLogs(id), reminders = api.reminders(id), careOverview = api.careOverview(id)) }
    }

    fun addMemory(text: String) = withPot { id -> mutableState.update { it.copy(memories = it.memories + api.addMemory(id, text)) } }

    fun sendChat(text: String) = withPot { id ->
        val reply = api.chat(id, text)
        mutableState.update { it.copy(messages = it.messages + reply.userMessage + reply.assistantMessage) }
    }

    fun generateDiary() = withPot { id ->
        val diary = api.generateDiary(id)
        mutableState.update { it.copy(diaries = (listOf(diary) + it.diaries).distinctBy(PlantDiary::id)) }
    }

    fun recordPomodoro() = withPot { id ->
        api.addFocusSession(id)
        mutableState.update { it.copy(careOverview = api.careOverview(id), focusDaily = api.focusDaily(id)) }
    }

    fun addSchedule(title: String, displayTime: String) = withPot { id ->
        api.addSchedule(id, CreateScheduleItemRequest(title = title, displayTime = displayTime))
        mutableState.update { it.copy(schedule = api.schedule(id), careOverview = api.careOverview(id), focusDaily = api.focusDaily(id)) }
    }

    fun toggleSchedule(item: ScheduleItem, completed: Boolean) = withPot { id ->
        api.updateSchedule(id, item.id, UpdateScheduleItemRequest(completed = completed))
        mutableState.update { it.copy(schedule = api.schedule(id), careOverview = api.careOverview(id), focusDaily = api.focusDaily(id)) }
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
        mutableState.update { it.copy(species = species, pots = pots, loading = false) }
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

    override fun onCleared() { api.close() }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SmartPotViewModel() as T
        }
    }
}
