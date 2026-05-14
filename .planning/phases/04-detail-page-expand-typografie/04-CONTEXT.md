# Phase 4: Detail-page expand + typografie - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning
**Mode:** Auto-generated (--auto)

<domain>
## Phase Boundary

`MovieDetailScreen` + `SeriesDetailScreen` openen met fullbleed-animatie van poster naar backdrop, scrollable body met sticky header (titel + Play) en duidelijke sectie-kopjes (Verhaal / Cast / Vergelijkbaar). Typografie herzien: displayMedium titel, bodyLarge plot, line-height 1.4.
</domain>

<decisions>
## Implementation Decisions

### Entry animation
- **D-01:** Geen shared-element transition (Compose for TV alpha10 ondersteunt dit niet). Gebruik `AnimatedVisibility` + `LookaheadLayout` voor een crossfade-met-scale (poster start scale 0.6, fade-in 300ms, scale tot 1.0). Pseudo-shared-element.
- **D-02:** Backdrop laadt onder de bestaande scrim; bij entry-animatie start backdrop transparent (alpha 0) → alpha 1 in 400ms. Geen Ken Burns binnen detail (statisch backdrop).

### Scroll structure
- **D-03:** `LazyColumn` als root van detail-body. Sticky header: `stickyHeader { TitleHeader(...) }` met titel + Play-knop. Bestaande hero blijft als eerste item (genoeg height dat het wegscrollt).
- **D-04:** Secties als reguliere LazyColumn-items: `item { OverviewSection() }`, `item { CastRail() }`, `item { SimilarRail() }`. Sectie-kopjes: titleLarge bold, padding 32dp boven 16dp onder.

### Typography
- **D-05:** `ui/theme/Type.kt` uitbreiden: voeg styles toe voor `detailTitle` (displayMedium, fontWeight ExtraBold, lineHeight 1.1), `detailPlot` (bodyLarge, lineHeight 1.4, color TextSecondary), `detailSectionTitle` (titleLarge, fontWeight Bold).
- **D-06:** Scrim: vervang bestaande horizontale gradient met `Brush.horizontalGradient(0f to Black.copy(0.92f), 0.45f to Black.copy(0.55f), 0.85f to Color.Transparent)` voor leesbaarheid links én visuele balans rechts.

### SeriesDetailScreen
- **D-07:** Zelfde structuur als MovieDetailScreen. Sectie-volgorde: Hero → Title sticky → Overview → Seasons accordion → Cast → Similar.
- **D-08:** Seasons accordion blijft huidige implementatie; krijgt zelfde typografie-update.

### Claude's Discretion
- Sectie-spacing exact (24dp vs 32dp).
- Of "Verhaal" sectie alleen wanneer plot > 100 chars (anders inline in header-area).

</decisions>

<canonical_refs>
## Canonical References

- `.planning/ROADMAP.md` §Phase 4
- `.planning/REQUIREMENTS.md` §Detail-page (DET-01, DET-02, DET-03)
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/detail/MovieDetailScreen.kt:106-280`
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/seriesdetail/SeriesDetailScreen.kt`
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/theme/Type.kt` (assume bestaat — anders aanmaken)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `MovieDetailScreen` heeft al backdrop + cast + similar rails (469-550).
- Ken Burns 14s loop nu actief — verwijderen in detail (statisch beter voor scroll).

### Established Patterns
- Compose `LazyColumn` patroon elders in app.
- `stickyHeader` werkt op tv-foundation alpha10.

### Integration Points
- `MovieDetailViewModel` state geeft alle data — geen wijziging nodig.
- Theme `IptvPalette` heeft kleuren — alleen Typography aanvullen.

</code_context>

<specifics>
## Specific Ideas

- Sticky header niet permanent zichtbaar bij scrollen — alleen wanneer hero wegscrollt. Implementatie: bereken `firstVisibleItemIndex > 0` en faden header in.

</specifics>

<deferred>
## Deferred Ideas

- Parallax-effect op backdrop bij scroll (Netflix doet dit) — kan in Phase 4.x.
- Tabs-navigatie binnen detail (Overview/Cast/Trailer) — out of scope, scroll is voldoende.

</deferred>

---
