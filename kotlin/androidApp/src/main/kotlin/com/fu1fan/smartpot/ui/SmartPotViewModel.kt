package com.fu1fan.smartpot.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
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
import java.net.NetworkInterface
import java.util.Collections
import kotlin.math.round
import kotlin.math.roundToInt

data class SmartPotUiState(
    val loading: Boolean = true,
    val inviteRequired: Boolean = false,
    val inviteSubmitting: Boolean = false,
    val userName: String = "主人",
    val userId: String = "",
    val userAvatarDataUrl: String? = null,
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
    val pomodoroRemainingSeconds: Int = 25 * 60,
    val pomodoroTimerRunning: Boolean = false,
    val pomodoroTimerEndEpochMs: Long = 0L,
    val lastCommand: CommandSubmission? = null,
    val shareCode: ShareCode? = null,
    val error: String? = null,
)

class SmartPotViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionPreferences = application.getSharedPreferences("smart_pot_session", Context.MODE_PRIVATE)
    private val debugDeviceBypass =
        BuildConfig.DEBUG_DEVICE_IP.isNotBlank() &&
            hasLocalNetworkAddress(BuildConfig.DEBUG_DEVICE_IP)
    private val storedSessionToken = sessionPreferences.getString(SessionTokenKey, null)
    private val storedSessionExpiresAt = sessionPreferences.getString(SessionExpiresAtKey, null)
    private val storedUserName = sessionPreferences.getString(UserNameKey, null)?.trim().orEmpty().ifBlank { "主人" }
    private val storedUserId = sessionPreferences.getString(UserIdKey, null)?.trim().orEmpty()
    private val storedUserAvatar = sessionPreferences.getString(UserAvatarKey, null)?.takeIf(String::isNotBlank)
    private val storedPomodoroEndEpochMs = sessionPreferences.getLong(PomodoroEndEpochMsKey, 0L)
    private var pomodoroSessionActive =
        sessionPreferences.getBoolean(PomodoroActiveKey, false) ||
            sessionPreferences.getBoolean(PomodoroRunningKey, false)
    private val restoredPomodoroRunning =
        sessionPreferences.getBoolean(PomodoroRunningKey, false) &&
            storedPomodoroEndEpochMs > System.currentTimeMillis()
    private val restoredPomodoroRemainingSeconds =
        if (restoredPomodoroRunning) {
            ((storedPomodoroEndEpochMs - System.currentTimeMillis() + 999L) / 1000L)
                .toInt()
                .coerceIn(1, PomodoroSessionSeconds)
        } else {
            sessionPreferences.getInt(PomodoroRemainingSecondsKey, PomodoroSessionSeconds)
                .coerceIn(1, PomodoroSessionSeconds)
        }
    private val storedSessionValid =
        !storedSessionToken.isNullOrBlank() &&
            storedSessionExpiresAt
                ?.let { expiry -> runCatching { Instant.parse(expiry).isAfter(Instant.now()) }.getOrDefault(false) }
                ?: false
    private var accessToken =
        if (debugDeviceBypass) BuildConfig.DEMO_TOKEN else storedSessionToken?.takeIf { storedSessionValid } ?: BuildConfig.DEMO_TOKEN
    private val api = SmartPotApi(BuildConfig.DEFAULT_SERVER_URL) { accessToken }
    private val mutableState = MutableStateFlow(
        SmartPotUiState(
            loading = debugDeviceBypass || storedSessionValid,
            inviteRequired = !debugDeviceBypass && !storedSessionValid,
            userName = storedUserName,
            userId = storedUserId,
            userAvatarDataUrl = storedUserAvatar,
            pomodoroRemainingSeconds = restoredPomodoroRemainingSeconds,
            pomodoroTimerRunning = restoredPomodoroRunning,
            pomodoroTimerEndEpochMs = storedPomodoroEndEpochMs.takeIf { restoredPomodoroRunning } ?: 0L,
        ),
    )
    val state: StateFlow<SmartPotUiState> = mutableState.asStateFlow()
    private var realtimeJob: Job? = null
    private var pomodoroTimerJob: Job? = null
    private var weatherLocation: Pair<Double, Double>? = null

    init {
        if (storedSessionToken != null && !storedSessionValid) {
            sessionPreferences.edit().remove(SessionTokenKey).remove(SessionExpiresAtKey).apply()
        }
        if (restoredPomodoroRunning) {
            startPomodoroTicker()
        } else if (sessionPreferences.getBoolean(PomodoroRunningKey, false)) {
            resetPomodoroTimer()
        }
        if (!mutableState.value.inviteRequired) bootstrap()
    }

    fun bootstrap() {
        if (mutableState.value.inviteRequired) return
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
            launch {
                while (isActive) {
                    delay(10_000)
                    refreshSnapshot(id)
                }
            }
            while (isActive) {
                runCatching {
                    api.realtime(id).collect { event ->
                        when (event.type) {
                            RealtimeEventType.FOCUS, RealtimeEventType.DIARY, RealtimeEventType.SCHEDULE, RealtimeEventType.MEMORY -> refreshAll(id)
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

    fun addCare(type: CareType, note: String, imageDataUrl: String?) {
        val potId = mutableState.value.selectedPotId ?: return
        val optimisticId = "pending-${System.nanoTime()}"
        val optimisticLog = CareLog(
            id = optimisticId,
            potId = potId,
            type = type,
            occurredAt = Instant.now().toString(),
            note = note,
            actorName = "主人",
            imageDataUrl = imageDataUrl,
        )
        mutableState.update { state ->
            state.copy(
                careLogs = listOf(optimisticLog) + state.careLogs,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                val savedLog = api.addCare(potId, CreateCareLogRequest(type, note = note, imageDataUrl = imageDataUrl))
                val reminders = runCatching { api.reminders(potId) }.getOrNull()
                val overview = runCatching { careOverview(potId) }.getOrNull()
                Triple(savedLog, reminders, overview)
            }.onSuccess { (savedLog, reminders, overview) ->
                mutableState.update { state ->
                    if (state.selectedPotId != potId) state
                    else state.copy(
                        careLogs = (listOf(savedLog) + state.careLogs.filterNot {
                            it.id == optimisticId || it.id == savedLog.id
                        }).sortedByDescending { it.occurredAt },
                        reminders = reminders ?: state.reminders,
                        careOverview = overview ?: state.careOverview,
                    )
                }
            }.onFailure { error ->
                mutableState.update { state ->
                    if (state.selectedPotId != potId) state
                    else state.copy(
                        careLogs = state.careLogs.filterNot { it.id == optimisticId },
                        error = error.message ?: "添加养护记录失败",
                    )
                }
            }
        }
    }

    fun deleteCare(careLogId: String) {
        val potId = mutableState.value.selectedPotId ?: return
        val removedLog = mutableState.value.careLogs.firstOrNull { it.id == careLogId } ?: return
        mutableState.update { state ->
            state.copy(
                careLogs = state.careLogs.filterNot { it.id == careLogId },
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { api.deleteCare(potId, careLogId) }
                .onSuccess {
                    val snapshot = runCatching { api.snapshot(potId) }.getOrNull()
                    val overview = runCatching { careOverview(potId) }.getOrNull()
                    mutableState.update { state ->
                        if (state.selectedPotId != potId) state
                        else state.copy(
                            snapshot = snapshot ?: state.snapshot,
                            careOverview = overview ?: state.careOverview,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { state ->
                        if (state.selectedPotId != potId) state
                        else state.copy(
                            careLogs = (state.careLogs + removedLog)
                                .distinctBy { it.id }
                                .sortedByDescending { it.occurredAt },
                            error = error.message ?: "删除养护记录失败",
                        )
                    }
                }
        }
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
        val memories = api.memories(id)
        mutableState.update {
            it.copy(
                chatDays = (listOf(ChatDaySummary(today, 0)) + days).distinctBy(ChatDaySummary::date),
                selectedChatDate = today,
                messages = messages,
                todayMessages = messages,
                memories = memories,
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

    fun saveDiary(title: String, content: String, imageDataUrls: List<String>, moodEmoji: String?, authorName: String?) = withPot { id ->
        val diary = api.addDiary(id, CreateDiaryRequest(title, content, imageDataUrls, moodEmoji, authorName))
        mutableState.update { state -> state.copy(diaries = (listOf(diary) + state.diaries).distinctBy(PlantDiary::id)) }
    }

    fun deleteDiary(diary: PlantDiary) {
        if (diary.author != DiaryAuthor.USER) return
        withPot { id ->
            api.deleteDiary(id, diary.id)
            mutableState.update { state -> state.copy(diaries = state.diaries.filterNot { it.id == diary.id }) }
        }
    }

    fun recordPomodoro() {
        val id = mutableState.value.selectedPotId ?: return
        adjustPomodoroLocally(1)
        viewModelScope.launch {
            runCatching { api.addFocusSession(id) }
                .onFailure {
                    adjustPomodoroLocally(-1)
                    fail(it)
                }
        }
    }

    fun removePomodoro() {
        val id = mutableState.value.selectedPotId ?: return
        val count = mutableState.value.careOverview?.focus?.pomodoroCount
            ?: mutableState.value.focusDaily.lastOrNull()?.pomodoroCount
            ?: 0
        if (count <= 0) return
        adjustPomodoroLocally(-1)
        viewModelScope.launch {
            runCatching { api.deleteLatestFocusSession(id) }
                .onFailure {
                    adjustPomodoroLocally(1)
                    fail(it)
                }
        }
    }

    fun startPomodoroTimer() {
        val current = mutableState.value
        if (current.pomodoroTimerRunning) return
        val remaining = current.pomodoroRemainingSeconds.coerceIn(1, PomodoroSessionSeconds)
        control(
            DeviceControlRequest(
                if (pomodoroSessionActive) DeviceCommandType.RESUME_POMODORO else DeviceCommandType.START_POMODORO,
            ),
        )
        pomodoroSessionActive = true
        val endEpochMs = System.currentTimeMillis() + remaining * 1_000L
        sessionPreferences.edit()
            .putBoolean(PomodoroActiveKey, true)
            .putBoolean(PomodoroRunningKey, true)
            .putLong(PomodoroEndEpochMsKey, endEpochMs)
            .putInt(PomodoroRemainingSecondsKey, remaining)
            .apply()
        mutableState.update {
            it.copy(
                pomodoroTimerRunning = true,
                pomodoroTimerEndEpochMs = endEpochMs,
                pomodoroRemainingSeconds = remaining,
            )
        }
        startPomodoroTicker()
    }

    fun pausePomodoroTimer() {
        val current = mutableState.value
        if (!current.pomodoroTimerRunning) return
        val remaining = (
            (current.pomodoroTimerEndEpochMs - System.currentTimeMillis() + 999L) / 1_000L
            ).toInt().coerceIn(1, PomodoroSessionSeconds)
        pomodoroTimerJob?.cancel()
        control(DeviceControlRequest(DeviceCommandType.PAUSE_POMODORO))
        sessionPreferences.edit()
            .putBoolean(PomodoroActiveKey, true)
            .putBoolean(PomodoroRunningKey, false)
            .remove(PomodoroEndEpochMsKey)
            .putInt(PomodoroRemainingSecondsKey, remaining)
            .apply()
        mutableState.update {
            it.copy(
                pomodoroTimerRunning = false,
                pomodoroTimerEndEpochMs = 0L,
                pomodoroRemainingSeconds = remaining,
            )
        }
    }

    fun exitPomodoroTimer() {
        if (pomodoroSessionActive || mutableState.value.pomodoroTimerRunning) {
            control(DeviceControlRequest(DeviceCommandType.STOP_POMODORO))
        }
        resetPomodoroTimer()
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

    fun toggleSchedule(item: ScheduleItem, completed: Boolean) {
        val id = mutableState.value.selectedPotId ?: return
        val changedAt = Instant.now().toString()
        updateScheduleItemLocally(
            item.copy(
                completed = completed,
                completedAt = if (completed) changedAt else null,
                updatedAt = changedAt,
            ),
        )
        viewModelScope.launch {
            runCatching { api.updateSchedule(id, item.id, UpdateScheduleItemRequest(completed = completed)) }
                .onSuccess(::updateScheduleItemLocally)
                .onFailure { error ->
                    val current = mutableState.value.schedule?.items?.firstOrNull { it.id == item.id }
                    if (current?.completed == completed) updateScheduleItemLocally(item)
                    fail(error)
                }
        }
    }

    fun speakDiary(diary: PlantDiary) {
        val id = mutableState.value.selectedPotId ?: return
        val chunks = splitTextForTts("${diary.title}。${diary.content}")
        launchAction {
            chunks.forEachIndexed { index, chunk ->
                val result = api.control(id, DeviceControlRequest(type = DeviceCommandType.SPEAK_TEXT, text = chunk))
                mutableState.update { it.copy(lastCommand = result) }
                check(result.acknowledged && result.ack?.status != CommandAckStatus.FAILED) {
                    "ESP 未能接收日记第 ${index + 1}/${chunks.size} 段，请稍后重试"
                }
            }
        }
    }

    fun control(request: DeviceControlRequest) = withPot { id ->
        val result = api.control(id, request)
        mutableState.update { it.copy(lastCommand = result) }
    }

    fun createShare() = withPot { id -> mutableState.update { it.copy(shareCode = api.share(id)) } }

    fun redeemInvite(code: String) {
        val normalized = code.trim()
        if (normalized.length != 6) {
            mutableState.update { it.copy(error = "请输入 6 位邀请码") }
            return
        }
        redeemShareSession(normalized, mutableState.value.userName, showInviteProgress = true)
    }

    fun redeemShare(code: String, actor: String) =
        redeemShareSession(code.trim(), actor.trim(), showInviteProgress = false)

    fun saveUserProfile(name: String, userId: String, avatarDataUrl: String?) {
        val normalizedName = name.trim().take(20).ifBlank { "主人" }
        val normalizedUserId = userId.trim().take(32)
        sessionPreferences.edit()
            .putString(UserNameKey, normalizedName)
            .putString(UserIdKey, normalizedUserId)
            .apply {
                if (avatarDataUrl.isNullOrBlank()) remove(UserAvatarKey) else putString(UserAvatarKey, avatarDataUrl)
            }
            .apply()
        mutableState.update {
            it.copy(
                userName = normalizedName,
                userId = normalizedUserId,
                userAvatarDataUrl = avatarDataUrl?.takeIf(String::isNotBlank),
            )
        }
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    private fun withPot(action: suspend (String) -> Unit) {
        val id = mutableState.value.selectedPotId ?: return
        launchAction { action(id) }
    }

    private fun launchAction(action: suspend () -> Unit) = viewModelScope.launch {
        runCatching { action() }.onFailure(::fail)
    }

    private fun redeemShareSession(code: String, actor: String, showInviteProgress: Boolean) {
        if (code.isBlank()) return
        viewModelScope.launch {
            if (showInviteProgress) {
                mutableState.update { it.copy(inviteSubmitting = true, error = null) }
            }
            runCatching {
                val session = api.redeem(code, actor.ifBlank { "共享伙伴" })
                accessToken = session.token
                sessionPreferences.edit()
                    .putString(SessionTokenKey, session.token)
                    .putString(SessionExpiresAtKey, session.expiresAt)
                    .apply()
                realtimeJob?.cancel()
                mutableState.value = SmartPotUiState(
                    loading = true,
                    inviteRequired = false,
                    inviteSubmitting = false,
                    userName = mutableState.value.userName,
                    userId = mutableState.value.userId,
                    userAvatarDataUrl = mutableState.value.userAvatarDataUrl,
                    pomodoroRemainingSeconds = mutableState.value.pomodoroRemainingSeconds,
                    pomodoroTimerRunning = mutableState.value.pomodoroTimerRunning,
                    pomodoroTimerEndEpochMs = mutableState.value.pomodoroTimerEndEpochMs,
                    selectedPotId = session.potId,
                )
                val species = api.species()
                val pots = api.pots()
                mutableState.update {
                    it.copy(
                        species = species,
                        pots = pots,
                        potsLoaded = true,
                        loading = false,
                        error = null,
                    )
                }
                selectPot(session.potId)
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        loading = false,
                        inviteSubmitting = false,
                        error = error.message ?: "邀请码无效或已过期",
                    )
                }
            }
        }
    }

    private fun fail(error: Throwable) {
        mutableState.update { it.copy(loading = false, error = error.message ?: "网络请求失败") }
    }

    private fun adjustPomodoroLocally(delta: Int) {
        mutableState.update { state ->
            val zone = zoneId(state.snapshot?.pot?.timezone)
            val todayDate = LocalDate.now(zone)
            val today = todayDate.toString()
            val current = state.careOverview?.focus
                ?: state.focusDaily.firstOrNull { it.date == today }
                ?: return@update state
            val updatedCount = (current.pomodoroCount + delta).coerceAtLeast(0)
            val updated = current.copy(
                pomodoroCount = updatedCount,
                focusMinutes = (current.focusMinutes + delta * 25).coerceAtLeast(0),
                scheduleCompletionPercent =
                    (updatedCount.toDouble() / current.targetPomodoroCount.coerceAtLeast(1) * 100)
                        .roundToInt()
                        .coerceIn(0, 100),
            )
            val focusDaily = if (state.focusDaily.any { it.date == today }) {
                state.focusDaily.map { if (it.date == today) updated else it }
            } else {
                state.focusDaily + updated
            }
            state.copy(
                careOverview = state.careOverview?.let { overview ->
                    if (overview.date == today) overview.copy(focus = updated) else overview
                },
                focusDaily = focusDaily,
            )
        }
    }

    private fun updateScheduleItemLocally(updatedItem: ScheduleItem) {
        mutableState.update { state ->
            val schedule = state.schedule ?: return@update state
            state.copy(
                schedule = schedule.copy(
                    revision = schedule.revision + 1,
                    items = schedule.items.map { if (it.id == updatedItem.id) updatedItem else it },
                ),
            )
        }
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

    private fun startPomodoroTicker() {
        pomodoroTimerJob?.cancel()
        pomodoroTimerJob = viewModelScope.launch {
            while (isActive) {
                val current = mutableState.value
                if (!current.pomodoroTimerRunning || current.pomodoroTimerEndEpochMs <= 0L) break
                val remaining = (
                    (current.pomodoroTimerEndEpochMs - System.currentTimeMillis() + 999L) / 1_000L
                    ).toInt()
                if (remaining <= 0) {
                    control(DeviceControlRequest(DeviceCommandType.STOP_POMODORO))
                    resetPomodoroTimer()
                    recordPomodoro()
                    break
                }
                if (remaining != current.pomodoroRemainingSeconds) {
                    mutableState.update { it.copy(pomodoroRemainingSeconds = remaining.coerceAtMost(PomodoroSessionSeconds)) }
                }
                delay(250)
            }
        }
    }

    private fun resetPomodoroTimer() {
        pomodoroTimerJob?.cancel()
        pomodoroSessionActive = false
        sessionPreferences.edit()
            .putBoolean(PomodoroActiveKey, false)
            .putBoolean(PomodoroRunningKey, false)
            .remove(PomodoroEndEpochMsKey)
            .putInt(PomodoroRemainingSecondsKey, PomodoroSessionSeconds)
            .apply()
        mutableState.update {
            it.copy(
                pomodoroRemainingSeconds = PomodoroSessionSeconds,
                pomodoroTimerRunning = false,
                pomodoroTimerEndEpochMs = 0L,
            )
        }
    }

    override fun onCleared() {
        pomodoroTimerJob?.cancel()
        api.close()
    }

    companion object {
        private const val SessionTokenKey = "share_session_token"
        private const val SessionExpiresAtKey = "share_session_expires_at"
        private const val UserNameKey = "user_name"
        private const val UserIdKey = "user_id"
        private const val UserAvatarKey = "user_avatar_data_url"
        private const val PomodoroRunningKey = "pomodoro_running"
        private const val PomodoroActiveKey = "pomodoro_active"
        private const val PomodoroEndEpochMsKey = "pomodoro_end_epoch_ms"
        private const val PomodoroRemainingSecondsKey = "pomodoro_remaining_seconds"
        private const val PomodoroSessionSeconds = 25 * 60
    }
}

private fun hasLocalNetworkAddress(expectedAddress: String): Boolean = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces()).any { networkInterface ->
        Collections.list(networkInterface.inetAddresses).any { address ->
            address.hostAddress?.substringBefore('%') == expectedAddress
        }
    }
}.getOrDefault(false)

internal fun scheduleDisplayTime(dueAt: Instant, timezone: String): String {
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Asia/Shanghai"))
    return DateTimeFormatter.ofPattern("MM-dd/HH:mm").format(dueAt.atZone(zone))
}

internal fun splitTextForTts(text: String, maxUtf8Bytes: Int = 220, maxChars: Int = 90): List<String> {
    require(maxUtf8Bytes >= 16)
    require(maxChars >= 8)
    val normalized = text.trim()
    if (normalized.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var currentBytes = 0
    var offset = 0

    fun flush() {
        current.toString().trim().takeIf(String::isNotEmpty)?.let(chunks::add)
        current.clear()
        currentBytes = 0
    }

    while (offset < normalized.length) {
        val codePoint = normalized.codePointAt(offset)
        val token = String(Character.toChars(codePoint))
        val tokenBytes = token.toByteArray(Charsets.UTF_8).size
        if ((currentBytes + tokenBytes > maxUtf8Bytes || current.length + token.length > maxChars) && current.isNotEmpty()) flush()
        current.append(token)
        currentBytes += tokenBytes
        offset += Character.charCount(codePoint)

        val sentenceEnd = codePoint in intArrayOf('。'.code, '！'.code, '？'.code, '!'.code, '?'.code, ';'.code, '\n'.code)
        if (sentenceEnd && currentBytes >= maxUtf8Bytes * 2 / 3) flush()
    }
    flush()
    return chunks
}
