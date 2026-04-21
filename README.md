# IPTV Player for Android TV

A lightweight, open-source IPTV player for Android TV 10+, built natively in
Kotlin with Compose for TV and Media3/ExoPlayer. Plays live channels, movies
and series from **M3U** playlists and **Xtream Codes** providers, with a
remote-friendly UI, optional EPG, and optional TMDB metadata enrichment.

Originally tuned for the **Formuler Z10 Pro Max**, but runs on any Android TV
device with API 29 or higher.

> **Disclaimers.** This project is an independent community app, **not
> affiliated with or endorsed by Formuler**. "Formuler" and "Z10 Pro Max" are
> trademarks of their respective owners. The app is a media player — it ships
> no channels, no streams and no credentials. You are responsible for the
> legality of the sources you connect to it. Piracy is not supported.

## Features

### Sources
- **M3U / M3U8** playlists (extended format with `tvg-*` and `group-title`).
- **Xtream Codes** accounts (live, VOD, series) via a typed Retrofit client.
- Unified `Channel` model (`TV`, `MOVIE`, `SERIES`) so the rest of the app
  doesn't care where the data came from.
- Local Room cache with ETag / Last-Modified awareness — refreshes fire only
  when the upstream playlist actually changed.
- Manual refresh and optional scheduled nightly refresh via WorkManager.

### Live TV
- Category rails and an "All channels" grid.
- D-pad navigation, Play/Pause, Channel Up/Down, numeric channel entry.
- Audio track and subtitle selection, with adjustable subtitle delay.
- Stream stats overlay (resolution, bitrate, codec, buffer, dropped frames).
- Now/Next from XMLTV EPG when the provider exposes it.

### Movies & Series (VOD)
- Dedicated detail screens for movies and for series (seasons → episodes).
- Resume playback per profile, with a "Continue watching" rail on the home
  screen.
- "My list" favourites and a "Related" rail on detail pages.
- Recent-search history and on-screen voice input (where the device supports
  it).

### Profiles
- Multiple user profiles, each with its own favourites, watch progress and
  recent searches. A `default` profile is seeded so single-user setups need no
  configuration.

### TMDB enrichment (optional)
- Posters, overviews, popularity ranking and a "Popular now" rail.
- Entirely optional — without a TMDB token the feature silently disables and
  the rest of the app keeps working.

### Player
- Media3 / ExoPlayer with HLS, DASH and progressive TS support.
- Adaptive playback with explicit audio-capability detection for the set-top
  box's HDMI audio path.
- Configurable aspect ratio (Fit / Fill / Zoom).
- Remote-friendly overlay panels for audio, subtitles and info, all reachable
  through the coloured remote buttons.

## Requirements

| | |
|---|---|
| Device | Android TV 10+ (API 29), D-pad remote |
| JDK to build | 17 or 21 (AGP 8.5.2 does not support JDK 25) |
| An IPTV source | An M3U URL **or** Xtream Codes credentials |
| TMDB token | Optional — only for posters & metadata |

## Building

Copy `local.properties.example` to `local.properties` and point `sdk.dir` at
your Android SDK (the file is git-ignored, so secrets stay local). Then:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests:

```sh
./gradlew test
```

### TMDB token (optional)

To enable poster and metadata enrichment, get a TMDB **v4 API Read Access
Token** from <https://www.themoviedb.org/settings/api> and add it to
`local.properties` (git-ignored):

```
TMDB_BEARER_TOKEN=eyJhbGciOi...
```

Without a token the app behaves identically except TMDB-powered rails and
artwork are hidden.

## Installing on an Android TV device

1. Enable **Developer options** on the TV (Settings → Device Preferences →
   About → tap Build seven times) and turn on **ADB debugging** under
   Developer options.
2. Note the device IP (Settings → Network).
3. From your dev machine:

   ```sh
   adb connect <tv-ip>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n nl.vanvrouwerff.iptv/.MainActivity
   ```

## First run

On first launch a welcome wizard asks you to pick M3U or Xtream and enter
your source details. The channel list is fetched, cached locally, and from
then on the app opens directly to the home screen.

Useful tips:

- A USB keyboard paired to the TV saves a lot of D-pad typing when entering
  URLs or credentials.
- On-screen text fields also accept `adb shell input text "..."` from a
  connected dev machine.
- Additional profiles can be created under Settings → Profiles.

## Tech stack

- **Kotlin** + **Coroutines** / **Flow**
- **Jetpack Compose** + **Compose for TV** (`androidx.tv.foundation`,
  `androidx.tv.material`)
- **Media3 / ExoPlayer** (HLS, DASH, TS)
- **Room** for the catalogue and watch-progress cache
- **Retrofit** + **kotlinx.serialization** for Xtream and TMDB APIs
- **OkHttp** (shared across Retrofit and Coil)
- **WorkManager** for the scheduled refresh
- **DataStore (Preferences)** for settings
- **Coil** for image loading

The app intentionally avoids dependency-injection frameworks — `IptvApp`
doubles as a small service locator.

## Project structure

```
app/src/main/java/nl/vanvrouwerff/iptv/
  IptvApp.kt                    Application class: service locator, Coil
                                config, refresh scheduler wiring
  MainActivity.kt               Compose root + route host (Splash / Welcome /
                                Channels / Settings / Profiles /
                                MovieDetail / SeriesDetail)
  player/
    PlayerActivity.kt           ExoPlayer + PlayerView, D-pad key handling,
                                resume persistence
    PlayerScreen.kt             Compose layer around PlayerView
  data/
    Channel.kt                  Unified domain model (TV / MOVIE / SERIES)
    m3u/M3uParser.kt            Extended M3U / M3U8 parser (unit-tested)
    xtream/                     Retrofit API + DTOs (live, VOD, series)
    epg/XmltvParser.kt          XMLTV parser for now/next
    tmdb/                       TMDB API client, matchers, popular repo
    remote/HttpClient.kt        Shared OkHttp + Retrofit setup
    db/                         Room entities, DAOs, mappers
    settings/                   DataStore-backed settings + SourceConfig
    repo/                       PlaylistRepository abstraction,
                                refresh use case, scheduler and WorkManager
                                worker
  ui/
    theme/                      Compose theme
    splash/                     Splash screen
    wizard/                     First-run welcome flow
    settings/                   Settings screen + VM
    profiles/                   Profiles screen + VM
    channels/                   Home screen: rails, search, favourites
    detail/                     Movie detail screen + VM
    seriesdetail/               Series detail screen + VM
```

Unit tests live under `app/src/test/`. The M3U parser has the most coverage;
the rest of the codebase leans on manual exploratory testing on the device.

## Known limitations

- **Text entry on a remote is painful.** Use a USB keyboard or
  `adb shell input text`. A leanback-friendly input dialog is not yet
  implemented.
- **Cleartext HTTP is enabled app-wide** because many IPTV providers still
  serve plain HTTP. See
  [`network_security_config.xml`](app/src/main/res/xml/network_security_config.xml).
- **Compose for TV is on `1.0.0-alpha10`.** A known focus-search crash inside
  the episode list is swallowed in `MainActivity.dispatchKeyEvent`; it goes
  away when the Compose BOM can be bumped.
- **TMDB matching is best-effort** based on title and year; uncommon
  releases may stay without artwork.
- **No DRM, no catch-up, no PVR, no Chromecast.** These are deliberately out
  of scope.

## Contributing

Issues and pull requests are welcome. Because this started as a personal
project:

- Keep dependencies light — the service-locator approach is intentional.
- Prefer small, focused PRs with a short description of the user-visible
  behaviour.
- Changes that touch the player or the catalogue refresh logic should be
  tested on real hardware; the emulator does not faithfully reproduce
  Android TV's D-pad focus or HDMI audio behaviour.
- New parser logic should ship with unit tests; see
  `app/src/test/.../M3uParserTest.kt` for the style.

## Acknowledgements

- [Media3 / ExoPlayer](https://developer.android.com/media/media3) for the
  playback stack.
- [The Movie Database (TMDB)](https://www.themoviedb.org/) for metadata and
  artwork — used under their API terms of use. This product uses the TMDB
  API but is not endorsed or certified by TMDB.
- The Android TV and Compose for TV teams for the underlying UI primitives.

## License

Licensed under the [Apache License 2.0](LICENSE). You may use, modify and
distribute this code within the terms of that license.
