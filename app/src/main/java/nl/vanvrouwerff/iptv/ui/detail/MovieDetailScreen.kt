package nl.vanvrouwerff.iptv.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    channelId: String,
    preview: Channel? = null,
    onBack: () -> Unit,
    onPlay: (Channel, resumeMs: Long) -> Unit,
    onPickRelated: (Channel) -> Unit = {},
    vm: MovieDetailViewModel = viewModel(),
) {
    LaunchedEffect(channelId) { vm.load(channelId, preview) }
    val state by vm.state.collectAsState()

    BackHandler(enabled = true, onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep),
    ) {
        val channel = state.channel ?: preview?.takeIf { it.id == channelId }
        when {
            channel == null && state.loading -> Unit
            channel == null -> CenterMessage(stringResource(R.string.detail_not_found))
            else -> DetailBody(
                channel = channel,
                state = state,
                onPlay = { resumeMs -> state.channel?.let { onPlay(it, resumeMs) } },
                onToggleFavorite = vm::toggleFavorite,
                onPickRelated = onPickRelated,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailBody(
    channel: Channel,
    state: MovieDetailState,
    onPlay: (resumeMs: Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onPickRelated: (Channel) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    // Only focus Play on the first composition of this detail. Re-focusing on every state
    // update (e.g. when VOD info arrives) would yank the user back mid-scroll.
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    val context = LocalContext.current
    val resumable = state.hasProgress && !state.watched

    val meta = listOfNotNull(
        state.releaseYear,
        state.rating?.let { "\u2605 $it" },
        state.durationLabel,
        state.genre,
        state.country,
    ).joinToString("  \u00B7  ")

    DetailScaffold(
        backdropUrl = state.backdropUrl ?: channel.logoUrl,
        eyebrow = channel.groupTitle,
        title = channel.name,
        meta = meta,
        headerExtra = {
            if (resumable) {
                Spacer(Modifier.height(12.dp))
                ResumeIndicator(state.positionMs, state.durationMs, state.progressFraction)
            }
        },
        actions = {
            Button(
                onClick = { onPlay(if (resumable) state.positionMs else 0L) },
                modifier = Modifier.focusRequester(playFocus),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.icon_desc_play),
                    modifier = Modifier.padding(start = 12.dp).size(22.dp),
                )
                Text(
                    text = stringResource(if (resumable) R.string.detail_resume else R.string.detail_play),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            if (resumable) {
                DetailActionButton(
                    icon = Icons.Filled.Replay,
                    label = stringResource(R.string.detail_play_from_start),
                    onClick = { onPlay(0L) },
                )
            }
            state.trailerUrl?.let { url ->
                DetailActionButton(
                    icon = Icons.Filled.OndemandVideo,
                    label = stringResource(R.string.detail_trailer),
                    onClick = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    },
                )
            }
            DetailActionButton(
                icon = if (state.isFavorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                label = stringResource(R.string.rail_my_list),
                contentDescription = stringResource(
                    if (state.isFavorite) R.string.detail_remove_from_list else R.string.detail_add_to_list,
                ),
                onClick = onToggleFavorite,
            )
        },
    ) {
        val credits = listOfNotNull(
            state.director?.let { "Regie: $it" },
            state.cast?.let { "Cast: $it" },
        )
        state.plot?.takeIf { it.isNotBlank() }?.let { plot ->
            detailSection(key = "plot", title = context.getString(R.string.detail_section_overview)) {
                FocusableTextBlock(text = plot, footer = credits)
            }
        }
        if (state.castList.isNotEmpty()) {
            detailSection(key = "cast", title = context.getString(R.string.rail_cast)) {
                TvLazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.castList, key = { it.id }) { member -> CastAvatar(member) }
                }
            }
        }
        when {
            state.similar.isNotEmpty() -> detailSection(
                key = "similar",
                title = context.getString(R.string.rail_more_like_this),
            ) {
                TvLazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.similar, key = { it.id }) { ch ->
                        RelatedCard(channel = ch, onClick = { onPickRelated(ch) })
                    }
                }
            }
            state.related.isNotEmpty() && channel.groupTitle != null -> detailSection(
                key = "related",
                title = context.getString(R.string.rail_related, channel.groupTitle),
            ) {
                TvLazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.related, key = { it.id }) { ch ->
                        RelatedCard(channel = ch, onClick = { onPickRelated(ch) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DetailActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    contentDescription: String = label,
) {
    Button(
        onClick = onClick,
        colors = androidx.tv.material3.ButtonDefaults.colors(
            containerColor = IptvPalette.SurfaceElevated.copy(alpha = 0.7f),
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = IptvPalette.TextPrimary,
            focusedContentColor = IptvPalette.BackgroundDeep,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.padding(start = 8.dp).size(20.dp),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastAvatar(member: nl.vanvrouwerff.iptv.data.tmdb.TmdbMovieDetailsRepository.CastEntry) {
    Column(
        modifier = Modifier.width(92.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(IptvPalette.SurfaceLift),
            contentAlignment = Alignment.Center,
        ) {
            val avatarUrl = member.profilePath?.let { TMDB_PROFILE_BASE + it }
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = IptvPalette.TextTertiary,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = member.name,
            style = MaterialTheme.typography.labelSmall.copy(
                color = IptvPalette.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        member.character?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = IptvPalette.TextTertiary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** TMDB CDN base for the circular 185-wide profile thumbnail. */
private const val TMDB_PROFILE_BASE = "https://image.tmdb.org/t/p/w185"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RelatedCard(channel: Channel, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = IptvPalette.SurfaceLift,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = IptvPalette.SurfaceLift,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = Modifier.size(width = 132.dp, height = 200.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.65f to Color.Transparent,
                            1f to IptvPalette.BackgroundDeep.copy(alpha = 0.9f),
                        ),
                    ),
            )
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = IptvPalette.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ResumeIndicator(positionMs: Long, durationMs: Long, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth(0.6f)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = IptvPalette.Accent,
            trackColor = IptvPalette.SurfaceElevated,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.detail_resume_remaining,
                formatRemaining(durationMs - positionMs),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = IptvPalette.TextSecondary,
        )
    }
}

private fun formatRemaining(remainingMs: Long): String {
    val totalMinutes = (remainingMs / 60_000).coerceAtLeast(0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h >= 1 && m > 0 -> "${h}u ${m}min"
        h >= 1 -> "${h}u"
        else -> "${m}min"
    }
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
