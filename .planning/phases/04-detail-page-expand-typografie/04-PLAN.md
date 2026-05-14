# Phase 4: Detail-page expand + typografie — Plan

**Status:** Ready for execution

## Goal recap
Fullbleed-entry + scrollable body + sticky titel/Play + leesbare typografie op detail-screens.

## Tasks

### Task 1 — `ui/theme/Type.kt` typography uitbreiding
Voeg styles toe (of nieuw bestand als nog niet bestaat):
```kotlin
object IptvType {
    val DetailTitle = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        color = IptvPalette.TextPrimary,
    )
    val DetailPlot = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,  // ~1.44 ratio
        color = IptvPalette.TextSecondary,
    )
    val SectionTitle = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        color = IptvPalette.TextPrimary,
    )
}
```

### Task 2 — `MovieDetailScreen.kt` refactor

Vervang huidige `Column` body met `LazyColumn`:
```kotlin
val lazyState = rememberLazyListState()
val showStickyHeader by remember {
    derivedStateOf { lazyState.firstVisibleItemIndex > 0 }
}

Box {
    LazyColumn(state = lazyState, modifier = Modifier.fillMaxSize()) {
        item { HeroBackdrop(state, entryAnim = true) }  // existing, with entry-fade
        stickyHeader { TitleHeader(state, visible = showStickyHeader, onPlay) }
        item { OverviewSection(state.plot) }
        item { CastRail(state.castList) }
        item { SimilarRail(state.similar) }
    }
}
```

```kotlin
@Composable
private fun HeroBackdrop(state: DetailUiState, entryAnim: Boolean) {
    val alpha by animateFloatAsState(if (entryAnim) 1f else 0f, tween(400))
    val scale by animateFloatAsState(if (entryAnim) 1f else 0.6f, tween(300))

    Box(modifier = Modifier
        .fillMaxWidth()
        .height(540.dp)
    ) {
        AsyncImage(
            state.backdropUrl,
            modifier = Modifier.fillMaxSize().alpha(alpha).scale(scale)
        )
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to Color.Black.copy(0.92f),
                0.45f to Color.Black.copy(0.55f),
                0.85f to Color.Transparent,
            )
        ))
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(48.dp)) {
            Text(state.title, style = IptvType.DetailTitle, maxLines = 2)
            // year, rating, runtime row
            // Play + More Info buttons (Phase 1)
        }
    }
}
```

```kotlin
@Composable
private fun TitleHeader(state: DetailUiState, visible: Boolean, onPlay: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Row(modifier = Modifier
            .fillMaxWidth()
            .background(IptvPalette.BackgroundDeep.copy(0.95f))
            .padding(horizontal = 48.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(state.title, style = titleLarge, modifier = Modifier.weight(1f))
            Button(onPlay) { Icon(PlayArrow); Text("Play") }
        }
    }
}

@Composable
private fun OverviewSection(plot: String) {
    if (plot.length < 100) return  // inline in header for short plots
    Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp)) {
        Text("Verhaal", style = IptvType.SectionTitle)
        Spacer(Modifier.height(12.dp))
        Text(plot, style = IptvType.DetailPlot)
    }
}

@Composable
private fun CastRail(cast: List<CastMember>) {
    if (cast.isEmpty()) return
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
        Text("Cast", style = IptvType.SectionTitle, modifier = Modifier.padding(horizontal = 48.dp))
        Spacer(Modifier.height(12.dp))
        LazyRow(...) { items(cast) { CastChip(it) } }
    }
}

@Composable
private fun SimilarRail(similar: List<Channel>) {
    if (similar.isEmpty()) return
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
        Text("Vergelijkbaar", style = IptvType.SectionTitle, modifier = Modifier.padding(horizontal = 48.dp))
        Spacer(Modifier.height(12.dp))
        LazyRow(...) { items(similar) { PosterCard(it) } }
    }
}
```

### Task 3 — `SeriesDetailScreen.kt` parallel refactor

Identieke LazyColumn-structuur:
```kotlin
LazyColumn(...) {
    item { HeroBackdrop(...) }
    stickyHeader { TitleHeader(...) }
    item { OverviewSection(...) }
    item { SeasonsAccordion(...) }  // existing
    item { CastRail(...) }
    item { SimilarRail(...) }
}
```

### Task 4 — Entry animation trigger
In both screens:
```kotlin
var entered by remember { mutableStateOf(false) }
LaunchedEffect(Unit) { entered = true }
HeroBackdrop(state, entryAnim = entered)
```

### Task 5 — i18n strings
- `detail_section_overview` = "Verhaal"
- `detail_section_cast` = "Cast"
- `detail_section_similar` = "Vergelijkbaar"

## File-list

| File | Type |
|------|------|
| `ui/theme/Type.kt` | NEW or extend |
| `ui/detail/MovieDetailScreen.kt` | refactor body |
| `ui/seriesdetail/SeriesDetailScreen.kt` | refactor body |
| `strings.xml` | 3 keys |

## Risico's

- **D-pad focus inside LazyColumn:** sticky header focus-traversal kan tricky zijn. Test scroll-down terwijl focus op CastRail item.
- **Compose for TV alpha10 stickyHeader:** verify dat het werkt op tv-foundation. Fallback: gewone `item { TitleHeader }` zonder sticky.
- **Performance:** LazyColumn met grote backdrop image + 3 LazyRows — keep eager-loading off.

## Verificatie

| # | Criterium |
|---|-----------|
| 1 | Entry animatie zichtbaar (~300ms) |
| 2 | Scroll body smooth |
| 3 | Sticky header zichtbaar na scroll |
| 4 | Tekst leesbaar op licht + donker backdrop |
| 5 | Beide screens (Movie + Series) krijgen update |

## Volgende stap
`/gsd-execute-phase 4`
