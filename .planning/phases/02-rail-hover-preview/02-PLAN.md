# Phase 2: Rail hover-preview — Plan

**Status:** Ready for execution
**Created:** 2026-05-14

## Goal recap
Focus-popover op rail-items na 700ms; hero-backdrop crossfade naar focused item.

## Tasks

### Task 1 — `HoverFocus` state in `ChannelsViewModel`
```kotlin
data class HoverFocus(val channelId: String, val backdropUrl: String?)

private val _hoverFocus = MutableStateFlow<HoverFocus?>(null)
val hoverFocus: StateFlow<HoverFocus?> = _hoverFocus.asStateFlow()

fun onHoverFocus(channel: Channel, backdropUrl: String?) {
    _hoverFocus.value = HoverFocus(channel.id, backdropUrl)
}
fun onClearHover() { _hoverFocus.value = null }
```

### Task 2 — `HoverPreviewCard` Composable (nieuw, in `ChannelsScreen.kt` of nieuwe `HoverPreview.kt`)
```kotlin
@Composable
fun HoverPreviewCard(
    channel: Channel,
    bundle: MovieDetailsBundle?,  // null tijdens load
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier
        .clip(RoundedCornerShape(12.dp))
        .background(IptvPalette.SurfaceLift)
        .padding(12.dp)
    ) {
        // existing poster (large)
        AsyncImage(channel.logoUrl, ...)
        Spacer(8.dp)
        Text(channel.name, style = titleMedium)
        Row { yearBadge; ratingBadge; durationBadge }
        bundle?.let { Text(it.plot.take(140), maxLines = 2, style = bodySmall) }
        Row {
            Button(onPlay) { Icon(PlayArrow); Text("Play") }
            OutlinedButton(onInfo) { Icon(Info); Text("Info") }
        }
    }
}
```

### Task 3 — `PosterCard` / `LogoCard` aanpassing
Vervang huidige focus-scale-only met:
```kotlin
var focused by remember { mutableStateOf(false) }
var showPreview by remember { mutableStateOf(false) }

LaunchedEffect(focused) {
    if (focused) {
        delay(700)
        showPreview = true
    } else {
        showPreview = false
    }
}

LaunchedEffect(focused) {
    if (focused) vm.onHoverFocus(channel, channel.backdropUrl)
}

Box(modifier = Modifier
    .onFocusChanged { focused = it.isFocused }
    .focusable()
    .zIndex(if (showPreview) 1f else 0f)
) {
    AnimatedContent(targetState = showPreview) { preview ->
        if (preview) HoverPreviewCard(channel, prefetchedBundle, onPlay, onInfo)
        else PosterImage(channel)  // existing minimal card
    }
}
```

### Task 4 — Prefetch on focus
```kotlin
LaunchedEffect(focused) {
    if (focused) {
        prefetchedBundle = app.tmdbMovieDetails.lookupMovie(...)
    }
}
```
Debounce: alleen prefetch als nog niet in cache.

### Task 5 — `HeroCarousel` accept `hoverFocus`
```kotlin
@Composable
fun HeroCarousel(..., hoverFocus: HoverFocus?) {
    val backdrop = hoverFocus?.backdropUrl
    if (backdrop != null) {
        Crossfade(targetState = backdrop) { url ->
            AsyncImage(url, ...)  // single hover backdrop
        }
        // auto-rotate paused
    } else {
        // existing HeroCarousel
    }
}
```

### Task 6 — Backdrop URL voor rail-items
`Channel` heeft alleen `logoUrl`. Voor backdrop nodig: TMDB `backdrop_path` uit `MovieDetailsBundle`. Twee opties:
- (a) Use logoUrl als backdrop fallback (geen extra fetch).
- (b) Bij prefetch ook backdropUrl extracten; bewaar in process-cache `Map<channelId, backdropUrl>`.

Kies (b): minimale extra werk, betere kwaliteit.

## File-list

| File | Type |
|------|------|
| `ChannelsViewModel.kt` | +HoverFocus state, +methods |
| `ChannelsScreen.kt` | PosterCard/LogoCard refactor, HeroCarousel param |
| `ui/channels/HoverPreview.kt` (nieuw) | HoverPreviewCard composable |
| `TmdbMovieDetailsRepository.kt` | expose backdrop_path in bundle |

## Risico's

- **D-pad focus traversal:** popover-overlap mag focus van naburige item niet stelen tijdens scroll.
- **Performance:** delay(700) coroutines per item kan accumuleren. Use `key(channel.id)` voor `LaunchedEffect`.
- **Hero-rotation pause/resume:** state-coordinatie tussen VM hoverFocus en HeroCarousel index-rotation timer. Test op edge cases (focus naar TopBar → moet hover clearen).

## Verificatie

| # | Criterium |
|---|-----------|
| 1 | 700ms idle → popover; korter focus → geen popover |
| 2 | Snelle scroll geen flicker |
| 3 | Hero backdrop swap zichtbaar bij rail-focus |
| 4 | Popover toont year + rating + plot bij cache-hit |
| 5 | Geen scroll-jank op Formuler hardware |

## Dependencies

- Phase 1 niet hard-required, maar kan parallel.

## Volgende stap
`/gsd-execute-phase 2`
