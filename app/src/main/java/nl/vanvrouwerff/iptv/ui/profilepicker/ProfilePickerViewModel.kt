package nl.vanvrouwerff.iptv.ui.profilepicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.IptvApp
import nl.vanvrouwerff.iptv.data.db.ProfileEntity

/**
 * Backs [ProfilePickerScreen]. Observes the profiles table and records the active
 * profile + session timestamp when the user picks a tile (so the cold-start picker
 * doesn't fire again within the session window).
 */
class ProfilePickerViewModel : ViewModel() {

    private val app = IptvApp.get()

    val profiles: StateFlow<List<ProfileEntity>> = app.database.profileDao()
        .observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [onPersisted] runs after the active profile is stored, so the home screen loads it. */
    fun onProfileSelected(profile: ProfileEntity, onPersisted: () -> Unit) {
        viewModelScope.launch {
            app.settings.setActiveProfile(profile.id)
            app.settings.setLastProfileSessionAt()
            onPersisted()
        }
    }
}
