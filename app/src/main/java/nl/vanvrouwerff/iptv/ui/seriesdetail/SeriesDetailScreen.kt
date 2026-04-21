package nl.vanvrouwerff.iptv.ui.seriesdetail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    onBack: () -> Unit,
    onPlayEpisode: (episode: Episode, season: SeriesSeason, resumeMs: Long) -> Unit,
    vm: SeriesDetailViewModel = viewModel(),
) {
    LaunchedEffect(seriesId) { vm.load(seriesId) }
    val state by vm.state.collectAsState()

    BackHandler(enabled = true, onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep),
    ) {
        Backdrop(cover = state.cover)

        when {
            state.loading -> CenterMessage(stringResource(R.string.detail_loading))
            state.error != null && state.seasons.isEmpty() ->
                CenterMessage(state.error ?: stringResource(R.string.series_load_failed))
            else -> DetailBody(
                state = state,
                onSelectSeason = vm::selectSeason,
                onToggleFavorite = vm::toggleFavorite,
                onBack = onBack,
                onPlayEpisode = { ep, season, resumeMs ->
                    // Cache episode metadata BEFORE starting playback so the "Continue
                    // watching" rail has something to render even if the user closes the
                    // app mid-episode.
                    vm.rememberForContinueWatching(ep)
                    onPlayEpisode(ep, season, resumeMs)
                },
            )
        }
    }
}

@Composable
private fun Backdrop(cover: String?) {
    if (cover != null) {
        AsyncImage(
            model = cover,
            contentDescription = null, // decorative; poster on the left carries the label
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(IptvPalette.AccentDeep, IptvPalette.BackgroundDeep),
                ),
            ),
        )
    }
    // Global dimmer so text over any cover stays legible.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.78f)),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailBody(
    state: SeriesDetailState,
    onSelectSeason: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onPlayEpisode: (Episode, SeriesSeason, Long) -> Unit,
) {
    // Grab focus on first composition so D-pad stays inside this overlay instead of
    // leaking to the ChannelsScreen rendered underneath. Without this, pressing OK lands
    // on whichever card was focused before the overlay opened.
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }

    Row(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        // Left: meta + cover.
        Column(
            modifier = Modifier.width(360.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.Top,
        ) {
            if (state.cover != null) {
                Box(
                    modifier = Modifier
                        .size(width = 200.dp, height = 300.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(IptvPalette.SurfaceLift),
                ) {
                    AsyncImage(
                        model = state.cover,
                        contentDescription = state.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = IptvPalette.TextPrimary,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val metaLine = listOfNotNull(
                state.genre,
                state.rating?.let { "★ $it" },
            ).joinToString("  ·  ")
            if (metaLine.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = IptvPalette.TextTertiary,
                        letterSpacing = 2.sp,
                    ),
                )
            }

            // Action buttons come BEFORE the plot. On a 540dp-tall TV viewport the poster
            // alone would push them below the fold otherwise — the favorite toggle needs
            // to be reliably visible (and focusable) regardless of plot length.
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onToggleFavorite,
                    modifier = Modifier.focusRequester(initialFocus),
                ) {
                    val label = if (state.isFavorite)
                        stringResource(R.string.detail_remove_from_list)
                    else
                        stringResource(R.string.detail_add_to_list)
                    Icon(
                        imageVector = if (state.isFavorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (state.isFavorite)
                            stringResource(R.string.icon_desc_bookmark_remove)
                        else
                            stringResource(R.string.icon_desc_bookmark_add),
                        modifier = Modifier.padding(start = 10.dp).size(20.dp),
                    )
                    Text(
                        label,
                        modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
                Button(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.icon_desc_back),
                        modifier = Modifier.padding(start = 10.dp).size(20.dp),
                    )
                    Text(
                        stringResource(R.string.detail_back),
                        modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }

            state.plot?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = IptvPalette.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(32.dp))

        // Right: seasons + episodes.
        Column(modifier = Modifier.fillMaxSize()) {
            SeasonRow(
                seasons = state.seasons,
                selected = state.selectedSeasonNumber,
                onSelect = onSelectSeason,
            )

            Spacer(Modifier.height(16.dp))

            val season = state.selectedSeason
            val episodes = season?.episodes.orEmpty()

            if (season == null || episodes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.series_no_episodes),
                        style = MaterialTheme.typography.titleMedium,
                        color = IptvPalette.TextSecondary,
                    )
                }
            } else {
                val activeSeason: SeriesSeason = season
                TvLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    items(episodes, key = { it.id }) { ep ->
                        EpisodeRow(
                            episode = ep,
                            onClick = { resumeMs -> onPlayEpisode(ep, activeSeason, resumeMs) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonRow(
    seasons: List<SeriesSeason>,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    if (seasons.isEmpty()) return
    TvLazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasons, key = { it.number }) { season ->
            SeasonChip(
                label = season.label,
                selected = season.number == selected,
                onClick = { onSelect(season.number) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) IptvPalette.Accent else Color.Transparent,
            contentColor = if (selected) Color.White else IptvPalette.TextSecondary,
            focusedContainerColor = if (selected) IptvPalette.Accent else IptvPalette.SurfaceElevated,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeRow(episode: Episode, onClick: (resumeMs: Long) -> Unit) {
    // Read per-episode progress from Room so we can show a resume hint + progress bar.
    // Scoped to the active profile — progress doesn't leak across profile switches.
    val app = nl.vanvrouwerff.iptv.IptvApp.get()
    val dao = app.database.channelDao()
    val profileId by app.activeProfileId.collectAsState()
    val progress by remember(episode.id, profileId) {
        dao.observeProgress(profileId, episode.id)
    }.collectAsState(initial = null)
    val positionMs = progress?.positionMs ?: 0L
    val durationMs = progress?.durationMs ?: 0L
    val hasProgress = positionMs > 0L && durationMs > 0L
    val fraction = if (hasProgress) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val watched = hasProgress && fraction >= WATCHED_FRACTION

    Surface(
        // If the user already finished the episode, resume at 0 instead of bouncing to
        // the credits roll.
        onClick = { onClick(if (hasProgress && !watched) positionMs else 0L) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = IptvPalette.SurfaceLift,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.series_episode_prefix, episode.episodeNumber),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = IptvPalette.TextTertiary,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.width(44.dp),
                )
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (watched) {
                    Text(
                        text = stringResource(R.string.episode_watched),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = IptvPalette.AccentSoft,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                if (episode.durationSecs > 0) {
                    Text(
                        text = formatSecs(episode.durationSecs),
                        style = MaterialTheme.typography.labelSmall,
                        color = IptvPalette.TextTertiary,
                    )
                }
            }
            episode.plot?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = IptvPalette.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasProgress && !watched) {
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = IptvPalette.Accent,
                    trackColor = IptvPalette.SurfaceLift,
                )
            }
        }
    }
}

/** Fraction above which a saved resume position is treated as "watched". */
private const val WATCHED_FRACTION = 0.95f

private fun formatSecs(secs: Long): String {
    if (secs <= 0) return ""
    val h = secs / 3600
    val m = (secs % 3600) / 60
    return if (h > 0) "${h}u ${m}m" else "${m}m"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CenterMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = IptvPalette.TextSecondary,
        )
    }
}
