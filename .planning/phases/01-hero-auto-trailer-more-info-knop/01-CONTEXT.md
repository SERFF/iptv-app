# Phase 1: Hero auto-trailer + "More Info" knop - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning
**Mode:** Auto-generated (--auto)

<domain>
## Phase Boundary

Hero-carousel op het home-scherm krijgt twee veranderingen:
1. Na 2.5s focus-idle op een hero-item start een muted YouTube-trailer (TMDB `trailerYoutubeKey`) in de plaats van/over de Ken Burns backdrop. Bij focus-loss/rotatie/error stopt de trailer en valt terug op de Ken Burns backdrop.
2. Naast de bestaande Play-knop verschijnt een "More Info" focus-target die navigeert naar `MovieDetail`/`SeriesDetail` zonder direct af te spelen.

TV-channels (`ContentType.TV`) hebben geen trailers → blijven zoals nu (Ken Burns backdrop, alleen Play knop).

</domain>

<decisions>
## Implementation Decisions

### YouTube playback approach
- **D-01:** YouTube DASH/HLS URL extractie via **NewPipeExtractor** (`com.github.TeamNewPipe:NewPipeExtractor`). Lichtgewicht, geen WebView, BSD-licentie, werkt zonder Google Play Services. Fetch op IO-thread, cache stream-URL TTL 30 min in process-memory.
  - *Rationale:* WebView/IFrame-player te zwaar voor STB (start-up lag, GPU compositing). Official YouTube TV SDK vereist Play Services dat Formuler/AOSP TV niet altijd heeft.
  - *Risk:* TOS-grey area, maar lib is breed gebruikt en project is open-source/persoonlijk gebruik. Vermeld in code-comment.

### Inline player surface
- **D-02:** Trailer rendert via **Media3 ExoPlayer** in een **Compose `AndroidView { PlayerView }`** in `HeroBanner`. Geen eigen UI-controls (alleen video, geen seekbar/buttons). Player-instance is per-hero-item (created onActive, released onDispose) — NIET gedeeld met PlayerActivity om audio-leak te vermijden.
- **D-03:** Z-index/layout: trailer-view bedekt de bestaande backdrop-Image maar STAAT ONDER de tekst-overlay-scrim (zelfde gradient die nu over backdrop ligt). Tekst + Play/More-Info knoppen blijven leesbaar.

### Activatie + timing
- **D-04:** Trailer start na **2.5s** zonder focus-wissel/rotatie (per ROADMAP-criterium). Geïmplementeerd via `LaunchedEffect(channel.id) { delay(2500); state = Trailer }`. Bij elke index-wissel reset timer.
- **D-05:** **Muted** by default (`player.volume = 0f`). Klik op Play-knop start volle `PlayerActivity` (huidige flow). Geen "unmute on focus" — out of scope.

### "More Info" knop
- **D-06:** Tweede focus-target in `HeroBanner`, **horizontaal rechts van Play** in een `Row(spacedBy = 12.dp)`. Zelfde styling als Play maar secundaire kleuren (transparant met outline ipv accent-fill). D-pad rechts vanaf Play → More Info. D-pad rechts vanaf More Info → niets (rand).
- **D-07:** Click op More Info → `onMoreInfo(channel)` callback bubbled tot `ChannelsScreen` → bestaande navigation naar `MovieDetail` voor MOVIE, `SeriesDetail` voor SERIES. Voor `ContentType.TV`: knop wordt **niet getoond** (geen detail-scherm).

### Trailer-key preloading
- **D-08:** Preloader-component **`TmdbHeroPreloader`** in `data/tmdb/`. Bij ChannelsViewModel state-update voor `heroes` rail: fire-and-forget `viewModelScope.launch` die `TmdbMovieDetailsRepository.lookupMovie()` aanroept voor max 5 hero-items (MOVIE + SERIES, niet TV). Maakt `MovieDetailsBundle` cache warm.
- **D-09:** Voor SERIES heeft `TmdbMovieDetailsRepository` momenteel alleen movie-lookup. Phase 1 voegt **`lookupSeries(channelId, title, year)`** toe (parallel naar bestaande `lookupMovie`) met TMDB `tv` endpoints. Hergebruik dezelfde caching-strategie.

### Fallback en error handling
- **D-10:** Elke fout (NewPipe extractor exception, ExoPlayer error, geen TMDB token, geen trailer-key, network down) → blijft op Ken Burns backdrop, geen crash, één enkele `Log.w` regel. Geen retry-loop in de hero (te dure achtergrond-traffic op een STB).
- **D-11:** Bij `TMDB_BEARER_TOKEN` blank: phase werkt identiek aan huidige toestand (geen trailer-paths actief). Bestaande Ken Burns + scrim blijft zoals nu.

### Lifecycle + cleanup
- **D-12:** ExoPlayer-instance in HeroBanner volgt Compose-lifecycle. `DisposableEffect` releases player + revoke surface. Bij `ON_PAUSE` (app naar achtergrond, of MainActivity → PlayerActivity): `player.pause()`. Bij `ON_RESUME`: hervat alleen als trailer-state nog Trailer is (na 2.5s reset).

### Claude's Discretion
- Exact spring-animatie tussen Ken Burns ↔ Trailer crossfade (~600ms zelfde duur als bestaande hero-fade is voldoende).
- Kleur en exact icon van "More Info" knop (informatie-icon `ⓘ` of tekst-only "Meer info" — kies wat past in IptvPalette + bestaande button-styling in `HeroBanner`).
- Of preloader 3 of 5 items vooruit warm-stookt (max 5, kan dynamisch op `heroes.size`).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Roadmap & requirements
- `.planning/ROADMAP.md` §Phase 1 — Hero auto-trailer + "More Info" knop
- `.planning/REQUIREMENTS.md` §Hero (HERO-01, HERO-02)
- `.planning/PROJECT.md` §Constraints — geen DI, service-locator, cleartext HTTP enabled

### Existing code
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt:1285-1349` — `HeroCarousel` + dots
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt:1372` ev. — `HeroBanner` (huidige Play knop + Ken Burns backdrop)
- `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsViewModel.kt` — `heroes` rail state
- `app/src/main/java/nl/vanvrouwerff/iptv/data/tmdb/TmdbMovieDetailsRepository.kt:106` — `trailerYoutubeKey` veld in `MovieDetailsBundle`
- `app/src/main/java/nl/vanvrouwerff/iptv/data/tmdb/TmdbMovieDetailsRepository.kt:124-141` — `pickTrailerKey()` selectielogica
- `app/src/main/java/nl/vanvrouwerff/iptv/data/tmdb/TmdbApi.kt` — TMDB endpoints (movie); needs tv-endpoint uitbreiding
- `app/src/main/java/nl/vanvrouwerff/iptv/IptvApp.kt` — service-locator (waar `TmdbHeroPreloader` aansluit)
- `app/src/main/java/nl/vanvrouwerff/iptv/player/PlayerActivity.kt` — bestaand ExoPlayer-gebruik (referentie voor track-selection / audio-config, NIET hergebruiken voor hero)

### Libraries (te onderzoeken in plan-phase)
- NewPipeExtractor — https://github.com/TeamNewPipe/NewPipeExtractor (extractor-core + extractor-services-youtube)
- Media3 ExoPlayer — al in project, hergebruiken

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`HeroBanner` Composable** — al alle hero-overlay logica (backdrop, gradient, Play-knop, focus). Phase 1 voegt een trailer-PlayerView + More-Info knop toe; backdrop blijft als fallback.
- **`TmdbMovieDetailsRepository.lookupMovie()`** — geeft al `trailerYoutubeKey` terug, cache TTL ingesteld. Reuse voor preloading.
- **`Media3 ExoPlayer`** + `androidx.media3:media3-exoplayer-hls` — al dependencies; PlayerView werkt via AndroidView.
- **`FocusRequester`** patroon — `HeroCarousel` heeft al shared focus-requester voor Play; uitbreiden naar Row(Play, More-Info).

### Established Patterns
- **Service-locator via `IptvApp`** — geen DI; `TmdbHeroPreloader` wordt eigenschap op IptvApp net als `tmdbMovieDetails`.
- **Coroutine-scopes** — `viewModelScope.launch(Dispatchers.IO) { ... }` voor TMDB calls; `Dispatchers.Default` voor index-builds. Volg dezelfde patronen.
- **Logging** — `Log.i(TAG, "...")` voor info, `Log.w(TAG, "...", throwable)` voor errors. Geen Timber.
- **Process-memory cache** — `LinkedHashMap` (zie `TmdbMovieDetailsRepository.cache`) — zelfde patroon voor trailer-URL extracts.

### Integration Points
- `ChannelsScreen.HeroCarousel` → krijgt nieuwe param `onMoreInfo: (Channel) -> Unit`.
- `ChannelsScreen` route handlers → wire `onMoreInfo` naar bestaande detail-navigation (zoals huidige tile-clicks doen).
- `IptvApp` → `val tmdbHeroPreloader = TmdbHeroPreloader(tmdbMovieDetails)`.
- `ChannelsViewModel.refresh()` → roept `app.tmdbHeroPreloader.warm(heroes)` aan na rail-build.

### Constraints uit codebase
- Compose for TV alpha10 — gebruik geen experimentele API's buiten wat al gebruikt is (`@OptIn(ExperimentalTvMaterial3Api::class)` is OK).
- minSdk 29 — alle gebruikte API's moeten daar werken.
- `network_security_config.xml` heeft cleartext HTTP; YouTube DASH-URLs zijn HTTPS, geen wijziging nodig.

</code_context>

<specifics>
## Specific Ideas

- Trailer-volume start op 0f. Geen "muted icon" overlay nodig — gebruiker verwacht muted hero.
- Bij hero-rotatie tijdens trailer-play: huidige `AnimatedContent(fadeIn 600ms)` blijft. Trailer-PlayerView wordt door dispose vernietigd. Volgende hero start opnieuw met 2.5s delay → backdrop → trailer.

</specifics>

<deferred>
## Deferred Ideas

- **Unmute-on-focus** voor power users (Phase 1.x of v2). Niet in scope nu.
- **Volume-fade in/out** bij trailer start/stop (animatie). Stilte → audio = jarring; maar muted in scope dus N/A nu.
- **Trailer auto-skip-to-action** (TMDB heeft geen timestamp). Phase 1 speelt vanaf 0.
- **Volledig scherm trailer-preview** als gebruiker langer dan X seconden focused blijft op hero. Out of scope.

</deferred>

---

*Phase: 01-hero-auto-trailer-more-info-knop*
*Context gathered: 2026-05-14*
