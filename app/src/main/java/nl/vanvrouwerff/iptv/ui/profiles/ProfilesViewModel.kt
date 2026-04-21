package nl.vanvrouwerff.iptv.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.data.db.IptvDatabase
import nl.vanvrouwerff.iptv.data.db.ProfileEntity

/** Palette presented in the "new profile" form. Kept short — fewer choices, less paralysis. */
val ProfileColorChoices: List<Int> = listOf(
    0xFFE53935.toInt(), // red
    0xFFFB8C00.toInt(), // orange
    0xFFFDD835.toInt(), // yellow
    0xFF43A047.toInt(), // green
    0xFF1E88E5.toInt(), // blue
    0xFF8E24AA.toInt(), // purple
    0xFF6D4C41.toInt(), // brown
    0xFF546E7A.toInt(), // slate
)

data class ProfileRow(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val isActive: Boolean,
    val isDefault: Boolean,
)

data class ProfilesUiState(
    val loading: Boolean = true,
    val profiles: List<ProfileRow> = emptyList(),
    val editing: EditingState? = null,
)

data class EditingState(
    /** null = creating a new profile; non-null = editing an existing profile. */
    val id: String?,
    val name: String,
    val colorArgb: Int,
) {
    val isNew: Boolean get() = id == null
}

class ProfilesViewModel : ViewModel() {

    private val app = IptvApp.get()
    private val profileDao = app.database.profileDao()
    private val settings = app.settings

    private val _state = MutableStateFlow(ProfilesUiState())
    val state: StateFlow<ProfilesUiState> = _state.asStateFlow()

    private val activeIdFlow: StateFlow<String> = settings.activeProfileId.stateIn(
        viewModelScope, SharingStarted.Eagerly, IptvDatabase.DEFAULT_PROFILE_ID,
    )

    init {
        viewModelScope.launch {
            combine(
                profileDao.observeProfiles(),
                activeIdFlow,
            ) { profiles, activeId ->
                profiles.map { p ->
                    ProfileRow(
                        id = p.id,
                        name = p.name,
                        colorArgb = p.colorArgb,
                        isActive = p.id == activeId,
                        isDefault = p.id == IptvDatabase.DEFAULT_PROFILE_ID,
                    )
                }
            }.collect { rows ->
                _state.update { it.copy(loading = false, profiles = rows) }
            }
        }
    }

    fun select(id: String) {
        viewModelScope.launch { settings.setActiveProfile(id) }
    }

    fun startCreating(defaultName: String) {
        // Pre-fill a sensible name so Save produces a profile even if the user doesn't
        // touch the text field — previously a blank name silently aborted the save and
        // the "added profile" never materialised.
        val used = _state.value.profiles.map { it.name.trim() }.toSet()
        val name = generateSequence(1) { it + 1 }
            .map { defaultName.format(it) }
            .first { it !in used }
        val usedColors = _state.value.profiles.map { it.colorArgb }.toSet()
        val color = ProfileColorChoices.firstOrNull { it !in usedColors }
            ?: ProfileColorChoices.first()
        _state.update {
            it.copy(
                editing = EditingState(
                    id = null,
                    name = name,
                    colorArgb = color,
                ),
            )
        }
    }

    fun startEditing(id: String) {
        val row = _state.value.profiles.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(editing = EditingState(id = row.id, name = row.name, colorArgb = row.colorArgb))
        }
    }

    fun cancelEditing() {
        _state.update { it.copy(editing = null) }
    }

    fun updateDraftName(name: String) {
        _state.update { it.copy(editing = it.editing?.copy(name = name)) }
    }

    fun updateDraftColor(argb: Int) {
        _state.update { it.copy(editing = it.editing?.copy(colorArgb = argb)) }
    }

    fun saveEditing() {
        val draft = _state.value.editing ?: return
        val name = draft.name.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            if (draft.isNew) {
                // Generate a stable id from the current time; two profiles created in the
                // same millisecond get a suffix so the PK stays unique.
                val base = "p-${System.currentTimeMillis()}"
                val existing = profileDao.allProfiles()
                val existingIds = existing.map { it.id }.toSet()
                val id = generateSequence(0) { it + 1 }
                    .map { if (it == 0) base else "$base-$it" }
                    .first { it !in existingIds }
                val sortIndex = (existing.maxOfOrNull { it.sortIndex } ?: 0) + 1
                profileDao.upsert(
                    ProfileEntity(
                        id = id,
                        name = name,
                        colorArgb = draft.colorArgb,
                        sortIndex = sortIndex,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                // Use upsert (REPLACE) instead of the targeted UPDATE rename() to keep the
                // edit path identical to the create path — Room's REPLACE strategy regenerates
                // the row, which sidesteps any mismatch we might otherwise see between
                // observeProfiles' cached snapshot and the actual row. Preserve sortIndex and
                // createdAt from the stored row so the list ordering stays stable.
                val stored = profileDao.getProfile(draft.id!!) ?: return@launch
                profileDao.upsert(
                    stored.copy(name = name, colorArgb = draft.colorArgb),
                )
            }
            _state.update { it.copy(editing = null) }
        }
    }

    /**
     * Delete a profile, its favorites/progress/episodes, and its per-profile prefs.
     * Refuses to delete the built-in default profile — the app requires at least one
     * profile to be present, and forbidding deletion of the default makes that invariant
     * trivial to enforce.
     */
    fun delete(id: String) {
        if (id == IptvDatabase.DEFAULT_PROFILE_ID) return
        viewModelScope.launch {
            // If the profile being deleted is currently active, swap the active id to the
            // default first so nothing reads from a now-gone profile during cascade.
            if (activeIdFlow.value == id) {
                settings.setActiveProfile(IptvDatabase.DEFAULT_PROFILE_ID)
            }
            profileDao.deleteCascade(id)
            settings.wipeProfilePrefs(id)
        }
    }
}
