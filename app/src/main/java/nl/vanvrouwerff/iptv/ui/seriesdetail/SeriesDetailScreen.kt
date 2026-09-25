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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.platform.LocalContext
import nl.vanvrouwerff.iptv.ui.detail.FocusableTextBlock
import nl.vanvrouwerff.iptv.ui.detail.detailSection
import nl.vanvrouwerff.iptv.ui.detail.DetailScaffold
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import nl.vanvrouwerff.iptv.ui.theme.tvFocus
import nl.vanvrouwerff.iptv.ui.theme.FocusStyle

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    preview: Channel? = null,
    onBack: () -> Unit,
    onPlayEpisode: (episode: Episode, season: SeriesSeason, series: SeriesRef, resumeMs: Long) -> Unit,
    vm: SeriesDetailViewModel = viewModel(),
) {
    LaunchedEffect(seriesId) { vm.load(seriesId, preview) }
    val state by vm.state.collectAsState()

    BackHandler(enabled = true, onBack = onBack)

    DetailBody(
        state = state,
        onSelectSeason = vm::selectSeason,
        onToggleFavorite = vm::toggleFavorite,
        onPlayEpisode = { ep, season, resumeMs ->
            val seriesChannelId = state.seriesChannelId ?: return@DetailBody
            onPlayEpisode(ep, season, SeriesRef(seriesChannelId, state.title, state.cover), resumeMs)
        },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailBody(
    state: SeriesDetailState,
    onSelectSeason: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayEpisode: (Episode, SeriesSeason, Long) -> Unit,
) {
    // The play button exists from the first frame (as a placeholder while episodes load),
    // so focus lands on it once and never has to jump when the data arrives.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }
    val context = LocalContext.current

    val meta = listOfNotNull(
        state.releaseYear,
        state.rating?.let { "\u2605 $it" },
        state.genre,
    ).joinToString("  \u00B7  ")

    DetailScaffold(
        backdropUrl = state.cover,
        eyebrow = null,
        title = state.title,
        meta = meta,
        actions = {
            val next = state.nextUp
            Button(
                onClick = { if (next != null) onPlayEpisode(next.episode, next.season, next.resumeMs) },
                modifier = Modifier.focusRequester(playFocus),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 10.dp).size(22.dp),
                )
                Text(
                    text = when {
                        next != null -> stringResource(
                            if (next.isResume) R.string.series_continue_episode else R.string.series_play_episode,
                            next.episode.seasonNumber,
                            next.episode.episodeNumber,
                        )
                        state.loading -> stringResource(R.string.detail_loading)
                        else -> stringResource(R.string.series_no_episodes)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 8.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            nl.vanvrouwerff.iptv.ui.detail.DetailActionButton(
                icon = if (state.isFavorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                label = stringResource(R.string.rail_my_list),
                contentDescription = stringResource(
                    if (state.isFavorite) R.string.detail_remove_from_list else R.string.detail_add_to_list,
                ),
                onClick = onToggleFavorite,
            )
        },
    ) {
        state.plot?.takeIf { it.isNotBlank() }?.let { plot ->
            detailSection(key = "plot", title = context.getString(R.string.detail_section_overview)) {
                FocusableTextBlock(text = plot)
            }
        }
        detailSection(key = "episodes", title = context.getString(R.string.series_section_episodes)) {
            EpisodesBlock(
                state = state,
                onSelectSeason = onSelectSeason,
                onPlayEpisode = onPlayEpisode,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodesBlock(
    state: SeriesDetailState,
    onSelectSeason: (Int) -> Unit,
    onPlayEpisode: (Episode, SeriesSeason, Long) -> Unit,
) {
    when {
        state.loading -> {
            Text(
                text = stringResource(R.string.detail_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = IptvPalette.TextSecondary,
            )
            return
        }
        state.error != null && state.seasons.isEmpty() -> {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = IptvPalette.TextSecondary,
            )
            return
        }
    }
    SeasonRow(
        seasons = state.seasons,
        selected = state.selectedSeasonNumber,
        watchedCountBySeason = state.watchedCountBySeason,
        onSelect = onSelectSeason,
    )
    val season = state.selectedSeason
    val episodes = season?.episodes.orEmpty()
    if (season != null && episodes.isNotEmpty()) {
        val watched = state.watchedCountBySeason[season.number] ?: 0
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.season_progress, watched, episodes.size),
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.TextTertiary,
                letterSpacing = 1.sp,
            ),
        )
    }
    Spacer(Modifier.height(10.dp))
    if (season == null || episodes.isEmpty()) {
        Text(
            text = stringResource(R.string.series_no_episodes),
            style = MaterialTheme.typography.bodyMedium,
            color = IptvPalette.TextSecondary,
        )
        return
    }
    val activeSeason: SeriesSeason = season
    val episodeListState = remember(activeSeason.number) {
        androidx.tv.foundation.lazy.list.TvLazyListState()
    }
    val nextUpIndex = state.nextUp
        ?.takeIf { it.season.number == activeSeason.number }
        ?.let { n -> episodes.indexOfFirst { it.id == n.episode.id } }
        ?: -1
    LaunchedEffect(activeSeason.number, nextUpIndex) {
        if (nextUpIndex >= 0) runCatching { episodeListState.scrollToItem(nextUpIndex) }
    }
    // Fixed-height inner list: nested inside the page's lazy column, and tall enough to
    // show several episodes at once on a 540dp screen.
    TvLazyColumn(
        state = episodeListState,
        modifier = Modifier.fillMaxWidth().height(EPISODE_LIST_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
    ) {
        items(episodes, key = { it.id }) { ep ->
            EpisodeRow(
                episode = ep,
                onClick = { resumeMs -> onPlayEpisode(ep, activeSeason, resumeMs) },
            )
        }
    }
}

private val EPISODE_LIST_HEIGHT = 380.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonRow(
    seasons: List<SeriesSeason>,
    selected: Int?,
    watchedCountBySeason: Map<Int, Int>,
    onSelect: (Int) -> Unit,
) {
    if (seasons.isEmpty()) return
    TvLazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasons, key = { it.number }) { season ->
            val watched = watchedCountBySeason[season.number] ?: 0
            val total = season.episodes.size
            SeasonChip(
                label = season.label,
                selected = season.number == selected,
                // Show a tiny dot + count only when partially watched. A fully-watched
                // season gets a ✓; a completely unwatched season gets nothing extra so
                // the chip stays light.
                trailing = when {
                    total == 0 -> null
                    watched >= total -> "\u2713"
                    watched > 0 -> "$watched/$total"
                    else -> null
                },
                onClick = { onSelect(season.number) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonChip(
    label: String,
    selected: Boolean,
    trailing: String?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) IptvPalette.Accent else Color.Transparent,
            contentColor = if (selected) Color.White else IptvPalette.TextSecondary,
            focusedContainerColor = if (selected) IptvPalette.Accent else FocusStyle.Fill,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape, FocusStyle.ChipScale),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) Color.White else IptvPalette.AccentSoft,
                    ),
                )
            }
        }
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

    var rowFocused by remember { mutableStateOf(false) }
    val episodeShape = RoundedCornerShape(10.dp)
    Surface(
        // If the user already finished the episode, resume at 0 instead of bouncing to
        // the credits roll.
        onClick = { onClick(if (hasProgress && !watched) positionMs else 0L) },
        shape = ClickableSurfaceDefaults.shape(episodeShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = FocusStyle.Fill,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { rowFocused = it.isFocused }
            .tvFocus(rowFocused, episodeShape),
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
            episode.airDate?.let { date ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.episode_airdate, date),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = IptvPalette.TextTertiary,
                        letterSpacing = 1.sp,
                    ),
                )
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
