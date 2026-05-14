# Phase 3: Profielkiezer + avatars — Plan

**Status:** Ready for execution

## Goal recap
Cold-start ProfilePicker bij >1 profiel + emoji-avatars per profiel.

## Tasks

### Task 1 — Room migratie 6→7
`IptvDatabase.kt`:
```kotlin
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN avatarEmoji TEXT")
    }
}
// register: .addMigrations(..., MIGRATION_6_7)
// bump @Database(version = 7)
```

### Task 2 — `ProfileEntity` veld
```kotlin
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int,
    val sortIndex: Int,
    val createdAt: Long,
    val avatarEmoji: String? = null,
)
```

### Task 3 — `SettingsStore` extension
```kotlin
val lastProfileSessionAt: Flow<Long>
suspend fun setLastProfileSessionAt(ms: Long)
```
Backed door DataStore Preferences key `last_profile_session_at`.

### Task 4 — `ProfilePickerScreen.kt` (nieuw, `ui/profilepicker/`)
```kotlin
@Composable
fun ProfilePickerScreen(
    profiles: List<Profile>,
    onProfileSelected: (Profile) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(IptvPalette.BackgroundDeep), ...) {
        Text(stringResource(R.string.profile_picker_title), style = displayLarge)
        LazyVerticalGrid(columns = GridCells.Fixed(3), ...) {
            items(profiles) { p ->
                ProfileTile(p, onClick = { onProfileSelected(p) })
            }
        }
    }
}

@Composable
private fun ProfileTile(profile: Profile, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f)
    Column(modifier = Modifier
        .scale(scale)
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier
            .size(160.dp).clip(CircleShape)
            .background(Color(profile.colorArgb))
        ) {
            if (profile.avatarEmoji != null) {
                Text(profile.avatarEmoji, fontSize = 72.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                Text(profile.name.take(1).uppercase(), fontSize = 64.sp, color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
        Text(profile.name, style = titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
```

### Task 5 — `ProfilePickerViewModel.kt` (nieuw)
```kotlin
class ProfilePickerViewModel : ViewModel() {
    val profiles: StateFlow<List<Profile>> = ...
    fun onProfileSelected(p: Profile) {
        viewModelScope.launch {
            app.settings.setActiveProfileId(p.id)
            app.settings.setLastProfileSessionAt(System.currentTimeMillis())
        }
    }
}
```

### Task 6 — `MainActivity.kt` routing
```kotlin
// in startDestination logic:
val profileCount = remember { runBlocking { profilesRepo.count() } }
val lastSession = settings.lastProfileSessionAt.collectAsState(0L).value
val needsPicker = profileCount > 1 && System.currentTimeMillis() - lastSession > 8 * 3600 * 1000

val start = if (needsPicker) Route.ProfilePicker else Route.Channels
NavHost(navController, startDestination = start) {
    composable(Route.ProfilePicker) {
        ProfilePickerScreen(...) { p ->
            navController.navigate(Route.Channels) { popUpTo(Route.ProfilePicker) { inclusive = true } }
        }
    }
    // ... existing routes
}
```

### Task 7 — Emoji-picker in Profiles-screen
`ProfilesScreen.kt`: voeg emoji-dropdown toe naast naam-input. Lijst van 24 emojis als grid in `AlertDialog`.

### Task 8 — Settings switch-profile button
`SettingsScreen.kt` Profiles-sectie: knop "Wisselen van profiel" → `navController.navigate(Route.ProfilePicker)`.

### Task 9 — i18n strings
`strings.xml`:
- `profile_picker_title` = "Wie kijkt er?"
- `settings_switch_profile` = "Wisselen van profiel"
- `profile_emoji_picker_title` = "Kies avatar"

## File-list

| File | Type |
|------|------|
| `IptvDatabase.kt` | +migration, bump version |
| `Entities.kt` | ProfileEntity field |
| `ProfileDao.kt` | mappers + queries (geen extra DAO methodes nodig) |
| `SettingsStore.kt` | +lastProfileSessionAt |
| `ui/profilepicker/ProfilePickerScreen.kt` | NEW |
| `ui/profilepicker/ProfilePickerViewModel.kt` | NEW |
| `MainActivity.kt` | routing |
| `ui/profiles/ProfilesScreen.kt` | emoji-picker |
| `ui/settings/SettingsScreen.kt` | switch button |
| `strings.xml` | 3 keys |

## Risico's

- Migration test: emulator-level Room.test util voor migratie 6→7 (`MigrationTestHelper`).
- Default-profiel scenario: bij 1 profiel skip picker — testen dat single-profile users niets merken.
- `runBlocking` in MainActivity = anti-pattern; gebruik `produceState` of `LaunchedEffect + state-hold`.

## Verificatie

| # | Criterium |
|---|-----------|
| 1 | Single-profile: directe Channels |
| 2 | Multi-profile cold start: ProfilePicker zichtbaar |
| 3 | Binnen 8u opnieuw open: Channels direct |
| 4 | Emoji ingesteld zichtbaar in tegel |
| 5 | Migratie 6→7 zonder data-verlies |

## Volgende stap
`/gsd-execute-phase 3`
