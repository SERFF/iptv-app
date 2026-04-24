package nl.vanvrouwerff.iptv.ui.detail

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
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
    onBack: () -> Unit,
    onPlay: (Channel, resumeMs: Long) -> Unit,
    onPickRelated: (Channel) -> Unit = {},
    vm: MovieDetailViewModel = viewModel(),
) {
    LaunchedEffect(channelId) { vm.load(channelId) }
    val state by vm.state.collectAsState()

    BackHandler(enabled = true, onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IptvPalette.BackgroundDeep),
    ) {
        when {
            state.loading -> CenterMessage(stringResource(R.string.detail_loading))
            state.channel == null -> CenterMessage(stringResource(R.string.detail_not_found))
            else -> DetailBody(
                channel = state.channel!!,
                state = state,
                onPlay = { resumeMs -> onPlay(state.channel!!, resumeMs) },
                onToggleFavorite = vm::toggleFavorite,
                onBack = onBack,
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
    onBack: () -> Unit,
    onPickRelated: (Channel) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    // Only focus Play on the first composition of this detail. Re-focusing on every state
    // update (e.g. when VOD info arrives) would yank the user back mid-scroll.
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    // Ken Burns on the backdrop — slow, cinematic zoom that loops forever. A 14s cycle at
    // 6 % peak scale is imperceptible frame-to-frame but very much felt over the time a
    // user reads the synopsis.
    val kenBurns = rememberInfiniteTransition(label = "detail-ken-burns")
    val backdropScale by kenBurns.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "detail-ken-burns-scale",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop — full-bleed cover with a side-to-side gradient for legibility.
        if (channel.logoUrl != null) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(backdropScale),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(IptvPalette.AccentDeep, IptvPalette.BackgroundDeep)),
                ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to IptvPalette.BackgroundDeep.copy(alpha = 0.95f),
                        0.5f to IptvPalette.BackgroundDeep.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to IptvPalette.BackgroundDeep,
                    ),
                ),
        )

        Row(modifier = Modifier.fillMaxSize()) {
            // Box + BottomStart pins the content block to the bottom of the left pane.
            // When plot/credits push the block taller than the screen, the overflow goes
            // upward (title/meta get clipped off the top) instead of downward — this keeps
            // Play / Favorite / Back and the related rail always reachable.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 64.dp, end = 48.dp, top = 72.dp, bottom = 56.dp),
            ) {
                channel.groupTitle?.let {
                    Text(
                        text = it.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = IptvPalette.TextTertiary,
                            letterSpacing = 3.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = IptvPalette.TextPrimary,
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                val metaParts = listOfNotNull(
                    state.releaseYear,
                    state.rating?.let { "\u2605 $it" },
                    state.durationLabel,
                    state.genre,
                    state.country,
                )
                if (metaParts.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = metaParts.joinToString("  \u00B7  "),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = IptvPalette.TextTertiary,
                            letterSpacing = 2.sp,
                        ),
                    )
                }

                state.plot?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = IptvPalette.TextSecondary,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val credits = listOfNotNull(
                    state.director?.let { "Regie: $it" },
                    state.cast?.let { "Cast: $it" },
                )
                if (credits.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    credits.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = IptvPalette.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (state.hasProgress && !state.watched) {
                    Spacer(Modifier.height(16.dp))
                    ResumeIndicator(state.positionMs, state.durationMs, state.progressFraction)
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.padding(bottom = if (state.related.isNotEmpty()) 24.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            onPlay(if (state.hasProgress && !state.watched) state.positionMs else 0L)
                        },
                        modifier = Modifier.focusRequester(playFocus),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.icon_desc_play),
                            modifier = Modifier.padding(start = 12.dp).size(22.dp),
                        )
                        Text(
                            text = if (state.hasProgress && !state.watched)
                                stringResource(R.string.detail_resume)
                            else
                                stringResource(R.string.detail_play),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                    Button(onClick = onToggleFavorite) {
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
                            text = label,
                            modifier = Modifier.padding(start = 8.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
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

                if (state.related.isNotEmpty() && channel.groupTitle != null) {
                    RelatedRail(
                        groupTitle = channel.groupTitle,
                        channels = state.related,
                        onPick = onPickRelated,
                    )
                }
            }
            }

            if (channel.logoUrl != null) {
                Box(
                    modifier = Modifier
                        .padding(48.dp)
                        .size(width = 320.dp, height = 480.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(IptvPalette.SurfaceLift)
                        .align(Alignment.CenterVertically),
                ) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RelatedRail(
    groupTitle: String,
    channels: List<Channel>,
    onPick: (Channel) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.rail_related, groupTitle),
            style = MaterialTheme.typography.labelLarge.copy(
                color = IptvPalette.TextSecondary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
        )
        Spacer(Modifier.height(10.dp))
        TvLazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(channels, key = { it.id }) { ch ->
                RelatedCard(channel = ch, onClick = { onPick(ch) })
            }
        }
    }
}

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
