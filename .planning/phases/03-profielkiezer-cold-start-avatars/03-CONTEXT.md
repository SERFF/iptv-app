# Phase 3: Profielkiezer op cold start + avatars - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning
**Mode:** Auto-generated (--auto)

<domain>
## Phase Boundary

Bij cold-start: als >1 profiel én geen recente sessie (<8u), toon `ProfilePickerScreen` voor Channels. Profielen krijgen optioneel emoji-avatar veld (naast bestaande colorArgb). Wissel via Settings → Profiles.
</domain>

<decisions>
## Implementation Decisions

### Schema
- **D-01:** Room-migratie 6→7: `ALTER TABLE profiles ADD COLUMN avatarEmoji TEXT NULL`. Geen NOT NULL — bestaande profielen blijven leeg (toont kleur-cirkel + initiaal).
- **D-02:** `ProfileEntity` krijgt `val avatarEmoji: String? = null`.

### Routing
- **D-03:** `SettingsStore` krijgt `lastProfileSessionAt: Long` (epoch ms). Geüpdatet bij ProfilePicker `onProfileSelected` of bij Settings → switch profile.
- **D-04:** `MainActivity` route-logic in Splash: na load, check `profilesRepo.count() > 1 && System.currentTimeMillis() - lastProfileSessionAt > 8*3600*1000`. Indien true: route naar `ProfilePicker`. Anders: route naar `Channels` (current behaviour).
- **D-05:** Splash → ProfilePicker → Channels. Back-press vanaf Channels gaat NIET terug naar ProfilePicker (zelfde als Splash). Back vanaf ProfilePicker quit app.

### UI
- **D-06:** Grid: 3 kolommen op tv breedte. Elke tegel: avatar-cirkel 160dp, naam onder. Focus-scale 1.08 (zelfde patroon als rails). Header "Wie kijkt er?" (i18n: `profile_picker_title`).
- **D-07:** Avatar-cirkel render: als `avatarEmoji != null` → emoji centered (font 72sp). Else: gekleurd circle met `colorArgb` background + grote initiaal in wit. Reuse zelfde patroon als bestaande Profiles-scherm avatar.

### Settings integration
- **D-08:** ProfilesScreen krijgt emoji-picker per profiel (eenvoudig: tap emoji-veld → list met 24 voorgekozen emojis: 😀 🦊 🐱 🐶 🦁 🐯 🐻 🐼 🐰 🦝 🐸 🦄 ⭐ 🎬 🍿 🎮 🎵 ☕ 🌙 ☀️ 🌈 🌸 🍀 ⚡).
- **D-09:** Settings → "Wisselen van profiel" knop (i18n: `settings_switch_profile`) → routet terug naar ProfilePicker zonder cold-start timer-check.

### Claude's Discretion
- Exact tegel-styling (gradient achter avatar of pure kleur).
- Header-font (displayLarge wsl beste impact).

</decisions>

<canonical_refs>
## Canonical References

- `.planning/ROADMAP.md` §Phase 3
- `.planning/REQUIREMENTS.md` §Profiles (PROF-01, PROF-02)
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/Entities.kt:37` — `ProfileEntity`
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/IptvDatabase.kt` — migrations
- `app/src/main/java/nl/vanvrouwerff/iptv/data/db/ProfileDao.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/data/settings/SettingsStore.kt:52-57` — activeProfileId
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/profiles/ProfilesScreen.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/MainActivity.kt` — routing

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ProfilesViewModel` heeft al CRUD voor profielen.
- `IptvApp.profilesRepo` service-locator entry.
- Splash → Channels routing.

### Established Patterns
- Room migration als anonymous Migration class, geregistreerd in DB-builder.
- Compose route via NavHost in MainActivity.

</code_context>

<specifics>
## Specific Ideas

- 8u cutoff voor "recent session" — balanceert tussen elke avond opnieuw kiezen en nooit.
- Emoji-set zonder skin-tones (eenvoudiger, geen variation-selector issues op alpha10 Compose).

</specifics>

<deferred>
## Deferred Ideas

- Custom-upload avatar (foto vanaf USB) — out of scope.
- Profile-level password/PIN — out of scope, family-shared device.
- Profile-level content-filtering (kids profiel) — out of scope.

</deferred>

---
