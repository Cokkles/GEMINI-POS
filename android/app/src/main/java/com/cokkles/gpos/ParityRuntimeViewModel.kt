package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.cokkles.gpos.data.CanonicalCachePolicy
import com.cokkles.gpos.data.local.CanonicalSnapshotEntity
import com.cokkles.gpos.data.local.GposDatabase
import com.cokkles.gpos.data.remote.AegisBackendClient
import com.cokkles.gpos.data.remote.CalendarRangePayloadMapper
import com.cokkles.gpos.data.remote.CalendarRangeSnapshot
import com.cokkles.gpos.data.remote.CapabilityPayloadMapper
import com.cokkles.gpos.data.remote.CapabilitySnapshot
import com.cokkles.gpos.data.remote.IntelligencePayloadMapper
import com.cokkles.gpos.data.remote.IntelligenceSnapshot
import com.cokkles.gpos.data.remote.NutritionPayloadMapper
import com.cokkles.gpos.data.remote.NutritionSnapshot
import com.cokkles.gpos.data.remote.RuntimeDataSource
import com.cokkles.gpos.data.remote.TaskHistoryItem
import com.cokkles.gpos.data.remote.TaskHistoryPayloadMapper
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.sync.AppointmentReminderScheduler
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class ParityDomainState<T>(
    val snapshot: T? = null,
    val source: RuntimeDataSource? = null,
    val fetchedAtEpochMs: Long? = null,
    val error: String? = null,
    val contractAvailable: Boolean = true,
)

data class ParityUiState(
    val capabilities: CapabilitySnapshot? = null,
    val capabilityError: String? = null,
    val calendar: ParityDomainState<CalendarRangeSnapshot> = ParityDomainState(),
    val intelligence: ParityDomainState<IntelligenceSnapshot> = ParityDomainState(),
    val nutrition: ParityDomainState<NutritionSnapshot> = ParityDomainState(),
    val taskHistory: ParityDomainState<List<TaskHistoryItem>> = ParityDomainState(),
    val selectedMonth: LocalDate = LocalDate.now().withDayOfMonth(1),
    val selectedDate: LocalDate = LocalDate.now(),
    val nutritionDays: Int = 30,
    val taskHistoryDays: Int = 7,
    val refreshing: Boolean = false,
)

class ParityRuntimeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val backend = AegisBackendClient()
    private val credentialStore = AndroidKeystoreCredentialStore(application)
    private val appointmentReminderScheduler = AppointmentReminderScheduler(application)
    private val cacheDao = Room.databaseBuilder(
        application,
        GposDatabase::class.java,
        CanonicalCachePolicy.DATABASE_NAME,
    ).build().canonicalSnapshotDao()

    private val _state = MutableStateFlow(ParityUiState())
    val state: StateFlow<ParityUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { loadProtectedCaches() }
    }

    private var refreshJob: Job? = null
    private var calendarRequest = 0L

    fun refreshAll() = refreshAll(showProgress = true)

    fun refreshAll(showProgress: Boolean) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val credential = credentialStore.read() ?: return@launch
            if (credential.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true) return@launch
            if (showProgress) _state.update { it.copy(refreshing = true) }
            try {
                val capabilities = runCatching { CapabilityPayloadMapper.map(backend.readCapabilities(credential.idToken)) }
                capabilities.onSuccess { snapshot ->
                    _state.update { it.copy(capabilities = snapshot, capabilityError = null) }
                }.onFailure { error ->
                    _state.update { it.copy(capabilityError = error.safeParityMessage()) }
                }

                refreshCalendarInternal(credential.idToken)
                refreshIntelligenceInternal(credential.idToken, force = false)

                val caps = capabilities.getOrNull() ?: _state.value.capabilities
                if (caps?.nutritionHistoryV1 == true) {
                    refreshNutritionInternal(credential.idToken, _state.value.nutritionDays)
                } else {
                    _state.update {
                        it.copy(
                            nutrition = it.nutrition.copy(
                                contractAvailable = false,
                                error = "Nutrition history contract is not advertised by the backend. Dashboard calories remain available.",
                            ),
                        )
                    }
                }
                if (caps?.tasksHistoryV1 == true) {
                    refreshTaskHistoryInternal(credential.idToken, _state.value.taskHistoryDays)
                } else {
                    _state.update {
                        it.copy(
                            taskHistory = it.taskHistory.copy(
                                contractAvailable = false,
                                error = "Task history contract is not advertised. Active Tasks and delayed completion remain available.",
                            ),
                        )
                    }
                }
            } finally {
                if (showProgress) _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun shiftMonth(deltaMonths: Long) {
        _state.update { current ->
            val month = current.selectedMonth.plusMonths(deltaMonths).withDayOfMonth(1)
            current.copy(selectedMonth = month, selectedDate = month)
        }
        refreshCalendar()
    }

    fun goToToday() {
        val today = LocalDate.now()
        _state.update { it.copy(selectedMonth = today.withDayOfMonth(1), selectedDate = today) }
        refreshCalendar()
    }

    fun selectDate(date: LocalDate) {
        _state.update { it.copy(selectedDate = date) }
    }

    fun refreshCalendar() {
        viewModelScope.launch {
            val credential = credentialStore.read() ?: return@launch
            refreshCalendarInternal(credential.idToken)
        }
    }

    fun refreshIntelligence() {
        viewModelScope.launch {
            val credential = credentialStore.read() ?: return@launch
            refreshIntelligenceInternal(credential.idToken, force = false)
        }
    }

    fun setNutritionDays(days: Int) {
        val bounded = if (days <= 7) 7 else 30
        _state.update { it.copy(nutritionDays = bounded) }
        if (_state.value.capabilities?.nutritionHistoryV1 == true) {
            viewModelScope.launch {
                val credential = credentialStore.read() ?: return@launch
                refreshNutritionInternal(credential.idToken, bounded)
            }
        }
    }

    fun setTaskHistoryDays(days: Int) {
        val bounded = if (days <= 7) 7 else 30
        _state.update { it.copy(taskHistoryDays = bounded) }
        if (_state.value.capabilities?.tasksHistoryV1 == true) {
            viewModelScope.launch {
                val credential = credentialStore.read() ?: return@launch
                refreshTaskHistoryInternal(credential.idToken, bounded)
            }
        }
    }

    private suspend fun refreshCalendarInternal(idToken: String) {
        val request = ++calendarRequest
        val range = monthRange(_state.value.selectedMonth)
        runCatching {
            val json = backend.readCalendarRange(idToken, range.first.toString(), range.second.toString())
            if (request != calendarRequest || range != monthRange(_state.value.selectedMonth)) return
            val fetchedAt = System.currentTimeMillis()
            val cachedJson = JSONObject(json.toString())
                .put("_android_start_date", range.first.toString())
                .put("_android_end_date", range.second.toString())
            cache(
                CanonicalCachePolicy.CALENDAR_RANGE_KEY,
                CanonicalCachePolicy.CALENDAR_RANGE_CONTRACT,
                CanonicalCachePolicy.CALENDAR_RANGE_FRESHNESS_MS,
                cachedJson,
                fetchedAt,
            )
            ParityDomainState(
                snapshot = CalendarRangePayloadMapper.map(json, range.first.toString(), range.second.toString()),
                source = RuntimeDataSource.LIVE,
                fetchedAtEpochMs = fetchedAt,
            )
        }.onSuccess { domain ->
            if (request == calendarRequest && range == monthRange(_state.value.selectedMonth)) {
                domain.snapshot?.let { appointmentReminderScheduler.replaceUpcoming(it) }
                _state.update { it.copy(calendar = domain) }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            if (request != calendarRequest || range != monthRange(_state.value.selectedMonth)) return
            if (!loadCachedCalendar(error.safeParityMessage())) {
                _state.update { it.copy(calendar = it.calendar.copy(error = error.safeParityMessage())) }
            }
        }
    }

    private suspend fun refreshIntelligenceInternal(idToken: String, force: Boolean) {
        runCatching {
            val json = backend.readIntelligence(idToken, force)
            val fetchedAt = System.currentTimeMillis()
            cache(
                CanonicalCachePolicy.INTELLIGENCE_KEY,
                CanonicalCachePolicy.INTELLIGENCE_CONTRACT,
                CanonicalCachePolicy.INTELLIGENCE_FRESHNESS_MS,
                json,
                fetchedAt,
            )
            ParityDomainState(
                snapshot = IntelligencePayloadMapper.map(json),
                source = RuntimeDataSource.LIVE,
                fetchedAtEpochMs = fetchedAt,
            )
        }.onSuccess { domain ->
            _state.update { it.copy(intelligence = domain) }
        }.onFailure { error ->
            if (!loadCachedIntelligence(error.safeParityMessage())) {
                _state.update { it.copy(intelligence = it.intelligence.copy(error = error.safeParityMessage())) }
            }
        }
    }

    private suspend fun refreshNutritionInternal(idToken: String, days: Int) {
        runCatching {
            val json = backend.readNutritionSummary(idToken, days)
            val fetchedAt = System.currentTimeMillis()
            val cachedJson = JSONObject(json.toString()).put("_android_days", days)
            cache(
                CanonicalCachePolicy.NUTRITION_KEY,
                CanonicalCachePolicy.NUTRITION_CONTRACT,
                CanonicalCachePolicy.NUTRITION_FRESHNESS_MS,
                cachedJson,
                fetchedAt,
            )
            ParityDomainState(
                snapshot = NutritionPayloadMapper.map(json, days),
                source = RuntimeDataSource.LIVE,
                fetchedAtEpochMs = fetchedAt,
                contractAvailable = true,
            )
        }.onSuccess { domain ->
            _state.update { it.copy(nutrition = domain) }
        }.onFailure { error ->
            if (!loadCachedNutrition(error.safeParityMessage())) {
                _state.update { it.copy(nutrition = it.nutrition.copy(error = error.safeParityMessage())) }
            }
        }
    }

    private suspend fun refreshTaskHistoryInternal(idToken: String, days: Int) {
        runCatching {
            val json = backend.readTaskHistory(idToken, days)
            val fetchedAt = System.currentTimeMillis()
            val cachedJson = JSONObject(json.toString()).put("_android_days", days)
            cache(
                CanonicalCachePolicy.TASK_HISTORY_KEY,
                CanonicalCachePolicy.TASK_HISTORY_CONTRACT,
                CanonicalCachePolicy.TASK_HISTORY_FRESHNESS_MS,
                cachedJson,
                fetchedAt,
            )
            ParityDomainState(
                snapshot = TaskHistoryPayloadMapper.map(json),
                source = RuntimeDataSource.LIVE,
                fetchedAtEpochMs = fetchedAt,
                contractAvailable = true,
            )
        }.onSuccess { domain ->
            _state.update { it.copy(taskHistory = domain) }
        }.onFailure { error ->
            if (!loadCachedTaskHistory(error.safeParityMessage())) {
                _state.update { it.copy(taskHistory = it.taskHistory.copy(error = error.safeParityMessage())) }
            }
        }
    }

    private suspend fun loadProtectedCaches() {
        if (credentialStore.read() == null) return
        loadCachedCalendar(null)
        loadCachedIntelligence(null)
        loadCachedNutrition(null)
        loadCachedTaskHistory(null)
    }

    private suspend fun loadCachedCalendar(error: String?): Boolean {
        val cached = cacheDao.read(CanonicalCachePolicy.CALENDAR_RANGE_KEY) ?: return false
        val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull() ?: return false
        val start = json.optString("_android_start_date").ifBlank { return false }
        val end = json.optString("_android_end_date").ifBlank { return false }
        val snapshot = runCatching { CalendarRangePayloadMapper.map(json, start, end) }.getOrNull() ?: return false
        appointmentReminderScheduler.replaceUpcoming(snapshot)
        _state.update {
            it.copy(
                calendar = ParityDomainState(
                    snapshot,
                    cachedSource(cached.staleAfterEpochMs),
                    cached.fetchedAtEpochMs,
                    error,
                ),
            )
        }
        return true
    }

    private suspend fun loadCachedIntelligence(error: String?): Boolean {
        val cached = cacheDao.read(CanonicalCachePolicy.INTELLIGENCE_KEY) ?: return false
        val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull() ?: return false
        val snapshot = runCatching { IntelligencePayloadMapper.map(json) }.getOrNull() ?: return false
        _state.update {
            it.copy(
                intelligence = ParityDomainState(
                    snapshot,
                    cachedSource(cached.staleAfterEpochMs),
                    cached.fetchedAtEpochMs,
                    error,
                ),
            )
        }
        return true
    }

    private suspend fun loadCachedNutrition(error: String?): Boolean {
        val cached = cacheDao.read(CanonicalCachePolicy.NUTRITION_KEY) ?: return false
        val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull() ?: return false
        val days = json.optInt("_android_days", 30).coerceIn(1, 30)
        val snapshot = runCatching { NutritionPayloadMapper.map(json, days) }.getOrNull() ?: return false
        _state.update {
            it.copy(
                nutrition = ParityDomainState(
                    snapshot,
                    cachedSource(cached.staleAfterEpochMs),
                    cached.fetchedAtEpochMs,
                    error,
                    contractAvailable = true,
                ),
            )
        }
        return true
    }

    private suspend fun loadCachedTaskHistory(error: String?): Boolean {
        val cached = cacheDao.read(CanonicalCachePolicy.TASK_HISTORY_KEY) ?: return false
        val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull() ?: return false
        val snapshot = runCatching { TaskHistoryPayloadMapper.map(json) }.getOrNull() ?: return false
        _state.update {
            it.copy(
                taskHistory = ParityDomainState(
                    snapshot,
                    cachedSource(cached.staleAfterEpochMs),
                    cached.fetchedAtEpochMs,
                    error,
                    contractAvailable = true,
                ),
            )
        }
        return true
    }

    private suspend fun cache(
        key: String,
        contract: String,
        freshnessMs: Long,
        json: JSONObject,
        fetchedAt: Long,
    ) {
        cacheDao.replace(
            CanonicalSnapshotEntity(
                cacheKey = key,
                contractVersion = contract,
                fetchedAtEpochMs = fetchedAt,
                staleAfterEpochMs = fetchedAt + freshnessMs,
                payloadVersion = json.optString("version").takeIf(String::isNotBlank),
                payloadJson = json.toString(),
            ),
        )
    }

    private fun monthRange(month: LocalDate): Pair<LocalDate, LocalDate> {
        val first = month.withDayOfMonth(1)
        val start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return start to start.plusDays(42)
    }

    private fun cachedSource(staleAfterEpochMs: Long): RuntimeDataSource =
        if (staleAfterEpochMs <= System.currentTimeMillis()) RuntimeDataSource.STALE else RuntimeDataSource.CACHED
}

private fun Throwable.safeParityMessage(): String =
    message?.takeIf(String::isNotBlank) ?: "AEGIS parity data is temporarily unavailable."
