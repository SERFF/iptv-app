# Phase 1: Hero auto-trailer + "More Info" knop — Plan

**Status:** Ready for execution
**Created:** 2026-05-14
**Source:** 01-CONTEXT.md decisions D-01..D-12

## Goal recap

Hero-carousel start na 2.5s focus-idle een muted YouTube-trailer voor MOVIE/SERIES heroes (TMDB `trailerYoutubeKey`); "More Info" knop naast Play navigeert naar detail-screen. TV-heroes onveranderd. App zonder TMDB-token blijft identiek.

## Scope-aanpassing tov CONTEXT.md

- **SERIES-trailers:** uitgesteld naar Phase 1.1 (decimal phase). TMDB `tv` endpoint + DTOs zijn substantieel werk — eerst MOVIE-trailers werkend krijgen. SERIES-heroes vallen terug op Ken Burns + Play + More Info (zonder trailer).
- D-09 `lookupSeries()` → **deferred**.

## Implementatie-aanpak

Single-PR met 6 file-wijzigingen + 1 nieuwe dep. Geen Room-migratie (UI-only).

### Task 1 — Dependency: YouTube IFrame player

**Waarom IFrame ipv NewPipeExtractor:** TOS-clean (gebruikt officiële YouTube IFrame Player JS API in een verborgen WebView), Apache 2.0, single-line dep. WebView is overhead op een STB, maar voor hero-trailers (muted, low priority, on-demand) acceptabel. NewPipeExtractor is TOS-grey en risico voor een open-source project dat publiek staat.

**Verandering:** `app/build.gradle.kts`
```kotlin
implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.2")
```

**ProGuard** (alleen als R8/minify aanstaat — check eerst `app/proguard-rules.pro`): geen extra rules nodig per lib README.

### Task 2 — `data/tmdb/TmdbHeroPreloader.kt` (nieuw)

```kotlin
package nl.vanvrouwerff.iptv.data.tmdb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType

/**
 * Warmt TmdbMovieDetailsRepository cache vooruit voor zichtbare hero-items zodat
 * `trailerYoutubeKey` direct beschikbaar is op het moment dat de hero-carousel die
 * nodig heeft.
 */
class TmdbHeroPreloader(
    private val movieDetails: TmdbMovieDetailsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val MAX_PRELOAD = 5

    fun warm(heroes: List<Channel>) {
        heroes.asSequence()
            .filter { it.type == ContentType.MOVIE }
            .take(MAX_PRELOAD)
            .forEach { ch ->
                scope.launch {
                    runCatching {
                        movieDetails.lookupMovie(
                            channelId = ch.id,
                            title = TmdbCatalogueMatcher.normalize(ch.name).ifBlank { ch.name },
                            releaseYear = null,
                        )
                    }
                }
            }
    }
}
```

### Task 3 — `IptvApp.kt` wire-up

Toevoegen:
```kotlin
val tmdbHeroPreloader by lazy { TmdbHeroPreloader(tmdbMovieDetails) }
```

### Task 4 — `ChannelsViewModel.kt` trigger

Op de plek waar `heroes` rail wordt geüpdatet (zoek `heroes =` in VM), na de update:
```kotlin
IptvApp.get().tmdbHeroPreloader.warm(heroes)
```

### Task 5 — `ui/channels/HeroTrailerPlayer.kt` (nieuw)

Compose `AndroidView` wrapper rond `YouTubePlayerView` met lifecycle binding:

```kotlin
package nl.vanvrouwerff.iptv.ui.channels

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

@Composable
fun HeroTrailerPlayer(
    youtubeKey: String,
    modifier: Modifier = Modifier,
    onError: () -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var playerViewRef by remember { mutableStateOf<YouTubePlayerView?>(null) }

    AndroidView(
        factory = { ctx ->
            val options = IFramePlayerOptions.Builder()
                .controls(0).rel(0).ivLoadPolicy(3).ccLoadPolicy(0)
                .build()
            YouTubePlayerView(ctx).also { view ->
                view.enableAutomaticInitialization = false
                lifecycleOwner.lifecycle.addObserver(view)
                view.initialize(object : AbstractYouTubePlayerListener() {
                    override fun onReady(player: YouTubePlayer) {
                        runCatching {
                            player.setVolume(0)
                            player.loadVideo(youtubeKey, 0f)
                        }.onFailure { onError() }
                    }
                    override fun onError(p: YouTubePlayer, e: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError) {
                        onError()
                    }
                }, options)
                playerViewRef = view
            }
        },
        modifier = modifier,
    )

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, _ -> /* lib handles */ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            playerViewRef?.release()
            playerViewRef = null
            lifecycleOwner.lifecycle.removeObserver(obs)
        }
    }
}
```

### Task 6 — `ChannelsScreen.kt` HeroBanner aanpassing

**6a.** `HeroCarousel` + `HeroBanner` krijgen `onMoreInfo: (Channel) -> Unit` param.

**6b.** Binnen `HeroBanner` BoxWithConstraints, vóór de scrim Boxes:
```kotlin
val isPoster = channel.type != ContentType.TV
// existing backdrop image
// ...
// trailer overlay (alleen MOVIE en TMDB-token configured)
var trailerKey by remember(channel.id) { mutableStateOf<String?>(null) }
var trailerActive by remember(channel.id) { mutableStateOf(false) }
val app = IptvApp.get()
LaunchedEffect(channel.id) {
    if (channel.type != ContentType.MOVIE) return@LaunchedEffect
    if (!TmdbClient.isConfigured) return@LaunchedEffect
    val bundle = runCatching {
        app.tmdbMovieDetails.lookupMovie(
            channelId = channel.id,
            title = TmdbCatalogueMatcher.normalize(channel.name).ifBlank { channel.name },
            releaseYear = null,
        )
    }.getOrNull()
    trailerKey = bundle?.trailerYoutubeKey
    if (trailerKey != null) {
        kotlinx.coroutines.delay(2500)
        trailerActive = true
    }
}
if (trailerActive && trailerKey != null) {
    HeroTrailerPlayer(
        youtubeKey = trailerKey!!,
        modifier = Modifier.fillMaxSize(),
        onError = { trailerActive = false },
    )
}
```

**6c.** Vervang bestaande Play-knop met `Row { Play; Spacer(12.dp); MoreInfo }`. MoreInfo alleen tonen als `channel.type != ContentType.TV`:
```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Button(onClick = onPlay, modifier = Modifier.focusRequester(activeFocus)) {
        // existing Play content
    }
    if (channel.type != ContentType.TV) {
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = { onMoreInfo(channel) }) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.padding(start = 10.dp).size(22.dp))
            Text(
                stringResource(R.string.hero_more_info),
                modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}
```

**6d.** `strings.xml`: nieuwe key `hero_more_info` = "Meer info".

### Task 7 — ChannelsScreen-route hookup

`ChannelsScreen` heeft al `onOpenMovieDetail` / `onOpenSeriesDetail` callbacks via `MainActivity`. Wire `onMoreInfo`:
```kotlin
onMoreInfo = { ch ->
    when (ch.type) {
        ContentType.MOVIE -> onOpenMovieDetail(ch.id)
        ContentType.SERIES -> onOpenSeriesDetail(ch.id)
        ContentType.TV -> Unit
    }
}
```

## File-list

| File | Type | LOC est. |
|------|------|----------|
| `app/build.gradle.kts` | +1 line dep | 1 |
| `app/src/main/java/nl/vanvrouwerff/iptv/data/tmdb/TmdbHeroPreloader.kt` | NEW | ~40 |
| `app/src/main/java/nl/vanvrouwerff/iptv/IptvApp.kt` | +1 lazy property | 3 |
| `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsViewModel.kt` | +1 warm() call | 3 |
| `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/HeroTrailerPlayer.kt` | NEW | ~55 |
| `app/src/main/java/nl/vanvrouwerff/iptv/ui/channels/ChannelsScreen.kt` | HeroBanner aanpassing + onMoreInfo param | ~50 |
| `app/src/main/res/values/strings.xml` | +1 string | 1 |
| `app/src/main/res/values-nl/strings.xml` | +1 string (als bestaat) | 1 |

## Risico's

- **Compose for TV alpha10:** `OutlinedButton` werkt; check of `androidx.tv.material3` versie ook outlined button heeft of dat we Material3 Button met outline-styling moeten gebruiken.
- **Lifecycle van YouTubePlayerView:** lib verwacht `addObserver(view)` op een `Lifecycle`. Bij hero-rotatie wordt YouTubePlayerView vernietigd; onDispose moet `release()` aanroepen om audio-leak te voorkomen.
- **D-pad focus order:** met 2 knoppen moet focus traversal Play ↔ More Info werken zonder dat de carrousel-rotatie de focus steelt. Bestaande `focusRequester.requestFocus()` op carousel-change moet Play targeten.

## Verificatie (per success-criteria uit ROADMAP)

| # | Criterium | Hoe te testen |
|---|-----------|---------------|
| 1 | Trailer start na 2.5s muted | Op MOVIE-hero blijven, klok kijken |
| 2 | Fallback bij geen trailer | Movie zonder TMDB-match testen (titel met garbage) |
| 3 | "More Info" via D-pad rechts | D-pad rechts vanaf Play |
| 4 | Preload bij rotatie | Logs `cache hit for $channelId` na rotatie |
| 5 | App zonder token werkt | `TMDB_BEARER_TOKEN=` leeg, rebuild, check hero blijft Ken Burns |

**Manueel testen op Formuler Z10 Pro Max + Chromecast HD vereist.** Emulator ontoereikend.

## Dependencies

- Phase 1 staat alleen — geen voorgaande phase nodig.

## Volgende stap

`/gsd-execute-phase 1` of handmatige code-implementatie aan de hand van bovenstaand.
