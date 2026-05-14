# Phase 2: Rail hover-preview - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning
**Mode:** Auto-generated (--auto)

<domain>
## Phase Boundary

Rail-items op home-screen reageren op D-pad focus met expanding popover (artwork, plot, year, rating, Play/Info knoppen) na 700ms idle. Hero-backdrop crossfade naar focused item's backdrop. Geen popover bij snelle scroll.
</domain>

<decisions>
## Implementation Decisions

### Popover behaviour
- **D-01:** Activatie via `LaunchedEffect(focusState.isFocused) { delay(700); show = true }`. Focus-loss cancelt coroutine → geen popover bij snelle scroll.
- **D-02:** Popover expandeert in-place: scale 1.0 → 1.18, glow border (Accent → AccentSoft, zelfde gradient als bestaande focus-scale), content-fade-in (180ms). Bestaande focus-scale van 1.08 wordt vervangen door dit nieuwe systeem.
- **D-03:** Popover rendert binnen de LazyRow rail in een `Box` met `zIndex(1f)` — popover overlapt naburige items maar niet de hele scherm. Geen aparte overlay-layer (geen `Popup` of `Dialog` — die breken D-pad focus op Compose for TV alpha10).

### Inhoud popover
- **D-04:** Layout: poster bovenaan (huidige formaat 1.18x), eronder strip met titel (titleMedium bold), year + rating-badge + duration, plot-snippet (2 regels, bodySmall, IptvPalette.TextSecondary), Play + Info buttons-row.
- **D-05:** Plot-data: gebruik bestaande `TmdbMovieDetailsRepository.lookupMovie()` cache. Bij focus-prefetch (geen popover-trigger nodig) start lookup; cache-hit binnen 700ms → popover toont data direct. Cache-miss → popover toont alleen poster + titel (geen plot).

### Hero-backdrop swap
- **D-06:** Shared state in `ChannelsViewModel`: `MutableStateFlow<HoverFocus?>(null)` met `data class HoverFocus(channelId, backdropUrl)`. Rail-items roepen `vm.onHoverFocus(ch, backdrop)` aan op focus.
- **D-07:** `HeroCarousel` observe `hoverFocus`. Indien non-null: render hover-backdrop in plaats van rotating hero (auto-rotate pauzeert). Indien null (focus terug naar hero of weg): hero resumes rotation. Crossfade 600ms (zelfde als bestaande hero-fade).

### Performance
- **D-08:** Popover renderpath BUITEN LazyRow main composable — gebruik `androidx.compose.foundation.layout.subcompose` patroon of houd popover-content lazy. Geen jank tijdens scroll-target-find.
- **D-09:** Prefetch debounce — alleen items 1-2 ahead/behind van focused index prefetchen, niet de hele rail. Voorkomt 100+ TMDB-calls bij snel scrollen.

### Claude's Discretion
- Exact ease curve voor scale-animation (probably `spring(stiffness = Spring.StiffnessMediumLow)`).
- Of badges (rating/year) als pills of inline text.
- Popover-grootte: 1.18x of 1.25x — visueel testen op hardware.

</decisions>

<canonical_refs>
## Canonical References

- `.planning/ROADMAP.md` §Phase 2
- `.planning/REQUIREMENTS.md` §Rails & Discovery (RAIL-01, RAIL-02)
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt:1920-1964` — focus-scale logica (`FocusableCard`/`PosterCard`/`LogoCard`)
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt:633` ev. — rail building
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsViewModel.kt` — state holder
- `app/src/main/java/nl/vanvrouwerff/iptv/data/tmdb/TmdbMovieDetailsRepository.kt` — cache + lookup

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `PosterCard` + `LogoCard` Composables — current focus-scale (1.08). Extend to support hover-preview overlay.
- `TmdbMovieDetailsRepository` — cache TTL al ingesteld; `MovieDetailsBundle` heeft alle popover-velden.
- `MutableStateFlow` patroon in VM — al gebruikt voor andere state.

### Established Patterns
- `LazyRow` met `items()` voor rails.
- `Modifier.onFocusChanged { ... }` voor focus-detection.
- Crossfade via `AnimatedContent` of `Crossfade`.

### Integration Points
- `ChannelsViewModel` krijgt `hoverFocus: StateFlow<HoverFocus?>` + `onHoverFocus()` / `onClearHover()` methods.
- `HeroCarousel` accepteert `hoverFocus` param.
- Elk rail-card type krijgt `onFocusChange(Channel)` callback.

</code_context>

<specifics>
## Specific Ideas

- Rating-badge alleen tonen als TMDB voteAverage > 0. Format: `★ 7.5`.
- Plot-snippet: `text.take(140).trim() + if (longer) "…" else ""`.

</specifics>

<deferred>
## Deferred Ideas

- Popover sound-effect bij open (Netflix doet dit) — out of scope.
- Popover als volledig scherm-edge bumper (zoals Netflix' edge cards) — complex op Compose for TV alpha10.
- TV-channel hover preview met EPG now/next — verschilt van movie popover, eigen design nodig.

</deferred>

---

*Phase: 02-rail-hover-preview*
