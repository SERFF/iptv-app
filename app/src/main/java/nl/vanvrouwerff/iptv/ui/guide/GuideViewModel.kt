package nl.vanvrouwerff.iptv.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.catchup.Catchup
import nl.vanvrouwerff.iptv.data.db.ProgrammeEntity
import nl.vanvrouwerff.iptv.data.live.LiveChannelIndex
import nl.vanvrouwerff.iptv.data.live.LiveGroup

data class GuideState(
    val loading: Boolean = true,
    val groups: List<LiveGroup> = emptyList(),
    val groupIndex: Int = 0,
    val numberById: Map<String, Int> = emptyMap(),
    /** Window start (rounded down to the half hour) and end, epoch ms. */
    val fromMs: Long = 0L,
    val toMs: Long = 0L,
    /** Programmes per EPG channel key, sorted by start. */
    val programmesByKey: Map<String, List<ProgrammeEntity>> = emptyMap(),
    val message: String? = null,
    /** "channelId:startMs" of every programme with a reminder. */
    val reminderKeys: Set<String> = emptySet(),
) {
    val group: LiveGroup? get() = groups.getOrNull(groupIndex)
}

class GuideViewModel : ViewModel() {

    private val app = IptvApp.get()
    private val dao = app.database.channelDao()
    private val _state = MutableStateFlow(GuideState())
    val state: StateFlow<GuideState> = _state.asStateFlow()
    private var programmesJob: Job? = null
    private var messageJob: Job? = null
    private val _playRequests = MutableSharedFlow<Channel>(extraBufferCapacity = 1)
    /** Catch-up items to hand to the player. */
    val playRequests: SharedFlow<Channel> = _playRequests.asSharedFlow()
    private var loaded = false

    init {
        viewModelScope.launch {
            app.reminders.all.collect { list ->
                _state.update { s -> s.copy(reminderKeys = list.mapTo(HashSet()) { reminderKey(it.channelId, it.startMs) }) }
            }
        }
    }

    /** OK on a programme that has not started yet: set or remove a reminder. */
    fun toggleReminder(channel: Channel, programme: ProgrammeEntity) {
        val existing = reminderKey(channel.id, programme.startMs) in _state.value.reminderKeys
        viewModelScope.launch {
            val set = app.reminders.toggle(channel, programme.title, programme.startMs, programme.stopMs, existing)
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(programme.startMs))
            showMessage(
                if (set) app.getString(R.string.reminder_set, programme.title, time)
                else app.getString(R.string.reminder_removed),
            )
        }
    }

    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val index = withContext(Dispatchers.IO) {
                LiveChannelIndex.load(
                    dao = dao,
                    profileId = app.activeProfileId.value,
                    favoritesLabel = app.getString(R.string.channel_list_favorites),
                    uncategorizedLabel = app.getString(R.string.channel_list_uncategorized),
                    hideAdult = app.kidsMode.value,
                )
            }
            val from = floorToHalfHour(System.currentTimeMillis())
            _state.update {
                it.copy(
                    loading = false,
                    groups = index.groups,
                    groupIndex = 0,
                    numberById = index.numberById,
                    fromMs = from,
                    toMs = from + WINDOW_MS,
                )
            }
            loadProgrammes()
        }
    }

    /** Moves the time window by [deltaMs], within [MAX_BACK_MS] before and [MAX_FORWARD_MS] after now. */
    fun shiftWindow(deltaMs: Long) {
        val nowFloor = floorToHalfHour(System.currentTimeMillis())
        val from = (_state.value.fromMs + deltaMs).coerceIn(nowFloor - MAX_BACK_MS, nowFloor + MAX_FORWARD_MS)
        if (from == _state.value.fromMs) return
        _state.update { it.copy(fromMs = from, toMs = from + WINDOW_MS) }
        loadProgrammes()
    }

    fun goToNow() {
        val from = floorToHalfHour(System.currentTimeMillis())
        if (from == _state.value.fromMs) return
        _state.update { it.copy(fromMs = from, toMs = from + WINDOW_MS) }
        loadProgrammes()
    }

    /** OK on a programme that has already ended (or started): replay it when the archive allows. */
    fun openPast(channel: Channel, programme: ProgrammeEntity) {
        val now = System.currentTimeMillis()
        if (!Catchup.isAvailable(channel, programme.startMs, programme.stopMs, now)) {
            showMessage(app.getString(R.string.catchup_unavailable))
            return
        }
        viewModelScope.launch {
            val item = Catchup.item(channel, programme.title, programme.startMs, programme.stopMs)
            if (item == null) showMessage(app.getString(R.string.catchup_unavailable))
            else _playRequests.tryEmit(item)
        }
    }

    fun showMessage(text: String) {
        messageJob?.cancel()
        _state.update { it.copy(message = text) }
        messageJob = viewModelScope.launch {
            delay(MESSAGE_MS)
            _state.update { it.copy(message = null) }
        }
    }

    fun selectGroup(index: Int) {
        val size = _state.value.groups.size
        if (size == 0) return
        _state.update { it.copy(groupIndex = ((index % size) + size) % size, programmesByKey = emptyMap()) }
        loadProgrammes()
    }

    private fun loadProgrammes() {
        val s = _state.value
        val group = s.group ?: return
        programmesJob?.cancel()
        programmesJob = viewModelScope.launch {
            val keys = group.channels.mapNotNull { it.epgChannelId }.distinct()
            val rows = withContext(Dispatchers.IO) {
                keys.chunked(500).flatMap { dao.programmesForKeys(it, s.fromMs, s.toMs) }
            }
            _state.update { it.copy(programmesByKey = rows.groupBy { p -> p.channelKey }) }
        }
    }

    companion object {
        const val HALF_HOUR_MS: Long = 30L * 60_000L
        /** 3.5 hours: fills the screen at a readable scale without horizontal scrolling. */
        const val WINDOW_MS: Long = 7L * HALF_HOUR_MS
        const val STEP_MS: Long = 6L * HALF_HOUR_MS
        const val MAX_BACK_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val MAX_FORWARD_MS: Long = 3L * 24 * 60 * 60 * 1000
        private const val MESSAGE_MS = 3_000L

        fun floorToHalfHour(ms: Long): Long = ms - ms % HALF_HOUR_MS

        fun reminderKey(channelId: String, startMs: Long): String = "$channelId:$startMs"
    }
}
