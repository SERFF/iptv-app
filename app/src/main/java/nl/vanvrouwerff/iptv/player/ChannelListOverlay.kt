package nl.vanvrouwerff.iptv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyListState
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import nl.vanvrouwerff.iptv.R
import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.ui.theme.FocusStyle
import nl.vanvrouwerff.iptv.ui.theme.IptvPalette
import nl.vanvrouwerff.iptv.ui.theme.tvFocus

/** One zap list in the in-player channel list: favourites or a category. */
data class ChannelGroup(val title: String, val channels: List<Channel>)

/** "Now" programme for a channel row: title plus 0..1 progress through the slot. */
data class NowInfo(val title: String, val progress: Float)

/**
 * Live-TV channel list over the left side of the picture. ◀▶ switches category, ▲▼ moves
 * through the channels, OK zaps and keeps the list open, BACK closes it (handled by the
 * activity). The playing channel is focused when the list opens.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelListOverlay(
    groups: List<ChannelGroup>,
    groupIndex: Int,
    currentChannelId: String?,
    nowByChannelId: Map<String, NowInfo>,
    channelNumberOf: (String) -> Int?,
    onSelectGroup: (Int) -> Unit,
    onZap: (ChannelGroup, Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val group = groups.getOrNull(groupIndex) ?: return
    val startIndex = remember(groupIndex, groups) {
        group.channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
    }
    val listState = remember(groupIndex, groups) { TvLazyListState(startIndex, 0) }
    val focusTarget = remember(groupIndex, groups) { FocusRequester() }
    LaunchedEffect(groupIndex, groups) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { focusTarget.requestFocus() }
    }

    Column(
        modifier = modifier
            .width(PANEL_WIDTH)
            .fillMaxHeight()
            .background(IptvPalette.BackgroundDeep.copy(alpha = 0.92f))
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onSelectGroup((groupIndex - 1 + groups.size) % groups.size)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSelectGroup((groupIndex + 1) % groups.size)
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "◀",
                style = MaterialTheme.typography.titleMedium,
                color = IptvPalette.TextTertiary,
            )
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = IptvPalette.TextPrimary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(
                text = "▶",
                style = MaterialTheme.typography.titleMedium,
                color = IptvPalette.TextTertiary,
            )
        }
        Text(
            text = stringResource(R.string.channel_list_count, groupIndex + 1, groups.size, group.channels.size),
            style = MaterialTheme.typography.labelSmall,
            color = IptvPalette.TextTertiary,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        TvLazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(group.channels, key = { _, ch -> ch.id }) { index, ch ->
                ChannelListItem(
                    channel = ch,
                    number = channelNumberOf(ch.id),
                    now = nowByChannelId[ch.id],
                    playing = ch.id == currentChannelId,
                    modifier = if (index == startIndex) Modifier.focusRequester(focusTarget) else Modifier,
                    onClick = { onZap(group, ch) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.channel_list_hint),
            style = MaterialTheme.typography.labelSmall,
            color = IptvPalette.TextSecondary,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelListItem(
    channel: Channel,
    number: Int?,
    now: NowInfo?,
    playing: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (playing) IptvPalette.AccentDeep.copy(alpha = 0.55f) else Color.Transparent,
            contentColor = IptvPalette.TextPrimary,
            focusedContainerColor = FocusStyle.Fill,
            focusedContentColor = IptvPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(focused, shape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = number?.let { "%03d".format(it) }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = IptvPalette.TextTertiary,
                modifier = Modifier.width(36.dp),
            )
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(IptvPalette.SurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.padding(3.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (focused || playing) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (now != null) {
                    Text(
                        text = now.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = IptvPalette.AccentSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(IptvPalette.SurfaceElevated),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(now.progress.coerceIn(0f, 1f))
                                .height(2.dp)
                                .background(IptvPalette.Accent),
                        )
                    }
                }
            }
        }
    }
}

private val PANEL_WIDTH = 420.dp
