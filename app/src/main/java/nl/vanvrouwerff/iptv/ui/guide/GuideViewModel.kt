package nl.vanvrouwerff.iptv.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.R
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
) {
    val group: LiveGroup? get() = groups.getOrNull(groupIndex)
}

class GuideViewModel : ViewModel() {

    private val app = IptvApp.get()
    private val dao = app.database.channelDao()
    private val _state = MutableStateFlow(GuideState())
    val state: StateFlow<GuideState> = _state.asStateFlow()
    private var programmesJob: Job? = null
    private var loaded = false

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
                )
            }
            val now = System.currentTimeMillis()
            val from = now - now % HALF_HOUR_MS
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
    }
}
