package nl.vanvrouwerff.iptv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.data.settings.SourceConfig

enum class SourceType { M3u, Xtream }

data class SettingsUiState(
    val type: SourceType = SourceType.M3u,
    val m3uUrl: String = "",
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val saving: Boolean = false,
    val savedOnce: Boolean = false,
    val refreshing: Boolean = false,
    val refreshError: String? = null,
    val autoRefreshEnabled: Boolean = false,
    val autoRefreshHour: Int = 3,
)

class SettingsViewModel : ViewModel() {

    private val app = IptvApp.get()
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val current = app.settings.sourceConfig.first()) {
                is SourceConfig.M3u -> _state.value = _state.value.copy(
                    type = SourceType.M3u, m3uUrl = current.url,
                )
                is SourceConfig.Xtream -> _state.value = _state.value.copy(
                    type = SourceType.Xtream,
                    host = current.host,
                    username = current.username,
                    password = current.password,
                )
                null -> Unit
            }
            _state.value = _state.value.copy(
                autoRefreshEnabled = app.settings.autoRefreshEnabled.first(),
                autoRefreshHour = app.settings.autoRefreshHour.first(),
            )
        }
    }

    fun setType(type: SourceType) { _state.value = _state.value.copy(type = type) }
    fun setM3uUrl(v: String) { _state.value = _state.value.copy(m3uUrl = v) }
    fun setHost(v: String) { _state.value = _state.value.copy(host = v) }
    fun setUsername(v: String) { _state.value = _state.value.copy(username = v) }
    fun setPassword(v: String) { _state.value = _state.value.copy(password = v) }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            _state.value = s.copy(saving = true)
            when (s.type) {
                SourceType.M3u -> app.settings.saveM3u(s.m3uUrl)
                SourceType.Xtream -> app.settings.saveXtream(s.host, s.username, s.password)
            }
            _state.value = _state.value.copy(saving = false, savedOnce = true)
            onDone()
        }
    }

    fun setAutoRefreshEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(autoRefreshEnabled = enabled)
        viewModelScope.launch { app.settings.setAutoRefreshEnabled(enabled) }
    }

    /** Cycles 0..23 for both buttons so remote-only users can reach any hour in ≤12 presses. */
    fun bumpAutoRefreshHour(delta: Int) {
        val next = ((_state.value.autoRefreshHour + delta) % 24 + 24) % 24
        _state.value = _state.value.copy(autoRefreshHour = next)
        viewModelScope.launch { app.settings.setAutoRefreshHour(next) }
    }

    /**
     * Kick off a full catalogue refresh from the settings screen. Delegates to the shared
     * PlaylistRefreshUseCase so the ChannelsScreen (observing the same state) renders the
     * "Vernieuwen…" pill during the run and flips back automatically on completion.
     */
    fun refreshNow(onDone: () -> Unit = {}) {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true, refreshError = null)
            val result = app.refreshUseCase()
            _state.value = _state.value.copy(
                refreshing = false,
                refreshError = result.exceptionOrNull()?.message,
            )
            onDone()
        }
    }
}
